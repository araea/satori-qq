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
const notices = [];
let sequence = 1;

function call(action, params = {}, timeoutMs = 25000) {
  const echo = `notice-mem-${sequence++}`;
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

function waitNotice(match, timeoutMs = 18000) {
  const start = Date.now();
  return new Promise((resolve, reject) => {
    const check = () => {
      const hit = notices.find(match);
      if (hit) {
        clearInterval(timer);
        resolve(hit);
      } else if (Date.now() - start > timeoutMs) {
        clearInterval(timer);
        reject(new Error('no matching notice got=' + JSON.stringify(notices.map((n) => ({
          type: n.notice_type, sub: n.sub_type, user: n.user_id, op: n.operator_id, req: n.request_type,
        })))));
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
  const out = { group_id: groupId, decrease: false, increase: false, invited: false };
  let target = 0;
  try {
    const login = requireOk(await call('get_login_info'), 'get_login_info');
    const selfId = Number(login.user_id || 0);
    const membersRaw = await call('get_group_member_list', { group_id: groupId });
    const members = Array.isArray(membersRaw.data) ? membersRaw.data
      : Array.isArray(membersRaw.data?.members) ? membersRaw.data.members : [];
    const other = members.find((m) => Number(m.user_id) !== selfId && m.role === 'member')
      || members.find((m) => Number(m.user_id) !== selfId);
    if (!other) throw new Error('no other member to kick');
    target = Number(other.user_id);
    out.target_ok = true;
    out.picked_role = other.role || '';

    requireOk(await call('set_group_kick', {
      group_id: groupId,
      user_id: target,
      reject_add_request: false,
    }), 'set_group_kick');
    const dec = await waitNotice((n) =>
      n.notice_type === 'group_decrease' && Number(n.group_id) === groupId
      && Number(n.user_id) === target);
    out.decrease = true;
    out.decrease_sub = dec.sub_type;
    out.decrease_op_self = Number(dec.operator_id) === selfId;

    try {
      requireOk(await call('invite_group', {
        group_id: groupId,
        user_id: target,
      }), 'invite_group');
      out.invited = true;
      const inc = await waitNotice((n) =>
        (n.notice_type === 'group_increase' && Number(n.group_id) === groupId
          && Number(n.user_id) === target)
        || (n.post_type === 'request' && n.request_type === 'group'), 20000);
      if (inc.notice_type === 'group_increase') {
        out.increase = true;
        out.increase_sub = inc.sub_type;
      } else {
        out.increase_pending_request = true;
        out.request_sub = inc.sub_type;
      }
    } catch (inviteErr) {
      out.invite_error = String(inviteErr && inviteErr.message || inviteErr);
    }

    console.log(JSON.stringify(out));
    if (!out.decrease) process.exitCode = 1;
  } catch (error) {
    console.log(JSON.stringify({ ...out, error: String(error && error.message || error) }));
    process.exitCode = 1;
  } finally {
    socket.close();
  }
});
