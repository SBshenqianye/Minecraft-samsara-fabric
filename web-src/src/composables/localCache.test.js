import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readCache, removeCache, writeCache } from './localCache.js';

function installMemoryStorage() {
  const data = new Map();
  vi.stubGlobal('localStorage', {
    getItem: (key) => (data.has(key) ? data.get(key) : null),
    setItem: (key, value) => data.set(key, String(value)),
    removeItem: (key) => data.delete(key),
    clear: () => data.clear(),
  });
  return data;
}

describe('localCache', () => {
  beforeEach(() => {
    installMemoryStorage();
    vi.useFakeTimers({ now: 1000 });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('写入后可按原类型读回', () => {
    writeCache('demo', { rows: [1, 2, 3] });
    expect(readCache('demo')).toEqual({ rows: [1, 2, 3] });
  });

  it('maxAgeMs 过期后返回 null', () => {
    writeCache('demo', { ok: true });
    vi.setSystemTime(1300);
    expect(readCache('demo', 400)).toEqual({ ok: true });
    vi.setSystemTime(1500);
    expect(readCache('demo', 400)).toBeNull();
  });

  it('非法 JSON / 非对象缓存返回 null', () => {
    localStorage.setItem('bad-json', '{');
    localStorage.setItem('array', '[1,2]');
    expect(readCache('bad-json')).toBeNull();
    expect(readCache('array')).toBeNull();
  });

  it('removeCache 删除指定键', () => {
    writeCache('demo', 1);
    removeCache('demo');
    expect(readCache('demo')).toBeNull();
  });

  it('localStorage 抛错时静默降级', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => { throw new Error('quota'); },
      setItem: () => { throw new Error('quota'); },
      removeItem: () => { throw new Error('quota'); },
    });
    expect(() => writeCache('demo', 1)).not.toThrow();
    expect(readCache('demo')).toBeNull();
    expect(() => removeCache('demo')).not.toThrow();
  });
});
