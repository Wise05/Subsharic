import { isFiniteNumber, isRecord, isTrack, Track } from './Track';

/** JSON-safe playback snapshot; socket references stay in RoomManager. */
export interface PlaybackState {
  track: Track | null;
  isPlaying: boolean;
  positionMs: number;
  updatedAt: number;
}

/** Serializable view of a room; internal Map and WebSocket fields are excluded. */
export interface RoomSnapshot {
  roomId: string;
  hostId: string;
  memberIds: string[];
  state: PlaybackState | null;
}

export function isPlaybackState(value: unknown): value is PlaybackState {
  if (!isRecord(value)) return false;
  return (value.track === null || isTrack(value.track))
    && typeof value.isPlaying === 'boolean'
    && isFiniteNumber(value.positionMs) && value.positionMs >= 0
    && isFiniteNumber(value.updatedAt) && value.updatedAt >= 0;
}
