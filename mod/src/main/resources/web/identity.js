'use strict';

// 身份组卡片：账号 UUID 与 data UUID 分角色拖拽。
// 正版/离线入口全局唯一；data UUID 允许复制到多个组（共享同一份玩家数据）。

const I = {
  model: null,
  groups: [],
  settings: { enabled: false, sync: true, maxOwners: 32 },
  candidates: [],
  extraCandidates: [],
  generated: [],
  names: {},
  online: {},
  drag: null,
  seq: 0,
  dirty: false,
  nameTimer: null,
  uuidTimer: null,
  uuidContext: null,
  selectedUuid: null,
  rebindGroup: null,
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
    identityMessage('已读取身份组配置', 'ok');
    return true;
  } catch (e) {
    identityMessage(window.SamsaraUI.authMessage(e, '读取身份组失败'), 'error');
    return false;
  }
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
          + (item.online ? ' · 在线' : '') + ' · ' + shortUuid(item.uuid);
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
  if (enabled) enabled.checked = I.settings.enabled;
  if (sync) sync.checked = I.settings.sync;
  if (maxOwners) maxOwners.value = String(I.settings.maxOwners);
  const path = $i('identity-path');
  const root = $i('identity-root');
  if (path) path.textContent = '配置文件：' + (I.model?.path || '—');
  if (root) root.textContent = '运行时数据：' + (I.model?.identityRoot || '—');
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
  titleRow.append(badgeIndex, nameInput);

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
    badges.append(badge('在线', 'default'));
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
  noteInput.placeholder = '例如：正版 + 小号';
  noteInput.addEventListener('input', () => {
    group.note = noteInput.value;
    I.dirty = true;
  });
  noteRow.append(noteLabel, noteInput);

  const zones = document.createElement('div');
  zones.className = 'uuid-zones';
  zones.append(renderZone(group, 'data', '数据 UUID', '可共享'));
  zones.append(renderZone(group, 'premium', '正版 UUID', '仅一个组'));
  zones.append(renderZone(group, 'offline', '离线 UUID', '仅一个组'));

  const actions = document.createElement('div');
  actions.className = 'card-actions';
  const preview = document.createElement('button');
  preview.type = 'button';
  preview.className = 'ghost';
  preview.textContent = '预览影响';
  preview.addEventListener('click', () => previewGroup(group, previewOutput));
  const rebind = document.createElement('button');
  rebind.type = 'button';
  rebind.className = 'ghost';
  rebind.textContent = '重绑 data';
  rebind.disabled = !findExisting(group);
  rebind.title = findExisting(group) ? '修改已有组的 data UUID' : '新组直接选 data 即可';
  rebind.addEventListener('click', () => openRebindDialog(group, ''));
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
  actions.append(preview, rebind, remove);

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
  if (role === 'data') {
    const generate = document.createElement('button');
    generate.type = 'button';
    generate.className = 'chip-action';
    generate.textContent = '生成';
    generate.title = '生成一个随机 data UUID 并填入本组';
    generate.addEventListener('click', async () => {
      const uuid = await generateDataUuid(false);
      if (uuid) assignRole(group._id, 'data', uuid, '');
    });
    label.append(generate);
  }
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

function uuidChip(uuid, role, groupId) {
  const chip = document.createElement('span');
  chip.className = 'uuid-chip ' + role;
  chip.draggable = true;
  chip.title = uuid + ' · ' + roleLabel(role);
  const group = I.groups.find((item) => item._id === groupId);
  const name = document.createElement('span');
  name.className = 'uuid-name';
  name.dataset.nameFor = uuid;
  name.textContent = I.names[uuid] || candidateName(uuid)
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
  if (role !== 'data') {
    const clear = document.createElement('button');
    clear.type = 'button';
    clear.className = 'chip-action';
    clear.textContent = '移除';
    clear.title = '从本组移除这个账号入口';
    clear.addEventListener('click', (event) => {
      event.stopPropagation();
      clearRole(groupId, role);
    });
    actions.append(clear);
  }
  chip.append(actions);

  chip.addEventListener('dragstart', (event) => {
    I.drag = { uuid, role, groupId, copy: role === 'data' };
    event.dataTransfer.effectAllowed = role === 'data' ? 'copy' : 'move';
    try { event.dataTransfer.setData('text/plain', uuid); } catch (e) { /* ignore */ }
  });
  chip.addEventListener('dragend', () => { I.drag = null; });
  return chip;
}

function roleLabel(role) {
  return role === 'data' ? 'data' : role === 'premium' ? '正版' : '离线';
}

function displayName(uuid) {
  return I.names[uuid] || candidateName(uuid) || '未命名';
}

function candidateName(uuid) {
  const found = [...I.candidates, ...I.extraCandidates].find((item) => item.uuid === uuid);
  return found ? found.name : '';
}

function candidateType(uuid) {
  const found = [...I.candidates, ...I.extraCandidates].find((item) => item.uuid === uuid);
  return found && found.type ? found.type : '';
}

function typeLabel(type) {
  return type === 'offline' ? '离线' : type === 'premium' ? '正版' : '';
}

function dataLabel(uuid, groupId) {
  const type = candidateType(uuid);
  if (type) return typeLabel(type) + '数据';
  return '空白 data';
}

function renderPool() {
  const accountPool = $i('identity-account-pool');
  const dataPool = $i('identity-data-pool');
  if (!accountPool || !dataPool) return;
  accountPool.replaceChildren();
  dataPool.replaceChildren();

  const assigned = new Set();
  for (const group of I.groups) {
    if (group.premium) assigned.add(group.premium);
    if (group.offline) assigned.add(group.offline);
  }
  const seen = new Set();
  let accountCount = 0;
  for (const item of [...I.candidates, ...I.extraCandidates]) {
    if (!item.uuid || assigned.has(item.uuid) || seen.has(item.uuid)) continue;
    seen.add(item.uuid);
    accountPool.append(poolChip(item.uuid, item.name, item.online, typeLabel(item.type)));
    accountCount++;
  }
  if (!accountCount) {
    const empty = document.createElement('span');
    empty.className = 'pool-empty';
    empty.textContent = '没有未进组账号；可以从上方“名称 / UUID”解析新玩家，或把卡片里的账号移除后回到这里。';
    accountPool.append(empty);
  }

  const usedData = new Set(I.groups.map((group) => group.data).filter(Boolean));
  const dataSeen = new Set();
  let dataCount = 0;
  // 账号派生的 data 候选：名称 + 正版/离线，拖到数据区即可直接用这个账号当数据键。
  for (const item of [...I.candidates, ...I.extraCandidates]) {
    if (!item.uuid || usedData.has(item.uuid) || dataSeen.has(item.uuid)) continue;
    const accountGroup = I.groups.find((group) =>
      group.premium === item.uuid || group.offline === item.uuid);
    // 账号已经是某个组的入口、且该组 data 不是它自己时，再当 data 会产生歧义。
    if (accountGroup && accountGroup.data !== item.uuid) continue;
    dataSeen.add(item.uuid);
    dataPool.append(poolChip(item.uuid, item.name, item.online,
        typeLabel(item.type) + '数据'));
    dataCount++;
  }
  // 随机生成的空白 data / 历史 state 里的闲置 data。
  const blankData = [...I.generated];
  for (const key of Object.keys(I.model?.states || {})) blankData.push(key);
  for (const uuid of blankData) {
    if (!uuid || usedData.has(uuid) || dataSeen.has(uuid)) continue;
    dataSeen.add(uuid);
    dataPool.append(poolChip(uuid, '空白 data', I.online[uuid], 'data'));
    dataCount++;
  }
  if (!dataCount) {
    const empty = document.createElement('span');
    empty.className = 'pool-empty';
    empty.textContent = '没有闲置 data UUID；点“生成 data UUID”创建一把空白钥匙。';
    dataPool.append(empty);
  }
}

function poolChip(uuid, name, online, tag) {
  const chip = document.createElement('span');
  chip.className = 'uuid-chip';
  chip.draggable = true;
  chip.title = '拖到卡片的 数据 / 正版 / 离线 区域 · ' + uuid;
  const label = document.createElement('span');
  label.className = 'uuid-name';
  label.dataset.nameFor = uuid;
  label.textContent = name || (tag === 'data' ? '空白 data' : displayName(uuid));
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
  if (online) {
    const live = document.createElement('span');
    live.className = 'uuid-role';
    live.textContent = '在线';
    chip.append(live);
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
  chip.addEventListener('dragstart', (event) => {
    I.drag = { uuid, name, role: null, groupId: null, copy: true };
    event.dataTransfer.effectAllowed = 'copy';
    try { event.dataTransfer.setData('text/plain', uuid); } catch (e) { /* ignore */ }
  });
  chip.addEventListener('dragend', () => { I.drag = null; });
  return chip;
}

function assignRole(groupId, role, uuid, name) {
  const group = I.groups.find((item) => item._id === groupId);
  if (!group || !uuid) return;
  if (!isUuid(uuid)) {
    identityMessage('UUID 格式不正确：' + uuid, 'error');
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
      identityMessage('这个 UUID 已经是「' + (dataOwner.name || '未命名') + '」的数据 UUID，不能作为正版/离线入口。', 'error');
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
  if (role === 'data') {
    identityMessage('data UUID 不能直接清空；请用“重绑 data”或删除整个组。', 'error');
    return;
  }
  const group = I.groups.find((item) => item._id === groupId);
  if (!group) return;
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
    ? 'data UUID 是玩家数据的钥匙；同一个值可以被多个组复用。已有组改 data 会走重绑流程。'
    : '正版/离线入口全局只能属于一个组；拖入别组已有的入口会把它移动过来。';
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
  const add = (label, uuid, name) => {
    if (!uuid || seen.has(uuid)) return;
    seen.add(uuid);
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'uuid-chip';
    chip.style.cursor = 'pointer';
    const nameSpan = document.createElement('span');
    nameSpan.className = 'uuid-name';
    nameSpan.textContent = label + ' · ' + (name || '未命名');
    const code = document.createElement('span');
    code.className = 'uuid-code';
    code.textContent = shortUuid(uuid);
    chip.append(nameSpan, code);
    chip.addEventListener('click', () => {
      I.selectedUuid = uuid;
      for (const other of resolved.querySelectorAll('.uuid-chip')) other.classList.remove('selected');
      chip.classList.add('selected');
      chip.style.outline = '2px solid var(--accent)';
      chip.style.outlineOffset = '1px';
    });
    resolved.append(chip);
  };
  if (result.premium) add('正版', result.premium, result.name || query);
  if (result.offline) add('离线', result.offline, result.name || query);
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
    identityMessage('请选择解析出的正版/离线 UUID，或直接输入完整 UUID。', 'error');
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
      identityMessage('组「' + name + '」的 data UUID 变了；必须用“重绑 data”流程，不能直接保存。', 'error');
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
    const addResult = (label, uuid, name) => {
      if (!uuid) return;
      const type = label === '正版' ? 'premium' : label === '离线' ? 'offline' : '';
      if (!I.extraCandidates.some((item) => item.uuid === uuid)) {
        I.extraCandidates.push({ uuid, name: name || '', online: !!result.online?.[uuid], tag: label, type });
      }
      const chip = document.createElement('span');
      chip.className = 'uuid-chip';
      chip.append(textSpan(label + ' · ' + (name || shortUuid(uuid)), 'uuid-name'),
          textSpan(shortUuid(uuid), 'uuid-code'));
      resultBox.append(chip);
    };
    if (result.premium) addResult('正版', result.premium, result.name || value);
    if (result.offline) addResult('离线', result.offline, result.name || value);
    for (const [uuid, name] of Object.entries(result.names || {})) {
      if (uuid !== result.premium && uuid !== result.offline) addResult('账号', uuid, name);
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
      { label: 'data · ' + shortUuid(group.data), target: 'identity-group-' + group._id },
      { label: '正版 · ' + (group.premium ? shortUuid(group.premium) : '未设置'), target: 'identity-group-' + group._id },
      { label: '离线 · ' + (group.offline ? shortUuid(group.offline) : '未设置'), target: 'identity-group-' + group._id },
    ],
  }));
  items.push({ label: '未进组 / 数据 UUID 库', target: 'identity-account-pool' });
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
      Object.assign(I.online, result.online || {});
      for (const el of document.querySelectorAll('[data-name-for]')) {
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
  const enabled = $i('identity-enabled');
  const sync = $i('identity-sync');
  const maxOwners = $i('identity-max-owners');
  if (enabled) enabled.addEventListener('change', () => { I.settings.enabled = enabled.checked; I.dirty = true; });
  if (sync) sync.addEventListener('change', () => { I.settings.sync = sync.checked; I.dirty = true; });
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
