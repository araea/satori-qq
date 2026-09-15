'use strict';

/**
 * 新增内核动作的现场巡检。只打测试群 280183116，只读项直接跑，写项自己还原。
 * 用法： node tests/ws-kernel-extras.js
 */

const { connect, delay } = require('./satori-client');

const GROUP = String(process.env.SATORI_TEST_GROUP || '280183116');
const results = [];

function record(name, ok, detail) {
  results.push({ name, ok: !!ok });
  console.log((ok ? 'ok   ' : 'FAIL ') + name +
    (ok || detail === undefined ? '' : '  ' + String(detail).slice(0, 300)));
}

async function check(name, fn, expect) {
  try {
    const out = await fn();
    if (expect && !expect(out)) throw new Error('unexpected shape: ' + JSON.stringify(out).slice(0, 200));
    record(name, true, out);
  } catch (error) {
    record(name, false, error && error.message || error);
  }
}

async function main() {
  const client = await connect();
  const selfId = client.ready?.logins?.[0]?.user?.id;
  console.log('self=' + selfId + ' group=' + GROUP + '\n');

  const read = (action, params) => client.callOk('internal/' + action, params || {});
  const ok = (o) => o && o.ok === true;

  // ---- 群 ----
  await check('group_detail', () => read('group_detail', { guild_id: GROUP }), ok);
  await check('group_all_info', () => read('group_all_info', { guild_id: GROUP }), ok);
  await check('group_bulletin', () => read('group_bulletin', { guild_id: GROUP }), ok);
  // 服务端对手机端的公告列表接口一律回 code=1，单条公告可读；这里只确认响应成形。
  await check('group_bulletin.list', () => read('group_bulletin', { guild_id: GROUP, op: 'list', count: 5 }),
    (o) => o && o.result !== undefined);
  await check('group_essence_list', () => read('group_essence_list', { guild_id: GROUP, limit: 5 }), ok);
  await check('group_statistic', () => read('group_statistic', { guild_id: GROUP }), ok);
  await check('group_member_level', () => read('group_member_level', { guild_id: GROUP }), ok);
  await check('group_avatar_wall', () => read('group_avatar_wall', { guild_id: GROUP }), ok);
  // 测试群没有勋章时服务端回 code=2 并给出空列表；只确认结构。
  await check('group_medal', () => read('group_medal', { guild_id: GROUP }),
    (o) => o && o.medals !== undefined);
  await check('member_identity', () => read('member_identity', { guild_id: GROUP, user_id: selfId }), ok);
  await check('member_common', () => read('member_common', { guild_id: GROUP, user_id: selfId }), ok);
  await check('group_msg_mask.read', () => read('group_msg_mask', { guild_id: GROUP }), ok);

  // ---- 好友 / 资料 ----
  await check('buddy_category', () => read('buddy_category'), (o) => o && Array.isArray(o.categories));
  await check('buddy_nick', () => read('buddy_nick', { user_id: selfId }), (o) => o && o.nicks);
  await check('buddy_req_unread', () => read('buddy_req_unread'), ok);
  await check('doubt_buddy', () => read('doubt_buddy'), ok);
  await check('add_me_setting', () => read('add_me_setting'), ok);
  await check('user_detail', () => read('user_detail', { user_id: selfId }), ok);
  await check('vas_info', () => read('vas_info', { user_id: selfId }), (o) => o && o.count >= 1);
  await check('profile_status', () => read('profile_status', { user_id: selfId }), (o) => o && o.count >= 1);
  await check('profile_intimate', () => read('profile_intimate', { user_id: selfId }), (o) => o && o.intimate !== undefined);
  await check('profile_relation_flag', () => read('profile_relation_flag', { user_id: selfId }), (o) => o && o.count >= 1);

  // ---- 消息 ----
  await check('fav_emoji', () => read('fav_emoji'), ok);
  await check('auto_reply', () => read('auto_reply'), ok);

  // 发一条消息以便测语音/已读相关
  let messageId = '';
  await check('message.create', async () => {
    const sent = await client.callOk('message.create', { channel_id: GROUP, content: '内核动作巡检' });
    const list = Array.isArray(sent?.data) ? sent.data : (Array.isArray(sent) ? sent : []);
    messageId = list[0]?.id || '';
    if (!messageId) throw new Error('没有返回 message id');
    return messageId;
  });
  if (messageId) {
    await check('unread_summary', () => read('unread_summary', { channel_id: GROUP }), ok);
    await check('mark_read', () => read('mark_read', { channel_id: GROUP }), (o) => o && o.read === true);
    await check('message.delete', () => client.callOk('message.delete', { channel_id: GROUP, message_id: messageId }));
  }

  const failed = results.filter((r) => !r.ok).length;
  console.log('\n' + (results.length - failed) + '/' + results.length + ' passed');
  client.close && client.close();
  process.exit(failed ? 1 : 0);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
