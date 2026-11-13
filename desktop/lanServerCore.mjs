import crypto from 'node:crypto';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { parseFile } from 'music-metadata';
import { WebSocketServer } from 'ws';

const AUDIO_EXTS = new Set(['.flac', '.mp3', '.opus', '.ogg', '.m4a', '.aac', '.wav', '.alac', '.ape', '.wma']);

export function getLanIpv4Address() {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === 'IPv4' && !net.internal) {
        return net.address;
      }
    }
  }
  return '127.0.0.1';
}

export function computeHmacHex(secret, message) {
  return crypto.createHmac('sha256', secret).update(message, 'utf8').digest('hex');
}

export function safeEqualHex(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  try {
    return crypto.timingSafeEqual(Buffer.from(a, 'hex'), Buffer.from(b, 'hex'));
  } catch {
    return false;
  }
}

export function contentTypeForFile(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  switch (ext) {
    case '.html':
      return 'text/html; charset=utf-8';
    case '.js':
    case '.mjs':
      return 'application/javascript; charset=utf-8';
    case '.css':
      return 'text/css; charset=utf-8';
    case '.json':
      return 'application/json; charset=utf-8';
    case '.svg':
      return 'image/svg+xml';
    case '.png':
      return 'image/png';
    case '.flac':
      return 'audio/flac';
    case '.mp3':
      return 'audio/mpeg';
    case '.opus':
      return 'audio/opus';
    case '.ogg':
      return 'audio/ogg';
    case '.m4a':
    case '.aac':
      return 'audio/mp4';
    case '.wav':
      return 'audio/wav';
    default:
      return 'application/octet-stream';
  }
}

function parseDiscFromFolder(folderName) {
  const match = /^(?:cd|disc|disk|disco)\s*[-_.]?\s*0*([1-9][0-9]*)(?:\b|[-_\s].*)?$/i.exec(folderName.trim());
  return match ? Number.parseInt(match[1], 10) : 1;
}

export async function scanMusicDirectory(rootPath) {
  const normalizedRoot = path.resolve(rootPath);
  const results = [];

  async function walk(currentDir) {
    const entries = await fsp.readdir(currentDir, { withFileTypes: true });
    for (const entry of entries) {
      if (entry.name.startsWith('.')) continue;
      const fullPath = path.join(currentDir, entry.name);
      if (entry.isDirectory()) {
        await walk(fullPath);
      } else if (entry.isFile()) {
        const ext = path.extname(entry.name).toLowerCase();
        if (!AUDIO_EXTS.has(ext)) continue;
        const stat = await fsp.stat(fullPath);
        const relPath = path.relative(normalizedRoot, fullPath).replace(/\\/g, '/');
        const parentDir = path.dirname(fullPath);
        const parentName = path.basename(parentDir);

        let title = path.basename(entry.name, ext).replace(/^\d{1,3}[\s._-]+/, '').trim() || entry.name;
        let artist = 'Unknown Artist';
        let album = parentName || 'Unknown Album';
        let albumArtist;
        let discNumber = parseDiscFromFolder(parentName);
        let totalDiscs = 1;
        let trackNumber = 0;
        let durationMs = 0;
        let format = ext.slice(1).toUpperCase();
        let bitrateKbps = 0;
        let sampleRateHz = 44100;
        let bitDepth = 16;
        let year;
        let genre;

        try {
          const meta = await parseFile(fullPath, { duration: true, skipCovers: true });
          const common = meta.common;
          const fmt = meta.format;
          if (common.title?.trim()) title = common.title.trim();
          if (common.artist?.trim()) artist = common.artist.trim();
          if (common.album?.trim()) album = common.album.trim();
          if (common.albumartist?.trim()) albumArtist = common.albumartist.trim();
          if (common.disk?.no) discNumber = common.disk.no;
          if (common.disk?.of) totalDiscs = common.disk.of;
          if (common.track?.no) trackNumber = common.track.no;
          if (common.year) year = common.year;
          if (common.genre?.[0]) genre = common.genre[0];
          if (fmt.duration) durationMs = Math.round(fmt.duration * 1000);
          if (fmt.bitrate) bitrateKbps = Math.round(fmt.bitrate / 1000);
          if (fmt.sampleRate) sampleRateHz = fmt.sampleRate;
          if (fmt.bitsPerSample) bitDepth = fmt.bitsPerSample;
          if (fmt.codec) format = fmt.codec.split('/')[0].toUpperCase();
        } catch {
          // Fallback to filename/folder inference if corrupt header
        }

        const isLossless = ['FLAC', 'ALAC', 'WAV', 'APE'].includes(format) || ['flac', 'alac', 'wav'].includes(ext.slice(1));
        results.push({
          filePath: fullPath.replace(/\\/g, '/'),
          relativePath: relPath,
          fileName: entry.name,
          fileExtension: ext.slice(1).toLowerCase(),
          title,
          artist,
          album,
          albumArtist,
          discNumber,
          totalDiscs,
          trackNumber,
          durationMs,
          audioQuality: {
            format,
            bitrateKbps,
            sampleRateHz,
            bitDepth,
            isLossless
          },
          year,
          genre,
          parentFolderPath: parentDir.replace(/\\/g, '/'),
          fileSizeBytes: stat.size,
          lastModified: stat.mtimeMs
        });
      }
    }
  }

  await walk(normalizedRoot);
  return { rootPath: normalizedRoot.replace(/\\/g, '/'), tracks: results };
}

export function createMusikaLanServer(options = {}) {
  const webDistDir = options.webDistDir || null;
  let state = {
    deviceId: options.deviceId || `desktop-${crypto.randomBytes(4).toString('hex')}`,
    deviceName: options.deviceName || `Musika Desktop (${os.hostname()})`,
    deviceType: 'DESKTOP',
    accountId: options.accountId || 'local-guest',
    username: options.username || 'MusikaUser',
    accountHash: options.accountHash || '',
    pairSecretHmac: options.pairSecretHmac || crypto.randomBytes(32).toString('hex'),
    pinCode: options.pinCode || String(Math.floor(100000 + Math.random() * 900000)),
    supabaseUrl: options.supabaseUrl || '',
    supabaseAnonKey: options.supabaseAnonKey || '',
    rootPath: options.rootPath || '',
    allowStreaming: Boolean(options.allowStreaming),
    catalogTracks: []
  };

  const incomingHandlers = new Set();
  const authenticatedClients = new Set();

  const server = http.createServer(async (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Range');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');

    if (req.method === 'OPTIONS') {
      res.writeHead(204);
      res.end();
      return;
    }

    const reqUrl = new URL(req.url || '/', `http://${req.headers.host || '127.0.0.1'}`);
    const pathname = reqUrl.pathname;

    if (req.method === 'GET' && pathname === '/api/discovery') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          version: '2.2.0',
          deviceId: state.deviceId,
          deviceName: state.deviceName,
          deviceType: state.deviceType,
          accountHash: state.accountHash,
          port: server.address()?.port ?? 8765
        })
      );
      return;
    }

    if (req.method === 'GET' && pathname === '/api/library') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ rootPath: state.rootPath, tracks: state.catalogTracks }));
      return;
    }

    if (req.method === 'POST' && pathname === '/api/pair/pin') {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      let body = {};
      try {
        body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      } catch {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Invalid JSON' }));
        return;
      }

      if (!body.pinCode || String(body.pinCode).trim() !== String(state.pinCode).trim()) {
        res.writeHead(403, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Invalid or expired PIN' }));
        return;
      }

      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          version: '2.2.0',
          accountId: state.accountId,
          username: state.username,
          pairSecretHmac: state.pairSecretHmac,
          pinCode: state.pinCode,
          lanHost: getLanIpv4Address(),
          lanPort: server.address()?.port ?? 8765,
          supabaseUrl: state.supabaseUrl,
          supabaseAnonKey: state.supabaseAnonKey,
          issuedAtMs: Date.now()
        })
      );
      return;
    }

    if (req.method === 'GET' && pathname === '/stream') {
      if (!state.allowStreaming) {
        res.writeHead(403, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'LAN audio streaming for missing tracks is disabled in Settings.' }));
        return;
      }
      const rel = reqUrl.searchParams.get('path') || '';
      if (!rel || !state.rootPath) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Missing path parameter or uninitialized root' }));
        return;
      }
      const resolvedRoot = path.resolve(state.rootPath);
      const targetFile = path.resolve(resolvedRoot, rel);
      const relCheck = path.relative(resolvedRoot, targetFile);
      if (relCheck.startsWith('..') || path.isAbsolute(relCheck)) {
        res.writeHead(403, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Path traversal denied' }));
        return;
      }
      if (!AUDIO_EXTS.has(path.extname(targetFile).toLowerCase())) {
        res.writeHead(403, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Unsupported audio extension' }));
        return;
      }

      try {
        const stat = await fsp.stat(targetFile);
        const totalSize = stat.size;
        const mime = contentTypeForFile(targetFile);
        const range = req.headers.range;
        if (range) {
          const match = /bytes=(\d+)-(\d*)/.exec(range);
          const start = match ? Number.parseInt(match[1], 10) : 0;
          const end = match && match[2] ? Math.min(totalSize - 1, Number.parseInt(match[2], 10)) : totalSize - 1;
          if (start >= totalSize || end < start) {
            res.writeHead(416, { 'Content-Range': `bytes */${totalSize}` });
            res.end();
            return;
          }
          res.writeHead(206, {
            'Content-Range': `bytes ${start}-${end}/${totalSize}`,
            'Accept-Ranges': 'bytes',
            'Content-Length': end - start + 1,
            'Content-Type': mime
          });
          fs.createReadStream(targetFile, { start, end }).pipe(res);
        } else {
          res.writeHead(200, {
            'Accept-Ranges': 'bytes',
            'Content-Length': totalSize,
            'Content-Type': mime
          });
          fs.createReadStream(targetFile).pipe(res);
        }
      } catch {
        res.writeHead(404);
        res.end();
      }
      return;
    }

    // Serve built Web PWA statically over LAN HTTP portal
    if (req.method === 'GET' && webDistDir && fs.existsSync(webDistDir)) {
      const cleanSub = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
      const candidate = path.resolve(webDistDir, cleanSub);
      const rel = path.relative(path.resolve(webDistDir), candidate);
      const fileToServe =
        !rel.startsWith('..') && fs.existsSync(candidate) && fs.statSync(candidate).isFile()
          ? candidate
          : path.join(webDistDir, 'index.html');
      try {
        const bytes = await fsp.readFile(fileToServe);
        res.writeHead(200, { 'Content-Type': contentTypeForFile(fileToServe) });
        res.end(bytes);
      } catch {
        res.writeHead(404);
        res.end();
      }
      return;
    }

    res.writeHead(404);
    res.end();
  });

  const wss = new WebSocketServer({ server, path: '/ws' });

  wss.on('connection', (ws) => {
    let authed = false;
    let nonce = crypto.randomBytes(16).toString('hex');
    let peerDeviceId = '';

    ws.on('message', (raw) => {
      let msg;
      try {
        msg = JSON.parse(String(raw));
      } catch {
        return;
      }

      if (msg.type === 'hello') {
        peerDeviceId = msg.senderDeviceId || '';
        nonce = crypto.randomBytes(16).toString('hex');
        ws.send(
          JSON.stringify({
            type: 'auth_challenge',
            senderDeviceId: state.deviceId,
            senderDeviceName: state.deviceName,
            senderDeviceType: state.deviceType,
            nonce,
            timestampMs: Date.now()
          })
        );
        return;
      }

      if (msg.type === 'auth_proof') {
        const expected = computeHmacHex(state.pairSecretHmac, `${nonce}|${msg.senderDeviceId || peerDeviceId}`);
        if (safeEqualHex(msg.hmacProof, expected)) {
          authed = true;
          authenticatedClients.add(ws);
          ws.send(
            JSON.stringify({
              type: 'catalog',
              senderDeviceId: state.deviceId,
              senderDeviceName: state.deviceName,
              senderDeviceType: state.deviceType,
              timestampMs: Date.now(),
              catalog: state.catalogTracks
            })
          );
        } else {
          ws.close(4003, 'HMAC auth failed');
        }
        return;
      }

      if (!authed) {
        ws.close(4001, 'Unauthenticated');
        return;
      }

      // Notify Electron renderer host and relay to other LAN peers
      for (const h of incomingHandlers) h(msg);
      for (const client of authenticatedClients) {
        if (client !== ws && client.readyState === 1) {
          client.send(JSON.stringify(msg));
        }
      }
    });

    ws.on('close', () => {
      authenticatedClients.delete(ws);
    });
  });

  return {
    server,
    wss,
    async listen(port = 8765, host = '0.0.0.0') {
      await new Promise((resolve) => server.listen(port, host, resolve));
      return server.address();
    },
    async close() {
      for (const client of authenticatedClients) {
        try {
          client.close();
        } catch {
          // ignore
        }
      }
      await new Promise((resolve) => wss.close(resolve));
      await new Promise((resolve) => server.close(resolve));
    },
    updateConfig(partial) {
      state = { ...state, ...partial };
    },
    getState() {
      return state;
    },
    broadcast(envelope) {
      const payload = JSON.stringify(envelope);
      for (const client of authenticatedClients) {
        if (client.readyState === 1) {
          client.send(payload);
        }
      }
    },
    onIncomingEnvelope(cb) {
      incomingHandlers.add(cb);
      return () => incomingHandlers.delete(cb);
    }
  };
}
