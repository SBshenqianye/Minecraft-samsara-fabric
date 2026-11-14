import { describe, expect, it } from 'vitest';
import { fragmentStyle, messageSummary, previewFragments } from './model.js';

describe('output model', () => {
  it('片段颜色/粗斜/下划线/删除线组合', () => {
    expect(fragmentStyle({ color: 'gold', bold: true })).toEqual({
      color: '#ffaa00', fontWeight: '700',
    });
    expect(fragmentStyle({ underline: true, strikethrough: true })).toEqual({
      textDecorationLine: 'underline line-through',
    });
    expect(fragmentStyle({ color: 'unknown' })).toEqual({});
    expect(fragmentStyle(null)).toEqual({});
  });

  it('消息信息行', () => {
    expect(messageSummary(null)).toBe('');
    expect(messageSummary({ key: 'join', description: '进服', variables: 2, buttons: 1 }))
      .toBe('join · 进服 · 变量 2 · 按钮 1');
  });

  it('预览片段保留可点击提示', () => {
    const rows = previewFragments({
      fragments: [
        { text: 'hello', color: 'green' },
        { text: '[按钮]', clickable: true, clickCommand: '/spawn', hover: '点击' },
        { text: null },
      ],
    });
    expect(rows).toHaveLength(3);
    expect(rows[0].style.color).toBe('#55ff55');
    expect(rows[1].clickable).toBe(true);
    expect(rows[1].title).toBe('/spawn 点击');
    expect(rows[2].text).toBe('');
  });
});
