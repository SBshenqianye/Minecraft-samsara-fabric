import { describe, expect, it } from 'vitest';
import {
  candidateView,
  filterPool,
  groupOnline,
  groupView,
  isProbeName,
  isUnknownName,
  isUuid,
  normalizeSettings,
  shortUuid,
  typeLabel,
} from './model.js';

describe('identity model', () => {
  it('UUID 校验与短显示', () => {
    expect(isUuid('069a79f4-44e9-4726-a5be-fca90e38aaf5')).toBe(true);
    expect(isUuid('not-a-uuid')).toBe(false);
    expect(shortUuid('069a79f4-44e9-4726-a5be-fca90e38aaf5')).toBe('069a79f4…aaf5');
    expect(shortUuid('abc')).toBe('abc');
    expect(shortUuid('')).toBe('—');
    expect(shortUuid(null)).toBe('—');
  });

  it('账号类型标签', () => {
    expect(typeLabel('premium')).toBe('正版账号');
    expect(typeLabel('offline')).toBe('离线账号');
    expect(typeLabel('data')).toBe('数据');
    expect(typeLabel(undefined)).toBe('账号');
  });

  it('设置缺省值与后端默认一致', () => {
    expect(normalizeSettings()).toEqual({ enabled: false, sync: true, maxOwners: 32 });
    expect(normalizeSettings({ enabled: true, syncPlayerData: false, maxOwners: 8 }))
      .toEqual({ enabled: true, sync: false, maxOwners: 8 });
  });

  it('候选显示保留在线状态与短 UUID', () => {
    const view = candidateView({ uuid: 'abcdef12-3456-4789-8abc-def012345678', name: 'Alice', online: true, type: 'premium' });
    expect(view.name).toBe('Alice');
    expect(view.meta).toBe('在线 · abcdef12…5678');
    expect(view.typeLabel).toBe('正版账号');
  });

  it('池子过滤隐藏 probe 与占位名', () => {
    const items = [
      { uuid: 'u1', name: 'Alice' },
      { uuid: 'u2', name: 'probe-1' },
      { uuid: 'u3', name: '玩家-abc12345' },
      { uuid: 'u4', name: '' },
    ];
    expect(filterPool(items).map((item) => item.uuid)).toEqual(['u1']);
    expect(filterPool(items, { hideProbe: false, hideUnknown: false })).toHaveLength(4);
    expect(filterPool(items, { hideProbe: false, hideUnknown: false, text: 'ali' })
      .map((item) => item.uuid)).toEqual(['u1']);
  });

  it('probe/占位名判定', () => {
    expect(isProbeName({ name: 'probe-a' })).toBe(true);
    expect(isProbeName({ name: 'test' })).toBe(false);
    expect(isProbeName({ name: 'Alice' })).toBe(false);
    expect(isUnknownName({ name: '' })).toBe(true);
    expect(isUnknownName({ name: '玩家-1234abcd' })).toBe(true);
    expect(isUnknownName({ name: '玩家小明' })).toBe(false);
  });

  it('组卡片派生共享 data 与在线状态', () => {
    const groups = [
      { name: 'A', data: 'd1', premium: { online: true } },
      { name: 'B', data: 'd1' },
    ];
    const view = groupView(groups[0], groups, { d1: { inventory: {} } });
    expect(view.shared).toBe(2);
    expect(view.badge).toBe('共享 data ×2');
    expect(view.online).toBe(true);
    expect(view.hasState).toBe(true);
    expect(groupOnline(groups[1])).toBe(false);
  });
});
