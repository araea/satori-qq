'use strict';

const crypto = require('crypto');
const fs = require('fs');
const net = require('net');

const groupId = Number(process.env.SATORI_TEST_GROUP || '675983807');
const desiredType = process.env.SATORI_RESOURCE_TYPE || '';
const configPath = '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json';
let token = 'satori-qq-token';
try {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (typeof config.token === 'string') token = config.token;
} catch (_) {}

const socket = net.createConnection({ host: '127.0.0.1', port: 3001 });
const key = crypto.randomBytes(16).toString('base64');
let buffer = Buffer.alloc(0);
let upgraded = false;
let finished = false;

function frame(text) {
  const payload = Buffer.from(text);
  const mask = crypto.randomBytes(4);
  let header;
  if (payload.length < 126) header = Buffer.from([0x81, 0x80 | payload.length]);
  else header = Buffer.from([0x81, 0xfe, payload.length >> 8, payload.length & 0xff]);
  const masked = Buffer.alloc(payload.length);
  for (let i = 0; i < payload.length; i++) masked[i] = payload[i] ^ mask[i & 3];
  return Buffer.concat([header, mask, masked]);
}

function send(action, params, echo) {
  socket.write(frame(JSON.stringify({ action, params, echo })));
}

function finish(summary, exitCode = 0) {
  if (finished) return;
  finished = true;
  console.log(JSON.stringify(summary));
  process.exitCode = exitCode;
  socket.end();
}

function handle(message) {
  if (message.echo === 'resource-history') {
    if (message.status !== 'ok') return finish({ history: 'failed', retcode: message.retcode }, 1);
    const messages = message.data?.messages || [];
    const actionFor = { image: 'get_image', record: 'get_record', file: 'get_file', video: 'get_file' };
    let picked;
    for (const item of messages) {
      for (const segment of item.message || []) {
        if ((!desiredType || desiredType === segment.type)
            && actionFor[segment.type] && segment.data?.file) {
          picked = { type: segment.type, file: segment.data.file, action: actionFor[segment.type] };
          break;
        }
      }
      if (picked) break;
    }
    if (!picked) return finish({ history: 'ok', resource: 'not-found' }, 2);
    send(picked.action, { file: picked.file }, `resource-${picked.type}`);
    return;
  }
  if (typeof message.echo === 'string' && message.echo.startsWith('resource-')) {
    const data = message.data || {};
    finish({
      history: 'ok',
      resource_type: message.echo.substring('resource-'.length),
      status: message.status,
      retcode: message.retcode,
      local_file: typeof data.file === 'string' && data.file.startsWith('/'),
      has_url: typeof data.url === 'string' && data.url.length > 0,
      size: data.file_size || 0,
    }, message.status === 'ok' ? 0 : 1);
  }
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
    try { handle(JSON.parse(payload.toString())); } catch (_) {}
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
    if (!response.startsWith('HTTP/1.1 101')) return finish({ handshake: 'failed' }, 1);
    upgraded = true;
    send('get_group_msg_history', { group_id: groupId, count: 100 }, 'resource-history');
  }
  parseFrames();
});

socket.on('error', (error) => finish({ error: error.message }, 1));
setTimeout(() => finish({ error: 'timeout' }, 1), 40000).unref();
