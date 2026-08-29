'use strict';

const fs = require('fs');

const groupId = Number(process.env.SATORI_TEST_GROUP || '280183116');
const scanFolders = process.env.SATORI_SCAN_FOLDERS === '1';
const scanLimit = Math.max(1, Number(process.env.SATORI_SCAN_LIMIT || '40'));
const configPath = '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json';
let token = 'satori-qq-token';
try {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (typeof config.token === 'string') token = config.token;
} catch (_) {}

const socket = new WebSocket(`ws://127.0.0.1:3001/?access_token=${encodeURIComponent(token)}`);
const pending = new Map();
let sequence = 1;

function call(action, params = {}, timeoutMs = 20000) {
  const echo = `group-files-${sequence++}`;
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
  try {
    const system = requireOk(await call('get_group_file_system_info', { group_id: groupId }),
      'get_group_file_system_info');
    const root = requireOk(await call('get_group_root_files', { group_id: groupId }),
      'get_group_root_files');
    const files = Array.isArray(root.files) ? root.files : [];
    const folders = Array.isArray(root.folders) ? root.folders : [];
    let child;
    let folderSampleFound = folders.length > 0;
    let groupsScanned = 0;
    if (folders.length > 0) {
      child = requireOk(await call('get_group_files_by_folder', {
        group_id: groupId,
        folder_id: folders[0].folder_id,
      }), 'get_group_files_by_folder');
    } else if (scanFolders) {
      const groups = requireOk(await call('get_group_list'), 'get_group_list');
      for (const group of Array.isArray(groups) ? groups : []) {
        if (groupsScanned >= scanLimit) break;
        const candidateGroup = Number(group.group_id || 0);
        if (!candidateGroup || candidateGroup === groupId) continue;
        groupsScanned++;
        try {
          const candidate = requireOk(await call('get_group_root_files', {
            group_id: candidateGroup,
          }, 10000), 'get_group_root_files');
          const candidateFolders = Array.isArray(candidate.folders) ? candidate.folders : [];
          if (candidateFolders.length === 0) continue;
          child = requireOk(await call('get_group_files_by_folder', {
            group_id: candidateGroup,
            folder_id: candidateFolders[0].folder_id,
          }), 'get_group_files_by_folder');
          folderSampleFound = true;
          break;
        } catch (_) {
          // A stale/inaccessible group should not abort the search for a natural folder sample.
        }
      }
    }
    let url;
    if (files.length > 0) {
      url = requireOk(await call('get_group_file_url', {
        group_id: groupId,
        file_id: files[0].file_id,
        busid: files[0].busid,
      }), 'get_group_file_url').url;
    }
    console.log(JSON.stringify({
      status: 'ok',
      file_count: Number(system.file_count || 0),
      limit_count: Number(system.limit_count || 0),
      used_space: Number(system.used_space || 0),
      total_space: Number(system.total_space || 0),
      root_files: files.length,
      root_folders: folders.length,
      child_tested: !!child,
      folder_sample_found: folderSampleFound,
      groups_scanned: groupsScanned,
      child_files: Array.isArray(child?.files) ? child.files.length : 0,
      child_folders: Array.isArray(child?.folders) ? child.folders.length : 0,
      url_tested: typeof url === 'string',
      url_https: typeof url === 'string' && url.startsWith('https://'),
    }));
    socket.close();
  } catch (error) {
    console.error(JSON.stringify({ status: 'failed', error: error.message }));
    process.exitCode = 1;
    socket.close();
  }
});

setTimeout(() => {
  console.error(JSON.stringify({ status: 'failed', error: 'overall timeout' }));
  process.exitCode = 1;
  socket.close();
}, 90000).unref();
