'use strict';

const fs = require('fs');

const groupId = Number(process.env.ONEBOT_TEST_GROUP || '280183116');
const configPath = '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/onebot-qq.json';
let token = 'onebot-qq-token';
try {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (typeof config.token === 'string') token = config.token;
} catch (_) {}

const socket = new WebSocket(`ws://127.0.0.1:3001/?access_token=${encodeURIComponent(token)}`);
const pending = new Map();
let sequence = 1;

function call(action, params = {}, timeoutMs = 20000) {
  const echo = `hc-${sequence++}`;
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

socket.addEventListener('message', (event) => {
  let message;
  try { message = JSON.parse(String(event.data)); } catch (_) { return; }
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
  const out = { group: false, paged: false };
  try {
    const first = requireOk(await call('get_group_msg_history', {
      group_id: groupId,
      count: 5,
    }), 'get_group_msg_history');
    const messages = Array.isArray(first.messages) ? first.messages : [];
    out.group = messages.length > 0;
    out.first_count = messages.length;
    const ids = messages.map((m) => Number(m.message_id || m.real_id || 0)).filter((id) => id > 0);
    out.ids = ids;
    out.has_message_seq = messages.some((m) => m.message_seq != null && Number(m.message_seq) > 0);
    const seqs = messages.map((m) => Number(m.message_seq || 0)).filter((id) => id > 0);
    out.seqs = seqs;
    if (ids.length >= 2) {
      const cursor = seqs.length ? seqs[seqs.length - 1] : ids[ids.length - 1];
      const page = requireOk(await call('get_group_msg_history', {
        group_id: groupId,
        message_seq: cursor,
        count: 5,
      }), 'get_group_msg_history_seq');
      const pageIds = (Array.isArray(page.messages) ? page.messages : [])
        .map((m) => Number(m.message_id || m.real_id || 0)).filter((id) => id > 0);
      out.page_count = pageIds.length;
      out.page_ids = pageIds;
      const overlap = pageIds.filter((id) => ids.includes(id));
      out.overlap = overlap.length;
      out.paged = pageIds.length > 0 && overlap.length < Math.min(ids.length, pageIds.length);
      out.same_as_first = pageIds.join(',') === ids.join(',');
    }
    console.log(JSON.stringify(out));
    if (!out.group) process.exitCode = 1;
  } catch (error) {
    console.log(JSON.stringify({ ...out, error: String(error && error.message || error) }));
    process.exitCode = 1;
  } finally {
    socket.close();
  }
});
