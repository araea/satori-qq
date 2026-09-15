'use strict';

/**
 * 全量功能巡检：把 READY 里宣告的每个能力都真实打一遍，输出逐项结果。
 *
 * - 只读项直接跑；写项只打测试群 `SATORI_TEST_GROUP`（默认 280183116），并且自己清理。
 * - 出站有每分钟频限（默认 20），写项控制在十余次以内。
 * - 退出码：有任意一项失败即 1。
 */

const { connect, delay } = require('./satori-client');

const GROUP = String(process.env.SATORI_TEST_GROUP || '280183116');

const results = [];
function record(name, ok, detail) {
  results.push({ name, ok: !!ok, detail: detail === undefined ? '' : detail });
  console.log((ok ? 'ok   ' : 'FAIL ') + name + (ok || detail === undefined ? '' : '  ' + String(detail).slice(0, 200)));
}

async function check(name, fn) {
  try {
    const detail = await fn();
    record(name, true, detail);
    return detail;
  } catch (error) {
    record(name, false, error && error.message || error);
    return undefined;
  }
}

async function main() {
  const client = await connect();

  // ---- 握手与元信息 ----
  await check('ws.ready', () => {
    const login = client.ready?.logins?.[0];
    if (!login) throw new Error('READY 没有 logins');
    if (!login.user?.id || login.user.id === '0') throw new Error('READY 的 user.id 不可用: ' + login.user?.id);
    if (login.platform !== 'red') throw new Error('platform=' + login.platform);
    return 'id=' + login.user.id + ' features=' + (login.features || []).length;
  });

  await check('ws.ping_pong', async () => {
    client.ping();
    await delay(500);
    return 'sent';
  });

  await check('meta', async () => {
    const meta = await client.callOk('meta');
    if (!Array.isArray(meta.logins)) throw new Error('meta.logins 不是数组');
    return 'logins=' + meta.logins.length;
  });

  const selfId = client.ready?.logins?.[0]?.user?.id;

  await check('login.get', async () => {
    const login = await client.callOk('login.get');
    if (String(login.user?.id) !== String(selfId)) throw new Error('账号不一致');
    return 'status=' + login.status;
  });

  await check('internal/status', async () => {
    const s = await client.callOk('internal/status');
    if (s.online !== true) throw new Error('online=' + s.online);
    return 'good=' + s.good;
  });

  await check('internal/version', async () => {
    const v = await client.callOk('internal/version');
    if (!v.version) throw new Error('没有 version');
    return v.version + ' qq=' + v.qq_version;
  });

  let advertised = [];
  await check('internal/capabilities', async () => {
    const caps = await client.callOk('internal/capabilities');
    advertised = caps.actions || [];
    if (!advertised.length) throw new Error('actions 为空');
    return advertised.length + ' actions';
  });

  await check('internal/help', async () => {
    const help = await client.callOk('internal/help');
    if (!help) throw new Error('空响应');
    return Object.keys(help).slice(0, 4).join(',');
  });

  // ---- 群与频道 ----
  await check('channel.get', async () => {
    const ch = await client.callOk('channel.get', { channel_id: GROUP });
    if (String(ch.id) !== GROUP) throw new Error('id=' + ch.id);
    if (ch.type !== 0) throw new Error('type=' + ch.type);
    if (!ch.name) throw new Error('没有群名');
    return ch.name;
  });

  await check('channel.list', async () => {
    const list = await client.callOk('channel.list', { guild_id: GROUP });
    const arr = Array.isArray(list) ? list : list.data;
    if (!Array.isArray(arr) || !arr.length) throw new Error('空频道列表');
    return 'channels=' + arr.length;
  });

  await check('guild.get', async () => {
    const g = await client.callOk('guild.get', { guild_id: GROUP });
    if (String(g.id) !== GROUP) throw new Error('id=' + g.id);
    return g.name;
  });

  await check('guild.list', async () => {
    const list = await client.callOk('guild.list');
    const arr = Array.isArray(list) ? list : list.data;
    if (!Array.isArray(arr) || !arr.length) throw new Error('空群列表');
    const hit = arr.some((g) => String(g.id) === GROUP);
    if (!hit) throw new Error('测试群不在列表里');
    return 'guilds=' + arr.length;
  });

  await check('guild.role.list', async () => {
    const roles = await client.callOk('guild.role.list', { guild_id: GROUP });
    const arr = Array.isArray(roles) ? roles : roles.data;
    if (!Array.isArray(arr) || !arr.length) throw new Error('空角色列表');
    const ids = arr.map((r) => r.id).join(',');
    if (!ids.includes('admin')) throw new Error('缺少 admin: ' + ids);
    return ids;
  });

  await check('guild.member.get', async () => {
    const m = await client.callOk('guild.member.get', { guild_id: GROUP, user_id: selfId });
    if (String(m.user?.id) !== String(selfId)) throw new Error('user.id=' + m.user?.id);
    return 'nick=' + m.nick;
  });

  let memberCount = 0;
  await check('guild.member.list', async () => {
    const res = await client.callOk('guild.member.list', { guild_id: GROUP });
    const arr = Array.isArray(res?.data) ? res.data : (Array.isArray(res) ? res : []);
    if (!arr.length) throw new Error('空成员列表');
    memberCount = arr.length;
    const self = arr.find((m) => String(m.user?.id) === String(selfId));
    if (!self) throw new Error('自己不在成员列表里');
    return 'members=' + arr.length;
  });

  await check('user.get', async () => {
    const u = await client.callOk('user.get', { user_id: selfId });
    if (String(u.id) !== String(selfId)) throw new Error('id=' + u.id);
    return u.name;
  });

  // ---- 内部只读动作 ----
  await check('internal/group_overview', async () => {
    const o = await client.callOk('internal/group_overview', { guild_id: GROUP });
    if (!o) throw new Error('空响应');
    return Object.keys(o).slice(0, 6).join(',');
  });

  await check('internal/group_extra', async () => {
    const o = await client.callOk('internal/group_extra', { guild_id: GROUP });
    if (!o) throw new Error('空响应');
    return Object.keys(o).slice(0, 6).join(',');
  });

  await check('internal/member_info', async () => {
    const o = await client.callOk('internal/member_info', { guild_id: GROUP, user_id: selfId });
    if (!o) throw new Error('空响应');
    return Object.keys(o).slice(0, 6).join(',');
  });

  await check('internal/group_member_search', async () => {
    const o = await client.callOk('internal/group_member_search', { guild_id: GROUP, key: String(selfId).slice(0, 4) });
    if (!o) throw new Error('空响应');
    return Object.keys(o).slice(0, 6).join(',');
  });

  await check('internal/recent_contacts', async () => {
    const o = await client.callOk('internal/recent_contacts');
    if (!o) throw new Error('空响应');
    return Object.keys(o).slice(0, 6).join(',');
  });

  await check('internal/contact_search', async () => {
    const o = await client.callOk('internal/contact_search', { key: String(selfId).slice(0, 4) });
    if (!o) throw new Error('空响应');
    return Object.keys(o).slice(0, 6).join(',');
  });

  await check('internal/friend_relation', async () => {
    const o = await client.callOk('internal/friend_relation', { user_id: selfId });
    if (!o) throw new Error('空响应');
    return JSON.stringify(o).slice(0, 120);
  });

  await check('internal/group_remark', async () => {
    const o = await client.callOk('internal/group_remark', { guild_id: GROUP, op: 'get' });
    if (!o) throw new Error('空响应');
    return JSON.stringify(o).slice(0, 120);
  });

  await check('internal/profile_self', async () => {
    const o = await client.callOk('internal/profile_self');
    if (!o) throw new Error('空响应');
    return JSON.stringify(o).slice(0, 160);
  });

  await check('internal/group_honor', async () => {
    const o = await client.callOk('internal/group_honor', { guild_id: GROUP });
    if (!o) throw new Error('空响应');
    return Object.keys(o).slice(0, 6).join(',');
  });

  await check('internal/group_shut_up_list', async () => {
    const o = await client.callOk('internal/group_shut_up_list', { guild_id: GROUP });
    if (!o) throw new Error('空响应');
    return Object.keys(o).slice(0, 6).join(',');
  });

  await check('internal/group_active', async () => {
    const o = await client.callOk('internal/group_active', { guild_id: GROUP });
    if (!o) throw new Error('空响应');
    return Object.keys(o).slice(0, 6).join(',');
  });

  await check('internal/group_anniversary', async () => {
    const o = await client.callOk('internal/group_anniversary', { guild_id: GROUP });
    if (!o) throw new Error('空响应');
    return Object.keys(o).slice(0, 6).join(',');
  });

  await check('friend.list', async () => {
    const res = await client.callOk('friend.list');
    const arr = Array.isArray(res?.data) ? res.data : (Array.isArray(res) ? res : []);
    return 'friends=' + arr.length;
  });

  // ---- 读回不该存在的目标，确认报错形状 ----
  await check('error.unknown_method', async () => {
    const msg = await client.callExpect('no.such_method', {}, 404, 'API not found');
    return msg;
  });

  await check('error.missing_channel_id', async () => {
    const msg = await client.callExpect('message.create', {}, 400, 'channel_id');
    return msg;
  });

  await check('error.unknown_message', async () => {
    const msg = await client.callExpect('message.get', { message_id: 'no-such-message-id' }, 404);
    return msg;
  });

  await check('unimplemented.returns_404', async () => {
    const msg = await client.callExpect('channel.create', { guild_id: GROUP }, 404);
    return msg;
  });

  // ---- 写项：发消息、回读、撤回 ----
  const stamp = 'sweep-' + Date.now();
  let messageId = null;
  await check('message.create', async () => {
    const sent = await client.callOk('message.create', { channel_id: GROUP, content: stamp });
    const arr = Array.isArray(sent) ? sent : [sent];
    if (!arr.length || !arr[0].id) throw new Error('没有返回消息 id: ' + JSON.stringify(sent).slice(0, 160));
    messageId = String(arr[0].id);
    return 'id=' + messageId;
  });

  await check('event.message_created', async () => {
    if (!messageId) throw new Error('上一步没发出消息');
    const ev = await client.waitFor(
      (e) => e.post_type === 'message-created' && String(e.id) === messageId, 15000, 'message-created');
    if (String(ev.channel?.id) !== GROUP) throw new Error('channel=' + ev.channel?.id);
    return 'user=' + ev.user?.id;
  });

  await check('message.get', async () => {
    if (!messageId) throw new Error('上一步没发出消息');
    const m = await client.callOk('message.get', { channel_id: GROUP, message_id: messageId });
    const one = Array.isArray(m) ? m[0] : m;
    if (!one) throw new Error('查不到刚发的消息');
    if (!JSON.stringify(one.content || one.message || '').includes(stamp)) {
      throw new Error('内容对不上: ' + JSON.stringify(one).slice(0, 160));
    }
    return 'ok';
  });

  await check('message.list', async () => {
    const m = await client.callOk('message.list', { channel_id: GROUP });
    const arr = Array.isArray(m?.data) ? m.data : (Array.isArray(m) ? m : []);
    if (!arr.length) throw new Error('空历史');
    return 'messages=' + arr.length;
  });

  await check('internal/message_context', async () => {
    if (!messageId) throw new Error('上一步没发出消息');
    const o = await client.callOk('internal/message_context', { channel_id: GROUP, message_id: messageId });
    if (!o) throw new Error('空响应');
    return Object.keys(o).slice(0, 6).join(',');
  });

  await check('internal/message_search', async () => {
    const o = await client.callOk('internal/message_search', { key: stamp });
    if (!o) throw new Error('空响应');
    return Object.keys(o).slice(0, 6).join(',');
  });

  await check('reaction.create', async () => {
    if (!messageId) throw new Error('上一步没发出消息');
    await client.callOk('reaction.create', { channel_id: GROUP, message_id: messageId, emoji_id: '4' });
    return 'emoji=4';
  });

  await check('reaction.list', async () => {
    if (!messageId) throw new Error('上一步没发出消息');
    const o = await client.callOk('reaction.list', { channel_id: GROUP, message_id: messageId });
    if (!o) throw new Error('空响应');
    return JSON.stringify(o).slice(0, 120);
  });

  await check('reaction.delete', async () => {
    if (!messageId) throw new Error('上一步没发出消息');
    await client.callOk('reaction.delete', { channel_id: GROUP, message_id: messageId, emoji_id: '4' });
    return 'ok';
  });

  await check('internal/poke', async () => {
    await client.callOk('internal/poke', { guild_id: GROUP, user_id: selfId });
    const ev = await client.waitFor(
      (e) => e.post_type === 'notice' && e.notice_type === 'notify' && e.sub_type === 'poke',
      15000, 'poke notice');
    return 'target=' + ev.target_id;
  });

  await check('internal/dice', async () => {
    await client.callOk('internal/dice', { channel_id: GROUP });
    return 'ok';
  });

  await check('internal/rps', async () => {
    await client.callOk('internal/rps', { channel_id: GROUP });
    return 'ok';
  });

  await check('message.delete', async () => {
    if (!messageId) throw new Error('上一步没发出消息');
    await client.callOk('message.delete', { channel_id: GROUP, message_id: messageId });
    return 'deleted ' + messageId;
  });

  await check('event.message_deleted_or_recall', async () => {
    if (!messageId) throw new Error('上一步没发出消息');
    const ev = await client.waitFor(
      (e) => (e.post_type === 'message-deleted' && String(e.message?.id) === messageId)
        || (e.post_type === 'notice' && e.notice_type === 'group' && e.sub_type === 'recall'),
      15000, 'recall event');
    return ev.post_type + '/' + (ev.sub_type || '');
  });

  await check('internal/group_msg_mask_read', async () => {
    const o = await client.callOk('internal/group_extra', { guild_id: GROUP });
    if (o && o.msg_mask === undefined && o.mask === undefined) {
      return 'no mask field: ' + Object.keys(o).slice(0, 8).join(',');
    }
    return 'mask=' + (o.msg_mask ?? o.mask);
  });

  await check('internal/title_display_read', async () => {
    const o = await client.callOk('internal/title_display', { guild_id: GROUP, user_id: selfId });
    if (!o) throw new Error('空响应');
    return JSON.stringify(o).slice(0, 120);
  });

  await check('internal/honor_display_read', async () => {
    const o = await client.callOk('internal/honor_display', { guild_id: GROUP, user_id: selfId });
    if (!o) throw new Error('空响应');
    return JSON.stringify(o).slice(0, 120);
  });

  await check('internal/get_resource', async () => {
    const o = await client.callOk('internal/get_resource', { file: 'internal:red/' + selfId + '/_tmp/none' });
    if (!o) throw new Error('空响应');
    return JSON.stringify(o).slice(0, 120);
  });

  client.close();

  const failed = results.filter((r) => !r.ok);
  console.log(JSON.stringify({
    total: results.length,
    failed: failed.length,
    group: GROUP,
    self_id: selfId,
    members: memberCount,
    failures: failed.map((f) => ({ name: f.name, detail: f.detail })),
  }));
  process.exitCode = failed.length ? 1 : 0;
}

main().catch((error) => {
  console.error('FAIL harness:', error && error.stack || error);
  process.exit(1);
});
