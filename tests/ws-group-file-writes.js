'use strict';

const fs = require('fs');

const groupId = Number(process.env.SATORI_TEST_GROUP || '280183116');
const stamp = Date.now().toString(36);
const folderName = `satori-tmp-${stamp}`;
const renamedName = `${folderName}-r`;
const configPath = '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json';
let token = 'satori-qq-token';
try {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (typeof config.token === 'string') token = config.token;
} catch (_) {}

const socket = new WebSocket(`ws://127.0.0.1:3001/?access_token=${encodeURIComponent(token)}`);
const pending = new Map();
let sequence = 1;
let folderId = '';

function call(action, params = {}, timeoutMs = 20000) {
  const echo = `group-file-writes-${sequence++}`;
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

function foldersOf(root) {
  return Array.isArray(root.folders) ? root.folders : [];
}

function hasFolder(root, id, name) {
  return foldersOf(root).some((folder) =>
    (id && folder.folder_id === id) || (name && folder.folder_name === name));
}

async function cleanup() {
  if (!folderId) return;
  try {
    await call('delete_group_folder', { group_id: groupId, folder_id: folderId });
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
      folder_name: folderName,
    }), 'create_group_file_folder');
    folderId = created.folder_id || '';
    if (!folderId) throw new Error('create returned no folder_id');

    const afterCreate = requireOk(await call('get_group_root_files', { group_id: groupId }),
      'get_group_root_files after create');
    if (!hasFolder(afterCreate, folderId, folderName)) {
      throw new Error('created folder not listed in root');
    }

    const renamed = requireOk(await call('rename_group_folder', {
      group_id: groupId,
      folder_id: folderId,
      new_folder_name: renamedName,
    }), 'rename_group_folder');
    const afterRename = requireOk(await call('get_group_root_files', { group_id: groupId }),
      'get_group_root_files after rename');
    if (!hasFolder(afterRename, folderId, renamedName)) {
      throw new Error('renamed folder not listed in root');
    }

    requireOk(await call('delete_group_folder', {
      group_id: groupId,
      folder_id: folderId,
    }), 'delete_group_folder');
    const afterDelete = requireOk(await call('get_group_root_files', { group_id: groupId }),
      'get_group_root_files after delete');
    const leftover = hasFolder(afterDelete, folderId, renamedName);
    folderId = leftover ? folderId : '';
    if (leftover) throw new Error('deleted folder still listed in root');

    console.log(JSON.stringify({
      status: 'ok',
      group_id: groupId,
      created_name: folderName,
      renamed_name: renamedName,
      folder_id: created.folder_id,
      rename_echoed: renamed.folder_id === created.folder_id,
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
