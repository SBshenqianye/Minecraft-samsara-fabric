import { onMounted, ref } from 'vue';
import { clearToken, getToken, setToken, useApi } from './useApi.js';

/**
 * 管理端 token 连接态：token / tokenInput / connected / connecting / error
 * 以及「校验 token → 持久化 → 执行页面自己的加载」这一套流程。
 *
 * onConnected 在 /api/status 通过后执行；onDisconnected 只在显式断开时执行，
 * 401 仅把 connected 置 false，避免后台轮询失败时清空用户正在看的数据。
 */
export function useAuth({ autoConnect = false, onConnected, onDisconnected } = {}) {
  const token = ref(getToken());
  const tokenInput = ref(token.value);
  const connected = ref(false);
  const connecting = ref(false);
  const error = ref('');

  const { request } = useApi({
    token: () => token.value,
    onUnauthorized: () => {
      connected.value = false;
    },
  });

  async function connect() {
    connecting.value = true;
    error.value = '';
    try {
      const value = String(tokenInput.value || '').trim();
      if (!value) throw new Error('请输入 token');
      token.value = value;
      const result = await request('/api/status');
      if (result.status !== 200) {
        throw new Error(result.data.error || ('连接失败：' + result.status));
      }
      setToken(value);
      connected.value = true;
      await onConnected?.();
    } catch (e) {
      error.value = e.message || String(e);
      connected.value = false;
    } finally {
      connecting.value = false;
    }
  }

  function logout() {
    clearToken();
    token.value = '';
    tokenInput.value = '';
    connected.value = false;
    onDisconnected?.();
  }

  onMounted(() => {
    if (autoConnect && token.value) connect();
  });

  return {
    token,
    tokenInput,
    connected,
    connecting,
    error,
    connect,
    logout,
    /** 原始请求，沿用页面已有的 { status, data } 判断方式。 */
    api: request,
  };
}
