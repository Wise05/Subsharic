import { isRecord } from './Track';
import { isPlaybackState, PlaybackState } from './Room';

/** Commands clients may send to the Sync Broker over WebSocket. */
export type RoomCommand =
  | { type: 'CREATE_ROOM' }
  | { type: 'JOIN_ROOM'; roomId: string }
  | { type: 'LEAVE_ROOM' }
  | { type: 'BROADCAST_STATE'; state: PlaybackState };

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
    default:
      return false;
  }
}
