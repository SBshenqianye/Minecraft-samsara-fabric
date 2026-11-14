import { describe, expect, it } from 'vitest';
import {
  configTotalEntries,
  currentValue,
  effectiveValue,
  isMissingValue,
  isVisibleByCondition,
  matchesQuery,
  parseVisibilityCondition,
  sameJson,
  valueKey,
} from './model.js';

describe('config model', () => {
  it('优先使用已编辑值，否则回退默认值', () => {
    const entry = { path: 'storage.enabled', value: null, default: true };
    expect(valueKey('storage', entry.path)).toBe('storage:storage.enabled');
    expect(effectiveValue(entry)).toBe(true);
    expect(isMissingValue(entry)).toBe(true);
    const edits = new Map([['storage:storage.enabled', false]]);
    expect(currentValue(edits, 'storage', entry)).toBe(false);
    expect(currentValue(new Map(), 'storage', entry)).toBe(true);
  });

  it('空值判断兼容 undefined/null 与空对象', () => {
    expect(isMissingValue({ value: undefined })).toBe(true);
    expect(isMissingValue({ value: null })).toBe(true);
    expect(isMissingValue({ value: 0 })).toBe(false);
    expect(isMissingValue(null)).toBe(true);
    expect(sameJson({ a: [1, 2] }, { a: [1, 2] })).toBe(true);
    expect(sameJson({ a: 1 }, { a: 2 })).toBe(false);
  });

  it('搜索匹配文件 id、键名、说明与分组', () => {
    const file = { id: 'web', entries: [] };
    const entry = {
      path: 'token', description: '管理端访问令牌', group: '安全',
    };
    expect(matchesQuery(file, entry, '')).toBe(true);
    expect(matchesQuery(file, entry, 'WEB')).toBe(true);
    expect(matchesQuery(file, entry, 'token')).toBe(true);
    expect(matchesQuery(file, entry, '令牌')).toBe(true);
    expect(matchesQuery(file, entry, '安全')).toBe(true);
    expect(matchesQuery(file, entry, '不存在')).toBe(false);
  });

  it('解析显示条件并读取依赖值', () => {
    expect(parseVisibilityCondition('enabled=true')).toEqual({ path: 'enabled', expected: 'true' });
    expect(parseVisibilityCondition('')).toBeNull();
    expect(parseVisibilityCondition('=true')).toBeNull();
    const file = {
      id: 'storage',
      entries: [
        { path: 'mode', value: 'advanced', default: 'simple' },
        { path: 'enabled', value: true, default: false },
      ],
    };
    expect(isVisibleByCondition(file, 'enabled=true', new Map())).toBe(true);
    expect(isVisibleByCondition(file, 'enabled=false', new Map())).toBe(false);
    expect(isVisibleByCondition(file, 'missing=true', new Map())).toBeNull();
    expect(isVisibleByCondition(file, 'bad', new Map())).toBeNull();
  });

  it('总项数排除圈地范围并容忍空模型', () => {
    const model = {
      files: [
        { id: 'commands', entries: [{}, {}] },
        { id: 'claim-range', entries: [{}, {}, {}] },
        { id: 'web', entries: [{}] },
      ],
    };
    expect(configTotalEntries(model)).toBe(3);
    expect(configTotalEntries(null)).toBe(0);
    expect(configTotalEntries({ files: [] })).toBe(0);
  });
});
