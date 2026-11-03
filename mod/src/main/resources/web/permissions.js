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
  const stats = { total: 0, disabled: 0, deny: 0, custom: 0, leaves: 0 };
  const walk = (node) => {
    stats.total++;
    if (node.disabled || node.disabledBy) stats.disabled++;
    if (node.level === 'deny') stats.deny++;
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
    ['关闭权限', stats.deny],
    ['停用节点', stats.disabled],
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

  const KIT = window.SamsaraUIKit;
  // 分段控件：关闭(DENY) | 继承 | config | owner | op | user
  const levelChoices = [
    { value: 'deny', label: '关闭' },
    { value: 'inherit', label: '继承' },
    { value: 'config', label: 'config' },
    { value: 'owner', label: 'owner' },
    { value: 'op', label: 'op' },
    { value: 'user', label: 'user' },
  ];
  const levelTitle = blocked
      ? '被祖先 ' + node.disabledBy + ' 停用；先恢复祖先'
      : '本节点声明的等级；关闭 = DENY（高于 config/owner/控制台）';
  let levelControl;
  if (KIT && KIT.buttonGroup) {
    levelControl = KIT.buttonGroup(levelChoices, node.level || 'inherit', (value) => {
      if (blocked || P.busy) return;
      savePermissionNode(node.path, { level: value });
    }, { className: 'perm-levels compact', title: levelTitle });
    for (const button of levelControl.querySelectorAll('button')) {
      button.disabled = blocked || P.busy;
    }
  } else {
    levelControl = document.createElement('select');
    for (const choice of levelChoices) {
      const option = document.createElement('option');
      option.value = choice.value;
      option.textContent = choice.label;
      levelControl.append(option);
    }
    levelControl.value = node.level || 'inherit';
    levelControl.disabled = blocked || P.busy;
    levelControl.title = levelTitle;
    levelControl.addEventListener('change', () => {
      savePermissionNode(node.path, { level: levelControl.value });
    });
  }
  row.append(levelControl);

  // 旧的 boolean disabled 保留，但视觉上降级为次级按钮“停用节点”
  const secondary = document.createElement('button');
  secondary.type = 'button';
  secondary.className = 'perm-secondary' + (node.disabled ? ' active' : '');
  secondary.textContent = node.disabled ? '已停用' : '停用节点';
  secondary.disabled = blocked || P.busy;
  secondary.title = blocked
      ? '被祖先 ' + node.disabledBy + ' 停用；先恢复祖先'
      : '整节点停用并级联到后代；与“关闭权限”不同，停用不改变等级';
  secondary.addEventListener('click', () => {
    if (blocked || P.busy) return;
    savePermissionNode(node.path, { disabled: !node.disabled });
  });
  row.append(secondary);

  if (node.recovery) {
    const warn = document.createElement('span');
    warn.className = 'perm-badge warning';
    warn.textContent = '!';
    warn.title = '关闭后仍保留控制台恢复入口（避免 Web/配置被锁死）';
    row.append(warn);
  }

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
    disabled.textContent = '被 ' + node.disabledBy + ' 停用';
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
