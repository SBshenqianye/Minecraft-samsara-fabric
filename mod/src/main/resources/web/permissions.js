'use strict';

// 权限树：关闭树干会级联关闭所有后代；叶子实际等级取整条路径最高等级。

const P = {
  model: null,
  query: '',
  collapsed: new Set(),
  busy: false,
};

const $p = (id) => document.getElementById(id);

function permMessage(text, kind) {
  const el = $p('perm-message');
  el.textContent = text || '';
  el.className = 'message' + (kind ? ' ' + kind : '');
}

async function loadPermissions() {
  try {
    P.model = await window.SamsaraUI.api('/api/permissions');
    renderPermissions();
    permMessage('已读取权限树', 'ok');
  } catch (e) {
    permMessage(e.status === 401 ? 'token 不正确，请先连接' : '读取权限树失败：' + e.message, 'error');
  }
}

function ensurePermissionsLoaded() {
  if (!P.model && window.SamsaraUI.getToken()) loadPermissions();
}

function nodeMatches(node, query) {
  if (!query) return true;
  const haystack = (node.path + ' ' + node.label + ' ' + node.effectiveLevel).toLowerCase();
  return haystack.includes(query) || (node.children || []).some((child) => nodeMatches(child, query));
}

function renderPermissions() {
  const container = $p('perm-tree');
  container.replaceChildren();
  if (!P.model || !P.model.tree) return;
  const query = P.query.trim().toLowerCase();
  for (const node of P.model.tree) {
    if (nodeMatches(node, query)) container.append(renderNode(node, 0, query));
  }
}

function renderNode(node, depth, query) {
  const wrap = document.createElement('div');
  wrap.className = 'perm-node';
  const row = document.createElement('div');
  row.className = 'perm-row' + (node.disabledBy ? ' disabled' : '');
  row.style.paddingLeft = (8 + depth * 18) + 'px';

  const children = node.children || [];
  const hasVisibleChildren = children.some((child) => nodeMatches(child, query));
  const collapseButton = document.createElement('button');
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
    permMessage(result.message || '权限已保存并热重载', 'ok');
  } catch (e) {
    permMessage((e.body && e.body.error) || ('保存权限失败：' + e.message), 'error');
  } finally {
    P.busy = false;
    renderPermissions();
  }
}

async function reloadPermissions() {
  try {
    const result = await window.SamsaraUI.api('/api/permissions/reload', { method: 'POST' });
    P.model = result.permissions;
    renderPermissions();
    permMessage(result.message || '权限配置已重载', 'ok');
  } catch (e) {
    permMessage('重新读取失败：' + e.message, 'error');
  }
}

$p('perm-search').addEventListener('input', () => {
  P.query = $p('perm-search').value;
  renderPermissions();
});
$p('perm-reload').addEventListener('click', reloadPermissions);

window.SamsaraPermissions = { load: loadPermissions, ensureLoaded: ensurePermissionsLoaded };
window.SamsaraUI.onConnect(loadPermissions);
