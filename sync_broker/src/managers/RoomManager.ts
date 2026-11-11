import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';
import { PlaybackState } from '../models/Room';
import { RoomCommand } from '../models/Messages';

export interface RoomMember {
  clientId: string;
  socket: WebSocket;
}

export interface Room {
  id: string;
  hostId: string;
  members: Map<string, RoomMember>;
  state: PlaybackState | null;
}

type ClientSocket = { clientId: string; socket: WebSocket };

/** In-memory room and membership store. Room state is lost when the process exits. */
export class RoomManager {
  private readonly rooms = new Map<string, Room>();
  private readonly roomByClient = new Map<string, string>();

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
      members: new Map([[client.clientId, { ...client }]]),
      state: null,
    };
    this.rooms.set(room.id, room);
    this.roomByClient.set(client.clientId, room.id);

    this.send(client.socket, { type: 'ROOM_CREATED', roomId: room.id });
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
        state: room.state,
      });
      return;
    }
    if (currentRoomId) this.leaveRoom(client.clientId);

    room.members.set(client.clientId, { ...client });
    this.roomByClient.set(client.clientId, room.id);
    this.send(client.socket, {
      type: 'ROOM_JOINED',
      roomId: room.id,
      hostId: room.hostId,
      state: room.state,
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
      const nextMember = room.members.keys().next().value as string | undefined;
      if (nextMember) room.hostId = nextMember;
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
    
    room.state = state;
    this.broadcast(room, { type: 'STATE_UPDATED', roomId: room.id, state });
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
