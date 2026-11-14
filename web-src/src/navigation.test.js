import { describe, expect, it } from 'vitest';
import {
  KEEP_ALIVE_PAGES,
  LEGACY_ALIASES,
  SECTIONS,
  SECTION_NAV,
  WORKSPACES,
} from './navigation.js';

describe('navigation', () => {
  it('工作区 id 唯一且包含本体与 samsara', () => {
    const ids = WORKSPACES.map((workspace) => workspace.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toEqual(['vanilla', 'samsara']);
  });

  it('二级分区 id 唯一且 target 为绝对路径', () => {
    for (const sections of Object.values(SECTIONS)) {
      const ids = sections.map((section) => section.id);
      expect(new Set(ids).size).toBe(ids.length);
      expect(sections.every((section) => section.target.startsWith('/'))).toBe(true);
    }
  });

  it('三级导航的 key 都对应一个二级分区', () => {
    const sectionIds = new Set(SECTIONS.samsara.map((section) => section.id));
    for (const key of Object.keys(SECTION_NAV)) {
      expect(sectionIds.has(key)).toBe(true);
      const items = SECTION_NAV[key];
      const ids = items.map((item) => item.id);
      expect(new Set(ids).size).toBe(ids.length);
      expect(items.every((item) => item.target.startsWith('/'))).toBe(true);
    }
  });

  it('旧链接别名都是绝对路径', () => {
    for (const target of Object.values(LEGACY_ALIASES)) {
      expect(target.startsWith('/')).toBe(true);
    }
  });

  it('保活页面名单无重复', () => {
    expect(new Set(KEEP_ALIVE_PAGES).size).toBe(KEEP_ALIVE_PAGES.length);
  });
});
