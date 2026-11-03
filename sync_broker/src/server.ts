import express from 'express';
import axios from 'axios';
import { NavidromeService } from './services/NavidromeService';

const app = express();

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
