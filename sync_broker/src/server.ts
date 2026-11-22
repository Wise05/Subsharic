import express from 'express';
import axios from 'axios';
import dotenv from 'dotenv';
import { createServer } from 'node:http';
import { createReadStream } from 'node:fs';
import { pipeline } from 'node:stream/promises';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import WebSocket, { WebSocketServer } from 'ws';
import { NavidromeService } from './services/NavidromeService.ts';
import { RoomManager } from './managers/RoomManager.ts';
import { isRoomCommand } from './models/Messages.ts';

// Support running the broker from the workspace, broker, or src directory.
for (const envPath of [resolve(process.cwd(), '.env'), resolve(process.cwd(), '../.env'), resolve(process.cwd(), '../../.env')]) {
  dotenv.config({ path: envPath });
}

const app = express();
const httpServer = createServer(app);
const websocketServer = new WebSocketServer({ noServer: true });
const roomManager = new RoomManager();
const aliveConnections = new Set<WebSocket>();
const playbackCommandVersions = new Map<string, number>();

// Keep HTTP routes (search/stream) and WebSocket connections on the same port.
httpServer.on('upgrade', (request, socket, head) => {
  let pathname: string;
  try {
    pathname = new URL(request.url ?? '/', 'http://localhost').pathname;
  } catch {
    socket.destroy();
    return;
  }

  if (pathname !== '/ws') {
    socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }

  websocketServer.handleUpgrade(request, socket, head, (websocket) => {
    websocketServer.emit('connection', websocket, request);
  });
});

websocketServer.on('connection', (websocket) => {
  const clientId = randomUUID();
  aliveConnections.add(websocket);
  console.log('WebSocket client connected');

  websocket.on('pong', () => aliveConnections.add(websocket));

  websocket.on('message', async (data, isBinary) => {
    if (isBinary) {
      websocket.close(1003, 'Text messages only');
      return;
    }

    try {
      const serverRx = Date.now();
      const message: unknown = JSON.parse(data.toString());
      if (!isRoomCommand(message)) {
        websocket.send(JSON.stringify({ type: 'ERROR', code: 'INVALID_MESSAGE', message: 'Unknown or malformed room command.' }));
        return;
      }

      if (message.type === 'SYNC_PING') {
        const serverTx = Date.now();
        websocket.send(JSON.stringify({
          type: 'SYNC_PONG',
          clientTx: message.clientTx,
          serverRx,
          serverTx,
        }));
        return;
      }

      if (message.type === 'ADD_TO_QUEUE') {
        console.log('ADD_TO_QUEUE command received');
      }
      let playbackCommandVersion: number | undefined;
      if (message.type === 'BROADCAST_STATE') {
        playbackCommandVersion = (playbackCommandVersions.get(clientId) ?? 0) + 1;
        playbackCommandVersions.set(clientId, playbackCommandVersion);
      }
      if (message.type === 'BROADCAST_STATE' && message.state.isPlaying && message.state.track) {
        try {
          websocket.send(JSON.stringify({ type: 'PLAYBACK_PREPARING' }));
          console.log('Preparing audio before starting room playback');
          await NavidromeService.getCachedAudio(message.state.track.id);
          console.log('Audio is ready; broadcasting room playback state');
        } catch (error) {
          console.error('Audio prefetch failed:', error instanceof Error ? error.message : error);
          websocket.send(JSON.stringify({
            type: 'ERROR',
            code: 'AUDIO_PREFETCH_FAILED',
            message: 'The broker could not prepare this track for playback.',
          }));
          return;
        }
      }
      if (playbackCommandVersion !== undefined
        && playbackCommandVersions.get(clientId) !== playbackCommandVersion) return;
      roomManager.handle({ clientId, socket: websocket }, message);
    } catch {
      websocket.send(JSON.stringify({ type: 'ERROR', code: 'INVALID_JSON', message: 'Message must be valid JSON.' }));
    }
  });

  websocket.on('close', () => {
    aliveConnections.delete(websocket);
    playbackCommandVersions.delete(clientId);
    roomManager.disconnect(clientId);
    console.log('WebSocket client disconnected');
  });
  websocket.on('error', (error) => console.error('WebSocket error:', error));
});

const heartbeatTimer = setInterval(() => {
  for (const websocket of websocketServer.clients) {
    if (!aliveConnections.has(websocket)) {
      websocket.terminate();
      continue;
    }

    aliveConnections.delete(websocket);
    if (websocket.readyState === WebSocket.OPEN) websocket.ping();
  }
}, 3_000);

websocketServer.on('close', () => clearInterval(heartbeatTimer));

// Proxy track search
app.get('/api/search', async (req, res) => {
  try {
    const query = typeof req.query.q === 'string' ? req.query.q.trim() : '';
    if (!query) {
      res.status(400).json({ error: 'Query parameter q is required.' });
      return;
    }
    const results = await NavidromeService.searchTracks(query);
    res.json(results);
  } catch (err) {
    res.status(500).json({ error: 'Failed to search tracks' });
  }
});

app.get('/api/albums', async (req, res) => {
  const offset = Number(req.query.offset ?? 0);
  const size = Number(req.query.size ?? 50);
  if (!Number.isInteger(offset) || offset < 0 || !Number.isInteger(size) || size < 1 || size > 100) {
    res.status(400).json({ error: 'offset must be non-negative and size must be between 1 and 100.' });
    return;
  }
  try {
    res.json(await NavidromeService.listAlbums(offset, size));
  } catch {
    res.status(500).json({ error: 'Failed to load albums' });
  }
});

app.get('/api/albums/:id', async (req, res) => {
  try {
    res.json(await NavidromeService.getAlbum(req.params.id));
  } catch {
    res.status(500).json({ error: 'Failed to load album' });
  }
});

// Proxy audio stream
app.get('/api/stream/:id', async (req, res) => {
  try {
    const cached = await NavidromeService.getCachedAudio(req.params.id);
    let start = 0;
    let end = cached.size - 1;
    let statusCode = 200;
    const range = req.headers.range;
    if (range) {
      const match = /^bytes=(\d*)-(\d*)$/.exec(range);
      if (!match || cached.size === 0) {
        res.setHeader('Content-Range', `bytes */${cached.size}`);
        res.status(416).end();
        return;
      }
      if (match[1] === '') {
        const suffixLength = Number(match[2]);
        start = Math.max(0, cached.size - suffixLength);
      } else {
        start = Number(match[1]);
        if (match[2] !== '') end = Math.min(end, Number(match[2]));
      }
      if (start > end || start >= cached.size) {
        res.setHeader('Content-Range', `bytes */${cached.size}`);
        res.status(416).end();
        return;
      }
      statusCode = 206;
      res.setHeader('Content-Range', `bytes ${start}-${end}/${cached.size}`);
    }
    res.status(statusCode);
    res.setHeader('Accept-Ranges', 'bytes');
    res.setHeader('Content-Type', cached.contentType);
    res.setHeader('Content-Length', end - start + 1);
    await pipeline(createReadStream(cached.path, { start, end }), res);
  } catch (error) {
    console.error('Audio stream serving failed:', error instanceof Error ? error.message : error);
    if (res.headersSent) res.destroy();
    else res.status(502).json({ error: 'Could not prepare audio stream' });
  }
});

app.get('/api/artwork/:id', async (req, res) => {
  try {
    const response = await axios({
      method: 'get',
      url: NavidromeService.getArtworkUrl(req.params.id),
      responseType: 'stream',
      timeout: 10_000,
    });
    const contentType = response.headers['content-type'];
    res.setHeader('Content-Type', typeof contentType === 'string' ? contentType : 'image/jpeg');
    response.data.pipe(res);
  } catch {
    res.status(404).json({ error: 'Artwork not found' });
  }
});

const port = Number(process.env.PORT ?? 3000);
httpServer.listen(port, () => {
  console.log(`Sync Broker listening on http://localhost:${port}`);
  console.log(`WebSocket endpoint: ws://localhost:${port}/ws`);
});
