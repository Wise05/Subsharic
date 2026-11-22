import axios from 'axios';
import { createHash } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { mkdir, readdir, rename, stat, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { getSubsonicAuthParams } from '../utils/subsonicAuth.ts';
import type { Track } from '../models/Track.ts';

interface SubsonicSong {
  id: string;
  title: string;
  artist?: string;
  album?: string;
  duration?: number;
  coverArt?: string;
}

export interface Album {
  id: string;
  title: string;
  artist?: string;
  artworkUrl?: string;
  songCount?: number;
}

interface AlbumSummary {
  id: string;
  name: string;
  artist?: string;
  coverArt?: string;
  songCount?: number;
}

interface SearchResponse {
  'subsonic-response'?: {
    status?: string;
    error?: { message?: string };
    searchResult3?: { song?: SubsonicSong[] };
    albumList2?: { album?: AlbumSummary[] };
    album?: AlbumSummary & { song?: SubsonicSong[] };
  };
}

export class NavidromeService {
  private static readonly audioCache = new Map<string, { path: string; contentType: string; size: number }>();
  private static readonly audioPrefetches = new Map<string, Promise<{ path: string; contentType: string; size: number }>>();
  private static readonly audioCacheDirectory = join(tmpdir(), 'subsharic-audio-cache');
  private static readonly maxAudioCacheBytes = 1024 * 1024 * 1024;
  private static audioCacheInitialization: Promise<void> | undefined;

  private static get baseUrl(): string {
    const value = process.env.NAVIDROME_URL;
    if (!value) throw new Error('NAVIDROME_URL is not configured');
    return value.replace(/\/+$/, '');
  }

  static async searchTracks(query: string): Promise<Track[]> {
    const response = await axios.get<SearchResponse>(`${this.baseUrl}/rest/search3.view`, {
      params: {
        ...getSubsonicAuthParams(),
        query,
        songCount: 50,
        albumCount: 0,
        artistCount: 0,
      },
      timeout: 10_000,
    });
    const subsonic = response.data['subsonic-response'];
    if (subsonic?.status !== 'ok') {
      throw new Error(subsonic?.error?.message || 'Navidrome search failed');
    }
    return (subsonic.searchResult3?.song ?? []).map((song) => this.mapTrack(song));
  }

  static async listAlbums(offset: number, size: number): Promise<Album[]> {
    const response = await axios.get<SearchResponse>(`${this.baseUrl}/rest/getAlbumList2.view`, {
      params: {
        ...getSubsonicAuthParams(),
        type: 'alphabeticalByName',
        size,
        offset,
      },
      timeout: 10_000,
    });
    const subsonic = response.data['subsonic-response'];
    if (subsonic?.status !== 'ok') {
      throw new Error(subsonic?.error?.message || 'Navidrome album listing failed');
    }
    return (subsonic.albumList2?.album ?? []).map((album) => this.mapAlbum(album));
  }

  static async getAlbum(id: string): Promise<{ album: Album; tracks: Track[] }> {
    const response = await axios.get<SearchResponse>(`${this.baseUrl}/rest/getAlbum.view`, {
      params: { ...getSubsonicAuthParams(), id },
      timeout: 10_000,
    });
    const subsonic = response.data['subsonic-response'];
    if (subsonic?.status !== 'ok' || !subsonic.album) {
      throw new Error(subsonic?.error?.message || 'Navidrome album could not be loaded');
    }
    return {
      album: this.mapAlbum(subsonic.album),
      tracks: (subsonic.album.song ?? []).map((song) => this.mapTrack(song)),
    };
  }

  static async getCachedAudio(trackId: string): Promise<{ path: string; contentType: string; size: number }> {
    await this.initializeAudioCache();
    const cached = this.audioCache.get(trackId);
    if (cached) {
      // Refresh insertion order so pruning evicts the least recently used track first.
      this.audioCache.delete(trackId);
      this.audioCache.set(trackId, cached);
      return cached;
    }

    const pending = this.audioPrefetches.get(trackId);
    if (pending) return pending;

    const prefetch = (async () => {
      await mkdir(this.audioCacheDirectory, { recursive: true });
      const cacheKey = createHash('sha256').update(trackId).digest('hex');
      const path = join(this.audioCacheDirectory, `${cacheKey}.audio`);
      const temporaryPath = `${path}.tmp`;
      try {
        const response = await axios.get<import('node:stream').Readable>(this.getStreamUrl(trackId), {
          responseType: 'stream',
          timeout: 30_000,
        });
        await pipeline(response.data, createWriteStream(temporaryPath));
        const fileInfo = await stat(temporaryPath);
        await rename(temporaryPath, path);
        const entry = {
          path,
          contentType: typeof response.headers['content-type'] === 'string'
            ? response.headers['content-type']
            : 'audio/mpeg',
          size: fileInfo.size,
        };
        this.audioCache.set(trackId, entry);
        await this.pruneAudioCache(trackId);
        return entry;
      } catch (error) {
        await unlink(temporaryPath).catch(() => undefined);
        throw error;
      } finally {
        this.audioPrefetches.delete(trackId);
      }
    })();
    this.audioPrefetches.set(trackId, prefetch);
    return prefetch;
  }

  private static async initializeAudioCache(): Promise<void> {
    this.audioCacheInitialization ??= (async () => {
      await mkdir(this.audioCacheDirectory, { recursive: true });
      const existingFiles = await readdir(this.audioCacheDirectory);
      await Promise.all(existingFiles.map((file) => unlink(join(this.audioCacheDirectory, file)).catch(() => undefined)));
    })();
    return this.audioCacheInitialization;
  }

  private static async pruneAudioCache(protectedTrackId: string): Promise<void> {
    let totalBytes = [...this.audioCache.values()].reduce((total, entry) => total + entry.size, 0);
    for (const [trackId, entry] of this.audioCache) {
      if (totalBytes <= this.maxAudioCacheBytes) break;
      if (trackId === protectedTrackId) continue;
      this.audioCache.delete(trackId);
      await unlink(entry.path).catch(() => undefined);
      totalBytes -= entry.size;
    }
  }

  private static mapTrack(song: SubsonicSong): Track {
    return {
      id: song.id,
      title: song.title,
      ...(song.artist ? { artist: song.artist } : {}),
      ...(song.album ? { album: song.album } : {}),
      ...(typeof song.duration === 'number' ? { durationMs: song.duration * 1_000 } : {}),
      ...(song.coverArt ? { artworkUrl: `/api/artwork/${encodeURIComponent(song.coverArt)}` } : {}),
    };
  }

  private static mapAlbum(album: AlbumSummary): Album {
    return {
      id: album.id,
      title: album.name,
      ...(album.artist ? { artist: album.artist } : {}),
      ...(album.coverArt ? { artworkUrl: `/api/artwork/${encodeURIComponent(album.coverArt)}` } : {}),
      ...(typeof album.songCount === 'number' ? { songCount: album.songCount } : {}),
    };
  }

  static getStreamUrl(trackId: string): string {
    return this.endpoint('stream.view', { id: trackId });
  }

  static getArtworkUrl(coverArtId: string): string {
    return this.endpoint('getCoverArt.view', { id: coverArtId, size: '512' });
  }

  private static endpoint(path: string, parameters: Record<string, string>): string {
    const url = new URL(`/rest/${path}`, `${this.baseUrl}/`);
    Object.entries({ ...getSubsonicAuthParams(), ...parameters }).forEach(([key, value]) => {
      url.searchParams.set(key, value);
    });
    return url.toString();
  }
}
