'use strict';

/**
 * 0.8.9.39 新增内核动作与 official internal 路由的现场巡检。
 * 只打测试群 280183116；写项自己还原，两个破坏性动作（群转让 / 解散群）不跑。
 *
 * 用法： node tests/internal-kernel-probe.js
 *
 * 判据：读动作以 `ok:true` 为准；`timeout` 说明该内核入口在 9.3.60 上不接线，
 * 应该从模块里撤掉并记进 docs/SATORI_SUPPORT.md 的「内核可用性」。
 */

const http = require('http');
const { connect } = require('./satori-client');

const GROUP = String(process.env.SATORI_TEST_GROUP || '280183116');
const results = [];

function record(name, ok, detail) {
  results.push({ name, ok: !!ok, detail: detail === undefined ? '' : String(detail).slice(0, 160) });
  const extra = detail === undefined || detail === null || detail === '' ? ''
    : '  ' + String(typeof detail === 'object' ? JSON.stringify(detail) : detail).slice(0, 200);
  console.log((ok ? 'ok   ' : 'FAIL ') + name + extra);
}

async function check(name, fn, expect) {
  try {
    const out = await fn();
    if (expect && !expect(out)) throw new Error('unexpected shape: ' + JSON.stringify(out).slice(0, 160));
    record(name, true, out && out.result);
    return out;
  } catch (error) {
    record(name, false, error && error.message || error);
    return null;
  }
}

/** 原始 HTTP，用来打 official internal 路由（satori-client 的 call 只走 /v1/<action>）。 */
function raw(client, method, path, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined || body === null ? null
      : (Buffer.isBuffer(body) ? body : Buffer.from(typeof body === 'string' ? body : JSON.stringify(body)));
    const req = http.request({
      host: client.host, port: client.port, path, method,
      headers: Object.assign({
        'Authorization': 'Bearer ' + client.token,
        'Satori-Platform': 'red',
        'Satori-User-ID': String(client.ready?.logins?.[0]?.user?.id || ''),
      }, payload ? { 'Content-Type': 'application/json', 'Content-Length': payload.length } : {}, headers),
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks), headers: res.headers }));
    });
    req.on('error', reject);
    req.setTimeout(30000, () => { req.destroy(); reject(new Error('timeout ' + method + ' ' + path)); });
    req.end(payload);
  });
}

async function main() {
  const client = await connect();
  const uin = String(client.ready?.logins?.[0]?.user?.id || '');
  const read = (action, params) => client.callOk('internal/' + action, params || {});
  const ok = (o) => o && o.ok === true;
  console.log('self=' + uin + ' group=' + GROUP + '\n');

  // ============ official internal 路由 ============
  console.log('---- internal 路由 ----');
  await check('POST /v1/internal/{red}/{uin}/_api/group_detail', async () => {
    const res = await raw(client, 'POST', `/v1/internal/red/${uin}/_api/group_detail`, [{ guild_id: GROUP }]);
    if (res.status !== 200) throw new Error('HTTP ' + res.status + ' ' + res.body.toString('utf8').slice(0, 120));
    const json = JSON.parse(res.body.toString('utf8'));
    if (json.ok !== true) throw new Error('not ok: ' + res.body.toString('utf8').slice(0, 120));
    return json;
  }, (o) => o && o.ok === true);
  await check('POST /v1/internal/{red}/{uin}/_api/groupDetail (camelCase)', async () => {
    const res = await raw(client, 'POST', `/v1/internal/red/${uin}/_api/groupDetail`, [{ guild_id: GROUP }]);
    if (res.status !== 200) throw new Error('HTTP ' + res.status);
    return JSON.parse(res.body.toString('utf8'));
  }, (o) => o && o.ok === true);
  await check('POST _api + Satori-Pagination', async () => {
    const res = await raw(client, 'POST', `/v1/internal/red/${uin}/_api/recent_contacts`, {},
      { 'Satori-Pagination': 'true' });
    if (res.status !== 200) throw new Error('HTTP ' + res.status);
    const json = JSON.parse(res.body.toString('utf8'));
    if (!Array.isArray(json.data)) throw new Error('no data array: ' + res.body.toString('utf8').slice(0, 120));
    return json;
  }, (o) => Array.isArray(o.data));
  await check('POST _api 别的登录回 404', async () => {
    const res = await raw(client, 'POST', '/v1/internal/red/1000000000/_api/group_detail', [{ guild_id: GROUP }]);
    if (res.status !== 404) throw new Error('HTTP ' + res.status);
    return { message: res.body.toString('utf8') };
  });
  await check('GET /v1/internal/{red}/{uin}/_tmp/{bad} 回 404', async () => {
    const res = await raw(client, 'GET', `/v1/internal/red/${uin}/_tmp/nope`);
    if (res.status !== 404) throw new Error('HTTP ' + res.status);
    return { status: res.status };
  });

  // upload.create 发出的 internal: 资源必须能从同一个路由取回来
  let uploadedId = '';
  await check('upload.create', async () => {
    const boundary = '----satoriqq' + Date.now();
    const body = Buffer.concat([
      Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="probe.png"\r\nContent-Type: image/png\r\n\r\n`),
      Buffer.from('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c6360000002000100ffff03000006000557bfabd40000000049454e44ae426082', 'hex'),
      Buffer.from(`\r\n--${boundary}--\r\n`),
    ]);
    const res = await raw(client, 'POST', '/v1/upload.create', body, {
      'Content-Type': 'multipart/form-data; boundary=' + boundary,
      'Content-Length': body.length,
    });
    if (res.status !== 200) throw new Error('HTTP ' + res.status + ' ' + res.body.toString('utf8').slice(0, 120));
    const json = JSON.parse(res.body.toString('utf8'));
    uploadedId = json.file || '';
    if (!uploadedId.startsWith('internal:')) throw new Error('no internal id: ' + res.body.toString('utf8').slice(0, 120));
    return { id: uploadedId };
  });
  if (uploadedId) {
    await check('GET internal 资源回落 /v1/internal/…/_tmp/…', async () => {
      const path = '/v1/' + uploadedId.replace(/^internal:/, 'internal/');
      const res = await raw(client, 'GET', path);
      if (res.status !== 200) throw new Error('HTTP ' + res.status + ' for ' + path);
      if (res.body.length < 8 || res.body[0] !== 0x89) throw new Error('not a png: ' + res.body.length + ' bytes');
      return { status: res.status, bytes: res.body.length, type: res.headers['content-type'] };
    });
  }

  // ============ 新增内核动作 ============
  console.log('\n---- 群 ----');
  await check('group_join_link', () => read('group_join_link', { guild_id: GROUP }),
    (o) => o && o.result !== undefined);
  await check('group_member_card', () => read('group_member_card', { guild_id: GROUP, user_id: uin }), ok);
  await check('group_related', () => read('group_related', { guild_id: GROUP }), (o) => o && o.result !== undefined);
  await check('group_related.sub', () => read('group_related', { guild_id: GROUP, op: 'sub' }), (o) => o && o.result !== undefined);
  await check('group_apps', () => read('group_apps', { guild_id: GROUP, count: 5 }), (o) => o && o.result !== undefined);
  await check('group_illegal', () => read('group_illegal', { guild_id: GROUP }), (o) => o && o.result !== undefined);
  await check('group_msg_limit', () => read('group_msg_limit', { guild_id: GROUP }), (o) => o && o.result !== undefined);
  await check('group_capacity', () => read('group_capacity', { guild_id: GROUP }), (o) => o && o.result !== undefined);
  await check('group_notify', () => read('group_notify'), (o) => o && o.result !== undefined);
  await check('group_check_member', () => read('group_check_member', { guild_id: GROUP, user_ids: [uin] }),
    (o) => o && o.result !== undefined);
  await check('group_signin_status', () => read('group_signin_status', { guild_id: GROUP }), (o) => o && o.result !== undefined);
  // 成员翻页（getNextMemberList）要先用 createMemberListScene 建场景，实测各游标都回空页，
  // 模块不提供该动作（见 docs/SATORI_SUPPORT.md）。

  console.log('\n---- 消息 ----');
  let messageId = '';
  await check('message.create', async () => {
    const sent = await client.callOk('message.create', { channel_id: GROUP, content: '0.8.9.39 内核动作巡检' });
    const list = Array.isArray(sent?.data) ? sent.data : (Array.isArray(sent) ? sent : []);
    messageId = list[0]?.id || '';
    if (!messageId) throw new Error('没有返回 message id');
    return { id: messageId };
  });
  await check('first_unread', () => read('first_unread', { channel_id: GROUP }), (o) => o && o.result !== undefined);
  if (messageId) {
    await check('message_by_id', () => read('message_by_id', { guild_id: GROUP, message_id: messageId }),
      (o) => o && o.result !== undefined);
    await check('recall_history', () => read('recall_history', { guild_id: GROUP, message_id: messageId }),
      (o) => o && o.result !== undefined);
    await check('msg_abstract', () => read('msg_abstract', { guild_id: GROUP, message_id: messageId }),
      (o) => o && o.result !== undefined);
    await check('emoji_likes', () => read('emoji_likes', { guild_id: GROUP, message_id: messageId, count: 5 }),
      (o) => o && o.result !== undefined);
  }
  await check('hidden_session.get', () => read('hidden_session'), (o) => o && o.result !== undefined);
  await check('hidden_session.set', () => read('hidden_session', { channel_id: GROUP, op: 'hide' }),
    (o) => o && o.hidden === true);
  await check('hidden_session.unhide', () => read('hidden_session', { channel_id: GROUP, op: 'unhide' }),
    (o) => o && o.hidden === false);
  await check('draft.get', () => read('draft', { channel_id: GROUP }), (o) => o && o.result !== undefined);
  // getOnLineDev 在 9.3.60 上接受调用但不回调，模块不提供该动作（见 docs/SATORI_SUPPORT.md）。
  await check('temp_chat', () => read('temp_chat', { channel_id: GROUP }), (o) => o && o.result !== undefined);
  await check('recent_faces', () => read('recent_faces', { count: 5 }), (o) => o && o.result !== undefined);

  console.log('\n---- 媒体 / 资料 / 会话 / 机器人 ----');
  await check('media_dir', () => read('media_dir'), (o) => o && o.dir !== undefined);
  await check('batch_file_count', () => read('batch_file_count', { guild_id: GROUP }), (o) => o && o.result !== undefined);
  // enumProvinceOptions 在内核 prepareRegionConfig 之前一律回空表，而该入口不回调，
  // 模块不提供该动作（见 docs/SATORI_SUPPORT.md）。
  await check('recent_snapshot', () => read('recent_snapshot', { count: 5 }), (o) => o && o.result !== undefined);
  await check('unread_details', () => read('unread_details'), (o) => o && o.result !== undefined);
  await check('session_top.on', () => read('session_top', { channel_id: GROUP, op: 'on' }), (o) => o && o.top === true);
  await check('session_top.off', () => read('session_top', { channel_id: GROUP, op: 'off' }), (o) => o && o.top === false);
  await check('robot_list', () => read('robot_list'), (o) => o && o.result !== undefined);
  await check('robot_owned', () => read('robot_owned', { guild_id: GROUP, user_id: uin }),
    (o) => o && o.result !== undefined);

  if (messageId) await check('message.delete', () => client.callOk('message.delete', { channel_id: GROUP, message_id: messageId }));

  const failed = results.filter((r) => !r.ok);
  console.log('\n' + (results.length - failed.length) + '/' + results.length + ' passed');
  if (failed.length) {
    console.log('failed: ' + failed.map((f) => f.name).join(', '));
  }
  client.close && client.close();
  process.exit(failed.length ? 1 : 0);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
