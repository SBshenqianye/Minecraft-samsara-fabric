'use strict';

// 本地配置面板：所有文本都用 textContent 写入，绝不使用 innerHTML。
// token 只存 sessionStorage，不进 URL、不写日志。

const state = {
  token: sessionStorage.getItem('samsara-token') || '',
  model: null,
  edits: new Map(), // `${file}:${path}` -> value
};

const connectHooks = [];

const $ = (id) => document.getElementById(id);

function setMessage(text, kind) {
  const el = $('message');
  el.textContent = text || '';
  el.className = 'message' + (kind ? ' ' + kind : '');
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

async function connect() {
  state.token = $('token').value.trim();
  if (!state.token) {
    setMessage('请先填写 web.yml 里的 token（服务器控制台启动时会打印）', 'error');
    return;
  }
  sessionStorage.setItem('samsara-token', state.token);
  await loadConfig();
  await loadStatus();
  await runConnectHooks();
}

async function loadConfig() {
  try {
    state.model = await api('/api/config');
    state.edits.clear();
    render();
    setMessage('已读取 ' + state.model.files.length + ' 个配置文件', 'ok');
  } catch (e) {
    if (e.status === 401) {
      setMessage('token 不正确，请重新填写', 'error');
    } else {
      setMessage('读取配置失败：' + e.message, 'error');
    }
  }
}

async function loadStatus() {
  try {
    const status = await api('/api/status');
    const list = $('status-list');
    list.replaceChildren();
    const rows = [
      ['模组版本', status.version],
      ['Minecraft', status.minecraft],
      ['配置根', status.configRoot],
      ['世界数据根', status.worldDataRoot],
      ['最近警告', (status.warnings || []).join('；') || '无'],
    ];
    for (const [key, value] of rows) {
      const dt = document.createElement('dt');
      dt.textContent = key;
      const dd = document.createElement('dd');
      dd.textContent = value == null ? '—' : String(value);
      list.append(dt, dd);
    }
    $('status').hidden = false;
  } catch (e) {
    setMessage('读取状态失败：' + e.message, 'error');
  }
}

function valueKey(fileId, path) {
  return fileId + ':' + path;
}

function currentValue(fileId, entry) {
  const key = valueKey(fileId, entry.path);
  return state.edits.has(key) ? state.edits.get(key) : entry.value;
}

function markEdited(fileId, entry, value) {
  state.edits.set(valueKey(fileId, entry.path), value);
}

function render() {
  const container = $('files');
  container.replaceChildren();
  if (!state.model) return;

  const query = $('search').value.trim().toLowerCase();
  let shown = 0;

  for (const file of state.model.files) {
    const matching = file.entries.filter((entry) => {
      const haystack = (file.id + ' ' + entry.path + ' ' + entry.description + ' '
          + entry.group).toLowerCase();
      return !query || haystack.includes(query);
    });
    if (matching.length === 0) continue;
    shown += matching.length;

    const section = document.createElement('details');
    section.className = 'file';
    section.open = true;
    const summary = document.createElement('summary');
    summary.className = 'file-summary';
    const title = document.createElement('span');
    title.className = 'file-title';
    title.textContent = file.name;
    const path = document.createElement('span');
    path.className = 'file-path muted';
    path.textContent = file.path;
    summary.append(title, path);
    section.append(summary);

    const groups = new Map();
    for (const entry of matching) {
      if (!groups.has(entry.group)) groups.set(entry.group, []);
      groups.get(entry.group).push(entry);
    }
    for (const [groupName, entries] of groups) {
      const groupEl = document.createElement('details');
      groupEl.className = 'group';
      groupEl.open = true;
      const groupSummary = document.createElement('summary');
      groupSummary.className = 'group-title';
      groupSummary.textContent = groupName;
      groupEl.append(groupSummary);
      for (const entry of entries) groupEl.append(renderEntry(file, entry));
      section.append(groupEl);
    }
    container.append(section);
  }

  $('summary').textContent = shown + ' / ' + totalEntries() + ' 项';
}

function totalEntries() {
  return state.model ? state.model.files.reduce((sum, f) => sum + f.entries.length, 0) : 0;
}

function renderEntry(file, entry) {
  const row = document.createElement('div');
  row.className = 'entry' + (entry.restart ? ' restart' : '');

  const key = document.createElement('div');
  const keyText = document.createElement('div');
  keyText.className = 'entry-key';
  keyText.textContent = entry.path;
  const desc = document.createElement('div');
  desc.className = 'entry-desc';
  desc.textContent = entry.description || '';
  key.append(keyText, desc);

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
  reset.className = 'ghost';
  reset.textContent = '恢复默认';
  reset.addEventListener('click', () => {
    markEdited(file.id, entry, entry.default);
    render();
  });
  actions.append(reset);

  row.append(key, control, actions);
  return row;
}

function buildInput(file, entry) {
  const value = currentValue(file.id, entry);
  if (entry.sensitive) {
    const input = document.createElement('input');
    input.type = 'password';
    input.placeholder = entry.configured ? '已配置（留空表示不修改）' : '尚未生成';
    input.addEventListener('input', () => {
      if (input.value) markEdited(file.id, entry, input.value);
    });
    return input;
  }
  switch (entry.type) {
    case 'boolean': {
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.checked = value === true;
      input.addEventListener('change', () => markEdited(file.id, entry, input.checked));
      return input;
    }
    case 'int':
    case 'double': {
      const input = document.createElement('input');
      input.type = 'number';
      if (entry.min != null) input.min = String(entry.min);
      if (entry.max != null) input.max = String(entry.max);
      input.step = entry.type === 'int' ? '1' : 'any';
      input.value = value == null ? '' : String(value);
      input.addEventListener('input', () => {
        markEdited(file.id, entry, input.value === '' ? null : Number(input.value));
      });
      return input;
    }
    case 'enum': {
      const select = document.createElement('select');
      for (const choice of entry.choices) {
        const option = document.createElement('option');
        option.value = choice;
        option.textContent = choice;
        select.append(option);
      }
      select.value = value == null ? entry.default : value;
      select.addEventListener('change', () => markEdited(file.id, entry, select.value));
      return select;
    }
    case 'string-list': {
      return window.SamsaraStructured.stringList(
          Array.isArray(value) ? value : [],
          (next) => markEdited(file.id, entry, next));
    }
    case 'level-map': {
      const map = value && typeof value === 'object' ? value : {};
      return window.SamsaraStructured.keyValueMap(
          map, ['user', 'op', 'owner', 'config'],
          (next) => markEdited(file.id, entry, next));
    }
    default: {
      const input = document.createElement('input');
      input.type = 'text';
      input.value = value == null ? '' : String(value);
      input.addEventListener('input', () => markEdited(file.id, entry, input.value));
      return input;
    }
  }
}

async function save() {
  if (!state.model) {
    setMessage('还没有读取配置', 'error');
    return;
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
        return;
      }
      setMessage('保存失败：' + e.message, 'error');
      return;
    }
  }
  state.edits.clear();
  render();
  setMessage('已保存并重载 ' + saved + ' 个文件', 'ok');
  await loadStatus();
}

async function reload() {
  try {
    const result = await api('/api/reload', { method: 'POST' });
    state.model = result.config;
    state.edits.clear();
    render();
    setMessage(result.message || '已重新读取', 'ok');
    await loadStatus();
  } catch (e) {
    setMessage('重新读取失败：' + e.message, 'error');
  }
}

function cssEscape(text) {
  return String(text).replace(/["\\]/g, '\\$&');
}

$('connect').addEventListener('click', connect);
$('save').addEventListener('click', save);
$('reload').addEventListener('click', reload);
$('search').addEventListener('input', render);
$('token').value = state.token;

for (const tab of document.querySelectorAll('.tab')) {
  tab.addEventListener('click', () => {
    for (const other of document.querySelectorAll('.tab')) {
      other.classList.toggle('active', other === tab);
    }
    const target = tab.dataset.panel;
    for (const panel of ['panel-config', 'panel-output', 'panel-permissions']) {
      document.getElementById(panel).hidden = panel !== target;
    }
    if (target === 'panel-output') window.SamsaraOutput?.ensureLoaded();
    if (target === 'panel-permissions') window.SamsaraPermissions?.ensureLoaded();
  });
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
}

window.SamsaraUI = {
  state,
  api,
  setMessage,
  onConnect,
  runConnectHooks,
  getToken: () => state.token,
};

if (state.token) {
  loadConfig().then(loadStatus).then(runConnectHooks);
}
