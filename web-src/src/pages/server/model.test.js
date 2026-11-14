import { describe, expect, it } from 'vitest';
import {
  auditEntryText,
  autoFillBan,
  backupStats,
  buildContent,
  defaultJsonItem,
  diffLineClass,
  displayPath,
  filterJsonItems,
  filterPlayers,
  formatBytes,
  formatTime,
  groupProperties,
  isDraftDirty,
  parseChatSegments,
  parseDraft,
  splitMotdLines,
  splitPropertyLines,
  stampNow,
} from './model.js';

const propertiesFile = {
  id: 'server-properties',
  name: 'server.properties',
  kind: 'properties',
  content: '# comment\nmotd=Hello\nonline-mode=true\npause-when-empty-seconds=0\n',
};

describe('server model', () => {
  it('字节数按 B/KB/MB/GB 展示', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2.0 KB');
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB');
    expect(formatBytes(2.5 * 1024 * 1024 * 1024)).toBe('2.50 GB');
  });

  it('时间空值回退为破折号', () => {
    expect(formatTime(0)).toBe('—');
    expect(formatTime(null)).toBe('—');
    expect(formatTime(1700000000000)).not.toBe('—');
  });

  it('properties 拆分保留注释、空行与首次等号后的值', () => {
    const lines = splitPropertyLines('\uFEFF# comment\n\nmotd=Hello=World\n! another\nraw');
    expect(lines[0]).toEqual({ type: 'comment', raw: '# comment' });
    expect(lines[1]).toEqual({ type: 'blank', raw: '' });
    expect(lines[2]).toEqual({
      type: 'prop', key: 'motd', value: 'Hello=World', originalValue: 'Hello=World',
    });
    expect(lines[3]).toEqual({ type: 'comment', raw: '! another' });
    expect(lines[4]).toEqual({ type: 'raw', raw: 'raw' });
  });

  it('时间戳固定为本地时区带偏移', () => {
    const stamp = stampNow(new Date(2026, 0, 2, 3, 4, 5));
    expect(stamp.startsWith('2026-01-02 03:04:05 ')).toBe(true);
    expect(stamp).toMatch(/[+-]\d{4}$/);
  });

  it('properties 草稿按分组排序并支持搜索说明', () => {
    const draft = parseDraft(propertiesFile);
    const all = groupProperties('server-properties', draft.tokens, '');
    expect(all.count).toBe(3);
    expect(all.groups[0].name).toBe('网络与状态');
    const searched = groupProperties('server-properties', draft.tokens, '暂停');
    expect(searched.count).toBe(1);
    expect(searched.groups[0].items[0].token.key).toBe('pause-when-empty-seconds');
  });

  it('草稿只在与原文不同时才算脏', () => {
    const draft = parseDraft(propertiesFile);
    expect(isDraftDirty(propertiesFile, draft)).toBe(false);
    draft.tokens.find((token) => token.key === 'motd').value = 'Changed';
    expect(isDraftDirty(propertiesFile, draft)).toBe(true);
    draft.tokens.find((token) => token.key === 'motd').value = 'Hello';
    expect(isDraftDirty(propertiesFile, draft)).toBe(false);
    // 行尾差异不算修改。
    expect(isDraftDirty({ ...propertiesFile, content: propertiesFile.content.replace(/\n/g, '\r\n') }, draft))
      .toBe(false);
  });

  it('JSON 草稿按规范化内容比较并回写两空格缩进', () => {
    const file = {
      id: 'whitelist', name: 'whitelist.json', kind: 'json_array',
      content: '[{"uuid":"a","name":"A"}]',
    };
    const draft = parseDraft(file);
    expect(draft.kind).toBe('json');
    expect(isDraftDirty(file, draft)).toBe(false);
    draft.data.push({ uuid: 'b', name: 'B' });
    expect(isDraftDirty(file, draft)).toBe(true);
    expect(buildContent(draft)).toBe(JSON.stringify(draft.data, null, 2) + '\n');
    expect(buildContent(draft)).toContain('\n  {\n    "uuid": "a"');
  });

  it('JSON 解析失败降级为源码编辑', () => {
    const draft = parseDraft({ id: 'ops', name: 'ops.json', kind: 'json_array', content: '{oops' });
    expect(draft.kind).toBe('text');
    expect(draft.parseError).toBe(true);
  });

  it('eula 文件缺少配置项时补一条', () => {
    const draft = parseDraft({ id: 'eula', name: 'eula.txt', kind: 'eula', content: '#By changing' });
    expect(draft.tokens.some((token) => token.type === 'prop' && token.key === 'eula')).toBe(true);
  });

  it('JSON 列表搜索匹配整条与字段说明', () => {
    const items = [{ uuid: 'aaa', name: 'Alice' }, { uuid: 'bbb', name: 'Bob' }];
    expect(filterJsonItems('whitelist', items, '').count).toBe(2);
    expect(filterJsonItems('whitelist', items, 'bob').items[0].index).toBe(1);
    expect(filterJsonItems('whitelist', items, '玩家名').count).toBe(2);
  });

  it('新增条目与封禁自动补全使用注入的时间', () => {
    expect(defaultJsonItem('ops')).toEqual({
      uuid: '', name: '', level: 4, bypassesPlayerLimit: false,
    });
    const item = defaultJsonItem('banned-ips', 'STAMP');
    expect(item.created).toBe('STAMP');
    const blank = autoFillBan('banned-players', {
      uuid: '', name: '', created: '', expires: '', source: '', reason: '',
    }, 'STAMP');
    expect(blank.created).toBe('STAMP');
    expect(blank.expires).toBe('forever');
    expect(blank.source).toBe('Web 控制台');
    expect(blank.reason).toBe('Banned by an operator.');
  });

  it('玩家搜索匹配名字与 UUID', () => {
    const players = [
      { name: 'Alice', uuid: '1111', online: true },
      { name: 'Bob', uuid: '2222', online: false },
    ];
    expect(filterPlayers(players, 'ali')).toHaveLength(1);
    expect(filterPlayers(players, '222')).toHaveLength(1);
    expect(filterPlayers(players, '')).toHaveLength(2);
  });

  it('显示路径按服务器根目录分隔符拼接', () => {
    expect(displayPath({ name: 'ops.json' }, 'C:\\server\\')).toBe('C:\\server\\ops.json');
    expect(displayPath({ name: 'ops.json', path: '/srv/ops.json' }, 'C:\\server')).toBe('/srv/ops.json');
  });

  it('审计与时间线展示文本', () => {
    const text = auditEntryText({
      action: '/api/config', createdAt: 1700000000000, rollbackable: true,
      fileCount: 2, bytes: 2048, note: 'x',
    });
    expect(text.title.startsWith('/api/config · ')).toBe(true);
    expect(text.meta).toBe('可回滚 · 2 文件 · 2.0 KB · x');
    const stats = backupStats({
      timeline: { headId: 'b1', nodes: [1, 2] }, worldBytes: 1024, freeBytes: 2048, keep: 5,
    });
    expect(stats[0]).toEqual(['备份数', '2']);
    expect(stats[1]).toEqual(['时间线头节点', 'b1']);
    expect(stats[5]).toEqual(['保留', '最新 5 份 + 祖先']);
  });

  it('diff 行按前后缀着色', () => {
    expect(diffLineClass('+add')).toBe('server-audit-line-add');
    expect(diffLineClass('-del')).toBe('server-audit-line-del');
    expect(diffLineClass(' ctx')).toBe('server-audit-line-ctx');
  });

  it('§ 颜色文本解析为带样式片段', () => {
    const segments = parseChatSegments('§e[公告] §f欢迎 §l粗');
    expect(segments).toHaveLength(3);
    expect(segments[0].style.color).toBe('#ffff55');
    expect(segments[0].text).toBe('[公告] ');
    expect(segments[1].style.color).toBe('#ffffff');
    expect(segments[2].style.fontWeight).toBe('700');
    expect(parseChatSegments('&a绿色')[0].style.color).toBe('#55ff55');
    expect(parseChatSegments('§r重置')[0].style.color).toBe('');
  });

  it('MOTD 预览按换行或 58 字符断成两行', () => {
    expect(splitMotdLines('one\ntwo')).toEqual(['one', 'two']);
    const long = 'a'.repeat(70);
    const [first, second] = splitMotdLines(long);
    expect(first.length).toBe(58);
    expect(second.length).toBe(12);
  });
});
