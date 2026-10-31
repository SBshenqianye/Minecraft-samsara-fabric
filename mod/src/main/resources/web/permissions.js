'use strict';

// 权限树：关闭树干会级联关闭所有后代；叶子实际等级取整条路径最高等级。
// 数据来自真实命令树，页面只负责展示与提交变更。

const P = {
  model: null,
  query: '',
  collapsed: new Set(),
  busy: false,
  owners: [],
};

const $p = (id) => document.getElementById(id);

function permMessage(text, kind) {
  const el = $p('perm-message');
  if (!el) return;
  el.textContent = text || '';
  el.className = 'message' + (kind ? ' ' + kind : '');
}

async function loadPermissions() {
  try {
    P.model = await window.SamsaraUI.api('/api/permissions');
    P.owners = Array.isArray(P.model.owners) ? P.model.owners.slice() : [];
    renderPermissions();
    renderOwners();
    permMessage('已读取权限树', 'ok');
    refreshCount();
    return true;
  } catch (e) {
    permMessage(window.SamsaraUI.authMessage(e, '读取权限树失败'), 'error');
    return false;
  }
}

function ensurePermissionsLoaded() {
  if (!P.model && window.SamsaraUI.getToken()) loadPermissions();
}

function refreshCount() {
  if (!P.model) return;
  const stats = collectStats(P.model.tree || []);
  window.SamsaraUI.setTabCount('permissions', stats.total);
  renderStats(stats);
}

function collectStats(nodes) {
  const stats = { total: 0, disabled: 0, custom: 0, leaves: 0 };
  const walk = (node) => {
    stats.total++;
    if (node.disabled || node.disabledBy) stats.disabled++;
    if (node.source === 'custom') stats.custom++;
    if (!node.children || node.children.length === 0) stats.leaves++;
    for (const child of node.children || []) walk(child);
  };
  for (const node of nodes) walk(node);
  return stats;
}

function renderStats(stats) {
  const container = $p('perm-stats');
  if (!container) return;
  container.replaceChildren();
  const rows = [
    ['命令路径', stats.total],
    ['已关闭', stats.disabled],
    ['自定义规则', stats.custom],
    ['叶子', stats.leaves],
  ];
  for (const [label, value] of rows) {
    const stat = document.createElement('div');
    stat.className = 'stat';
    const key = document.createElement('span');
    key.textContent = label;
    const val = document.createElement('strong');
    val.textContent = String(value);
    stat.append(key, val);
    container.append(stat);
  }
}

function renderOwners() {
  const container = $p('perm-owners');
  if (!container) return;
  container.replaceChildren();
  container.append(window.SamsaraStructured.stringList(P.owners, (next) => {
    P.owners = next;
    ownerMessage('owner 名单有修改，点右上角“保存 owner 名单”后生效');
  }, { addAtTop: true, scrollIntoView: true }));
}

function ownerMessage(text, kind) {
  const el = $p('perm-owner-message');
  if (!el) return;
  el.textContent = text || '';
  el.className = 'message small' + (kind ? ' ' + kind : '');
}

async function saveOwners() {
  if (!P.model) {
    ownerMessage('权限树尚未读取，请先点“重新读取”', 'error');
    return false;
  }
  try {
    const result = await window.SamsaraUI.api('/api/permissions', {
      method: 'PUT',
      body: JSON.stringify({ owners: P.owners }),
    });
    P.model = result.permissions;
    P.owners = Array.isArray(P.model.owners) ? P.model.owners.slice() : P.owners;
    renderOwners();
    refreshCount();
    ownerMessage(result.message || 'owner 名单已保存并热重载', 'ok');
    return true;
  } catch (e) {
    ownerMessage(window.SamsaraUI.authMessage(e, '保存 owner 名单失败'), 'error');
    return false;
  }
}

function nodeMatches(node, query) {
  if (!query) return true;
  const haystack = (node.path + ' ' + node.label + ' ' + node.effectiveLevel).toLowerCase();
  return haystack.includes(query) || (node.children || []).some((child) => nodeMatches(child, query));
}

function renderPermissions() {
  const container = $p('perm-tree');
  if (!container) return;
  container.replaceChildren();
  if (!P.model || !P.model.tree) return;
  const query = P.query.trim().toLowerCase();
  let shown = 0;
  const navItems = [];
  for (const node of P.model.tree) {
    if (nodeMatches(node, query)) {
      const rendered = renderNode(node, 0, query);
      container.append(rendered);
      shown += countVisible(node, query);
      navItems.push({
        label: node.path === '*' ? '/samsara（整棵树）' : node.label,
        count: countVisible(node, query),
        target: rendered.id,
        children: (node.children || []).filter((child) => nodeMatches(child, query)).map((child) => ({
          label: child.label,
          target: 'perm-node-' + encodeURIComponent(child.path),
        })),
      });
    }
  }
  if (shown === 0) {
    const empty = document.createElement('div');
    empty.className = 'nav-empty';
    empty.textContent = '没有匹配的权限路径';
    container.append(empty);
  }
  if (window.SamsaraUI.isActiveTab('permissions')) {
    window.SamsaraUI.buildNav('权限导航', navItems, []);
  }
}

function countVisible(node, query) {
  let count = 1;
  for (const child of node.children || []) {
    if (nodeMatches(child, query)) count += countVisible(child, query);
  }
  return count;
}

function renderNode(node, depth, query) {
  const wrap = document.createElement('div');
  wrap.className = 'perm-node';
  wrap.id = 'perm-node-' + encodeURIComponent(node.path);
  const row = document.createElement('div');
  row.className = 'perm-row' + (node.disabledBy ? ' disabled' : '');
  row.style.paddingLeft = (8 + depth * 20) + 'px';

  const children = node.children || [];
  const hasVisibleChildren = children.some((child) => nodeMatches(child, query));
  const collapseButton = document.createElement('button');
  collapseButton.type = 'button';
  collapseButton.className = 'perm-expand ghost';
  if (children.length === 0 || !hasVisibleChildren) {
    collapseButton.textContent = '·';
    collapseButton.disabled = true;
  } else {
    const collapsed = !query && P.collapsed.has(node.path);
    collapseButton.textContent = collapsed ? '▸' : '▾';
    collapseButton.title = collapsed ? '展开' : '折叠';
    collapseButton.addEventListener('click', () => {
      if (P.collapsed.has(node.path)) P.collapsed.delete(node.path);
      else P.collapsed.add(node.path);
      renderPermissions();
    });
  }
  row.append(collapseButton);

  const blocked = node.disabledBy && node.disabledBy !== node.path;
  const label = document.createElement('span');
  label.className = 'perm-label';
  label.textContent = node.path === '*' ? '/samsara（整棵树）' : node.label;
  label.title = node.path;
  row.append(label);

  if (children.length) {
    const childCount = document.createElement('span');
    childCount.className = 'perm-badge';
    childCount.textContent = children.length + ' 子项';
    row.append(childCount);
  }

  const toggle = document.createElement('label');
  toggle.className = 'perm-toggle';
  const checkbox = document.createElement('input');
  checkbox.type = 'checkbox';
  checkbox.checked = !node.disabled && !blocked;
  checkbox.disabled = blocked || P.busy;
  checkbox.title = blocked
    ? '被祖先 ' + node.disabledBy + ' 关闭；先开启祖先'
    : (node.disabled ? '已关闭' : '已启用');
  checkbox.addEventListener('change', () => {
    savePermissionNode(node.path, { disabled: !checkbox.checked });
  });
  toggle.append(checkbox, document.createTextNode('启用'));
  row.append(toggle);

  const select = document.createElement('select');
  for (const level of ['inherit', 'user', 'op', 'owner', 'config']) {
    const option = document.createElement('option');
    option.value = level;
    option.textContent = level === 'inherit' ? '继承' : level;
    select.append(option);
  }
  select.value = node.level || 'inherit';
  select.disabled = blocked || P.busy;
  select.title = '本节点声明的等级；inherit 表示不覆盖';
  select.addEventListener('change', () => {
    savePermissionNode(node.path, { level: select.value });
  });
  row.append(select);

  const effective = document.createElement('span');
  effective.className = 'perm-badge';
  effective.textContent = '实际：' + node.effectiveLevel;
  row.append(effective);

  if (node.source === 'custom') {
    const source = document.createElement('span');
    source.className = 'perm-badge custom';
    source.textContent = '自定义';
    row.append(source);
  }
  if (node.disabledBy) {
    const disabled = document.createElement('span');
    disabled.className = 'perm-badge danger-text';
    disabled.textContent = '被 ' + node.disabledBy + ' 关闭';
    row.append(disabled);
  }
  wrap.append(row);

  if (children.length && (!P.collapsed.has(node.path) || query)) {
    for (const child of children) {
      if (nodeMatches(child, query)) wrap.append(renderNode(child, depth + 1, query));
    }
  }
  return wrap;
}

async function savePermissionNode(path, change) {
  if (P.busy) return;
  P.busy = true;
  try {
    const node = { path };
    if (change.level !== undefined) node.level = change.level;
    if (change.disabled !== undefined) node.disabled = change.disabled;
    const result = await window.SamsaraUI.api('/api/permissions', {
      method: 'PUT',
      body: JSON.stringify({ nodes: [node] }),
    });
    P.model = result.permissions;
    renderPermissions();
    refreshCount();
    permMessage(result.message || '权限已保存并热重载', 'ok');
  } catch (e) {
    permMessage(window.SamsaraUI.authMessage(e, '保存权限失败'), 'error');
  } finally {
    P.busy = false;
    renderPermissions();
  }
}

async function reloadPermissions() {
  try {
    const result = await window.SamsaraUI.api('/api/permissions/reload', { method: 'POST' });
    P.model = result.permissions;
    P.owners = Array.isArray(P.model.owners) ? P.model.owners.slice() : [];
    renderPermissions();
    renderOwners();
    refreshCount();
    permMessage(result.message || '权限配置已重载', 'ok');
    return true;
  } catch (e) {
    permMessage(window.SamsaraUI.authMessage(e, '重新读取失败'), 'error');
    return false;
  }
}

function expandAll() {
  P.collapsed.clear();
  renderPermissions();
}

function collapseAll() {
  const walk = (nodes) => {
    for (const node of nodes) {
      if ((node.children || []).length) P.collapsed.add(node.path);
      walk(node.children || []);
    }
  };
  walk(P.model?.tree || []);
  renderPermissions();
}

function activate() {
  if (!P.model) ensurePermissionsLoaded();
  else {
    renderPermissions();
    renderOwners();
    refreshCount();
  }
}

function flash(text) {
  permMessage(text, 'ok');
}

if ($p('perm-search')) {
  $p('perm-search').addEventListener('input', () => {
    P.query = $p('perm-search').value;
    renderPermissions();
  });
}
if ($p('perm-expand-all')) $p('perm-expand-all').addEventListener('click', expandAll);
if ($p('perm-collapse-all')) $p('perm-collapse-all').addEventListener('click', collapseAll);

window.SamsaraPermissions = {
  load: loadPermissions,
  ensureLoaded: ensurePermissionsLoaded,
  activate,
  reload: reloadPermissions,
  saveOwners,
  refreshCount,
};
window.SamsaraUI.onConnect(loadPermissions);
