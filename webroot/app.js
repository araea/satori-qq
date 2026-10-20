// 知弦管理页：服务是否就绪 → 断在哪一环 → 下一步做什么，然后是连接地址、设置、开关、常驻守护、更新与诊断。
// 判定规则沿用原知弦应用的 Status：拿不到数据时只说「未知」或「未连接」，绝不沿用上一次的在线结论。
import {
  ctl, fromB64, toB64, parseJson, h, icon, setText, section, note, group, item, switchItem, button, iconButton,
  hero as makeHero, dataList, snack, confirmDialog, copy, topBar, poll, duration, updates,
} from './kit.js';

const DEFAULT_PORT = 3001;
/** 管理页能改的开关：键、标题、说明。其余键（高级选项）保存时原样保留。 */
const SWITCHES = [
  ['status_notification', '状态通知', '在通知栏显示连接状态，点按可回到 QQ。'],
  ['wake_lock_auto', '启动时保持唤醒', 'QQ 启动后立即持有唤醒锁，减少掉线，待机耗电会增加。'],
  ['wifi_sustain', '保持 Wi-Fi 连接', '有客户端连接时持有 Wi-Fi 锁。只在 Wi-Fi 下有效。'],
  ['manual_self_messages', '投递手机上手动发的消息', '你在 QQ 里亲手发的消息也作为事件投递，作者为独立身份 qq-client:账号，方便测试机器人。'],
];

// ---------------------------------------------------------------- 一次检查

const S = { checked: false, d: null, config: null, configBad: false, health: null, healthPort: 0, guard: null, relaunching: false };

function readProbe(r) {
  const p = r.kv;
  S.checked = true;
  if (!r.bridge || p.version === undefined) {
    S.d = null;
    return p;
  }
  S.d = {
    module: p.module === '1', disabled: p.disabled === '1', removing: p.removing === '1', updatePending: p.update === '1',
    version: p.version || '', updateVersion: p.update_version || '', enabled: p.enabled !== '0',
    installed: p.installed === '1', qqVersion: p.qq_version || '', pid: Number(p.pid) || 0, frozen: p.frozen === '1',
  };
  const text = fromB64(p.config);
  S.config = parseJson(text);
  S.configBad = text != null && !S.config;
  S.health = parseJson(fromB64(p.health));
  S.healthPort = Number(p.health_port) || 0;
  S.guard = parseJson(fromB64(p.guard));
  return p;
}

const cfg = () => S.config || {};
const port = () => (Number.isInteger(cfg().port) ? cfg().port : DEFAULT_PORT);
const revision = () => Number(cfg().revision) || 0;

function state() {
  if (!S.checked) return 'checking';
  const d = S.d;
  if (!d) return 'no-root';
  if (!d.module) return d.updatePending ? 'module-pending' : 'no-module';
  if (d.disabled || d.removing) return 'module-off';
  if (!d.enabled) return 'disabled';
  if (S.relaunching) return 'relaunching';
  const hz = S.health;
  if (!hz) return d.installed ? (d.pid ? (d.frozen ? 'frozen' : 'unreachable') : 'stopped') : 'no-qq';
  if (hz.config_status === 'module-invalid') return 'config-bad';
  if (!hz.listening) return 'not-listening';
  if (!hz.online) return 'logged-out';
  return 'ready';
}

/** 已保存的设置有没有被运行中的服务采用：Zygisk 只在 QQ 启动时读 config.json。 */
function pending() {
  const hz = S.health;
  if (!hz || !S.config) return false;
  return hz.config_status !== 'module' || (Number(hz.config_revision) || 0) !== revision() || S.healthPort !== port();
}

// ---------------------------------------------------------------- 动作

async function act(args, fail, done) {
  const r = await ctl(...args);
  if (!r.ok || r.kv.error) snack(r.kv.error || fail);
  else if (done) snack(done);
  refresh();
  return r;
}

const openQQ = () => act(['open'], '没能打开 QQ');

async function relaunch() {
  S.relaunching = true;
  render();
  await act(['relaunch'], '没能重新启动 QQ');
  setTimeout(() => { S.relaunching = false; refresh(); }, 4000);
}

const setEnabled = (on) => act(['set-enabled', on ? '1' : '0'], '没能写入模块开关', on ? '模块已开启' : '模块已关闭');
const setGuard = (on) => act(['guard', on ? 'start' : 'stop'], '看守没有响应', on ? '常驻守护已开启' : '常驻守护已暂停');

async function stopQQ() {
  if (await confirmDialog({ title: '停止保活并关闭 QQ？', body: '看守转为暂停，QQ 被强行停止；之后不会自动拉起，直到重新开启守护。', ok: '关闭 QQ', danger: true })) {
    act(['guard', 'kill'], '看守没有响应', 'QQ 已关闭，守护已暂停');
  }
}

// ---------------------------------------------------------------- 结论

function heroLine() {
  const hz = S.health;
  const open = { label: '打开 QQ', run: openQQ };
  const restart = { label: '重新启动 QQ', run: relaunch };
  switch (state()) {
    case 'checking': return { tone: 'neutral', title: '正在检查', detail: '正在连接 QQ 里的知弦服务…' };
    case 'no-root': return { tone: 'error', title: '需要在 root 管理器里打开', detail: '这个页面通过 KernelSU / APatch 的模块网页界面执行 root 命令；在模块列表里点知弦打开它。' };
    case 'module-pending': return { tone: 'warning', title: '重启手机后启用', detail: '知弦模块已经刷入，重启手机后生效。' };
    case 'no-module': return { tone: 'error', title: '没有安装知弦模块', detail: '用 KernelSU 或 Magisk 刷入 SatoriQQ-module.zip，然后重启手机。模块依赖 Zygisk。' };
    case 'module-off': return S.d.removing
      ? { tone: 'warning', title: '模块将被卸载', detail: '知弦模块已标记为卸载，重启手机后移除。要保留它，在模块列表里撤销卸载。' }
      : { tone: 'warning', title: '模块已停用', detail: '在模块列表里重新启用知弦，然后重启手机。' };
    case 'disabled': return { tone: 'neutral', title: '已关闭', detail: '模块开关已关闭：不监听端口、不显示通知、不保活，和没有安装时一样。', actions: [{ label: '开启模块', run: () => setEnabled(true) }] };
    case 'relaunching': return { tone: 'neutral', title: '正在重新启动 QQ', detail: '新设置随 QQ 启动生效；服务恢复后，客户端会自动重连。' };
    case 'no-qq': return { tone: 'error', title: '没有安装 QQ', detail: '知弦运行在 QQ 里，请先安装 QQ。' };
    case 'stopped': return { tone: 'warning', title: 'QQ 没有在运行', detail: '知弦服务随 QQ 启动。打开 QQ 后服务随之恢复。', actions: [open] };
    case 'frozen': return { tone: 'warning', title: 'QQ 被系统冻结了', detail: 'QQ 退到后台后被系统冻结，服务随之暂停。开启常驻守护后，看守会在冻结时立即解冻。', actions: [open] };
    case 'unreachable': return { tone: 'error', title: '未连接', detail: d().updatePending ? '模块更新已经刷入但还没生效。重启手机后再试。' : 'QQ 在运行，但没有连上里面的知弦服务。刚刷入模块时需要重启手机；否则重新启动 QQ 通常可以恢复。', actions: [restart] };
    case 'config-bad': return { tone: 'error', title: '设置文件无效', detail: '模块目录里的 config.json 读不出来，服务保持关闭。在下面的设置里重新保存一次，再重新启动 QQ。', actions: [restart] };
    case 'not-listening': return { tone: 'warning', title: '服务待恢复', detail: '模块已经加载，但本机端口还没有就绪。重新启动 QQ 通常可以恢复。', actions: [restart] };
    case 'logged-out': return { tone: 'warning', title: '等待登录', detail: '本机服务已就绪。在 QQ 里登录账号后，客户端就能收发消息。', actions: [open] };
    default: {
      const clients = hz.connections || 0;
      return { tone: 'success', title: '连接就绪', detail: clients ? `${clients} 个 Satori 客户端已接入。` : 'QQ 与本机服务都已就绪，正在等待 Satori 客户端接入。' };
    }
  }
}

const d = () => S.d;
const STEP = { ok: ['ok', 'ok'], wait: ['pending', 'wait'], fail: ['error', 'fail'], unknown: ['info', ''] };

function steps() {
  const st = state();
  const hz = S.health;
  if (!hz || ['disabled', 'checking', 'no-root', 'relaunching'].includes(st)) {
    const service = ['unreachable', 'frozen', 'not-listening'].includes(st) ? (st === 'frozen' ? 'wait' : 'fail') : 'unknown';
    const values = st === 'disabled' ? ['—', '模块已关闭', '—'] : st === 'checking' || st === 'relaunching' ? ['检查中', '检查中', '检查中']
      : st === 'stopped' ? ['—', '随 QQ 启动', '—'] : st === 'frozen' ? ['—', '随 QQ 冻结而暂停', '—'] : ['未知', '未连接', '未知'];
    return [['unknown', service, 'unknown'], values];
  }
  const clients = hz.connections || 0;
  const p = S.healthPort || port();
  return [
    [hz.online ? 'ok' : 'wait', hz.listening ? 'ok' : 'fail', clients ? 'ok' : hz.listening ? 'wait' : 'unknown'],
    [hz.online ? `已登录${hz.self_id ? `（${hz.self_id}）` : ''}` : '未登录',
      `${hz.listening ? `端口 ${p} 已就绪` : `端口 ${p} 未就绪`} · v${hz.version || '?'}`,
      clients ? `${clients} 个已接入` : hz.listening ? '等待接入' : '未知'],
  ];
}

function guardText() {
  const g = S.guard;
  if (!g) return S.d ? '不可用：没有找到 qqguard' : '正在读取守护状态…';
  if (g.mode !== 'ARMED') return '已暂停，QQ 退出后不会被自动拉起';
  return `保活中${g.running ? '' : '，但看守进程未运行'}${g.qq_frozen ? '；QQ 当前被系统冻结' : ''}`;
}

function diagnostics() {
  const hz = S.health;
  const dd = S.d;
  let module = !dd ? '未知' : !dd.module ? '未安装' : dd.version || '已安装';
  if (dd?.updateVersion) module += `（${dd.updateVersion} 待重启手机）`;
  let running = '未连接', compat = '—', notice = '—', failures = '—', sessionErrors = '—';
  if (hz) {
    running = hz.version || '未知';
    if (dd?.version && dd.version !== hz.version) running += '（与已安装的模块不同，重启手机后更新）';
    const c = hz.compat;
    compat = !c ? '待检查' : `${c.passed} / ${c.total}${c.passed >= c.total ? '，全部可用' : `，缺 ${c.total - c.passed} 项`}`;
    notice = String(hz.notice || '').includes('posted=yes') ? '已显示' : '未显示';
    failures = `${hz.sso?.failures || 0} 次`;
    sessionErrors = `${hz.sso?.session_errors || 0} 次`;
  }
  const g = S.guard;
  const keeper = !g ? '不可用' : `${g.mode === 'ARMED' ? '保活中' : '已暂停'} · 1 小时内重启 ${g.restarts_1h || 0} 次`;
  const source = !hz ? '—' : { module: `管理页（第 ${hz.config_revision} 版）`, default: '默认值', 'module-invalid': 'config.json 无效' }[hz.config_status]
    || String(hz.config_status || '—').replace(/^file:/, '文件 ');
  return [!dd ? '未知' : dd.enabled ? '开启' : '已关闭', module, running, dd?.qqVersion || (dd?.installed ? '已安装' : '未安装'),
    compat, source, notice, failures, sessionErrors, keeper];
}

const DIAG = ['模块开关', '已安装的模块', '运行中的模块', 'QQ 版本', '内核接口', '设置来源', '状态通知', '请求失败', '会话错误', '常驻守护'];

function report() {
  const lines = ['知弦 · 诊断报告', `检查时间：${new Date().toLocaleString('zh-CN', { hour12: false })}`, `结论：${heroLine().title}`];
  diagnostics().forEach((v, i) => lines.push(`${DIAG[i]}：${v}`));
  lines.push('报告只含版本、状态与错误计数，不含令牌、QQ 账号或消息内容。');
  return lines.join('\n');
}

// ---------------------------------------------------------------- 页面

const refreshBtn = iconButton('refresh', '刷新状态', () => refresh());
const heroCard = makeHero('本机服务');
const clientsValue = h('b', { text: '—' });
const uptimeValue = h('b', { text: '—' });
const chain = ['QQ 账号', '本机服务', 'Satori 客户端'].map((t) => item({ icon: 'info', title: t, sub: '检查中' }));
const endpoint = item({ title: `http://127.0.0.1:${DEFAULT_PORT}`, sub: 'Satori 地址 · 只供本机客户端使用',
  trailing: iconButton('copy', '复制连接地址', () => copy(`http://127.0.0.1:${port()}`, '连接地址')) });
const tokenRow = item({ icon: 'key', title: '令牌', sub: '读取中',
  trailing: iconButton('copy', '复制令牌', () => (cfg().token ? copy(cfg().token, '令牌') : snack('没有设置令牌'))) });

const portField = h('input', { inputmode: 'numeric', autocomplete: 'off', placeholder: String(DEFAULT_PORT) });
const tokenField = h('input', { type: 'password', autocomplete: 'off', spellcheck: 'false', class: 'mono', placeholder: '留空则不校验' });
const portHint = h('span', { class: 'hint', text: '1024–65535。改了端口，客户端里的地址也要一起改。' });
const tokenHint = h('span', { class: 'hint', text: '最多 128 个无空格的英文字母、数字或符号；留空则客户端无需令牌。' });
const eye = iconButton('eye', '显示令牌', () => {
  const show = tokenField.type === 'password';
  tokenField.type = show ? 'text' : 'password';
  eye.replaceChildren(icon(show ? 'eyeOff' : 'eye'));
});
const toggles = SWITCHES.map(([key, title, sub]) => {
  const row = switchItem({ title, sub, onChange: () => validate() });
  row.key = key;
  row.input = row.el.querySelector('input');
  return row;
});
const saveBtn = button('保存', 'filled', save, 'check');
const appliedText = h('p', { class: 'note' });

const moduleSwitch = switchItem({ icon: 'power', title: '模块开关', onChange: setEnabled });
const guardSwitch = switchItem({ icon: 'shield', title: '保持 QQ 在线', onChange: setGuard });
const openRow = item({ icon: 'open', title: '打开 QQ', sub: '服务随 QQ 主进程运行', onClick: openQQ });
const relaunchRow = item({ icon: 'restart', title: '重新启动 QQ', sub: '应用新设置；客户端会短暂断开', onClick: relaunch });
const killRow = item({ icon: 'power', title: '停止保活并关闭 QQ', sub: '之后不会自动拉起，直到重新开启守护', onClick: stopQQ, danger: true });
const diag = dataList(DIAG);
const up = updates('知弦', () => refresh());

/** 表单最近一次从文件载入的值；与它不同即为草稿，草稿期间轮询不覆盖输入。 */
let formBase = null;
const formValue = () => JSON.stringify([portField.value, tokenField.value, toggles.map((t) => t.input.checked)]);

document.body.append(
  topBar('知弦', refreshBtn),
  h('main', {},
    h('div', { class: 'brand' }, h('h2', { text: '知弦' }), h('p', { text: 'QQ 的 Satori 服务' })),
    up.cards,
    h('div', { style: 'margin-top:16px' }, heroCard.el),
    h('div', { class: 'metrics' },
      h('div', { class: 'metric' }, h('span', { text: '客户端' }), clientsValue),
      h('div', { class: 'metric' }, h('span', { text: '连续在线' }), uptimeValue)),
    section('连接链路'), group(...chain),
    section('连接'), group(endpoint, tokenRow),
    section('连接设置'),
    h('div', { class: 'form' },
      h('label', { class: 'field' }, portField, h('span', { text: '本机端口' })), portHint,
      h('label', { class: 'field' }, tokenField, h('span', { text: '连接令牌' }), eye), tokenHint),
    section('运行偏好'), group(...toggles),
    h('div', { class: 'row' }, saveBtn, button('重新启动 QQ', 'tonal', relaunch, 'restart')),
    appliedText,
    section('模块'), group(moduleSwitch),
    section('常驻守护'), group(guardSwitch, openRow, relaunchRow, killRow),
    note('看守 qqguard 在 root 侧运行：QQ 被冻结时解冻、异常退出时按预算拉起。KernelSU 模块列表里知弦的「操作」按钮同样切换它。'),
    section('更新'), up.list,
    note('更新来自 GitHub 发布页，下载后校验 sha256，再由 root 管理器安装。模块更新需要重启手机才会生效。'),
    section('诊断'), diag.el,
    h('div', { class: 'row' }, button('复制报告', 'tonal', () => copy(report(), '诊断报告'), 'copy')),
    note('报告只含版本、状态与错误计数，不含令牌、QQ 账号或消息内容。'),
    h('p', { class: 'note footer', text: '关闭这个页面不影响服务：服务随 QQ 一起工作。' }),
  ),
);

portField.addEventListener('input', validate);
tokenField.addEventListener('input', validate);

function validate() {
  const p = portField.value.trim();
  const portOk = /^\d+$/.test(p) && +p >= 1024 && +p <= 65535;
  const t = tokenField.value;
  const tokenOk = t.length <= 128 && /^[\x21-\x7e]*$/.test(t);
  portField.parentElement.classList.toggle('invalid', !portOk);
  tokenField.parentElement.classList.toggle('invalid', !tokenOk);
  portHint.className = `hint${portOk ? '' : ' error'}`;
  tokenHint.className = `hint${tokenOk ? '' : ' error'}`;
  saveBtn.disabled = !(portOk && tokenOk) || !S.d?.module || (!!S.config && formValue() === formBase);
  return portOk && tokenOk;
}

async function save() {
  if (!validate()) return;
  saveBtn.disabled = true;
  // 只改管理页负责的键，高级选项（host、限速、转发模式…）原样保留。
  const next = { ...(S.config || {}), port: +portField.value.trim(), token: tokenField.value, revision: revision() + 1 };
  for (const t of toggles) next[t.key] = t.input.checked;
  delete next.enabled; // 主开关只认 QQ 数据目录里的开关文件
  const r = await ctl('write-config', toB64(JSON.stringify(next, null, 2)));
  if (r.kv.ok === '1') {
    S.config = next;
    portField.value = String(next.port);
    formBase = formValue();
    snack('已保存。重新启动 QQ 后生效');
  } else {
    snack(r.kv.error || '保存失败');
  }
  refresh();
}

function appliedLine() {
  if (!S.d) return '';
  if (!S.d.module) return '安装模块并重启手机后才能保存设置。';
  if (S.configBad) return '设置文件无效：保存一次即可用上面的设置重写它。';
  if (!S.health) return '设置在 QQ 启动时读取；保存后重新启动 QQ 生效。';
  if (pending()) return '已保存，重新启动 QQ 后生效：QQ 仍在使用旧设置，客户端会短暂断开。';
  return S.config ? '设置已生效：QQ 正在使用最近一次保存的设置。' : '正在使用默认设置；保存一次后由这里管理。';
}

function render() {
  heroCard.set(heroLine());
  const hz = S.health;
  const ready = state() === 'ready';
  setText(clientsValue, hz ? String(hz.connections || 0) : '—');
  setText(uptimeValue, ready && hz.online_since_epoch_ms > 0 ? duration(Date.now() - hz.online_since_epoch_ms) : '—');
  const [st, values] = steps();
  chain.forEach((row, i) => row.lead(...STEP[st[i]]).sub(values[i]));

  endpoint.title(`http://127.0.0.1:${port()}`);
  tokenRow.sub(cfg().token ? '已设置，客户端须填写相同令牌' : '未设置，客户端无需令牌');

  const focused = [portField, tokenField].includes(document.activeElement);
  if ((formBase === null || formValue() === formBase) && !focused && S.d) {
    // 没有设置文件时，表单显示服务实际在用的值（或默认值）。
    const c = S.config || {};
    portField.value = String(port());
    tokenField.value = c.token || '';
    toggles.forEach((t) => { t.input.checked = c[t.key] !== false; });
    formBase = formValue();
  }
  validate();
  setText(appliedText, appliedLine());

  const dd = S.d;
  const off = !dd || !dd.module;
  moduleSwitch.checked(dd?.enabled ?? true).disabled(off)
    .sub(!dd ? '读取中' : dd.enabled ? '开启：服务、通知与保活都在运行' : '已关闭：端口、通知与保活都已停下');
  const g = S.guard;
  guardSwitch.checked(g?.mode === 'ARMED').disabled(off || !g || !dd.enabled).sub(guardText());
  openRow.disabled(!dd?.installed);
  relaunchRow.disabled(!dd?.installed || !dd.enabled || S.relaunching);
  killRow.disabled(!g || !dd?.installed);
  diag.set(diagnostics());
}

async function probe() {
  refreshBtn.classList.add('spin');
  const r = await ctl('probe');
  const p = readProbe(r);
  refreshBtn.classList.remove('spin');
  up.sync(p);
  render();
}

const refresh = poll(probe, 4000);
render();
refresh();
