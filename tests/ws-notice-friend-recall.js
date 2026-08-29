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

function call(action, params = {}, timeoutMs = 25000) {
  const echo = `fr-${sequence++}`;
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

function asList(data) {
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.members)) return data.members;
  return [];
}

function waitRecall(id, timeoutMs = 12000) {
  const start = Date.now();
  return new Promise((resolve, reject) => {
    const check = () => {
      const hit = notices.find((n) =>
        n.notice_type === 'friend_recall' && (id === 0 || n.message_id === id));
      if (hit) {
        clearInterval(timer);
        resolve(hit);
      } else if (Date.now() - start > timeoutMs) {
        clearInterval(timer);
        reject(new Error('no friend_recall notices=' + notices.map((n) => n.notice_type).join(',')));
      }
    };
    const timer = setInterval(check, 200);
    check();
  });
}

function pickTarget(selfId, members, friends) {
  const friendSet = new Set(friends.map((f) => Number(f.user_id)).filter((id) => id && id !== selfId));
  const memberIds = members.map((m) => Number(m.user_id)).filter((id) => id && id !== selfId);
  const both = memberIds.find((id) => friendSet.has(id));
  if (both) return { user_id: both, via: 'friend+member' };
  if (memberIds.length) return { user_id: memberIds[0], via: 'member' };
  const friend = friends.find((f) => Number(f.user_id) !== selfId);
  if (friend) return { user_id: Number(friend.user_id), via: 'friend' };
  return null;
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
  const out = { login: false, sent: false, recall: false, history: false };
  try {
    const login = requireOk(await call('get_login_info'), 'get_login_info');
    const selfId = Number(login.user_id || 0);
    out.login = !!selfId;
    const members = asList(requireOk(await call('get_group_member_list', { group_id: groupId }), 'get_group_member_list'));
    const friendsRaw = await call('get_friend_list');
    const friends = asList(requireOk(friendsRaw, 'get_friend_list'));
    const target = pickTarget(selfId, members, friends);
    if (!target) throw new Error('no private-chat target');
    out.via = target.via;

    const sent = requireOk(await call('send_private_msg', {
      user_id: target.user_id,
      message: `ob-friend-recall-${stamp}`,
    }), 'send_private_msg');
    messageId = sent.message_id;
    out.sent = messageId > 0;
    requireOk(await call('delete_msg', { message_id: messageId }), 'delete_msg');
    const ev = await waitRecall(messageId);
    out.recall = ev.notice_type === 'friend_recall' && ev.message_id === messageId;
    out.notice_type = ev.notice_type;

    const hist = requireOk(await call('get_friend_msg_history', {
      user_id: target.user_id,
      count: 5,
    }), 'get_friend_msg_history');
    out.history = Array.isArray(hist.messages);
    out.history_count = Array.isArray(hist.messages) ? hist.messages.length : 0;
    out.message_id = messageId;
    console.log(JSON.stringify(out));
    if (!out.login || !out.sent || !out.recall) process.exitCode = 1;
  } catch (error) {
    console.log(JSON.stringify({ ...out, error: String(error && error.message || error) }));
    process.exitCode = 1;
  } finally {
    socket.close();
  }
});
