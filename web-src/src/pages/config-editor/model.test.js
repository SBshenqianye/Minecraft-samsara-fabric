import { describe, expect, it } from 'vitest';
import {
  collectValues,
  currentValue,
  fieldGroups,
  fieldKind,
  filteredPages,
  navModel,
  pageKey,
  pageSummary,
  pretty,
  sourceLabel,
} from './model.js';

const page = {
  id: 'samsara:commands',
  title: '命令',
  fileName: 'commands.yml',
  source: 'samsara',
  path: '/x/commands.yml',
  writable: true,
  values: { 'where.enabled': true },
  form: {
    groups: [{ id: 'where' }, { id: 'tpa' }],
    fields: [
      { path: 'where.enabled', group: 'where', type: 'bool', description: '开关' },
      { path: 'where.cooldown', group: 'where', type: 'int', description: '冷却', defaultValue: 30 },
      { path: 'tpa.enabled', group: 'tpa', type: 'bool', description: '传送' },
      { path: 'secret', group: 'tpa', type: 'password', secret: true },
      { path: 'json', group: 'tpa', type: 'json' },
    ],
  },
};

describe('config-editor model', () => {
  it('来源标签与页面过滤', () => {
    expect(sourceLabel('server')).toBe('服务器本体');
    expect(sourceLabel('third-party')).toBe('第三方模组');
    expect(sourceLabel('samsara')).toBe('samsara 配置');
    const pages = [page, { ...page, id: 'server:x', source: 'server', title: 'server' }];
    expect(filteredPages(pages, 'samsara', '').map((item) => item.id)).toEqual(['samsara:commands']);
    expect(filteredPages([page], null, '冷却').map((item) => item.id)).toEqual(['samsara:commands']);
    expect(filteredPages(pages, null, '不存在')).toEqual([]);
  });

  it('导航按来源分组', () => {
    const items = navModel([page, { ...page, id: 'third-party:a', source: 'third-party', title: 'a' }], null, '');
    expect(items.map((item) => item.label)).toEqual(['samsara 配置', '第三方模组']);
    expect(items[0].children[0]).toMatchObject({ label: '命令', target: 'samsara:commands' });
  });

  it('字段分组保留 PageSpec 顺序并支持搜索', () => {
    const groups = fieldGroups(page, '');
    expect(groups.map((group) => group.id)).toEqual(['where', 'tpa']);
    expect(groups[0].fields).toHaveLength(2);
    const searched = fieldGroups(page, '冷却');
    expect(searched).toHaveLength(1);
    expect(searched[0].fields[0].path).toBe('where.cooldown');
  });

  it('取值优先草稿，其次文件值，最后默认值', () => {
    const edits = new Map([[pageKey(page.id, 'where.cooldown'), 12]]);
    expect(currentValue(page, page.form.fields[0], edits)).toBe(true);
    expect(currentValue(page, page.form.fields[1], edits)).toBe(12);
    expect(currentValue(page, page.form.fields[2], edits)).toBe(undefined);
  });

  it('控件类型判定', () => {
    expect(fieldKind(page, { widget: 'chat', type: 'text' })).toBe('chat');
    expect(fieldKind(page, { readOnly: true, type: 'bool' })).toBe('readonly');
    expect(fieldKind(page, { type: 'bool' })).toBe('bool');
    expect(fieldKind(page, { type: 'int' })).toBe('int');
    expect(fieldKind(page, { type: 'json' })).toBe('json');
    expect(fieldKind(page, { type: 'weird' })).toBe('text');
    expect(fieldKind({ writable: false }, { type: 'text' })).toBe('readonly');
  });

  it('收集草稿值并校验 JSON/数字', () => {
    const edits = new Map([
      [pageKey(page.id, 'where.enabled'), false],
      [pageKey(page.id, 'where.cooldown'), '12'],
      [pageKey(page.id, 'secret'), ''],
      [pageKey(page.id, 'json'), '{bad'],
    ]);
    const { values, errors } = collectValues(page, edits);
    expect(values['where.enabled']).toBe(false);
    expect(values['where.cooldown']).toBe(12);
    expect(values.secret).toBeUndefined();
    expect(errors.json).toContain('JSON 格式错误');
    expect(pretty({ a: 1 })).toBe('{\n  "a": 1\n}');
    expect(pageSummary(3, 2, 'x')).toBe('匹配 2 / 3 个页面');
    expect(pageSummary(3, 2, '')).toBe('3 个页面');
  });
});
