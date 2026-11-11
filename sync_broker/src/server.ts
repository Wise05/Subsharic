import express from 'express';
import axios from 'axios';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import WebSocket, { WebSocketServer } from 'ws';
import { NavidromeService } from './services/NavidromeService';
import { RoomManager } from './managers/RoomManager';
import { isRoomCommand } from './models/Messages';

const app = express();
const httpServer = createServer(app);
const websocketServer = new WebSocketServer({ noServer: true });
const roomManager = new RoomManager();
const aliveConnections = new Set<WebSocket>();

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

  websocket.on('message', (data, isBinary) => {
    if (isBinary) {
      websocket.close(1003, 'Text messages only');
      return;
    }

    try {
      const message: unknown = JSON.parse(data.toString());
      if (!isRoomCommand(message)) {
        websocket.send(JSON.stringify({ type: 'ERROR', code: 'INVALID_MESSAGE', message: 'Unknown or malformed room command.' }));
        return;
      }
      roomManager.handle({ clientId, socket: websocket }, message);
    } catch {
      websocket.send(JSON.stringify({ type: 'ERROR', code: 'INVALID_JSON', message: 'Message must be valid JSON.' }));
    }
  });

  websocket.on('close', () => {
    aliveConnections.delete(websocket);
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
    const query = req.query.q as string;
    const results = await NavidromeService.searchTracks(query);
    res.json(results);
  } catch (err) {
    res.status(500).json({ error: 'Failed to search tracks' });
  }
});

// Proxy audio stream
app.get('/api/stream/:id', async (req, res) => {
  try {
    const streamUrl = NavidromeService.getStreamUrl(req.params.id);
    const response = await axios({
      method: 'get',
      url: streamUrl,
      responseType: 'stream',
    });

    res.setHeader('Content-Type', response.headers['content-type'] || 'audio/mpeg');
    response.data.pipe(res);
  } catch (err) {
    res.status(500).json({ error: 'Stream fetch failed' });
  }
});

const port = Number(process.env.PORT ?? 3000);
httpServer.listen(port, () => {
  console.log(`Sync Broker listening on http://localhost:${port}`);
  console.log(`WebSocket endpoint: ws://localhost:${port}/ws`);
});
