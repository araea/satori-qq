'use strict';

// ayjx-shaped merge-forward: send_msg + node[], not send_*_forward_msg.
const fs = require('fs');

const groupId = Number(process.env.SATORI_TEST_GROUP || '280183116');
const stamp = Date.now().toString(36);
const configPath = '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json';
let token = 'satori-qq-token';
try {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (typeof config.token === 'string') token = config.token;
} catch (_) {}

const socket = new WebSocket(`ws://127.0.0.1:3001/?access_token=${encodeURIComponent(token)}`);
const pending = new Map();
let sequence = 1;

function call(action, params = {}, timeoutMs = 40000) {
  const echo = `send-msg-fwd-${sequence++}`;
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

function typesOf(content) {
  return (Array.isArray(content) ? content : []).map((seg) => seg.type);
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
  try {
    const login = requireOk(await call('get_login_info'), 'get_login_info');
    const selfId = String(login.user_id || '');
    const nick = login.nickname || 'self';

    const forwarded = requireOk(await call('send_msg', {
      message_type: 'group',
      group_id: groupId,
      message: [
        {
          type: 'node',
          data: {
            user_id: selfId,
            nickname: nick,
            content: [{ type: 'text', data: { text: 'ayjx-node-a ' + stamp } }],
          },
        },
        {
          type: 'node',
          data: {
            user_id: '10000',
            nickname: 'System Bot',
            content: [{ type: 'text', data: { text: 'ayjx-node-b ' + stamp } }],
          },
        },
      ],
    }), 'send_msg node forward');

    const messageId = Number(forwarded.message_id || 0);
    if (!messageId) throw new Error('send_msg node forward missing message_id');

    let resId = forwarded.res_id || forwarded.forward_id;
    if (!resId) {
      await new Promise((r) => setTimeout(r, 1200));
      const hist = requireOk(await call('get_group_msg_history', {
        group_id: groupId,
        count: 12,
      }), 'get_group_msg_history');
      const messages = Array.isArray(hist.messages) ? hist.messages : [];
      for (let i = messages.length - 1; i >= 0; i--) {
        const segs = messages[i]?.message;
        const fwd = (Array.isArray(segs) ? segs : []).find((s) => s && s.type === 'forward');
        if (!fwd) continue;
        resId = fwd.data?.id || fwd.data?.resid || '';
        break;
      }
    }
    if (!resId && !forwarded.native_forward) throw new Error('forward missing res_id');

    let innerTypes = [];
    let nodeCount = 0;
    if (resId) {
      const got = requireOk(await call('get_forward_msg', { id: resId }, 40000), 'get_forward_msg');
      const nodes = Array.isArray(got.messages) ? got.messages : [];
      nodeCount = nodes.length;
      if (nodeCount < 2) throw new Error('expected 2 nodes got ' + nodeCount);
      const inner = nodes[0]?.data?.content || nodes[0]?.content || [];
      innerTypes = typesOf(inner);
      if (!innerTypes.includes('text')) {
        throw new Error('forward nodes missing text got ' + innerTypes.join(','));
      }
    }

    let cardType = 'unknown';
    try {
      await new Promise((r) => setTimeout(r, 800));
      const msg = requireOk(await call('get_msg', { message_id: messageId }), 'get_msg');
      const outer = typesOf(msg.message);
      if (outer.includes('forward')) cardType = 'forward';
      else if (outer.includes('json')) cardType = 'json';
      else cardType = outer.join(',') || 'empty';
    } catch (e) {
      cardType = 'get_msg:' + e.message;
    }

    console.log(JSON.stringify({
      status: 'ok',
      group_id: groupId,
      message_id: messageId,
      native_forward: !!forwarded.native_forward,
      res_id_present: !!resId,
      node_count: nodeCount,
      card_type: cardType,
      inner_types: innerTypes,
    }));
    socket.close();
  } catch (error) {
    console.error(JSON.stringify({ status: 'failed', error: error.message }));
    process.exitCode = 1;
    socket.close();
  }
});
