import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  clearToken,
  getToken,
  setToken,
  useApi,
} from './useApi.js';

function installSessionStorage() {
  const data = new Map();
  vi.stubGlobal('sessionStorage', {
    getItem: (key) => (data.has(key) ? data.get(key) : null),
    setItem: (key, value) => data.set(key, String(value)),
    removeItem: (key) => data.delete(key),
    clear: () => data.clear(),
  });
  return data;
}

function mockResponse({ status = 200, body = {}, ok } = {}) {
  return {
    status,
    ok: ok ?? (status >= 200 && status < 300),
    text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
  };
}

describe('token 存储', () => {
  beforeEach(() => installSessionStorage());
  afterEach(() => vi.unstubAllGlobals());

  it('set/get/clear 使用同一个 sessionStorage 键', () => {
    expect(getToken()).toBe('');
    setToken('abc');
    expect(getToken()).toBe('abc');
    clearToken();
    expect(getToken()).toBe('');
  });

  it('sessionStorage 不可用时返回空串且不抛错', () => {
    vi.stubGlobal('sessionStorage', {
      getItem: () => { throw new Error('blocked'); },
      setItem: () => { throw new Error('blocked'); },
      removeItem: () => { throw new Error('blocked'); },
    });
    expect(getToken()).toBe('');
    expect(() => setToken('abc')).not.toThrow();
    expect(() => clearToken()).not.toThrow();
  });
});

describe('useApi', () => {
  beforeEach(() => {
    installSessionStorage();
    setToken('token-1');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('request 自动带 Bearer 并解析 JSON', async () => {
    const fetchMock = vi.fn(async () => mockResponse({ body: { ok: true, value: 7 } }));
    vi.stubGlobal('fetch', fetchMock);

    const { request } = useApi();
    const result = await request('/api/demo');

    expect(result.status).toBe(200);
    expect(result.ok).toBe(true);
    expect(result.data).toEqual({ ok: true, value: 7 });
    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers.Authorization).toBe('Bearer token-1');
  });

  it('有 body 时补 Content-Type，保留调用方 headers', async () => {
    const fetchMock = vi.fn(async () => mockResponse({ body: { ok: true } }));
    vi.stubGlobal('fetch', fetchMock);

    const { request } = useApi();
    await request('/api/demo', {
      method: 'POST',
      body: '{}',
      headers: { 'X-Test': '1' },
    });

    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers['Content-Type']).toBe('application/json');
    expect(options.headers['X-Test']).toBe('1');
  });

  it('401 调用 onUnauthorized 并抛 ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => mockResponse({
      status: 401,
      body: { error: 'bad token' },
    })));
    const onUnauthorized = vi.fn();
    const { request } = useApi({ onUnauthorized });

    await expect(request('/api/demo')).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('throwOnUnauthorized=false 时返回 401 结果', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => mockResponse({ status: 401, body: {} })));
    const { request } = useApi({ throwOnUnauthorized: false });

    const result = await request('/api/demo');
    expect(result.status).toBe(401);
    expect(result.ok).toBe(false);
  });

  it('非 JSON 响应被包装成 { error: text }', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => mockResponse({ body: '<html>oops</html>' })));
    const { request } = useApi();

    const result = await request('/api/demo');
    expect(result.data).toEqual({ error: '<html>oops</html>' });
  });

  it('json 成功时直接返回 body', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => mockResponse({ body: { ok: true, rows: [1] } })));
    const { json } = useApi();

    await expect(json('/api/demo')).resolves.toEqual({ ok: true, rows: [1] });
  });

  it('json 在非 2xx 或 ok=false 时抛错', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => mockResponse({
      status: 500,
      body: { ok: false, error: 'boom' },
    })));
    const { json } = useApi();

    await expect(json('/api/demo')).rejects.toThrow('boom');
  });

  it('json 支持函数形式的兜底文案', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => mockResponse({
      status: 503,
      body: {},
    })));
    const { json } = useApi();

    await expect(json('/api/demo', {}, (result) => '状态 ' + result.status))
      .rejects.toThrow('状态 503');
  });
});
