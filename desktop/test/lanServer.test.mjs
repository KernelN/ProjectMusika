import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { WebSocket } from 'ws';
import { computeHmacHex, createMusikaLanServer } from '../lanServerCore.mjs';

test('Desktop LAN server enforces PIN pairing, HMAC WebSocket handshake, and default-OFF /stream guard', async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'musika-lan-test-'));
  const audioFile = path.join(tmpDir, 'sample.flac');
  await fs.writeFile(audioFile, Buffer.from('fLaC0123456789ABCDEF'));

  const lanServer = createMusikaLanServer({
    deviceId: 'desktop-test',
    deviceName: 'PC Test',
    accountId: 'user-99',
    username: 'ale_pc',
    pairSecretHmac: 'deadbeef00112233445566778899aabb',
    pinCode: '654321',
    rootPath: tmpDir,
    allowStreaming: false
  });

  const addr = await lanServer.listen(0, '127.0.0.1');
  const baseUrl = `http://127.0.0.1:${addr.port}`;

  try {
    // 1. Wrong PIN returns 403
    const badPin = await fetch(`${baseUrl}/api/pair/pin`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pinCode: '000000' })
    });
    assert.equal(badPin.status, 403);

    // 2. Correct PIN returns QR pair payload
    const goodPin = await fetch(`${baseUrl}/api/pair/pin`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pinCode: '654321', deviceId: 'phone-1', deviceName: 'Galaxy' })
    });
    assert.equal(goodPin.status, 200);
    const pairData = await goodPin.json();
    assert.equal(pairData.username, 'ale_pc');

    // 3. /stream is strictly blocked when allowStreaming=false
    const blockedStream = await fetch(`${baseUrl}/stream?path=sample.flac`);
    assert.equal(blockedStream.status, 403);

    // 4. Enabling streaming allows HTTP 206 partial content and blocks path traversal
    lanServer.updateConfig({ allowStreaming: true });
    const traversalResp = await fetch(`${baseUrl}/stream?path=../../secret.flac`);
    assert.equal(traversalResp.status, 403);

    const rangeResp = await fetch(`${baseUrl}/stream?path=sample.flac`, {
      headers: { Range: 'bytes=0-3' }
    });
    assert.equal(rangeResp.status, 206);
    assert.equal(await rangeResp.text(), 'fLaC');

    // 5. WebSocket HMAC challenge-response + command delivery
    const received = [];
    lanServer.onIncomingEnvelope((env) => received.push(env));

    await new Promise((resolve, reject) => {
      const ws = new WebSocket(`ws://127.0.0.1:${addr.port}/ws`);
      ws.on('open', () => {
        ws.send(JSON.stringify({ type: 'hello', senderDeviceId: 'phone-1', senderDeviceName: 'Galaxy', senderDeviceType: 'ANDROID', timestampMs: Date.now() }));
      });
      ws.on('message', (buf) => {
        const msg = JSON.parse(String(buf));
        if (msg.type === 'auth_challenge') {
          const proof = computeHmacHex('deadbeef00112233445566778899aabb', `${msg.nonce}|phone-1`);
          ws.send(JSON.stringify({ type: 'auth_proof', senderDeviceId: 'phone-1', nonce: msg.nonce, hmacProof: proof, timestampMs: Date.now() }));
        } else if (msg.type === 'catalog') {
          ws.send(JSON.stringify({ type: 'command', senderDeviceId: 'phone-1', senderDeviceName: 'Galaxy', senderDeviceType: 'ANDROID', command: { type: 'SkipNext' }, timestampMs: Date.now() }));
          setTimeout(() => {
            ws.close();
            resolve();
          }, 50);
        }
      });
      ws.on('error', reject);
    });

    assert.equal(received.length, 1);
    assert.equal(received[0].command.type, 'SkipNext');
  } finally {
    await lanServer.close();
    await fs.rm(tmpDir, { recursive: true, force: true });
  }
});
