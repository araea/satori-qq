'use strict';

const fs = require('fs');

const groupId = Number(process.env.ONEBOT_TEST_GROUP || '280183116');
const stamp = Date.now().toString(36);
const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const configPath = '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/onebot-qq.json';
let token = 'onebot-qq-token';
try {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (typeof config.token === 'string') token = config.token;
} catch (_) {}

const socket = new WebSocket(`ws://127.0.0.1:3001/?access_token=${encodeURIComponent(token)}`);
const pending = new Map();
let sequence = 1;
const cleanup = { messageIds: [], fileId: '', folderId: '' };

function call(action, params = {}, timeoutMs = 30000) {
  const echo = `forward-rich-${sequence++}`;
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

async function sweep() {
  for (const id of cleanup.messageIds.splice(0)) {
    try { await call('delete_msg', { message_id: id }); } catch (_) {}
  }
  try {
    if (cleanup.fileId) await call('delete_group_file', { group_id: groupId, file_id: cleanup.fileId });
  } catch (_) {}
  try {
    if (cleanup.folderId) await call('delete_group_folder', { group_id: groupId, folder_id: cleanup.folderId });
  } catch (_) {}
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

    const img = requireOk(await call('send_group_msg', {
      group_id: groupId,
      message: [{ type: 'image', data: { file: 'base64://' + png } }],
    }), 'send image seed');
    const imageMessageId = Number(img.message_id || 0);
    if (imageMessageId) cleanup.messageIds.push(imageMessageId);

    const uploaded = requireOk(await call('upload_group_file', {
      group_id: groupId,
      file: 'base64://' + Buffer.from(`forward-rich ${stamp}\n`).toString('base64'),
      name: `onebot-fwd-${stamp}.txt`,
    }, 40000), 'upload_group_file');
    cleanup.fileId = uploaded.file_id || '';
    const chatFileMsg = Number(uploaded.message_id || 0);
    if (chatFileMsg) cleanup.messageIds.push(chatFileMsg);

    const forwarded = requireOk(await call('send_group_forward_msg', {
      group_id: groupId,
      messages: [
        {
          type: 'node',
          data: {
            user_id: selfId,
            nickname: nick,
            content: [
              { type: 'reply', data: { id: String(imageMessageId) } },
              { type: 'at', data: { qq: selfId } },
              { type: 'text', data: { text: ' fwd-rich ' + stamp } },
              { type: 'image', data: { file: 'base64://' + png } },
              { type: 'file', data: { file_id: cleanup.fileId, name: `onebot-fwd-${stamp}.txt` } },
            ],
          },
        },
      ],
    }, 40000), 'send_group_forward_msg');
    const forwardId = Number(forwarded.message_id || 0);
    if (forwardId) cleanup.messageIds.push(forwardId);
    const resId = forwarded.res_id || forwarded.forward_id;
    if (!resId) throw new Error('forward missing res_id');

    const got = requireOk(await call('get_forward_msg', { id: resId }, 40000), 'get_forward_msg');
    const nodes = Array.isArray(got.messages) ? got.messages : [];
    if (!nodes.length) throw new Error('get_forward_msg returned no nodes');
    const content = nodes[0]?.data?.content || nodes[0]?.content || [];
    const types = typesOf(content);
    const missing = ['reply', 'at', 'text', 'image', 'file'].filter((t) => !types.includes(t));
    if (missing.length) {
      throw new Error('forward nodes missing ' + missing.join(',') + ' got ' + types.join(','));
    }

    await sweep();
    console.log(JSON.stringify({
      status: 'ok',
      group_id: groupId,
      res_id_present: true,
      types,
      cleaned: true,
    }));
    socket.close();
  } catch (error) {
    await sweep();
    console.error(JSON.stringify({ status: 'failed', error: error.message }));
    process.exitCode = 1;
    socket.close();
  }
});
