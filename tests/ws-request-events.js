'use strict';

const fs = require('fs');

const configPath = '/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json';
let token = 'satori-qq-token';
try {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  if (typeof config.token === 'string') token = config.token;
} catch (_) {}

const socket = new WebSocket(`ws://127.0.0.1:3001/?access_token=${encodeURIComponent(token)}`);
const pending = new Map();
const requests = [];
let sequence = 1;

function call(action, params = {}, timeoutMs = 20000) {
  const echo = `req-${sequence++}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(echo);
      reject(new Error(`${action} timeout`));
    }, timeoutMs);
    pending.set(echo, {
      resolve: (value) => { clearTimeout(timer); resolve(value); },
      reject: (error) => { reject(error); },
    });
    socket.send(JSON.stringify({ action, params, echo }));
  });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

socket.addEventListener('message', (event) => {
  let message;
  try { message = JSON.parse(String(event.data)); } catch (_) { return; }
  if (message.post_type === 'request') {
    requests.push(message);
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

function expectFail(response, action, code) {
  if (response?.retcode !== code) {
    throw new Error(`${action} expected ${code} got retcode=${response?.retcode} ${response?.message || ''}`);
  }
}

async function main() {
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', () => reject(new Error('ws open failed')), { once: true });
  });

  const login = await call('get_login_info');
  if (login.status !== 'ok' || !login.data?.user_id) {
    throw new Error('not logged in');
  }

  expectFail(await call('set_friend_add_request', {}), 'set_friend_add_request empty', 1400);
  expectFail(await call('set_friend_add_request', { flag: 'no-such-flag-0' }),
      'set_friend_add_request unknown', 1404);
  expectFail(await call('set_group_add_request', {}), 'set_group_add_request empty', 1400);
  expectFail(await call('set_group_add_request', { flag: 'no-such-group-flag' }),
      'set_group_add_request unknown', 1404);

  await sleep(2500);

  const out = {
    ok: true,
    login: true,
    action_errors: { empty: 1400, unknown: 1404 },
    request_events: requests.map((r) => ({
      type: r.request_type,
      sub: r.sub_type || '',
      group: r.group_id || 0,
      has_flag: !!r.flag,
    })),
  };
  console.log(JSON.stringify(out));
  socket.close();
}

main().catch((error) => {
  console.error(String(error && error.stack || error));
  process.exit(1);
});
