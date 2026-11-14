/** 高级配置页的纯逻辑：配置值读取、条件显示、搜索匹配与计数。 */

export function valueKey(fileId, path) {
  return fileId + ':' + path;
}

export function effectiveValue(entry) {
  if (!entry || entry.value === undefined || entry.value === null) {
    return entry ? entry.default : undefined;
  }
  return entry.value;
}

export function currentValue(edits, fileId, entry) {
  const key = valueKey(fileId, entry.path);
  return edits.has(key) ? edits.get(key) : effectiveValue(entry);
}

export function isMissingValue(entry) {
  return !entry || entry.value === undefined || entry.value === null;
}

export function sameJson(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

/** 搜索结果匹配：文件 id、键名、说明、分组任一包含查询串。 */
export function matchesQuery(file, entry, query) {
  const needle = String(query || '').trim().toLowerCase();
  if (!needle) return true;
  const haystack = (file.id + ' ' + entry.path + ' ' + entry.description + ' '
    + entry.group).toLowerCase();
  return haystack.includes(needle);
}

/** 解析 `path=value` 条件；无效条件返回 null。 */
export function parseVisibilityCondition(condition) {
  const text = String(condition || '');
  const split = text.indexOf('=');
  if (split <= 0) return null;
  return { path: text.slice(0, split), expected: text.slice(split + 1) };
}

/** 依赖项等于期望值时显示；条件无效或依赖缺失返回 null。 */
export function isVisibleByCondition(file, condition, edits) {
  const parsed = parseVisibilityCondition(condition);
  if (!parsed) return null;
  const dep = (file.entries || []).find((entry) => entry.path === parsed.path);
  if (!dep) return null;
  return String(currentValue(edits, file.id, dep)) === parsed.expected;
}

/** 总配置项数；圈地范围单独成页，不计入高级配置计数。 */
export function configTotalEntries(model) {
  if (!model || !Array.isArray(model.files)) return 0;
  return model.files
    .filter((file) => file.id !== 'claim-range')
    .reduce((sum, file) => sum + (file.entries?.length || 0), 0);
}
