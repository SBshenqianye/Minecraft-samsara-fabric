import { describe, expect, it } from 'vitest';
import {
  addOwner,
  collapsePaths,
  collectStats,
  countVisible,
  emptyModel,
  normalizeModel,
  nodeMatches,
  pathId,
  removeOwner,
} from './model.js';

const tree = [
  {
    path: '*', label: '整棵树', level: 'inherit', effectiveLevel: 'user', source: 'default',
    children: [
      {
        path: 'where', label: 'where', level: 'config', effectiveLevel: 'config',
        children: [
          { path: 'where broadcast', label: 'broadcast', level: 'user', effectiveLevel: 'user', source: 'custom' },
        ],
      },
      { path: 'stop', label: 'stop', level: 'deny', effectiveLevel: 'deny', disabled: true },
    ],
  },
];

describe('permissions model', () => {
  it('emptyModel / normalizeModel', () => {
    expect(emptyModel()).toEqual({ path: '', tree: [], owners: [], warnings: [] });
    const model = normalizeModel({ path: 'a', tree, owners: ['alice'], warnings: ['w'] });
    expect(model.tree).toHaveLength(1);
    expect(model.owners).toEqual(['alice']);
  });

  it('collectStats 统计整棵树', () => {
    expect(collectStats(tree)).toEqual({
      total: 4,
      disabled: 1,
      deny: 1,
      custom: 1,
      leaves: 2,
    });
  });

  it('nodeMatches 匹配自身或后代', () => {
    expect(nodeMatches(tree[0], '')).toBe(true);
    expect(nodeMatches(tree[0], 'broadcast')).toBe(true);
    expect(nodeMatches(tree[0], 'nothing')).toBe(false);
    expect(nodeMatches(tree[0], 'custom')).toBe(false);
  });

  it('countVisible 统计可见节点', () => {
    expect(countVisible(tree[0], '')).toBe(4);
    expect(countVisible(tree[0], 'where')).toBe(3);
    expect(countVisible(tree[0], 'stop')).toBe(2);
  });

  it('pathId 对特殊字符编码', () => {
    expect(pathId('where broadcast')).toBe('perm-node-where%20broadcast');
  });

  it('collapsePaths 只返回有子节点的路径', () => {
    expect(collapsePaths(tree)).toEqual(['*', 'where']);
  });

  it('owner 列表新增去重与删除', () => {
    const owners = ['alice'];
    expect(addOwner(owners, ' bob ')).toEqual(['bob', 'alice']);
    expect(addOwner(owners, 'alice')).toBe(owners);
    expect(addOwner(owners, '')).toBe(owners);
    expect(removeOwner(owners, 0)).toEqual([]);
  });
});
