'use strict';

/**
 * acumen 接口面冒烟：按 acumen（Rust 端）**实际调用**的动作逐个探一遍。
 *
 * 动作集合取自 acumen 的 `src/adapters/satori/api.rs` 与
 * `src/plugins/oai/chat/session.rs`；改这边的动作表或改名时，先跑这个脚本。
 *
 * 判定口径：
 * - 只读动作要 200，并且读得回形状对的数据；
 * - 会改状态的动作只用**参数校验**来确认「路由到了真实实现」——404 + `API not found`
 *   才算没实现，其余 4xx 都算路由成功。真改状态的回归在 `ws-write-sweep.js`。
 *
 *   node tests/ws-acumen-smoke.js
 */

const { connect, delay } = require('./satori-client');

// 测试群必须显式给：原来的默认群 280183116 已于 2026-09-19 解散。
const GROUP = String(process.env.SATORI_TEST_GROUP || '');
if (!GROUP) {
  console.error('需要 SATORI_TEST_GROUP=<群号>：原来的默认测试群 280183116 已于 2026-09-19 解散。');
  process.exit(2);
}

const results = [];
function record(name, ok, detail) {
  results.push({ name, ok: !!ok, detail: detail === undefined ? '' : String(detail).slice(0, 200) });
  console.log((ok ? 'ok   ' : 'FAIL ') + name + (ok ? '' : '  ' + String(detail).slice(0, 200)));
}

async function check(name, fn) {
  try {
    record(name, true, await fn());
  } catch (error) {
    record(name, false, (error && error.message) || error);
  }
}

/** 只验「路由到了真实实现」：404 且文案是 API not found 才算没实现。 */
async function expectRouted(client, action, params) {
  const res = await client.call(action, params);
  const message = (res.json && res.json.message) || res.text || '';
  if (res.http_status === 404 && /API not found/i.test(message)) {
    throw new Error('未实现（404 API not found）');
  }
  return res.http_status + ' ' + message.slice(0, 70);
}

async function main() {
  const client = await connect();
  const selfId = String(client.ready.logins[0].user.id);
  console.log('# group=' + GROUP + ' self=' + selfId);

  // ---- 只读：acumen 的上下文与身份 ----
  await check('login.get', async () => {
    // HTTP login.get 回扁平的一个 Login（`self_id` / `user` / `features`）；WS READY 里是
    // `logins[]`。两种形状 acumen 都要认，这里两种都查。
    const o = await client.callOk('login.get', {});
    const flat = o.self_id || o.user?.id;
    if (String(flat) !== selfId) throw new Error('self_id=' + flat);
    const wsLogin = (client.ready?.logins || [])[0] || {};
    if (String(wsLogin.user?.id) !== selfId) throw new Error('READY logins[0].user.id=' + wsLogin.user?.id);
    if (!Array.isArray(o.features) || o.features.length === 0) throw new Error('features 为空');
    return 'features=' + o.features.length;
  });

  await check('guild.get', async () => {
    const o = await client.callOk('guild.get', { guild_id: GROUP });
    if (String(o.id) !== GROUP) throw new Error('id=' + o.id);
    return 'name=' + JSON.stringify(o.name === undefined ? null : o.name);
  });

  await check('guild.list', async () => {
    const o = await client.callOk('guild.list', {});
    if (!Array.isArray(o.data)) throw new Error('data 不是数组');
    const hit = o.data.find((g) => String(g.id) === GROUP);
    if (!hit) throw new Error('测试群不在列表里（共 ' + o.data.length + ' 个）');
    return 'guilds=' + o.data.length;
  });

  await check('guild.member.get', async () => {
    const o = await client.callOk('guild.member.get', { guild_id: GROUP, user_id: selfId });
    if (String(o.user?.id) !== selfId) throw new Error('user.id=' + o.user?.id);
    return 'roles=' + (o.roles || []).map((r) => r.id).join(',');
  });

  await check('guild.member.list', async () => {
    const o = await client.callOk('guild.member.list', { guild_id: GROUP });
    if (!Array.isArray(o.data)) throw new Error('data 不是数组');
    if (!o.data.some((m) => String(m.user?.id) === selfId)) throw new Error('自己不在成员表里');
    return 'members=' + o.data.length;
  });

  await check('message.list', async () => {
    const o = await client.callOk('message.list', { channel_id: GROUP, limit: 3 });
    if (!Array.isArray(o.data)) throw new Error('data 不是数组');
    return 'messages=' + o.data.length;
  });

  await check('reaction.list', async () => {
    const list = await client.callOk('message.list', { channel_id: GROUP, limit: 1 });
    let mid = list?.data?.[0]?.id;
    if (!mid) {
      // 新群里可能一条消息都没有；表态是挂在消息上的，先造一条（测试群，允许发消息）。
      const sent = await client.callOk('message.create', { channel_id: GROUP, content: 'acumen-smoke 表态探针' });
      const arr = Array.isArray(sent) ? sent : [sent];
      mid = arr[0]?.id;
    }
    if (!mid) throw new Error('群里没有消息、也发不出一条');
    const o = await client.callOk('reaction.list', { channel_id: GROUP, message_id: String(mid), emoji_id: '4' });
    if (!Array.isArray(o.data)) throw new Error('data 不是数组');
    return 'users=' + o.data.length;
  });

  // ---- acumen 的扩展动作 ----
  await check('internal/capabilities', async () => {
    const o = await client.callOk('internal/capabilities', {});
    if (!Array.isArray(o.actions) || o.actions.length === 0) throw new Error('actions 为空');
    return 'actions=' + o.actions.length;
  });

  await check('internal/get_forward', async () => {
    // 拿一个不存在的父消息去问：期望的不是 200，而是**内核真的回了话**（code=4 Data Not Existed），
    // 那说明路由通了；只有 404 API not found 才算实现端没有这个能力。
    const detail = await expectRouted(client, 'internal/get_forward', { id: 'native:1', channel_id: GROUP });
    if (/API not found/i.test(detail)) throw new Error(detail);
    return detail;
  });

  // sign 会真的打卡，只在显式开启时跑
  if (process.env.SATORI_ACUMEN_SIGN === '1') {
    await check('internal/sign', async () => expectRouted(client, 'internal/sign', { guild_id: GROUP }));
  } else {
    console.log('skip internal/sign（会真的打卡；SATORI_ACUMEN_SIGN=1 才跑）');
  }

  // ---- 会改状态：只用参数校验确认路由 ----
  await check('message.get(未知 id)', async () => {
    const message = await expectRouted(client, 'message.get', { message_id: '999999999999999' });
    if (!/not found/i.test(message)) throw new Error('未知 id 的文案不对：' + message);
    return message;
  });

  await check('message.delete(缺参)', async () => expectRouted(client, 'message.delete', {}));
  await check('reaction.create(缺参)', async () => expectRouted(client, 'reaction.create', {}));
  await check('reaction.delete(缺参)', async () => expectRouted(client, 'reaction.delete', {}));
  await check('reaction.clear(缺参)', async () => expectRouted(client, 'reaction.clear', {}));
  await check('channel.update(缺参)', async () => expectRouted(client, 'channel.update', { channel_id: GROUP }));
  await check('channel.mute(缺参)', async () => expectRouted(client, 'channel.mute', { channel_id: GROUP }));
  await check('guild.member.mute(缺参)', async () => expectRouted(client, 'guild.member.mute', { guild_id: GROUP }));
  await check('guild.member.kick(缺参)', async () => expectRouted(client, 'guild.member.kick', { guild_id: GROUP }));
  await check('internal/card(缺参)', async () => expectRouted(client, 'internal/card', { guild_id: GROUP }));
  await check('internal/special_title(缺参)', async () => expectRouted(client, 'internal/special_title', { guild_id: GROUP }));
  await check('internal/poke(缺参)', async () => expectRouted(client, 'internal/poke', { guild_id: GROUP }));
  await check('internal/essence(缺参)', async () => expectRouted(client, 'internal/essence', { guild_id: GROUP }));

  // ---- 已知缺口：这些动作 acumen 还在调，但实现端已经没有了 ----
  // 只要它报的是「已移除」而不是「API not found」，acumen 就能把能力标成不可用。
  await check('internal/like(已移除，文案要能认)', async () => {
    const message = await expectRouted(client, 'internal/like', { user_id: selfId, times: 1 });
    if (/API not found/i.test(message)) throw new Error('报的还是「没这个方法」：' + message);
    return message;
  });

  // message.update 从来没实现过，acumen 只在单测里用它验「404 不算成功」。
  await check('message.update(从未实现)', async () => {
    const res = await client.call('message.update', {});
    const message = (res.json && res.json.message) || res.text || '';
    if (!/API not found/i.test(message)) throw new Error('期望 404 API not found，实得 ' + res.http_status + ' ' + message);
    return '404 ' + message;
  });

  const failed = results.filter((r) => !r.ok);
  console.log(JSON.stringify({
    status: failed.length ? 'failed' : 'ok',
    group: GROUP,
    self_id: selfId,
    total: results.length,
    failed: failed.length,
    failures: failed,
  }));
  client.close();
  if (failed.length) process.exitCode = 1;
}

main().catch((error) => {
  console.error('harness failed: ' + (error && error.stack || error));
  process.exitCode = 1;
});
