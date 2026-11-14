import { describe, expect, it } from 'vitest';
import {
  betSections,
  claims,
  dashboardModel,
  dateText,
  dayText,
  historyRows,
  insuranceRows,
  roundState,
  statsRows,
  timeText,
  valueRows,
  valueText,
  vp,
} from './model.js';

describe('lottery model', () => {
  it('VP 金额保留整数或最多两位小数', () => {
    expect(vp(0)).toBe('0 VP');
    expect(vp(12)).toBe('12 VP');
    expect(vp(12.5)).toBe('12.5 VP');
    expect(vp(12.345)).toBe('12.35 VP');
    expect(vp('abc')).toBe('0 VP');
    expect(vp(null)).toBe('0 VP');
  });

  it('游戏日 ID 转中文', () => {
    expect(dayText('game-194')).toBe('第 194 游戏日');
    expect(dayText('custom')).toBe('custom');
    expect(dayText('')).toBe('—');
  });

  it('时间与日期空值回退', () => {
    expect(timeText(0)).toBe('—');
    expect(dateText(0)).toBe('—');
    expect(typeof timeText(1700000000000)).toBe('string');
    expect(timeText(1700000000000)).not.toBe('—');
    expect(typeof dateText(1700000000000)).toBe('string');
  });

  const model = {
    enabled: true,
    mode: 'game',
    gold: { enabled: true, price: 2400, blockValue: 12 },
    countingDay: {
      day: 'game-12', deathCount: 3, seedValue: 42, pot: 100, tickets: 4,
      state: 'BETTING', bets: [{ name: 'Alice', guess: 2, tickets: 2, value: 20 }],
    },
    bettingDay: {
      day: 'game-13', ownPot: 30, pot: 30, tickets: 1, cutoff: '20:00', drawTime: '21:00',
      bets: [{ name: 'Bob', guess: 1, tickets: 1, value: 10 }],
    },
    activePot: 100,
    bettingPot: 30,
    rollover: 50,
    fund: { value: 60, items: [{ id: 'minecraft:iron_ingot', zh: '铁锭', count: 3 }] },
    claims: [{ owner: 'Alice', reason: '中奖退款', at: 1700000000000, items: [] }],
    insurance: {
      policies: [
        { id: 'i1', owner: 'Alice', value: 100, premium: 200, expiresAt: 1700000000000, status: 'ACTIVE' },
        { id: 'i2', owner: 'Bob', value: 10, premium: 20, status: 'PENDING' },
      ],
    },
    history: [
      { day: 'game-11', deathCount: 5, winnerCount: 1, pot: 200, rake: 10, winners: ['Alice'] },
      { day: 'game-10', deathCount: 2, winnerCount: 0, pot: 100, rake: 0, rollover: true },
    ],
  };

  it('概览统计行覆盖开关/金价/轮次/基金', () => {
    const rows = statsRows(model);
    expect(rows).toHaveLength(13);
    expect(rows[0]).toEqual(['开关', '启用']);
    expect(rows[2]).toEqual(['国际金价', '2400.00 USD/oz']);
    expect(rows[4]).toEqual(['统计中', '第 12 游戏日']);
    expect(rows[12]).toEqual(['服务器基金', '60 VP']);
  });

  it('轮次徽标与下注分区', () => {
    expect(roundState(model)).toEqual({ state: 'BETTING', tone: 'ok', label: '统计中' });
    expect(roundState({ countingDay: { state: 'SETTLED' } }).label).toBe('已结算');
    expect(roundState({}).tone).toBe('warn');
    const sections = betSections(model);
    expect(sections[0].title).toBe('待开奖 · 第 12 游戏日');
    expect(sections[0].rows[0].meta).toBe('猜 2 人 · 2 张 · 投入 20 VP');
    expect(sections[1].rows[0].title).toBe('Bob');
  });

  it('待领取、保险与历史行文案', () => {
    const claimRows = claims(model);
    expect(claimRows[0].owner).toBe('Alice');
    expect(claimRows[0].meta.startsWith('中奖退款 · ')).toBe(true);
    const ins = insuranceRows(model);
    expect(ins.total).toBe(2);
    expect(ins.pending).toBe(1);
    expect(ins.rows[0].status).toBe('生效中');
    expect(ins.rows[0].meta).toContain('保费 200 VP');
    expect(ins.rows[1].tone).toBe('warn');
    const hist = historyRows(model);
    expect(hist[0].title).toBe('第 11 游戏日 · 死亡 5');
    expect(hist[1].rollover).toBe(true);
  });

  it('概览大屏数据比例正常', () => {
    const dash = dashboardModel(model);
    expect(dash.fund.value).toBe(60);
    expect(dash.fund.itemCount).toBe(1);
    expect(dash.lottery.pot).toBe(100);
    expect(dash.lottery.sub).toContain('1 张下注');
    expect(dash.insurance.active).toBe(1);
    expect(dash.insurance.legend.some((item) => item.status === 'PENDING' && item.count === 1)).toBe(true);
    expect(dash.history).toHaveLength(2);
    expect(dash.history[0].day).toBe('10');
    expect(dash.history[1].winner).toBe(true);
  });

  it('价值表行显示中文名、ID 与不收', () => {
    expect(valueText({ value: 0 })).toBe('不收');
    expect(valueText({ value: 12.5 })).toBe('12.5 VP');
    const rows = valueRows([
      { id: 'minecraft:iron_ingot', name: 'Iron Ingot', zh: '铁锭', category: '材料', value: 1 },
      { id: 'minecraft:dirt', name: 'Dirt', zh: '泥土', value: 0 },
    ]);
    expect(rows[0].name).toBe('铁锭');
    expect(rows[0].id).toBe('Iron Ingot · minecraft:iron_ingot');
    expect(rows[0].value).toBe('1 VP');
    expect(rows[1].zero).toBe(true);
    expect(rows[1].value).toBe('不收');
  });
});
