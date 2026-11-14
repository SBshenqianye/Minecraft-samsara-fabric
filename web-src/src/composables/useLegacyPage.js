import { onMounted } from 'vue';
import { waitForElement } from '../legacy/document.js';

/**
 * 复杂旧页面的 Vue 壳：等 LegacyPanel 挂载后，再按页加载旧脚本并激活。
 * 这样旧脚本不再进入全局 legacy/admin.js，页面仍然由 Vue 路由和生命周期管理。
 */
async function waitForGlobal(name, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs;
  while (!window[name] && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 40));
  }
  return Boolean(window[name]);
}

export function useLegacyPage({
  panelId = '',
  panelIds = [],
  load,
  activate,
  afterLoad,
} = {}) {
  onMounted(async () => {
    const ids = [panelId, ...panelIds].filter(Boolean);
    if (ids.length) {
      await Promise.all(ids.map((id) => waitForElement(id, 6000).catch(() => {})));
    }
    // 旧脚本首行会 `if (!window.SamsaraUI) return;`，必须等全局壳就绪再按页加载。
    await waitForGlobal('SamsaraUI', 10000);
    if (load) await load();
    if (afterLoad) await afterLoad();
    if (activate) activate();
  });
}
