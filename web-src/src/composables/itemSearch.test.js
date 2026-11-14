import { describe, expect, it } from 'vitest';
import { filterItems, matchesItem } from './itemSearch.js';

const diamond = {
  id: 'minecraft:diamond',
  name: 'Diamond',
  zh: '钻石',
  py: 'zuanshi',
  initials: 'zs',
};

const sword = {
  id: 'minecraft:diamond_sword',
  name: 'Diamond Sword',
  zh: '钻石剑',
  py: 'zuanshijian',
  initials: 'zsj',
  enchantments: [{ id: 'minecraft:sharpness' }],
  attributes: [{ id: 'minecraft:attack_damage' }],
  components: '{"damage":3}',
};

const potion = {
  id: 'minecraft:potion',
  name: 'Potion',
  zh: '药水',
  potion: { id: 'minecraft:strength' },
};

describe('matchesItem', () => {
  it('匹配中文、英文、ID、拼音与首字母', () => {
    expect(matchesItem(diamond, '钻石')).toBe(true);
    expect(matchesItem(diamond, 'diamond')).toBe(true);
    expect(matchesItem(diamond, 'minecraft:diamond')).toBe(true);
    expect(matchesItem(diamond, 'zuanshi')).toBe(true);
    expect(matchesItem(diamond, 'zs')).toBe(true);
  });

  it('支持 @模组 与 #组件 前缀', () => {
    expect(matchesItem(sword, '@minecraft')).toBe(true);
    expect(matchesItem(sword, '@create')).toBe(false);
    expect(matchesItem(sword, '#sharpness')).toBe(true);
    expect(matchesItem(sword, '#attack_damage')).toBe(true);
    expect(matchesItem(sword, '#unbreaking')).toBe(false);
    expect(matchesItem(potion, '#strength')).toBe(true);
  });

  it('多关键词需要全部命中', () => {
    expect(matchesItem(sword, '钻石 剑')).toBe(true);
    expect(matchesItem(sword, '钻石 药水')).toBe(false);
  });

  it('空查询匹配所有条目', () => {
    expect(matchesItem(diamond, '')).toBe(true);
    expect(matchesItem(diamond, '   ')).toBe(true);
  });
});

describe('filterItems', () => {
  it('无查询时返回原数组引用', () => {
    const rows = [diamond, sword];
    expect(filterItems(rows, '')).toBe(rows);
    expect(filterItems(rows, '  ')).toBe(rows);
  });

  it('有查询时返回过滤后的新数组', () => {
    const rows = [diamond, sword, potion];
    expect(filterItems(rows, '钻石')).toEqual([diamond, sword]);
  });

  it('容忍空列表和 undefined', () => {
    expect(filterItems(undefined, '钻石')).toEqual([]);
    expect(filterItems([], '钻石')).toEqual([]);
  });
});
