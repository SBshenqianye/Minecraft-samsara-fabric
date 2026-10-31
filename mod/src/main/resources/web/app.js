'use strict';

// 单页控制台：所有文本都用 textContent 写入，绝不使用 innerHTML。
// token 只存 sessionStorage，不进 URL、不写日志。

const state = {
  token: sessionStorage.getItem('samsara-token') || '',
  model: null,
  status: null,
  edits: new Map(), // `${file}:${path}` -> value
  tab: 'config',
  navTargets: [],
  navScrollHandler: null,
  scrollPositions: { config: 0, identity: 0, 'claim-range': 0, output: 0, permissions: 0 },
  navScrollPositions: { config: 0, identity: 0, 'claim-range': 0, output: 0, permissions: 0 },
  claimPreview: null,
};

const connectHooks = [];
const panelIds = ['panel-identity', 'panel-config', 'panel-claim-range', 'panel-output', 'panel-permissions'];
const tabNames = { 'panel-identity': 'identity', 'panel-config': 'config', 'panel-claim-range': 'claim-range', 'panel-output': 'output', 'panel-permissions': 'permissions' };

const $ = (id) => document.getElementById(id);

function setMessage(text, kind) {
  for (const id of ['message', 'claim-range-message']) {
    const el = $(id);
    if (!el) continue;
    el.textContent = text || '';
    el.className = 'message' + (kind ? ' ' + kind : '');
  }
}

function setConnection(kind, text) {
  const el = $('connection-state');
  if (!el) return;
  el.textContent = text;
  el.className = 'conn-state' + (kind ? ' ' + kind : '');
}

function authHeaders(extra) {
  return Object.assign({ Authorization: 'Bearer ' + state.token }, extra || {});
}

function tokenError(text) {
  const el = $('token-error');
  if (el) el.textContent = text || '';
}

function lockApp(reason, clearToken) {
  document.body.classList.add('locked');
  const main = document.querySelector('main.app-shell');
  const skeleton = $('app-skeleton');
  if (main) main.hidden = true;
  if (skeleton) skeleton.hidden = false;
  if (clearToken) {
    state.token = '';
    sessionStorage.removeItem('samsara-token');
  }
  setConnection('bad', '未连接');
  const dialog = $('token-dialog');
  const input = $('token-input');
  if (input) input.value = state.token || '';
  tokenError(reason || '请输入 web.yml 里的 token。');
  if (dialog && !dialog.open) {
    try { dialog.showModal(); } catch (e) { /* 已打开或浏览器不支持，忽略 */ }
  }
  if (input) window.setTimeout(() => input.focus(), 60);
}

function unlockApp() {
  document.body.classList.remove('locked');
  const main = document.querySelector('main.app-shell');
  const skeleton = $('app-skeleton');
  if (main) main.hidden = false;
  if (skeleton) skeleton.hidden = true;
  tokenError('');
  const input = $('token-input');
  if (input) input.value = '';
  const dialog = $('token-dialog');
  if (dialog && dialog.open) dialog.close();
  setConnection('ok', '已连接');
}

async function api(path, options) {
  const response = await fetch(path, Object.assign({}, options, {
    headers: authHeaders(Object.assign({ 'Content-Type': 'application/json' },
        options && options.headers ? options.headers : {})),
  }));
  let body = null;
  try { body = await response.json(); } catch (e) { body = null; }
  if (!response.ok) {
    if (response.status === 401) lockApp('token 无效或已失效，请重新输入。', true);
    const error = new Error((body && body.error) || ('HTTP ' + response.status));
    error.status = response.status;
    error.body = body;
    throw error;
  }
  return body;
}

function authMessage(error, fallback) {
  if (error && error.status === 401) {
    return 'token 无效：请在弹窗里重新填写 web.yml 里的 token。';
  }
  return fallback + (error && error.message ? '：' + error.message : '');
}

async function connect() {
  const input = $('token-input');
  const token = input ? input.value.trim() : '';
  if (!token) {
    tokenError('请先填写 web.yml 里的 token。');
    if (input) input.focus();
    return;
  }
  state.token = token;
  sessionStorage.setItem('samsara-token', token);
  tokenError('');
  setConnection('', '连接中…');
  const button = $('token-connect');
  if (button) button.disabled = true;
  const ok = await loadConfig();
  if (ok) {
    await loadStatus();
    await runConnectHooks();
    unlockApp();
  } else {
    setConnection('bad', '连接失败');
  }
  if (button) button.disabled = false;
}

async function loadConfig() {
  try {
    state.model = await api('/api/config');
    state.edits.clear();
    render();
    renderClaimRange();
    setMessage('已读取 ' + state.model.files.length + ' 个配置文件', 'ok');
    setConnection('ok', '已连接');
    return true;
  } catch (e) {
    setConnection('bad', e.status === 401 ? 'token 无效' : '连接失败');
    setMessage(authMessage(e, '读取配置失败'), 'error');
    if (e.status !== 401) tokenError(authMessage(e, '读取配置失败'));
    return false;
  }
}

async function loadStatus() {
  try {
    state.status = await api('/api/status');
    renderStatus();
  } catch (e) {
    if (e.status !== 401) setMessage(authMessage(e, '读取状态失败'), 'error');
  }
}

function renderStatus() {
  const container = $('status-chips');
  if (!container) return;
  container.replaceChildren();
  const status = state.status;
  if (!status) return;
  const version = $('app-version');
  if (version && status.version) version.textContent = 'v' + status.version;
  const rows = [
    ['模组版本', status.version],
    ['Minecraft', status.minecraft],
    ['配置根', status.configRoot],
    ['世界数据', status.worldDataRoot],
    ['身份数据', status.identityRoot],
  ];
  for (const [label, value] of rows) {
    const chip = document.createElement('div');
    chip.className = 'status-chip';
    const key = document.createElement('span');
    key.textContent = label;
    const val = document.createElement('strong');
    val.textContent = value == null || value === '' ? '—' : String(value);
    val.title = val.textContent;
    chip.append(key, val);
    container.append(chip);
  }
  const warnings = status.warnings || [];
  const warning = $('status-warning');
  if (warning) {
    warning.hidden = warnings.length === 0;
    warning.textContent = warnings.length ? '最近警告：' + warnings.join('；') : '';
  }
}

// ---- 配置值 ----

function valueKey(fileId, path) {
  return fileId + ':' + path;
}

function effectiveValue(entry) {
  if (entry.value === undefined || entry.value === null) return entry.default;
  return entry.value;
}

function currentValue(fileId, entry) {
  const key = valueKey(fileId, entry.path);
  return state.edits.has(key) ? state.edits.get(key) : effectiveValue(entry);
}

function markEdited(fileId, entry, value) {
  state.edits.set(valueKey(fileId, entry.path), value);
  document.dispatchEvent(new CustomEvent('samsara:config-changed', {
    detail: { fileId: fileId, path: entry.path },
  }));
}

function isMissingValue(entry) {
  return entry.value === undefined || entry.value === null;
}

function sameJson(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

// ---- 配置渲染 ----

function buildFileBlock(file, matching, navTargets, plain) {
  const fileEl = document.createElement(plain ? 'div' : 'details');
  fileEl.className = 'file-block' + (plain ? ' plain-file' : '');
  if (!plain) fileEl.open = true;
  fileEl.id = 'cfg-file-' + encodeURIComponent(file.id);

  if (!plain) {
    const summary = document.createElement('summary');
    summary.className = 'file-summary';
    const title = document.createElement('span');
    title.className = 'file-title';
    title.textContent = file.name.replace(/\.yml$/, '');
    const path = document.createElement('span');
    path.className = 'file-path';
    path.textContent = file.path;
    const count = document.createElement('span');
    count.className = 'count-pill file-count';
    count.textContent = String(matching.length);
    summary.append(title, path, count);
    fileEl.append(summary);
  }
  navTargets.push({ id: fileEl.id, el: fileEl });

  const body = document.createElement('div');
  body.className = 'file-body';

  const groups = new Map();
  for (const entry of matching) {
    if (!groups.has(entry.group)) groups.set(entry.group, []);
    groups.get(entry.group).push(entry);
  }

  const navGroupChildren = [];
  for (const [groupName, entries] of groups) {
    const groupEl = document.createElement('details');
    groupEl.className = 'group-block';
    groupEl.open = true;
    groupEl.id = 'cfg-group-' + encodeURIComponent(file.id + '|' + groupName);
    const groupSummary = document.createElement('summary');
    groupSummary.className = 'group-title';
    const groupLabel = document.createElement('span');
    groupLabel.textContent = groupName;
    const groupCount = document.createElement('span');
    groupCount.className = 'count-pill group-count';
    groupCount.textContent = String(entries.length);
    groupSummary.append(groupLabel, groupCount);
    groupEl.append(groupSummary);
    navTargets.push({ id: groupEl.id, el: groupEl });

    const entryNav = [];
    for (const entry of entries) {
      const entryEl = renderEntry(file, entry);
      groupEl.append(entryEl);
      entryNav.push({ label: entry.path, target: entryEl.id });
      navTargets.push({ id: entryEl.id, el: entryEl });
    }
    body.append(groupEl);
    navGroupChildren.push({ label: groupName, count: entries.length, target: groupEl.id, children: entryNav });
  }

  fileEl.append(body);
  return { fileEl, navGroupChildren };
}

function render() {
  const container = $('files');
  if (!container) return;
  container.replaceChildren();
  state.claimPreview = null;
  if (!state.model) return;

  const query = $('search').value.trim().toLowerCase();
  let shown = 0;
  const navItems = [];
  const navTargets = [];

  for (const file of state.model.files) {
    if (file.id === 'claim-range') continue;
    const matching = file.entries.filter((entry) => {
      const haystack = (file.id + ' ' + entry.path + ' ' + entry.description + ' '
          + entry.group).toLowerCase();
      return !query || haystack.includes(query);
    });
    if (matching.length === 0) continue;
    shown += matching.length;

    const { fileEl, navGroupChildren } = buildFileBlock(file, matching, navTargets);
    container.append(fileEl);
    navItems.push({ label: file.name, count: matching.length, target: fileEl.id, children: navGroupChildren });
  }

  $('summary').textContent = shown + ' / ' + totalEntries() + ' 项';
  setTabCount('config', totalEntries());
  if (state.tab === 'config') {
    buildNav('配置导航', navItems, navTargets);
  }
}

function renderClaimRange() {
  const container = $('claim-range-files');
  if (!container) return;
  container.replaceChildren();
  state.claimPreview = null;
  if (!state.model) return;
  const file = state.model.files.find((item) => item.id === 'claim-range');
  if (!file) {
    const empty = document.createElement('div');
    empty.className = 'nav-empty';
    empty.textContent = '没有找到 claim-range.yml 配置';
    container.append(empty);
    setTabCount('claim-range', 0);
    return;
  }
  const navTargets = [];
  const { fileEl, navGroupChildren } = buildFileBlock(file, file.entries, navTargets, true);
  const preview = claimRangePreview(file);
  preview.id = 'claim-range-preview';
  const body = fileEl.querySelector('.file-body');
  if (body) body.append(preview);
  container.append(fileEl);
  setTabCount('claim-range', file.entries.length);
  if (state.tab === 'claim-range') {
    navTargets.push({ id: preview.id, el: preview });
    const entryItems = navGroupChildren.flatMap((group) => group.children || []);
    buildNav('圈地范围', [
      ...entryItems,
      { label: '保护范围预览', target: preview.id },
    ], navTargets);
  }
}

function totalEntries() {
  if (!state.model) return 0;
  return state.model.files
      .filter((file) => file.id !== 'claim-range')
      .reduce((sum, file) => sum + file.entries.length, 0);
}

function renderEntry(file, entry) {
  const row = document.createElement('div');
  row.className = 'entry' + (entry.restart ? ' restart' : '')
      + (entry.type === 'int-list' ? ' entry-wide' : '');
  row.id = 'cfg-entry-' + encodeURIComponent(file.id + '|' + entry.path);

  const main = document.createElement('div');
  const keyLine = document.createElement('div');
  keyLine.className = 'entry-key';
  keyLine.textContent = entry.path;
  if (isMissingValue(entry) && !entry.sensitive) {
    const badge = document.createElement('span');
    badge.className = 'badge default';
    badge.textContent = '默认值';
    badge.title = '文件里没有这一项；保存时会按默认值写入';
    keyLine.append(document.createTextNode(' '));
    keyLine.append(badge);
  }
  if (entry.restart) {
    const badge = document.createElement('span');
    badge.className = 'badge restart';
    badge.textContent = '需重启';
    keyLine.append(document.createTextNode(' '));
    keyLine.append(badge);
  }
  const desc = document.createElement('div');
  desc.className = 'entry-desc';
  desc.textContent = entry.description || '';
  main.append(keyLine, desc);

  const control = document.createElement('div');
  control.className = 'entry-control';
  const input = buildInput(file, entry);
  const error = document.createElement('div');
  error.className = 'entry-error';
  error.dataset.path = entry.path;
  control.append(input, error);

  const actions = document.createElement('div');
  actions.className = 'entry-actions';
  const reset = document.createElement('button');
  reset.type = 'button';
  reset.className = 'ghost';
  reset.textContent = '恢复默认';
  reset.title = '把这个字段恢复为 schema 默认值';
  reset.addEventListener('click', () => {
    markEdited(file.id, entry, entry.default);
    render();
  });
  actions.append(reset);
  if (file.id === 'permissions' && entry.path === 'entries') {
    const jump = document.createElement('button');
    jump.type = 'button';
    jump.className = 'ghost';
    jump.textContent = '打开权限树';
    jump.addEventListener('click', () => setActiveTab('permissions'));
    actions.append(jump);
  }

  row.append(main, control, actions);
  return row;
}

function buildInput(file, entry) {
  const value = currentValue(file.id, entry);
  if (entry.sensitive) {
    const input = document.createElement('input');
    input.type = 'password';
    input.placeholder = entry.configured ? '已配置；留空表示不修改' : '尚未生成';
    input.addEventListener('input', () => {
      if (input.value) markEdited(file.id, entry, input.value);
    });
    return input;
  }
  switch (entry.type) {
    case 'boolean':
      return buildBoolean(file, entry, value);
    case 'int':
    case 'double': {
      const input = document.createElement('input');
      input.type = 'number';
      if (entry.min != null) input.min = String(entry.min);
      if (entry.max != null) input.max = String(entry.max);
      input.step = entry.type === 'int' ? '1' : 'any';
      const fallback = entry.default == null ? '' : String(entry.default);
      input.value = value == null ? fallback : String(value);
      input.placeholder = fallback === '' ? '默认：空' : '默认：' + fallback;
      input.title = '默认值：' + (fallback === '' ? '空' : fallback);
      input.addEventListener('input', () => {
        if (input.value === '') {
          markEdited(file.id, entry, entry.default);
        } else {
          markEdited(file.id, entry, Number(input.value));
        }
      });
      input.addEventListener('change', () => {
        if (input.value === '' && fallback !== '') {
          input.value = fallback;
          markEdited(file.id, entry, entry.default);
        }
      });
      return input;
    }
    case 'enum': {
      const wrap = document.createElement('div');
      wrap.className = 'particle-wrap';
      const select = document.createElement('select');
      for (const choice of entry.choices || []) {
        const option = document.createElement('option');
        option.value = choice;
        option.textContent = choice;
        select.append(option);
      }
      select.value = value == null ? entry.default : value;
      select.addEventListener('change', () => {
        markEdited(file.id, entry, select.value);
        updateParticlePreview(wrap, select.value);
      });
      wrap.append(select);
      if (/Particle$/i.test(entry.path) || /particle/i.test(entry.path)) {
        const theme = entry.path === 'dayParticle' ? 'day'
            : entry.path === 'nightParticle' ? 'night' : 'dark';
        wrap.dataset.particleTheme = theme;
        const canvas = document.createElement('canvas');
        canvas.className = 'particle-canvas';
        canvas.title = '粒子效果预览；点击画布会在鼠标处生成粒子';
        wrap.append(canvas);
        // 刚 append 时还未进入布局，clientWidth 为 0；等一帧再取真实尺寸。
        requestAnimationFrame(() => {
          if (canvas.isConnected) window.SamsaraParticles?.attach(canvas, select.value, { background: theme });
        });
      }
      return wrap;
    }
    case 'int-list': {
      const values = Array.isArray(value) ? value.map(Number) : [];
      return buildIntList(file, entry, values);
    }
    case 'string-list': {
      const values = Array.isArray(value) ? value : [];
      const editor = window.SamsaraStructured.stringList(values,
          (next) => markEdited(file.id, entry, next));
      return bucket(`列表 · ${values.length} 项`, editor, values.length > 6);
    }
    case 'level-map': {
      const map = value && typeof value === 'object' && !Array.isArray(value) ? value : {};
      const keys = Object.keys(map);
      const editor = window.SamsaraStructured.keyValueMap(
          map, ['user', 'op', 'owner', 'config'],
          (next) => markEdited(file.id, entry, next));
      const body = document.createElement('div');
      body.className = 'data-body';
      const hint = document.createElement('div');
      hint.className = 'data-hint';
      hint.textContent = '数据表默认折叠；展开后可逐行增删。命令路径 → 权限等级。';
      body.append(hint, editor);
      return bucket(`${entry.path} · ${keys.length} 条`, body, true);
    }
    default: {
      const input = document.createElement('input');
      input.type = 'text';
      const fallback = entry.default == null ? '' : String(entry.default);
      input.value = value == null ? fallback : String(value);
      input.placeholder = fallback === '' ? '默认：空' : '默认：' + fallback;
      input.title = '默认值：' + (fallback === '' ? '空' : fallback);
      input.addEventListener('input', () => markEdited(file.id, entry, input.value));
      input.addEventListener('change', () => {
        if (input.value === '' && fallback !== '') {
          input.value = fallback;
          markEdited(file.id, entry, entry.default);
        }
      });
      return input;
    }
  }
}

function buildBoolean(file, entry, value) {
  const row = document.createElement('label');
  row.className = 'boolean-row';
  const input = document.createElement('input');
  input.type = 'checkbox';
  input.checked = value === true;
  input.addEventListener('change', () => markEdited(file.id, entry, input.checked));
  const text = document.createElement('span');
  text.textContent = value === true ? '启用' : '关闭';
  input.addEventListener('change', () => { text.textContent = input.checked ? '启用' : '关闭'; });
  row.append(input, text);
  return row;
}

// ---- 圈地保护范围：染料色块编辑 + 实时形状预览 ----

const DYE_PALETTE = [
  { name: '白', hex: '#f9fffe' },
  { name: '橙', hex: '#f9801d' },
  { name: '品红', hex: '#c74ebd' },
  { name: '淡蓝', hex: '#3ab3da' },
  { name: '黄', hex: '#fed83d' },
  { name: '黄绿', hex: '#80c71f' },
  { name: '粉', hex: '#f38baa' },
  { name: '灰', hex: '#474f52' },
  { name: '淡灰', hex: '#9d9d97' },
  { name: '青', hex: '#169c9c' },
  { name: '紫', hex: '#8932b8' },
  { name: '蓝', hex: '#3c44aa' },
  { name: '棕', hex: '#835432' },
  { name: '绿', hex: '#5e7c16' },
  { name: '红', hex: '#b02e26' },
  { name: '黑', hex: '#1d1d21' },
];

/** 8 方向，按角度顺序（北→东北→…→西北）；x 向东，z 向南。 */
const CLAIM_DIRECTIONS = [
  { name: '北', dx: 0, dz: -1 },
  { name: '东北', dx: 1, dz: -1 },
  { name: '东', dx: 1, dz: 0 },
  { name: '东南', dx: 1, dz: 1 },
  { name: '南', dx: 0, dz: 1 },
  { name: '西南', dx: -1, dz: 1 },
  { name: '西', dx: -1, dz: 0 },
  { name: '西北', dx: -1, dz: -1 },
];

const CLAIM_PATTERNS = {
  border: { label: 'border · 8 方向（实心正方形）', dirs: [0, 1, 2, 3, 4, 5, 6, 7] },
  cross: { label: 'cross · 4 斜向（实心正方形）', dirs: [1, 3, 5, 7] },
  straight_cross: { label: 'straight_cross · 4 正向（实心菱形）', dirs: [0, 2, 4, 6] },
  stripe_top: { label: 'stripe_top · 单条纹（线）', dirs: [4] },
  square_top_left: { label: 'square_top_left · 单角（线）', dirs: [3] },
};

function buildIntList(file, entry, values) {
  const wrap = document.createElement('div');
  wrap.className = 'int-list-wrap';

  const tools = document.createElement('div');
  tools.className = 'int-list-tools';
  const hint = document.createElement('span');
  hint.className = 'int-list-hint';
  hint.textContent = '颜色 0（白）→ 15（黑）；数字为单方向延伸区块数';
  const bulk = document.createElement('input');
  bulk.type = 'number';
  bulk.min = String(entry.min == null ? 1 : entry.min);
  bulk.max = String(entry.max == null ? 32 : entry.max);
  bulk.step = '1';
  bulk.value = String(values[0] == null ? (entry.default || [])[0] || 1 : values[0]);
  bulk.title = '把这个值应用到全部 16 种颜色';
  const bulkWrap = document.createElement('label');
  bulkWrap.className = 'int-list-bulk';
  const bulkText = document.createElement('span');
  bulkText.textContent = '统一设为';
  bulkWrap.append(bulkText, bulk);
  const apply = document.createElement('button');
  apply.type = 'button';
  apply.className = 'ghost';
  apply.textContent = '应用到全部';
  tools.append(hint, bulkWrap, apply);

  const grid = document.createElement('div');
  grid.className = 'int-list-grid';
  const defaults = Array.isArray(entry.default) ? entry.default : [];
  const count = defaults.length || values.length || DYE_PALETTE.length;
  const min = entry.min == null ? 1 : entry.min;
  const max = entry.max == null ? 32 : entry.max;
  const inputs = [];
  for (let i = 0; i < count; i += 1) {
    const dye = DYE_PALETTE[i] || { name: '颜色 ' + i, hex: '#94a3b8' };
    const tile = document.createElement('label');
    tile.className = 'int-list-tile';
    tile.title = dye.name + '（颜色 ' + i + '）';
    const swatch = document.createElement('span');
    swatch.className = 'int-list-swatch';
    swatch.style.background = dye.hex;
    const input = document.createElement('input');
    input.type = 'number';
    input.min = String(min);
    input.max = String(max);
    input.step = '1';
    input.value = String(values[i] == null ? (defaults[i] == null ? min : defaults[i]) : values[i]);
    input.setAttribute('aria-label', dye.name + ' 保护距离（区块）');
    input.addEventListener('input', () => {
      const next = inputs.map((el, idx) => {
        const parsed = Math.round(Number(el.value));
        if (!Number.isFinite(parsed)) return Number(defaults[idx] == null ? min : defaults[idx]);
        return Math.max(min, Math.min(max, parsed));
      });
      markEdited(file.id, entry, next);
    });
    tile.append(swatch, input);
    grid.append(tile);
    inputs.push(input);
  }

  apply.addEventListener('click', () => {
    const parsed = Math.round(Number(bulk.value));
    if (!Number.isFinite(parsed)) return;
    const clamped = Math.max(min, Math.min(max, parsed));
    for (const input of inputs) input.value = String(clamped);
    markEdited(file.id, entry, inputs.map(() => clamped));
  });

  wrap.append(tools, grid);
  return wrap;
}

function claimRangePreview(file) {
  const section = document.createElement('div');
  section.className = 'claim-preview';

  const head = document.createElement('div');
  head.className = 'claim-preview-head';
  const titles = document.createElement('div');
  const title = document.createElement('strong');
  title.textContent = '保护范围可视化预览';
  const sub = document.createElement('span');
  sub.className = 'muted small';
  sub.textContent = '按当前染料距离实时计算；中心格 = 旗帜所在区块，每格 = 1 区块。';
  titles.append(title, sub);

  const controls = document.createElement('div');
  controls.className = 'claim-preview-controls';
  const patternSelect = document.createElement('select');
  for (const [key, pattern] of Object.entries(CLAIM_PATTERNS)) {
    const option = document.createElement('option');
    option.value = key;
    option.textContent = pattern.label;
    patternSelect.append(option);
  }
  const colorSelect = document.createElement('select');
  DYE_PALETTE.forEach((dye, index) => {
    const option = document.createElement('option');
    option.value = String(index);
    option.textContent = dye.name + '（' + index + '）';
    colorSelect.append(option);
  });
  controls.append(patternSelect, colorSelect);
  head.append(titles, controls);

  const canvas = document.createElement('canvas');
  canvas.className = 'claim-preview-canvas';
  const summary = document.createElement('div');
  summary.className = 'claim-preview-summary';
  const legend = document.createElement('div');
  legend.className = 'claim-preview-legend';
  legend.textContent = '预览按当前距离缩放，并假设旗帜朝北；实际游戏内会随旗帜朝向旋转。固定模式下所有颜色使用同一距离。';

  section.append(head, canvas, summary, legend);

  const readValue = (path, fallback) => {
    const entry = file.entries.find((item) => item.path === path);
    return entry ? currentValue(file.id, entry) : fallback;
  };

  const redraw = () => {
    const mode = String(readValue('mode', 'dye'));
    const fixedDistance = Number(readValue('fixedDistance', 4));
    const maxDistance = Math.max(1, Math.min(32, Number(readValue('maxDistance', 16)) || 16));
    const table = readValue('colorDistances', []);
    const color = Number(colorSelect.value);
    const rawDistance = mode === 'fixed'
      ? fixedDistance
      : Number(Array.isArray(table) ? table[color] : NaN);
    const distance = Math.max(1, Math.min(maxDistance,
        Number.isFinite(rawDistance) ? Math.round(rawDistance) : color + 1));
    const pattern = CLAIM_PATTERNS[patternSelect.value] || CLAIM_PATTERNS.border;
    const cells = drawClaimRange(canvas, pattern.dirs, distance, maxDistance);
    const dye = DYE_PALETTE[color] || { name: '颜色 ' + color };
    summary.textContent = (mode === 'fixed' ? '固定模式' : '染料模式')
      + ' · ' + pattern.label.split(' · ')[0] + ' · ' + dye.name
      + ' → 距离 ' + distance + ' 区块，保护 ' + cells + ' 区块'
      + (distance >= maxDistance ? '（已到上限）' : '');
  };

  patternSelect.addEventListener('change', redraw);
  colorSelect.addEventListener('change', redraw);
  redraw();
  state.claimPreview = { redraw };
  return section;
}

/** 按与服务端一致的 8 方向射线 + 端点环规则，画出保护形状并返回区块数。 */
function drawClaimRange(canvas, dirs, distance, maxDistance) {
  const size = 560;
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(size * dpr);
  canvas.height = Math.round(size * dpr);
  canvas.style.width = '100%';
  canvas.style.maxWidth = size + 'px';
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, size, size);

  const radius = Math.max(4, Math.min(maxDistance, distance + 2));
  const pad = 24;
  const cell = (size - pad * 2) / (2 * radius + 1);
  const px = (x) => pad + (x + radius) * cell;
  const pz = (z) => pad + (z + radius) * cell;

  ctx.fillStyle = '#0a1220';
  ctx.fillRect(0, 0, size, size);

  const cells = new Set();
  const add = (x, z) => cells.add(x + ',' + z);
  add(0, 0);
  for (const index of dirs) {
    const dir = CLAIM_DIRECTIONS[index];
    for (let step = 1; step <= distance; step += 1) {
      add(dir.dx * step, dir.dz * step);
    }
  }

  // 末端 ≥ 3 个时「化线为面」会填出内部；用端点按角度顺序连成的多边形近似。
  const endpoints = dirs.map((index) => {
    const dir = CLAIM_DIRECTIONS[index];
    return { x: dir.dx * distance, z: dir.dz * distance };
  });
  if (endpoints.length >= 3) {
    for (let i = 0; i < endpoints.length; i += 1) {
      const a = endpoints[i];
      const b = endpoints[(i + 1) % endpoints.length];
      addLineCells(a.x, a.z, b.x, b.z, add);
    }
    for (let x = -radius; x <= radius; x += 1) {
      for (let z = -radius; z <= radius; z += 1) {
        if (pointInPolygon(x, z, endpoints)) add(x, z);
      }
    }
  }

  // 网格线
  const gridStep = cell >= 9 ? 1 : 4;
  ctx.strokeStyle = 'rgba(148, 163, 184, .12)';
  ctx.lineWidth = 1;
  for (let i = -radius; i <= radius + 1; i += gridStep) {
    const x = pad + (i + radius) * cell;
    const z = pad + (i + radius) * cell;
    ctx.beginPath();
    ctx.moveTo(x, pad);
    ctx.lineTo(x, size - pad);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(pad, z);
    ctx.lineTo(size - pad, z);
    ctx.stroke();
  }

  // 保护格
  for (const key of cells) {
    const [x, z] = key.split(',').map(Number);
    const cx = Math.floor(px(x));
    const cz = Math.floor(pz(z));
    const w = Math.ceil(cell) - 1;
    ctx.fillStyle = x === 0 && z === 0
      ? 'rgba(251, 191, 36, .82)'
      : 'rgba(45, 212, 191, .32)';
    ctx.fillRect(cx + 1, cz + 1, w, w);
  }

  // 射线与端点环
  ctx.strokeStyle = 'rgba(45, 212, 191, .55)';
  ctx.lineWidth = 1.5;
  for (const index of dirs) {
    const dir = CLAIM_DIRECTIONS[index];
    ctx.beginPath();
    ctx.moveTo(px(0) + cell / 2, pz(0) + cell / 2);
    ctx.lineTo(px(dir.dx * distance) + cell / 2, pz(dir.dz * distance) + cell / 2);
    ctx.stroke();
  }
  if (endpoints.length >= 3) {
    ctx.strokeStyle = 'rgba(45, 212, 191, .9)';
    ctx.lineWidth = 2;
    ctx.beginPath();
    endpoints.forEach((point, index) => {
      const x = px(point.x) + cell / 2;
      const z = pz(point.z) + cell / 2;
      if (index === 0) ctx.moveTo(x, z);
      else ctx.lineTo(x, z);
    });
    ctx.closePath();
    ctx.stroke();
  }

  // 方位
  ctx.fillStyle = 'rgba(226, 232, 240, .7)';
  ctx.font = '12px system-ui, sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText('北', size / 2, 15);
  ctx.fillText('南', size / 2, size - 6);
  ctx.textAlign = 'left';
  ctx.fillText('西', 6, size / 2);
  ctx.textAlign = 'right';
  ctx.fillText('东', size - 6, size / 2);

  return cells.size;
}

/** Bresenham：把两个端点之间的区块也纳入边界，贴近服务端的“末端环”线段。 */
function addLineCells(x1, z1, x2, z2, add) {
  let x = x1;
  let z = z1;
  const dx = Math.abs(x2 - x1);
  const dz = Math.abs(z2 - z1);
  const sx = x1 < x2 ? 1 : -1;
  const sz = z1 < z2 ? 1 : -1;
  let error = dx - dz;
  for (;;) {
    add(x, z);
    if (x === x2 && z === z2) break;
    const doubled = error * 2;
    if (doubled > -dz) {
      error -= dz;
      x += sx;
    }
    if (doubled < dx) {
      error += dx;
      z += sz;
    }
  }
}

function pointInPolygon(x, z, points) {
  let inside = false;
  for (let i = 0, j = points.length - 1; i < points.length; j = i, i += 1) {
    const xi = points[i].x;
    const zi = points[i].z;
    const xj = points[j].x;
    const zj = points[j].z;
    const intersect = ((zi > z) !== (zj > z))
      && (x < (xj - xi) * (z - zi) / (zj - zi) + xi);
    if (intersect) inside = !inside;
  }
  return inside;
}

function updateParticlePreview(wrap, name) {
  const canvas = wrap.querySelector('canvas');
  if (canvas) {
    window.SamsaraParticles?.attach(canvas, name, {
      background: wrap.dataset.particleTheme || 'dark',
    });
  }
}

function bucket(summaryText, content, collapsed) {
  const details = document.createElement('details');
  details.className = 'data-bucket';
  if (!collapsed) details.open = true;
  const summary = document.createElement('summary');
  const label = document.createElement('span');
  label.textContent = summaryText;
  const hint = document.createElement('span');
  hint.className = 'count-pill';
  hint.textContent = collapsed ? '展开' : '收起';
  summary.append(label, hint);
  details.append(summary, content);
  details.addEventListener('toggle', () => {
    hint.textContent = details.open ? '收起' : '展开';
  });
  return details;
}

async function saveConfig(onlyFile) {
  if (!state.model) {
    setMessage('还没有读取配置', 'error');
    return false;
  }
  setMessage('');
  let saved = 0;
  for (const file of state.model.files) {
    if (onlyFile && file.id !== onlyFile) continue;
    const values = {};
    for (const entry of file.entries) {
      if (entry.sensitive) continue;
      values[entry.path] = currentValue(file.id, entry);
    }
    try {
      const result = await api('/api/config', {
        method: 'PUT',
        body: JSON.stringify({ file: file.id, values }),
      });
      saved++;
      state.model = result.config;
    } catch (e) {
      if (e.status === 400 && e.body && e.body.fields) {
        for (const [path, message] of Object.entries(e.body.fields)) {
          const el = document.querySelector('.entry-error[data-path="' + cssEscape(path) + '"]');
          if (el) el.textContent = message;
        }
        setMessage(file.name + ' 有 ' + Object.keys(e.body.fields).length + ' 个字段未通过校验，未保存', 'error');
        return false;
      }
      setMessage(authMessage(e, '保存失败'), 'error');
      return false;
    }
  }
  if (onlyFile) {
    for (const key of [...state.edits.keys()]) {
      if (key.startsWith(onlyFile + ':')) state.edits.delete(key);
    }
  } else {
    state.edits.clear();
  }
  render();
  renderClaimRange();
  if (onlyFile === 'claim-range') {
    setMessage('圈地范围已保存，已有圈地已按新范围重算', 'ok');
  } else {
    setMessage('已保存并重载 ' + saved + ' 个文件', 'ok');
  }
  await loadStatus();
  return true;
}

async function reloadConfig() {
  try {
    const result = await api('/api/reload', { method: 'POST' });
    state.model = result.config;
    state.edits.clear();
    render();
    renderClaimRange();
    setMessage(result.message || '已重新读取', 'ok');
    await loadStatus();
    return true;
  } catch (e) {
    setMessage(authMessage(e, '重新读取失败'), 'error');
    return false;
  }
}

function cssEscape(text) {
  return String(text).replace(/["\\]/g, '\\$&');
}

// ---- 全局导航 ----

function stickyNavOffset() {
  const nav = $('global-nav');
  const header = document.querySelector('.app-header');
  const headerH = header ? header.offsetHeight : 0;
  if (nav) {
    const top = parseFloat(getComputedStyle(nav).top);
    if (Number.isFinite(top)) return Math.max(headerH, top);
  }
  return headerH + 14;
}

let navAlignTimer = 0;

// 目标可能在内部滚动容器里（权限树、配置的数据桶）。先把这些容器滚到目标位置，
// 否则窗口本身没那么多可滚动距离，目标只会停在 header 附近。
function scrollNestedScrollers(target) {
  let parent = target.parentElement;
  while (parent && parent !== document.body && parent !== document.documentElement) {
    const style = getComputedStyle(parent);
    if (/(auto|scroll)/.test(style.overflowY) && parent.scrollHeight > parent.clientHeight + 1) {
      const pad = parseFloat(style.paddingTop) || 0;
      const delta = target.getBoundingClientRect().top - parent.getBoundingClientRect().top - pad;
      if (Math.abs(delta) > 1) parent.scrollTop += delta;
    }
    parent = parent.parentElement;
  }
}

function scrollToTarget(id) {
  const target = document.getElementById(id);
  if (!target) return;
  scrollNestedScrollers(target);
  const top = target.getBoundingClientRect().top + window.scrollY - stickyNavOffset();
  window.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
  setNavActive(id);
  // 平滑滚动结束后再按导航实际位置校正一次，避免 header 高度或内部滚动留下偏差。
  const token = id + ':' + Date.now();
  navAlignTimer = token;
  window.setTimeout(() => {
    if (navAlignTimer !== token) return;
    const nav = $('global-nav');
    if (!nav || !target.isConnected) return;
    const delta = target.getBoundingClientRect().top - nav.getBoundingClientRect().top;
    if (Math.abs(delta) > 1) window.scrollBy({ top: delta, behavior: 'auto' });
  }, 460);
}

function buildNav(title, items, targets, options) {
  const nav = $('global-nav');
  if (!nav) return;
  nav.replaceChildren();
  const opts = options || {};
  const heading = document.createElement('div');
  heading.className = 'nav-title';
  const headingText = document.createElement('span');
  headingText.textContent = title;
  heading.append(headingText);
  if (opts.count != null) {
    const pill = document.createElement('span');
    pill.className = 'count-pill';
    pill.textContent = String(opts.count);
    heading.append(pill);
  }
  nav.append(heading);
  if (!items || items.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'nav-empty';
    empty.textContent = '暂无可导航内容';
    nav.append(empty);
    watchNav([]);
    return;
  }
  const list = document.createElement('div');
  list.className = 'nav-group';
  for (const item of items) appendNavItem(list, item);
  nav.append(list);
  watchNav(targets || []);
  const first = items[0];
  if (first) setNavActive(first.target);
}

function appendNavItem(parent, item) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'nav-item';
  button.dataset.target = item.target || '';
  const label = document.createElement('span');
  label.textContent = item.label;
  button.append(label);
  if (item.count != null) {
    const count = document.createElement('span');
    count.className = 'nav-count';
    count.textContent = String(item.count);
    button.append(count);
  }
  button.addEventListener('click', () => {
    if (typeof item.onClick === 'function') {
      item.onClick();
      if (item.target) setNavActive(item.target);
    } else if (item.target) {
      scrollToTarget(item.target);
    }
  });
  parent.append(button);
  if (item.children && item.children.length) {
    const children = document.createElement('div');
    children.className = 'nav-children';
    for (const child of item.children) appendNavItem(children, child);
    parent.append(children);
  }
}

function setNavActive(id) {
  for (const item of document.querySelectorAll('#global-nav .nav-item')) {
    item.classList.toggle('active', item.dataset.target === id);
  }
}

function watchNav(targets) {
  state.navTargets = targets || [];
  if (state.navScrollHandler) {
    window.removeEventListener('scroll', state.navScrollHandler);
    state.navScrollHandler = null;
  }
  if (!targets.length) return;
  const threshold = stickyNavOffset() + 12;
  let ticking = false;
  state.navScrollHandler = () => {
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(() => {
      ticking = false;
      let current = targets[0];
      for (const target of targets) {
        if (target.el && target.el.getBoundingClientRect().top <= threshold) current = target;
      }
      if (current) setNavActive(current.id);
    });
  };
  window.addEventListener('scroll', state.navScrollHandler, { passive: true });
}

// ---- 标签页 ----

function setActiveTab(name) {
  if (!panelIds.includes('panel-' + name)) name = 'config';
  const previous = state.tab;
  if (previous) {
    state.scrollPositions[previous] = window.scrollY;
    const nav = $('global-nav');
    state.navScrollPositions[previous] = nav ? nav.scrollTop : 0;
  }
  state.tab = name;
  for (const panel of panelIds) {
    const el = document.getElementById(panel);
    if (el) el.hidden = panel !== 'panel-' + name;
  }
  for (const tab of document.querySelectorAll('.tab')) {
    tab.classList.toggle('active', tab.dataset.panel === 'panel-' + name);
  }
  try { history.replaceState(null, '', '#' + name); } catch (e) { /* file:// 等场景忽略 */ }
  updateTabActions(name);
  if (name === 'config') {
    render();
  } else if (name === 'claim-range') {
    renderClaimRange();
  } else if (name === 'identity') {
    window.SamsaraIdentity?.activate();
  } else if (name === 'output') {
    window.SamsaraOutput?.activate();
  } else if (name === 'permissions') {
    window.SamsaraPermissions?.activate();
  }
  const restore = () => {
    window.scrollTo(0, state.scrollPositions[name] || 0);
    const nav = $('global-nav');
    if (nav) nav.scrollTop = state.navScrollPositions[name] || 0;
  };
  requestAnimationFrame(() => {
    restore();
    requestAnimationFrame(restore);
  });
}

const TAB_ACTIONS = {
  config: {
    title: '配置',
    hint: '保存会写入全部配置并热重载',
    save: '保存并重载',
  },
  'claim-range': {
    title: '圈地范围',
    hint: '保存会写入 claim-range.yml，并立即重算已有圈地',
    save: '保存并重算',
  },
  identity: {
    title: '身份组',
    hint: '保存会写入 identity.yml 并热重载',
    save: '保存并重载',
  },
  output: {
    title: '输出格式',
    hint: '保存当前消息模板并热重载',
    save: '保存并重载',
  },
  permissions: {
    title: '权限',
    hint: '节点修改即时保存；owner 名单用保存按钮',
    save: '保存 owner 名单',
  },
};

function updateTabActions(name) {
  const config = TAB_ACTIONS[name] || TAB_ACTIONS.config;
  const title = $('action-title');
  const hint = $('action-hint');
  const save = $('save');
  const def = $('action-default');
  if (title) title.textContent = config.title;
  if (hint) hint.textContent = config.hint;
  if (save) save.textContent = config.save;
  if (def) def.hidden = name !== 'output';
}

function setTabCount(panel, count) {
  const map = { config: 'config-count', identity: 'identity-count', 'claim-range': 'claim-range-count', output: 'output-count', permissions: 'perm-count' };
  const el = $(map[panel]);
  if (el) el.textContent = String(count == null ? 0 : count);
}

function refreshCounts() {
  if (state.model) {
    setTabCount('config', totalEntries());
    const range = state.model.files.find((file) => file.id === 'claim-range');
    setTabCount('claim-range', range ? range.entries.length : 0);
  }
  window.SamsaraIdentity?.refreshCount?.();
  window.SamsaraOutput?.refreshCount?.();
  window.SamsaraPermissions?.refreshCount?.();
}

async function saveActive() {
  if (state.tab === 'identity') return window.SamsaraIdentity?.save?.();
  if (state.tab === 'output') return window.SamsaraOutput?.save?.();
  if (state.tab === 'permissions') return window.SamsaraPermissions?.saveOwners?.();
  if (state.tab === 'claim-range') return saveConfig('claim-range');
  return saveConfig();
}

async function reloadActive() {
  if (state.tab === 'identity') return window.SamsaraIdentity?.reload?.();
  if (state.tab === 'output') return window.SamsaraOutput?.reload?.();
  if (state.tab === 'permissions') return window.SamsaraPermissions?.reload?.();
  return reloadConfig();
}

// ---- 事件 ----

$('token-connect').addEventListener('click', connect);
$('token-form').addEventListener('submit', (event) => {
  event.preventDefault();
  connect();
});
$('token-dialog').addEventListener('cancel', (event) => event.preventDefault());
$('save').addEventListener('click', saveActive);
$('reload').addEventListener('click', reloadActive);
$('action-default').addEventListener('click', () => window.SamsaraOutput?.restoreDefault?.());
$('search').addEventListener('input', render);

// 染料距离、模式、上限等任一输入变化后，保护形状预览立即重算。
document.addEventListener('samsara:config-changed', () => {
  state.claimPreview?.redraw?.();
});

function updateHeaderHeight() {
  const header = document.querySelector('.app-header');
  if (!header) return;
  document.documentElement.style.setProperty('--header-h', header.offsetHeight + 'px');
}

updateHeaderHeight();
window.addEventListener('resize', updateHeaderHeight);
if ('ResizeObserver' in window) {
  new ResizeObserver(updateHeaderHeight).observe(document.querySelector('.app-header'));
}

const backToTop = $('back-to-top');
if (backToTop) {
  backToTop.addEventListener('click', () => window.scrollTo({ top: 0, behavior: 'smooth' }));
  window.addEventListener('scroll', () => {
    backToTop.hidden = window.scrollY < 360;
  }, { passive: true });
}

for (const tab of document.querySelectorAll('.tab')) {
  tab.addEventListener('click', () => setActiveTab(tabNames[tab.dataset.panel] || 'config'));
}

function onConnect(fn) {
  connectHooks.push(fn);
}

async function runConnectHooks() {
  for (const fn of connectHooks) {
    try {
      await fn();
    } catch (e) {
      // 各面板自己显示错误；这里只避免连接流程中断
    }
  }
  refreshCounts();
}

window.SamsaraUI = {
  state,
  api,
  setMessage,
  onConnect,
  runConnectHooks,
  getToken: () => state.token,
  setConnection,
  setActiveTab,
  isActiveTab: (name) => state.tab === name,
  buildNav,
  setNavActive,
  watchNav,
  refreshCounts,
  setTabCount,
  effectiveValue,
  authMessage,
};

const initialTab = (location.hash || '').replace('#', '');
if (['config', 'claim-range', 'identity', 'output', 'permissions'].includes(initialTab)) {
  setActiveTab(initialTab);
} else {
  updateTabActions('config');
}

async function restoreSession() {
  setConnection('', '连接中…');
  const ok = await loadConfig();
  if (!ok) {
    lockApp('token 无效或服务器未启动，请重新输入。', true);
    return;
  }
  await loadStatus();
  await runConnectHooks();
  unlockApp();
  if (state.tab !== 'config') setActiveTab(state.tab);
}

if (state.token) {
  restoreSession();
} else {
  lockApp('请输入 web.yml 里的 token（服务器启动日志也会打印）。', true);
}
