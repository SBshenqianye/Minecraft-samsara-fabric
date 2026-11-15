/**
 * 框架弹窗（Element Plus 外壳）的唯一入口与状态。
 * 状态放在模块里，由 `FrameworkDialogHost.vue` 渲染；不再向 window 挂全局桥。
 */
import { nextTick, reactive, ref } from 'vue';

const dialogs = ref([]);
const hosts = new Map();

export const frameworkDialogs = dialogs;

export function setFrameworkDialogHost(id, el) {
  const key = String(id || '');
  if (!key) return;
  if (!el) {
    hosts.delete(key);
    return;
  }
  hosts.set(key, el);
  const dialog = dialogs.value.find((item) => item.id === key);
  if (dialog?.content && dialog.content.parentElement !== el) el.append(dialog.content);
}

export async function mountFrameworkDialog(dialog) {
  if (!dialog) return;
  await nextTick();
  const el = hosts.get(dialog.id);
  if (el && dialog.content && dialog.content.parentElement !== el) el.append(dialog.content);
}

export async function openFrameworkDialog(id, options = {}) {
  const key = String(id || '');
  if (!key) return;
  let dialog = dialogs.value.find((item) => item.id === key);
  if (!dialog) {
    dialog = reactive({
      id: key,
      title: options.title || '',
      width: options.width || 'min(980px, 94vw)',
      visible: false,
      content: null,
    });
    dialogs.value.push(dialog);
  }
  dialog.title = options.title || dialog.title || '对话框';
  dialog.width = options.width || dialog.width;
  if (options.content) dialog.content = options.content;
  await nextTick();
  dialog.visible = true;
  await nextTick();
  await mountFrameworkDialog(dialog);
}

export function closeFrameworkDialog(id) {
  const dialog = dialogs.value.find((item) => item.id === id);
  if (dialog) dialog.visible = false;
}

export function isFrameworkDialogOpen(id) {
  const dialog = dialogs.value.find((item) => item.id === id);
  return Boolean(dialog && dialog.visible);
}

export function closeAllFrameworkDialogs() {
  for (const dialog of dialogs.value) dialog.visible = false;
}
