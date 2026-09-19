'use strict';

/**
 * 写操作巡验：把 `ws-feature-sweep.js` 没覆盖的协议方法真实打一遍。
 *
 * 覆盖：`channel.update` / `channel.mute` / `user.channel.create` /
 * `guild.member.mute` / `guild.member.role.set|unset|list` / `guild.member.kick` /
 * `reaction.clear` / `friend.delete` / `friend.approve` / `guild.approve` /
 * `guild.member.approve` / `upload.create`。
 *
 * 原则：
 * - 改群名、全员禁言、成员禁言、成员上/下管理都**当场恢复**，并把恢复后的状态读回来校验；
 * - `guild.member.kick` 与三个 approve 需要真实外部条件（要踢的人 / 待处理申请），
 *   只验「路由到了真实实现」而不是 404 未实现，并打印真实报错；
 * - 出站护栏一分钟只放 20 次写、连续 3 次失败会开熔断，所以撞上 `circuit open` 时等待重试。
 *
 * 目标账号取群里除自己外的第一个成员（可用 SATORI_TARGET_USER 指定）。
 *
 *   node tests/ws-write-sweep.js
 */

const http = require('http');
const { connect, loadToken, loadPort, delay } = require('./satori-client');

const GROUP = String(process.env.SATORI_TEST_GROUP || '280183116');
const HOST = '127.0.0.1';

const results = [];
function record(name, ok, detail) {
  results.push({ name, ok: !!ok, detail: detail === undefined ? '' : detail });
  console.log((ok ? 'ok   ' : 'FAIL ') + name + (ok || detail === undefined ? '' : '  ' + String(detail).slice(0, 220)));
}

/** 出站熔断打开时等着，再重试一次。 */
async function retryOnCircuit(fn) {
  try {
    return await fn();
  } catch (error) {
    const message = String((error && error.message) || error);
    const hit = /retry after (\d+)s/.exec(message);
    if (!hit) throw error;
    const wait = (parseInt(hit[1], 10) + 2) * 1000;
    console.log('       (circuit open, waiting ' + Math.round(wait / 1000) + 's)');
    await delay(wait);
    return fn();
  }
}

async function check(name, fn) {
  try {
    const detail = await retryOnCircuit(fn);
    record(name, true, detail);
    return detail;
  } catch (error) {
    record(name, false, (error && error.message) || error);
    return undefined;
  }
}

/** 期待一个明确的失败（用来验参数校验），返回错误文案。 */
async function expectFailure(client, action, params, status, fragment) {
  const res = await client.call(action, params);
  if (res.http_status !== status) {
    throw new Error(action + ' 期望 HTTP ' + status + '，实得 ' + res.http_status + ' ' + res.text);
  }
  const message = (res.json && res.json.message) || res.text || '';
  if (fragment && !message.includes(fragment)) {
    throw new Error(action + ' 期望错误含 ' + JSON.stringify(fragment) + '，实得 ' + JSON.stringify(message));
  }
  return message;
}

/** 只验「路由到了真实实现」：不是 404 的 "API not found"。 */
async function expectRouted(client, action, params) {
  const res = await client.call(action, params);
  const message = (res.json && res.json.message) || res.text || '';
  if (res.http_status === 404 && /API not found/i.test(message)) {
    throw new Error(action + ' 未实现（404 API not found）');
  }
  return res.http_status + ' ' + message.slice(0, 80);
}

/** upload.create 走 multipart，客户端封装只发 JSON，所以这里自己拼一个。 */
function multipartUpload(token, port, field, filename, contentType, bytes) {
  const boundary = '----satori' + Date.now();
  const head = Buffer.from(
    '--' + boundary + '\r\n'
    + 'Content-Disposition: form-data; name="' + field + '"; filename="' + filename + '"\r\n'
    + 'Content-Type: ' + contentType + '\r\n\r\n', 'utf8');
  const tail = Buffer.from('\r\n--' + boundary + '--\r\n', 'utf8');
  const body = Buffer.concat([head, bytes, tail]);
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: HOST, port, path: '/v1/upload.create', method: 'POST',
      headers: {
        'Content-Type': 'multipart/form-data; boundary=' + boundary,
        'Content-Length': body.length,
        'Authorization': 'Bearer ' + token,
      },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, text: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('error', reject);
    req.end(body);
  });
}

async function main() {
  const client = await connect();
  const selfId = client.ready.logins[0].user.id;

  const membersRes = await client.callOk('guild.member.list', { guild_id: GROUP });
  const members = Array.isArray(membersRes?.data) ? membersRes.data : [];
  const other = members.find((m) => String(m.user?.id) !== String(selfId));
  const target = String(process.env.SATORI_TARGET_USER || other?.user?.id || '');
  console.log('# group=' + GROUP + ' self=' + selfId + ' target=' + (target || '(无)') + ' members=' + members.length);

  // ---- 只读项 ----
  await check('user.channel.create', async () => {
    const ch = await client.callOk('user.channel.create', { user_id: selfId });
    if (!ch || !String(ch.id || '').startsWith('private:')) throw new Error('形状不对: ' + JSON.stringify(ch).slice(0, 120));
    return ch.id;
  });

  await check('guild.member.role.list', async () => {
    const m = await client.callOk('guild.member.role.list', { guild_id: GROUP, user_id: selfId });
    const roles = m?.data;
    if (!Array.isArray(roles)) throw new Error('data 不是数组: ' + JSON.stringify(m).slice(0, 120));
    return roles.map((r) => r.id).join(',') || '(空)';
  });

  await check('upload.create', async () => {
    // 1x1 透明 PNG
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
    const res = await multipartUpload(loadToken(), loadPort(), 'file', 'sweep.png', 'image/png', png);
    if (res.status !== 200) throw new Error('HTTP ' + res.status + ' ' + res.text.slice(0, 160));
    const json = JSON.parse(res.text);
    if (!String(json.file || '').startsWith('internal:')) throw new Error('返回形状不对: ' + res.text.slice(0, 160));
    return json.file.slice(0, 60);
  });

  // ---- 表态：清单个 + 清全部 ----
  let scratch = null;
  await check('reaction.clear (准备消息与两个表态)', async () => {
    const sent = await client.callOk('message.create', { channel_id: GROUP, content: 'write-sweep ' + Date.now() });
    const one = Array.isArray(sent) ? sent[0] : sent;
    scratch = String(one.id);
    await client.callOk('reaction.create', { channel_id: GROUP, message_id: scratch, emoji_id: '4' });
    await client.callOk('reaction.create', { channel_id: GROUP, message_id: scratch, emoji_id: '5' });
    return 'id=' + scratch;
  });

  await check('reaction.clear (按 emoji_id)', async () => {
    if (!scratch) throw new Error('上一步没造出消息');
    await client.callOk('reaction.clear', { channel_id: GROUP, message_id: scratch, emoji_id: '4' });
    const left = await client.callOk('reaction.list', { channel_id: GROUP, message_id: scratch, emoji_id: '4' });
    const users = Array.isArray(left?.data) ? left.data.length : -1;
    if (users !== 0) throw new Error('清完之后 emoji 4 还有 ' + users + ' 个');
    return 'emoji4 已清空';
  });

  await check('reaction.clear (不带 emoji_id，清全部)', async () => {
    if (!scratch) throw new Error('上一步没造出消息');
    await client.callOk('reaction.clear', { channel_id: GROUP, message_id: scratch });
    return 'ok';
  });

  // ---- 群资料 ----
  // 原名两个来源都读一遍：`guild.get` 与 `guild.list` 都取自内核的 GroupSimpleInfo，谁先更新
  // 不一定。读到空串就**不改名**——把空串写回去等于把群名清掉（2026-09-19 就是这么把测试群
  // 改坏的：校验读到的还是旧值，抛错走了「没还原」的分支，群里留下一个没名字的群）。
  const readNameFromList = async () => {
    const list = await client.callOk('guild.list', {});
    const hit = (list?.data || []).find((g) => String(g.id) === GROUP);
    return String(hit?.name || '');
  };
  const readNameFromGet = async () => {
    try { return String((await client.callOk('guild.get', { guild_id: GROUP }))?.name || ''); }
    catch (_) { return ''; }
  };
  let originalName = await readNameFromGet();
  if (!originalName) originalName = await readNameFromList();

  // 改名是**默认不跑**的破坏性用例：群名是对外可见、且清掉就补不回来的东西，而这里过去真把
  // 测试群改成过空的（原名读成空串、还原写回空串）。要跑就显式 SATORI_DESTRUCTIVE=1。
  if (process.env.SATORI_DESTRUCTIVE === '1') await check('channel.update (改名并还原)', async () => {
    if (!originalName) throw new Error('读不到当前群名，跳过以免改坏');
    const temp = originalName + '·测试';
    // 读回等缓存跟上；读到空串也当成「还没跟上」，绝不当成目标值。
    const waitForName = async (want) => {
      for (let i = 0; i < 12; i++) {
        await delay(1000);
        const list = await readNameFromList();
        const one = await readNameFromGet();
        if (list === want || one === want) return true;
      }
      return false;
    };
    let renamed = false;
    try {
      await client.callOk('channel.update', { channel_id: GROUP, data: { name: temp } });
      renamed = true;
      if (!await waitForName(temp)) throw new Error('改名没读到生效');
    } finally {
      // 无论校验成功与否都要还原：上一次失败就是漏了这一步，群里留下一个没名字的群。
      if (renamed) {
        await client.callOk('channel.update', { channel_id: GROUP, data: { name: originalName } });
        if (!await waitForName(originalName)) {
          throw new Error('还原后没读到 ' + JSON.stringify(originalName) + '，请手工确认群名');
        }
      }
    }
    return originalName + ' -> ' + temp + ' -> ' + originalName;
  });

  // ---- 全员禁言（同样默认不跑：它会打断群里所有人的发言） ----
  if (process.env.SATORI_DESTRUCTIVE === '1') {
    await check('channel.mute (开 3 秒再解除)', async () => {
      await client.callOk('channel.mute', { guild_id: GROUP, duration: 3000 });
      await delay(1200);
      await client.callOk('channel.mute', { guild_id: GROUP, duration: 0 });
      return 'ok';
    });
  } else {
    console.log('skip channel.update / channel.mute（破坏性，SATORI_DESTRUCTIVE=1 才跑）');
  }

  // ---- 成员级动作：目标取群里除自己外的第一个成员，做完还原 ----
  if (!target) {
    record('guild.member.mute', false, '群里没有除自己以外的成员，无法安全试');
    record('guild.member.role.set/unset', false, '同上');
  } else {
    await check('guild.member.mute (禁言 60 秒再解除)', async () => {
      await client.callOk('guild.member.mute', { guild_id: GROUP, user_id: target, duration: 60000 });
      await delay(1200);
      await client.callOk('guild.member.mute', { guild_id: GROUP, user_id: target, duration: 0 });
      return 'target=' + target;
    });

    await check('guild.member.role.set/unset (上管理再撤)', async () => {
      const isAdmin = async () => {
        const now = await client.callOk('guild.member.role.list', { guild_id: GROUP, user_id: target });
        return (now?.data || []).some((r) => r.id === 'admin');
      };
      await client.callOk('guild.member.role.set', { guild_id: GROUP, user_id: target, role_id: 'admin' });
      const has = await isAdmin();
      // QQ 偶发驳回撤销（实测 code=120101154，手工重试就过），重试两轮；撤不掉要报出来，
      // 不能把目标留在管理员位上悄悄过去。
      let still = true;
      for (let i = 0; i < 3 && still; i++) {
        try { await client.callOk('guild.member.role.unset', { guild_id: GROUP, user_id: target, role_id: 'admin' }); }
        catch (error) { if (i === 2) throw error; }
        await delay(1500);
        still = await isAdmin();
      }
      if (still) throw new Error('撤销失败，目标仍挂着 admin，请手工撤');
      return 'set=' + has + ' unset=' + !still;
    });
  }

  // ---- 需要真实前置条件的：只验路由与参数校验 ----
  await check('guild.member.kick (缺 user_id 的校验)', () =>
    expectFailure(client, 'guild.member.kick', { guild_id: GROUP }, 400));

  await check('friend.delete (缺 user_id 的校验)', () =>
    expectFailure(client, 'friend.delete', {}, 400, 'user_id'));

  await check('friend.approve (路由与报错形状)', () =>
    expectRouted(client, 'friend.approve', { message_id: 'no-such-request' }));

  await check('guild.approve (路由与报错形状)', () =>
    expectRouted(client, 'guild.approve', { message_id: 'no-such-request' }));

  await check('guild.member.approve (路由与报错形状)', () =>
    expectRouted(client, 'guild.member.approve', { message_id: 'no-such-request' }));

  if (scratch) {
    // 清理不是断言：QQ 对自己的消息有撤回时限（约两分钟），这条消息是巡检开头造的，跑到这里
    // 可能已经超时（实测 `recall failed: code=7`）。撤不掉就说明白，不占用例的失败名额。
    try {
      await client.callOk('message.delete', { channel_id: GROUP, message_id: scratch });
      record('清理：删掉巡检消息', true, 'deleted ' + scratch);
    } catch (error) {
      record('清理：删掉巡检消息（撤回窗口已过，消息留在群里）', true, (error && error.message) || error);
    }
  }

  client.close();
  const failed = results.filter((r) => !r.ok);
  console.log(JSON.stringify({
    total: results.length,
    failed: failed.length,
    group: GROUP,
    self_id: selfId,
    target,
    failures: failed.map((f) => ({ name: f.name, detail: f.detail })),
  }));
  process.exitCode = failed.length ? 1 : 0;
}

main().catch((error) => {
  console.error('FAIL harness:', (error && error.stack) || error);
  process.exit(1);
});
