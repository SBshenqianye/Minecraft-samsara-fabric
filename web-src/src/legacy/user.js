let started = false;

/** 公开 /user 页：复用原 user.html + 只读脚本，不加载任何管理端 API。 */
export async function mountLegacyUser(host) {
  if (!host || host.dataset.mounted === '1') return;
  host.dataset.mounted = '1';
  const response = await fetch('/legacy-user.html', { cache: 'no-store' });
  if (!response.ok) throw new Error('用户端页面壳加载失败：' + response.status);
  const html = await response.text();
  const doc = new DOMParser().parseFromString(html, 'text/html');
  for (const node of Array.from(doc.body.childNodes)) {
    if (node.nodeType === Node.ELEMENT_NODE && node.tagName === 'SCRIPT') continue;
    host.appendChild(document.importNode(node, true));
  }
  if (started) return;
  started = true;
  await import('../../../mod/src/main/resources/web/banners.js');
  await import('../../../mod/src/main/resources/web/item-ui.js');
  await import('../../../mod/src/main/resources/web/value-table.js');
  await import('../../../mod/src/main/resources/web/user.js');
  await import('../../../mod/src/main/resources/web/map.js');
}
