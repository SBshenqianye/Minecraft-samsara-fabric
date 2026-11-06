let started = false;

/**
 * 把现有管理端页面壳挂到 Vue 宿主里，再按原顺序加载命令式脚本。
 * 复杂页面（地图 canvas、世界栈、配置编辑器、权限树、RCON）继续复用原逻辑；
 * Vue 负责入口、路由与卸载边界。只搬运静态 DOM，不使用 innerHTML。
 */
export async function mountLegacyAdmin(host) {
  if (!host || host.dataset.mounted === '1') return;
  host.dataset.mounted = '1';
  const response = await fetch('/legacy.html', { cache: 'no-store' });
  if (!response.ok) throw new Error('管理端页面壳加载失败：' + response.status);
  const html = await response.text();
  const doc = new DOMParser().parseFromString(html, 'text/html');
  for (const node of Array.from(doc.body.childNodes)) {
    if (node.nodeType === Node.ELEMENT_NODE && node.tagName === 'SCRIPT') continue;
    host.appendChild(document.importNode(node, true));
  }
  addContainerLink(host);
  if (started) return;
  started = true;
  await import('../../../mod/src/main/resources/web/structured.js');
  await import('../../../mod/src/main/resources/web/form-kit.js');
  await import('../../../mod/src/main/resources/web/particles.js');
  await import('../../../mod/src/main/resources/web/banners.js');
  await import('../../../mod/src/main/resources/web/item-ui.js');
  await import('../../../mod/src/main/resources/web/value-table.js');
  await import('../../../mod/src/main/resources/web/lottery.js');
  await import('../../../mod/src/main/resources/web/page-kit.js');
  await import('../../../mod/src/main/resources/web/app.js');
  await import('../../../mod/src/main/resources/web/chat-text.js');
  await import('../../../mod/src/main/resources/web/config-editor.js');
  await import('../../../mod/src/main/resources/web/announcements.js');
  await import('../../../mod/src/main/resources/web/map.js');
  await import('../../../mod/src/main/resources/web/world-stack.js');
  await import('../../../mod/src/main/resources/web/output.js');
  await import('../../../mod/src/main/resources/web/identity.js');
  await import('../../../mod/src/main/resources/web/permissions.js');
  await import('../../../mod/src/main/resources/web/server.js');
}

function addContainerLink(host) {
  const connection = host.querySelector('.connection');
  if (!connection || connection.querySelector('[data-container-link]')) return;
  const link = document.createElement('a');
  link.href = '/containers';
  link.className = 'user-view-link';
  link.dataset.containerLink = '1';
  link.textContent = '容器';
  link.title = '浏览和编辑箱子/桶等容器';
  connection.appendChild(link);
}
