import { describe, expect, it } from 'vitest';
import { dateText, dayText, timeText, vp } from './model.js';

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
});
