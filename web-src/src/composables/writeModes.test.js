import { describe, expect, it } from 'vitest';
import { modeHint, modeLabel, WRITE_MODES } from './writeModes.js';

describe('writeModes', () => {
  it('暴露三种写入模式', () => {
    expect(WRITE_MODES.map((mode) => mode.id)).toEqual(['live', 'preset', 'simulate']);
  });

  it('返回已知模式的标签与提示', () => {
    expect(modeLabel('live')).toBe('实时');
    expect(modeLabel('preset')).toBe('预设');
    expect(modeLabel('simulate')).toBe('模拟');
    expect(modeHint('live')).toContain('立即写入');
  });

  it('未知模式回退为模拟', () => {
    expect(modeLabel('unknown')).toBe('模拟');
    expect(modeHint('unknown')).toBe('');
  });
});
