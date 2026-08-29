'use strict';

// Smoke the OneBot actions ayjx plugins actually call. Keeps chat messages.
const fs = require('fs');

const groupId = Number(process.env.ONEBOT_TEST_GROUP || '280183116');
const stamp = Date.now().toString(36);
const configPath = '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/onebot-qq.json';
let token = 'onebot-qq-token';
try {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (typeof config.token === 'string') token = config.token;
} catch (_) {}

const payloadName = `ayjx-smoke-${stamp}.txt`;
const payloadBody = `ayjx-smoke ${stamp}\n`;

const socket = new WebSocket(`ws://127.0.0.1:3001/?access_token=${encodeURIComponent(token)}`);
const pending = new Map();
let sequence = 1;
const results = [];

function call(action, params = {}, timeoutMs = 40000) {
  const echo = `ayjx-smoke-${sequence++}`;
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

function record(name, ok, extra = {}) {
  results.push({ action: name, ok, ...extra });
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
  let groupFileId = '';
  let groupBusid = 0;
  try {
    const login = requireOk(await call('get_login_info'), 'get_login_info');
    const selfId = Number(login.user_id || 0);
    if (!selfId) throw new Error('get_login_info missing user_id');
    record('get_login_info', true, { user_id: selfId, nickname: login.nickname || '' });

    const groups = requireOk(await call('get_group_list'), 'get_group_list');
    const groupArr = Array.isArray(groups) ? groups
      : (Array.isArray(groups.group_list) ? groups.group_list : []);
    const hasTestGroup = groupArr.some((g) => Number(g.group_id) === groupId);
    if (!hasTestGroup) throw new Error('get_group_list missing test group ' + groupId);
    record('get_group_list', true, { count: groupArr.length, has_test_group: true });

    const member = requireOk(await call('get_group_member_info', {
      group_id: groupId,
      user_id: selfId,
    }), 'get_group_member_info');
    record('get_group_member_info', true, {
      role: member.role || '',
      title: member.title || member.special_title || '',
    });

    const sent = requireOk(await call('send_msg', {
      message_type: 'group',
      group_id: groupId,
      message: [{ type: 'text', data: { text: 'ayjx-smoke text ' + stamp } }],
    }), 'send_msg text');
    const messageId = Number(sent.message_id || 0);
    if (!messageId) throw new Error('send_msg missing message_id');
    record('send_msg text', true, { message_id: messageId });

    await new Promise((r) => setTimeout(r, 800));
    const hist = requireOk(await call('get_group_msg_history', {
      group_id: groupId,
      count: 20,
    }), 'get_group_msg_history helper');
    const histMsgs = Array.isArray(hist.messages) ? hist.messages : [];
    // ayjx get_msg is used on incoming/reply ids. send_msg's own id has no MsgRecord.
    let getMsgId = 0;
    let getTypes = [];
    for (let i = histMsgs.length - 1; i >= 0; i--) {
      const id = Number(histMsgs[i]?.message_id || 0);
      if (!id) continue;
      const resp = await call('get_msg', { message_id: id });
      if (resp?.status === 'ok' && resp?.retcode === 0) {
        getMsgId = id;
        const segs = Array.isArray(resp.data?.message) ? resp.data.message : [];
        getTypes = segs.map((s) => s && s.type);
        break;
      }
    }
    if (!getMsgId) throw new Error('get_msg: no history message_id rendered');
    record('get_msg', true, { message_id: getMsgId, types: getTypes });

    requireOk(await call('set_msg_emoji_like', {
      message_id: getMsgId,
      emoji_id: 124,
      set: true,
    }), 'set_msg_emoji_like');
    record('set_msg_emoji_like', true, { emoji_id: 124, message_id: getMsgId });

    const forwarded = requireOk(await call('send_msg', {
      message_type: 'group',
      group_id: groupId,
      message: [
        {
          type: 'node',
          data: {
            user_id: String(selfId),
            nickname: login.nickname || 'self',
            content: [{ type: 'text', data: { text: 'ayjx-smoke node ' + stamp } }],
          },
        },
      ],
    }), 'send_msg node');
    record('send_msg node', true, {
      message_id: Number(forwarded.message_id || 0),
      native_forward: !!forwarded.native_forward,
      res_id: !!(forwarded.res_id || forwarded.forward_id),
    });

    const title = String(member.title || member.special_title || '');
    requireOk(await call('set_group_special_title', {
      group_id: groupId,
      user_id: selfId,
      special_title: title,
      duration: -1,
    }), 'set_group_special_title');
    record('set_group_special_title', true, { wrote_back: title === '' ? '(empty)' : 'current' });

    const fileSpec = 'base64://' + Buffer.from(payloadBody).toString('base64');
    const uploaded = requireOk(await call('upload_group_file', {
      group_id: groupId,
      file: fileSpec,
      name: payloadName,
    }, 60000), 'upload_group_file');
    groupFileId = uploaded.file_id || '';
    groupBusid = Number(uploaded.busid || uploaded.bus_id || 0);
    if (!groupFileId) throw new Error('upload_group_file missing file_id');
    record('upload_group_file', true, { file_id_len: groupFileId.length });

    let privateOk = false;
    let privateDetail = '';
    let friendId = 0;
    try {
      const friends = requireOk(await call('get_friend_list'), 'get_friend_list helper');
      const friendArr = Array.isArray(friends) ? friends
        : (Array.isArray(friends.friend_list) ? friends.friend_list : []);
      const other = friendArr.find((f) => Number(f.user_id) && Number(f.user_id) !== selfId);
      friendId = other ? Number(other.user_id) : 0;
    } catch (e) {
      privateDetail = 'friend_list:' + e.message;
    }
    if (friendId) {
      const priv = requireOk(await call('upload_private_file', {
        user_id: friendId,
        file: fileSpec,
        name: payloadName,
      }, 60000), 'upload_private_file');
      const pfid = priv.file_id || '';
      const pmid = Number(priv.message_id || 0);
      if (!pfid && !pmid) throw new Error('upload_private_file missing file_id and message_id');
      privateOk = true;
      privateDetail = pfid ? ('file_id_len=' + pfid.length) : ('message_id=' + pmid);
    } else if (!privateDetail) {
      privateDetail = 'no friend target';
    }
    record('upload_private_file', privateOk, { detail: privateDetail });

    const delMissing = await call('delete_msg', { message_id: 2147483647 });
    const delOk = delMissing?.retcode === 1404 || delMissing?.retcode === 0;
    record('delete_msg missing-id', delOk, {
      retcode: delMissing?.retcode,
      note: 'did not recall smoke messages',
    });
    if (!delOk) throw new Error('delete_msg unexpected retcode=' + delMissing?.retcode);

    if (groupFileId) {
      requireOk(await call('delete_group_file', {
        group_id: groupId,
        file_id: groupFileId,
        busid: groupBusid,
      }), 'delete_group_file cleanup');
    }

    const failed = results.filter((r) => !r.ok);
    console.log(JSON.stringify({
      status: failed.length ? 'failed' : 'ok',
      group_id: groupId,
      results,
    }));
    if (failed.length) process.exitCode = 1;
    socket.close();
  } catch (error) {
    if (groupFileId) {
      try {
        await call('delete_group_file', {
          group_id: groupId,
          file_id: groupFileId,
          busid: groupBusid,
        });
      } catch (_) {}
    }
    console.error(JSON.stringify({
      status: 'failed',
      error: error.message,
      results,
    }));
    process.exitCode = 1;
    socket.close();
  }
});
