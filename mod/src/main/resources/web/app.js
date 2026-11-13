'use strict';

// 单页控制台：所有文本都用 textContent 写入，绝不使用 innerHTML。
// token 只存 sessionStorage，不进 URL、不写日志。

const state = {
  token: sessionStorage.getItem('samsara-token') || '',
  model: null,
  status: null,
  edits: new Map(), // `${file}:${path}` -> value
  invalidCurves: new Set(),
  tab: 'config',
  navTargets: [],
  navScrollHandler: null,
  scrollPositions: { config: 0, 'config-editor': 0, identity: 0, 'claim-range': 0, output: 0, permissions: 0, map: 0, announcements: 0, 'world-stack': 0, lottery: 0 },
  navScrollPositions: { config: 0, 'config-editor': 0, identity: 0, 'claim-range': 0, output: 0, permissions: 0, map: 0, announcements: 0, 'world-stack': 0, lottery: 0 },
  claimPreview: null,
  activePage: null,
  activePageContext: null,
  pageToken: 0,
};

const pageKit = window.SamsaraPageKit || null;

const connectHooks = [];
const connectedHooks = [];
// 文件块 → 配置模型。条件显示需要在 markEdited 后立即重算，
// 不能只依赖原生 input/change 或 DOM 冒泡（分段按钮不在表单事件链里）。
const fileBlockFiles = new WeakMap();
const curveEstimators = new Map();
const panelIds = ['panel-identity', 'panel-config', 'panel-config-editor', 'panel-claim-range', 'panel-output', 'panel-permissions', 'panel-map', 'panel-announcements', 'panel-world-stack', 'panel-lottery'];
const tabNames = { 'panel-identity': 'identity', 'panel-config': 'config', 'panel-config-editor': 'config-editor', 'panel-claim-range': 'claim-range', 'panel-output': 'output', 'panel-permissions': 'permissions', 'panel-map': 'map', 'panel-announcements': 'announcements', 'panel-world-stack': 'world-stack', 'panel-lottery': 'lottery' };

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
  if (version && status.webVersion) version.textContent = 'v' + status.webVersion;
  else if (version && status.version) version.textContent = 'v' + status.version;
  const rows = [
    ['Web 版本', status.webVersion || '—'],
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
  // 分段按钮、按钮组、int-list/string-list 等由 markEdited 主动写入，
  // 不会触发原生 input/change，所以这里直接重算全部文件块的条件显示。
  applyAllVisibility();
  document.dispatchEvent(new CustomEvent('samsara:config-changed', {
    detail: { fileId: fileId, path: entry.path },
  }));
}

// 保留曲线预估依赖同文件的其他配置（keepAll）；用单一监听器刷新，
// 不随 render() 重复注册，避免页面重渲染后监听器累积。
document.addEventListener('samsara:config-changed', (event) => {
  const fileId = event && event.detail ? event.detail.fileId : null;
  for (const [key, refresh] of curveEstimators) {
    if (!fileId || key.startsWith(fileId + ':')) refresh();
  }
});

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
  fileBlockFiles.set(fileEl, file);
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

/** 所有已渲染文件块的条件显示重算；markEdited 后立即调用。 */
function applyAllVisibility() {
  for (const el of document.querySelectorAll('.file-block')) {
    const file = fileBlockFiles.get(el);
    if (file) applyVisibility(el, file);
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
  const wideTypes = new Set(['string-list', 'int-list', 'level-map', 'template', 'curve']);
  const wide = wideTypes.has(entry.type)
      || (entry.description && entry.description.length > 110)
      || (typeof entry.default === 'string' && entry.default.length > 80);
  const row = document.createElement('div');
  row.className = 'entry' + (entry.restart ? ' restart' : '')
      + (wide ? ' entry-wide' : '');
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
      const choices = entry.choices || [];
      let current = value == null ? entry.default : value;
      const apply = (next) => {
        current = next;
        markEdited(file.id, entry, next);
        updateParticlePreview(wrap, next);
      };
      if (window.SamsaraUIKit && choices.length === 2) {
        wrap.append(window.SamsaraUIKit.buttonGroup(choices, current, apply));
      } else {
        const select = document.createElement('select');
        for (const choice of choices) {
          const option = document.createElement('option');
          option.value = choice;
          option.textContent = choice;
          select.append(option);
        }
        select.value = current;
        select.addEventListener('change', () => apply(select.value));
        wrap.append(select);
      }
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
          if (canvas.isConnected) window.SamsaraParticles?.attach(canvas, current, { background: theme });
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
    case 'curve': {
      // 轨迹保留曲线：逐行编辑 ageDays / keepRatio，保存前校验并显示容量预估
      return buildCurve(file, entry, value);
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
  if (window.SamsaraUIKit) {
    return window.SamsaraUIKit.control({ type: 'bool', onLabel: '启用', offLabel: '关闭' }, value === true,
        (next) => markEdited(file.id, entry, next));
  }
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

// ---- 轨迹保留曲线：逐行编辑 + 校验 + 容量预估 ----

function buildCurve(file, entry, value) {
  const key = valueKey(file.id, entry.path);
  const wrap = document.createElement('div');
  wrap.className = 'curve-editor';

  const chartWrap = document.createElement('div');
  chartWrap.className = 'curve-chart-wrap';
  const chart = document.createElement('canvas');
  chart.className = 'curve-chart';
  chart.tabIndex = 0;
  chart.setAttribute('role', 'img');
  chart.title = '拖动圆点修改曲线：横向 = 年龄上限（天），纵向 = 保留比例；右键删除圆点';
  const readout = document.createElement('div');
  readout.className = 'curve-readout';
  chartWrap.append(chart, readout);

  const actions = document.createElement('div');
  actions.className = 'curve-actions';
  const add = document.createElement('button');
  add.type = 'button';
  add.className = 'ghost';
  add.textContent = '+ 增加一档';
  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'ghost';
  remove.textContent = '删除选中';
  const hint = document.createElement('span');
  hint.className = 'muted small';
  hint.textContent = '拖动圆点即可修改：横向 = 年龄上限（天），纵向 = 保留比例。最后一档固定在右边缘，表示“更老”。';
  actions.append(add, remove, hint);

  const error = document.createElement('div');
  error.className = 'curve-error';
  error.setAttribute('role', 'alert');
  const estimate = document.createElement('div');
  estimate.className = 'curve-estimate';
  wrap.append(chartWrap, actions, error, estimate);

  const initial = Array.isArray(value) ? value : (entry.default || []);
  let points = initial.map((item) => ({
    ageDays: Number(item && item.ageDays),
    keepRatio: Number(item && item.keepRatio),
  })).filter((point) => Number.isFinite(point.ageDays) && Number.isFinite(point.keepRatio));
  if (points.length === 0) points = [{ ageDays: 7, keepRatio: 1 }];
  points.sort((a, b) => {
    // 末尾的 ageDays=0 表示“更老”，始终排在最后；其余按年龄升序
    if (a.ageDays === 0 && b.ageDays !== 0) return 1;
    if (b.ageDays === 0 && a.ageDays !== 0) return -1;
    return a.ageDays - b.ageDays;
  });
  let selected = Math.min(1, points.length - 1);
  let hover = -1;
  let drag = null;
  let estimateTimer = 0;
  let maxDays = 30;

  const PAD = { left: 46, right: 18, top: 18, bottom: 30 };
  const HEIGHT = 260;

  const layout = () => {
    const width = Math.max(280, chart.clientWidth || chart.parentElement?.clientWidth || 640);
    const dpr = window.devicePixelRatio || 1;
    if (chart.width !== Math.round(width * dpr) || chart.height !== Math.round(HEIGHT * dpr)) {
      chart.width = Math.round(width * dpr);
      chart.height = Math.round(HEIGHT * dpr);
    }
    chart.style.height = HEIGHT + 'px';
    return {
      width,
      height: HEIGHT,
      left: PAD.left,
      right: width - PAD.right,
      top: PAD.top,
      bottom: HEIGHT - PAD.bottom,
    };
  };

  const plotWidth = (box) => Math.max(10, box.right - box.left);
  const plotHeight = (box) => Math.max(10, box.bottom - box.top);

  const recomputeMaxDays = () => {
    let limit = 7;
    for (const point of points) {
      if (point.ageDays > 0) limit = Math.max(limit, point.ageDays);
    }
    const last = points[points.length - 1];
    const beforeLast = points.length >= 2 ? points[points.length - 2] : null;
    // 末档 age=0 表示“更老”：横轴延伸到 prev*2（至少 prev+30），
    // 与服务端 keepRatioAt 的平滑尾部一致。
    const tail = last && last.ageDays === 0 && beforeLast
      ? Math.max(beforeLast.ageDays * 2, beforeLast.ageDays + 30) : 0;
    maxDays = Math.max(10, Math.ceil(Math.max(limit * 1.18, tail)));
  };

  const xOf = (index, box, span) => {
    const point = points[index];
    if (index === points.length - 1 || point.ageDays === 0) return box.right;
    return box.left + Math.min(1, Math.max(0, point.ageDays / span)) * plotWidth(box);
  };
  const yOf = (ratio, box) => box.top + (1 - Math.min(1, Math.max(0, ratio))) * plotHeight(box);

  const ageAt = (px, box, span) => {
    const ratio = (px - box.left) / plotWidth(box);
    return Math.max(1, Math.round(ratio * span));
  };

  const niceStep = (span) => {
    const target = span / 6;
    const candidates = [1, 2, 5, 10, 20, 30, 50, 100, 200, 500, 1000, 2000, 5000];
    for (const step of candidates) if (step >= target) return step;
    return 10000;
  };

  const readRows = () => points.map((point) => ({
    ageDays: point.ageDays,
    keepRatio: point.keepRatio,
  }));

  const validate = (rows) => {
    const errors = [];
    if (rows.length === 0) {
      errors.push('至少需要一档');
      return errors;
    }
    let previousAge = -1;
    let previousRatio = 1;
    rows.forEach((point, index) => {
      const last = index === rows.length - 1;
      if (!Number.isInteger(point.ageDays) || point.ageDays < 0) {
        errors.push('第 ' + (index + 1) + ' 档 ageDays 必须是非负整数');
      } else if (last) {
        if (point.ageDays !== 0 && point.ageDays <= previousAge) {
          errors.push('最后一档 ageDays 必须为 0（更老）或大于前一档');
        }
      } else if (point.ageDays <= 0) {
        errors.push('只有最后一档的 ageDays 允许为 0（第 ' + (index + 1) + ' 档）');
      } else if (point.ageDays <= previousAge) {
        errors.push('ageDays 必须严格递增（第 ' + (index + 1) + ' 档）');
      }
      if (!Number.isFinite(point.keepRatio) || point.keepRatio < 0 || point.keepRatio > 1) {
        errors.push('keepRatio 必须在 [0,1] 内（第 ' + (index + 1) + ' 档）');
      } else if (point.keepRatio > previousRatio + 1e-9) {
        errors.push('keepRatio 必须单调不增（第 ' + (index + 1) + ' 档）');
      }
      previousAge = Math.max(previousAge, point.ageDays);
      previousRatio = point.keepRatio;
    });
    return errors;
  };

  const humanBytes = (bytes) => {
    const number = Number(bytes) || 0;
    if (number >= 1073741824) return (number / 1073741824).toFixed(2) + ' GiB';
    if (number >= 1048576) return (number / 1048576).toFixed(2) + ' MiB';
    if (number >= 1024) return (number / 1024).toFixed(1) + ' KiB';
    return number + ' B';
  };

  const renderEstimate = (data) => {
    estimate.replaceChildren();
    if (!data || data.ok === false) {
      estimate.textContent = '容量预估暂不可用' + (data && data.error ? '：' + data.error : '');
      return;
    }
    const head = document.createElement('div');
    head.className = 'curve-estimate-head';
    const calibrated = data.actualTotalSamples > 0;
    head.textContent = '容量预估 · 每点 ' + (data.bytesPerSample || 6) + 'B'
      + (calibrated ? '（按实际 .bin 校准）' : '（默认假设）')
      + ' · 新鲜采样 5Hz/天 ' + humanBytes(data.freshBytesPerDay)
      + '（仅估算，按每档保留比例换算）';
    estimate.append(head);
    const actual = document.createElement('div');
    actual.className = 'curve-estimate-line';
    actual.textContent = '当前实际 .bin：' + humanBytes(data.actualTotalBytes || 0)
      + ' · ' + (data.actualFileCount || 0) + ' 个文件'
      + ' · ' + (data.actualPlayerCount || 0) + ' 名玩家'
      + ' · 平均 ' + humanBytes(Math.round(data.actualBytesPerPlayer || 0)) + '/玩家'
      + (calibrated ? ' · 实测 ' + (data.actualBytesPerSample || 0).toFixed(2) + 'B/点' : '');
    estimate.append(actual);
    for (const tier of data.tiers || []) {
      const line = document.createElement('div');
      line.className = 'curve-estimate-line';
      const label = tier.ageDays === 0 ? '更老' : '≤ ' + tier.ageDays + ' 天';
      line.textContent = label + ' · 保留 ' + Math.round(tier.keepRatio * 100) + '%'
        + ' · 每日 ' + humanBytes(tier.bytesPerDay)
        + ' · 有限档累计 ' + humanBytes(tier.cumulativeBytes);
      estimate.append(line);
    }
    const summary = document.createElement('div');
    summary.className = 'curve-estimate-line';
    summary.textContent = '有限档稳态合计 ' + humanBytes(data.steadyBytes)
      + ' · 新鲜数据每日 ' + humanBytes(data.freshBytesPerDay);
    estimate.append(summary);
    if (data.warning) {
      const warn = document.createElement('div');
      warn.className = 'curve-estimate-warn';
      warn.textContent = (data.unbounded || data.keepAll ? '⚠ ' : '') + data.warning;
      estimate.append(warn);
    }
  };

  const fetchEstimate = (rows) => {
    window.clearTimeout(estimateTimer);
    estimateTimer = window.setTimeout(async () => {
      const keepAllEntry = file.entries.find((item) => item.path === 'track.retention.keepAll');
      const keepAll = keepAllEntry ? currentValue(file.id, keepAllEntry) === true : false;
      const query = '?curve=' + encodeURIComponent(JSON.stringify(rows))
        + '&keepAll=' + (keepAll ? '1' : '0');
      try {
        renderEstimate(await api('/api/config/retention-estimate' + query));
      } catch (e) {
        renderEstimate({ ok: false, error: e && e.message ? e.message : '请求失败' });
      }
    }, 220);
  };

  const apply = () => {
    const errors = validate(points);
    if (errors.length > 0) {
      error.textContent = errors[0];
      state.invalidCurves.add(key);
    } else {
      error.textContent = '';
      state.invalidCurves.delete(key);
      markEdited(file.id, entry, points.map((point) => ({
        ageDays: point.ageDays,
        keepRatio: point.keepRatio,
      })));
    }
    fetchEstimate(points);
  };

  const syncReadout = () => {
    readout.replaceChildren();
    points.forEach((point, index) => {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'curve-chip' + (index === selected ? ' selected' : '');
      chip.textContent = (point.ageDays === 0 ? '更老' : '≤ ' + point.ageDays + ' 天')
        + ' · ' + Math.round(point.keepRatio * 100) + '%';
      chip.addEventListener('click', () => {
        selected = index;
        draw();
      });
      readout.append(chip);
    });
    remove.disabled = points.length <= 1;
    chart.dataset.points = JSON.stringify(points);
  };

  const draw = () => {
    const box = layout();
    const span = maxDays;
    const dpr = window.devicePixelRatio || 1;
    const ctx = chart.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, box.width, box.height);

    const styles = getComputedStyle(document.documentElement);
    const ink = styles.getPropertyValue('--text').trim() || '#e2e8f0';
    const muted = styles.getPropertyValue('--muted').trim() || '#94a3b8';
    const line = styles.getPropertyValue('--line').trim() || 'rgba(148,163,184,.25)';
    const accent = styles.getPropertyValue('--accent').trim() || '#5eead4';
    const amber = '#fbbf24';

    // 网格
    ctx.font = '11px ui-monospace, SFMono-Regular, Menlo, monospace';
    ctx.fillStyle = muted;
    ctx.strokeStyle = line;
    ctx.lineWidth = 1;
    const step = niceStep(span);
    for (let age = 0; age <= span; age += step) {
      const x = box.left + (age / span) * plotWidth(box);
      ctx.beginPath();
      ctx.moveTo(x, box.top);
      ctx.lineTo(x, box.bottom);
      ctx.stroke();
      ctx.textAlign = 'center';
      ctx.fillText(String(age), x, box.bottom + 16);
    }
    for (const ratio of [0, 0.25, 0.5, 0.75, 1]) {
      const y = yOf(ratio, box);
      ctx.beginPath();
      ctx.moveTo(box.left, y);
      ctx.lineTo(box.right, y);
      ctx.stroke();
      ctx.textAlign = 'right';
      ctx.fillText(Math.round(ratio * 100) + '%', box.left - 8, y + 4);
    }
    ctx.textAlign = 'center';
    ctx.fillText('年龄（天）', box.left + plotWidth(box) / 2, box.height - 6);

    // 平滑曲线：相邻档位之间线性插值；(0,100%) 作为起点，末档落到右边缘。
    ctx.strokeStyle = accent;
    ctx.lineWidth = 2;
    ctx.beginPath();
    if (points[0].ageDays > 0) {
      ctx.moveTo(box.left, yOf(1, box));
    } else {
      ctx.moveTo(xOf(0, box, span), yOf(points[0].keepRatio, box));
    }
    points.forEach((point, index) => {
      ctx.lineTo(xOf(index, box, span), yOf(point.keepRatio, box));
    });
    ctx.lineTo(box.right, yOf(points[points.length - 1].keepRatio, box));
    ctx.stroke();

    // 圆点
    points.forEach((point, index) => {
      const x = xOf(index, box, span);
      const y = yOf(point.keepRatio, box);
      const isSelected = index === selected;
      const isHover = index === hover;
      ctx.beginPath();
      ctx.arc(x, y, isSelected ? 7 : isHover ? 6 : 5, 0, Math.PI * 2);
      ctx.fillStyle = isSelected ? amber : accent;
      ctx.fill();
      ctx.strokeStyle = 'rgba(15,23,42,.85)';
      ctx.lineWidth = 2;
      ctx.stroke();
      const label = point.ageDays === 0 ? '更老' : point.ageDays + '天';
      ctx.fillStyle = isSelected ? amber : ink;
      ctx.textAlign = index === points.length - 1 ? 'right' : 'center';
      ctx.fillText(label, x, Math.max(box.top + 12, y - 12));
    });

    syncReadout();
    chart.setAttribute('aria-label', points.map((point) => (point.ageDays === 0
      ? '更老 ' + Math.round(point.keepRatio * 100) + '%'
      : '≤' + point.ageDays + ' 天 ' + Math.round(point.keepRatio * 100) + '%')).join('，'));
  };

  const hitTest = (event) => {
    const box = layout();
    const rect = chart.getBoundingClientRect();
    const px = event.clientX - rect.left;
    const py = event.clientY - rect.top;
    let best = -1;
    let bestDistance = 14;
    points.forEach((point, index) => {
      const dx = px - xOf(index, box, maxDays);
      const dy = py - yOf(point.keepRatio, box);
      const distance = Math.hypot(dx, dy);
      if (distance <= bestDistance) {
        bestDistance = distance;
        best = index;
      }
    });
    return best;
  };

  const applyDrag = (event) => {
    if (!drag) return;
    const index = drag.index;
    const box = drag.box;
    const rect = chart.getBoundingClientRect();
    const px = event.clientX - rect.left;
    const py = event.clientY - rect.top;
    const point = points[index];
    const last = index === points.length - 1;
    if (!last) {
      const minAge = index === 0 ? 1 : points[index - 1].ageDays + 1;
      const nextAge = index + 1 < points.length ? points[index + 1].ageDays : 0;
      const maxAge = nextAge > 0 ? Math.max(minAge, nextAge - 1) : drag.span;
      point.ageDays = Math.min(maxAge, Math.max(minAge, ageAt(px, box, drag.span)));
    }
    const maxRatio = index === 0 ? 1 : points[index - 1].keepRatio;
    const minRatio = index + 1 < points.length ? points[index + 1].keepRatio : 0;
    const ratio = 1 - (py - box.top) / plotHeight(box);
    point.keepRatio = Math.round(Math.min(maxRatio, Math.max(minRatio, ratio)) * 100) / 100;
    draw();
  };

  chart.addEventListener('pointerdown', (event) => {
    if (event.button === 2) return;
    const index = hitTest(event);
    if (index < 0) return;
    event.preventDefault();
    selected = index;
    drag = { index, span: maxDays, box: layout() };
    chart.setPointerCapture?.(event.pointerId);
    draw();
  });
  chart.addEventListener('pointermove', (event) => {
    if (drag) {
      applyDrag(event);
      return;
    }
    const index = hitTest(event);
    if (index !== hover) {
      hover = index;
      draw();
    }
    chart.style.cursor = index >= 0 ? 'grab' : 'default';
  });
  const endDrag = (event) => {
    if (!drag) return;
    drag = null;
    chart.releasePointerCapture?.(event.pointerId);
    recomputeMaxDays();
    apply();
    draw();
  };
  chart.addEventListener('pointerup', endDrag);
  chart.addEventListener('pointercancel', endDrag);
  chart.addEventListener('contextmenu', (event) => {
    const index = hitTest(event);
    if (index < 0 || points.length <= 1) return;
    event.preventDefault();
    points.splice(index, 1);
    selected = Math.max(0, Math.min(selected, points.length - 1));
    recomputeMaxDays();
    apply();
    draw();
  });
  chart.addEventListener('mouseleave', () => {
    if (!drag && hover !== -1) {
      hover = -1;
      draw();
    }
  });
  chart.addEventListener('keydown', (event) => {
    if (event.key !== 'Delete' && event.key !== 'Backspace') return;
    if (points.length <= 1) return;
    event.preventDefault();
    points.splice(selected, 1);
    selected = Math.max(0, Math.min(selected, points.length - 1));
    recomputeMaxDays();
    apply();
    draw();
  });
  window.addEventListener('resize', draw);

  add.addEventListener('click', () => {
    const last = points[points.length - 1];
    const insertAt = last && last.ageDays === 0 ? points.length - 1 : points.length;
    const previous = insertAt > 0 ? points[insertAt - 1].ageDays : 0;
    const next = insertAt < points.length ? points[insertAt].ageDays : 0;
    let age = Math.max(1, previous + 30);
    if (next > 0) age = Math.max(previous + 1, Math.min(next - 1, age));
    points.splice(insertAt, 0, {
      ageDays: age,
      keepRatio: insertAt > 0 ? points[insertAt - 1].keepRatio : 1,
    });
    selected = insertAt;
    recomputeMaxDays();
    apply();
    draw();
  });

  remove.addEventListener('click', () => {
    if (points.length <= 1) return;
    points.splice(selected, 1);
    selected = Math.max(0, Math.min(selected, points.length - 1));
    apply();
    draw();
  });

  curveEstimators.set(key, () => fetchEstimate(readRows()));

  recomputeMaxDays();
  draw();
  apply();
  return wrap;
}


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
  'south', 'southwest', 'west', 'northwest', 'center',
];
const CLAIM_DIRECTION_LABELS = ['北', '东北', '东', '东南', '南', '西南', '西', '西北', '边框'];
const CLAIM_DIRECTION_GLYPHS = ['↑', '↗', '→', '↘', '↓', '↙', '←', '↖', '▣'];
// 3×3 罗盘位置：列、行（1-based）；center 占正中间
const CLAIM_DIRECTION_CELLS = [
  [2, 1], [3, 1], [3, 2], [3, 3],
  [2, 3], [1, 3], [1, 2], [1, 1], [2, 2],
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
  border: ['center'],
};

const CLAIM_FACINGS = [
  { id: 'north', label: '北', offset: 0 },
  { id: 'east', label: '东', offset: 2 },
  { id: 'south', label: '南', offset: 4 },
  { id: 'west', label: '西', offset: 6 },
];

// ---- 可复用浮窗：4×4 染料色板 / 带预览的图案网格 ----

let openClaimPopover = null;

function clampNumber(value, min, max, fallback) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.max(min, Math.min(max, Math.round(parsed)));
}

function colorDistanceValue(index, range) {
  const cfg = range || {};
  const max = clampNumber(cfg.maxDistance, 1, 32, 32);
  const mode = cfg.mode === 'fixed' ? 'fixed' : 'dye';
  const base = mode === 'fixed'
    ? clampNumber(cfg.fixedDistance, 1, max, 1)
    : (Array.isArray(cfg.colorDistances) && cfg.colorDistances[index] != null
      ? Number(cfg.colorDistances[index]) : index + 1);
  return clampNumber(base, 1, max, 1);
}

function closeClaimPopover() {
  if (!openClaimPopover) return;
  const current = openClaimPopover;
  openClaimPopover = null;
  current.cleanup?.();
  current.element.remove();
}

function mountClaimPopover(pop, anchor, focusTarget) {
  closeClaimPopover();
  pop.className = 'claim-popover ' + (pop.className || '');
  document.body.append(pop);
  const margin = 8;
  const rect = anchor.getBoundingClientRect();
  const width = pop.offsetWidth;
  const height = pop.offsetHeight;
  let left = Math.min(Math.max(margin, rect.left), window.innerWidth - width - margin);
  let top = rect.bottom + 6;
  if (top + height > window.innerHeight - margin) top = rect.top - height - 6;
  top = Math.max(margin, top);
  pop.style.left = Math.round(left) + 'px';
  pop.style.top = Math.round(top) + 'px';

  const onPointerDown = (event) => {
    if (!pop.contains(event.target) && !anchor.contains(event.target)) closeClaimPopover();
  };
  const onKeyDown = (event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeClaimPopover();
      try { anchor.focus({ preventScroll: true }); } catch (e) { /* ignore */ }
    }
  };
  const onViewportChange = () => closeClaimPopover();
  document.addEventListener('pointerdown', onPointerDown, true);
  document.addEventListener('keydown', onKeyDown, true);
  window.addEventListener('resize', onViewportChange);
  openClaimPopover = {
    element: pop,
    anchor,
    cleanup: () => {
      document.removeEventListener('pointerdown', onPointerDown, true);
      document.removeEventListener('keydown', onKeyDown, true);
      window.removeEventListener('resize', onViewportChange);
    },
  };
  requestAnimationFrame(() => {
    try { (focusTarget || pop.querySelector('button'))?.focus({ preventScroll: true }); } catch (e) { /* ignore */ }
  });
}

/** 4×4 浮窗色板；16 个 MC 染料颜色，每格显示色块 + 名称 + 序号 + 当前模式距离。 */
function openColorPalette(anchor, options) {
  const opts = options || {};
  const range = opts.range || {};
  const selected = clampNumber(opts.selected, 0, 15, 0);
  const pop = document.createElement('div');
  pop.className = 'claim-popover claim-color-popover';
  pop.setAttribute('role', 'dialog');
  pop.setAttribute('aria-label', opts.title || '选择颜色');

  const head = document.createElement('div');
  head.className = 'claim-popover-head';
  const title = document.createElement('strong');
  title.textContent = opts.title || '选择颜色';
  const hint = document.createElement('span');
  hint.className = 'muted small';
  hint.textContent = opts.hint
    || (range.mode === 'fixed'
      ? '固定模式：所有颜色使用同一距离'
      : '染料模式：距离取该颜色对应的区块数');
  head.append(title, hint);

  const grid = document.createElement('div');
  grid.className = 'claim-color-grid';
  grid.setAttribute('role', 'grid');
  const buttons = [];
  for (let index = 0; index < DYE_PALETTE.length; index += 1) {
    const dye = DYE_PALETTE[index];
    const distance = colorDistanceValue(index, range);
    const cell = document.createElement('button');
    cell.type = 'button';
    cell.className = 'claim-color-cell' + (index === selected ? ' selected' : '');
    cell.dataset.index = String(index);
    cell.setAttribute('role', 'gridcell');
    cell.setAttribute('aria-pressed', index === selected ? 'true' : 'false');
    cell.setAttribute('aria-label', dye.name + '（' + index + '），' + distance + ' 格');
    cell.title = dye.name + '（' + index + '） · ' + distance + ' 格';

    const swatch = document.createElement('span');
    swatch.className = 'claim-color-swatch';
    swatch.style.background = dye.hex;
    const name = document.createElement('span');
    name.className = 'claim-color-name';
    name.textContent = dye.name;
    const meta = document.createElement('span');
    meta.className = 'claim-color-meta';
    meta.textContent = index + ' · ' + distance + ' 格';
    const check = document.createElement('span');
    check.className = 'claim-color-check';
    check.textContent = index === selected ? '✓' : '';
    check.setAttribute('aria-hidden', 'true');

    cell.append(swatch, name, meta, check);
    cell.addEventListener('click', () => {
      closeClaimPopover();
      opts.onPick?.(index);
      try { anchor.focus({ preventScroll: true }); } catch (e) { /* ignore */ }
    });
    buttons.push(cell);
    grid.append(cell);
  }

  grid.addEventListener('keydown', (event) => {
    const current = buttons.indexOf(document.activeElement);
    if (current < 0) return;
    let next = current;
    if (event.key === 'ArrowRight') next = Math.min(15, current + 1);
    else if (event.key === 'ArrowLeft') next = Math.max(0, current - 1);
    else if (event.key === 'ArrowDown') next = Math.min(15, current + 4);
    else if (event.key === 'ArrowUp') next = Math.max(0, current - 4);
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = 15;
    else return;
    event.preventDefault();
    buttons[next].focus({ preventScroll: true });
  });

  const foot = document.createElement('div');
  foot.className = 'claim-popover-foot';
  foot.textContent = range.mode === 'fixed'
    ? '当前 fixedDistance = ' + colorDistanceValue(selected, range) + ' 区块'
    : '颜色 0（白）→ 15（黑）；数字是单方向延伸区块数';

  pop.append(head, grid, foot);
  mountClaimPopover(pop, anchor, buttons[selected] || buttons[0]);
  return pop;
}

/** 4 列图案网格；每格用 SamsaraBanners 画出「当前图案颜色 + 当前旗面颜色」的真实预览。 */
function openPatternGrid(anchor, options) {
  const opts = options || {};
  const selected = CLAIM_PATTERNS[opts.selected] ? opts.selected : 'border';
  const baseDye = DYE_PALETTE[opts.baseColor] || DYE_PALETTE[0];
  const patternDye = DYE_PALETTE[opts.patternColor] || DYE_PALETTE[0];
  const pop = document.createElement('div');
  pop.className = 'claim-popover claim-pattern-popover';
  pop.setAttribute('role', 'dialog');
  pop.setAttribute('aria-label', opts.title || '选择图案');

  const head = document.createElement('div');
  head.className = 'claim-popover-head';
  const title = document.createElement('strong');
  title.textContent = opts.title || '选择图案';
  const hint = document.createElement('span');
  hint.className = 'muted small';
  hint.textContent = '预览：图案颜色 ' + patternDye.name + ' / 旗面颜色 ' + baseDye.name;
  head.append(title, hint);

  const grid = document.createElement('div');
  grid.className = 'claim-pattern-grid';
  const buttons = [];
  Object.entries(CLAIM_PATTERNS).forEach(([id, pattern], index) => {
    const cell = document.createElement('button');
    cell.type = 'button';
    cell.className = 'claim-pattern-cell' + (id === selected ? ' selected' : '');
    cell.dataset.pattern = id;
    cell.setAttribute('aria-pressed', id === selected ? 'true' : 'false');
    cell.setAttribute('aria-label', pattern.label);
    cell.title = pattern.label + '（' + id + '）';

    const iconWrap = document.createElement('span');
    iconWrap.className = 'claim-pattern-cell-icon';
    if (window.SamsaraBanners) {
      const icon = window.SamsaraBanners.icon([{ id, color: opts.patternColor }], baseDye.hex, 40);
      icon.className = 'claim-pattern-canvas';
      iconWrap.append(icon);
    } else {
      const placeholder = document.createElement('span');
      placeholder.className = 'claim-pattern-placeholder';
      placeholder.textContent = '…';
      iconWrap.append(placeholder);
    }
    const label = document.createElement('span');
    label.className = 'claim-pattern-name';
    label.textContent = pattern.label;
    const code = document.createElement('span');
    code.className = 'claim-pattern-code';
    code.textContent = id;
    const check = document.createElement('span');
    check.className = 'claim-pattern-check';
    check.textContent = id === selected ? '✓' : '';
    check.setAttribute('aria-hidden', 'true');
    cell.append(iconWrap, label, code, check);
    cell.addEventListener('click', () => {
      closeClaimPopover();
      opts.onPick?.(id);
      try { anchor.focus({ preventScroll: true }); } catch (e) { /* ignore */ }
    });
    buttons.push(cell);
    grid.append(cell);
  });

  grid.addEventListener('keydown', (event) => {
    const current = buttons.indexOf(document.activeElement);
    if (current < 0) return;
    let next = current;
    if (event.key === 'ArrowRight') next = Math.min(buttons.length - 1, current + 1);
    else if (event.key === 'ArrowLeft') next = Math.max(0, current - 1);
    else if (event.key === 'ArrowDown') next = Math.min(buttons.length - 1, current + 4);
    else if (event.key === 'ArrowUp') next = Math.max(0, current - 4);
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = buttons.length - 1;
    else return;
    event.preventDefault();
    buttons[next].focus({ preventScroll: true });
  });

  const foot = document.createElement('div');
  foot.className = 'claim-popover-foot';
  foot.textContent = '图案决定单位方向；“边框”按图案颜色距离画正方形边框。图案颜色决定射线/边框长度（区块）。';

  pop.append(head, grid, foot);
  mountClaimPopover(pop, anchor, buttons.find((button) => button.dataset.pattern === selected) || buttons[0]);
  return pop;
}

/** 紧凑颜色触发器：约 30px 色块 + 短名称 + 序号/距离，不再使用 260px 下拉框。 */
function createColorTrigger(options) {
  const opts = options || {};
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'claim-trigger claim-color-trigger';
  button.dataset.role = opts.role || 'color-trigger';
  const swatch = document.createElement('span');
  swatch.className = 'claim-trigger-swatch';
  const name = document.createElement('span');
  name.className = 'claim-trigger-label';
  const meta = document.createElement('span');
  meta.className = 'claim-trigger-meta';
  button.append(swatch, name, meta);

  let value = clampNumber(opts.value, 0, 15, 0);
  const rangeOf = typeof opts.range === 'function' ? opts.range : () => opts.range || {};
  const sync = (next) => {
    if (next != null) value = clampNumber(next, 0, 15, 0);
    const range = rangeOf();
    const dye = DYE_PALETTE[value] || { name: '颜色 ' + value, hex: '#94a3b8' };
    const distance = colorDistanceValue(value, range);
    swatch.style.background = dye.hex;
    name.textContent = dye.name;
    meta.textContent = value + ' · ' + distance + ' 格';
    button.dataset.value = String(value);
    button.title = (opts.title || '颜色') + '：' + dye.name + '（' + value + '） · ' + distance + ' 格';
    button.setAttribute('aria-label', button.title);
  };
  button.addEventListener('click', () => {
    if (openClaimPopover && openClaimPopover.anchor === button) {
      closeClaimPopover();
      return;
    }
    const range = rangeOf();
    openColorPalette(button, {
      title: opts.title || '选择颜色',
      hint: opts.hint,
      selected: value,
      range,
      onPick: (index) => {
        value = index;
        sync();
        opts.onPick?.(index);
      },
    });
  });
  sync(opts.value);
  return { element: button, set: sync, get: () => value };
}

/** 紧凑图案触发器：小预览图标 + 图案名。 */
function createPatternTrigger(options) {
  const opts = options || {};
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'claim-trigger claim-pattern-trigger';
  button.dataset.role = opts.role || 'pattern-trigger';
  const iconWrap = document.createElement('span');
  iconWrap.className = 'claim-trigger-icon';
  const label = document.createElement('span');
  label.className = 'claim-trigger-label';
  button.append(iconWrap, label);

  let value = CLAIM_PATTERNS[opts.value] ? opts.value : 'border';
  const dynamicValue = (option, fallback) => (typeof option === 'function' ? option() : option) ?? fallback;
  const sync = (next) => {
    if (next != null && CLAIM_PATTERNS[next]) value = next;
    iconWrap.replaceChildren();
    if (window.SamsaraBanners) {
      const baseHex = (DYE_PALETTE[dynamicValue(opts.baseColor, 0)] || DYE_PALETTE[0]).hex;
      const icon = window.SamsaraBanners.icon(
          [{ id: value, color: dynamicValue(opts.patternColor, 0) }], baseHex, 28);
      icon.className = 'claim-trigger-canvas';
      iconWrap.append(icon);
    } else {
      const placeholder = document.createElement('span');
      placeholder.className = 'claim-trigger-placeholder';
      placeholder.textContent = '…';
      iconWrap.append(placeholder);
    }
    label.textContent = CLAIM_PATTERNS[value]?.label || value;
    button.dataset.value = value;
    button.title = (opts.title || '图案') + '：' + label.textContent;
    button.setAttribute('aria-label', button.title);
  };
  button.addEventListener('click', () => {
    if (openClaimPopover && openClaimPopover.anchor === button) {
      closeClaimPopover();
      return;
    }
    openPatternGrid(button, {
      title: opts.title || '选择图案',
      selected: value,
      patternColor: dynamicValue(opts.patternColor, 0),
      baseColor: dynamicValue(opts.baseColor, 0),
      onPick: (id) => {
        value = id;
        sync();
        opts.onPick?.(id);
      },
    });
  });
  sync(opts.value);
  return { element: button, set: sync, get: () => value };
}

function normalizeDirectionNames(list) {
  const source = Array.isArray(list) ? list : [];
  return CLAIM_DIRECTION_NAMES.filter((name) => source.some((item) => String(item).toLowerCase() === name));
}

function createBannerComposer(getSelected, onFill, getRange) {
  const wrap = document.createElement('div');
  wrap.className = 'banner-composer';
  const head = document.createElement('div');
  head.className = 'banner-composer-head';
  const title = document.createElement('strong');
  title.textContent = '旗帜选择器 / 合成器';
  const hint = document.createElement('span');
  hint.className = 'muted small';
  hint.textContent = '旗面颜色与每层图案颜色都用 4×4 色板；图案用带真实预览的网格。颜色决定射线长度，图案决定射线方向。';
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
  const baseWrap = document.createElement('div');
  baseWrap.className = 'inline-field banner-composer-base';
  const baseLabel = document.createElement('span');
  baseLabel.textContent = '旗面颜色';
  const rangeOf = typeof getRange === 'function' ? getRange : () => ({
    mode: 'dye', fixedDistance: 4, colorDistances: [], maxDistance: 32,
  });
  const baseTrigger = createColorTrigger({
    value: 15,
    title: '旗面颜色',
    role: 'base-color-trigger',
    range: rangeOf,
    onPick: (index) => {
      state.baseColor = index;
      renderLayers();
      refresh();
    },
  });
  baseWrap.append(baseLabel, baseTrigger.element);

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
  let layerTriggers = [];

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
    baseTrigger.set(state.baseColor);
    for (const trigger of layerTriggers) trigger.sync?.();
  };

  const renderLayers = () => {
    layers.replaceChildren();
    layerTriggers = [];
    state.layers.forEach((layer, index) => {
      const row = document.createElement('div');
      row.className = 'banner-composer-layer';
      const number = document.createElement('span');
      number.className = 'banner-composer-index';
      number.textContent = String(index + 1);

      const patternTrigger = createPatternTrigger({
        value: layer.id,
        title: '第 ' + (index + 1) + ' 层图案',
        role: 'layer-pattern-trigger',
        patternColor: () => layer.color,
        baseColor: () => state.baseColor,
        onPick: (id) => {
          layer.id = id;
          refresh();
        },
      });
      const colorTrigger = createColorTrigger({
        value: layer.color,
        title: '第 ' + (index + 1) + ' 层图案颜色',
        role: 'layer-color-trigger',
        range: rangeOf,
        onPick: (color) => {
          layer.color = color;
          patternTrigger.set(layer.id);
          refresh();
        },
      });
      layerTriggers.push({
        sync: () => {
          patternTrigger.set(layer.id);
          colorTrigger.set(layer.color);
        },
      });

      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'ghost danger';
      remove.textContent = '×';
      remove.title = '删除这一层';
      remove.addEventListener('click', () => {
        if (state.layers.length <= 1) return;
        state.layers.splice(index, 1);
        renderLayers();
        refresh();
      });
      row.append(number, patternTrigger.element, colorTrigger.element, remove);
      layers.append(row);
    });
  };

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
  return {
    element: wrap,
    refresh: () => {
      refresh();
      baseTrigger.set(state.baseColor);
      for (const trigger of layerTriggers) trigger.sync?.();
    },
    loadSelected: () => load.click(),
    getState: () => state,
  };
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
      + '点 3×3 罗盘上的方向即可开关；正中间的“▣ 边框”按图案颜色距离画正方形边框，颜色 = 边框半径，内部不填充。'
      + '例如“全边框”默认只选边框，白色距离 1 就是 3×3 周长，橙色距离 2 就是 5×5 周长。'
      + '内部由多面旗帜的射线闭合后填充；点“试用”可把该图案放到下方调试画布。';

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
    for (let direction = 0; direction < CLAIM_DIRECTION_NAMES.length; direction += 1) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'pattern-direction-cell'
        + (CLAIM_DIRECTION_NAMES[direction] === 'center' ? ' center' : '');
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
  sub.textContent = '射线会参与全局闭合填充；边框只画正方形周长，不填内部。“闭合填充”开关可对比原始线段与算完闭合后的结果。滚轮缩放、空白处拖动平移、点击放置、拖动移动、右键或 R 旋转。';
  titles.append(title, sub);

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
    patternDirections: Object.fromEntries(patternIds.map((id) => {
      // 与服务端 ClaimRangeConfig.normalizeDirections 一致：配置为空时回退默认方向。
      const configured = normalizeDirectionNames(readValue('patternDirections.' + id,
          DEFAULT_CLAIM_PATTERN_DIRECTIONS[id] || []));
      // 旧配置迁移：border 过去是八向（或八向 + center），新语义统一为纯边框。
      if (id === 'border' && configured.filter((name) => name !== 'center').length >= 8) {
        configured.length = 0;
        configured.push('center');
      }
      return [id, configured.length ? configured : (DEFAULT_CLAIM_PATTERN_DIRECTIONS[id] || [])];
    })),
  });
  const rangeInfo = () => {
    const cfg = readConfig();
    return {
      mode: cfg.mode,
      fixedDistance: cfg.fixedDistance,
      colorDistances: cfg.colorDistances,
      maxDistance: cfg.maxDistance,
    };
  };

  const state = {
    banners: [],
    selected: -1,
    hover: null,
    drag: null,
    pan: null,
    pendingPlace: null,
    lastSummary: 0,
    fillClosed: true,
    view: { radius: 12, cx: 0, cz: 0 },
    draft: { patternId: 'border', color: 0, baseColor: 15, facing: 'north' },
  };

  const firstLayer = (banner) => (banner && banner.patterns && banner.patterns[0])
    || { id: state.draft.patternId, color: state.draft.color };
  const selectedBanner = () => state.banners[state.selected] || null;
  const activeLayer = () => {
    const banner = selectedBanner();
    return banner ? firstLayer(banner) : state.draft;
  };
  const activeBase = () => {
    const banner = selectedBanner();
    return banner ? Number(banner.baseColor ?? 0) : state.draft.baseColor;
  };

  const controls = document.createElement('div');
  controls.className = 'claim-preview-controls';

  const patternTrigger = createPatternTrigger({
    value: state.draft.patternId,
    title: '图案',
    role: 'canvas-pattern-trigger',
    patternColor: () => state.draft.color,
    baseColor: () => state.draft.baseColor,
    onPick: (id) => {
      activeLayer().id = id;
      state.draft.patternId = id;
      updateSummary();
    },
  });
  const patternColorTrigger = createColorTrigger({
    value: state.draft.color,
    title: '图案颜色',
    role: 'canvas-pattern-color-trigger',
    range: rangeInfo,
    onPick: (color) => {
      activeLayer().color = color;
      state.draft.color = color;
      patternTrigger.set(activeLayer().id);
      updateSummary();
    },
  });
  const baseColorTrigger = createColorTrigger({
    value: state.draft.baseColor,
    title: '旗面颜色',
    role: 'canvas-base-color-trigger',
    range: rangeInfo,
    onPick: (color) => {
      const banner = selectedBanner();
      if (banner) banner.baseColor = color;
      state.draft.baseColor = color;
      patternTrigger.set(activeLayer().id);
      updateSummary();
    },
  });

  let facingGroup = null;
  if (window.SamsaraUIKit) {
    facingGroup = window.SamsaraUIKit.buttonGroup(
        CLAIM_FACINGS.map((facing) => ({ value: facing.id, label: '朝' + facing.label })),
        state.draft.facing,
        (id) => {
          const banner = selectedBanner();
          if (banner) banner.facing = id;
          state.draft.facing = id;
          updateSummary();
        },
        { className: 'compact' });
    facingGroup.id = 'claim-facing';
    facingGroup.title = '旗帜朝向（只支持 4 个正方向）';
  }
  controls.append(patternTrigger.element, patternColorTrigger.element,
      baseColorTrigger.element);
  if (facingGroup) controls.append(facingGroup);
  head.append(titles, controls);

  const toolbar = document.createElement('div');
  toolbar.className = 'claim-sim-toolbar';
  const chip = document.createElement('span');
  chip.className = 'claim-banner-chip';
  chip.draggable = true;
  chip.textContent = '拖到网格放置旗帜';
  const spacer = document.createElement('span');
  spacer.className = 'spacer';

  const zoomOut = document.createElement('button');
  zoomOut.type = 'button';
  zoomOut.id = 'claim-zoom-out';
  zoomOut.className = 'ghost';
  zoomOut.textContent = '−';
  zoomOut.title = '缩小（视野变大）';
  const zoomLabel = document.createElement('span');
  zoomLabel.id = 'claim-zoom-label';
  zoomLabel.className = 'claim-zoom-label';
  zoomLabel.textContent = '视野 12 格';
  const zoomIn = document.createElement('button');
  zoomIn.type = 'button';
  zoomIn.id = 'claim-zoom-in';
  zoomIn.className = 'ghost';
  zoomIn.textContent = '＋';
  zoomIn.title = '放大（视野变小）';
  const fitBtn = document.createElement('button');
  fitBtn.type = 'button';
  fitBtn.id = 'claim-zoom-fit';
  fitBtn.className = 'ghost';
  fitBtn.textContent = '适应内容';
  const resetBtn = document.createElement('button');
  resetBtn.type = 'button';
  resetBtn.id = 'claim-zoom-reset';
  resetBtn.className = 'ghost';
  resetBtn.textContent = '重置';
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
  const fillToggle = document.createElement('label');
  fillToggle.className = 'check-line claim-fill-toggle';
  const fillInput = document.createElement('input');
  fillInput.type = 'checkbox';
  fillInput.id = 'claim-fill-closed';
  fillInput.checked = state.fillClosed;
  fillInput.addEventListener('change', () => {
    state.fillClosed = fillInput.checked;
    updateSummary();
    draw();
  });
  const fillText = document.createElement('span');
  fillText.textContent = '闭合填充';
  fillToggle.append(fillInput, fillText);
  toolbar.append(chip, spacer, zoomOut, zoomLabel, zoomIn, fitBtn, resetBtn,
      rotateBtn, deleteBtn, clearBtn, fillToggle);

  const canvas = document.createElement('canvas');
  canvas.id = 'claim-sim-canvas';
  canvas.className = 'claim-preview-canvas claim-sim-canvas';
  canvas.tabIndex = 0;
  canvas.title = '点击放置旗帜；空白处拖动平移；滚轮缩放；拖动旗帜移动；右键/R 旋转；Delete 删除';
  const summary = document.createElement('div');
  summary.className = 'claim-preview-summary';
  const legend = document.createElement('div');
  legend.className = 'claim-preview-legend';
  legend.textContent = '绿色粒子 = 保护边界；青色线段 = 选中旗帜的射线/边框；琥珀色 = 选中旗帜范围。“闭合填充”关闭时只看原始线段。';

  section.append(head, toolbar, canvas, summary, legend);

  const size = 560;
  const pad = 26;
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(size * dpr);
  canvas.height = Math.round(size * dpr);
  canvas.style.width = '100%';
  canvas.style.maxWidth = size + 'px';
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;

  const clampRadius = (value) => Math.max(4, Math.min(64, Math.round(value)));
  const cellSize = () => (size - pad * 2) / (2 * state.view.radius + 1);
  const px = (x) => pad + (x - state.view.cx + state.view.radius) * cellSize();
  const pz = (z) => pad + (z - state.view.cz + state.view.radius) * cellSize();

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

  const rotatedShape = (patternId, facing, cfg) => {
    const names = (cfg.patternDirections && cfg.patternDirections[patternId])
      || DEFAULT_CLAIM_PATTERN_DIRECTIONS[patternId] || [];
    const offset = CLAIM_FACINGS.find((item) => item.id === facing)?.offset || 0;
    const rays = [];
    let center = false;
    for (const name of normalizeDirectionNames(names)) {
      const index = CLAIM_DIRECTION_NAMES.indexOf(name);
      if (name === 'center') {
        center = true;
      } else if (index >= 0) {
        const rotated = (index + offset) % 8;
        if (!rays.includes(rotated)) rays.push(rotated);
      }
    }
    rays.sort((a, b) => a - b);
    return { rays, center };
  };

  /** 单面旗帜的八向射线。 */
  const raySegmentsFor = (banner, cfg) => {
    const out = [];
    for (const layer of banner.patterns || []) {
      const distance = distanceOfColor(layer.color, cfg);
      const shape = rotatedShape(layer.id, banner.facing, cfg);
      for (const index of shape.rays) {
        const dir = CLAIM_DIRECTIONS[index];
        out.push([banner.x, banner.z,
          banner.x + dir.dx * distance, banner.z + dir.dz * distance]);
      }
    }
    return out;
  };

  /** 单面旗帜的边框：图案颜色距离 = 半径，画正方形周长（不填充内部）。 */
  const borderSegmentsFor = (banner, cfg) => {
    const out = [];
    for (const layer of banner.patterns || []) {
      const distance = distanceOfColor(layer.color, cfg);
      const shape = rotatedShape(layer.id, banner.facing, cfg);
      if (!shape.center) continue;
      const x = banner.x;
      const z = banner.z;
      out.push([x - distance, z - distance, x + distance, z - distance]);
      out.push([x + distance, z - distance, x + distance, z + distance]);
      out.push([x + distance, z + distance, x - distance, z + distance]);
      out.push([x - distance, z + distance, x - distance, z - distance]);
    }
    return out;
  };

  /** 单面旗帜的线段：射线 + 边框（用于选中高亮）。 */
  const shapeSegments = (banner, cfg) => [
    ...raySegmentsFor(banner, cfg),
    ...borderSegmentsFor(banner, cfg),
  ];

  /** 单面旗帜：射线 + 边框（含中心），绝不单独填充。 */
  const cellsFor = (banner, cfg) => {
    const cells = new Set();
    // 旗帜所在区块始终属于保护范围；边框只额外贡献周长。
    cells.add(banner.x + ',' + banner.z);
    for (const segment of shapeSegments(banner, cfg)) {
      addLineCells(segment[0], segment[1], segment[2], segment[3],
          (x, z) => cells.add(x + ',' + z));
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

  // ---- 与 Geometry.compute 同源的全局「化线为面」 ----
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
    let px0 = x1;
    let pz0 = z1;
    for (let i = 0; i < steps; i += 1) {
      const nx = px0 + sx;
      const nz = pz0 + sz;
      // 与服务端 Geometry.rasterize 一致：中间格点也必须进入点集，
      // 否则 findFaces 只从端点出发，单旗射线会少掉中间格，Web 预览与服务端不一致。
      addGraphPoint(points, nx, nz);
      addGraphEdge(adj, px0, pz0, nx, nz);
      px0 = nx;
      pz0 = nz;
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
      fill: state.fillClosed,
    });
    if (key === geometryKey) return geometryCells;
    const points = new Set();
    const adj = new Map();
    const borderCells = new Set();
    for (const banner of banners) {
      addGraphPoint(points, banner.x, banner.z);
      // 射线参与全局化线为面；边框只做周长，不进入填充图。
      for (const segment of raySegmentsFor(banner, cfg)) {
        rasterLine(points, adj, segment[0], segment[1], segment[2], segment[3]);
      }
      for (const segment of borderSegmentsFor(banner, cfg)) {
        rasterLine(borderCells, new Map(), segment[0], segment[1], segment[2], segment[3]);
      }
    }
    const cells = new Set(points);
    for (const key of borderCells) cells.add(key);
    if (state.fillClosed) {
      for (const face of findFaces(points, adj)) {
        for (const cell of scanlineFill(face, adj)) cells.add(cell);
      }
    }
    geometryKey = key;
    geometryCells = cells;
    return cells;
  };

  const viewCellAt = (event) => {
    const rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    const x = (event.clientX - rect.left) * (size / rect.width);
    const y = (event.clientY - rect.top) * (size / rect.height);
    if (x < 0 || y < 0 || x > size || y > size) return null;
    const cell = cellSize();
    const gx = Math.floor((x - pad) / cell + state.view.cx - state.view.radius);
    const gz = Math.floor((y - pad) / cell + state.view.cz - state.view.radius);
    if (gx < state.view.cx - state.view.radius || gx > state.view.cx + state.view.radius
        || gz < state.view.cz - state.view.radius || gz > state.view.cz + state.view.radius) {
      return null;
    }
    return { x: gx, z: gz };
  };

  const bannerAt = (cell) => state.banners.findIndex((item) => item.x === cell.x && item.z === cell.z);

  const fitView = () => {
    const cfg = readConfig();
    let minX = Infinity;
    let maxX = -Infinity;
    let minZ = Infinity;
    let maxZ = -Infinity;
    const consider = (x, z) => {
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minZ = Math.min(minZ, z);
      maxZ = Math.max(maxZ, z);
    };
    if (state.banners.length === 0) {
      consider(0, 0);
    } else {
      for (const banner of state.banners) {
        const cells = cellsFor(banner, cfg);
        for (const key of cells) {
          const [x, z] = key.split(',').map(Number);
          consider(x, z);
        }
        consider(banner.x, banner.z);
      }
    }
    const margin = 3;
    const cx = (minX + maxX) / 2;
    const cz = (minZ + maxZ) / 2;
    const half = Math.max((maxX - minX) / 2, (maxZ - minZ) / 2) + margin;
    state.view.radius = clampRadius(half);
    state.view.cx = cx;
    state.view.cz = cz;
    updateZoomLabel();
  };

  const resetView = () => {
    state.view.radius = 12;
    state.view.cx = 0;
    state.view.cz = 0;
    updateZoomLabel();
  };

  const updateZoomLabel = () => {
    zoomLabel.textContent = '视野 ' + state.view.radius + ' 格';
    canvas.dataset.viewRadius = String(state.view.radius);
    canvas.dataset.viewCenter = state.view.cx.toFixed(2) + ',' + state.view.cz.toFixed(2);
  };

  const zoomAt = (event, deltaY) => {
    const rect = canvas.getBoundingClientRect();
    if (!rect.width) return;
    const sx = (event.clientX - rect.left) * (size / rect.width);
    const sy = (event.clientY - rect.top) * (size / rect.height);
    const oldRadius = state.view.radius;
    const nextRadius = clampRadius(oldRadius * (deltaY > 0 ? 1.2 : 1 / 1.2));
    if (nextRadius === oldRadius) return;
    const oldCell = (size - pad * 2) / (2 * oldRadius + 1);
    const focusX = (sx - pad) / oldCell + state.view.cx - oldRadius;
    const focusZ = (sy - pad) / oldCell + state.view.cz - oldRadius;
    state.view.radius = nextRadius;
    const newCell = (size - pad * 2) / (2 * nextRadius + 1);
    state.view.cx = focusX - (sx - pad) / newCell + nextRadius;
    state.view.cz = focusZ - (sy - pad) / newCell + nextRadius;
    updateZoomLabel();
  };

  const syncSelectors = () => {
    const banner = selectedBanner();
    if (banner) {
      const layer = firstLayer(banner);
      state.draft.patternId = layer.id;
      state.draft.color = layer.color;
      state.draft.baseColor = Number(banner.baseColor ?? 0);
      state.draft.facing = banner.facing || 'north';
    }
    patternTrigger.set(state.draft.patternId);
    patternColorTrigger.set(state.draft.color);
    baseColorTrigger.set(state.draft.baseColor);
    facingGroup?.setValue(state.draft.facing);
  };

  const rotateBanner = (banner) => {
    if (!banner) return;
    const index = Math.max(0, CLAIM_FACINGS.findIndex((item) => item.id === banner.facing));
    banner.facing = CLAIM_FACINGS[(index + 1) % CLAIM_FACINGS.length].id;
    state.draft.facing = banner.facing;
    facingGroup?.setValue(banner.facing);
    updateSummary();
  };

  const updateSummary = () => {
    const cfg = readConfig();
    const union = claimGeometry(state.banners, cfg);
    const boundary = boundaryOf(union);
    const banner = selectedBanner();
    let text = '已放置 ' + state.banners.length + ' 面 · 保护 ' + union.size
        + ' 区块 · 边界 ' + boundary.length + ' 格 · '
        + (state.fillClosed ? '闭合填充' : '原始线段');
    if (banner) {
      const layers = banner.patterns || [];
      const baseDye = DYE_PALETTE[banner.baseColor ?? 0] || { name: '颜色 ' + (banner.baseColor ?? 0) };
      const layerText = layers.map((layer) => {
        const dye = DYE_PALETTE[layer.color] || { name: '颜色 ' + layer.color };
        return (CLAIM_PATTERNS[layer.id]?.label || layer.id) + '/' + dye.name;
      }).join(' + ');
      const shapes = layers.map((layer) => rotatedShape(layer.id, banner.facing, cfg));
      const dirs = [...new Set(shapes.flatMap((shape) => shape.rays))]
          .map((index) => CLAIM_DIRECTION_LABELS[index])
          .concat(shapes.some((shape) => shape.center) ? ['边框'] : [])
          .join('+') || '无方向';
      const facingLabel = CLAIM_FACINGS.find((item) => item.id === banner.facing)?.label || '北';
      text += ' · 选中：' + (layerText || '无图案')
          + ' · 旗面' + baseDye.name
          + ' · 旗面朝 ' + facingLabel
          + ' · 方向 ' + dirs
          + ' → 距离 ' + distanceOf(banner, cfg) + ' 区块';
      canvas.dataset.facing = banner.facing;
    } else {
      text += ' · 点击网格放置第一面旗帜';
      canvas.dataset.facing = state.draft.facing;
    }
    summary.textContent = text;
    canvas.dataset.bannerCount = String(state.banners.length);
    canvas.dataset.protectedCells = String(union.size);
    canvas.dataset.pattern = banner ? firstLayer(banner).id : state.draft.patternId;
    canvas.dataset.patternColor = String(banner ? firstLayer(banner).color : state.draft.color);
    canvas.dataset.patternDistance = String(distanceOfColor(
        banner ? firstLayer(banner).color : state.draft.color, cfg));
    canvas.dataset.rayCells = String(banner ? cellsFor(banner, cfg).size : 0);
    canvas.dataset.fillClosed = state.fillClosed ? '1' : '0';
  };

  const drawFacing = (banner, cx, cz, cell, isSelected) => {
    const facing = CLAIM_FACINGS.find((item) => item.id === banner.facing) || CLAIM_FACINGS[0];
    const vector = CLAIM_DIRECTIONS[facing.offset];
    const angle = Math.atan2(vector.dz, vector.dx);
    const length = Math.max(cell * 1.15, 16);
    const spread = Math.PI / 6;

    ctx.beginPath();
    ctx.moveTo(cx, cz);
    ctx.arc(cx, cz, length * 1.2, angle - spread, angle + spread);
    ctx.closePath();
    ctx.fillStyle = isSelected ? 'rgba(251, 191, 36, .16)' : 'rgba(94, 234, 212, .09)';
    ctx.fill();

    const ex = cx + vector.dx * length;
    const ez = cz + vector.dz * length;
    ctx.strokeStyle = isSelected ? 'rgba(251, 191, 36, .95)' : 'rgba(226, 232, 240, .72)';
    ctx.lineWidth = isSelected ? 2 : 1.4;
    ctx.beginPath();
    ctx.moveTo(cx, cz);
    ctx.lineTo(ex, ez);
    ctx.stroke();

    const head = Math.max(4, cell * .3);
    const perp = angle + Math.PI / 2;
    ctx.beginPath();
    ctx.moveTo(ex, ez);
    ctx.lineTo(ex - Math.cos(angle) * head + Math.cos(perp) * head * .55,
        ez - Math.sin(angle) * head + Math.sin(perp) * head * .55);
    ctx.lineTo(ex - Math.cos(angle) * head - Math.cos(perp) * head * .55,
        ez - Math.sin(angle) * head - Math.sin(perp) * head * .55);
    ctx.closePath();
    ctx.fillStyle = isSelected ? 'rgba(251, 191, 36, .95)' : 'rgba(226, 232, 240, .72)';
    ctx.fill();

    if (isSelected) {
      ctx.font = 'bold 12px system-ui, sans-serif';
      ctx.fillStyle = '#fbbf24';
      ctx.textAlign = 'left';
      ctx.textBaseline = 'alphabetic';
      ctx.fillText('朝' + facing.label, cx + cell * .55, cz - cell * .55);
    }
  };

  const drawCompass = () => {
    const cx = size - 52;
    const cy = 52;
    const radius = 26;
    ctx.save();
    ctx.fillStyle = 'rgba(8, 12, 17, .74)';
    ctx.strokeStyle = 'rgba(148, 163, 184, .32)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.arc(cx, cy, radius + 9, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    const selectedFacing = selectedBanner()?.facing;
    for (const item of [
      { id: 'north', label: '北', dx: 0, dz: -1 },
      { id: 'east', label: '东', dx: 1, dz: 0 },
      { id: 'south', label: '南', dx: 0, dz: 1 },
      { id: 'west', label: '西', dx: -1, dz: 0 },
    ]) {
      const active = item.id === selectedFacing;
      ctx.strokeStyle = active ? '#fbbf24' : 'rgba(148, 163, 184, .5)';
      ctx.lineWidth = active ? 2.4 : 1.1;
      ctx.beginPath();
      ctx.moveTo(cx, cy);
      ctx.lineTo(cx + item.dx * (radius - 5), cy + item.dz * (radius - 5));
      ctx.stroke();
      ctx.fillStyle = active ? '#fbbf24' : 'rgba(226, 232, 240, .72)';
      ctx.font = (active ? 'bold ' : '') + '11px system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(item.label, cx + item.dx * (radius + 4), cy + item.dz * (radius + 4));
    }
    ctx.fillStyle = 'rgba(226, 232, 240, .9)';
    ctx.beginPath();
    ctx.arc(cx, cy, 2, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  };

  const draw = (now) => {
    if (!canvas.isConnected) return;
    const cfg = readConfig();
    const radius = state.view.radius;
    const cell = cellSize();

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, size, size);
    ctx.fillStyle = '#0a1220';
    ctx.fillRect(0, 0, size, size);

    ctx.strokeStyle = 'rgba(148, 163, 184, .10)';
    ctx.lineWidth = 1;
    const step = cell >= 14 ? 1 : (cell >= 7 ? 2 : 4);
    const firstX = Math.floor(state.view.cx - radius);
    const lastX = Math.ceil(state.view.cx + radius);
    const firstZ = Math.floor(state.view.cz - radius);
    const lastZ = Math.ceil(state.view.cz + radius);
    for (let i = firstX; i <= lastX; i += step) {
      ctx.beginPath();
      ctx.moveTo(px(i), pad);
      ctx.lineTo(px(i), size - pad);
      ctx.stroke();
    }
    for (let i = firstZ; i <= lastZ; i += step) {
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
      const isSelected = index === state.selected;
      drawFacing(banner, cx, cz, cell, isSelected);
      if (isSelected) {
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
    });

    const ghost = state.drag ? state.drag.cell : state.hover;
    if (ghost && (state.drag || bannerAt(ghost) < 0)) {
      const ghostBanner = state.drag
        ? Object.assign({}, state.banners[state.drag.index], { x: ghost.x, z: ghost.z })
        : {
          x: ghost.x,
          z: ghost.z,
          patterns: [{ id: state.draft.patternId, color: state.draft.color }],
          baseColor: state.draft.baseColor,
          facing: state.draft.facing,
        };
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
      ctx.fillStyle = (DYE_PALETTE[state.draft.baseColor] || { hex: '#2dd4bf' }).hex;
      ctx.fillRect(gx + cell * .3, gz + cell * .3, cell * .4, cell * .4);
      ctx.globalAlpha = 1;
    }

    drawCompass();

    if (now - state.lastSummary > 220) {
      state.lastSummary = now;
      updateSummary();
      updateZoomLabel();
    }
    state.raf = requestAnimationFrame(draw);
  };

  const placeBanner = (cell) => {
    state.banners.push({
      x: cell.x,
      z: cell.z,
      patterns: [{ id: state.draft.patternId, color: state.draft.color }],
      baseColor: state.draft.baseColor,
      facing: state.draft.facing,
    });
    state.selected = state.banners.length - 1;
    syncSelectors();
    updateSummary();
  };

  const schedulePlace = (cell) => {
    if (state.pendingPlace) clearTimeout(state.pendingPlace);
    state.pendingPlace = setTimeout(() => {
      state.pendingPlace = null;
      placeBanner(cell);
    }, 220);
  };

  zoomIn.addEventListener('click', () => {
    state.view.radius = clampRadius(state.view.radius / 1.25);
    updateZoomLabel();
  });
  zoomOut.addEventListener('click', () => {
    state.view.radius = clampRadius(state.view.radius * 1.25);
    updateZoomLabel();
  });
  fitBtn.addEventListener('click', fitView);
  resetBtn.addEventListener('click', resetView);
  rotateBtn.addEventListener('click', () => rotateBanner(selectedBanner()));
  deleteBtn.addEventListener('click', () => {
    if (state.selected < 0 || !state.banners[state.selected]) return;
    state.banners.splice(state.selected, 1);
    state.selected = state.banners.length ? Math.min(state.selected, state.banners.length - 1) : -1;
    syncSelectors();
    updateSummary();
  });
  clearBtn.addEventListener('click', () => {
    state.banners = [];
    state.selected = -1;
    syncSelectors();
    updateSummary();
  });

  chip.addEventListener('dragstart', (event) => {
    try { event.dataTransfer.setData('text/plain', 'samsara-banner'); } catch (e) { /* ignore */ }
    event.dataTransfer.effectAllowed = 'copy';
  });
  canvas.addEventListener('dragover', (event) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
    state.hover = viewCellAt(event);
  });
  canvas.addEventListener('drop', (event) => {
    event.preventDefault();
    const cell = viewCellAt(event);
    if (!cell) return;
    placeBanner(cell);
  });

  canvas.addEventListener('pointermove', (event) => {
    const cell = viewCellAt(event);
    state.hover = cell;
    if (state.drag && cell) state.drag.cell = cell;
    if (state.pan) {
      const rect = canvas.getBoundingClientRect();
      if (rect.width) {
        const dx = (event.clientX - state.pan.startX) * (size / rect.width);
        const dy = (event.clientY - state.pan.startY) * (size / rect.height);
        if (Math.abs(dx) > 3 || Math.abs(dy) > 3) state.pan.moved = true;
        const cellW = cellSize();
        state.view.cx = state.pan.startCx - dx / cellW;
        state.view.cz = state.pan.startCz - dy / cellW;
        updateZoomLabel();
      }
    }
    canvas.style.cursor = state.pan
      ? (state.pan.moved ? 'grabbing' : 'grab')
      : (state.drag ? 'grabbing' : (cell && bannerAt(cell) >= 0 ? 'move' : 'crosshair'));
  });
  canvas.addEventListener('pointerleave', () => {
    state.hover = null;
  });
  canvas.addEventListener('pointerdown', (event) => {
    if (event.button === 2) return;
    canvas.focus();
    const cell = viewCellAt(event);
    const found = cell ? bannerAt(cell) : -1;
    if (event.button === 0 && found >= 0) {
      state.selected = found;
      state.drag = { index: found, cell: { x: cell.x, z: cell.z } };
      syncSelectors();
      try { canvas.setPointerCapture(event.pointerId); } catch (e) { /* ignore */ }
      return;
    }
    if (event.button !== 0) return;
    state.pan = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      startCx: state.view.cx,
      startCz: state.view.cz,
      moved: false,
      cell,
    };
    try { canvas.setPointerCapture(event.pointerId); } catch (e) { /* ignore */ }
    canvas.style.cursor = 'grab';
  });
  canvas.addEventListener('pointerup', (event) => {
    if (state.drag) {
      const cell = viewCellAt(event) || state.drag.cell;
      const banner = state.banners[state.drag.index];
      if (banner) {
        banner.x = cell.x;
        banner.z = cell.z;
      }
      state.drag = null;
      state.hover = null;
      updateSummary();
      return;
    }
    if (state.pan) {
      const pan = state.pan;
      state.pan = null;
      if (!pan.moved && pan.cell) schedulePlace(pan.cell);
      state.hover = viewCellAt(event);
    }
  });
  canvas.addEventListener('pointercancel', () => {
    state.drag = null;
    state.pan = null;
  });
  canvas.addEventListener('contextmenu', (event) => {
    event.preventDefault();
    const cell = viewCellAt(event);
    if (!cell) return;
    const found = bannerAt(cell);
    if (found >= 0) {
      state.selected = found;
      rotateBanner(state.banners[found]);
      syncSelectors();
    }
  });
  canvas.addEventListener('keydown', (event) => {
    if (event.key === 'Delete' || event.key === 'Backspace') {
      if (state.selected >= 0) {
        state.banners.splice(state.selected, 1);
        state.selected = state.banners.length ? Math.min(state.selected, state.banners.length - 1) : -1;
        syncSelectors();
        updateSummary();
        event.preventDefault();
      }
    } else if (event.key === 'r' || event.key === 'R') {
      rotateBanner(selectedBanner());
      syncSelectors();
      event.preventDefault();
    }
  });
  canvas.addEventListener('wheel', (event) => {
    event.preventDefault();
    zoomAt(event, event.deltaY);
  }, { passive: false });
  canvas.addEventListener('dblclick', (event) => {
    event.preventDefault();
    if (state.pendingPlace) {
      clearTimeout(state.pendingPlace);
      state.pendingPlace = null;
    }
    resetView();
  });

  const composer = createBannerComposer(
    () => selectedBanner(),
    (bannerData) => {
      let banner = selectedBanner();
      if (!banner) {
        banner = {
          x: 0, z: 0, patterns: [], baseColor: bannerData.baseColor,
          facing: state.draft.facing,
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
    },
    rangeInfo);
  composer.element.id = 'claim-banner-composer';
  section.append(composer.element);

  // 首面旗帜：让画布一打开就有可操作对象。
  state.banners.push({
    x: 0,
    z: 0,
    patterns: [{ id: state.draft.patternId, color: state.draft.color }],
    baseColor: state.draft.baseColor,
    facing: state.draft.facing,
  });
  state.selected = 0;
  syncSelectors();
  updateZoomLabel();
  state.raf = requestAnimationFrame(draw);
  updateSummary();

  const api = {
    redraw: () => {
      syncSelectors();
      updateSummary();
      composer.refresh();
    },
    selectPattern: (id) => {
      if (!CLAIM_PATTERNS[id]) return;
      if (!state.banners.length) {
        state.draft.patternId = id;
        placeBanner({ x: 0, z: 0 });
      } else {
        const banner = selectedBanner();
        if (banner) firstLayer(banner).id = id;
        state.draft.patternId = id;
      }
      syncSelectors();
      updateSummary();
      canvas.classList.remove('claim-sim-flash');
      void canvas.offsetWidth;
      canvas.classList.add('claim-sim-flash');
      setTimeout(() => canvas.classList.remove('claim-sim-flash'), 1600);
    },
  };
  section.claimPreview = api;
  if (window.SamsaraUI) window.SamsaraUI.state.claimPreview = api;
  return section;
}

/** Bresenham：把射线端点之间的区块也纳入单旗射线预览。 */
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
  if (!onlyFile && state.invalidCurves && state.invalidCurves.size > 0) {
    setMessage('轨迹保留曲线有非法值，已阻止保存', 'error');
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
    state.invalidCurves.clear();
  }
  render();
  renderClaimRange();
  if (onlyFile === 'claim-range') {
    setMessage('圈地范围已保存，已有圈地已按新范围重算', 'ok');
    // claims.json 可能没有变化，但几何依赖 claim-range；地图页下次打开必须重新拉取。
    window.SamsaraMap?.markStale?.();
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
    window.SamsaraMap?.markStale?.();
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
  const section = document.querySelector('.section-tabs');
  const headerH = header ? header.offsetHeight : 0;
  const sectionH = section ? section.offsetHeight : 0;
  const base = headerH + sectionH + 14;
  if (nav) {
    const top = parseFloat(getComputedStyle(nav).top);
    if (Number.isFinite(top)) return Math.max(base, top);
  }
  return base;
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

function clearNav() {
  const nav = $('global-nav');
  if (nav) {
    nav.replaceChildren();
    nav.hidden = true;
  }
  const shell = document.querySelector('main.app-shell');
  if (shell) shell.classList.add('no-nav');
  watchNav([]);
}

function clearPageSurfaces() {
  // 跨页浮窗不属于任何 panel；切页时统一关闭，避免上一页的 dialog 残留在屏幕上。
  for (const dialog of document.querySelectorAll('dialog[open]')) {
    if (dialog.id === 'token-dialog') continue;
    try {
      dialog.close();
    } catch (error) {
      dialog.removeAttribute('open');
    }
  }
}

function leavePage(name) {
  if (!name) return;
  const definition = pageKit && pageKit.get(name);
  const context = state.activePageContext;
  if (definition) {
    try {
      definition.unmount?.(context);
    } catch (error) {
      console.warn('[page] unmount failed:', name, error);
    }
    try {
      definition.destroy?.(context);
    } catch (error) {
      console.warn('[page] destroy failed:', name, error);
    }
  }
  if (state.activePageContext?.scope) {
    try {
      state.activePageContext.scope.dispose();
    } catch (error) {
      console.warn('[page] scope dispose failed:', name, error);
    }
  }
  state.activePage = null;
  state.activePageContext = null;
}

function enterPage(name) {
  if (!pageKit) return false;
  const definition = pageKit.get(name);
  if (!definition) return false;
  state.pageToken += 1;
  const scope = pageKit.createScope(name);
  const context = {
    name,
    root: document.getElementById('panel-' + name),
    scope,
    token: state.pageToken,
    ui: window.SamsaraUI,
  };
  state.activePage = name;
  state.activePageContext = context;
  try {
    definition.mount?.(context);
    definition.buildNav?.(context);
  } catch (error) {
    console.warn('[page] mount failed:', name, error);
    scope.dispose();
    state.activePage = null;
    state.activePageContext = null;
    return false;
  }
  return true;
}

function activeNavPage() {
  if (document.body.classList.contains('server-section')) return 'server';
  return state.tab || '';
}

function buildNav(title, items, targets, options) {
  const nav = $('global-nav');
  if (!nav) return;
  const opts = options || {};
  // 页面切走后，隐藏页面的异步刷新不能覆盖当前页面的页内导航。
  if (opts.owner && opts.owner !== activeNavPage()) return;
  const targetList = Array.isArray(targets) ? targets : [];
  if (targetList.length) {
    const visible = targetList.some((target) => {
      const el = target && target.el;
      return el && typeof el.getClientRects === 'function' && el.getClientRects().length > 0;
    });
    if (!visible) return;
  }
  nav.hidden = false;
  const shell = document.querySelector('main.app-shell');
  if (shell) shell.classList.remove('no-nav');
  nav.replaceChildren();
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
  leavePage(previous);
  state.tab = name;
  for (const panel of panelIds) {
    const el = document.getElementById(panel);
    if (el) el.hidden = panel !== 'panel-' + name;
  }
  for (const tab of document.querySelectorAll('.tab[data-panel]')) {
    tab.classList.toggle('active', tab.dataset.panel === 'panel-' + name);
  }
  try {
    history.replaceState(null, '', window.SamsaraUseRouter
      ? (location.pathname + location.search)
      : '#' + name);
  } catch (e) { /* file:// 等场景忽略 */ }
  updateTabActions(name);
  // 导航是跨页共享的 DOM；先清空，页面自己调用 buildNav 时再显示。
  clearNav();
  clearPageSurfaces();
  const pageHandled = enterPage(name);
  if (pageHandled) {
    // 已注册页面由 mount/buildNav 接管。
  } else if (name === 'config') {
    render();
  } else if (name === 'config-editor') {
    window.SamsaraConfigEditor?.activate();
  } else if (name === 'claim-range') {
    renderClaimRange();
  } else if (name === 'map') {
    window.SamsaraMap?.activate();
  } else if (name === 'announcements') {
    window.SamsaraAnnouncements?.activate();
  } else if (name === 'world-stack') {
    window.SamsaraWorldStack?.activate();
  } else if (name === 'identity') {
    window.SamsaraIdentity?.activate();
  } else if (name === 'output') {
    window.SamsaraOutput?.activate();
  } else if (name === 'permissions') {
    window.SamsaraPermissions?.activate();
  } else if (name === 'lottery') {
    window.samsaraLottery?.activate();
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
    title: '高级配置',
    hint: '保存会写入全部配置并热重载',
    save: '保存并重载',
  },
  'config-editor': {
    title: '配置编辑器',
    hint: 'PageSpec/FormSpec 驱动；第三方与服务器配置需预览确认，写入前自动备份',
    save: '预览并保存',
  },
  'claim-range': {
    title: '圈地范围',
    hint: '保存会写入 claim-range.yml，并立即重算已有圈地',
    save: '保存并重算',
  },
  map: {
    title: '地图',
    hint: '只读扫描存档；世界栈总览显示在线玩家（2 秒刷新）；切世界后点“重新读取”',
    save: null,
  },
  announcements: {
    title: '公告',
    hint: '编辑后保存；发送前先预览，确认后才推送；定时/进服触发由服务端执行',
    save: '保存公告',
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
  lottery: {
    title: '经济业务',
    hint: '死人乐透、服务器基金与保险；只读总览，开奖/退款需二次确认',
    save: null,
  },
};

window.SamsaraTabActions = TAB_ACTIONS;

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
  if (state.tab === 'config-editor') return window.SamsaraConfigEditor?.save?.();
  if (state.tab === 'announcements') return window.SamsaraAnnouncements?.save?.();
  if (state.tab === 'identity') return window.SamsaraIdentity?.save?.();
  if (state.tab === 'output') return window.SamsaraOutput?.save?.();
  if (state.tab === 'permissions') return window.SamsaraPermissions?.saveOwners?.();
  if (state.tab === 'map') return window.SamsaraMap?.reload?.();
  if (state.tab === 'lottery') return window.samsaraLottery?.load?.();
  if (state.tab === 'claim-range') return saveConfig('claim-range');
  return saveConfig();
}

async function reloadActive() {
  if (state.tab === 'config-editor') return window.SamsaraConfigEditor?.reload?.();
  if (state.tab === 'announcements') return window.SamsaraAnnouncements?.reload?.();
  if (state.tab === 'identity') return window.SamsaraIdentity?.reload?.();
  if (state.tab === 'output') return window.SamsaraOutput?.reload?.();
  if (state.tab === 'permissions') return window.SamsaraPermissions?.reload?.();
  if (state.tab === 'map') return window.SamsaraMap?.reload?.();
  if (state.tab === 'lottery') return window.samsaraLottery?.load?.();
  if (state.tab === 'world-stack') return window.SamsaraWorldStack?.reload?.();
  return reloadConfig();
}

// ---- 事件 ----

/** 页面懒挂载后，某些旧页面元素可能还没进 DOM；绑定事件时统一判空。 */
function on(id, event, handler, options) {
  const node = $(id);
  if (node) node.addEventListener(event, handler, options);
}

on('token-connect', 'click', connect);
on('token-form', 'submit', (event) => {
  event.preventDefault();
  connect();
});
on('token-dialog', 'cancel', (event) => event.preventDefault());
on('save', 'click', saveActive);
on('reload', 'click', reloadActive);
on('action-default', 'click', () => window.SamsaraOutput?.restoreDefault?.());
// #search 位于配置页，页面懒挂载后才出现；用事件委托保证它挂载后仍能筛选。
document.addEventListener('input', (event) => {
  if (event.target && event.target.id === 'search') render();
});

// 子元素有滚动条时只滚子元素；到边界也不带动父元素。没有滚动条才交给父/页面。
document.addEventListener('wheel', (event) => {
  if (event.defaultPrevented || event.ctrlKey || event.metaKey) return;
  let node = event.target instanceof Element ? event.target : event.target?.parentElement;
  while (node && node !== document.body && node !== document.documentElement) {
    const style = getComputedStyle(node);
    if (/(auto|scroll|overlay)/.test(style.overflowY)
        && node.scrollHeight > node.clientHeight + 1) {
      const dy = event.deltaY;
      const canScroll = dy < 0
        ? node.scrollTop > 0
        : node.scrollTop + node.clientHeight < node.scrollHeight - 1;
      if (!canScroll) event.preventDefault();
      return;
    }
    node = node.parentElement;
  }
}, { passive: false, capture: true });

// 染料距离、模式、上限等任一输入变化后，保护形状预览立即重算。
// 事件派发在 body 上并冒泡；这里用捕获阶段保证无论目标在哪个子树都能收到。
document.addEventListener('samsara:config-changed', () => {
  state.claimPreview?.redraw?.();
}, true);

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

function onConnected(fn) {
  connectedHooks.push(fn);
}

async function runConnectHooks() {
  for (const fn of connectHooks) {
    try {
      await fn();
    } catch (e) {
      // 各面板自己显示错误；这里只避免连接流程中断
    }
  }
  // 所有页面都加载完后再同步一次导航，避免晚注册的页面覆盖当前页导航。
  for (const fn of connectedHooks) {
    try {
      await fn();
    } catch (e) {
      // 同上
    }
  }
  const inServerSection = window.SamsaraServer
      && window.SamsaraServer.state
      && window.SamsaraServer.state.section === 'server';
  if (!inServerSection) {
    setActiveTab(state.tab || 'config');
  }
  refreshCounts();
}

window.SamsaraUI = {
  state,
  api,
  setMessage,
  onConnect,
  onConnected,
  runConnectHooks,
  getToken: () => state.token,
  authHeaders,
  setConnection,
  setActiveTab,
  isActiveTab: (name) => state.tab === name,
  registerPage: (name, definition) => pageKit?.register(name, definition),
  unregisterPage: (name) => pageKit?.unregister(name),
  activePage: () => state.activePage,
  pageScope: () => state.activePageContext?.scope || null,
  pageToken: () => state.pageToken,
  buildNav,
  clearNav,
  setNavActive,
  watchNav,
  scrollToTarget,
  refreshCounts,
  setTabCount,
  effectiveValue,
  authMessage,
};

const initialTab = (location.hash || '').replace('#', '');
if (['config', 'config-editor', 'claim-range', 'identity', 'output', 'permissions', 'map', 'announcements', 'world-stack', 'lottery'].includes(initialTab)) {
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
