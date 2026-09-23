'use strict';
// Read-only by default. Explicit SATORI_TEST_GROUP + SATORI_INTERACTION_TEST=1
// authorizes one message, two reactions, clearing our reactions, one poke and a dice.
const assert = require('node:assert/strict');
const { connect, delay } = require('./satori-client');
async function main() {
  const options = { token: process.env.SATORI_TOKEN };
  const client = await connect(options);
  const heartbeat = setInterval(() => client.ping(), 10000);
  let resumed;
  let scratch;
  async function call(method, params = {}) {
    // Only retry explicit pre-admission throttles; never retry timeout/unknown results.
    for (let i = 0; ; i++) {
      try { return await client.callOk(method, params, 65000); }
      catch (error) {
        const wait = /retry after (\d+)s/.exec(String(error));
        if (!wait || i >= 2) throw error;
        await delay((Number(wait[1]) + 1) * 1000);
      }
    }
  }
  try {
    const login = client.ready.logins[0];
    assert.equal(login.adapter, 'satori-qq');
    assert.ok(client.ready.satori_qq.session_id);
    assert.ok(!login.features.includes('reaction.clear'));
    const caps = await call('internal/capabilities');
    for (const action of ['poke', 'reaction_summary', 'reaction_clear', 'dice', 'rps']) assert.ok(caps.actions.includes(action));
    await client.callExpect('reaction.clear', {}, 404);
    console.log('ok READY/session, capability discovery, standard clear unsupported');
    if (process.env.SATORI_INTERACTION_TEST !== '1') return;
    const group = process.env.SATORI_TEST_GROUP;
    assert.match(group || '', /^\d+$/, 'explicit test group required');
    const members = await call('guild.member.list', {guild_id: group});
    const target = members.data.map(m => String(m.user?.id || '')).find(id => id && id !== String(login.user.id));
    assert.ok(target, 'another group member is required for poke');
    const before = client.events.length;
    const sent = await call('message.create', {channel_id: group, content: '[适配验证] 测试表情回应、戳一戳与骰子，完成后撤回本条。'});
    scratch = sent[0]?.id;
    assert.equal(typeof scratch, 'string');
    const params = {channel_id: group, message_id: scratch};
    await call('reaction.create', {...params, emoji_id:'4'});
    await call('reaction.create', {...params, emoji_id:'76'});
    const summary = await call('internal/reaction_summary', params);
    assert.equal(summary.message_id, scratch);
    assert.equal(summary.source, 'kernel_cache');
    for (const id of ['4','76']) assert.ok(summary.data.some(e => e.emoji_id === id && e.self));
    const users = await call('reaction.list', {...params, emoji_id:'4'});
    assert.ok(Array.isArray(users.data));
    await client.callExpect('reaction.list', params, 400);
    console.log('ok message receipt, two reactions, summary and standard user list');
    const cleared = await call('internal/reaction_clear', params);
    assert.equal(cleared.scope, 'self');
    assert.equal(cleared.cleared, 2);
    const remaining = await call('internal/reaction_summary', params);
    assert.ok(remaining.data.every(e => !e.self));
    assert.equal((await call('internal/reaction_clear', params)).cleared, 0);
    console.log('ok own-only clear is idempotent despite stale kernel counts');
    await call('internal/poke', {channel_id:group, user_id:target});
    await client.waitFor(e => e.type === 'internal' && e._type === 'satori-qq/poke'
      && String(e._data?.target_id) === target && String(e._data?.group_id) === group, 15000, 'poke event');
    console.log('ok channel-scoped poke and native incoming event');
    await call('internal/dice', {channel_id:group});
    console.log('ok JNI animated dice');
    await delay(1500);
    const reactions = client.events.slice(before).filter(e => e.type.startsWith('reaction-') && e.message?.id === scratch);
    assert.ok(reactions.some(e => e.type === 'reaction-added'));
    assert.ok(reactions.some(e => e.type === 'reaction-removed'));
    for (const event of reactions) {
      assert.equal(event._type, 'satori-qq/reaction');
      assert.equal(typeof event._data.delta, 'number');
      assert.ok(!event.user, 'aggregate count cannot identify the actor');
    }
    const cursor = reactions[0].sn;
    resumed = await connect({...options, identifyBody:{sn:cursor}});
    await delay(400);
    const replay = resumed.events.filter(e => e.sn > cursor);
    for (const original of reactions.filter(e => e.sn > cursor)) assert.ok(replay.some(e => e.sn === original.sn));
    assert.ok(replay.every((e, i) => !i || e.sn > replay[i-1].sn));
    assert.ok(replay.every(e => !e.type.startsWith('login-')));
    console.log('ok reaction events/count metadata, ordered replay without login events');
  } finally {
    if (scratch) {
      try { await call('message.delete', {channel_id:process.env.SATORI_TEST_GROUP, message_id:scratch}); console.log('ok test message recalled'); }
      catch (e) { console.error('cleanup:', String(e)); }
    }
    clearInterval(heartbeat);
    resumed?.close(); client.close();
  }
}
main().catch(error => { console.error(error); process.exitCode=1; });
