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
  const patternEntries = file.entries.filter((entry) => entry.path.startsWith('patternDistances.'));
  const mainEntries = file.entries.filter((entry) => !entry.path.startsWith('patternDistances.'));
  const { fileEl, navGroupChildren } = buildFileBlock(file, mainEntries, navTargets, true);
  const body = fileEl.querySelector('.file-body') || fileEl;
  const patternEditor = patternDistancesEditor(file, patternEntries);
  body.append(patternEditor);
  navTargets.push({ id: patternEditor.id, el: patternEditor });
  const preview = claimRangePreview(file);
  preview.id = 'claim-range-preview';
  body.append(preview);
  container.append(fileEl);
  setTabCount('claim-range', file.entries.length);
  if (state.tab === 'claim-range') {
    navTargets.push({ id: preview.id, el: preview });
    const entryItems = navGroupChildren.flatMap((group) => group.children || []);
    buildNav('圈地范围', [
      ...entryItems,
      { label: '图案距离', target: patternEditor.id },
      { label: '保护范围调试', target: preview.id },
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
  border: { label: '全边框 · 8 方向', dirs: [0, 1, 2, 3, 4, 5, 6, 7] },
  cross: { label: '交叉 · 4 斜向', dirs: [1, 3, 5, 7] },
  straight_cross: { label: '正向十字 · 4 正向', dirs: [0, 2, 4, 6] },
  stripe_top: { label: '顶部条纹', dirs: [4] },
  stripe_bottom: { label: '底部条纹', dirs: [0] },
  stripe_left: { label: '左侧条纹', dirs: [2] },
  stripe_right: { label: '右侧条纹', dirs: [6] },
  square_top_left: { label: '左上角', dirs: [3] },
  square_top_right: { label: '右上角', dirs: [5] },
  square_bottom_left: { label: '左下角', dirs: [1] },
  square_bottom_right: { label: '右下角', dirs: [7] },
  stripe_downleft: { label: '左上斜纹', dirs: [1, 5] },
  stripe_downright: { label: '右上斜纹', dirs: [3, 7] },
  stripe_center: { label: '居中竖纹', dirs: [0, 4] },
  stripe_middle: { label: '居中横纹', dirs: [2, 6] },
};

const CLAIM_FACINGS = [
  { id: 'north', label: '北', offset: 0 },
  { id: 'east', label: '东', offset: 2 },
  { id: 'south', label: '南', offset: 4 },
  { id: 'west', label: '西', offset: 6 },
];

function patternDistancesEditor(file, entries) {
  const wrap = document.createElement('div');
  wrap.className = 'pattern-distances';
  wrap.id = 'claim-pattern-distances';
  const head = document.createElement('div');
  head.className = 'pattern-distances-head';
  const title = document.createElement('strong');
  title.textContent = '图案距离';
  const hint = document.createElement('span');
  hint.className = 'muted small';
  hint.textContent = '0 = 沿用染料/固定距离；大于 0 时该图案固定使用这个距离。';
  const reset = document.createElement('button');
  reset.type = 'button';
  reset.className = 'ghost';
  reset.textContent = '全部清零';
  head.append(title, hint, reset);

  const grid = document.createElement('div');
  grid.className = 'pattern-distance-grid';
  const inputs = [];
  for (const entry of entries) {
    const id = entry.path.split('.').pop();
    const row = document.createElement('label');
    row.className = 'pattern-distance-row';
    const label = document.createElement('span');
    label.className = 'pattern-distance-name';
    label.textContent = CLAIM_PATTERNS[id]?.label || id;
    const code = document.createElement('code');
    code.textContent = id;
    const input = document.createElement('input');
    input.type = 'number';
    input.min = '0';
    input.max = String(entry.max == null ? 32 : entry.max);
    input.step = '1';
    input.value = String(Number(currentValue(file.id, entry)) || 0);
    input.addEventListener('input', () => {
      const value = Math.max(0, Math.min(entry.max == null ? 32 : entry.max,
          Math.round(Number(input.value) || 0)));
      input.value = String(value);
      row.classList.toggle('override', value > 0);
      markEdited(file.id, entry, value);
    });
    row.classList.toggle('override', Number(input.value) > 0);
    row.append(label, code, input);
    grid.append(row);
    inputs.push(input);
  }
  reset.addEventListener('click', () => {
    entries.forEach((entry, index) => {
      inputs[index].value = '0';
      inputs[index].closest('.pattern-distance-row')?.classList.remove('override');
      markEdited(file.id, entry, 0);
    });
  });

  wrap.append(head, grid);
  return wrap;
}


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
  title.textContent = '保护范围调试画布';
  const sub = document.createElement('span');
  sub.className = 'muted small';
  sub.textContent = '点击网格放置旗帜，拖动移动；右键或“旋转”改变朝向。边界按游戏内风格用粒子点显示。';
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
  const facingSelect = document.createElement('select');
  for (const facing of CLAIM_FACINGS) {
    const option = document.createElement('option');
    option.value = facing.id;
    option.textContent = '朝' + facing.label;
    facingSelect.append(option);
  }
  controls.append(patternSelect, colorSelect, facingSelect);
  head.append(titles, controls);

  const toolbar = document.createElement('div');
  toolbar.className = 'claim-sim-toolbar';
  const chip = document.createElement('span');
  chip.className = 'claim-banner-chip';
  chip.draggable = true;
  chip.textContent = '拖到网格放置旗帜';
  const spacer = document.createElement('span');
  spacer.className = 'spacer';
  const zoomWrap = document.createElement('label');
  zoomWrap.className = 'claim-zoom';
  const zoomText = document.createElement('span');
  zoomText.textContent = '视野';
  const zoomSelect = document.createElement('select');
  for (const [value, label] of [['0', '自动'], ['7', '13 格'], ['10', '21 格'], ['14', '29 格'], ['20', '41 格']]) {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = label;
    zoomSelect.append(option);
  }
  zoomWrap.append(zoomText, zoomSelect);
  const rotateBtn = document.createElement('button');
  rotateBtn.type = 'button';
  rotateBtn.className = 'ghost';
  rotateBtn.textContent = '旋转';
  const deleteBtn = document.createElement('button');
  deleteBtn.type = 'button';
  deleteBtn.className = 'ghost';
  deleteBtn.textContent = '删除选中';
  const clearBtn = document.createElement('button');
  clearBtn.type = 'button';
  clearBtn.className = 'ghost danger';
  clearBtn.textContent = '清空';
  toolbar.append(chip, spacer, zoomWrap, rotateBtn, deleteBtn, clearBtn);

  const canvas = document.createElement('canvas');
  canvas.className = 'claim-preview-canvas claim-sim-canvas';
  canvas.tabIndex = 0;
  canvas.title = '点击放置旗帜；拖动移动；右键旋转；Delete 删除';
  const summary = document.createElement('div');
  summary.className = 'claim-preview-summary';
  const legend = document.createElement('div');
  legend.className = 'claim-preview-legend';
  legend.textContent = '绿色粒子 = 保护边界；旗帜颜色 = 染料距离或图案覆盖距离。固定模式下所有颜色使用同一距离。';

  section.append(head, toolbar, canvas, summary, legend);

  const readValue = (path, fallback) => {
    const entry = file.entries.find((item) => item.path === path);
    return entry ? currentValue(file.id, entry) : fallback;
  };
  const patternIds = Object.keys(CLAIM_PATTERNS);
  const readConfig = () => ({
    mode: String(readValue('mode', 'dye')),
    fixedDistance: Number(readValue('fixedDistance', 4)) || 4,
    maxDistance: Math.max(1, Math.min(32, Number(readValue('maxDistance', 16)) || 16)),
    colorDistances: (() => {
      const value = readValue('colorDistances', []);
      return Array.isArray(value) ? value.map(Number) : [];
    })(),
    patternDistances: Object.fromEntries(patternIds.map((id) => [id,
      Number(readValue('patternDistances.' + id, 0)) || 0])),
  });

  const state = {
    banners: [{ x: 0, z: 0, pattern: patternSelect.value, color: Number(colorSelect.value), facing: facingSelect.value }],
    selected: 0,
    hover: null,
    drag: null,
    lastSummary: 0,
    zoom: 0,
  };

  const size = 560;
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(size * dpr);
  canvas.height = Math.round(size * dpr);
  canvas.style.width = '100%';
  canvas.style.maxWidth = size + 'px';
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;

  const distanceOf = (banner, cfg) => {
    const override = cfg.patternDistances[banner.pattern] || 0;
    const color = Math.max(0, Math.min(15, Number(banner.color) || 0));
    const base = override > 0
        ? override
        : (cfg.mode === 'fixed' ? cfg.fixedDistance : (cfg.colorDistances[color] ?? color + 1));
    return Math.max(1, Math.min(cfg.maxDistance, Math.round(base)));
  };

  const rotatedDirs = (banner) => {
    const dirs = CLAIM_PATTERNS[banner.pattern]?.dirs || [];
    const offset = CLAIM_FACINGS.find((item) => item.id === banner.facing)?.offset || 0;
    return dirs.map((index) => (index + offset) % 8);
  };

  const viewRadius = (cfg) => {
    if (state.zoom > 0) return state.zoom;
    let max = 1;
    for (const banner of state.banners) max = Math.max(max, distanceOf(banner, cfg));
    return Math.max(6, Math.min(28, max + 4));
  };

  const cellsFor = (banner, cfg) => {
    const dirs = rotatedDirs(banner);
    const distance = distanceOf(banner, cfg);
    const cells = new Set();
    const add = (x, z) => cells.add(x + ',' + z);
    add(banner.x, banner.z);
    const endpoints = [];
    for (const index of dirs) {
      const dir = CLAIM_DIRECTIONS[index];
      for (let step = 1; step <= distance; step += 1) {
        add(banner.x + dir.dx * step, banner.z + dir.dz * step);
      }
      endpoints.push({ x: banner.x + dir.dx * distance, z: banner.z + dir.dz * distance });
    }
    if (endpoints.length >= 3) {
      for (let i = 0; i < endpoints.length; i += 1) {
        const a = endpoints[i];
        const b = endpoints[(i + 1) % endpoints.length];
        addLineCells(a.x, a.z, b.x, b.z, add);
      }
      const minX = Math.min(...endpoints.map((p) => p.x));
      const maxX = Math.max(...endpoints.map((p) => p.x));
      const minZ = Math.min(...endpoints.map((p) => p.z));
      const maxZ = Math.max(...endpoints.map((p) => p.z));
      for (let x = minX; x <= maxX; x += 1) {
        for (let z = minZ; z <= maxZ; z += 1) {
          if (pointInPolygon(x, z, endpoints)) add(x, z);
        }
      }
    }
    return cells;
  };

  const boundaryOf = (cells) => {
    const out = [];
    for (const key of cells) {
      const [x, z] = key.split(',').map(Number);
      if (!cells.has((x + 1) + ',' + z) || !cells.has((x - 1) + ',' + z)
          || !cells.has(x + ',' + (z + 1)) || !cells.has(x + ',' + (z - 1))) {
        out.push([x, z]);
      }
    }
    return out;
  };

  const cellAt = (event) => {
    const rect = canvas.getBoundingClientRect();
    const cfg = readConfig();
    const radius = viewRadius(cfg);
    const pad = 26;
    const cell = (size - pad * 2) / (2 * radius + 1);
    const x = (event.clientX - rect.left) * (size / rect.width);
    const y = (event.clientY - rect.top) * (size / rect.height);
    const gx = Math.floor((x - pad) / cell) - radius;
    const gz = Math.floor((y - pad) / cell) - radius;
    if (gx < -radius || gx > radius || gz < -radius || gz > radius) return null;
    return { x: gx, z: gz };
  };

  const bannerAt = (cell) => state.banners.findIndex((item) => item.x === cell.x && item.z === cell.z);
  const selectedBanner = () => state.banners[state.selected] || null;

  const rotateBanner = (banner) => {
    if (!banner) return;
    const index = Math.max(0, CLAIM_FACINGS.findIndex((item) => item.id === banner.facing));
    banner.facing = CLAIM_FACINGS[(index + 1) % CLAIM_FACINGS.length].id;
    facingSelect.value = banner.facing;
  };

  const updateSummary = () => {
    const cfg = readConfig();
    const union = new Set();
    for (const banner of state.banners) {
      for (const key of cellsFor(banner, cfg)) union.add(key);
    }
    const boundary = boundaryOf(union);
    const banner = selectedBanner();
    let text = '已放置 ' + state.banners.length + ' 面 · 保护 ' + union.size
        + ' 区块 · 边界 ' + boundary.length + ' 格';
    if (banner) {
      const dye = DYE_PALETTE[banner.color] || { name: '颜色 ' + banner.color };
      const override = cfg.patternDistances[banner.pattern] || 0;
      text += ' · 选中：' + (CLAIM_PATTERNS[banner.pattern]?.label || banner.pattern)
          + ' · ' + dye.name + ' · 朝' + (CLAIM_FACINGS.find((f) => f.id === banner.facing)?.label || '北')
          + ' → ' + distanceOf(banner, cfg) + ' 区块'
          + (override > 0 ? '（图案覆盖）' : '');
    } else {
      text += ' · 点击网格放置第一面旗帜';
    }
    summary.textContent = text;
  };

  const draw = (now) => {
    if (!canvas.isConnected) return;
    const cfg = readConfig();
    const radius = viewRadius(cfg);
    const pad = 26;
    const cell = (size - pad * 2) / (2 * radius + 1);
    const px = (x) => pad + (x + radius) * cell;
    const pz = (z) => pad + (z + radius) * cell;

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, size, size);
    ctx.fillStyle = '#0a1220';
    ctx.fillRect(0, 0, size, size);

    ctx.strokeStyle = 'rgba(148, 163, 184, .10)';
    ctx.lineWidth = 1;
    const step = cell >= 10 ? 1 : 2;
    for (let i = -radius; i <= radius + 1; i += step) {
      ctx.beginPath();
      ctx.moveTo(px(i), pad);
      ctx.lineTo(px(i), size - pad);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(pad, pz(i));
      ctx.lineTo(size - pad, pz(i));
      ctx.stroke();
    }

    const union = new Set();
    for (const banner of state.banners) {
      for (const key of cellsFor(banner, cfg)) union.add(key);
    }

    ctx.fillStyle = 'rgba(45, 212, 191, .07)';
    for (const key of union) {
      const [x, z] = key.split(',').map(Number);
      ctx.fillRect(px(x), pz(z), cell, cell);
    }

    const time = now / 1000;
    const boundary = boundaryOf(union);
    for (const [x, z] of boundary) {
      const seed = ((x * 73856093) ^ (z * 19349663)) >>> 0;
      const baseX = px(x);
      const baseZ = pz(z);
      const edges = [];
      if (!union.has((x + 1) + ',' + z)) edges.push([1, 0]);
      if (!union.has((x - 1) + ',' + z)) edges.push([-1, 0]);
      if (!union.has(x + ',' + (z + 1))) edges.push([0, 1]);
      if (!union.has(x + ',' + (z - 1))) edges.push([0, -1]);
      edges.forEach(([ex, ez], edgeIndex) => {
        const count = 3;
        for (let i = 0; i < count; i += 1) {
          const wobble = ((seed >>> ((edgeIndex * 3 + i) % 16)) % 7) / 7;
          const along = (i + .5) / count + (wobble - .5) * .25;
          let dx = 0;
          let dz = 0;
          if (ex !== 0) {
            dx = ex > 0 ? cell + 1 : -1;
            dz = along * cell;
          } else {
            dx = along * cell;
            dz = ez > 0 ? cell + 1 : -1;
          }
          const alpha = .35 + .45 * (0.5 + 0.5 * Math.sin(time * 3.2 + (seed % 100) / 12 + i));
          const dot = Math.max(1, Math.round(cell * .16));
          ctx.globalAlpha = alpha;
          ctx.fillStyle = i === 1 ? '#a7f3d0' : (i === 2 ? '#5eead4' : '#2dd4bf');
          ctx.fillRect(Math.round(baseX + dx), Math.round(baseZ + dz), dot, dot);
        }
      });
    }
    ctx.globalAlpha = 1;

    state.banners.forEach((banner, index) => {
      const cx = px(banner.x) + cell / 2;
      const cz = pz(banner.z) + cell / 2;
      const dye = DYE_PALETTE[banner.color] || { hex: '#2dd4bf' };
      if (index === state.selected) {
        ctx.strokeStyle = 'rgba(251, 191, 36, .95)';
        ctx.lineWidth = 2;
        ctx.strokeRect(px(banner.x) + 1.5, pz(banner.z) + 1.5, cell - 3, cell - 3);
      }
      ctx.fillStyle = '#e2e8f0';
      ctx.fillRect(Math.round(cx) - 1, Math.round(cz - cell * .32), 2, Math.round(cell * .64));
      ctx.fillStyle = dye.hex;
      ctx.beginPath();
      ctx.moveTo(Math.round(cx) + 1, Math.round(cz - cell * .32));
      ctx.lineTo(Math.round(cx + cell * .34), Math.round(cz - cell * .12));
      ctx.lineTo(Math.round(cx) + 1, Math.round(cz + cell * .04));
      ctx.closePath();
      ctx.fill();
      const facing = CLAIM_DIRECTIONS[(CLAIM_FACINGS.find((f) => f.id === banner.facing)?.offset || 0)];
      ctx.strokeStyle = 'rgba(226, 232, 240, .85)';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.moveTo(cx, cz + cell * .28);
      ctx.lineTo(cx + facing.dx * cell * .55, cz + cell * .28 + facing.dz * cell * .55);
      ctx.stroke();
    });

    const ghost = state.drag ? state.drag.cell : state.hover;
    if (ghost && (state.drag || bannerAt(ghost) < 0)) {
      const ghostBanner = state.drag
        ? Object.assign({}, state.banners[state.drag.index], { x: ghost.x, z: ghost.z })
        : { x: ghost.x, z: ghost.z, pattern: patternSelect.value, color: Number(colorSelect.value), facing: facingSelect.value };
      const ghostCells = cellsFor(ghostBanner, cfg);
      ctx.fillStyle = 'rgba(251, 191, 36, .08)';
      for (const key of ghostCells) {
        const [gx, gz] = key.split(',').map(Number);
        ctx.fillRect(px(gx), pz(gz), cell, cell);
      }
      const ghostBoundary = boundaryOf(ghostCells);
      ctx.fillStyle = 'rgba(251, 191, 36, .9)';
      for (const [gx, gz] of ghostBoundary) {
        const dot = Math.max(1, Math.round(cell * .16));
        ctx.fillRect(Math.round(px(gx) + cell * .5), Math.round(pz(gz) + cell * .5), dot, dot);
      }

      const gx = px(ghost.x);
      const gz = pz(ghost.z);
      ctx.strokeStyle = 'rgba(94, 234, 212, .85)';
      ctx.setLineDash([4, 3]);
      ctx.lineWidth = 1.5;
      ctx.strokeRect(gx + 1, gz + 1, cell - 2, cell - 2);
      ctx.setLineDash([]);
      ctx.globalAlpha = .45;
      ctx.fillStyle = (DYE_PALETTE[Number(colorSelect.value)] || { hex: '#2dd4bf' }).hex;
      ctx.fillRect(gx + cell * .3, gz + cell * .3, cell * .4, cell * .4);
      ctx.globalAlpha = 1;
    }

    ctx.fillStyle = 'rgba(226, 232, 240, .55)';
    ctx.font = '12px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('北', size / 2, 16);
    ctx.textAlign = 'left';
    ctx.fillText('西', 6, size / 2);
    ctx.textAlign = 'right';
    ctx.fillText('东', size - 6, size / 2);
    ctx.textAlign = 'center';
    ctx.fillText('南', size / 2, size - 6);

    if (now - state.lastSummary > 220) {
      state.lastSummary = now;
      updateSummary();
    }
    state.raf = requestAnimationFrame(draw);
  };

  patternSelect.addEventListener('change', () => {
    const banner = selectedBanner();
    if (banner) banner.pattern = patternSelect.value;
  });
  colorSelect.addEventListener('change', () => {
    const banner = selectedBanner();
    if (banner) banner.color = Number(colorSelect.value);
  });
  facingSelect.addEventListener('change', () => {
    const banner = selectedBanner();
    if (banner) banner.facing = facingSelect.value;
  });

  zoomSelect.addEventListener('change', () => {
    state.zoom = Number(zoomSelect.value) || 0;
  });
  rotateBtn.addEventListener('click', () => rotateBanner(selectedBanner()));
  deleteBtn.addEventListener('click', () => {
    if (state.selected < 0 || !state.banners[state.selected]) return;
    state.banners.splice(state.selected, 1);
    state.selected = state.banners.length ? Math.min(state.selected, state.banners.length - 1) : -1;
  });
  clearBtn.addEventListener('click', () => {
    state.banners = [];
    state.selected = -1;
  });

  chip.addEventListener('dragstart', (event) => {
    try { event.dataTransfer.setData('text/plain', 'samsara-banner'); } catch (e) { /* ignore */ }
    event.dataTransfer.effectAllowed = 'copy';
  });
  canvas.addEventListener('dragover', (event) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
    state.hover = cellAt(event);
  });
  canvas.addEventListener('drop', (event) => {
    event.preventDefault();
    const cell = cellAt(event);
    if (!cell) return;
    state.banners.push({
      x: cell.x,
      z: cell.z,
      pattern: patternSelect.value,
      color: Number(colorSelect.value),
      facing: facingSelect.value,
    });
    state.selected = state.banners.length - 1;
  });

  canvas.addEventListener('pointermove', (event) => {
    const cell = cellAt(event);
    state.hover = cell;
    if (state.drag && cell) state.drag.cell = cell;
    canvas.style.cursor = cell ? (state.drag ? 'grabbing' : 'crosshair') : 'default';
  });
  canvas.addEventListener('pointerleave', () => {
    state.hover = null;
  });
  canvas.addEventListener('pointerdown', (event) => {
    if (event.button === 2) return;
    const cell = cellAt(event);
    if (!cell) return;
    const found = bannerAt(cell);
    if (found >= 0) {
      state.selected = found;
      state.drag = { index: found, cell: { x: cell.x, z: cell.z } };
    } else {
      state.banners.push({
        x: cell.x,
        z: cell.z,
        pattern: patternSelect.value,
        color: Number(colorSelect.value),
        facing: facingSelect.value,
      });
      state.selected = state.banners.length - 1;
      state.drag = { index: state.selected, cell: { x: cell.x, z: cell.z } };
    }
    canvas.setPointerCapture(event.pointerId);
    canvas.focus();
  });
  canvas.addEventListener('pointerup', (event) => {
    if (state.drag) {
      const cell = cellAt(event) || state.drag.cell;
      const banner = state.banners[state.drag.index];
      if (banner) {
        banner.x = cell.x;
        banner.z = cell.z;
      }
      state.drag = null;
    }
    state.hover = null;
  });
  canvas.addEventListener('contextmenu', (event) => {
    event.preventDefault();
    const cell = cellAt(event);
    if (!cell) return;
    const found = bannerAt(cell);
    if (found >= 0) {
      state.selected = found;
      rotateBanner(state.banners[found]);
    }
  });
  canvas.addEventListener('keydown', (event) => {
    if (event.key === 'Delete' || event.key === 'Backspace') {
      if (state.selected >= 0) {
        state.banners.splice(state.selected, 1);
        state.selected = state.banners.length ? Math.min(state.selected, state.banners.length - 1) : -1;
        event.preventDefault();
      }
    } else if (event.key === 'r' || event.key === 'R') {
      rotateBanner(selectedBanner());
      event.preventDefault();
    }
  });

  const syncSelectors = () => {
    const banner = selectedBanner();
    if (banner) {
      patternSelect.value = banner.pattern;
      colorSelect.value = String(banner.color);
      facingSelect.value = banner.facing;
    }
  };

  syncSelectors();
  state.raf = requestAnimationFrame(draw);
  updateSummary();

  state.claimPreview = {
    redraw: () => {
      updateSummary();
    },
  };
  return section;
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
