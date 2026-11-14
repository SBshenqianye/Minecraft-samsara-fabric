const LEGACY_PATHS = [
  '/legacy.html',
  '/legacy-user.html',
  '/user.html',
  '/style.css',
  '/app.js',
  '/page-kit.js',
  '/chat-text.js',
  '/structured.js',
  '/form-kit.js',
  '/particles.js',
  '/banners.js',
  '/item-ui.js',
  '/value-table.js',
  '/map.js',
  '/server.js',
  '/world-stack.js',
  '/identity.js',
  '/config-editor.js',
  '/lottery.js',
  '/output.js',
];

export default {
  name: 'batch5',
  description: 'batch 5：旧页面壳/静态脚本已移除，公开用户端与内核按需加载仍可用',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    // 1. 旧静态路径必须全部下线，只保留 SPA 与公开 API 文档。
    await navigate('/');
    await until("window.SamsaraUI && document.querySelector('main.app-shell')", 20000, '管理端外壳');
    const staticStatus = await evaluate(`(async () => {
      const paths = ${JSON.stringify(LEGACY_PATHS)};
      const out = {};
      for (const path of paths) {
        try {
          const response = await fetch(path, { cache: 'no-store' });
          out[path] = response.status;
        } catch (error) {
          out[path] = 'error:' + (error?.message || error);
        }
      }
      try {
        const openapi = await fetch('/openapi.json', { cache: 'no-store' });
        out['/openapi.json'] = openapi.status;
      } catch (error) {
        out['/openapi.json'] = 'error:' + (error?.message || error);
      }
      return out;
    })()`);
    const stillServed = Object.entries(staticStatus)
      .filter(([path, status]) => path !== '/openapi.json' && status !== 404);
    check('旧页面壳与旧静态脚本全部返回 404', stillServed.length === 0, JSON.stringify(stillServed));
    check('/openapi.json 仍可访问', staticStatus['/openapi.json'] === 200,
      JSON.stringify(staticStatus['/openapi.json']));

    // 2. 管理端页面不再插入任何旧 <script src>，内核全部走 Vite bundle。
    const scriptSources = await evaluate(`[...document.querySelectorAll('script[src]')]
      .map((node) => node.getAttribute('src'))`);
    const legacyScripts = scriptSources.filter((src) => LEGACY_PATHS.some((path) => src === path));
    check('管理端不再加载旧 script 标签', legacyScripts.length === 0, JSON.stringify(legacyScripts));
    check('旧内核仍通过 bundle 暴露全局入口',
      await evaluate('Boolean(window.SamsaraUI)'),
      'SamsaraUI');
    await sleep(100);

    // 3. 公开用户端结构由 Vue 渲染，公开只读接口与地图内核可用。
    await navigate('/user');
    await until("document.querySelectorAll('section[id^=\"user-section-\"]').length >= 5", 30000, '用户端分区');
    await until("document.querySelectorAll('#user-status .status-chip').length >= 1", 30000, '用户端状态');
    const userView = await evaluate(`(() => ({
      title: document.title,
      sections: [...document.querySelectorAll('section[id^="user-section-"]')].map((node) => node.id),
      chips: document.querySelectorAll('#user-status .status-chip').length,
      mapCanvas: Boolean(document.getElementById('map-canvas')),
      worldCards: document.querySelectorAll('#user-section-worlds .world-stack-card').length,
      paths: performance.getEntriesByType('resource')
        .map((entry) => entry.name)
        .filter((name) => ${JSON.stringify(LEGACY_PATHS)}.some((path) => name.endsWith(path))),
      errors: window.__e2eErrors || [],
    }))()`);
    check('用户端五个分区全部由 Vue 渲染',
      userView.sections.join('|') === 'user-section-status|user-section-worlds|user-section-lottery|user-section-values|user-section-map',
      JSON.stringify(userView.sections));
    check('用户端状态与地图内核可用',
      userView.chips >= 1 && userView.mapCanvas === true,
      JSON.stringify({ chips: userView.chips, mapCanvas: userView.mapCanvas }));
    check('用户端不再请求旧静态脚本', userView.paths.length === 0, JSON.stringify(userView.paths));
    check('用户端没有未捕获脚本错误', userView.errors.length === 0, userView.errors.slice(0, 3).join(' | '));
  },
};
