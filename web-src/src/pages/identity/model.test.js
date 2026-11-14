import { describe, expect, it } from 'vitest';
import {
  advancementGroups,
  advancementLayout,
  candidateView,
  filterPool,
  formatStat,
  groupOnline,
  groupSkin,
  groupView,
  isProbeName,
  isUnknownName,
  isUuid,
  normalizeSettings,
  poolModel,
  shortUuid,
  statsForTab,
  suggestGroups,
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
    expect(isProbeName({ name: 'probe-1' })).toBe(true);
    expect(isProbeName({ name: 'test' })).toBe(true);
    expect(isProbeName({ name: 'Alice' })).toBe(false);
    expect(isUnknownName({ name: '' })).toBe(true);
    expect(isUnknownName({ name: '玩家-1234abcd' })).toBe(true);
    expect(isUnknownName({ name: '玩家小明' })).toBe(false);
    expect(isProbeName({ name: '玩家-1234abcd' })).toBe(false);
  });

  it('组卡片派生共享 data 与在线状态', () => {
    const groups = [
      { name: 'A', data: 'd1', premium: 'p1' },
      { name: 'B', data: 'd1' },
    ];
    const view = groupView(groups[0], groups, { d1: { syncBlocked: true } }, { p1: true });
    expect(view.shared).toBe(2);
    expect(view.online).toBe(true);
    expect(view.syncBlocked).toBe(true);
    expect(groupOnline(groups[1], { p1: true })).toBe(false);
    expect(groupSkin(groups[0], { p1: 'Alice' })).toEqual({ uuid: 'p1', name: 'Alice' });
  });

  it('池子分三类并保留磁盘 data 与空白 data', () => {
    const pool = poolModel({
      groups: [{ data: 'd-group', premium: 'p1', offline: null }],
      candidates: [
        { uuid: 'p1', name: 'Alice', type: 'premium' },
        { uuid: 'p2', name: 'Bob', type: 'premium' },
        { uuid: 'o1', name: 'Carol', type: 'offline' },
        { uuid: 'd-group', name: 'Alice', type: 'premium' },
      ],
      dataList: [{ uuid: 'd-disk', label: '磁盘数据', group: 'X', backups: 2 }],
      generated: ['d-blank'],
      modelStates: { 'd-old': {} },
      online: { p2: true },
      filter: {},
    });
    expect(pool.counts.premium).toBe(2);
    expect(pool.premium.map((row) => row.uuid)).toContain('p2');
    expect(pool.premium.map((row) => row.uuid)).not.toContain('p1');
    expect(pool.premium.find((row) => row.uuid === 'p2').online).toBe(true);
    expect(pool.counts.offline).toBe(1);
    expect(pool.data.map((row) => row.uuid)).toEqual(expect.arrayContaining(['o1', 'd-disk', 'd-blank', 'd-old']));
    expect(pool.data.find((row) => row.uuid === 'd-disk').badge).toBe('2 个备份');
    expect(pool.data.find((row) => row.uuid === 'd-blank').canRemove).toBe(true);
    expect(pool.poolText).toContain('账号候选');
  });

  it('相似 ID 推荐合并同名的正版/离线账号', () => {
    const suggestions = suggestGroups([
      { uuid: 'p1', name: 'Alice', type: 'premium' },
      { uuid: 'o1', name: 'alice', type: 'offline' },
      { uuid: 'p2', name: 'Bob', type: 'premium' },
    ], [{ name: 'Bob', premium: 'p2' }]);
    expect(suggestions).toHaveLength(1);
    expect(suggestions[0].premium.uuid).toBe('p1');
    expect(suggestions[0].offline.uuid).toBe('o1');
  });

  it('统计分类与格式化', () => {
    const stats = {
      custom: { 'minecraft:deaths': 3, 'minecraft:play_time': 72000, 'minecraft:walk_one_cm': 1234 },
      items: { mined: { 'minecraft:stone': 12 }, crafted: {} },
      mobs: { killed: { 'minecraft:zombie': 4 }, killed_by: {} },
      names: { 'minecraft:zombie': '僵尸' },
    };
    const custom = statsForTab(stats, 'custom');
    expect(custom.rows.map((row) => row.label)).toEqual(['游玩时间', '步行距离', '死亡']);
    expect(custom.rows[0].value).toBe('1.0 小时');
    expect(custom.rows[1].value).toBe('12.3 米');
    expect(custom.rows[2].value).toBe('3');
    const items = statsForTab(stats, 'items');
    expect(items.sections).toHaveLength(1);
    expect(items.sections[0].label).toBe('挖掘 · 1');
    expect(items.sections[0].items[0].count).toBe('12');
    const mobs = statsForTab(stats, 'mobs');
    expect(mobs.sections[0].items[0].name).toBe('僵尸');
    expect(formatStat('damage_dealt', 100)).toBe('10.0');
  });

  it('成就按四类分组并排版成树', () => {
    const nodes = [
      { id: 'minecraft:story/root', title: 'Minecraft', parent: null, done: true },
      { id: 'minecraft:story/mine_stone', title: '石器时代', parent: 'minecraft:story/root', done: true },
      { id: 'minecraft:adventure/root', title: '冒险', parent: null, done: false },
      { id: 'minecraft:nether/root', title: '下界', parent: null, done: false },
      { id: 'minecraft:husbandry/root', title: '农牧业', parent: null, done: false },
    ];
    const groups = advancementGroups(nodes);
    expect(groups.story).toHaveLength(2);
    expect(groups.adventure).toHaveLength(1);
    expect(groups.nether).toHaveLength(1);
    expect(groups.husbandry).toHaveLength(1);
    const layout = advancementLayout(nodes);
    expect(layout.points).toHaveLength(5);
    expect(layout.edges).toHaveLength(1);
    expect(layout.doneCount).toBe(2);
    expect(layout.width).toBeGreaterThan(300);
    expect(layout.pathOf(nodes[1])).toEqual(['minecraft:story/mine_stone', 'minecraft:story/root']);
  });
});
