/**
 * 原生 Vue 页面使用框架弹窗（Element Plus 外壳）的唯一入口。
 * 旧页面的 window.SamsaraDialog 由 FrameworkDialogHost 提供，这里只做一层包装，
 * 避免新页面直接依赖 window.Samsara* 全局。
 */
export function openFrameworkDialog(id, options = {}) {
  return window.SamsaraDialog?.open?.(id, options);
}

export function closeFrameworkDialog(id) {
  return window.SamsaraDialog?.close?.(id);
}

export function isFrameworkDialogOpen(id) {
  return Boolean(window.SamsaraDialog?.isOpen?.(id));
}
