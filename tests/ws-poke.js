'use strict';

/**
 * 戳一戳（poke）回归测试。
 *
 * 覆盖两条链路：
 *   1. 出站：`internal/poke` 打出的 OIDB 0xED3_1，以及参数校验（缺 user_id 必须 400）。
 *   2. 入站：QQ 回推的灰条（elementType 8 / XML nudge）被转成 `internal` + `_type=satori-qq/poke`
 *      事件，字段 user_id / target_id / group_id 落在正确的人身上。
 *
 * 戳别人，别戳自己：服务端对「戳自己」回 `Process_Nudge failed`（实测），那是服务端拒绝，
 * 拿它当用例会得出错误结论。测试群必须显式给，理由见 README「测试群」一节。
 *
 *   SATORI_TEST_GROUP=<群号> node tests/ws-poke.js
 */

const { connect, delay } = require('./satori-client');

const GROUP = String(process.env.SATORI_TEST_GROUP || '');
if (!GROUP) {
  console.error('需要 SATORI_TEST_GROUP=<群号>：脚本不猜测试群。');
  process.exit(2);
}

let failed = 0;
function record(name, ok, detail) {
  console.log((ok ? 'ok   ' : 'FAIL ') + name + (ok || detail === undefined ? '' : '  ' + String(detail).slice(0, 220)));
  if (!ok) failed++;
}

/** 出站护栏说「等一下再试」时等着重试（与 ws-feature-sweep 同一套判据）。 */
async function check(name, fn) {
  for (let attempt = 0; ; attempt++) {
    try {
      record(name, true, await fn());
      return;
    } catch (error) {
      const message = String((error && error.message) || error);
      const hit = /retry after (\d+)s/.exec(message);
      if (!hit || attempt >= 2) {
        record(name, false, message);
        return;
      }
      await delay((parseInt(hit[1], 10) + 2) * 1000);
    }
  }
}

async function main() {
  const client = await connect();
  const selfId = String(client.ready?.logins?.[0]?.user?.id || '');
  if (!selfId || selfId === '0') throw new Error('READY 里没有可用的 self_id: ' + selfId);
  console.log(`self=${selfId} group=${GROUP}`);

  // 1. 能力表把 poke 标成可写动作，参数表也列出来。
  await check('capabilities 宣告 poke', async () => {
    const caps = await client.callOk('internal/capabilities');
    const actions = (caps?.actions || []).map(String);
    if (!actions.includes('poke')) throw new Error('actions 里没有 poke: ' + JSON.stringify(actions));
    const params = caps?.params?.poke;
    if (typeof params !== 'string' || !params.includes('user_id')) {
      throw new Error('params.poke 没写 user_id: ' + JSON.stringify(params));
    }
    return params;
  });

  // 2. 缺参数必须是 400，不能悄悄去戳一个 0 号。
  await check('internal/poke(缺 user_id) -> 400', async () => {
    const message = await client.callExpect('internal/poke', { guild_id: GROUP }, 400);
    if (!/missing user_id/i.test(message)) throw new Error('错误文案不对: ' + message);
    return message;
  });

  // 3. 真正的出站戳 + 入站事件。
  let target = '';
  await check('挑一个非自己的群成员', async () => {
    const res = await client.callOk('guild.member.list', { guild_id: GROUP });
    const members = (res?.data || []).map((m) => String(m.user?.id)).filter(Boolean);
    target = members.find((id) => id !== selfId) || '';
    if (!target) throw new Error('测试群里除了自己没有别人，换个群：members=' + JSON.stringify(members));
    return 'target=' + target;
  });
  if (!target) {
    client.close();
    process.exit(1);
  }

  const marker = 'poke-' + Date.now();
  const before = client.events.length;
  await check('internal/poke -> 事件 satori-qq/poke', async () => {
    await client.callOk('internal/poke', { guild_id: GROUP, user_id: target });
    const ev = await client.waitFor(
      (e) => e.type === 'internal' && e._type === 'satori-qq/poke'
        && String(e._data?.target_id) === target && String(e._data?.group_id) === GROUP,
      15000, `poke 事件(自 ${before} 起) ${marker}`);
    const data = ev._data || {};
    if (String(data.user_id) !== selfId) throw new Error('发起人不是自己: ' + JSON.stringify(data));
    if (String(data.target_id) !== target) throw new Error('目标不是 ' + target + ': ' + JSON.stringify(data));
    if (!ev.user?.id) throw new Error('事件缺 user.id: ' + JSON.stringify(ev).slice(0, 200));
    return `user=${data.user_id} target=${data.target_id} group=${data.group_id}`;
  });

  client.close();
  console.log(failed ? `== ${failed} 项失败 ==` : '== 全部通过 ==');
  process.exit(failed ? 1 : 0);
}

main().catch((error) => {
  console.error('FATAL', error && error.stack || error);
  process.exit(1);
});
