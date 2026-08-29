'use strict';

const fs = require('fs');

const groupId = Number(process.env.SATORI_TEST_GROUP || '280183116');
const configPath = '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json';
let token = 'satori-qq-token';
try {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (typeof config.token === 'string') token = config.token;
} catch (_) {}

function wavBase64() {
  const rate = 8000;
  const samples = rate;
  const out = Buffer.alloc(44 + samples * 2);
  out.write('RIFF', 0); out.writeUInt32LE(out.length - 8, 4); out.write('WAVEfmt ', 8);
  out.writeUInt32LE(16, 16); out.writeUInt16LE(1, 20); out.writeUInt16LE(1, 22);
  out.writeUInt32LE(rate, 24); out.writeUInt32LE(rate * 2, 28);
  out.writeUInt16LE(2, 32); out.writeUInt16LE(16, 34);
  out.write('data', 36); out.writeUInt32LE(samples * 2, 40);
  for (let i = 0; i < samples; i++) {
    const sample = Math.round(Math.sin(2 * Math.PI * 440 * i / rate) * 5000);
    out.writeInt16LE(sample, 44 + i * 2);
  }
  return out.toString('base64');
}

const socket = new WebSocket(`ws://127.0.0.1:3001/?access_token=${encodeURIComponent(token)}`);
const pending = new Map();
const notices = [];
let sequence = 1;

function call(action, params = {}, timeoutMs = 40000) {
  const echo = `rec-${sequence++}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(echo);
      reject(new Error(`${action} timeout`));
    }, timeoutMs);
    pending.set(echo, {
      resolve: (value) => { clearTimeout(timer); resolve(value); },
      reject: (error) => { clearTimeout(timer); reject(error); },
    });
    socket.send(JSON.stringify({ action, params, echo }));
  });
}

function requireOk(response, action) {
  if (response?.status !== 'ok' || response?.retcode !== 0) {
    throw new Error(`${action} failed retcode=${response?.retcode}: ${response?.message || response?.wording || ''}`);
  }
  return response.data || {};
}

function recordFile(message) {
  const segs = Array.isArray(message?.message) ? message.message : [];
  const rec = segs.find((s) => s?.type === 'record' && s?.data?.file);
  return rec ? rec.data.file : '';
}

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

socket.addEventListener('message', (event) => {
  let message;
  try { message = JSON.parse(String(event.data)); } catch (_) { return; }
  if (message.post_type === 'message' || message.post_type === 'notice') {
    notices.push(message);
    return;
  }
  if (!message.echo || !pending.has(String(message.echo))) return;
  const waiter = pending.get(String(message.echo));
  pending.delete(String(message.echo));
  waiter.resolve(message);
});

socket.addEventListener('close', () => {
  for (const waiter of pending.values()) waiter.reject(new Error('socket closed'));
  pending.clear();
});

socket.addEventListener('error', () => {
  for (const waiter of pending.values()) waiter.reject(new Error('websocket error'));
  pending.clear();
});

socket.addEventListener('open', async () => {
  const out = { login: false, sent: false, downloaded: false };
  try {
    const login = requireOk(await call('get_login_info'), 'get_login_info');
    out.login = !!login.user_id;
    const sent = requireOk(await call('send_group_msg', {
      group_id: groupId,
      message: [{ type: 'record', data: { file: `base64://${wavBase64()}` } }],
    }, 40000), 'send_group_msg record');
    const messageId = sent.message_id;
    out.sent = messageId > 0;
    out.message_id = messageId;

    let file = recordFile(notices.find((n) =>
      n.post_type === 'message' && n.message_type === 'group' && n.message_id === messageId));
    if (file) out.via = 'event';
    for (let attempt = 0; !file && attempt < 10; attempt++) {
      if (attempt > 0) await delay(1500);
      const hist = requireOk(await call('get_group_msg_history', {
        group_id: groupId,
        count: 20,
      }), 'get_group_msg_history');
      const hit = (hist.messages || []).find((m) => m.message_id === messageId);
      file = recordFile(hit);
      if (file) out.via = 'history-id';
    }

    out.file_id = file ? 'yes' : '';
    if (!file) throw new Error('no record file id after send');
    const got = requireOk(await call('get_record', { file }), 'get_record');
    out.downloaded = typeof got.file === 'string' && got.file.startsWith('/');
    out.file_size = got.file_size || 0;
    out.resource_type = got.resource_type || '';
    console.log(JSON.stringify(out));
    if (!out.login || !out.sent || !out.downloaded) process.exitCode = 1;
  } catch (error) {
    console.log(JSON.stringify({ ...out, error: String(error && error.message || error) }));
    process.exitCode = 1;
  } finally {
    socket.close();
  }
});
