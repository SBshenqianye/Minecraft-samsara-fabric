import { describe, expect, it } from 'vitest';
import { formatBytes, formatTime, splitPropertyLines } from './model.js';

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
});
