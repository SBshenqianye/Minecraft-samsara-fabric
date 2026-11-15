/** 配置编辑器（PageSpec/FormSpec）的纯逻辑：页面分组、字段分组、取值与序列化。 */

export function pageKey(pageId, path) {
  return pageId + '|' + path;
}

export function sourceLabel(source) {
  if (source === 'server') return '服务器本体';
  if (source === 'third-party') return '第三方模组';
  return 'samsara 配置';
}

export function pageTitle(page) {
  return page?.title || page?.fileName || page?.id || '';
}

export function pretty(value) {
  if (value == null || value === '') return '';
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

export function currentValue(page, field, edits) {
  const edit = edits.get(pageKey(page.id, field.path));
  if (edit !== undefined) return edit;
  if (Object.prototype.hasOwnProperty.call(page.values || {}, field.path)) {
    return page.values[field.path];
  }
  return field.defaultValue;
}

/** 过滤页面：标题/文件名/路径/字段说明任一命中。 */
export function filteredPages(pages, sourceFilter, query) {
  const needle = String(query || '').trim().toLowerCase();
  let list = pages || [];
  if (sourceFilter) list = list.filter((page) => (page.source || 'samsara') === sourceFilter);
  if (!needle) return list;
  return list.filter((page) => (pageTitle(page) + ' ' + page.fileName + ' ' + page.source + ' ' + page.path)
    .toLowerCase().includes(needle)
    || (page.form?.fields || []).some((field) =>
      (field.path + ' ' + field.group + ' ' + field.description).toLowerCase().includes(needle)));
}

/** 左侧导航：按来源分组，组内是配置页面。 */
export function navModel(pages, sourceFilter, query) {
  const groups = new Map();
  for (const page of filteredPages(pages, sourceFilter, query)) {
    const label = sourceLabel(page.source || 'samsara');
    if (!groups.has(label)) groups.set(label, []);
    groups.get(label).push(page);
  }
  return [...groups.entries()].map(([label, list]) => ({
    label,
    count: list.length,
    target: list[0].id,
    children: list.map((page) => ({
      label: pageTitle(page),
      count: (page.form?.fields || []).length,
      target: page.id,
    })),
  }));
}

/** 表单字段按 group 分组，保留 PageSpec 的分组顺序。 */
export function fieldGroups(page, query) {
  const needle = String(query || '').trim().toLowerCase();
  const groups = new Map();
  for (const group of page?.form?.groups || []) groups.set(group.id, []);
  for (const field of page?.form?.fields || []) {
    if (!groups.has(field.group)) groups.set(field.group, []);
    groups.get(field.group).push(field);
  }
  const out = [];
  for (const [id, fields] of groups) {
    const visible = fields.filter((field) => !needle
      || (field.path + ' ' + field.description + ' ' + field.label + ' ' + id)
        .toLowerCase().includes(needle));
    if (visible.length) out.push({ id, fields: visible });
  }
  return out;
}

/** 控件类型；widget=chat 优先。 */
export function fieldKind(page, field) {
  if (field.readOnly || !page?.writable) return 'readonly';
  if (field.widget === 'chat') return 'chat';
  if (field.type === 'bool') return 'bool';
  if (field.type === 'enum') return 'enum';
  if (field.type === 'int') return 'int';
  if (field.type === 'number') return 'number';
  if (field.type === 'json') return 'json';
  if (field.type === 'multiline') return 'multiline';
  if (field.type === 'password') return 'password';
  if (field.type === 'url') return 'url';
  return 'text';
}

/** 收集草稿值：json 解析、整数/小数转换、敏感值留空不写回。 */
export function collectValues(page, edits) {
  const values = {};
  const errors = {};
  for (const field of page?.form?.fields || []) {
    const raw = edits.get(pageKey(page.id, field.path));
    if (raw === undefined) continue;
    if (field.secret && String(raw).trim() === '') continue;
    if (field.type === 'json') {
      const text = String(raw).trim();
      if (!text) {
        values[field.path] = null;
        continue;
      }
      try {
        values[field.path] = JSON.parse(text);
      } catch (error) {
        errors[field.path] = 'JSON 格式错误：' + error.message;
      }
    } else if (field.type === 'int') {
      values[field.path] = raw === '' ? null : Number.parseInt(raw, 10);
    } else if (field.type === 'number') {
      values[field.path] = raw === '' ? null : Number.parseFloat(raw);
    } else {
      values[field.path] = raw;
    }
  }
  return { values, errors };
}

export function readonlyText(value) {
  return value != null && typeof value === 'object' ? pretty(value) : (value == null ? '' : String(value));
}

export function pageSummary(pages, filtered, query) {
  if (!pages) return '';
  return query ? '匹配 ' + filtered + ' / ' + pages + ' 个页面' : pages + ' 个页面';
}
