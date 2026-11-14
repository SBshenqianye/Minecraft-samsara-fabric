let started = false;

/**
 * 公开 /user 页：页面结构已由 UserPage.vue 渲染，这里只按需加载只读内核，
 * 不加载任何管理端 API，也不再拉取旧 user.html 页面壳。
 */
export async function mountLegacyUser() {
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
