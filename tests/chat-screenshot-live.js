// Read-only on-device acceptance: SATORI_TEST_GROUP=<group> node tests/chat-screenshot-live.js
// Fetches one local history message and checks the generated PNG. No chat content is printed.
const assert = require('node:assert/strict');
const group = process.env.SATORI_TEST_GROUP;
if (!group) throw new Error('set SATORI_TEST_GROUP');
const base = process.env.SATORI_ENDPOINT || 'http://127.0.0.1:3001';
const headers = { 'Content-Type': 'application/json' };
if (process.env.SATORI_TOKEN) headers.Authorization = `Bearer ${process.env.SATORI_TOKEN}`;
async function post(path, body) {
  const res = await fetch(base + path, { method: 'POST', headers, body: JSON.stringify(body) });
  const data = await res.json();
  if (!res.ok) throw new Error(`${path}: HTTP ${res.status} ${JSON.stringify(data)}`);
  return data;
}
(async () => {
  const history = await post('/v1/message.list', { channel_id: group, limit: 1 });
  const id = history.data?.[0]?.id;
  if (!id) throw new Error('no history message in this group');
  const result = await post('/v1/internal/chat_screenshot', {
    channel_id: group, start_message_id: id, end_message_id: id,
  });
  assert.equal(result.count, 1);
  assert.equal(result.mime, 'image/png');
  assert.match(result.file, /^internal:red\/\d+\/_tmp\//);
  const url = new URL(result.url);
  assert.equal(url.origin, new URL(base).origin);
  const image = await fetch(url);
  assert.equal(image.status, 200);
  assert.equal(image.headers.get('content-type'), 'image/png');
  const png = Buffer.from(await image.arrayBuffer());
  assert.equal(png.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  console.log('chat_screenshot: 1 local history message -> PNG OK');
})().catch(e => { console.error(e); process.exitCode = 1; });
