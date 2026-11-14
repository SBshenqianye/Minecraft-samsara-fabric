let started = false;

/** 公开 /user 页：复用原 user.html + 只读脚本，不加载任何管理端 API。 */
export async function mountLegacyUser(host) {
  if (!host || host.dataset.mounted === '1') return;
  host.dataset.mounted = '1';
  const response = await fetch('/legacy-user.html', { cache: 'no-store' });
  if (!response.ok) throw new Error('用户端页面壳加载失败：' + response.status);
  const html = await response.text();
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const main = doc.querySelector('main.app-shell.user-shell') || doc.querySelector('main');
  const content = main?.querySelector('.content') || main;
  if (content) {
    for (const child of Array.from(content.children)) {
      host.appendChild(document.importNode(child, true));
    }
  }
  if (started) return;
  started = true;
  // user.js 负责 SamsaraUI/公开 API 适配，map.js 提供 SamsaraStackIcon。
  // 先让 user.js 注册但不启动，等 map.js 就绪后再统一 start。
  window.__samsaraUserStartDeferred = true;
  await import('../legacy-core/banners.js');
  await import('../legacy-core/item-ui.js');
  await import('../legacy-core/value-table.js');
  await import('../legacy-core/user.js');
  await import('../legacy-core/map.js');
  window.__samsaraUserStartDeferred = false;
  window.__samsaraUserStart?.();
}
