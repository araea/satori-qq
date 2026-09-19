'use strict';

/**
 * 测试用 Satori 客户端。模块的线上形状是两条通道：
 * 事件走 WebSocket `/v1/events`（先发 IDENTIFY 再收 READY），动作走 HTTP `POST /v1/<action>`。
 * 这个文件把两件事收在一处，测试脚本不必各写一份握手。
 *
 * 帧格式：客户端发出的帧必须带掩码；服务端发出的帧不带掩码，可能是分片。
 */

const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const net = require('net');

const CONFIG_PATHS = [
  '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json',
  '/sdcard/Android/data/com.tencent.mobileqq/files/satori-qq.json',
  '/sdcard/satori-qq.json',
];

const OP_EVENT = 0;
const OP_PING = 1;
const OP_PONG = 2;
const OP_IDENTIFY = 3;
const OP_READY = 4;

function loadToken() {
  for (const p of CONFIG_PATHS) {
    try {
      const cfg = JSON.parse(fs.readFileSync(p, 'utf8'));
      if (typeof cfg.token === 'string') return cfg.token;
    } catch (_) {}
  }
  return '';
}

function loadPort() {
  for (const p of CONFIG_PATHS) {
    try {
      const cfg = JSON.parse(fs.readFileSync(p, 'utf8'));
      if (Number(cfg.port) > 0) return Number(cfg.port);
    } catch (_) {}
  }
  return 3001;
}

function maskFrame(payload, opcode = 0x1) {
  const len = payload.length;
  let header;
  if (len < 126) header = Buffer.from([0x80 | opcode, 0x80 | len]);
  else if (len < 65536) { header = Buffer.alloc(4); header[0] = 0x80 | opcode; header[1] = 0x80 | 126; header.writeUInt16BE(len, 2); }
  else { header = Buffer.alloc(10); header[0] = 0x80 | opcode; header[1] = 0x80 | 127; header.writeBigUInt64BE(BigInt(len), 2); }
  const mask = crypto.randomBytes(4);
  const body = Buffer.alloc(len);
  for (let i = 0; i < len; i++) body[i] = payload[i] ^ mask[i & 3];
  return Buffer.concat([header, mask, body]);
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

class SatoriClient {
  constructor(options = {}) {
    this.host = options.host || '127.0.0.1';
    this.port = options.port || loadPort();
    this.token = options.token !== undefined ? options.token : loadToken();
    this.identifyBody = options.identifyBody || null;
    this.events = [];
    this.eventHandlers = [];
    this.ready = null;
    this.socket = null;
    this.buffer = Buffer.alloc(0);
    this.upgraded = false;
    this.closed = false;
    this.fragment = Buffer.alloc(0);
    this.fragmentOpcode = 0;
  }

  /** 打开事件通道并发 IDENTIFY，等 READY。 */
  async connect(timeoutMs = 10000) {
    await this.upgrade(timeoutMs);
    const identify = { op: OP_IDENTIFY, body: Object.assign({ token: this.token }, this.identifyBody || {}) };
    this.write(JSON.stringify(identify));
    const deadline = Date.now() + timeoutMs;
    while (!this.ready) {
      if (this.closed) throw new Error('connection closed before READY');
      if (Date.now() > deadline) throw new Error('READY timeout');
      await delay(50);
    }
    return this.ready;
  }

  upgrade(timeoutMs) {
    return new Promise((resolve, reject) => {
      const key = crypto.randomBytes(16).toString('base64');
      const query = this.token ? '?access_token=' + encodeURIComponent(this.token) : '';
      const socket = net.createConnection({ host: this.host, port: this.port });
      this.socket = socket;
      const timer = setTimeout(() => {
        socket.destroy();
        reject(new Error('websocket upgrade timeout'));
      }, timeoutMs);
      socket.on('connect', () => {
        socket.write(
          'GET /v1/events' + query + ' HTTP/1.1\r\n' +
          'Host: ' + this.host + ':' + this.port + '\r\n' +
          'Upgrade: websocket\r\n' +
          'Connection: Upgrade\r\n' +
          'Sec-WebSocket-Key: ' + key + '\r\n' +
          'Sec-WebSocket-Version: 13\r\n' +
          'Authorization: Bearer ' + this.token + '\r\n\r\n');
      });
      socket.on('data', (chunk) => {
        this.buffer = Buffer.concat([this.buffer, chunk]);
        if (!this.upgraded) {
          const end = this.buffer.indexOf('\r\n\r\n');
          if (end < 0) return;
          const head = this.buffer.subarray(0, end).toString();
          this.buffer = this.buffer.subarray(end + 4);
          if (!head.startsWith('HTTP/1.1 101')) {
            clearTimeout(timer);
            socket.destroy();
            reject(new Error('upgrade rejected: ' + head.split('\r\n')[0]));
            return;
          }
          this.upgraded = true;
          clearTimeout(timer);
          resolve();
        }
        this.drain();
      });
      socket.on('error', (error) => { clearTimeout(timer); this.closed = true; reject(error); });
      socket.on('close', () => {
        this.closed = true;
        for (const h of this.eventHandlers) {
          if (h.onClose) h.onClose();
        }
      });
    });
  }

  drain() {
    while (this.buffer.length >= 2) {
      const b0 = this.buffer[0];
      const b1 = this.buffer[1];
      const fin = (b0 & 0x80) !== 0;
      const opcode = b0 & 0x0f;
      let length = b1 & 0x7f;
      let offset = 2;
      if (length === 126) {
        if (this.buffer.length < 4) return;
        length = this.buffer.readUInt16BE(2);
        offset = 4;
      } else if (length === 127) {
        if (this.buffer.length < 10) return;
        length = Number(this.buffer.readBigUInt64BE(2));
        offset = 10;
      }
      if (this.buffer.length < offset + length) return;
      const payload = this.buffer.subarray(offset, offset + length);
      this.buffer = this.buffer.subarray(offset + length);
      if (opcode === 0x8) { this.close(); return; }
      if (opcode === 0x9) { this.writeRaw(payload, 0xA); continue; }
      if (opcode === 0xA) continue;
      if (opcode === 0x0) {
        this.fragment = Buffer.concat([this.fragment, payload]);
        if (fin) { this.dispatch(this.fragmentOpcode, this.fragment); this.fragment = Buffer.alloc(0); this.fragmentOpcode = 0; }
        continue;
      }
      if (fin) this.dispatch(opcode, payload);
      else { this.fragmentOpcode = opcode; this.fragment = Buffer.from(payload); }
    }
  }

  dispatch(opcode, payload) {
    if (opcode !== 0x1) return;
    let message;
    try { message = JSON.parse(payload.toString('utf8')); } catch (_) { return; }
    if (message.op === OP_READY) { this.ready = message.body || {}; return; }
    if (message.op !== OP_EVENT) return;
    const body = message.body || {};
    this.events.push(body);
    for (const handler of this.eventHandlers) {
      try { handler(body); } catch (_) {}
    }
  }

  write(text) { this.writeRaw(Buffer.from(text, 'utf8'), 0x1); }

  writeRaw(payload, opcode) {
    if (!this.socket || this.closed) return;
    this.socket.write(maskFrame(payload, opcode));
  }

  onEvent(handler) { this.eventHandlers.push(handler); }

  /** 动作走 HTTP。返回响应体（含 status/retcode/data）。 */
  call(action, params = {}, timeoutMs = 20000) {
    const body = Buffer.from(JSON.stringify(params || {}));
    const headers = {
      'Content-Type': 'application/json',
      'Content-Length': body.length,
      'Authorization': 'Bearer ' + this.token,
      'Satori-Platform': 'red',
    };
    if (this.token) headers['Satori-Platform-Token'] = this.token;
    const login = (this.ready && Array.isArray(this.ready.logins) && this.ready.logins[0]) || null;
    if (login && login.user && login.user.id) headers['Satori-User-ID'] = String(login.user.id);
    return new Promise((resolve, reject) => {
      const req = http.request({
        host: this.host, port: this.port,
        path: '/v1/' + action, method: 'POST', headers,
      }, (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let json = null;
          try { json = JSON.parse(text); } catch (_) {}
          resolve({ http_status: res.statusCode, json, text });
        });
      });
      req.on('error', reject);
      req.setTimeout(timeoutMs, () => { req.destroy(); reject(new Error('http timeout ' + action)); });
      req.end(body);
    });
  }

  /** call 的严格版本：HTTP 200 才算成功。失败响应体是 {"message":"..."}。 */
  async callOk(action, params = {}) {
    const res = await this.call(action, params);
    if (res.http_status !== 200) {
      throw new Error(action + ' -> ' + res.http_status + ' ' + (res.json?.message || res.text));
    }
    return res.json;
  }

  /** 期待失败：断言状态码与错误文案片段。 */
  async callExpect(action, params, status, fragment) {
    const res = await this.call(action, params);
    if (res.http_status !== status) {
      throw new Error(action + ' expected HTTP ' + status + ' got ' + res.http_status + ' ' + res.text);
    }
    const message = res.json?.message || res.text || '';
    if (fragment && !message.includes(fragment)) {
      throw new Error(action + ' expected message containing ' + JSON.stringify(fragment) + ' got ' + JSON.stringify(message));
    }
    return message;
  }

  async waitFor(predicate, timeoutMs = 15000, description = 'event') {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const hit = this.events.find(predicate);
      if (hit) return hit;
      if (Date.now() > deadline) {
        throw new Error('no matching ' + description + ' within ' + timeoutMs + 'ms; got ' +
          JSON.stringify(this.events.map((e) => ({ t: e.type, d: e.detail_type, s: e.sub_type, id: e.id }))));
      }
      await delay(150);
    }
  }

  ping() { this.write(JSON.stringify({ op: OP_PING })); }

  close() {
    if (this.closed) return;
    try { this.writeRaw(Buffer.alloc(0), 0x8); } catch (_) {}
    this.closed = true;
    try { this.socket.destroy(); } catch (_) {}
  }
}

async function connect(options) {
  const client = new SatoriClient(options);
  await client.connect();
  return client;
}

module.exports = { SatoriClient, connect, loadToken, loadPort, delay };
