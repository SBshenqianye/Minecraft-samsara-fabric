import { createApp } from 'vue';
import { openFrameworkDialog } from '../../composables/useFrameworkDialog.js';
import IdentityDetailDialog from './IdentityDetailDialog.vue';

/** 用框架弹窗承载 Vue 详情组件；关闭仍由 FrameworkDialogHost 负责。 */
export function openIdentityDetail(identity, uuid) {
  if (!uuid) return;
  const host = document.createElement('div');
  host.id = 'identity-data-content';
  host.className = 'identity-data-content';
  createApp(IdentityDetailDialog, { identity, uuid }).mount(host);
  openFrameworkDialog('identity-data-dialog', {
    title: 'data UUID 详情',
    width: 'min(1500px, 96vw)',
    content: host,
  });
}
