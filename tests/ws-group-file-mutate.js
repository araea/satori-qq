'use strict';

const fs = require('fs');

const groupId = Number(process.env.ONEBOT_TEST_GROUP || '280183116');
const stamp = Date.now().toString(36);
const fileName = `onebot-tmp-${stamp}.txt`;
const payload = `onebot-qq write test ${stamp}\n`;
const fileB64 = Buffer.from(payload).toString('base64');

const configPath = '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/onebot-qq.json';
let token = 'onebot-qq-token';
try {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (typeof config.token === 'string') token = config.token;
} catch (_) {}

const socket = new WebSocket(`ws://127.0.0.1:3001/?access_token=${encodeURIComponent(token)}`);
const pending = new Map();
let sequence = 1;
let folderId = '';
let fileId = '';
let messageId = 0;

function call(action, params = {}, timeoutMs = 25000) {
  const echo = `group-file-mutate-${sequence++}`;
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

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function findFile(root, name) {
  const files = Array.isArray(root.files) ? root.files : [];
  return files.find((file) => file.file_name === name) || null;
}

async function cleanup() {
  try {
    if (fileId) {
      await call('delete_group_file', { group_id: groupId, file_id: fileId });
    }
  } catch (_) {}
  try {
    if (folderId) {
      await call('delete_group_folder', { group_id: groupId, folder_id: folderId });
    }
  } catch (_) {}
  try {
    if (messageId) await call('delete_msg', { message_id: messageId });
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
    const created = requireOk(await call('create_group_file_folder', {
      group_id: groupId,
      folder_name: `onebot-dir-${stamp}`,
    }), 'create_group_file_folder');
    folderId = created.folder_id || '';

    const uploaded = requireOk(await call('upload_group_file', {
      group_id: groupId,
      file: 'base64://' + fileB64,
      name: fileName,
    }), 'upload_group_file');
    messageId = Number(uploaded.message_id || 0);

    let listed = {};
    let found = null;
    for (let i = 0; i < 8 && !found; i++) {
      await sleep(1500);
      listed = requireOk(await call('get_group_root_files', { group_id: groupId }),
        'get_group_root_files poll');
      found = findFile(listed, fileName);
    }
    if (!found) {
      throw new Error('uploaded chat file did not appear in group file system');
    }
    fileId = found.file_id;
    const busid = found.busid || 102;

    const renamedName = `onebot-tmp-${stamp}-r.txt`;
    requireOk(await call('rename_group_file', {
      group_id: groupId,
      file_id: fileId,
      parent_directory: '/',
      new_name: renamedName,
      busid,
    }), 'rename_group_file');

    requireOk(await call('move_group_file', {
      group_id: groupId,
      file_id: fileId,
      parent_directory: '/',
      target_directory: folderId,
      busid,
    }), 'move_group_file');

    const inFolder = requireOk(await call('get_group_files_by_folder', {
      group_id: groupId,
      folder_id: folderId,
    }), 'get_group_files_by_folder');
    const moved = findFile(inFolder, renamedName) || findFile(inFolder, fileName);
    if (!moved) throw new Error('moved file not listed in target folder');

    requireOk(await call('delete_group_file', {
      group_id: groupId,
      file_id: fileId,
      busid,
    }), 'delete_group_file');
    fileId = '';

    requireOk(await call('delete_group_folder', {
      group_id: groupId,
      folder_id: folderId,
    }), 'delete_group_folder');
    folderId = '';

    if (messageId) {
      try { await call('delete_msg', { message_id: messageId }); } catch (_) {}
      messageId = 0;
    }

    console.log(JSON.stringify({
      status: 'ok',
      group_id: groupId,
      uploaded_name: fileName,
      renamed_name: renamedName,
      chat_message_id: uploaded.message_id || 0,
      appeared_in_fs: true,
      renamed: true,
      moved: true,
      cleaned: true,
    }));
    socket.close();
  } catch (error) {
    await cleanup();
    console.error(JSON.stringify({ status: 'failed', error: error.message }));
    process.exitCode = 1;
    socket.close();
  }
});

setTimeout(() => {
  cleanup().finally(() => {
    console.error(JSON.stringify({ status: 'failed', error: 'overall timeout' }));
    process.exitCode = 1;
    socket.close();
  });
}, 90000).unref();
