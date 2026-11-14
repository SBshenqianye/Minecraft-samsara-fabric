/** 权限树纯逻辑：模型归一化、统计、搜索匹配、路径 id 与 owner 列表。 */

export const LEVELS = [
  { value: 'deny', label: '关闭' },
  { value: 'inherit', label: '继承' },
  { value: 'config', label: 'config' },
  { value: 'owner', label: 'owner' },
  { value: 'op', label: 'op' },
  { value: 'user', label: 'user' },
];

export const LEVEL_LABELS = Object.fromEntries(LEVELS.map((level) => [level.value, level.label]));

export function emptyModel() {
  return { path: '', tree: [], owners: [], warnings: [] };
}

export function normalizeModel(data) {
  if (!data || typeof data !== 'object') return emptyModel();
  return {
    path: String(data.path || ''),
    tree: Array.isArray(data.tree) ? data.tree : [],
    owners: Array.isArray(data.owners) ? data.owners.map(String) : [],
    warnings: Array.isArray(data.warnings) ? data.warnings.map(String) : [],
  };
}

export function collectStats(nodes) {
  const stats = { total: 0, disabled: 0, deny: 0, custom: 0, leaves: 0 };
  const walk = (node) => {
    stats.total++;
    if (node.disabled || node.disabledBy) stats.disabled++;
    if (node.level === 'deny') stats.deny++;
    if (node.source === 'custom') stats.custom++;
    if (!node.children || node.children.length === 0) stats.leaves++;
    for (const child of node.children || []) walk(child);
  };
  for (const node of nodes || []) walk(node);
  return stats;
}

export function nodeMatches(node, query) {
  const text = String(query || '').trim().toLowerCase();
  if (!text) return true;
  const haystack = (node.path + ' ' + node.label + ' ' + node.effectiveLevel).toLowerCase();
  return haystack.includes(text) || (node.children || []).some((child) => nodeMatches(child, text));
}

export function countVisible(node, query) {
  let count = 1;
  for (const child of node.children || []) {
    if (nodeMatches(child, query)) count += countVisible(child, query);
  }
  return count;
}

export function pathId(path) {
  return 'perm-node-' + encodeURIComponent(String(path || ''));
}

export function collapsePaths(nodes) {
  const out = [];
  const walk = (node) => {
    if ((node.children || []).length) out.push(node.path);
    for (const child of node.children || []) walk(child);
  };
  for (const node of nodes || []) walk(node);
  return out;
}

export function addOwner(owners, name) {
  const value = String(name || '').trim();
  if (!value) return owners;
  if (owners.includes(value)) return owners;
  return [value, ...owners];
}

export function removeOwner(owners, index) {
  return owners.filter((_, i) => i !== index);
}
