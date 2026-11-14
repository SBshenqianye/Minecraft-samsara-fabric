/**
 * 统一 HTTP 层：token 读取、JSON 解析、401 处理。
 * 页面不再各自复制 fetch + Authorization + response.text() 逻辑。
 */

export const TOKEN_KEY = 'samsara-token';

export function getToken() {
  try {
    return sessionStorage.getItem(TOKEN_KEY) || '';
  } catch {
    return '';
  }
}

export function setToken(value) {
  try {
    if (value) sessionStorage.setItem(TOKEN_KEY, value);
    else sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    // 隐私模式或配额满：只保留内存里的 token，不阻塞本次会话。
  }
}

export function clearToken() {
  setToken('');
}

export class ApiError extends Error {
  constructor(message, status, data) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.data = data;
  }
}

/**
 * @param {object} [options]
 * @param {() => string} [options.token] 默认每次请求都从 sessionStorage 读取。
 * @param {() => void} [options.onUnauthorized] 401 时通知页面退出连接态。
 * @param {boolean} [options.throwOnUnauthorized=true] raw request 是否直接抛出 401。
 */
export function useApi({
  token = getToken,
  onUnauthorized = null,
  throwOnUnauthorized = true,
} = {}) {
  /** 原始请求：返回 { response, status, ok, data }，由调用方决定状态码语义。 */
  async function request(path, options = {}) {
    const headers = Object.assign({}, options.headers || {});
    const value = token();
    if (value) headers.Authorization = 'Bearer ' + value;
    if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
    const response = await fetch(path, Object.assign({}, options, { headers }));
    const text = await response.text();
    let data;
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      data = { error: text };
    }
    const result = { response, status: response.status, ok: response.ok, data };
    if (result.status === 401) {
      onUnauthorized?.();
      if (throwOnUnauthorized) {
        throw new ApiError('token 无效或已失效', 401, data);
      }
    }
    return result;
  }

  /**
   * 严格请求：返回解析后的 body；非 2xx 或 body.ok === false 时抛错。
   * fallback 可以是字符串，也可以根据响应生成文案。
   */
  async function json(path, options = {}, fallback = '请求失败') {
    let result;
    try {
      result = await request(path, options);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        throw new ApiError(error.data?.error || 'token 无效或已失效', 401, error.data);
      }
      throw error;
    }
    if (!result.ok || result.data.ok === false) {
      const message = typeof fallback === 'function' ? fallback(result) : fallback;
      throw new ApiError(result.data.error || message, result.status, result.data);
    }
    return result.data;
  }

  /**
   * 下载接口：直接取二进制，不经过 JSON 解析。
   * 只在确实需要文件流的页面使用（例如备份下载）。
   */
  async function download(path, filename) {
    const headers = {};
    const value = token();
    if (value) headers.Authorization = 'Bearer ' + value;
    const response = await fetch(path, { headers });
    if (response.status === 401) {
      onUnauthorized?.();
      throw new ApiError('token 无效或已失效', 401, null);
    }
    if (!response.ok) throw new ApiError('HTTP ' + response.status, response.status, null);
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename || 'download';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  return { request, json, download };
}
