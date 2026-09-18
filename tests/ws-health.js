'use strict';

const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const net = require('net');

function loadToken() {
  const paths = [
    '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json',
    '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json',
  ];
  for (const configPath of paths) {
    try {
      const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
      if (typeof config.token === 'string') return config.token;
    } catch (_) {}
  }
  return 'satori-qq-token';
}

const token = loadToken();
const host = '127.0.0.1';
const port = 3001;

function post(path, body) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(JSON.stringify(body || {}));
    const req = http.request({
      host, port, path, method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': data.length,
        'Authorization': `Bearer ${token}`,
        'Satori-Platform': 'red',
      },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let json = null;
        try { json = JSON.parse(text); } catch (_) {}
        resolve({ status: res.statusCode, json, text });
      });
    });
    req.on('error', reject);
    req.setTimeout(4000, () => { req.destroy(); reject(new Error('http timeout ' + path)); });
    req.end(data);
  });
}

function identify() {
  return new Promise((resolve, reject) => {
    const key = crypto.randomBytes(16).toString('base64');
    const socket = net.createConnection({ host, port });
    let buffer = Buffer.alloc(0);
    let upgraded = false;
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error('ws identify timeout'));
    }, 5000);

    function frame(text) {
      const payload = Buffer.from(text);
      const mask = crypto.randomBytes(4);
      const header = payload.length < 126
        ? Buffer.from([0x81, 0x80 | payload.length])
        : Buffer.from([0x81, 0xfe, payload.length >> 8, payload.length & 0xff]);
      const masked = Buffer.alloc(payload.length);
      for (let i = 0; i < payload.length; i++) masked[i] = payload[i] ^ mask[i & 3];
      return Buffer.concat([header, mask, masked]);
    }

    socket.on('connect', () => socket.write(
      `GET /v1/events HTTP/1.1\r\nHost: ${host}:${port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
      `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer ${token}\r\n\r\n`,
    ));

    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      if (!upgraded) {
        const end = buffer.indexOf('\r\n\r\n');
        if (end < 0) return;
        const response = buffer.subarray(0, end).toString();
        buffer = buffer.subarray(end + 4);
        if (!response.startsWith('HTTP/1.1 101')) {
          clearTimeout(timer);
          socket.destroy();
          reject(new Error(response.split('\r\n')[0]));
          return;
        }
        upgraded = true;
        socket.write(frame(JSON.stringify({ op: 3, body: { token } })));
      }
      while (buffer.length >= 2) {
        let length = buffer[1] & 0x7f;
        let offset = 2;
        if (length === 126) {
          if (buffer.length < 4) return;
          length = buffer.readUInt16BE(2);
          offset = 4;
        } else if (length === 127) {
          if (buffer.length < 10) return;
          length = Number(buffer.readBigUInt64BE(2));
          offset = 10;
        }
        if (buffer.length < offset + length) return;
        const opcode = buffer[0] & 0x0f;
        const payload = buffer.subarray(offset, offset + length);
        buffer = buffer.subarray(offset + length);
        if (opcode !== 1) continue;
        try {
          const message = JSON.parse(payload.toString());
          if (message.op === 4) {
            clearTimeout(timer);
            socket.end();
            resolve(message.body);
            return;
          }
        } catch (_) {}
      }
    });
    socket.on('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

(async () => {
  const login = await post('/v1/login.get', {});
  const status = await post('/v1/internal/status', {});
  const version = await post('/v1/internal/version', {});
  const ready = await identify();
  const loginJson = login.json || {};
  const statusJson = status.json || {};
  const versionJson = version.json || {};
  const summary = {
    satori: 'ok',
    http: login.status === 200 && status.status === 200,
    ready: Array.isArray(ready && ready.logins) && ready.logins.length > 0,
    online: loginJson.status === 1 || !!statusJson.online,
    version: versionJson.version || loginJson.adapter || '',
    platform: (ready.logins && ready.logins[0] && ready.logins[0].platform) || loginJson.platform || '',
    login: !!(loginJson.user && loginJson.user.id),
  };
  if (statusJson.fekit_attach && statusJson.fekit_attach.enabled) {
    summary.fekit_attach = statusJson.fekit_attach;
  }
  if (statusJson.env_report) summary.env_report = statusJson.env_report;
  if (statusJson.outbound_guard) {
    summary.outbound_guard = statusJson.outbound_guard;
    summary.online_since_epoch_ms = statusJson.online_since_epoch_ms || 0;
  }
  console.log(JSON.stringify(summary));
})().catch((error) => {
  console.error(`Satori health error: ${error.message}`);
  process.exitCode = 1;
});
