const PAGES = [
  {
    route: '/samsara/economy',
    label: '经济业务 / lottery',
    panel: '#panel-lottery',
    extra: () => document.querySelectorAll('#lottery-stats .stat').length >= 1,
  },
  {
    route: '/samsara/community/identity',
    label: '身份组 / identity',
    panel: '#panel-identity',
    extra: () => document.querySelectorAll('#panel-identity .identity-card').length >= 1,
  },
  {
    route: '/samsara/config/advanced',
    label: '高级配置 / config',
    panel: '#panel-config',
    extra: () => document.querySelectorAll('#files .file-block').length >= 1,
  },
  {
    route: '/samsara/config/claim-range',
    label: '圈地范围 / claim-range',
    panel: '#panel-claim-range',
    extra: () => Boolean(document.getElementById('claim-range-files')?.textContent.trim()),
  },
  {
    route: '/samsara/config/editor',
    label: '配置编辑器 / config-editor',
    panel: '#panel-config-editor',
    extra: () => document.querySelectorAll('#panel-config-editor .entry, #panel-config-editor input, #panel-config-editor select').length >= 1,
  },
  {
    route: '/samsara/world/map',
    label: '地图 / map',
    panel: '#panel-map',
    extra: () => Boolean(document.getElementById('map-canvas')),
  },
  {
    route: '/samsara/world/stack',
    label: '世界栈 / world-stack',
    panel: '#panel-world-stack',
    extra: () => document.querySelectorAll('#world-stack-list .world-stack-card, #world-stack-timeline .timeline-node').length >= 1,
  },
  {
    route: '/vanilla/config',
    label: '本体配置 / server',
    panel: '#server-config-panel',
    extra: () => document.querySelectorAll('#server-config-files details.file-block').length >= 1,
  },
  {
    route: '/vanilla/backup',
    label: '本体备份 / server',
    panel: '#server-backup-panel',
    extra: () => Boolean(document.getElementById('server-backup-timeline')),
  },
  {
    route: '/samsara/system/audit',
    label: 'Web 审计 / server',
    panel: '#server-audit-panel',
    extra: () => Boolean(document.getElementById('server-audit-list')),
  },
];

export default {
  name: 'pages',
  description: '所有管理端页面都由 Vue 渲染，且没有未捕获脚本错误',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    for (const page of PAGES) {
      await navigate(page.route);
      await until(
        `document.querySelector(${JSON.stringify(page.panel)})`,
        20000,
        page.label,
      );
      await sleep(800);
      const state = await evaluate(`(() => ({
        path: location.pathname,
        panel: (() => {
          const node = document.querySelector(${JSON.stringify(page.panel)});
          return node ? !node.hidden && getComputedStyle(node).display !== 'none' : false;
        })(),
        extra: (${page.extra.toString()})(),
        errors: window.__e2eErrors || [],
      }))()`);
      check(page.label, state.path === page.route && state.panel
        && state.extra && state.errors.length === 0, JSON.stringify(state));
    }

    const errors = await evaluate('window.__e2eErrors || []');
    check('页面冒烟没有未捕获脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));
    await sleep(100);
  },
};
