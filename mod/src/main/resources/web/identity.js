'use strict';

// 身份组卡片：账号 UUID 与 data UUID 分角色拖拽。
// 正版账号/离线账号入口全局唯一；data UUID 允许复制到多个组（共享同一份玩家数据）。

const I = {
  model: null,
  groups: [],
  settings: { enabled: false, sync: true, maxOwners: 32 },
  candidates: [],
  extraCandidates: [],
  dataList: [],
  labels: {},
  generated: [],
  names: {},
  types: {},
  online: {},
  onlineMode: true,
  drag: null,
  seq: 0,
  dirty: false,
  nameTimer: null,
  uuidTimer: null,
  uuidContext: null,
  selectedUuid: null,
  rebindGroup: null,
  assign: null,
  pool: { text: '', hideProbe: true, hideUnknown: true },
};

const $i = (id) => document.getElementById(id);
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function identityMessage(text, kind) {
  const el = $i('identity-message');
  if (!el) return;
  el.textContent = text || '';
  el.className = 'message' + (kind ? ' ' + kind : '');
}

function shortUuid(uuid) {
  if (!uuid) return '—';
  return uuid.length > 13 ? uuid.slice(0, 8) + '…' + uuid.slice(-4) : uuid;
}

function nextGroupId() {
  I.seq += 1;
  return 'g' + I.seq;
}

function isUuid(value) {
  return UUID_RE.test(value || '');
}

async function loadIdentity() {
  try {
    const model = await window.SamsaraUI.api('/api/identity');
    applyModel(model);
    refreshDataList();
    identityMessage('已读取身份组配置', 'ok');
    return true;
  } catch (e) {
    identityMessage(window.SamsaraUI.authMessage(e, '读取身份组失败'), 'error');
    return false;
  }
}

/**
 * 拉取磁盘上所有 data UUID。
 *
 * <p>这是「未分组 data UUID」的持久来源：文件在，列表就在；
 * 不再依赖前端内存 I.generated，刷新页面也不会把闲置 data 弄丢。
 */
async function refreshDataList() {
  try {
    const list = await window.SamsaraUI.api('/api/identity/data/list');
    I.dataList = Array.isArray(list.data) ? list.data : [];
    renderPool();
    renderDatalists();
  } catch (e) {
    // 老版本或身份服务未初始化时保持空列表，不影响卡片编辑。
  }
}

function dataMeta(uuid) {
  return I.labels[uuid] || null;
}

function dataDisplayName(uuid) {
  const meta = dataMeta(uuid);
  if (meta && meta.name) return meta.name;
  const row = I.dataList.find((item) => item.uuid === uuid);
  if (row && row.label) return row.label;
  return '';
}

async function editDataLabel(uuid) {
  const meta = dataMeta(uuid) || {};
  const name = window.prompt('给这个 data UUID 起个名字（留空清除）\n'
    + uuid + '\n格式建议：所有者 + 用途 + 日期，例如「小明 主号 2026-01」',
  meta.name || dataDisplayName(uuid) || '');
  if (name === null) return;
  const note = window.prompt('备注（可留空）\n用于区分借出/归还记录', meta.note || '');
  if (note === null) return;
  try {
    const result = await window.SamsaraUI.api('/api/identity/data/label', {
      method: 'POST',
      body: JSON.stringify({ uuid, name: name.trim(), note: note.trim() }),
    });
    if (!result.ok) throw new Error(result.error || '保存失败');
    I.labels[uuid] = { name: name.trim(), note: note.trim() };
    await refreshDataList();
    renderGroups();
    identityMessage(result.message || '已保存 data UUID 元数据', 'ok');
  } catch (e) {
    identityMessage(window.SamsaraUI.authMessage(e, '保存 data UUID 元数据失败'), 'error');
  }
}

/** 打开全局 Vue 背包弹窗（身份组 data UUID）。 */
function openGroupInventory(dataUuid, groupName) {
  if (!dataUuid) {
    identityMessage('该身份组还没有 data UUID', 'error');
    return;
  }
  window.dispatchEvent(new CustomEvent('samsara:identity-inventory', {
    detail: { data: dataUuid, name: groupName || '' },
  }));
}

function ensureIdentityLoaded() {
  if (!I.model && window.SamsaraUI.getToken()) loadIdentity();
}

function applyModel(model) {
  I.model = model;
  const config = model.config || {};
  I.settings = {
    enabled: !!config.enabled,
    sync: config.syncPlayerData !== false,
    maxOwners: config.maxOwners || 32,
  };
  I.groups = (config.groups || []).map((group) => ({
    name: group.name || '',
    data: group.data || '',
    premium: group.premium || null,
    offline: group.offline || null,
    note: group.note || '',
    _id: nextGroupId(),
    _originalName: group.name || '',
  }));
  I.candidates = model.candidates || [];
  I.labels = config.dataLabels || {};
  I.onlineMode = model.onlineMode !== false;
  I.dirty = false;
  renderAll();
}

function renderAll() {
  renderSettings();
  renderGroups();
  renderPool();
  renderDatalists();
  renderNav();
  refreshNames();
  window.SamsaraUI.setTabCount('identity', I.groups.length);
}

function renderDatalists() {
  const candidates = $i('identity-candidates');
  const dataList = $i('identity-data-uuids');
  if (candidates) {
    candidates.replaceChildren();
    const seen = new Set();
    for (const item of [...I.candidates, ...I.extraCandidates]) {
      if (!item.uuid || seen.has(item.uuid)) continue;
      seen.add(item.uuid);
      const option = document.createElement('option');
      option.value = item.uuid;
      option.label = (item.name || shortUuid(item.uuid))
          + (item.online ? ' · 当前在线' : '') + ' · ' + shortUuid(item.uuid);
      candidates.append(option);
    }
  }
  if (dataList) {
    dataList.replaceChildren();
    const seen = new Set();
    const values = [];
    for (const group of I.groups) if (group.data) values.push(group.data);
    for (const uuid of I.generated) values.push(uuid);
    for (const key of Object.keys(I.model?.states || {})) values.push(key);
    for (const uuid of values) {
      if (!uuid || seen.has(uuid)) continue;
      seen.add(uuid);
      const option = document.createElement('option');
      option.value = uuid;
      const candidate = [...I.candidates, ...I.extraCandidates]
          .find((item) => item.uuid === uuid);
      const label = candidate
          ? (candidate.name || shortUuid(uuid)) + ' · ' + typeLabel(candidate.type) + '数据'
          : (I.names[uuid] || '空白 data');
      option.label = label + ' · ' + shortUuid(uuid);
      dataList.append(option);
    }
  }
}

function renderSettings() {
  const enabled = $i('identity-enabled');
  const sync = $i('identity-sync');
  const maxOwners = $i('identity-max-owners');
  renderSettingToggle(enabled, I.settings.enabled, (value) => {
    I.settings.enabled = value;
    I.dirty = true;
  });
  renderSettingToggle(sync, I.settings.sync, (value) => {
    I.settings.sync = value;
    I.dirty = true;
  });
  if (maxOwners) maxOwners.value = String(I.settings.maxOwners);
  const path = $i('identity-path');
  const root = $i('identity-root');
  if (path) path.textContent = '配置文件：' + (I.model?.path || '—');
  if (root) root.textContent = '运行时数据：' + (I.model?.identityRoot || '—');
}

function renderSettingToggle(host, value, onChange) {
  if (!host || !window.SamsaraUIKit) return;
  host.textContent = '';
  const toggle = window.SamsaraUIKit.toggle(value === true, onChange,
      { onLabel: '启用', offLabel: '关闭' });
  toggle.classList.add('compact');
  host.appendChild(toggle);
}

function findExisting(group) {
  if (!group || !group._originalName) return null;
  return (I.model?.config?.groups || []).find((item) => item.name === group._originalName) || null;
}

function sharedDataCount(group) {
  return I.groups.filter((item) => item.data && item.data === group.data).length;
}

function stateOf(group) {
  return (I.model?.states || {})[group.data] || null;
}

function renderGroups() {
  const container = $i('identity-groups');
  if (!container) return;
  container.replaceChildren();
  if (!I.groups.length) {
    const empty = document.createElement('div');
    empty.className = 'panel-sub';
    empty.textContent = '还没有身份组。点右上角“新增组”创建一张卡片。';
    container.append(empty);
    return;
  }
  I.groups.forEach((group, index) => container.append(groupCard(group, index)));
}

function groupCard(group, index) {
  const card = document.createElement('article');
  card.className = 'identity-card';
  card.id = 'identity-group-' + group._id;

  const head = document.createElement('div');
  head.className = 'card-head';
  const titleRow = document.createElement('div');
  titleRow.className = 'card-title-row';
  const avatar = document.createElement('img');
  avatar.className = 'identity-avatar';
  avatar.alt = '';
  avatar.loading = 'lazy';
  avatar.title = group.name || '身份组头像';
  const skin = groupSkin(group);
  if (skin.uuid || skin.name) {
    const params = new URLSearchParams();
    if (skin.uuid) params.set('uuid', skin.uuid);
    if (skin.name) params.set('name', skin.name);
    avatar.src = '/api/user/map/head?' + params.toString();
    avatar.addEventListener('error', () => { avatar.style.visibility = 'hidden'; });
  } else {
    avatar.style.visibility = 'hidden';
  }
  const badgeIndex = document.createElement('span');
  badgeIndex.className = 'group-index';
  badgeIndex.textContent = String(index + 1).padStart(2, '0');
  const nameInput = document.createElement('input');
  nameInput.className = 'group-name';
  nameInput.value = group.name;
  nameInput.placeholder = '组名（必填）';
  nameInput.maxLength = 64;
  nameInput.addEventListener('input', () => {
    group.name = nameInput.value;
    I.dirty = true;
    renderNav();
  });
  titleRow.append(avatar, badgeIndex, nameInput);

  const badges = document.createElement('div');
  badges.className = 'card-badges';
  if (sharedDataCount(group) > 1) {
    badges.append(badge('共享 data', 'default'));
  }
  const state = stateOf(group);
  if (state && state.syncBlocked) {
    badges.append(badge('同步阻断', 'danger-text'));
  }
  if (groupOnline(group)) {
    badges.append(badge('当前在线', 'default'));
  }
  head.append(titleRow, badges);

  const noteRow = document.createElement('label');
  noteRow.className = 'card-note';
  const noteLabel = document.createElement('span');
  noteLabel.textContent = '备注';
  const noteInput = document.createElement('input');
  noteInput.type = 'text';
  noteInput.value = group.note || '';
  noteInput.maxLength = 512;
  noteInput.placeholder = '例如：正版账号 + 小号';
  noteInput.addEventListener('input', () => {
    group.note = noteInput.value;
    I.dirty = true;
  });
  noteRow.append(noteLabel, noteInput);

  const zones = document.createElement('div');
  zones.className = 'uuid-zones';
  zones.append(renderZone(group, 'data', '数据 UUID', '可共享'));
  zones.append(renderZone(group, 'premium', '正版账号 UUID', 'Mojang 账号 · 仅一个组'));
  zones.append(renderZone(group, 'offline', '离线账号 UUID', '非正版 · 仅一个组'));

  const actions = document.createElement('div');
  actions.className = 'card-actions';
  const inventory = document.createElement('button');
  inventory.type = 'button';
  inventory.className = 'ghost';
  inventory.textContent = '编辑身份组背包';
  inventory.title = '直接编辑该身份组 data UUID 的 41 格背包（大窗口，写入前自动备份）';
  inventory.addEventListener('click', () => openGroupInventory(group.data, group.name));
  const preview = document.createElement('button');
  preview.type = 'button';
  preview.className = 'ghost';
  preview.textContent = '预览影响';
  preview.addEventListener('click', () => previewGroup(group, previewOutput));
  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'ghost danger';
  remove.textContent = '删除组';
  remove.addEventListener('click', () => {
    if (!window.confirm('确认删除身份组「' + (group.name || '未命名') + '」？不会删除玩家数据。')) return;
    I.groups = I.groups.filter((item) => item._id !== group._id);
    I.dirty = true;
    renderAll();
  });
  actions.append(inventory, preview, remove);

  const previewOutput = document.createElement('pre');
  previewOutput.className = 'card-preview';
  previewOutput.hidden = true;

  card.append(head, noteRow, zones, actions, previewOutput);
  return card;
}

function badge(text, className) {
  const span = document.createElement('span');
  span.className = 'perm-badge' + (className ? ' ' + className : '');
  span.textContent = text;
  return span;
}

function groupOnline(group) {
  return [group.premium, group.offline].some((uuid) => uuid && I.online[uuid]);
}

/** 头像始终优先用组内正版 UUID 查皮肤；显示名仍按当前服务器模式取在线/离线账号。 */
function groupSkin(group) {
  const uuid = group.premium || group.offline || group.data || '';
  const name = I.names[uuid] || group.name || '';
  return { uuid: uuid || '', name };
}

function renderZone(group, role, labelText, hintText) {
  const zone = document.createElement('div');
  zone.className = 'uuid-zone';
  zone.dataset.groupId = group._id;
  zone.dataset.role = role;
  const label = document.createElement('div');
  label.className = 'zone-label';
  const name = document.createElement('span');
  name.textContent = labelText;
  const hint = document.createElement('span');
  hint.className = 'hint';
  if (role === 'data' && sharedDataCount(group) > 1) {
    hint.textContent = hintText + ' · ' + sharedDataCount(group) + ' 组共用';
  } else {
    hint.textContent = hintText;
  }
  label.append(name, hint);

  const choose = document.createElement('button');
  choose.type = 'button';
  choose.className = 'chip-action';
  choose.textContent = '选择';
  choose.title = '按名称或 UUID 选择';
  choose.addEventListener('click', () => openUuidDialog(group, role));
  label.append(choose);
  const paste = document.createElement('button');
  paste.type = 'button';
  paste.className = 'chip-action';
  paste.textContent = '粘贴';
  paste.title = '从剪贴板读取 UUID 放到这个区域';
  paste.addEventListener('click', () => pasteIntoZone(group, role));
  label.append(paste);
  zone.append(label);

  const body = document.createElement('div');
  body.className = 'zone-body';
  const value = group[role];
  if (value) {
    body.append(uuidChip(value, role, group._id));
  } else {
    const empty = document.createElement('span');
    empty.className = 'zone-empty';
    empty.textContent = '拖入账号 / data UUID，或点击这里选择';
    empty.addEventListener('click', () => openUuidDialog(group, role));
    body.append(empty);
  }
  zone.append(body);

  zone.addEventListener('dragover', (event) => {
    event.preventDefault();
    zone.classList.add('drop-over');
  });
  zone.addEventListener('dragleave', (event) => {
    if (!zone.contains(event.relatedTarget)) zone.classList.remove('drop-over');
  });
  zone.addEventListener('drop', (event) => {
    event.preventDefault();
    zone.classList.remove('drop-over');
    if (!I.drag) return;
    assignRole(group._id, role, I.drag.uuid, I.drag.name);
    I.drag = null;
  });
  return zone;
}

function dataNode(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function advancementTree(adv) {
  const nodes = Array.isArray(adv.nodes) ? adv.nodes.slice() : [];
  const box = dataNode('div', 'identity-adv-tree');
  const bar = dataNode('div', 'identity-adv-bar');
  bar.append(dataNode('span', 'muted small', '拖动平移 · 滚轮缩放 · 点击节点查看说明'));
  bar.append(dataNode('span', 'identity-adv-spacer'));
  const reset = dataNode('button', 'ghost tiny', '重置视图');
  bar.append(reset);
  const viewport = dataNode('div', 'identity-adv-viewport');
  const canvas = dataNode('div', 'identity-adv-canvas');
  const doneNodes = nodes.filter((row) => row.done).length;
  const footer = dataNode('div', 'identity-adv-footer muted small',
    '共 ' + nodes.length + ' 个成就节点，已获得 ' + doneNodes + ' 个。');

  const xs = nodes.map((row) => Number(row.x) || 0);
  const ys = nodes.map((row) => Number(row.y) || 0);
  const minX = Math.min(...xs);
  const maxX = Math.max(...xs);
  const minY = Math.min(...ys);
  const maxY = Math.max(...ys);
  const UNIT = 72;
  const PAD = 34;
  const width = Math.max(320, (maxX - minX) * UNIT + PAD * 2);
  const height = Math.max(260, (maxY - minY) * UNIT + PAD * 2);
  canvas.style.width = width + 'px';
  canvas.style.height = height + 'px';

  const position = (row) => ({
    x: (Number(row.x) - minX) * UNIT + PAD,
    y: (Number(row.y) - minY) * UNIT + PAD,
  });
  const byId = new Map();
  for (const row of nodes) byId.set(row.id, row);

  const svgNs = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(svgNs, 'svg');
  svg.setAttribute('class', 'identity-adv-lines');
  svg.setAttribute('width', String(width));
  svg.setAttribute('height', String(height));
  for (const row of nodes) {
    if (!row.parent || !byId.has(row.parent)) continue;
    const from = position(byId.get(row.parent));
    const to = position(row);
    const line = document.createElementNS(svgNs, 'line');
    line.setAttribute('x1', String(from.x));
    line.setAttribute('y1', String(from.y));
    line.setAttribute('x2', String(to.x));
    line.setAttribute('y2', String(to.y));
    line.setAttribute('class', row.done ? 'done' : 'todo');
    svg.append(line);
  }
  canvas.append(svg);

  for (const row of nodes) {
    const point = position(row);
    const node = dataNode('button', 'identity-adv-node '
      + (row.done ? 'done' : 'todo') + ' frame-' + (row.frame || 'task'));
    node.style.left = (point.x - 22) + 'px';
    node.style.top = (point.y - 22) + 'px';
    node.title = (row.title || row.id) + String.fromCharCode(10)
      + (row.description || '') + String.fromCharCode(10)
      + (row.done ? '已完成' : '未完成');
    const icon = document.createElement('img');
    icon.src = '/items/' + (row.icon || 'stone') + '.png';
    icon.alt = '';
    icon.loading = 'lazy';
    icon.onerror = () => {
      icon.style.display = 'none';
      if (!node.querySelector('.identity-adv-fallback')) {
        node.append(dataNode('span', 'identity-adv-fallback',
          String(row.title || row.id || '?').slice(0, 1)));
      }
    };
    node.append(icon);
    node.append(dataNode('span', 'identity-adv-label', row.title || row.id));
    node.addEventListener('pointerdown', (event) => event.stopPropagation());
    node.addEventListener('click', () => {
      footer.textContent = (row.title || row.id) + ' · '
        + (row.done ? '已完成' : '未完成') + ' · ' + (row.description || '');
    });
    canvas.append(node);
  }

  let tx = 18;
  let ty = 18;
  let scale = 1;
  let drag = null;
  const apply = () => {
    canvas.style.transform = 'translate(' + tx + 'px, ' + ty + 'px) scale(' + scale + ')';
  };
  viewport.addEventListener('pointerdown', (event) => {
    if (event.button !== 0) return;
    // 长按拖动时不要连带选中成就节点文字。
    event.preventDefault();
    drag = { x: event.clientX, y: event.clientY, tx, ty };
    try { viewport.setPointerCapture?.(event.pointerId); } catch { /* 合成事件没有真实 pointer */ }
    viewport.classList.add('dragging');
  });
  viewport.addEventListener('pointermove', (event) => {
    if (!drag) return;
    tx = drag.tx + event.clientX - drag.x;
    ty = drag.ty + event.clientY - drag.y;
    apply();
  });
  const stop = () => {
    drag = null;
    viewport.classList.remove('dragging');
  };
  viewport.addEventListener('pointerup', stop);
  viewport.addEventListener('pointercancel', stop);
  viewport.addEventListener('wheel', (event) => {
    event.preventDefault();
    const rect = viewport.getBoundingClientRect();
    const px = event.clientX - rect.left;
    const py = event.clientY - rect.top;
    const next = Math.max(0.45, Math.min(2, scale * (event.deltaY < 0 ? 1.1 : 0.9)));
    tx = px - (px - tx) * (next / scale);
    ty = py - (py - ty) * (next / scale);
    scale = next;
    apply();
  }, { passive: false });
  reset.addEventListener('click', () => {
    tx = 18;
    ty = 18;
    scale = 1;
    apply();
    footer.textContent = '共 ' + nodes.length + ' 个成就节点，已获得 ' + doneNodes + ' 个。';
  });

  apply();
  viewport.append(canvas);
  box.append(bar, viewport, footer);
  return box;
}

async function backupData(uuid) {
  if (!window.confirm('备份 data UUID ' + uuid + ' 的玩家数据、成就与统计？')) return;
  try {
    const result = await window.SamsaraUI.api('/api/identity/data/backup', {
      method: 'POST',
      body: JSON.stringify({ uuid }),
    });
    identityMessage((result.message || (result.ok ? '已备份' : result.error))
      + (result.ok && result.path ? ' · ' + result.path : ''), result.ok ? 'ok' : 'error');
    if (result.ok) {
      const list = document.querySelector(
        `.identity-backup-section[data-backup-for="${uuid}"] .identity-backup-list`);
      if (list) fetchBackups(uuid, list);
      refreshDataList();
    }
  } catch (e) {
    identityMessage(window.SamsaraUI.authMessage(e, '备份失败'), 'error');
  }
}

/** 备份记录：时间、大小、文件数、可复制路径。 */
function backupSection(uuid) {
  const box = dataNode('section', 'identity-data-section identity-backup-section');
  box.dataset.backupFor = uuid;
  box.append(dataNode('h4', null, '备份记录'));
  const list = dataNode('div', 'identity-backup-list');
  list.append(dataNode('p', 'muted small', '加载中…'));
  box.append(list);
  fetchBackups(uuid, list);
  return box;
}

async function fetchBackups(uuid, list) {
  list.replaceChildren();
  try {
    const result = await window.SamsaraUI.api(
      '/api/identity/data/backups?uuid=' + encodeURIComponent(uuid));
    const rows = Array.isArray(result.backups) ? result.backups : [];
    if (!rows.length) {
      list.append(dataNode('p', 'muted small',
        '还没有备份。点「备份数据」会在 ' + (result.root || 'identity/backups/data/' + uuid) + ' 下生成一份。'));
      return;
    }
    list.append(dataNode('p', 'muted small', '备份根目录：' + (result.root || '—')));
    for (const row of rows) {
      const line = dataNode('div', 'identity-backup-row');
      line.append(dataNode('code', '', row.id));
      line.append(dataNode('span', 'muted small',
        ((Number(row.bytes) || 0) / 1024).toFixed(1) + ' KiB · '
          + (row.files || []).length + ' 个文件'));
      const copyPath = dataNode('button', 'chip-action', '复制路径');
      copyPath.type = 'button';
      copyPath.addEventListener('click', () => {
        copyText(row.path);
        identityMessage('已复制备份路径', 'ok');
      });
      line.append(copyPath);
      line.append(dataNode('span', 'muted small', row.path));
      list.append(line);
    }
  } catch (e) {
    list.append(dataNode('p', 'message error',
      window.SamsaraUI.authMessage(e, '读取备份失败')));
  }
}

function openDataDialog(uuid) {
  let dialog = document.getElementById('identity-data-dialog');
  let content;
  if (!dialog) {
    dialog = document.createElement('dialog');
    dialog.id = 'identity-data-dialog';
    dialog.className = 'app-dialog identity-data-dialog';
    const body = document.createElement('div');
    body.className = 'dialog-body';
    const head = document.createElement('div');
    head.className = 'dialog-head';
    const titleBox = document.createElement('div');
    const title = document.createElement('h3');
    title.textContent = 'data UUID 详情';
    const sub = document.createElement('p');
    sub.className = 'dialog-sub';
    sub.textContent = '查看身份数据文件、成就、统计与备份位置；背包编辑在独立大窗口中完成。';
    titleBox.append(title, sub);
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'ghost';
    close.textContent = '关闭';
    close.addEventListener('click', () => dialog.close());
    head.append(titleBox, close);
    content = document.createElement('div');
    content.id = 'identity-data-content';
    content.className = 'identity-data-content';
    body.append(head, content);
    dialog.append(body);
    document.body.append(dialog);
  } else {
    content = document.getElementById('identity-data-content');
  }
  content.replaceChildren(dataNode('p', 'muted', '加载中…'));
  if (typeof dialog.showModal === 'function') dialog.showModal();
  window.SamsaraUI.api('/api/identity/data?uuid=' + encodeURIComponent(uuid))
      .then((data) => renderDataDetail(content, uuid, data))
      .catch((e) => content.replaceChildren(dataNode('p', 'message error',
          window.SamsaraUI.authMessage(e, '读取详情失败'))));
}

function renderDataDetail(root, uuid, data) {
  root.replaceChildren();
  const head = dataNode('div', 'identity-data-head');
  const copy = dataNode('button', 'ghost', '复制 UUID');
  copy.type = 'button';
  copy.addEventListener('click', () => {
    copyText(uuid);
    identityMessage('已复制 ' + uuid, 'ok');
  });
  const backup = dataNode('button', 'ghost', '备份数据');
  backup.type = 'button';
  backup.addEventListener('click', () => backupData(uuid));
  const rename = dataNode('button', 'ghost', '命名/备注');
  rename.type = 'button';
  rename.addEventListener('click', () => editDataLabel(uuid));
  const edit = dataNode('button', 'ghost identity-edit-link', '编辑身份组背包');
  edit.type = 'button';
  edit.title = '在独立大窗口中编辑该 data UUID 的背包';
  edit.addEventListener('click', () => {
    document.getElementById('identity-data-dialog')?.close();
    openGroupInventory(uuid, dataDisplayName(uuid));
  });
  head.append(dataNode('code', 'identity-data-uuid', uuid), copy, rename, edit, backup);
  root.append(head);
  if (!data.ok) {
    root.append(dataNode('p', 'message error', data.error || data.message || '读取失败'));
    return;
  }
  const meta = dataMeta(uuid) || {};
  const metaLine = dataNode('p', 'muted small',
    '名称：' + (meta.name || dataDisplayName(uuid) || '未命名')
      + ' · 备注：' + (meta.note || '—'));
  root.append(metaLine);
  root.append(backupSection(uuid));
  const chips = dataNode('div', 'status-chips');
  const values = [
    ['数据文件', data.exists ? '存在' : '不存在'],
    ['大小', data.bytes ? (Number(data.bytes) / 1024).toFixed(1) + ' KiB' : '—'],
    ['生命', data.health == null ? '—' : String(data.health)],
    ['饥饿', data.food == null ? '—' : String(data.food)],
    ['经验', data.xpLevel == null ? '—' : data.xpLevel + ' 级 / ' + (data.xpTotal || 0)],
    ['维度', data.dimension || '—'],
    ['坐标', Array.isArray(data.pos) && data.pos.length >= 3
      ? data.pos.slice(0, 3).map((v) => Number(v).toFixed(1)).join(', ') : '—'],
  ];
  for (const [label, value] of values) {
    const chip = dataNode('div', 'status-chip');
    chip.append(dataNode('span', null, label), dataNode('strong', null, value));
    chips.append(chip);
  }
  root.append(chips);

  root.append(itemSection('背包', data.inventory));
  root.append(itemSection('末影箱', data.enderItems));
  const adv = data.advancements || {};
  const advBox = dataNode('div', 'identity-data-section identity-data-wide');
  const displayedDone = Array.isArray(adv.nodes)
    ? adv.nodes.filter((row) => row.done).length : 0;
  const displayedTotal = Array.isArray(adv.nodes) && adv.nodes.length
    ? adv.nodes.length : (adv.total || 0);
  const recipeNote = Array.isArray(adv.nodes) && adv.nodes.length
    && (adv.done || 0) > displayedDone
    ? ' · 另有 ' + ((adv.done || 0) - displayedDone) + ' 个配方解锁' : '';
  advBox.append(dataNode('h4', null, '成就 · ' + displayedDone + ' / '
    + displayedTotal + recipeNote));
  if (Array.isArray(adv.nodes) && adv.nodes.length) {
    advBox.append(advancementTree(adv));
  } else if (Array.isArray(adv.ids) && adv.ids.length) {
    const list = dataNode('div', 'identity-data-tags');
    for (const id of adv.ids) list.append(dataNode('span', 'identity-data-tag', id));
    advBox.append(list);
  } else {
    advBox.append(dataNode('p', 'muted small', '没有已完成的成就记录。'));
  }
  root.append(advBox);

  const stats = data.stats || {};
  const statBox = dataNode('div', 'identity-data-section');
  statBox.append(dataNode('h4', null, '统计'));
  const statGrid = dataNode('div', 'identity-data-stats');
  const labels = {
    play_time: '游玩时间', deaths: '死亡', mob_kills: '击杀生物',
    player_kills: '击杀玩家', walk_one_cm: '步行', sprint_one_cm: '疾跑',
    fly_one_cm: '飞行', damage_dealt: '造成伤害',
  };
  let statCount = 0;
  for (const [key, label] of Object.entries(labels)) {
    if (stats[key] == null) continue;
    statCount++;
    const row = dataNode('div', 'identity-data-stat');
    row.append(dataNode('span', null, label), dataNode('strong', null, String(stats[key])));
    statGrid.append(row);
  }
  statBox.append(statCount ? statGrid : dataNode('p', 'muted small', '没有统计数据。'));
  root.append(statBox);
}

function itemSection(title, items) {
  const box = dataNode('div', 'identity-data-section');
  box.append(dataNode('h4', null, title + ' · ' + (items || []).length));
  if (!Array.isArray(items) || !items.length) {
    box.append(dataNode('p', 'muted small', '空'));
    return box;
  }
  const grid = dataNode('div', 'identity-data-items');
  for (const item of items) {
    const row = dataNode('div', 'identity-data-item');
    if (window.SamsaraItem?.icon) {
      row.append(window.SamsaraItem.icon(item, { size: 'sm' }));
    }
    const text = dataNode('div', 'identity-data-item-text');
    text.append(dataNode('span', 'identity-data-item-name', item.name || item.id));
    text.append(dataNode('span', 'identity-data-item-meta',
        'x' + (item.count || 1) + ' · ' + (item.id || '')));
    row.append(text);
    grid.append(row);
  }
  box.append(grid);
  return box;
}

function uuidChip(uuid, role, groupId) {
  const chip = document.createElement('span');
  chip.className = 'uuid-chip ' + role;
  chip.draggable = true;
  chip.title = uuid + ' · ' + roleLabel(role);
  const group = I.groups.find((item) => item._id === groupId);
  const name = document.createElement('span');
  name.className = 'uuid-name';
  name.dataset.nameFor = uuid;
  const custom = role === 'data' ? dataDisplayName(uuid) : '';
  if (custom) name.dataset.labelLocked = '1';
  name.textContent = custom || I.names[uuid] || candidateName(uuid)
      || (role === 'data' ? (group?.name ? group.name + ' · 数据' : '空白 data') : '未命名');
  const code = document.createElement('span');
  code.className = 'uuid-code';
  code.textContent = shortUuid(uuid);
  const roleText = document.createElement('span');
  roleText.className = 'uuid-role';
  roleText.textContent = role === 'data' ? dataLabel(uuid, groupId) : roleLabel(role);
  chip.append(name, code, roleText);

  const actions = document.createElement('span');
  actions.className = 'chip-actions';
  const copy = document.createElement('button');
  copy.type = 'button';
  copy.className = 'chip-action';
  copy.textContent = '复制';
  copy.title = '复制 UUID';
  copy.addEventListener('click', (event) => {
    event.stopPropagation();
    copyText(uuid);
    identityMessage('已复制 ' + uuid, 'ok');
  });
  actions.append(copy);
  if (role === 'data') {
    const rename = document.createElement('button');
    rename.type = 'button';
    rename.className = 'chip-action';
    rename.textContent = '命名';
    rename.title = '给这个 data UUID 起名/备注，避免和账号 UUID 混淆';
    rename.addEventListener('click', (event) => {
      event.stopPropagation();
      editDataLabel(uuid);
    });
    actions.append(rename);
    const detail = document.createElement('button');
    detail.type = 'button';
    detail.className = 'chip-action';
    detail.textContent = '详情';
    detail.title = '查看背包、末影箱、成就与统计';
    detail.addEventListener('click', (event) => {
      event.stopPropagation();
      openDataDialog(uuid);
    });
    actions.append(detail);
    const backup = document.createElement('button');
    backup.type = 'button';
    backup.className = 'chip-action';
    backup.textContent = '备份';
    backup.title = '备份该 data UUID 的玩家数据、成就与统计';
    backup.addEventListener('click', (event) => {
      event.stopPropagation();
      backupData(uuid);
    });
    actions.append(backup);
  }
  const clear = document.createElement('button');
  clear.type = 'button';
  clear.className = 'chip-action';
  clear.textContent = role === 'data' ? '清空' : '移除';
  clear.title = role === 'data'
    ? '清空这个组的数据 UUID；不会删除玩家数据，保存前必须重新指定'
    : '从本组移除这个账号入口';
  clear.addEventListener('click', (event) => {
    event.stopPropagation();
    clearRole(groupId, role);
  });
  actions.append(clear);
  chip.append(actions);

  chip.addEventListener('dragstart', (event) => {
    I.drag = { uuid, role, groupId, copy: role === 'data', type: candidateType(uuid) };
    event.dataTransfer.effectAllowed = role === 'data' ? 'copy' : 'move';
    try { event.dataTransfer.setData('text/plain', uuid); } catch (e) { /* ignore */ }
  });
  chip.addEventListener('dragend', () => { I.drag = null; });
  return chip;
}

function roleLabel(role) {
  return role === 'data' ? '数据 UUID' : role === 'premium' ? '正版账号' : '离线账号';
}

function displayName(uuid) {
  return I.names[uuid] || candidateName(uuid) || '未命名';
}

function navDataLabel(uuid) {
  if (!uuid) return '未设置';
  if (!I.names[uuid] && !candidateName(uuid) && !candidateType(uuid)) return '空白 data';
  return displayName(uuid);
}

function navAccountLabel(uuid) {
  return uuid ? displayName(uuid) : '未设置';
}

function candidateName(uuid) {
  const found = [...I.candidates, ...I.extraCandidates].find((item) => item.uuid === uuid);
  return found ? found.name : '';
}

function candidateType(uuid) {
  if (I.types[uuid]) return I.types[uuid];
  const found = [...I.candidates, ...I.extraCandidates].find((item) => item.uuid === uuid);
  return found && found.type ? found.type : '';
}

function typeLabel(type) {
  return type === 'offline' ? '离线账号' : type === 'premium' ? '正版账号' : '';
}

function dataLabel(uuid, groupId) {
  const type = candidateType(uuid);
  if (type) return typeLabel(type) + '数据';
  return '空白 data';
}

function poolEmpty(text) {
  const empty = document.createElement('span');
  empty.className = 'pool-empty';
  empty.textContent = text;
  return empty;
}

function allCandidates() {
  const map = new Map();
  for (const item of [...I.candidates, ...I.extraCandidates]) {
    if (item && item.uuid && !map.has(item.uuid)) map.set(item.uuid, item);
  }
  return [...map.values()];
}

function isUnknownName(item) {
  const name = (item && item.name ? item.name : '').trim();
  return item?.nameKnown === false || /^玩家-[0-9a-f]{8}$/i.test(name);
}

function isProbeName(item) {
  const name = (item && item.name ? item.name : '').trim();
  if (!name) return true;
  if (isUnknownName(item)) return false;
  return /^probe[_-]?\d*$/i.test(name)
    || /^dsh[_-]/i.test(name)
    || /^(test|bench|debug)[_-]?\d*$/i.test(name)
    || name === 'Y'
    || /^0+(_0+)*$/.test(name);
}

function probeBadge(item) {
  if (isUnknownName(item)) return '占位名';
  if (isProbeName(item)) return 'probe';
  return '';
}

function normalizeName(name) {
  return (name || '').toLowerCase().replace(/[^a-z0-9]/g, '');
}

function levenshtein(a, b) {
  if (a === b) return 0;
  if (!a.length) return b.length;
  if (!b.length) return a.length;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i += 1) {
    const row = [i];
    for (let j = 1; j <= b.length; j += 1) {
      row[j] = Math.min(row[j - 1] + 1, prev[j] + 1,
        prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    prev = row;
  }
  return prev[b.length];
}

function similarName(a, b) {
  const x = normalizeName(a);
  const y = normalizeName(b);
  if (!x || !y) return false;
  if (x === y) return true;
  if (Math.min(x.length, y.length) < 5) return false;
  if (x.startsWith(y) || y.startsWith(x)) return true;
  return levenshtein(x, y) <= 2;
}

function filteredCandidates() {
  const q = I.pool.text.trim().toLowerCase();
  return allCandidates().filter((item) => {
    if (I.pool.hideProbe && isProbeName(item)) return false;
    if (I.pool.hideUnknown && isUnknownName(item)) return false;
    if (!q) return true;
    return (item.name || '').toLowerCase().includes(q)
      || item.uuid.toLowerCase().includes(q);
  }).sort((a, b) => (b.lastLoginMs || 0) - (a.lastLoginMs || 0)
    || (a.name || '').localeCompare(b.name || ''));
}

function renderSuggestions(candidates) {
  const host = $i('identity-suggestions');
  if (!host) return;
  host.replaceChildren();
  const assigned = new Set();
  for (const group of I.groups) {
    if (group.premium) assigned.add(group.premium);
    if (group.offline) assigned.add(group.offline);
  }
  const items = candidates.filter((item) => !assigned.has(item.uuid)
    && !isProbeName(item) && !isUnknownName(item));
  if (items.length < 2) {
    host.hidden = true;
    return;
  }
  const parent = items.map((_, index) => index);
  const find = (x) => {
    let i = x;
    while (parent[i] !== i) i = parent[i];
    return i;
  };
  const union = (x, y) => {
    const a = find(x);
    const b = find(y);
    if (a !== b) parent[b] = a;
  };
  for (let i = 0; i < items.length; i += 1) {
    for (let j = i + 1; j < items.length; j += 1) {
      if (similarName(items[i].name, items[j].name)) union(i, j);
    }
  }
  const clusters = new Map();
  items.forEach((item, index) => {
    const root = find(index);
    if (!clusters.has(root)) clusters.set(root, []);
    clusters.get(root).push(item);
  });
  const groups = [...clusters.values()].filter((list) => list.length > 1).slice(0, 6);
  if (!groups.length) {
    host.hidden = true;
    return;
  }
  host.hidden = false;
  const head = document.createElement('p');
  head.className = 'muted small';
  head.textContent = 'ID 相似度推荐（只是建议，不会自动保存）：同一名字的正版账号/离线账号入口可以合成一个组；同类型多个账号只能选一个入口。';
  host.append(head);
  for (const cluster of groups) {
    const row = document.createElement('div');
    row.className = 'identity-suggestion';
    const label = document.createElement('div');
    label.className = 'grow';
    const title = document.createElement('strong');
    const premium = cluster.find((item) => item.type === 'premium');
    const offline = cluster.find((item) => item.type === 'offline');
    const titleName = premium?.name || offline?.name || cluster[0].name;
    title.textContent = titleName + (premium && offline ? ' · 正版账号 + 离线账号' : ' · 相似 ID');
    const detail = document.createElement('div');
    detail.className = 'muted small';
    detail.textContent = cluster.map((item) =>
      (item.name || '未命名') + ' (' + typeLabel(item.type) + ' ' + shortUuid(item.uuid) + ')')
      .join(' · ');
    label.append(title, detail);
    const create = document.createElement('button');
    create.type = 'button';
    create.className = 'ghost';
    create.textContent = premium && offline ? '建组（正版账号 + 离线账号）' : '建组（取第一个）';
    create.addEventListener('click', () => createDraftGroup(cluster));
    row.append(label, create);
    host.append(row);
  }
}

function createDraftGroup(cluster) {
  const premium = cluster.find((item) => item.type === 'premium');
  const offline = cluster.find((item) => item.type === 'offline');
  const first = premium || offline || cluster[0];
  let name = (premium?.name || offline?.name || first.name || '新身份组')
    .replace(/^玩家-[0-9a-f]{8}$/i, '').trim() || '新身份组';
  const normalized = normalizeName(name);
  if (I.groups.some((group) => normalizeName(group.name) === normalized)) {
    name += ' (推荐)';
  }
  I.groups.push({
    name,
    data: (premium || offline || first).uuid,
    premium: premium ? premium.uuid : null,
    offline: offline ? offline.uuid : null,
    note: '由相似 ID 推荐创建，请确认',
    _id: nextGroupId(),
    _originalName: '',
  });
  I.dirty = true;
  renderAll();
  identityMessage('已创建草稿组「' + name + '」；同类型多余账号仍需手动处理，确认后再保存。', 'ok');
}

function renderPool() {
  const premiumPool = $i('identity-premium-pool');
  const offlinePool = $i('identity-offline-pool');
  const dataPool = $i('identity-data-pool');
  if (!premiumPool || !offlinePool || !dataPool) return;
  premiumPool.replaceChildren();
  offlinePool.replaceChildren();
  dataPool.replaceChildren();
  const hideProbe = $i('identity-hide-probe');
  const hideUnknown = $i('identity-hide-unknown');
  const search = $i('identity-pool-search');
  if (hideProbe) {
    I.pool.hideProbe = hideProbe.checked;
  }
  if (hideUnknown) {
    I.pool.hideUnknown = hideUnknown.checked;
  }
  if (search && search.value !== I.pool.text) search.value = I.pool.text;

  const assigned = new Set();
  for (const group of I.groups) {
    if (group.premium) assigned.add(group.premium);
    if (group.offline) assigned.add(group.offline);
  }
  const candidates = filteredCandidates();
  let premiumCount = 0;
  let offlineCount = 0;
  for (const item of candidates) {
    if (assigned.has(item.uuid)) continue;
    const offline = item.type === 'offline';
    (offline ? offlinePool : premiumPool).append(poolChip(
      item.uuid, item.name, item.online, offline ? '离线账号' : '正版账号', {
        assignRole: offline ? 'offline' : 'premium',
        type: item.type,
        badge: probeBadge(item),
      }));
    if (offline) offlineCount++;
    else premiumCount++;
  }
  if (!premiumCount) premiumPool.append(poolEmpty('没有未进组的正版账号 UUID。'));
  if (!offlineCount) offlinePool.append(poolEmpty('没有未进组的离线账号 UUID。'));

  const usedData = new Set(I.groups.map((group) => group.data).filter(Boolean));
  const dataSeen = new Set();
  let dataCount = 0;
  // 账号派生的 data 候选：名称 + 正版账号/离线账号，可直接用这个账号当数据键。
  for (const item of candidates) {
    if (usedData.has(item.uuid) || dataSeen.has(item.uuid)) continue;
    const accountGroup = I.groups.find((group) =>
      group.premium === item.uuid || group.offline === item.uuid);
    if (accountGroup && accountGroup.data !== item.uuid) continue;
    dataSeen.add(item.uuid);
    dataPool.append(poolChip(item.uuid, item.name, item.online,
      typeLabel(item.type) + '数据', {
        assignRole: 'data',
        type: item.type,
        badge: probeBadge(item),
      }));
    dataCount++;
  }
  // 随机生成的空白 data / 历史 state 里的闲置 data。
  const blankData = [...I.generated];
  for (const key of Object.keys(I.model?.states || {})) blankData.push(key);
  const q = I.pool.text.trim().toLowerCase();
  // 磁盘上真实存在的 data UUID：未分组也一直在这里，刷新页面不会消失。
  for (const row of I.dataList) {
    const uuid = row.uuid;
    if (!uuid || usedData.has(uuid) || dataSeen.has(uuid)) continue;
    if (q && !String(row.label || '').toLowerCase().includes(q)
        && !String(row.note || '').toLowerCase().includes(q)
        && !uuid.toLowerCase().includes(q)) continue;
    dataSeen.add(uuid);
    dataPool.append(poolChip(uuid, row.label || (row.group ? '组 ' + row.group : '空白 data'),
      I.online[uuid], row.group ? '已入组' : '未分组', {
        assignRole: 'data',
        type: 'data',
        label: row.label || '',
        badge: row.backups ? row.backups + ' 个备份' : '',
        onLabel: () => editDataLabel(uuid),
        onBackups: () => openDataDialog(uuid),
      }));
    dataCount++;
  }
  for (const uuid of blankData) {
    if (!uuid || usedData.has(uuid) || dataSeen.has(uuid)) continue;
    if (q && !uuid.toLowerCase().includes(q)) continue;
    dataSeen.add(uuid);
    const generated = I.generated.includes(uuid);
    dataPool.append(poolChip(uuid, '空白 data', I.online[uuid], 'data', {
      assignRole: 'data',
      type: 'data',
      onRemove: generated ? () => {
        I.generated = I.generated.filter((item) => item !== uuid);
        renderPool();
        renderDatalists();
        identityMessage('已从池子移除空白 data：' + uuid, 'ok');
      } : null,
    }));
    dataCount++;
  }
  if (!dataCount) {
    dataPool.append(poolEmpty('没有闲置 data UUID；点“生成 data UUID”创建一把空白钥匙。'));
  }
  const all = allCandidates();
  const setCount = (id, text) => {
    const el = $i(id);
    if (el) el.textContent = text;
  };
  setCount('identity-premium-count', String(premiumCount));
  setCount('identity-offline-count', String(offlineCount));
  setCount('identity-data-count', String(dataCount));
  setCount('identity-pool-count', '显示 ' + candidates.length + ' 个账号候选'
    + ' · 隐藏 probe ' + all.filter(isProbeName).length
    + ' · 占位名 ' + all.filter(isUnknownName).length);
  renderSuggestions(candidates);
}

function poolChip(uuid, name, online, tag, options) {
  const opts = options || {};
  const chip = document.createElement('span');
  const chipRole = opts.assignRole || (tag === 'data' ? 'data' : '');
  chip.className = 'uuid-chip' + (chipRole ? ' role-' + chipRole : '');
  chip.draggable = true;
  chip.title = '拖到卡片的 数据 UUID / 正版账号 / 离线账号 区域 · ' + uuid;
  const label = document.createElement('span');
  label.className = 'uuid-name';
  label.dataset.nameFor = uuid;
  if (opts.label) label.dataset.labelLocked = '1';
  label.textContent = opts.label || name || (tag === 'data' ? '空白 data' : displayName(uuid));
  const code = document.createElement('span');
  code.className = 'uuid-code';
  code.textContent = shortUuid(uuid);
  chip.append(label, code);
  if (tag) {
    const role = document.createElement('span');
    role.className = 'uuid-role';
    role.textContent = tag;
    chip.append(role);
  }
  if (opts.badge) {
    const badge = document.createElement('span');
    badge.className = 'uuid-role';
    badge.style.color = 'var(--amber)';
    badge.textContent = opts.badge;
    chip.append(badge);
  }
  if (online) {
    const live = document.createElement('span');
    live.className = 'uuid-role';
    live.textContent = '当前在线';
    chip.append(live);
  }
  if (opts.assignRole) {
    const assign = document.createElement('button');
    assign.type = 'button';
    assign.className = 'chip-action';
    assign.textContent = '→组';
    assign.title = '放入身份组…（不需要拖动）';
    assign.addEventListener('click', (event) => {
      event.stopPropagation();
      openAssignDialog(uuid, name, opts.assignRole, opts.type || candidateType(uuid));
    });
    chip.append(assign);
  }
  const copy = document.createElement('button');
  copy.type = 'button';
  copy.className = 'chip-action';
  copy.textContent = '复制';
  copy.addEventListener('click', (event) => {
    event.stopPropagation();
    copyText(uuid);
    identityMessage('已复制 ' + uuid, 'ok');
  });
  chip.append(copy);
  if (typeof opts.onLabel === 'function') {
    const rename = document.createElement('button');
    rename.type = 'button';
    rename.className = 'chip-action';
    rename.textContent = '命名';
    rename.title = '给这个 data UUID 起名/备注，避免和账号 UUID 混淆';
    rename.addEventListener('click', (event) => {
      event.stopPropagation();
      opts.onLabel();
    });
    chip.append(rename);
  }
  if (typeof opts.onBackups === 'function') {
    const backups = document.createElement('button');
    backups.type = 'button';
    backups.className = 'chip-action';
    backups.textContent = '备份记录';
    backups.title = '查看该 data UUID 的备份位置与时间';
    backups.addEventListener('click', (event) => {
      event.stopPropagation();
      opts.onBackups();
    });
    chip.append(backups);
  }
  if (typeof opts.onRemove === 'function') {
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'chip-action';
    remove.textContent = '×';
    remove.title = '从池子移除这个空白 data';
    remove.addEventListener('click', (event) => {
      event.stopPropagation();
      opts.onRemove();
    });
    chip.append(remove);
  }
  chip.addEventListener('dragstart', (event) => {
    I.drag = { uuid, name, role: null, groupId: null, copy: true, type: candidateType(uuid) };
    event.dataTransfer.effectAllowed = 'copy';
    try { event.dataTransfer.setData('text/plain', uuid); } catch (e) { /* ignore */ }
  });
  chip.addEventListener('dragend', () => { I.drag = null; });
  return chip;
}

function openAssignDialog(uuid, name, role, type) {
  if (!I.groups.length) {
    identityMessage('还没有身份组，请先点右上角“新增组”。', 'error');
    return;
  }
  const dialog = $i('assign-dialog');
  const select = $i('assign-group');
  const roleSelect = $i('assign-role');
  if (!dialog || !select || !roleSelect) return;
  I.assign = { uuid, name: name || '', type: type || candidateType(uuid) };
  select.replaceChildren();
  for (const group of I.groups) {
    const option = document.createElement('option');
    option.value = group._id;
    option.textContent = (group.name || '未命名')
      + (group.data ? '' : '（未设 data）');
    select.append(option);
  }
  for (const option of roleSelect.options) {
    option.disabled = (type !== 'offline' && type !== 'premium' && option.value !== 'data')
      || (type === 'offline' && option.value === 'premium')
      || (type === 'premium' && option.value === 'offline');
  }
  roleSelect.value = role || 'data';
  if (roleSelect.selectedOptions[0]?.disabled) roleSelect.value = 'data';
  const target = $i('assign-target');
  if (target) {
    target.textContent = (name || shortUuid(uuid)) + ' · ' + uuid
      + (type ? ' · ' + typeLabel(type) : '');
  }
  const error = $i('assign-error');
  if (error) error.textContent = '';
  dialog.showModal();
}

function applyAssignDialog() {
  const context = I.assign;
  if (!context) return;
  const groupId = $i('assign-group')?.value;
  const role = $i('assign-role')?.value || 'data';
  const group = I.groups.find((item) => item._id === groupId);
  if (!group) {
    const error = $i('assign-error');
    if (error) error.textContent = '请选择目标组。';
    return;
  }
  $i('assign-dialog')?.close();
  I.assign = null;
  assignRole(groupId, role, context.uuid, context.name);
}

function assignRole(groupId, role, uuid, name) {
  const group = I.groups.find((item) => item._id === groupId);
  if (!group || !uuid) return;
  if (!isUuid(uuid)) {
    identityMessage('UUID 格式不正确：' + uuid, 'error');
    return;
  }
  const accountType = candidateType(uuid) || (I.drag && I.drag.type) || '';
  if (role === 'premium' && accountType === 'offline') {
    identityMessage('这是离线账号 UUID，只能放进“离线账号 UUID”区域；正版账号和离线账号的 UUID 是两套不同的值。', 'error');
    return;
  }
  if (role === 'offline' && accountType === 'premium') {
    identityMessage('这是正版账号 UUID，只能放进“正版账号 UUID”区域；正版账号和离线账号的 UUID 是两套不同的值。', 'error');
    return;
  }
  const existing = findExisting(group);
  if (role === 'data') {
    if (existing && group.data !== uuid) {
      openRebindDialog(group, uuid);
      return;
    }
    group.data = uuid;
  } else {
    const dataOwner = I.groups.find((item) => item._id !== groupId && item.data === uuid);
    if (dataOwner) {
      identityMessage('这个 UUID 已经是「' + (dataOwner.name || '未命名') + '」的数据 UUID，不能作为正版账号/离线账号入口。', 'error');
      return;
    }
    for (const other of I.groups) {
      if (other._id !== groupId) {
        if (other.premium === uuid) other.premium = null;
        if (other.offline === uuid) other.offline = null;
      }
    }
    if (role === 'premium' && group.offline === uuid) group.offline = null;
    if (role === 'offline' && group.premium === uuid) group.premium = null;
    group[role] = uuid;
  }
  if (name && !I.names[uuid]) I.names[uuid] = name;
  I.dirty = true;
  identityMessage('已把 ' + shortUuid(uuid) + ' 放到「' + (group.name || '未命名') + '」的 ' + roleLabel(role) + ' 区域；记得保存。', 'ok');
  renderAll();
}

function clearRole(groupId, role) {
  const group = I.groups.find((item) => item._id === groupId);
  if (!group) return;
  if (role === 'data') {
    if (!window.confirm('清空这个组的 data UUID？\n不会删除玩家数据；保存前必须重新指定 data，否则无法保存。')) {
      return;
    }
    if (group.data && !I.generated.includes(group.data)) I.generated.push(group.data);
    group.data = '';
    I.dirty = true;
    renderAll();
    identityMessage('已清空 data UUID；请重新指定后再保存。', 'ok');
    return;
  }
  group[role] = null;
  I.dirty = true;
  renderAll();
}

async function pasteIntoZone(group, role) {
  try {
    if (!navigator.clipboard || !navigator.clipboard.readText) {
      identityMessage('浏览器不允许读取剪贴板；请用“选择”输入框粘贴。', 'error');
      return;
    }
    const text = (await navigator.clipboard.readText()).trim();
    if (!isUuid(text)) {
      identityMessage('剪贴板里不是完整 UUID：' + (text || '空'), 'error');
      return;
    }
    assignRole(group._id, role, text, '');
  } catch (e) {
    identityMessage('读取剪贴板失败；请用“选择”输入框粘贴。', 'error');
  }
}

async function generateDataUuid(copy) {
  try {
    const result = await window.SamsaraUI.api('/api/identity/generate', { method: 'POST' });
    const uuid = result.data;
    if (!I.generated.includes(uuid)) I.generated.push(uuid);
    if (copy) copyText(uuid);
    renderPool();
    renderDatalists();
    identityMessage('已生成空白 data UUID：' + uuid + (copy ? '（已尝试复制）' : '')
        + '；把它拖到某个组的数据区，或直接用账号数据候选。', 'ok');
    return uuid;
  } catch (e) {
    identityMessage(window.SamsaraUI.authMessage(e, '生成 data UUID 失败'), 'error');
    return null;
  }
}

function copyText(text) {
  if (navigator.clipboard) {
    navigator.clipboard.writeText(text).catch(() => {});
  }
}

function openUuidDialog(group, role) {
  I.uuidContext = { groupId: group._id, role };
  I.selectedUuid = null;
  const dialog = $i('uuid-dialog');
  const input = $i('uuid-input');
  const resolved = $i('uuid-resolved');
  if (!dialog || !input) return;
  $i('uuid-title').textContent = '设置「' + (group.name || '未命名') + '」的 ' + roleLabel(role) + ' UUID';
  $i('uuid-desc').textContent = role === 'data'
    ? 'data UUID 是玩家数据的钥匙；同一个值可以被多个组复用。已有组改 data 会自动弹出重绑确认。'
    : '正版账号/离线账号入口全局只能属于一个组；拖入别组已有的入口会把它移动过来。';
  input.value = group[role] || '';
  resolved.hidden = true;
  resolved.replaceChildren();
  const clearButton = $i('uuid-clear');
  if (clearButton) clearButton.hidden = role === 'data';
  dialog.showModal();
  input.focus();
  if (input.value) resolveUuidInput();
}

async function resolveUuidInput() {
  const input = $i('uuid-input');
  const resolved = $i('uuid-resolved');
  if (!input || !resolved) return;
  const value = input.value.trim();
  if (!value) {
    resolved.hidden = true;
    resolved.replaceChildren();
    return;
  }
  try {
    const body = isUuid(value) ? { uuids: [value] } : { name: value };
    const result = await window.SamsaraUI.api('/api/identity/resolve', {
      method: 'POST',
      body: JSON.stringify(body),
    });
    Object.assign(I.names, result.names || {});
    Object.assign(I.types, result.types || {});
    renderResolved(result, value);
  } catch (e) {
    resolved.hidden = false;
    resolved.replaceChildren();
    const error = document.createElement('span');
    error.className = 'note';
    error.textContent = window.SamsaraUI.authMessage(e, '解析失败');
    resolved.append(error);
  }
}

function renderResolved(result, query) {
  const resolved = $i('uuid-resolved');
  if (!resolved) return;
  resolved.replaceChildren();
  resolved.hidden = false;
  const seen = new Set();
  const role = I.uuidContext ? I.uuidContext.role : '';
  const add = (label, uuid, name) => {
    if (!uuid || seen.has(uuid)) return;
    seen.add(uuid);
    const type = (result.types && result.types[uuid]) || candidateType(uuid);
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'uuid-chip';
    chip.style.cursor = 'pointer';
    chip.dataset.type = type || '';
    const nameSpan = document.createElement('span');
    nameSpan.className = 'uuid-name';
    nameSpan.textContent = label + ' · ' + (name || '未命名');
    const code = document.createElement('span');
    code.className = 'uuid-code';
    code.textContent = shortUuid(uuid);
    chip.append(nameSpan, code);
    if (type === 'offline' || type === 'premium') {
      const typeSpan = document.createElement('span');
      typeSpan.className = 'uuid-role';
      typeSpan.textContent = typeLabel(type) + ' UUID';
      chip.append(typeSpan);
    }
    if ((role === 'premium' && type === 'offline') || (role === 'offline' && type === 'premium')) {
      chip.classList.add('mismatch');
      chip.title = '类型不匹配：' + typeLabel(type) + ' UUID 不能放进“' + roleLabel(role) + ' UUID”区域';
    }
    chip.addEventListener('click', () => {
      if ((role === 'premium' && type === 'offline') || (role === 'offline' && type === 'premium')) {
        identityMessage('类型不匹配：' + typeLabel(type) + ' UUID 不能放进“'
            + roleLabel(role) + ' UUID”区域。正版账号和离线账号的 UUID 是两套不同的值。', 'error');
        return;
      }
      I.selectedUuid = uuid;
      for (const other of resolved.querySelectorAll('.uuid-chip')) {
        other.classList.remove('selected');
        other.style.outline = '';
      }
      chip.classList.add('selected');
      chip.style.outline = '2px solid var(--accent)';
      chip.style.outlineOffset = '1px';
    });
    resolved.append(chip);
  };
  if (result.premium) add('正版账号', result.premium, result.name || query);
  if (result.offline) add('离线账号', result.offline, result.name || query);
  for (const [uuid, name] of Object.entries(result.names || {})) {
    const known = uuid === result.premium || uuid === result.offline;
    if (!known) add(isUuid(query) ? '解析' : '账号', uuid, name);
  }
  if (result.premiumNote) {
    const note = document.createElement('span');
    note.className = 'note';
    note.textContent = result.premiumNote;
    resolved.append(note);
  }
  if (!resolved.childElementCount) {
    const note = document.createElement('span');
    note.className = 'note';
    note.textContent = '没有解析结果；可以直接输入完整 UUID。';
    resolved.append(note);
  }
}

function applyUuidDialog() {
  const context = I.uuidContext;
  if (!context) return;
  const input = $i('uuid-input');
  const value = input ? input.value.trim() : '';
  let uuid = I.selectedUuid;
  if (!uuid && isUuid(value)) uuid = value;
  if (!uuid) {
    identityMessage('请选择解析出的正版账号/离线账号 UUID，或直接输入完整 UUID。', 'error');
    return;
  }
  const type = candidateType(uuid);
  if ((context.role === 'premium' && type === 'offline')
      || (context.role === 'offline' && type === 'premium')) {
    identityMessage('类型不匹配：' + typeLabel(type) + ' UUID 不能放进“'
        + roleLabel(context.role) + ' UUID”区域。', 'error');
    return;
  }
  $i('uuid-dialog').close();
  I.uuidContext = null;
  I.selectedUuid = null;
  assignRole(context.groupId, context.role, uuid, '');
}

async function clearUuidDialog() {
  const context = I.uuidContext;
  if (!context) return;
  $i('uuid-dialog').close();
  I.uuidContext = null;
  I.selectedUuid = null;
  clearRole(context.groupId, context.role);
}

function openRebindDialog(group, newData) {
  if (!findExisting(group)) {
    group.data = newData;
    I.dirty = true;
    renderAll();
    return;
  }
  I.rebindGroup = group;
  $i('rebind-group').textContent = group.name || '未命名';
  $i('rebind-input').value = newData || '';
  $i('rebind-confirm').checked = false;
  $i('rebind-error').textContent = '';
  const reject = document.querySelector('input[name="rebind-mode"][value="reject"]');
  if (reject) reject.checked = true;
  $i('rebind-dialog').showModal();
}

async function applyRebind() {
  const group = I.rebindGroup;
  if (!group || !findExisting(group)) return;
  const error = $i('rebind-error');
  if (!$i('rebind-confirm').checked) {
    error.textContent = '请先勾选“我已了解会先备份旧数据与目标数据”。';
    return;
  }
  let newData = $i('rebind-input').value.trim();
  if (!newData) {
    newData = await generateDataUuid(false);
    if (!newData) return;
  }
  if (!isUuid(newData)) {
    error.textContent = '新的 data UUID 格式不正确。';
    return;
  }
  const mode = document.querySelector('input[name="rebind-mode"]:checked')?.value || 'reject';
  error.textContent = '';
  try {
    const result = await window.SamsaraUI.api('/api/identity/rebind', {
      method: 'POST',
      body: JSON.stringify({
        group: findExisting(group).name,
        newData,
        mode,
        confirm: true,
      }),
    });
    $i('rebind-dialog').close();
    I.rebindGroup = null;
    applyModel(result);
    identityMessage(result.message || 'data UUID 已重绑', 'ok');
  } catch (e) {
    error.textContent = window.SamsaraUI.authMessage(e, '重绑失败');
  }
}

async function previewGroup(group, output) {
  output.hidden = false;
  output.textContent = '正在预览…';
  try {
    const result = await window.SamsaraUI.api('/api/identity/preview', {
      method: 'POST',
      body: JSON.stringify({
        group: group.name,
        data: group.data,
        uuids: [group.premium, group.offline].filter(Boolean),
      }),
    });
    output.textContent = result.text || JSON.stringify(result, null, 2);
  } catch (e) {
    output.textContent = window.SamsaraUI.authMessage(e, '预览失败');
  }
}

async function saveIdentity() {
  if (!I.groups.length) {
    identityMessage('至少需要一个身份组；点“新增组”创建。', 'error');
    return false;
  }
  const groups = [];
  for (const group of I.groups) {
    const name = (group.name || '').trim();
    const data = (group.data || '').trim();
    if (!name) {
      identityMessage('有身份组还没有组名，请先填写。', 'error');
      return false;
    }
    if (!isUuid(data)) {
      identityMessage('组「' + name + '」缺少有效的 data UUID。', 'error');
      return false;
    }
    const existing = findExisting(group);
    if (existing && existing.data !== data) {
      identityMessage('组「' + name + '」的 data UUID 变了；请先在数据区重新选择，重绑确认会自动弹出。', 'error');
      return false;
    }
    groups.push({
      name,
      data,
      premium: group.premium || null,
      offline: group.offline || null,
      note: group.note || '',
    });
  }
  try {
    const result = await window.SamsaraUI.api('/api/identity', {
      method: 'PUT',
      body: JSON.stringify({
        enabled: I.settings.enabled,
        syncPlayerData: I.settings.sync,
        maxOwners: Number(I.settings.maxOwners || 32),
        groups,
      }),
    });
    applyModel(result);
    identityMessage(result.message || '身份组配置已保存并热重载', 'ok');
    return true;
  } catch (e) {
    const errors = e.body && e.body.errors;
    if (errors && Object.keys(errors).length) {
      identityMessage(Object.entries(errors).map(([key, value]) => key + '：' + value).join('；'), 'error');
    } else {
      identityMessage(window.SamsaraUI.authMessage(e, '保存身份组失败'), 'error');
    }
    return false;
  }
}

async function reloadIdentity() {
  try {
    const result = await window.SamsaraUI.api('/api/identity/reload', { method: 'POST' });
    applyModel(result);
    identityMessage(result.message || '身份组已重载', 'ok');
    return true;
  } catch (e) {
    identityMessage(window.SamsaraUI.authMessage(e, '重新读取失败'), 'error');
    return false;
  }
}

async function auditIdentity() {
  try {
    const result = await window.SamsaraUI.api('/api/identity/audit');
    const card = $i('identity-audit-card');
    const output = $i('identity-audit-output');
    card.hidden = false;
    card.open = true;
    output.textContent = result.text || '';
    identityMessage('审计完成', 'ok');
  } catch (e) {
    identityMessage(window.SamsaraUI.authMessage(e, '审计失败'), 'error');
  }
}

async function resolveToolbar() {
  const input = $i('identity-resolve-input');
  const resultBox = $i('identity-resolve-result');
  if (!input || !resultBox) return;
  const value = input.value.trim();
  if (!value) {
    identityMessage('请输入玩家名或 UUID。', 'error');
    return;
  }
  try {
    const body = isUuid(value) ? { uuids: [value] } : { name: value };
    const result = await window.SamsaraUI.api('/api/identity/resolve', {
      method: 'POST',
      body: JSON.stringify(body),
    });
    resultBox.hidden = false;
    resultBox.replaceChildren();
    const addResult = (type, label, uuid, name) => {
      if (!uuid) return;
      if (!I.extraCandidates.some((item) => item.uuid === uuid)) {
        I.extraCandidates.push({ uuid, name: name || '', online: !!result.online?.[uuid], tag: label, type });
      }
      const chip = document.createElement('span');
      chip.className = 'uuid-chip';
      chip.append(textSpan(label + ' · ' + (name || shortUuid(uuid)), 'uuid-name'),
          textSpan(shortUuid(uuid), 'uuid-code'));
      resultBox.append(chip);
    };
    if (result.premium) addResult('premium', '正版账号', result.premium, result.name || value);
    if (result.offline) addResult('offline', '离线账号', result.offline, result.name || value);
    for (const [uuid, name] of Object.entries(result.names || {})) {
      if (uuid !== result.premium && uuid !== result.offline) addResult('', '账号', uuid, name);
    }
    if (result.premiumNote) {
      const note = document.createElement('span');
      note.className = 'note';
      note.textContent = result.premiumNote;
      resultBox.append(note);
    }
    renderPool();
    renderDatalists();
    refreshNames();
    identityMessage('解析完成；结果已放进“未进组账号”，拖到卡片即可。', 'ok');
  } catch (e) {
    identityMessage(window.SamsaraUI.authMessage(e, '解析失败'), 'error');
  }
}

function textSpan(text, className) {
  const span = document.createElement('span');
  span.className = className;
  span.textContent = text;
  return span;
}

async function addGroup() {
  const uuid = await generateDataUuid(false);
  const group = {
    name: '新身份组 ' + (I.groups.length + 1),
    data: uuid || '',
    premium: null,
    offline: null,
    note: '',
    _id: nextGroupId(),
    _originalName: '',
  };
  I.groups.push(group);
  I.dirty = true;
  renderAll();
  const card = document.getElementById('identity-group-' + group._id);
  const input = card?.querySelector('.group-name');
  if (input) {
    input.focus();
    input.select();
  }
}

function renderNav() {
  const items = I.groups.map((group) => ({
    label: group.name || '未命名组',
    count: [group.data, group.premium, group.offline].filter(Boolean).length,
    target: 'identity-group-' + group._id,
    children: [
      { label: '数据 UUID · ' + navDataLabel(group.data), target: 'identity-group-' + group._id },
      { label: '正版账号 · ' + navAccountLabel(group.premium), target: 'identity-group-' + group._id },
      { label: '离线账号 · ' + navAccountLabel(group.offline), target: 'identity-group-' + group._id },
    ],
  }));
  items.push({ label: '未进组账号 / data UUID 库', target: 'identity-pool-panel' });
  if (window.SamsaraUI.isActiveTab('identity')) {
    const targets = I.groups.map((group) => ({
      id: 'identity-group-' + group._id,
      el: document.getElementById('identity-group-' + group._id),
    })).filter((item) => item.el);
    window.SamsaraUI.buildNav('身份组导航', items, targets);
  }
}

function refreshNames() {
  if (I.nameTimer) clearTimeout(I.nameTimer);
  I.nameTimer = setTimeout(async () => {
    const uuids = new Set();
    for (const group of I.groups) {
      for (const uuid of [group.premium, group.offline]) {
        if (uuid && isUuid(uuid)) uuids.add(uuid);
      }
    }
    for (const item of [...I.candidates, ...I.extraCandidates]) {
      if (item.uuid && isUuid(item.uuid)) uuids.add(item.uuid);
    }
    if (!uuids.size) return;
    try {
      const result = await window.SamsaraUI.api('/api/identity/resolve', {
        method: 'POST',
        body: JSON.stringify({ uuids: [...uuids] }),
      });
      Object.assign(I.names, result.names || {});
      Object.assign(I.types, result.types || {});
      Object.assign(I.online, result.online || {});
      for (const el of document.querySelectorAll('[data-name-for]')) {
        if (el.dataset.labelLocked === '1') continue;
        const uuid = el.dataset.nameFor;
        const name = I.names[uuid];
        if (name) el.textContent = name;
      }
    } catch (e) {
      // 名称解析失败不影响编辑；保留短 UUID。
    }
  }, 150);
}

function activate() {
  if (!I.model) ensureIdentityLoaded();
  else {
    renderNav();
    refreshNames();
  }
}

function wireEvents() {
  const maxOwners = $i('identity-max-owners');
  if (maxOwners) maxOwners.addEventListener('input', () => {
    I.settings.maxOwners = Number(maxOwners.value || 32);
    I.dirty = true;
  });
  const add = $i('identity-add-group');
  if (add) add.addEventListener('click', addGroup);
  const generate = $i('identity-generate');
  if (generate) generate.addEventListener('click', () => generateDataUuid(true));
  const audit = $i('identity-audit');
  if (audit) audit.addEventListener('click', auditIdentity);
  const resolve = $i('identity-resolve-button');
  if (resolve) resolve.addEventListener('click', resolveToolbar);
  const resolveInput = $i('identity-resolve-input');
  if (resolveInput) resolveInput.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') { event.preventDefault(); resolveToolbar(); }
  });

  const poolSearch = $i('identity-pool-search');
  if (poolSearch) {
    poolSearch.addEventListener('input', () => {
      I.pool.text = poolSearch.value;
      renderPool();
    });
  }
  const hideProbe = $i('identity-hide-probe');
  if (hideProbe) {
    hideProbe.checked = I.pool.hideProbe;
    hideProbe.addEventListener('change', () => {
      I.pool.hideProbe = hideProbe.checked;
      renderPool();
    });
  }
  const hideUnknown = $i('identity-hide-unknown');
  if (hideUnknown) {
    hideUnknown.checked = I.pool.hideUnknown;
    hideUnknown.addEventListener('change', () => {
      I.pool.hideUnknown = hideUnknown.checked;
      renderPool();
    });
  }
  $i('assign-apply')?.addEventListener('click', applyAssignDialog);
  $i('assign-cancel')?.addEventListener('click', () => {
    I.assign = null;
    $i('assign-dialog')?.close();
  });

  // HTML5 拖拽不会自动滚动长页面；靠近视口上下边缘时手动滚。
  document.addEventListener('dragover', (event) => {
    if (!I.drag) return;
    const margin = 90;
    if (event.clientY < margin) window.scrollBy(0, -24);
    else if (event.clientY > window.innerHeight - margin) window.scrollBy(0, 24);
  }, { passive: true });

  const uuidInput = $i('uuid-input');
  if (uuidInput) {
    uuidInput.addEventListener('input', () => {
      I.selectedUuid = null;
      if (I.uuidTimer) clearTimeout(I.uuidTimer);
      I.uuidTimer = setTimeout(resolveUuidInput, 220);
    });
    uuidInput.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') { event.preventDefault(); applyUuidDialog(); }
    });
  }
  $i('uuid-apply')?.addEventListener('click', applyUuidDialog);
  $i('uuid-cancel')?.addEventListener('click', () => $i('uuid-dialog').close());
  $i('uuid-clear')?.addEventListener('click', clearUuidDialog);
  $i('rebind-apply')?.addEventListener('click', applyRebind);
  $i('rebind-cancel')?.addEventListener('click', () => $i('rebind-dialog').close());
  $i('rebind-generate')?.addEventListener('click', async () => {
    const uuid = await generateDataUuid(false);
    if (uuid) $i('rebind-input').value = uuid;
  });
}

wireEvents();
window.SamsaraIdentity = {
  load: loadIdentity,
  ensureLoaded: ensureIdentityLoaded,
  activate,
  save: saveIdentity,
  reload: reloadIdentity,
  refreshCount: () => window.SamsaraUI.setTabCount('identity', I.groups.length),
};
window.SamsaraUI.onConnect(loadIdentity);
