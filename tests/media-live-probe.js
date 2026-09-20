'use strict';

/**
 * 媒体发送的真机探针：走客户端的真实路径（`upload.create` 上传字节，再 `message.create`
 * 发带 `<audio>` / `<file>` 的元素），发完用 `message.list` 读回 QQ 自己的消息记录确认
 * 落地。acumen 的搭话与音乐/视频房间用的就是这两步，所以这里测通了等于那条路通。
 *
 *   node tests/media-live-probe.js voice      # <audio>，默认 /sdcard/Download/voice-probe.mp3
 *   node tests/media-live-probe.js file       # <file> 聊天气泡（同时进群文件）
 *   node tests/media-live-probe.js filecn     # 同上，中文文件名
 *   node tests/media-live-probe.js groupfile  # 只传群文件（internal/group_file op=upload）
 *   node tests/media-live-probe.js voicerepeat# 同一个音频连发 N 条，看有没有丢
 *   node tests/media-live-probe.js timing     # 只发一条，分开报上传与发送各花多久
 *   node tests/media-live-probe.js list       # 只读最近几条
 *
 * 环境变量：`SATORI_TEST_GROUP`（默认 280183116）、`MP3`、`TIMES`。
 * 会在群里留下消息（`<audio>` / `<file>` / 群文件），不自动撤回——判「有没有落地」靠的就是
 * 它们还在，验完自己撤。转码是偶发失败的，`voicerepeat` 就是用来量这件事的。
 */

const fs = require('fs');
const http = require('http');
const { connect, delay } = require('./satori-client');

const GROUP = String(process.env.SATORI_TEST_GROUP || '280183116');
const HOST = '127.0.0.1';
const PORT = 3001;
const MODE = process.argv[2] || 'voice';

function multipart(parts) {
  const boundary = '----satoriprobe' + Date.now().toString(36);
  const chunks = [];
  for (const part of parts) {
    chunks.push(Buffer.from(
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="${part.name}"; filename="${part.filename}"\r\n` +
      `Content-Type: ${part.contentType}\r\n\r\n`));
    chunks.push(part.data);
    chunks.push(Buffer.from('\r\n'));
  }
  chunks.push(Buffer.from(`--${boundary}--\r\n`));
  return { body: Buffer.concat(chunks), boundary };
}

function post(path, body, headers, timeoutMs = 90000) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: HOST, port: PORT, path, method: 'POST',
      headers: Object.assign({ 'Content-Length': body.length, 'Satori-Platform': 'red' }, headers),
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({
        status: res.statusCode,
        text: Buffer.concat(chunks).toString('utf8'),
      }));
    });
    req.on('error', reject);
    req.setTimeout(timeoutMs, () => { req.destroy(); reject(new Error('timeout ' + path)); });
    req.end(body);
  });
}

async function upload(filename, contentType, data) {
  const { body, boundary } = multipart([
    { name: 'file', filename, contentType, data },
  ]);
  const res = await post('/v1/upload.create', body, {
    'Content-Type': 'multipart/form-data; boundary=' + boundary,
  });
  if (res.status !== 200) throw new Error('upload.create ' + res.status + ' ' + res.text);
  return JSON.parse(res.text);
}

async function main() {
  const client = await connect();
  const login = client.ready?.logins?.[0];
  console.log('READY self=' + login?.user?.id + ' platform=' + login?.platform);

  if (MODE === 'list') {
    await dump(client, 12);
    client.close();
    return;
  }

  if (MODE === 'timing') {
    const src = process.env.MP3 || '/sdcard/Download/voice-probe.mp3';
    const data = fs.readFileSync(src);
    let t = Date.now();
    const up = await upload('timing-probe.mp3', 'audio/mpeg', data);
    console.log('upload ' + data.length + ' bytes = ' + (Date.now() - t) + 'ms');
    t = Date.now();
    const sent = await client.call('message.create', {
      channel_id: GROUP, content: `<audio src="${up.file}"/>`,
    }, 120000);
    console.log('message.create = ' + (Date.now() - t) + 'ms http=' + sent.http_status);
    await delay(2000);
    await dump(client, 2);
  }

  if (MODE === 'voice') {
    const src = process.env.MP3 || '/sdcard/Download/voice-probe.mp3';
    const data = fs.readFileSync(src);
    console.log('uploading voice ' + src + ' (' + data.length + ' bytes)');
    const up = await upload('voice-probe.mp3', 'audio/mpeg', data);
    console.log('upload -> ' + JSON.stringify(up));
    const content = `<audio src="${up.file}"/>`;
    console.log('message.create ' + content);
    const sent = await client.call('message.create', { channel_id: GROUP, content }, 60000);
    console.log('send http=' + sent.http_status + ' ' + sent.text.slice(0, 400));
    await delay(3000);
    await dump(client, 6);
  }

  if (MODE === 'file') {
    const stamp = Date.now().toString(36);
    const filename = `media-probe-${stamp}.txt`;
    const data = Buffer.from(`media probe ${stamp}\n`);
    console.log('uploading file ' + filename);
    const up = await upload(filename, 'text/plain', data);
    console.log('upload -> ' + JSON.stringify(up));
    const content = `<file src="${up.file}" title="${filename}"/>`;
    console.log('message.create ' + content);
    const sent = await client.call('message.create', { channel_id: GROUP, content }, 60000);
    console.log('send http=' + sent.http_status + ' ' + sent.text.slice(0, 400));
    await delay(3000);
    await dump(client, 6);
  }

  if (MODE === 'filecn') {
    const filename = '测试文件 中文名.txt';
    const data = Buffer.from('中文名文件探针\n');
    const up = await upload(filename, 'text/plain', data);
    console.log('upload -> ' + JSON.stringify(up));
    const content = `<file src="${up.file}" title="${filename}"/>`;
    console.log('message.create ' + content);
    const sent = await client.call('message.create', { channel_id: GROUP, content }, 60000);
    console.log('send http=' + sent.http_status + ' ' + sent.text.slice(0, 300));
    await delay(3000);
    await dump(client, 4);
    const files = await client.call('internal/group_file', { group_id: Number(GROUP), op: 'list' });
    console.log('group_file list http=' + files.http_status + ' ' + files.text.slice(0, 400));
  }

  if (MODE === 'voicerepeat') {
    const src = process.env.MP3 || '/sdcard/Download/voice-probe.mp3';
    const times = Number(process.env.TIMES || '3');
    const data = fs.readFileSync(src);
    const up = await upload('voice-repeat.mp3', 'audio/mpeg', data);
    console.log('upload -> ' + JSON.stringify(up));
    const ids = [];
    for (let i = 0; i < times; i++) {
      const res = await client.call('message.create', {
        channel_id: GROUP, content: `<audio src="${up.file}"/>`,
      }, 60000);
      console.log('#' + i + ' http=' + res.http_status + ' ' + res.text.slice(0, 160));
      if (res.http_status === 200) {
        try { ids.push(JSON.parse(res.text)[0].id); } catch (_) {}
      }
      await delay(2500);
    }
    await delay(3000);
    await dump(client, 20);
    console.log('sent ids: ' + ids.join(','));
  }

  if (MODE === 'groupfile') {
    const stamp = Date.now().toString(36);
    const filename = `group-probe-${stamp}.txt`;
    const data = Buffer.from(`group file probe ${stamp}\n`);
    const up = await upload(filename, 'text/plain', data);
    console.log('upload -> ' + JSON.stringify(up));
    const put = await client.call('internal/group_file', {
      group_id: Number(GROUP), op: 'upload', file: up.file, name: filename, folder_id: '/',
    });
    console.log('group_file upload http=' + put.http_status + ' ' + put.text.slice(0, 500));
    await delay(1500);
    const files = await client.call('internal/group_file', { group_id: Number(GROUP), op: 'list' });
    console.log('group_file list http=' + files.http_status + ' ' + files.text.slice(0, 900));
  }

  client.close();
}

async function dump(client, count) {
  const res = await client.call('message.list', { channel_id: GROUP, next: '' });
  if (res.http_status !== 200) {
    console.log('message.list ' + res.http_status + ' ' + res.text.slice(0, 300));
    return;
  }
  const data = JSON.parse(res.text).data || [];
  console.log('--- recent ' + Math.min(count, data.length) + ' of ' + data.length + ' ---');
  for (const m of data.slice(0, count)) {
    console.log(m.timestamp + ' ' + m.id + ' | ' + String(m.content).slice(0, 200));
  }
}

main().catch((error) => {
  console.error('FAIL ' + (error && error.stack || error));
  process.exit(1);
});
