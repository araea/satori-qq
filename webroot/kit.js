// 模块管理页的共用部件（satori-qq 与 satori-wx 逐字相同）：root 调用、列表与开关、提示条与对话框、更新流程。
// 所有 root 动作都经模块自带的 ctl.sh，界面这边只拼参数、不拼脚本。
import { ICONS } from './icons.js';

export const MODULE_ID = document.documentElement.dataset.module;
export const MODDIR = `/data/adb/modules/${MODULE_ID}`;

// ---------------------------------------------------------------- root

let seq = 0;

/** KernelSU / APatch 的 WebUI 接口：exec(命令, 选项, 回调名)，结果回调到 window 上的同名函数。 */
export function exec(cmd) {
  return new Promise((resolve) => {
    const ksu = window.ksu;
    if (!ksu || typeof ksu.exec !== 'function') {
      resolve({ errno: -1, stdout: '', stderr: 'no-webui-bridge' });
      return;
    }
    const name = `satori_exec_${Date.now()}_${seq++}`;
    window[name] = (errno, stdout, stderr) => {
      delete window[name];
      resolve({ errno, stdout: stdout || '', stderr: stderr || '' });
    };
    try {
      ksu.exec(cmd, '{}', name);
    } catch (error) {
      delete window[name];
      resolve({ errno: -1, stdout: '', stderr: String(error) });
    }
  });
}

const quote = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;

/** 跑 ctl.sh 的一条子命令；输出的 key=value 行解析成对象（同名键取最后一个）。 */
export async function ctl(...args) {
  const r = await exec(`/system/bin/sh ${MODDIR}/ctl.sh ${args.map(quote).join(' ')}`);
  return { ok: r.errno === 0, bridge: r.stderr !== 'no-webui-bridge', kv: kv(r.stdout), out: r.stdout };
}

export function kv(text) {
  const out = {};
  for (const line of text.split('\n')) {
    const eq = line.indexOf('=');
    if (eq > 0) out[line.slice(0, eq)] = line.slice(eq + 1).trim();
  }
  return out;
}

export function fromB64(s) {
  if (!s) return null;
  try {
    return new TextDecoder().decode(Uint8Array.from(atob(s), (c) => c.charCodeAt(0)));
  } catch {
    return null;
  }
}

export function toB64(s) {
  let bin = '';
  for (const b of new TextEncoder().encode(s)) bin += String.fromCharCode(b);
  return btoa(bin);
}

export function parseJson(s) {
  if (!s) return null;
  try {
    const v = JSON.parse(s);
    return v && typeof v === 'object' ? v : null;
  } catch {
    return null;
  }
}

/** 本机偏好（自动检查更新等）：只是便利，读写失败就当没有。 */
export function pref(key, value) {
  const k = `${MODULE_ID}.${key}`;
  try {
    if (value === undefined) return localStorage.getItem(k);
    localStorage.setItem(k, String(value));
  } catch {
    // 私密窗口、预览等环境里 localStorage 可能不可用。
  }
  return null;
}

// ---------------------------------------------------------------- 视图

export function h(tag, props = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid);
  return el;
}

export function icon(name, cls = '') {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 -960 960 960');
  svg.setAttribute('class', `icon ${cls}`.trim());
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', ICONS[name] || '');
  svg.append(path);
  return svg;
}

/** 只在文字真的变了时才改，轮询不会让读屏器反复朗读。 */
export function setText(el, text) {
  if (el.textContent !== text) el.textContent = text;
}

export const section = (title) => h('h3', { class: 'section-title', text: title });
export const note = (text) => h('p', { class: 'note', text });
export const group = (...items) => h('div', { class: 'group' }, items.map((i) => i.el || i));

function rowParts(opts) {
  const lead = opts.icon ? h('span', { class: 'lead' }, icon(opts.icon)) : null;
  const headline = h('span', { class: 'headline', text: opts.title || '' });
  const supporting = h('span', { class: 'supporting', text: opts.sub || '' });
  return { lead, headline, supporting, text: h('span', { class: 'text' }, headline, supporting) };
}

function rowApi(el, parts) {
  return {
    el,
    title(t) { setText(parts.headline, t); return this; },
    sub(t) { setText(parts.supporting, t); parts.supporting.hidden = !t; return this; },
    lead(name, tone) {
      if (!parts.lead) return this;
      if (parts.lead.dataset.icon !== name) {
        parts.lead.replaceChildren(icon(name));
        parts.lead.dataset.icon = name;
      }
      parts.lead.className = `lead ${tone || ''}`.trim();
      return this;
    },
    disabled(off) { el.classList.toggle('disabled', !!off); if ('disabled' in el) el.disabled = !!off; return this; },
  };
}

/** 一行：静态（没有 onClick）或可点。trailing 放在右侧（例如复制按钮）。 */
export function item(opts) {
  const parts = rowParts(opts);
  const tag = opts.onClick ? 'button' : 'div';
  const el = h(tag, { class: `item${opts.danger ? ' danger' : ''}`, onclick: opts.onClick, type: opts.onClick ? 'button' : null },
    parts.lead, parts.text, opts.trailing);
  const api = rowApi(el, parts);
  api.sub(opts.sub || '');
  return api;
}

/** 带开关的一行。拨动后先停在用户拨到的位置，由 onChange 的结果决定最终状态。 */
export function switchItem(opts) {
  const parts = rowParts(opts);
  const input = h('input', { type: 'checkbox', role: 'switch' });
  const el = h('label', { class: 'item' }, parts.lead, parts.text,
    h('span', { class: 'switch' }, input, h('span', { class: 'track' }, h('span', { class: 'thumb' }))));
  input.addEventListener('change', () => opts.onChange && opts.onChange(input.checked));
  const api = rowApi(el, parts);
  api.checked = (v) => { input.checked = !!v; return api; };
  api.disabled = (off) => { input.disabled = !!off; el.classList.toggle('disabled', !!off); return api; };
  api.sub(opts.sub || '');
  return api;
}

export function button(label, kind, onClick, iconName) {
  return h('button', { class: `btn ${kind}`, type: 'button', onclick: onClick }, iconName ? icon(iconName) : null, label);
}

export function iconButton(name, label, onClick) {
  return h('button', { class: 'icon-btn', type: 'button', 'aria-label': label, title: label, onclick: onClick }, icon(name));
}

/** 状态卡片：语调决定容器色与图标，文字本身必须已经说清状态。 */
export function hero(label) {
  const glyph = h('span');
  const title = h('h3', { 'aria-live': 'polite', text: '正在检查' });
  const detail = h('p', { 'aria-live': 'polite' });
  const actions = h('div', { class: 'actions' });
  const el = h('section', { class: 'hero neutral' }, h('div', { class: 'label' }, glyph, h('span', { text: label })), title, detail, actions);
  const glyphs = { neutral: 'info', success: 'ok', warning: 'pending', error: 'error' };
  let key = '';
  return {
    el,
    set({ tone, title: t, detail: d, actions: list = [] }) {
      el.className = `hero ${tone}`;
      if (glyph.dataset.tone !== tone) {
        glyph.replaceChildren(icon(glyphs[tone] || 'info'));
        glyph.dataset.tone = tone;
      }
      setText(title, t);
      setText(detail, d || '');
      const next = list.map((a) => a.label).join('|');
      if (next !== key) {
        key = next;
        actions.replaceChildren(...list.map((a, i) => button(a.label, i === 0 ? 'filled' : 'text', a.run)));
      }
    },
  };
}

/** 定义列表：标签与值一一对应。 */
export function dataList(labels) {
  const values = labels.map(() => h('dd', { text: '—' }));
  const el = h('dl', { class: 'data' }, labels.map((l, i) => h('div', {}, h('dt', { text: l }), values[i])));
  return { el, set(list) { list.forEach((v, i) => setText(values[i], v)); } };
}

let snackTimer = 0;
export function snack(message) {
  let bar = document.querySelector('.snackbar');
  if (!bar) {
    bar = h('div', { class: 'snackbar', role: 'status' });
    document.body.append(bar);
  }
  bar.textContent = message;
  bar.classList.add('show');
  clearTimeout(snackTimer);
  snackTimer = setTimeout(() => bar.classList.remove('show'), 3200);
}

export function confirmDialog({ title, body, ok = '确定', danger = false }) {
  return new Promise((resolve) => {
    const done = (v) => { scrim.remove(); resolve(v); };
    const scrim = h('div', { class: 'scrim', onclick: (e) => e.target === scrim && done(false) },
      h('div', { class: 'dialog', role: 'alertdialog', 'aria-modal': 'true' },
        h('h4', { text: title }), body ? h('p', { text: body }) : null,
        h('div', { class: 'row' }, button('取消', 'text', () => done(false)), button(ok, danger ? 'danger' : 'filled', () => done(true)))));
    document.body.append(scrim);
    scrim.querySelector('.btn:last-child').focus();
  });
}

export async function copy(text, what) {
  let ok = false;
  try {
    await navigator.clipboard.writeText(text);
    ok = true;
  } catch {
    const area = h('textarea', { style: 'position:fixed;opacity:0' });
    area.value = text;
    document.body.append(area);
    area.select();
    try { ok = document.execCommand('copy'); } catch { ok = false; }
    area.remove();
  }
  snack(ok ? `已复制${what}` : `没能复制${what}`);
}

/** 顶栏：页面滚过大标题后显示小标题并抬升。 */
export function topBar(title, actions) {
  const el = h('header', { class: 'bar' }, h('h1', { text: title }), actions);
  addEventListener('scroll', () => el.classList.toggle('lifted', scrollY > 48), { passive: true });
  return el;
}

/** 轮询：页面可见时每隔 gap 毫秒跑一次 task（上一次跑完才排下一次），回到前台立即跑。 */
export function poll(task, gap) {
  let timer = 0;
  let running = false;
  const run = async () => {
    clearTimeout(timer);
    if (running) return;
    running = true;
    try { await task(); } finally { running = false; }
    if (document.visibilityState === 'visible') timer = setTimeout(run, gap);
  };
  document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && run());
  return run;
}

export function duration(ms) {
  const minutes = Math.max(0, Math.floor(ms / 60000)), hours = Math.floor(minutes / 60), days = Math.floor(hours / 24);
  if (days > 0) return `${days} 天 ${hours % 24} 小时`;
  if (hours > 0) return `${hours} 小时 ${minutes % 60} 分`;
  return minutes < 1 ? '不到 1 分钟' : `${minutes} 分钟`;
}

export function ago(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return '刚刚';
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`;
  if (s < 86400) return `${Math.floor(s / 3600)} 小时前`;
  return `${Math.floor(s / 86400)} 天前`;
}

// ---------------------------------------------------------------- 更新

const CHECK_OK_GAP = 6 * 3600e3;
const CHECK_FAIL_GAP = 30 * 60e3;

/**
 * 检查更新与一键更新。清单是发布附带的 update.json（模块页的 updateJson 也读它）；下载、sha256 校验与安装都在
 * ctl.sh 里做，安装交给 root 管理器自己的命令（ksud / apd / magisk），装好的更新重启手机后合并。
 *
 * 返回 { cards, list, sync(probe) }：cards 放在页首，list 放在「更新」分组里。
 */
export function updates(name, refresh) {
  const state = { installed: '', code: 0, staged: '', manifest: null, checking: false, installing: false, error: '', checkedAt: Number(pref('checkedAt')) || 0 };

  const updTitle = h('h4');
  const updBody = h('p');
  const updNotes = h('div', { class: 'notes' });
  const updAction = button('更新', 'filled', install, 'download');
  const updCard = h('section', { class: 'card', hidden: true }, updTitle, updBody, updNotes, h('div', { class: 'row' }, updAction));

  const bootBody = h('p');
  const bootCard = h('section', { class: 'card', hidden: true }, h('h4', { text: '重启手机后生效' }), bootBody,
    h('div', { class: 'row' }, button('立即重启', 'filled', reboot, 'restart')));

  const checkRow = item({ icon: 'update', title: '检查更新', onClick: () => check(true) });
  const autoRow = switchItem({ icon: 'wait', title: '自动检查更新', sub: `打开${name}管理页时检查（6 小时内不重复）。模块列表里的更新提示由管理器自己检查。`,
    onChange: (on) => { pref('auto', on ? '1' : '0'); render(); } });

  function newer() {
    const m = state.manifest;
    return m && m.versionCode > state.code && m.version !== state.staged ? m : null;
  }

  function render() {
    const m = newer();
    updCard.hidden = !m;
    if (m) {
      setText(updTitle, `${name} ${m.version} 可以更新`);
      setText(updBody, state.installing ? '正在下载并校验，然后交给管理器安装…' : `当前 ${state.installed}。下载后校验 sha256，再由 root 管理器安装；重启手机后生效。`);
      setText(updNotes, cleanNotes(m.notes));
      updAction.disabled = state.installing;
      updAction.lastChild.textContent = state.installing ? '正在更新…' : '更新';
    }
    bootCard.hidden = !state.staged;
    setText(bootBody, `${name} ${state.staged} 已经装好，重启手机后替换正在运行的 ${state.installed}。`);

    let sub;
    if (state.checking) sub = '正在检查…';
    else if (state.error) sub = `检查失败：${state.error}`;
    else if (m) sub = `有新版本 ${m.version}`;
    else if (state.manifest) sub = `已是最新（${state.installed}）· ${ago(Date.now() - state.checkedAt)}检查`;
    else sub = `当前 ${state.installed || '—'}`;
    checkRow.sub(sub).disabled(state.checking || state.installing);
    autoRow.checked(pref('auto') !== '0');
  }

  async function check(manual) {
    if (state.checking) return;
    state.checking = true;
    state.error = '';
    render();
    const r = await ctl('update-check');
    state.checking = false;
    const m = parseJson(fromB64(r.kv.manifest));
    const valid = m && Number.isInteger(m.versionCode) && typeof m.version === 'string'
      && /^https:\/\//.test(m.zipUrl || '') && /^[0-9a-f]{64}$/.test(m.sha256 || '');
    if (valid) {
      state.manifest = m;
      state.checkedAt = Date.now();
      pref('checkedAt', state.checkedAt);
      pref('failedAt', 0);
    } else {
      state.error = r.kv.error || (m ? '清单不完整' : '读不到更新清单');
      pref('failedAt', Date.now());
    }
    render();
    if (manual && valid && !newer()) snack(state.staged ? '最新版本已经装好，重启手机后生效' : '已是最新版本');
  }

  async function install() {
    const m = newer();
    if (!m || state.installing) return;
    state.installing = true;
    render();
    const r = await ctl('update-install', m.zipUrl, m.sha256);
    state.installing = false;
    if (r.kv.ok === '1') {
      snack(`${name} ${m.version} 已安装，重启手机后生效`);
      state.staged = m.version;
      refresh();
    } else {
      snack(r.kv.error || '更新失败');
    }
    render();
  }

  async function reboot() {
    if (await confirmDialog({ title: '现在重启手机？', body: `重启后${name}换成新版本。正在进行的通话、下载等会被中断。`, ok: '重启' })) {
      ctl('reboot');
    }
  }

  let autoChecked = false;
  return {
    cards: [bootCard, updCard],
    list: group(checkRow, autoRow),
    /** 每次探测后调用：已安装与待生效的版本。第一次调用时按需自动检查。 */
    sync(probe) {
      state.installed = probe.version || '';
      state.code = Number(probe.version_code) || 0;
      state.staged = probe.update === '1' ? (probe.update_version || '新版本') : '';
      render();
      if (autoChecked || !state.code) return;
      autoChecked = true;
      const failedAt = Number(pref('failedAt')) || 0;
      const now = Date.now();
      if (pref('auto') !== '0' && now - state.checkedAt > CHECK_OK_GAP && now - failedAt > CHECK_FAIL_GAP) check(false);
    },
  };
}

/** 更新说明是 Markdown：去掉标题井号、强调与代码标记，留下能直接读的文字。 */
function cleanNotes(text) {
  return String(text || '')
    .replace(/^#+\s*/gm, '')
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .trim();
}
