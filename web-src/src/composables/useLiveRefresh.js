import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

const INTERVALS = { live: 1000, preset: 4000, simulate: 0 };

/**
 * 三种模式下的静默刷新。
 *
 * - 实时：1 秒轮询，游戏内背包/容器变化会同步到 Web。
 * - 预设：4 秒轮询，只做提示性刷新，不覆盖本地未提交修改。
 * - 模拟：完全不轮询，保持纯本地演练。
 *
 * 并发控制：inFlight 锁 + AbortController + generation 序号，保证同一时刻只有一个
 * 读取在跑；目标或模式切换时旧请求结果会被丢弃；页面隐藏或卸载时立即停止。
 * apply 由调用方再做一次「脏数据/保存中/光标」检查，形成双检锁。
 */
export function useLiveRefresh(options) {
  const {
    mode,
    key = () => '',
    canRefresh = () => true,
    fetchSnapshot,
    applySnapshot,
  } = options;

  const refreshing = ref(false);
  const lastSyncAt = ref('');
  let timer = null;
  let controller = null;
  let inFlight = false;
  let generation = 0;
  let disposed = false;

  function clearTimer() {
    if (timer) {
      window.clearTimeout(timer);
      timer = null;
    }
  }

  function schedule() {
    clearTimer();
    if (disposed) return;
    const interval = INTERVALS[mode()] || 0;
    if (!interval) return;
    timer = window.setTimeout(run, interval);
  }

  async function run() {
    if (disposed || inFlight) {
      schedule();
      return;
    }
    if (document.hidden || !canRefresh()) {
      schedule();
      return;
    }
    inFlight = true;
    refreshing.value = true;
    const current = ++generation;
    controller = new AbortController();
    try {
      const snapshot = await fetchSnapshot(controller.signal);
      if (!disposed && current === generation && snapshot) {
        // applySnapshot 内部再次检查本地状态，避免请求在途期间用户开始编辑。
        if (applySnapshot(snapshot)) lastSyncAt.value = new Date().toLocaleTimeString();
      }
    } catch (error) {
      if (error?.name !== 'AbortError') {
        // 静默刷新失败不应打断编辑；下一次轮询会重试。
      }
    } finally {
      inFlight = false;
      refreshing.value = false;
      controller = null;
      schedule();
    }
  }

  function invalidate() {
    generation++;
    controller?.abort();
    clearTimer();
    schedule();
  }

  onMounted(() => {
    watch(mode, invalidate);
    watch(key, invalidate);
    schedule();
  });

  onBeforeUnmount(() => {
    disposed = true;
    generation++;
    controller?.abort();
    clearTimer();
  });

  return { refreshing, lastSyncAt, invalidate };
}
