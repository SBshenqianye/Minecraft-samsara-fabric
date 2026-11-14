import { describe, expect, it } from 'vitest';
import {
  QUICK_GROUPS,
  addHistory,
  completePreview,
  filterQuickGroups,
  historyValue,
  isDangerous,
  responseSummary,
} from './model.js';

describe('server-console model', () => {
  it('快捷命令分组包含常用/世界/玩家/调试/危险', () => {
    expect(QUICK_GROUPS.map((group) => group.label)).toEqual(['常用', '世界', '玩家', '调试', '危险']);
    expect(QUICK_GROUPS.find((group) => group.label === '危险').items[0].command).toBe('stop');
  });

  it('危险命令识别', () => {
    expect(isDangerous('stop')).toBe(true);
    expect(isDangerous('stop now')).toBe(true);
    expect(isDangerous('kill @e')).toBe(true);
    expect(isDangerous('list')).toBe(false);
    expect(isDangerous('samsara where')).toBe(false);
    expect(isDangerous('')).toBe(false);
  });

  it('快捷命令过滤', () => {
    const filtered = filterQuickGroups(QUICK_GROUPS, 'save');
    expect(filtered).toHaveLength(1);
    expect(filtered[0].items[0].command).toBe('save-all');
    expect(filterQuickGroups(QUICK_GROUPS, '')).toEqual(QUICK_GROUPS);
  });

  it('历史去重、上限与取值', () => {
    const history = addHistory([], 'list');
    expect(history).toEqual(['list']);
    expect(addHistory(history, 'list')).toBe(history);
    expect(addHistory(history, 'tps')).toEqual(['tps', 'list']);
    expect(historyValue(history, 0)).toBe('list');
    expect(historyValue(history, 5)).toBe('');
  });

  it('补全预览与响应摘要', () => {
    expect(completePreview('samsara ', { start: 8, end: 8 }, { text: 'where' })).toBe('samsara where');
    expect(completePreview('abc', null, { text: 'x' })).toBe('');
    expect(responseSummary({ timeMs: 12 })).toBe('完成 · 12 ms');
    expect(responseSummary({ timeMs: 3, stopping: true })).toBe('完成 · 3 ms · 服务器正在停止');
  });
});
