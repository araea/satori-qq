'use strict';

const crypto = require('crypto');
const fs = require('fs');
const net = require('net');

const configPath = '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/onebot-qq.json';
let token = 'onebot-qq-token';
try {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (typeof config.token === 'string') token = config.token;
} catch (_) {}

const expected = new Set(['health-status', 'health-version', 'health-login']);
const results = new Map();
const key = crypto.randomBytes(16).toString('base64');
const socket = net.createConnection({ host: '127.0.0.1', port: 3001 });
let buffer = Buffer.alloc(0);
let upgraded = false;

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

function finishIfReady() {
  if (results.size !== expected.size) return;
  const status = results.get('health-status');
  const version = results.get('health-version');
  const login = results.get('health-login');
  const summary = {
    ws: 'ok',
    online: !!(status?.data?.online),
    version: version?.data?.app_version || '',
    login: login?.status === 'ok' && !!login?.data?.user_id,
  };
  if (status?.data?.fekit_attach?.enabled) {
    summary.fekit_attach = status.data.fekit_attach;
  }
  console.log(JSON.stringify(summary));
  socket.end();
}

function parseFrames() {
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
      if (expected.has(message.echo)) results.set(message.echo, message);
      finishIfReady();
    } catch (_) {}
  }
}

socket.on('connect', () => socket.write(
  `GET / HTTP/1.1\r\nHost: 127.0.0.1:3001\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
  `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer ${token}\r\n\r\n`,
));

socket.on('data', (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  if (!upgraded) {
    const end = buffer.indexOf('\r\n\r\n');
    if (end < 0) return;
    const response = buffer.subarray(0, end).toString();
    buffer = buffer.subarray(end + 4);
    if (!response.startsWith('HTTP/1.1 101')) throw new Error(response.split('\r\n')[0]);
    upgraded = true;
    socket.write(frame(JSON.stringify({ action: 'get_status', echo: 'health-status' })));
    socket.write(frame(JSON.stringify({ action: 'get_version_info', echo: 'health-version' })));
    socket.write(frame(JSON.stringify({ action: 'get_login_info', echo: 'health-login' })));
  }
  parseFrames();
});

socket.on('error', (error) => {
  console.error(`WS health error: ${error.message}`);
  process.exitCode = 1;
});

setTimeout(() => {
  if (results.size !== expected.size) {
    console.error(`WS health timeout: received ${results.size}/${expected.size}`);
    process.exitCode = 1;
    socket.destroy();
  }
}, 5000).unref();
