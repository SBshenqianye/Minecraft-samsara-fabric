/**
 * 原生 Vue 页面使用框架弹窗（Element Plus 外壳）的唯一入口。
 * 旧页面的 window.FrameworkDialog 由 FrameworkDialogHost 提供，这里只做一层包装，
 * 避免新页面直接依赖旧全局壳。
 */
export function openFrameworkDialog(id, options = {}) {
  return window.FrameworkDialog?.open?.(id, options);
}

export function closeFrameworkDialog(id) {
  return window.FrameworkDialog?.close?.(id);
}

export function isFrameworkDialogOpen(id) {
  return Boolean(window.FrameworkDialog?.isOpen?.(id));
}
