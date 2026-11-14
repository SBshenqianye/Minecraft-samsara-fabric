import { describe, expect, it } from 'vitest';
import {
  createAnnouncement,
  duplicateAnnouncement,
  emptyModel,
  normalizeModel,
  previewChannels,
  summaryText,
} from './model.js';

describe('announcements model', () => {
  it('默认公告字段完整', () => {
    const item = createAnnouncement();
    expect(item.name).toBe('新公告');
    expect(item.trigger).toBe('manual');
    expect(item.chat).toBe(true);
    expect(item.bossbarColor).toBe('BLUE');
    expect(item.priority).toBe(0);
  });

  it('emptyModel 提供全部可选值', () => {
    const model = emptyModel();
    expect(model.announcements).toEqual([]);
    expect(model.triggers).toContain('manual');
    expect(model.targets).toContain('players');
    expect(model.colors).toContain('BLUE');
    expect(model.overlays).toContain('PROGRESS');
    expect(model.clickActions).toContain('run');
  });

  it('normalizeModel 容忍缺字段并回退默认选项', () => {
    const model = normalizeModel({ announcements: [{ name: 'A' }], triggers: [] });
    expect(model.announcements).toHaveLength(1);
    expect(model.triggers).toContain('interval');
    expect(model.history).toEqual([]);
  });

  it('summaryText 区分是否脏', () => {
    expect(summaryText(0, false)).toBe('0 条公告');
    expect(summaryText(3, true)).toBe('3 条公告 · 有未保存修改');
  });

  it('duplicateAnnouncement 深拷贝并清空 id', () => {
    const source = { id: 'a1', name: '公告 A', chatText: 'x' };
    const copy = duplicateAnnouncement(source);
    expect(copy.id).toBe('');
    expect(copy.name).toBe('公告 A 副本');
    expect(copy.chatText).toBe('x');
    copy.chatText = 'y';
    expect(source.chatText).toBe('x');
  });

  it('previewChannels 容忍空值', () => {
    expect(previewChannels(null)).toEqual([]);
    expect(previewChannels({ channels: ['chat'] })).toEqual(['chat']);
  });
});
