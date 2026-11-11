/** JSON-safe track metadata shared by room members. */
export interface Track {
  id: string;
  title: string;
  artist?: string;
  album?: string;
  durationMs?: number;
  artworkUrl?: string;
}

export function isTrack(value: unknown): value is Track {
  if (!isRecord(value)) return false;
  return typeof value.id === 'string' && value.id.length > 0
    && typeof value.title === 'string' && value.title.length > 0
    && (value.artist === undefined || typeof value.artist === 'string')
    && (value.album === undefined || typeof value.album === 'string')
    && (value.durationMs === undefined || (isFiniteNumber(value.durationMs) && value.durationMs >= 0))
    && (value.artworkUrl === undefined || typeof value.artworkUrl === 'string');
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}
