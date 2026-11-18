import { getCurrentInstance, onBeforeUnmount } from 'vue';

/**
 * 未保存修改的统一拦截。
 *
 * <p>页面注册一个「是否脏」的读取函数；路由跳转时由 `router.js` 的守卫统一确认，
 * 浏览器刷新/关闭时用 `beforeunload` 兜底。组件卸载会自动注销，不需要手动清理。
 */
const sources = new Map();

let seq = 0;

/** 是否有任一页面报告未保存修改。 */
export function hasUnsavedChanges() {
  for (const read of sources.values()) {
    try {
      if (read()) return true;
    } catch {
      // 页面正在卸载时读取失败按“没有脏数据”处理，避免拦死导航。
    }
  }
  return false;
}

/**
 * 在页面/组件里注册未保存状态。
 *
 * @param {() => boolean} isDirty 读取函数（通常是 computed/ref）
 */
export function useUnsavedChanges(isDirty) {
  const id = ++seq;
  sources.set(id, isDirty);
  const onBeforeUnload = (event) => {
    if (window.__e2e) return;
    if (!hasUnsavedChanges()) return;
    event.preventDefault();
    event.returnValue = '';
  };
  window.addEventListener('beforeunload', onBeforeUnload);
  const cleanup = () => {
    sources.delete(id);
    window.removeEventListener('beforeunload', onBeforeUnload);
  };
  if (getCurrentInstance()) {
    onBeforeUnmount(cleanup);
  }
  return cleanup;
}

/** 测试/调试用：当前注册的页面数量。 */
export function unsavedSourceCount() {
  return sources.size;
}
