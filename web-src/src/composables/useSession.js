import { computed, reactive } from 'vue';
import { clearToken, getToken, setToken, useApi } from './useApi.js';

/**
 * 管理端连接会话：token 只存 sessionStorage，连接状态由 /api/status 校验。
 * 连接成功后广播 `samsara:connected`，各页面自行加载数据。
 */
export const session = reactive({
  token: getToken(),
  connected: false,
  connecting: false,
  error: '',
});

const api = useApi({ token: () => session.token });

export const connectionText = computed(() => {
  if (session.connecting) return '连接中…';
  return session.connected ? '已连接' : '未连接';
});

export async function connect(value) {
  const token = String(value || '').trim();
  if (!token) {
    session.error = '请输入 token';
    return false;
  }
  session.token = token;
  session.connecting = true;
  session.error = '';
  try {
    const result = await api.request('/api/status');
    if (result.status !== 200) {
      throw new Error(result.data?.error || ('连接失败：' + result.status));
    }
    setToken(token);
    session.connected = true;
    window.dispatchEvent(new CustomEvent('samsara:connected'));
    return true;
  } catch (error) {
    session.error = error.message || String(error);
    session.connected = false;
    return false;
  } finally {
    session.connecting = false;
  }
}

/** 页面启动时用已保存的 token 静默连接。 */
export async function autoConnect() {
  if (session.connected || session.connecting) return session.connected;
  const token = getToken();
  if (!token) return false;
  session.token = token;
  return connect(token);
}

export function disconnect() {
  clearToken();
  session.token = '';
  session.connected = false;
  session.error = '';
  window.dispatchEvent(new CustomEvent('samsara:disconnected'));
}
