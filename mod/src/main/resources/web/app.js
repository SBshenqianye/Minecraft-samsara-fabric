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
  scrollPositions: { config: 0, identity: 0, output: 0, permissions: 0 },
  navScrollPositions: { config: 0, identity: 0, output: 0, permissions: 0 },
};

const connectHooks = [];
const panelIds = ['panel-identity', 'panel-config', 'panel-output', 'panel-permissions'];
const tabNames = { 'panel-identity': 'identity', 'panel-config': 'config', 'panel-output': 'output', 'panel-permissions': 'permissions' };

const $ = (id) => document.getElementById(id);

function setMessage(text, kind) {
  const el = $('message');
  if (!el) return;
  el.textContent = text || '';
  el.className = 'message' + (kind ? ' ' + kind : '');
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

async function api(path, options) {
  const response = await fetch(path, Object.assign({}, options, {
    headers: authHeaders(Object.assign({ 'Content-Type': 'application/json' },
        options && options.headers ? options.headers : {})),
  }));
  let body = null;
  try { body = await response.json(); } catch (e) { body = null; }
  if (!response.ok) {
    const error = new Error((body && body.error) || ('HTTP ' + response.status));
    error.status = response.status;
    error.body = body;
    throw error;
  }
  return body;
}

function authMessage(error, fallback) {
  if (error && error.status === 401) {
    return 'token 无效：请填写 web.yml 里的 token 后点“连接”。';
  }
  return fallback + (error && error.message ? '：' + error.message : '');
}

async function connect() {
  state.token = $('token').value.trim();
  if (!state.token) {
    setConnection('bad', '未填写 token');
    setMessage('请先填写 web.yml 里的 token（服务器控制台启动时会打印）', 'error');
    return;
  }
  sessionStorage.setItem('samsara-token', state.token);
  setConnection('', '连接中…');
  const ok = await loadConfig();
  await loadStatus();
  await runConnectHooks();
  if (ok) {
    setConnection('ok', '已连接');
  } else {
    setConnection('bad', '连接失败');
  }
}

async function loadConfig() {
  try {
    state.model = await api('/api/config');
    state.edits.clear();
    render();
    setMessage('已读取 ' + state.model.files.length + ' 个配置文件', 'ok');
    setConnection('ok', '已连接');
    return true;
  } catch (e) {
    setConnection('bad', e.status === 401 ? 'token 无效' : '连接失败');
    setMessage(authMessage(e, '读取配置失败'), 'error');
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
}

function isMissingValue(entry) {
  return entry.value === undefined || entry.value === null;
}

function sameJson(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

// ---- 配置渲染 ----

function render() {
  const container = $('files');
  if (!container) return;
  container.replaceChildren();
  if (!state.model) return;

  const query = $('search').value.trim().toLowerCase();
  let shown = 0;
  const navItems = [];
  const navTargets = [];

  for (const file of state.model.files) {
    const matching = file.entries.filter((entry) => {
      const haystack = (file.id + ' ' + entry.path + ' ' + entry.description + ' '
          + entry.group).toLowerCase();
      return !query || haystack.includes(query);
    });
    if (matching.length === 0) continue;
    shown += matching.length;

    const fileEl = document.createElement('details');
    fileEl.className = 'file-block';
    fileEl.open = true;
    fileEl.id = 'cfg-file-' + encodeURIComponent(file.id);

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
    container.append(fileEl);
    navItems.push({ label: file.name, count: matching.length, target: fileEl.id, children: navGroupChildren });
  }

  $('summary').textContent = shown + ' / ' + totalEntries() + ' 项';
  setTabCount('config', totalEntries());
  if (state.tab === 'config') {
    buildNav('配置导航', navItems, navTargets);
  }
}

function totalEntries() {
  return state.model ? state.model.files.reduce((sum, f) => sum + f.entries.length, 0) : 0;
}

function renderEntry(file, entry) {
  const row = document.createElement('div');
  row.className = 'entry' + (entry.restart ? ' restart' : '');
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
        window.SamsaraParticles?.attach(canvas, select.value, { background: theme });
      }
      return wrap;
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

async function saveConfig() {
  if (!state.model) {
    setMessage('还没有读取配置', 'error');
    return false;
  }
  setMessage('');
  let saved = 0;
  for (const file of state.model.files) {
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
  state.edits.clear();
  render();
  setMessage('已保存并重载 ' + saved + ' 个文件', 'ok');
  await loadStatus();
  return true;
}

async function reloadConfig() {
  try {
    const result = await api('/api/reload', { method: 'POST' });
    state.model = result.config;
    state.edits.clear();
    render();
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

function scrollToTarget(id) {
  const target = document.getElementById(id);
  if (!target) return;
  target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  setNavActive(id);
}

function buildNav(title, items, targets) {
  const nav = $('global-nav');
  if (!nav) return;
  nav.replaceChildren();
  const heading = document.createElement('div');
  heading.className = 'nav-title';
  heading.textContent = title;
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
  let ticking = false;
  state.navScrollHandler = () => {
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(() => {
      ticking = false;
      let current = targets[0];
      for (const target of targets) {
        if (target.el && target.el.getBoundingClientRect().top <= 170) current = target;
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
  const map = { config: 'config-count', identity: 'identity-count', output: 'output-count', permissions: 'perm-count' };
  const el = $(map[panel]);
  if (el) el.textContent = String(count == null ? 0 : count);
}

function refreshCounts() {
  if (state.model) setTabCount('config', totalEntries());
  window.SamsaraIdentity?.refreshCount?.();
  window.SamsaraOutput?.refreshCount?.();
  window.SamsaraPermissions?.refreshCount?.();
}

async function saveActive() {
  if (state.tab === 'identity') return window.SamsaraIdentity?.save?.();
  if (state.tab === 'output') return window.SamsaraOutput?.save?.();
  if (state.tab === 'permissions') return window.SamsaraPermissions?.saveOwners?.();
  return saveConfig();
}

async function reloadActive() {
  if (state.tab === 'identity') return window.SamsaraIdentity?.reload?.();
  if (state.tab === 'output') return window.SamsaraOutput?.reload?.();
  if (state.tab === 'permissions') return window.SamsaraPermissions?.reload?.();
  return reloadConfig();
}

// ---- 事件 ----

$('connect').addEventListener('click', connect);
$('save').addEventListener('click', saveActive);
$('reload').addEventListener('click', reloadActive);
$('action-default').addEventListener('click', () => window.SamsaraOutput?.restoreDefault?.());
$('search').addEventListener('input', render);
$('token').value = state.token;

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
if (['config', 'identity', 'output', 'permissions'].includes(initialTab)) {
  setActiveTab(initialTab);
} else {
  updateTabActions('config');
}

if (state.token) {
  loadConfig().then(loadStatus).then(runConnectHooks).then(() => {
    if (state.tab !== 'config') setActiveTab(state.tab);
  });
} else {
  setConnection('', '未连接');
}
