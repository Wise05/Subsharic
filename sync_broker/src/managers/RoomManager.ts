import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';
import type { PlaybackState, QueueEntry } from '../models/Room.ts';
import type { RoomCommand } from '../models/Messages.ts';
import type { Track } from '../models/Track.ts';

export interface RoomMember {
  clientId: string;
  socket: WebSocket;
  /** Server time when this peer joined this room; reconnects start a new tenure. */
  joinedAt: number;
}

export interface Room {
  id: string;
  hostId: string;
  members: Map<string, RoomMember>;
  state: PlaybackState | null;
  queue: QueueEntry[];
}

type ClientSocket = { clientId: string; socket: WebSocket };

/** In-memory room and membership store. Room state is lost when the process exits. */
export class RoomManager {
  private readonly rooms = new Map<string, Room>();
  private readonly roomByClient = new Map<string, string>();
  private readonly now: () => number;

  constructor(now: () => number = Date.now) {
    this.now = now;
  }

  handle(client: ClientSocket, command: RoomCommand): void {
    switch (command.type) {
      case 'CREATE_ROOM':
        this.createRoom(client);
        return;
      case 'JOIN_ROOM':
        this.joinRoom(client, command.roomId);
        return;
      case 'LEAVE_ROOM':
        this.leaveRoom(client.clientId);
        return;
      case 'BROADCAST_STATE':
        this.broadcastState(client, command.state);
        return;
      case 'ADD_TO_QUEUE':
        this.addToQueue(client, command.track);
        return;
      case 'REMOVE_FROM_QUEUE':
        this.removeFromQueue(client, command.queueId);
        return;
      case 'REORDER_QUEUE':
        this.reorderQueue(client, command.queueIds);
        return;
    }
  }

  /** Remove a disconnected socket from its room. */
  disconnect(clientId: string): void {
    this.leaveRoom(clientId);
  }

  private createRoom(client: ClientSocket): void {
    this.leaveRoom(client.clientId);
    const room: Room = {
      id: randomUUID(),
      hostId: client.clientId,
      members: new Map([[client.clientId, { ...client, joinedAt: this.now() }]]),
      state: null,
      queue: [],
    };
    this.rooms.set(room.id, room);
    this.roomByClient.set(client.clientId, room.id);

    this.send(client.socket, { type: 'ROOM_CREATED', roomId: room.id, hostId: room.hostId, clientId: client.clientId, queue: room.queue });
  }

  private joinRoom(client: ClientSocket, roomId: string): void {
    const room = this.rooms.get(roomId);
    if (!room) {
      this.sendError(client.socket, 'ROOM_NOT_FOUND', 'That room does not exist.');
      return;
    }

    const currentRoomId = this.roomByClient.get(client.clientId);
    if (currentRoomId === room.id && room.members.has(client.clientId)) {
      this.send(client.socket, {
        type: 'ROOM_JOINED',
        roomId: room.id,
        hostId: room.hostId,
        clientId: client.clientId,
        state: room.state,
        queue: room.queue,
      });
      return;
    }
    if (currentRoomId) this.leaveRoom(client.clientId);

    room.members.set(client.clientId, { ...client, joinedAt: this.now() });
    this.roomByClient.set(client.clientId, room.id);
    this.send(client.socket, {
      type: 'ROOM_JOINED',
      roomId: room.id,
      hostId: room.hostId,
      clientId: client.clientId,
      state: room.state,
      queue: room.queue,
    });
    this.broadcast(room, {
      type: 'MEMBER_JOINED',
      roomId: room.id,
      clientId: client.clientId,
    }, client.clientId);
  }

  private leaveRoom(clientId: string): void {
    const roomId = this.roomByClient.get(clientId);
    if (!roomId) return;

    const room = this.rooms.get(roomId);
    const member = room?.members.get(clientId);
    this.roomByClient.delete(clientId);
    if (!room) return;

    room.members.delete(clientId);
    if (member) this.send(member.socket, { type: 'ROOM_LEFT', roomId: room.id });
    if (room.hostId === clientId) {
      const nextMember = [...room.members.values()].sort((a, b) => a.joinedAt - b.joinedAt)[0];
      if (nextMember) room.hostId = nextMember.clientId;
    }
    if (room.members.size === 0) {
      this.rooms.delete(room.id);
      return;
    }

    this.broadcast(room, {
      type: 'MEMBER_LEFT',
      roomId: room.id,
      clientId,
      hostId: room.hostId,
    });
  }

  private broadcastState(client: ClientSocket, state: PlaybackState): void {
    const roomId = this.roomByClient.get(client.clientId);
    const room = roomId ? this.rooms.get(roomId) : undefined;
    if (!room || !room.members.has(client.clientId)) {
      this.sendError(client.socket, 'NOT_IN_ROOM', 'Join a room before changing its state.');
      return;
    }
    if (room.hostId !== client.clientId) {
      this.sendError(client.socket, 'NOT_HOST', 'Only the host can update room state.');
      return;
    }
    
    // updatedAt is a broker epoch timestamp paired with this playhead position.
    // Never trust client wall clocks for the shared timeline anchor.
    room.state = { ...state, updatedAt: this.now() };
    this.broadcast(room, { type: 'STATE_UPDATED', roomId: room.id, state: room.state });
  }

  private addToQueue(client: ClientSocket, track: Track): void {
    const room = this.getMemberRoom(client);
    if (!room) return;
    room.queue.push({ queueId: randomUUID(), track });
    console.log(`Track added to room ${room.id}; queue now has ${room.queue.length} tracks`);
    this.broadcast(room, { type: 'QUEUE_UPDATED', roomId: room.id, queue: room.queue });
  }

  private removeFromQueue(client: ClientSocket, queueId: string): void {
    const room = this.getHostRoom(client);
    if (!room) return;
    const entryIndex = room.queue.findIndex((entry) => entry.queueId === queueId);
    if (entryIndex < 0) {
      this.sendError(client.socket, 'QUEUE_ENTRY_NOT_FOUND', 'That queue entry no longer exists.');
      return;
    }
    room.queue.splice(entryIndex, 1);
    this.broadcast(room, { type: 'QUEUE_UPDATED', roomId: room.id, queue: room.queue });
  }

  private reorderQueue(client: ClientSocket, queueIds: string[]): void {
    const room = this.getHostRoom(client);
    if (!room) return;
    const currentIds = room.queue.map((entry) => entry.queueId);
    if (queueIds.length !== currentIds.length
      || new Set(queueIds).size !== currentIds.length
      || currentIds.some((queueId) => !queueIds.includes(queueId))) {
      this.sendError(client.socket, 'INVALID_QUEUE_ORDER', 'Queue order must contain every current entry exactly once.');
      return;
    }
    const entriesById = new Map(room.queue.map((entry) => [entry.queueId, entry]));
    room.queue = queueIds.map((queueId) => entriesById.get(queueId)!);
    this.broadcast(room, { type: 'QUEUE_UPDATED', roomId: room.id, queue: room.queue });
  }

  private getMemberRoom(client: ClientSocket): Room | null {
    const roomId = this.roomByClient.get(client.clientId);
    const room = roomId ? this.rooms.get(roomId) : undefined;
    if (!room || !room.members.has(client.clientId)) {
      this.sendError(client.socket, 'NOT_IN_ROOM', 'Join a room before changing its queue.');
      return null;
    }
    return room;
  }

  private getHostRoom(client: ClientSocket): Room | null {
    const room = this.getMemberRoom(client);
    if (!room) return null;
    if (room.hostId !== client.clientId) {
      this.sendError(client.socket, 'NOT_HOST', 'Only the host can reorder or remove queue entries.');
      return null;
    }
    return room;
  }

  private broadcast(room: Room, payload: unknown, exceptClientId?: string): void {
    for (const [clientId, member] of room.members) {
      if (clientId !== exceptClientId) this.send(member.socket, payload);
    }
  }

  private sendError(socket: WebSocket, code: string, message: string): void {
    this.send(socket, { type: 'ERROR', code, message });
  }

  private send(socket: WebSocket, payload: unknown): void {
    if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(payload));
  }
}
