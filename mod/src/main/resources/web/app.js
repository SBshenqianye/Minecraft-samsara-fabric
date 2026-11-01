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
  scrollPositions: { config: 0, identity: 0, 'claim-range': 0, output: 0, permissions: 0, map: 0, 'world-stack': 0 },
  navScrollPositions: { config: 0, identity: 0, 'claim-range': 0, output: 0, permissions: 0, map: 0, 'world-stack': 0 },
  claimPreview: null,
};

const connectHooks = [];
const panelIds = ['panel-identity', 'panel-config', 'panel-claim-range', 'panel-output', 'panel-permissions', 'panel-map', 'panel-world-stack'];
const tabNames = { 'panel-identity': 'identity', 'panel-config': 'config', 'panel-claim-range': 'claim-range', 'panel-output': 'output', 'panel-permissions': 'permissions', 'panel-map': 'map', 'panel-world-stack': 'world-stack' };

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
  applyVisibility(fileEl, file);
  body.addEventListener('input', () => applyVisibility(fileEl, file));
  body.addEventListener('change', () => applyVisibility(fileEl, file));
  return { fileEl, navGroupChildren };
}

/**
 * 通用条件显示：schema 里声明 visibleWhen="path=value" 的条目，
 * 只有当前表单值匹配时才显示。首次从隐藏变为显示时高亮几秒。
 */
function applyVisibility(scope, file) {
  for (const row of scope.querySelectorAll('.entry[data-visible-when]')) {
    const condition = row.dataset.visibleWhen || '';
    const split = condition.indexOf('=');
    if (split <= 0) continue;
    const depPath = condition.slice(0, split);
    const expected = condition.slice(split + 1);
    const dep = file.entries.find((item) => item.path === depPath);
    if (!dep) continue;
    const visible = String(currentValue(file.id, dep)) === expected;
    const wasHidden = row.hidden;
    row.hidden = !visible;
    const navButton = document.querySelector(
      '#global-nav .nav-item[data-target="' + cssEscape(row.id) + '"]');
    if (navButton) navButton.hidden = !visible;
    if (!visible) {
      row.classList.remove('reveal-flash');
      continue;
    }
    if (wasHidden) flashRow(row);
  }
}

function flashRow(row) {
  row.classList.remove('reveal-flash');
  void row.offsetWidth;
  row.classList.add('reveal-flash');
  clearTimeout(Number(row.dataset.flashTimer) || 0);
  row.dataset.flashTimer = String(setTimeout(() => {
    row.classList.remove('reveal-flash');
  }, 2800));
}

/** buildNav 之后再同步一次：目标行隐藏时，对应导航项也隐藏。 */
function syncConditionalNav() {
  for (const button of document.querySelectorAll('#global-nav .nav-item[data-target]')) {
    const target = document.getElementById(button.dataset.target);
    if (target) button.hidden = target.hidden;
  }
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
    syncConditionalNav();
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
  let previewApi = null;
  const patternEntries = file.entries.filter((entry) => entry.path.startsWith('patternDirections.'));
  const mainEntries = file.entries.filter((entry) => !entry.path.startsWith('patternDirections.'));
  const { fileEl, navGroupChildren } = buildFileBlock(file, mainEntries, navTargets, true);
  const body = fileEl.querySelector('.file-body') || fileEl;
  const patternEditor = patternDirectionsEditor(file, patternEntries, (patternId) => {
    if (!previewApi) return;
    previewApi.selectPattern(patternId);
    scrollToTarget('claim-range-preview');
  });
  body.append(patternEditor);
  navTargets.push({ id: patternEditor.id, el: patternEditor });
  const preview = claimRangePreview(file);
  preview.id = 'claim-range-preview';
  body.append(preview);
  previewApi = preview.claimPreview;
  container.append(fileEl);
  setTabCount('claim-range', file.entries.length);
  if (state.tab === 'claim-range') {
    navTargets.push({ id: preview.id, el: preview });
    const entryItems = navGroupChildren.flatMap((group) => group.children || []);
    buildNav('圈地范围', [
      ...entryItems,
      { label: '图案单位方向', target: patternEditor.id },
      { label: '保护范围调试', target: preview.id },
      { label: '旗帜合成器', target: 'claim-banner-composer' },
    ], navTargets);
    syncConditionalNav();
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
  if (entry.visibleWhen) row.dataset.visibleWhen = entry.visibleWhen;

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
  { name: '白', id: 'white', hex: '#f9fffe' },
  { name: '橙', id: 'orange', hex: '#f9801d' },
  { name: '品红', id: 'magenta', hex: '#c74ebd' },
  { name: '淡蓝', id: 'light_blue', hex: '#3ab3da' },
  { name: '黄', id: 'yellow', hex: '#fed83d' },
  { name: '黄绿', id: 'lime', hex: '#80c71f' },
  { name: '粉', id: 'pink', hex: '#f38baa' },
  { name: '灰', id: 'gray', hex: '#474f52' },
  { name: '淡灰', id: 'light_gray', hex: '#9d9d97' },
  { name: '青', id: 'cyan', hex: '#169c9c' },
  { name: '紫', id: 'purple', hex: '#8932b8' },
  { name: '蓝', id: 'blue', hex: '#3c44aa' },
  { name: '棕', id: 'brown', hex: '#835432' },
  { name: '绿', id: 'green', hex: '#5e7c16' },
  { name: '红', id: 'red', hex: '#b02e26' },
  { name: '黑', id: 'black', hex: '#1d1d21' },
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
  border: { label: '全边框' },
  cross: { label: '交叉' },
  straight_cross: { label: '正向十字' },
  stripe_top: { label: '顶部条纹' },
  stripe_bottom: { label: '底部条纹' },
  stripe_left: { label: '左侧条纹' },
  stripe_right: { label: '右侧条纹' },
  square_top_left: { label: '左上角' },
  square_top_right: { label: '右上角' },
  square_bottom_left: { label: '左下角' },
  square_bottom_right: { label: '右下角' },
  stripe_downleft: { label: '左上斜纹' },
  stripe_downright: { label: '右上斜纹' },
  stripe_center: { label: '居中竖纹' },
  stripe_middle: { label: '居中横纹' },
};

const CLAIM_DIRECTION_NAMES = [
  'north', 'northeast', 'east', 'southeast',
  'south', 'southwest', 'west', 'northwest',
];
const CLAIM_DIRECTION_LABELS = ['北', '东北', '东', '东南', '南', '西南', '西', '西北'];
const CLAIM_DIRECTION_GLYPHS = ['↑', '↗', '→', '↘', '↓', '↙', '←', '↖'];
// 3×3 罗盘位置：列、行（1-based）
const CLAIM_DIRECTION_CELLS = [
  [2, 1], [3, 1], [3, 2], [3, 3],
  [2, 3], [1, 3], [1, 2], [1, 1],
];
const DEFAULT_CLAIM_PATTERN_DIRECTIONS = {
  stripe_top: ['north'],
  stripe_bottom: ['south'],
  stripe_left: ['west'],
  stripe_right: ['east'],
  square_top_left: ['northwest'],
  square_top_right: ['northeast'],
  square_bottom_left: ['southwest'],
  square_bottom_right: ['southeast'],
  stripe_downleft: ['northeast', 'southwest'],
  stripe_downright: ['northwest', 'southeast'],
  stripe_center: ['north', 'south'],
  stripe_middle: ['east', 'west'],
  cross: ['northeast', 'southeast', 'southwest', 'northwest'],
  straight_cross: ['north', 'east', 'south', 'west'],
  border: ['north', 'northeast', 'east', 'southeast', 'south', 'southwest', 'west', 'northwest'],
};

const CLAIM_FACINGS = [
  { id: 'north', label: '北', offset: 0 },
  { id: 'east', label: '东', offset: 2 },
  { id: 'south', label: '南', offset: 4 },
  { id: 'west', label: '西', offset: 6 },
];

function normalizeDirectionNames(list) {
  const source = Array.isArray(list) ? list : [];
  return CLAIM_DIRECTION_NAMES.filter((name) => source.some((item) => String(item).toLowerCase() === name));
}

function createBannerComposer(getSelected, onFill) {
  const wrap = document.createElement('div');
  wrap.className = 'banner-composer';
  const head = document.createElement('div');
  head.className = 'banner-composer-head';
  const title = document.createElement('strong');
  title.textContent = '旗帜选择器 / 合成器';
  const hint = document.createElement('span');
  hint.className = 'muted small';
  hint.textContent = '选择旗面颜色并叠加图案层；图案颜色决定保护长度。图案名/染料色已内置，织布机配方与图案物品图标暂未内置。';
  head.append(title, hint);

  const body = document.createElement('div');
  body.className = 'banner-composer-body';
  const preview = document.createElement('canvas');
  preview.className = 'banner-composer-preview';
  preview.width = 64;
  preview.height = 80;
  preview.title = '旗帜预览';

  const controls = document.createElement('div');
  controls.className = 'banner-composer-controls';
  const baseWrap = document.createElement('label');
  baseWrap.className = 'inline-field';
  const baseLabel = document.createElement('span');
  baseLabel.textContent = '旗面颜色';
  const baseSelect = document.createElement('select');
  DYE_PALETTE.forEach((dye, index) => {
    const option = document.createElement('option');
    option.value = String(index);
    option.textContent = dye.name + '（' + index + '）';
    baseSelect.append(option);
  });
  baseSelect.value = '15';
  baseWrap.append(baseLabel, baseSelect);

  const layers = document.createElement('div');
  layers.className = 'banner-composer-layers';
  const actions = document.createElement('div');
  actions.className = 'banner-composer-actions';
  const add = document.createElement('button');
  add.type = 'button';
  add.className = 'ghost';
  add.textContent = '+ 添加图案层';
  const load = document.createElement('button');
  load.type = 'button';
  load.className = 'ghost';
  load.textContent = '载入选中旗帜';
  const fill = document.createElement('button');
  fill.type = 'button';
  fill.className = 'primary';
  fill.textContent = '填入调试画布';
  actions.append(add, load, fill);
  controls.append(baseWrap, layers, actions);

  const commandWrap = document.createElement('div');
  commandWrap.className = 'banner-composer-command';
  const command = document.createElement('code');
  const copy = document.createElement('button');
  copy.type = 'button';
  copy.className = 'ghost';
  copy.textContent = '复制命令';
  commandWrap.append(command, copy);

  body.append(preview, controls);
  wrap.append(head, body, commandWrap);

  const state = { baseColor: 15, layers: [{ id: 'border', color: 0 }] };

  const commandText = () => {
    const base = DYE_PALETTE[state.baseColor] || DYE_PALETTE[0];
    const parts = state.layers.map((layer) => {
      const color = DYE_PALETTE[layer.color] || DYE_PALETTE[0];
      return '{pattern:"minecraft:' + layer.id + '",color:"' + color.id + '"}';
    });
    return '/give @p minecraft:' + base.id + '_banner[banner_patterns=['
      + parts.join(',') + ']] 1';
  };

  const refresh = () => {
    if (window.SamsaraBanners) {
      window.SamsaraBanners.paint(preview, state.layers,
        (DYE_PALETTE[state.baseColor] || DYE_PALETTE[0]).hex);
    }
    command.textContent = commandText();
  };

  const renderLayers = () => {
    layers.replaceChildren();
    state.layers.forEach((layer, index) => {
      const row = document.createElement('div');
      row.className = 'banner-composer-layer';
      const number = document.createElement('span');
      number.className = 'banner-composer-index';
      number.textContent = String(index + 1);
      const pattern = document.createElement('select');
      for (const [id, item] of Object.entries(CLAIM_PATTERNS)) {
        const option = document.createElement('option');
        option.value = id;
        option.textContent = item.label;
        pattern.append(option);
      }
      pattern.value = layer.id;
      const color = document.createElement('select');
      DYE_PALETTE.forEach((dye, colorIndex) => {
        const option = document.createElement('option');
        option.value = String(colorIndex);
        option.textContent = '图案 ' + dye.name;
        color.append(option);
      });
      color.value = String(layer.color);
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'ghost danger';
      remove.textContent = '×';
      remove.title = '删除这一层';
      pattern.addEventListener('change', () => {
        layer.id = pattern.value;
        refresh();
      });
      color.addEventListener('change', () => {
        layer.color = Number(color.value);
        refresh();
      });
      remove.addEventListener('click', () => {
        if (state.layers.length <= 1) return;
        state.layers.splice(index, 1);
        renderLayers();
        refresh();
      });
      row.append(number, pattern, color, remove);
      layers.append(row);
    });
  };

  baseSelect.addEventListener('change', () => {
    state.baseColor = Number(baseSelect.value);
    refresh();
  });
  add.addEventListener('click', () => {
    if (state.layers.length >= 8) return;
    state.layers.push({ id: 'border', color: 0 });
    renderLayers();
    refresh();
  });
  load.addEventListener('click', () => {
    const banner = getSelected();
    if (!banner) return;
    state.baseColor = banner.baseColor ?? 0;
    state.layers = (banner.patterns || []).map((layer) => ({ id: layer.id, color: layer.color }));
    if (state.layers.length === 0) state.layers = [{ id: 'border', color: 0 }];
    baseSelect.value = String(state.baseColor);
    renderLayers();
    refresh();
  });
  fill.addEventListener('click', () => {
    onFill({
      baseColor: state.baseColor,
      patterns: state.layers.map((layer) => ({ id: layer.id, color: layer.color })),
    });
  });
  copy.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(command.textContent);
      copy.textContent = '已复制';
      setTimeout(() => { copy.textContent = '复制命令'; }, 1200);
    } catch (e) {
      const range = document.createRange();
      range.selectNodeContents(command);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    }
  });

  renderLayers();
  refresh();
  return { element: wrap, refresh };
}

function patternDirectionsEditor(file, entries, onTrial) {
  const wrap = document.createElement('div');
  wrap.className = 'pattern-directions';
  wrap.id = 'claim-pattern-directions';
  const head = document.createElement('div');
  head.className = 'pattern-directions-head';
  const title = document.createElement('strong');
  title.textContent = '图案单位方向';
  const hint = document.createElement('span');
  hint.className = 'muted small';
  hint.textContent = '图案只决定单位长度上的方向；颜色负责延长这些方向。方向随旗帜朝向旋转。';
  const reset = document.createElement('button');
  reset.type = 'button';
  reset.className = 'ghost';
  reset.textContent = '恢复默认方向';
  head.append(title, hint, reset);

  const note = document.createElement('div');
  note.className = 'pattern-directions-note';
  note.textContent = '统一采用“看旗人正面视角”：图标上方 = 北，下方 = 南，左侧 = 西，右侧 = 东。'
      + '点 3×3 罗盘上的方向即可开关。例如“全边框”默认八向全开，用白色图案颜色就向八方各延伸 1 格，'
      + '用黑色则各延伸 16 格；图案本身不配置距离。点“试用”可把该图案放到下方调试画布。';

  const grid = document.createElement('div');
  grid.className = 'pattern-direction-grid';
  const controls = [];
  for (const entry of entries) {
    const id = entry.path.split('.').pop();
    const row = document.createElement('div');
    row.className = 'pattern-direction-row';
    const icon = window.SamsaraBanners
      ? window.SamsaraBanners.icon([{ id: id, color: 15 }], 0, 40)
      : null;
    if (icon) {
      icon.className = 'pattern-direction-icon';
      icon.title = '原版 ' + id + ' 旗帜图案';
    }
    const meta = document.createElement('div');
    meta.className = 'pattern-direction-meta';
    const label = document.createElement('span');
    label.className = 'pattern-direction-name';
    label.textContent = CLAIM_PATTERNS[id]?.label || id;
    const code = document.createElement('code');
    code.textContent = id;
    meta.append(label, code);

    const picker = document.createElement('div');
    picker.className = 'pattern-direction-picker';
    const buttons = [];
    for (let direction = 0; direction < 8; direction += 1) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'pattern-direction-cell';
      button.textContent = CLAIM_DIRECTION_GLYPHS[direction];
      button.title = CLAIM_DIRECTION_LABELS[direction];
      button.style.gridColumn = String(CLAIM_DIRECTION_CELLS[direction][0]);
      button.style.gridRow = String(CLAIM_DIRECTION_CELLS[direction][1]);
      button.addEventListener('click', () => {
        button.classList.toggle('active');
        const selected = buttons
            .map((item, index) => item.classList.contains('active')
              ? CLAIM_DIRECTION_NAMES[index] : null)
            .filter(Boolean);
        row.classList.toggle('empty', selected.length === 0);
        markEdited(file.id, entry, selected);
      });
      picker.append(button);
      buttons.push(button);
    }
    const center = document.createElement('span');
    center.className = 'pattern-direction-center';
    center.textContent = '·';
    center.style.gridColumn = '2';
    center.style.gridRow = '2';
    center.setAttribute('aria-hidden', 'true');
    picker.append(center);

    const setNames = (names) => {
      const normalized = normalizeDirectionNames(names);
      buttons.forEach((button, index) => {
        button.classList.toggle('active', normalized.includes(CLAIM_DIRECTION_NAMES[index]));
      });
      row.classList.toggle('empty', normalized.length === 0);
    };
    setNames(currentValue(file.id, entry));

    row.append(...(icon ? [icon] : []), meta, picker);
    if (onTrial) {
      const trial = document.createElement('button');
      trial.type = 'button';
      trial.className = 'ghost pattern-trial';
      trial.textContent = '试用';
      trial.title = '在下方调试画布中使用这个图案';
      trial.addEventListener('click', (event) => {
        event.preventDefault();
        event.stopPropagation();
        onTrial(id);
      });
      row.append(trial);
    }
    grid.append(row);
    controls.push({ entry, row, setNames });
  }

  reset.addEventListener('click', () => {
    for (const control of controls) {
      const defaults = control.entry.default
        || DEFAULT_CLAIM_PATTERN_DIRECTIONS[control.entry.path.split('.').pop()]
        || [];
      control.setNames(defaults);
      markEdited(file.id, control.entry, normalizeDirectionNames(defaults));
    }
  });

  wrap.append(head, note, grid);
  return wrap;
}


function buildIntList(file, entry, values) {
  const wrap = document.createElement('div');
  wrap.className = 'int-list-wrap';

  const tools = document.createElement('div');
  tools.className = 'int-list-tools';
  const hint = document.createElement('span');
  hint.className = 'int-list-hint';
  hint.textContent = '图案颜色 0（白）→ 15（黑）；数字为单方向延伸区块数（旗面颜色只影响外观）';
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
    option.textContent = '图案 ' + dye.name + '（' + index + '）';
    colorSelect.append(option);
  });
  const baseColorSelect = document.createElement('select');
  DYE_PALETTE.forEach((dye, index) => {
    const option = document.createElement('option');
    option.value = String(index);
    option.textContent = '旗面 ' + dye.name + '（' + index + '）';
    baseColorSelect.append(option);
  });
  baseColorSelect.value = '15';
  colorSelect.value = '0';
  const facingSelect = document.createElement('select');
  for (const facing of CLAIM_FACINGS) {
    const option = document.createElement('option');
    option.value = facing.id;
    option.textContent = '朝' + facing.label;
    facingSelect.append(option);
  }
  const patternIcon = document.createElement('canvas');
  patternIcon.className = 'claim-pattern-icon';
  patternIcon.width = 40;
  patternIcon.height = 40;
  patternIcon.title = '当前图案与染料颜色';
  controls.append(patternIcon, patternSelect, baseColorSelect, colorSelect, facingSelect);
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
    patternDirections: Object.fromEntries(patternIds.map((id) => [id,
      normalizeDirectionNames(readValue('patternDirections.' + id,
          DEFAULT_CLAIM_PATTERN_DIRECTIONS[id] || []))])),
  });

  const state = {
    banners: [{
      x: 0,
      z: 0,
      patterns: [{ id: patternSelect.value, color: Number(colorSelect.value) }],
      baseColor: Number(baseColorSelect.value),
      facing: facingSelect.value,
    }],
    selected: 0,
    hover: null,
    drag: null,
    lastSummary: 0,
    zoom: 0,
  };

  const firstLayer = (banner) => (banner && banner.patterns && banner.patterns[0])
    || { id: 'border', color: 0 };

  const size = 560;
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(size * dpr);
  canvas.height = Math.round(size * dpr);
  canvas.style.width = '100%';
  canvas.style.maxWidth = size + 'px';
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;

  const iconCache = new Map();
  const bannerIcon = (patterns, baseColor) => {
    const key = JSON.stringify([patterns, baseColor]);
    if (iconCache.has(key)) return iconCache.get(key);
    const base = DYE_PALETTE[baseColor] || DYE_PALETTE[0];
    const iconCanvas = window.SamsaraBanners
      ? window.SamsaraBanners.icon(patterns, base.hex, 64)
      : null;
    iconCache.set(key, iconCanvas);
    return iconCanvas;
  };

  const refreshPatternIcon = () => {
    if (!window.SamsaraBanners) return;
    const banner = selectedBanner();
    const layers = banner ? banner.patterns : [{ id: patternSelect.value, color: Number(colorSelect.value) }];
    const baseColor = banner ? Number(banner.baseColor ?? 0) : Number(baseColorSelect.value) || 0;
    window.SamsaraBanners.paint(patternIcon, layers, (DYE_PALETTE[baseColor] || DYE_PALETTE[0]).hex);
  };

  const distanceOfColor = (color, cfg) => {
    const index = Math.max(0, Math.min(15, Number(color) || 0));
    const base = cfg.mode === 'fixed' ? cfg.fixedDistance
      : (cfg.colorDistances[index] ?? index + 1);
    return Math.max(1, Math.min(cfg.maxDistance, Math.round(base)));
  };

  const distanceOf = (banner, cfg) => {
    const layers = banner.patterns || [];
    return layers.reduce((max, layer) => Math.max(max, distanceOfColor(layer.color, cfg)), 1);
  };

  const rotatedDirs = (patternId, facing, cfg) => {
    const names = (cfg.patternDirections && cfg.patternDirections[patternId])
      || DEFAULT_CLAIM_PATTERN_DIRECTIONS[patternId] || [];
    const offset = CLAIM_FACINGS.find((item) => item.id === facing)?.offset || 0;
    return normalizeDirectionNames(names)
        .map((name) => CLAIM_DIRECTION_NAMES.indexOf(name))
        .filter((index) => index >= 0)
        .map((index) => (index + offset) % 8);
  };

  const viewRadius = (cfg) => {
    if (state.zoom > 0) return state.zoom;
    let max = 1;
    for (const banner of state.banners) max = Math.max(max, distanceOf(banner, cfg));
    return Math.max(6, Math.min(28, max + 4));
  };

  const cellsFor = (banner, cfg) => {
    const cells = new Set();
    const add = (x, z) => cells.add(x + ',' + z);
    add(banner.x, banner.z);
    for (const layer of banner.patterns || []) {
      const dirs = rotatedDirs(layer.id, banner.facing, cfg);
      const distance = distanceOfColor(layer.color, cfg);
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

  // ---- 与 Geometry.compute 同源的“化线为面”：先合并多面旗帜的线段，再求封闭面 ----
  const pointKey = (x, z) => x + ',' + z;
  const keyPoint = (key) => {
    const [x, z] = key.split(',').map(Number);
    return { x, z };
  };
  const gcd = (a, b) => {
    let x = Math.abs(a);
    let y = Math.abs(b);
    while (y) { const t = x % y; x = y; y = t; }
    return x;
  };
  const directionIndex = (dx, dz) => {
    const sx = Math.sign(dx);
    const sz = Math.sign(dz);
    const key = sx + ',' + sz;
    return { '0,-1': 0, '1,-1': 1, '1,0': 2, '1,1': 3,
      '0,1': 4, '-1,1': 5, '-1,0': 6, '-1,-1': 7 }[key];
  };
  const addGraphPoint = (points, x, z) => {
    points.add(pointKey(x, z));
  };
  const addGraphEdge = (adj, x1, z1, x2, z2) => {
    const a = pointKey(x1, z1);
    const b = pointKey(x2, z2);
    if (!adj.has(a)) adj.set(a, new Set());
    if (!adj.has(b)) adj.set(b, new Set());
    adj.get(a).add(b);
    adj.get(b).add(a);
  };
  const rasterLine = (points, adj, x1, z1, x2, z2) => {
    addGraphPoint(points, x1, z1);
    addGraphPoint(points, x2, z2);
    const steps = gcd(x2 - x1, z2 - z1);
    if (steps === 0) return;
    const sx = (x2 - x1) / steps;
    const sz = (z2 - z1) / steps;
    let px = x1;
    let pz = z1;
    for (let i = 0; i < steps; i += 1) {
      const nx = px + sx;
      const nz = pz + sz;
      addGraphEdge(adj, px, pz, nx, nz);
      px = nx;
      pz = nz;
    }
  };
  const findFaces = (points, adj) => {
    const visited = new Set();
    const faces = [];
    for (const start of points) {
      const neighbors = adj.get(start) || new Set();
      for (const first of neighbors) {
        const edge = start + '->' + first;
        if (visited.has(edge)) continue;
        const face = new Set();
        let cur = start;
        let nxt = first;
        let bounded = true;
        const maxSteps = Math.max(16, points.size * 4);
        for (let step = 0; step < maxSteps; step += 1) {
          visited.add(cur + '->' + nxt);
          face.add(cur);
          if (nxt === start) break;
          const curPoint = keyPoint(cur);
          const nxtPoint = keyPoint(nxt);
          const reverse = directionIndex(curPoint.x - nxtPoint.x, curPoint.z - nxtPoint.z);
          let nextNode = null;
          let best = 9;
          for (const candidate of adj.get(nxt) || []) {
            if (candidate === cur) continue;
            const point = keyPoint(candidate);
            const dir = directionIndex(point.x - nxtPoint.x, point.z - nxtPoint.z);
            const diff = (dir - reverse + 8) % 8;
            if (diff > 0 && diff < best) {
              best = diff;
              nextNode = candidate;
            }
          }
          if (!nextNode) { bounded = false; break; }
          cur = nxt;
          nxt = nextNode;
        }
        if (bounded && face.size >= 3) faces.push(face);
      }
    }
    return faces;
  };
  const scanlineFill = (boundary, adj) => {
    const interior = new Set();
    if (boundary.size < 3) return interior;
    const edges = new Set();
    for (const key of boundary) {
      for (const neighbor of adj.get(key) || []) {
        if (!boundary.has(neighbor)) continue;
        const a = keyPoint(key);
        const b = keyPoint(neighbor);
        const ordered = (a.x < b.x || (a.x === b.x && a.z <= b.z))
          ? [a, b] : [b, a];
        edges.add(ordered[0].x + ',' + ordered[0].z + ',' + ordered[1].x + ',' + ordered[1].z);
      }
    }
    let minZ = Infinity;
    let maxZ = -Infinity;
    for (const key of boundary) {
      const point = keyPoint(key);
      minZ = Math.min(minZ, point.z);
      maxZ = Math.max(maxZ, point.z);
    }
    for (let z = minZ + 1; z < maxZ; z += 1) {
      const intersections = [];
      for (const edge of edges) {
        const [x1, z1, x2, z2] = edge.split(',').map(Number);
        if ((z1 < z && z <= z2) || (z2 < z && z <= z1)) {
          intersections.push(Math.round(x1 + (z - z1) / (z2 - z1) * (x2 - x1)));
        }
      }
      intersections.sort((a, b) => a - b);
      for (let i = 0; i + 1 < intersections.length; i += 2) {
        for (let x = intersections[i]; x <= intersections[i + 1]; x += 1) {
          const key = pointKey(x, z);
          if (!boundary.has(key)) interior.add(key);
        }
      }
    }
    return interior;
  };

  let geometryKey = '';
  let geometryCells = new Set();
  const claimGeometry = (banners, cfg) => {
    const key = JSON.stringify({
      banners,
      mode: cfg.mode,
      fixed: cfg.fixedDistance,
      colors: cfg.colorDistances,
      max: cfg.maxDistance,
      directions: cfg.patternDirections,
    });
    if (key === geometryKey) return geometryCells;
    const points = new Set();
    const adj = new Map();
    for (const banner of banners) {
      addGraphPoint(points, banner.x, banner.z);
      for (const layer of banner.patterns || []) {
        const dirs = rotatedDirs(layer.id, banner.facing, cfg);
        const distance = distanceOfColor(layer.color, cfg);
        const endpoints = [];
        for (const dir of dirs) {
          const direction = CLAIM_DIRECTIONS[dir];
          const ex = banner.x + direction.dx * distance;
          const ez = banner.z + direction.dz * distance;
          rasterLine(points, adj, banner.x, banner.z, ex, ez);
          endpoints.push({ x: ex, z: ez });
        }
        if (endpoints.length >= 3) {
          endpoints.sort((a, b) => {
            const da = Math.atan2(a.z - banner.z, a.x - banner.x);
            const db = Math.atan2(b.z - banner.z, b.x - banner.x);
            return da - db;
          });
          for (let i = 0; i < endpoints.length; i += 1) {
            const a = endpoints[i];
            const b = endpoints[(i + 1) % endpoints.length];
            rasterLine(points, adj, a.x, a.z, b.x, b.z);
          }
        }
      }
    }
    const cells = new Set(points);
    for (const face of findFaces(points, adj)) {
      for (const cell of scanlineFill(face, adj)) cells.add(cell);
    }
    geometryKey = key;
    geometryCells = cells;
    return cells;
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
    const union = claimGeometry(state.banners, cfg);
    const boundary = boundaryOf(union);
    const banner = selectedBanner();
    let text = '已放置 ' + state.banners.length + ' 面 · 保护 ' + union.size
        + ' 区块 · 边界 ' + boundary.length + ' 格';
    if (banner) {
      const layers = banner.patterns || [];
      const baseDye = DYE_PALETTE[banner.baseColor ?? 0] || { name: '颜色 ' + (banner.baseColor ?? 0) };
      const layerText = layers.map((layer) => {
        const dye = DYE_PALETTE[layer.color] || { name: '颜色 ' + layer.color };
        return (CLAIM_PATTERNS[layer.id]?.label || layer.id) + '/' + dye.name;
      }).join(' + ');
      const dirs = [...new Set(layers.flatMap((layer) => rotatedDirs(layer.id, banner.facing, cfg)))]
          .map((index) => CLAIM_DIRECTION_LABELS[index]).join('+') || '无方向';
      text += ' · 选中：' + (layerText || '无图案')
          + ' · 旗面' + baseDye.name
          + ' · 朝' + (CLAIM_FACINGS.find((f) => f.id === banner.facing)?.label || '北')
          + ' · ' + dirs + ' → ' + distanceOf(banner, cfg) + ' 区块';
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

    const union = claimGeometry(state.banners, cfg);

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

    const selected = selectedBanner();
    if (selected) {
      const selectedCells = cellsFor(selected, cfg);
      ctx.fillStyle = 'rgba(251, 191, 36, .14)';
      for (const key of selectedCells) {
        const [x, z] = key.split(',').map(Number);
        ctx.fillRect(px(x), pz(z), cell, cell);
      }
      ctx.fillStyle = 'rgba(251, 191, 36, .95)';
      for (const [x, z] of boundaryOf(selectedCells)) {
        ctx.fillRect(Math.round(px(x) + cell * .35), Math.round(pz(z) + cell * .35),
            Math.max(2, cell * .3), Math.max(2, cell * .3));
      }
    }

    state.banners.forEach((banner, index) => {
      const cx = px(banner.x) + cell / 2;
      const cz = pz(banner.z) + cell / 2;
      if (index === state.selected) {
        ctx.strokeStyle = 'rgba(251, 191, 36, .95)';
        ctx.lineWidth = 2;
        ctx.strokeRect(px(banner.x) + 1.5, pz(banner.z) + 1.5, cell - 3, cell - 3);
      }
      const icon = bannerIcon(banner.patterns || [], banner.baseColor ?? 0);
      const iconSize = Math.max(12, Math.min(cell * .9, 42));
      if (icon) {
        ctx.drawImage(icon, Math.round(cx - iconSize / 2), Math.round(cz - iconSize / 2),
            Math.round(iconSize), Math.round(iconSize));
      }
      const facing = CLAIM_DIRECTIONS[(CLAIM_FACINGS.find((f) => f.id === banner.facing)?.offset || 0)];
      ctx.strokeStyle = 'rgba(226, 232, 240, .85)';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.moveTo(cx, cz + cell * .30);
      ctx.lineTo(cx + facing.dx * cell * .55, cz + cell * .30 + facing.dz * cell * .55);
      ctx.stroke();
    });

    const ghost = state.drag ? state.drag.cell : state.hover;
    if (ghost && (state.drag || bannerAt(ghost) < 0)) {
      const ghostBanner = state.drag
        ? Object.assign({}, state.banners[state.drag.index], { x: ghost.x, z: ghost.z })
        : { x: ghost.x, z: ghost.z, patterns: [{ id: patternSelect.value, color: Number(colorSelect.value) }], baseColor: Number(baseColorSelect.value), facing: facingSelect.value };
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
      ctx.fillStyle = (DYE_PALETTE[Number(baseColorSelect.value)] || { hex: '#2dd4bf' }).hex;
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
    const layer = firstLayer(selectedBanner());
    layer.id = patternSelect.value;
    refreshPatternIcon();
  });
  colorSelect.addEventListener('change', () => {
    const layer = firstLayer(selectedBanner());
    layer.color = Number(colorSelect.value);
    refreshPatternIcon();
  });
  baseColorSelect.addEventListener('change', () => {
    const banner = selectedBanner();
    if (banner) banner.baseColor = Number(baseColorSelect.value);
    refreshPatternIcon();
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
      patterns: [{ id: patternSelect.value, color: Number(colorSelect.value) }],
      baseColor: Number(baseColorSelect.value),
      facing: facingSelect.value,
    });
    state.selected = state.banners.length - 1;
    syncSelectors();
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
        patterns: [{ id: patternSelect.value, color: Number(colorSelect.value) }],
        baseColor: Number(baseColorSelect.value),
        facing: facingSelect.value,
      });
      state.selected = state.banners.length - 1;
      state.drag = { index: state.selected, cell: { x: cell.x, z: cell.z } };
    }
    syncSelectors();
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
      const layer = firstLayer(banner);
      patternSelect.value = layer.id;
      colorSelect.value = String(layer.color);
      baseColorSelect.value = String(banner.baseColor ?? 0);
      facingSelect.value = banner.facing;
    }
    refreshPatternIcon();
  };

  syncSelectors();
  state.raf = requestAnimationFrame(draw);
  updateSummary();

  const composer = createBannerComposer(
    () => selectedBanner(),
    (bannerData) => {
      let banner = selectedBanner();
      if (!banner) {
        banner = {
          x: 0, z: 0, patterns: [], baseColor: bannerData.baseColor,
          facing: facingSelect.value,
        };
        state.banners.push(banner);
        state.selected = state.banners.length - 1;
      }
      banner.baseColor = bannerData.baseColor;
      banner.patterns = bannerData.patterns.map((layer) => ({
        id: layer.id, color: layer.color,
      }));
      syncSelectors();
      updateSummary();
      canvas.classList.remove('claim-sim-flash');
      void canvas.offsetWidth;
      canvas.classList.add('claim-sim-flash');
      setTimeout(() => canvas.classList.remove('claim-sim-flash'), 1600);
    });
  composer.element.id = 'claim-banner-composer';
  section.append(composer.element);

  const api = {
    redraw: () => {
      updateSummary();
      refreshPatternIcon();
    },
    selectPattern: (id) => {
      if (!CLAIM_PATTERNS[id]) return;
      patternSelect.value = id;
      if (!state.banners.length) {
        state.banners.push({
          x: 0,
          z: 0,
          patterns: [{ id: id, color: Number(colorSelect.value) }],
          baseColor: Number(baseColorSelect.value),
          facing: facingSelect.value,
        });
        state.selected = 0;
      } else {
        const banner = selectedBanner();
        if (banner) firstLayer(banner).id = id;
      }
      refreshPatternIcon();
      canvas.classList.remove('claim-sim-flash');
      void canvas.offsetWidth;
      canvas.classList.add('claim-sim-flash');
      setTimeout(() => canvas.classList.remove('claim-sim-flash'), 1600);
    },
  };
  section.claimPreview = api;
  if (window.SamsaraUI) window.SamsaraUI.state.claimPreview = api;
  state.claimPreview = api;
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
  for (const tab of document.querySelectorAll('.tab[data-panel]')) {
    tab.classList.toggle('active', tab.dataset.panel === 'panel-' + name);
  }
  try { history.replaceState(null, '', '#' + name); } catch (e) { /* file:// 等场景忽略 */ }
  updateTabActions(name);
  if (name === 'config') {
    render();
  } else if (name === 'claim-range') {
    renderClaimRange();
  } else if (name === 'map') {
    window.SamsaraMap?.activate();
  } else if (name === 'world-stack') {
    window.SamsaraWorldStack?.activate();
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
  map: {
    title: '地图',
    hint: '只读扫描存档；标签计数 = 全服保护区块；切世界后点“重新读取”',
    save: null,
  },
  'world-stack': {
    title: '世界栈',
    hint: '导入/归档重启后执行；排序立即保存，只改逻辑顺序不改目录名',
    save: null,
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
  if (save) {
    save.textContent = config.save || '保存并重载';
    save.hidden = !config.save;
  }
  if (def) def.hidden = name !== 'output';
}

function setTabCount(panel, count) {
  const map = { config: 'config-count', identity: 'identity-count', 'claim-range': 'claim-range-count', output: 'output-count', permissions: 'perm-count', map: 'map-count', 'world-stack': 'world-stack-count' };
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
  window.SamsaraMap?.refreshCount?.();
  window.SamsaraWorldStack?.refreshCount?.();
}

async function saveActive() {
  if (state.tab === 'identity') return window.SamsaraIdentity?.save?.();
  if (state.tab === 'output') return window.SamsaraOutput?.save?.();
  if (state.tab === 'permissions') return window.SamsaraPermissions?.saveOwners?.();
  if (state.tab === 'map') return window.SamsaraMap?.reload?.();
  if (state.tab === 'claim-range') return saveConfig('claim-range');
  return saveConfig();
}

async function reloadActive() {
  if (state.tab === 'identity') return window.SamsaraIdentity?.reload?.();
  if (state.tab === 'output') return window.SamsaraOutput?.reload?.();
  if (state.tab === 'permissions') return window.SamsaraPermissions?.reload?.();
  if (state.tab === 'map') return window.SamsaraMap?.reload?.();
  if (state.tab === 'world-stack') return window.SamsaraWorldStack?.reload?.();
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

for (const tab of document.querySelectorAll('.tab[data-panel]')) {
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
  authHeaders,
  setConnection,
  setActiveTab,
  isActiveTab: (name) => state.tab === name,
  buildNav,
  setNavActive,
  watchNav,
  scrollToTarget,
  refreshCounts,
  setTabCount,
  effectiveValue,
  authMessage,
};

const initialTab = (location.hash || '').replace('#', '');
if (['config', 'claim-range', 'identity', 'output', 'permissions', 'map', 'world-stack'].includes(initialTab)) {
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
