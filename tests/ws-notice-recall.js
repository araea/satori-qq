'use strict';

const fs = require('fs');

const groupId = Number(process.env.ONEBOT_TEST_GROUP || '280183116');
const stamp = Date.now().toString(36);
const configPath = '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/onebot-qq.json';
let token = 'onebot-qq-token';
try {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (typeof config.token === 'string') token = config.token;
} catch (_) {}

const socket = new WebSocket(`ws://127.0.0.1:3001/?access_token=${encodeURIComponent(token)}`);
const pending = new Map();
const notices = [];
let sequence = 1;
let messageId = 0;

function call(action, params = {}, timeoutMs = 20000) {
  const echo = `notice-${sequence++}`;
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

function waitRecall(id, timeoutMs = 12000) {
  const start = Date.now();
  return new Promise((resolve, reject) => {
    const check = () => {
      const hit = notices.find((n) =>
        (n.notice_type === 'group_recall' || n.notice_type === 'friend_recall') &&
        (id === 0 || n.message_id === id));
      if (hit) {
        clearInterval(timer);
        resolve(hit);
      } else if (Date.now() - start > timeoutMs) {
        clearInterval(timer);
        reject(new Error('no group_recall event notices=' + notices.length));
      }
    };
    const timer = setInterval(check, 200);
    check();
  });
}

socket.addEventListener('message', (event) => {
  let message;
  try { message = JSON.parse(String(event.data)); } catch (_) { return; }
  if (message.post_type === 'notice' || message.post_type === 'request') {
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
  const out = { group_id: groupId, login: false, recall: false, history: false };
  try {
    const login = requireOk(await call('get_login_info'), 'get_login_info');
    out.login = !!login.user_id;
    const sent = requireOk(await call('send_group_msg', {
      group_id: groupId,
      message: `ob-recall-${stamp}`,
    }), 'send_group_msg');
    messageId = sent.message_id;
    requireOk(await call('delete_msg', { message_id: messageId }), 'delete_msg');
    const ev = await waitRecall(messageId);
    out.recall = ev.notice_type === 'group_recall' && ev.group_id === groupId;
    const hist = requireOk(await call('get_group_msg_history', {
      group_id: groupId,
      count: 5,
    }), 'get_group_msg_history');
    out.history = Array.isArray(hist.messages);
    out.history_count = hist.messages.length;
    out.message_id = messageId;
    out.notice_type = ev.notice_type;
    console.log(JSON.stringify(out));
    if (!out.login || !out.recall || !out.history) process.exitCode = 1;
  } catch (error) {
    if (messageId) {
      try { await call('delete_msg', { message_id: messageId }); } catch (_) {}
    }
    console.log(JSON.stringify({ ...out, error: String(error && error.message || error) }));
    process.exitCode = 1;
  } finally {
    socket.close();
  }
});
