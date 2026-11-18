import { ElMessageBox } from 'element-plus';

/**
 * 统一的危险操作确认。
 *
 * - 默认返回 false 而不是抛异常，调用方写 `if (!(await confirmDanger(...))) return;`
 * - 不可逆操作可要求输入关键字（如存档名），避免手滑。
 */
export async function confirmDanger(message, options = {}) {
  const {
    title = '确认操作',
    confirmText = '确认',
    cancelText = '取消',
    requireText = '',
    type = 'warning',
  } = options;

  try {
    if (requireText) {
      await ElMessageBox.prompt(message, title, {
        type,
        confirmButtonText: confirmText,
        cancelButtonText: cancelText,
        inputPlaceholder: `输入 ${requireText} 以确认`,
        inputValidator: (value) => value === requireText || `请输入 ${requireText}`,
      });
      return true;
    }
    await ElMessageBox.confirm(message, title, {
      type,
      confirmButtonText: confirmText,
      cancelButtonText: cancelText,
      confirmButtonClass: 'el-button--danger',
    });
    return true;
  } catch {
    return false;
  }
}
