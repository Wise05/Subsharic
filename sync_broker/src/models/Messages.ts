import { isRecord, isTrack } from './Track.ts';
import type { Track } from './Track.ts';
import { isPlaybackState } from './Room.ts';
import type { PlaybackState, QueueEntry } from './Room.ts';

/** Commands clients may send to the Sync Broker over WebSocket. */
export type RoomCommand =
  | { type: 'CREATE_ROOM' }
  | { type: 'JOIN_ROOM'; roomId: string }
  | { type: 'LEAVE_ROOM' }
  | { type: 'BROADCAST_STATE'; state: PlaybackState }
  | { type: 'ADD_TO_QUEUE'; track: Track }
  | { type: 'REMOVE_FROM_QUEUE'; queueId: string }
  | { type: 'REORDER_QUEUE'; queueIds: string[] }
  | { type: 'SYNC_PING'; clientTx: number };

export type SyncPongMessage = {
  type: 'SYNC_PONG';
  clientTx: number;
  serverRx: number;
  serverTx: number;
};

export type QueueUpdatedMessage = { type: 'QUEUE_UPDATED'; roomId: string; queue: QueueEntry[] };

/** Validate parsed JSON at runtime before passing it to RoomManager. */
export function isRoomCommand(value: unknown): value is RoomCommand {
  if (!isRecord(value) || typeof value.type !== 'string') return false;
  switch (value.type) {
    case 'CREATE_ROOM':
    case 'LEAVE_ROOM':
      return true;
    case 'JOIN_ROOM':
      return typeof value.roomId === 'string' && value.roomId.trim().length > 0;
    case 'BROADCAST_STATE':
      return isPlaybackState(value.state);
    case 'ADD_TO_QUEUE':
      return isTrack(value.track);
    case 'REMOVE_FROM_QUEUE':
      return typeof value.queueId === 'string' && value.queueId.length > 0;
    case 'REORDER_QUEUE':
      return Array.isArray(value.queueIds)
        && value.queueIds.every((queueId) => typeof queueId === 'string' && queueId.length > 0);
    case 'SYNC_PING':
      return typeof value.clientTx === 'number';
    default:
      return false;
  }
}
