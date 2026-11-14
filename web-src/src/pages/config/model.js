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

/** 文件块标题（去掉 .yml 后缀）。 */
export function fileTitle(file) {
  return String(file?.name || '').replace(/\.yml$/, '');
}

export function entryElementId(fileId, path) {
  return 'cfg-entry-' + encodeURIComponent(fileId + '|' + path);
}

export function groupElementId(fileId, group) {
  return 'cfg-group-' + encodeURIComponent(fileId + '|' + group);
}

export function fileElementId(fileId) {
  return 'cfg-file-' + encodeURIComponent(fileId);
}

const WIDE_TYPES = new Set(['string-list', 'int-list', 'level-map', 'template', 'curve']);

export function isWideEntry(entry) {
  return WIDE_TYPES.has(entry?.type)
    || (entry?.description && entry.description.length > 110)
    || (typeof entry?.default === 'string' && entry.default.length > 80);
}

/** 搜索 + 条件显示后的文件视图：分组顺序与旧渲染器一致。 */
export function fileView(file, edits, query) {
  const entries = (file.entries || []).filter((entry) => matchesQuery(file, entry, query));
  const groups = [];
  const index = new Map();
  for (const entry of entries) {
    const name = entry.group || '其他';
    if (!index.has(name)) {
      index.set(name, { name, entries: [] });
      groups.push(index.get(name));
    }
    index.get(name).entries.push(entry);
  }
  return { entries, groups, count: entries.length };
}

/** 条件显示：无条件返回 true；条件无效或依赖缺失时按可见处理。 */
export function entryVisible(file, entry, edits) {
  if (!entry?.visibleWhen) return true;
  const visible = isVisibleByCondition(file, entry.visibleWhen, edits);
  return visible == null ? true : visible;
}

/** 保存时每个文件的 values（敏感字段不写回）。 */
export function fileValues(file, edits) {
  const values = {};
  for (const entry of file.entries || []) {
    if (entry.sensitive) continue;
    values[entry.path] = currentValue(edits, file.id, entry);
  }
  return values;
}

/** 输入控件的类型归类，页面按类型选择组件。 */
export function controlKind(entry) {
  if (!entry) return 'text';
  if (entry.sensitive) return 'sensitive';
  if (entry.type === 'boolean') return 'boolean';
  if (entry.type === 'enum') return 'enum';
  if (entry.type === 'int' || entry.type === 'double') return 'number';
  if (entry.type === 'int-list') return 'int-list';
  if (entry.type === 'string-list') return 'string-list';
  if (entry.type === 'level-map') return 'level-map';
  if (entry.type === 'curve') return 'curve';
  return 'text';
}

/** 数值输入的兜底值（旧渲染器在空值时回写默认值）。 */
export function numericFallback(entry) {
  return entry?.default == null ? '' : String(entry.default);
}

export function numberPlaceholder(entry) {
  const fallback = numericFallback(entry);
  return fallback === '' ? '默认：空' : '默认：' + fallback;
}

/** 布尔枚举：粒子预览需要知道主题。 */
export function particleTheme(path) {
  if (path === 'dayParticle') return 'day';
  if (path === 'nightParticle') return 'night';
  if (/Particle$/i.test(path) || /particle/i.test(path)) return 'dark';
  return '';
}

