'use strict';

/**
 * 新增写动作的现场验证。每项都自己还原：分组建了就删，特别关心开了就关，
 * 性别按读回来的原值写回。只碰测试群 280183116 与 bot 自己的账号。
 *
 * 用法： node tests/ws-kernel-writes.js
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
    if (expect && !expect(out)) throw new Error('unexpected shape: ' + JSON.stringify(out).slice(0, 240));
    record(name, true, out);
    return out;
  } catch (error) {
    record(name, false, error && error.message || error);
    return undefined;
  }
}

async function main() {
  const client = await connect();
  const selfId = String(client.ready?.logins?.[0]?.user?.id);
  console.log('self=' + selfId + ' group=' + GROUP + '\n');

  const call = (action, params) => client.callOk('internal/' + action, params || {});

  // ---- 好友分组：建、改名、删，全程自清理 ----
  const before = await check('buddy_category.list', () => call('buddy_category'),
    (o) => o && Array.isArray(o.categories));
  const usedIds = new Set((before?.categories || []).map((c) => c.categoryId));
  const stamp = 'zz-satori-test-' + Date.now().toString(36);

  const created = await check('buddy_category.add', () => call('buddy_category', { op: 'add', name: stamp }),
    (o) => o && o.op === 'add');
  let newId = -1;
  for (const c of (created?.categories || [])) {
    if (c.categroyName === stamp) newId = c.categoryId;
  }
  if (newId < 0) {
    record('buddy_category.find-new', false, '新建的分组没有出现在列表里');
  } else {
    record('buddy_category.find-new', true, 'id=' + newId);
    await check('buddy_category.rename', () => call('buddy_category',
      { op: 'rename', category_id: newId, name: stamp + '-r' }),
      (o) => o && o.op === 'rename');
    await check('buddy_category.delete', () => call('buddy_category',
      { op: 'delete', category_id: newId }), (o) => o && o.op === 'delete');
    const after = await call('buddy_category').catch(() => null);
    const stillThere = (after?.categories || []).some((c) => c.categoryId === newId);
    record('buddy_category.cleaned', !stillThere, stillThere ? '测试分组没删掉' : '');
  }
  const leaked = [...usedIds].length && (await call('buddy_category').catch(() => null));
  void leaked;

  // ---- 特别关心：开回来再关掉，恢复原状 ----
  const relation = await call('profile_relation_flag', { user_id: selfId }).catch(() => null);
  const flags = relation?.relation || {};
  const wasOn = Object.values(flags).some((f) => f && f.isSpecialCareOpen === true);

  const toggled = await check('special_care.on', () => call('special_care', { user_id: selfId, enable: true }),
    (o) => o && o.enable === true);
  if (toggled) {
    await call('special_care', { user_id: selfId, enable: wasOn });
    record('special_care.restored', true, wasOn ? '原本就是开着的，已写回开' : '已写回关闭');
  }

  // ---- 生日：读回原值再写回同一个值，验证通路不改数据 ----
  const detail = await call('user_detail', { user_id: selfId }).catch(() => null);
  const birth = readBirthday(detail);
  if (!birth) {
    record('profile_set_birthday', false, '读不到当前生日，跳过（不盲改）');
  } else {
    record('profile_set_birthday.read', true, JSON.stringify(birth));
    await check('profile_set_birthday.write', () => call('profile_set_birthday', birth),
      (o) => o && o.month === birth.month && o.day === birth.day);
  }

  // ---- 已读：对测试群标记已读 ----
  await check('mark_read', () => call('mark_read', { channel_id: GROUP }), (o) => o && o.read === true);

  const failed = results.filter((r) => !r.ok).length;
  console.log('\n' + (results.length - failed) + '/' + results.length + ' passed');
  process.exit(failed ? 1 : 0);
}

/** 从 user_detail 的反射负载里找生日，找不到返回 null（宁可跳过也不盲改）。 */
function readBirthday(detail) {
  const find = (node, wanted, depth) => {
    if (!node || typeof node !== 'object' || depth > 5) return null;
    for (const [key, value] of Object.entries(node)) {
      if (key.toLowerCase() === wanted && typeof value === 'number') return value;
    }
    for (const value of Object.values(node)) {
      const hit = find(value, wanted, depth + 1);
      if (hit !== null) return hit;
    }
    return null;
  };
  const year = find(detail, 'birthdayyear', 0);
  const month = find(detail, 'birthdaymonth', 0);
  const day = find(detail, 'birthdayday', 0);
  if (year === null || month === null || day === null) return null;
  return { year, month, day };
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
