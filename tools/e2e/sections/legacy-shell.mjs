const PAGES = [
  {
    route: '/samsara/economy',
    label: '经济业务 / lottery',
    panel: '#panel-lottery',
    global: '',
    extra: () => document.querySelectorAll('#lottery-stats .stat').length >= 1,
  },
  {
    route: '/samsara/community/identity',
    label: '身份组 / identity',
    panel: '#panel-identity',
    global: '',
    extra: () => document.querySelectorAll('#panel-identity .identity-card').length >= 1,
  },
  {
    route: '/samsara/config/advanced',
    label: '高级配置 / config',
    panel: '#panel-config',
    global: '',
    extra: () => document.querySelectorAll('#files .file-block').length >= 1,
  },
  {
    route: '/samsara/config/claim-range',
    label: '圈地范围 / claim-range',
    panel: '#panel-claim-range',
    global: '',
    extra: () => Boolean(document.getElementById('claim-range-files')?.textContent.trim()),
  },
  {
    route: '/samsara/config/editor',
    label: '配置编辑器 / config-editor',
    panel: '#panel-config-editor',
    global: 'SamsaraConfigEditor',
    extra: () => document.querySelectorAll('#panel-config-editor .entry, #panel-config-editor input, #panel-config-editor select').length >= 1,
  },
  {
    route: '/samsara/world/map',
    label: '地图 / map',
    panel: '#panel-map',
    global: 'SamsaraMap',
    extra: () => Boolean(document.getElementById('map-canvas')),
  },
  {
    route: '/samsara/world/stack',
    label: '世界栈 / world-stack',
    panel: '#panel-world-stack',
    global: 'SamsaraWorldStack',
    extra: () => document.querySelectorAll('#world-stack-list .world-stack-card, #world-stack-timeline .timeline-node').length >= 1,
  },
  {
    route: '/vanilla/config',
    label: '本体配置 / server',
    panel: '#server-config-panel',
    global: '',
    extra: () => document.querySelectorAll('#server-config-files details.file-block').length >= 1,
  },
  {
    route: '/vanilla/backup',
    label: '本体备份 / server',
    panel: '#server-backup-panel',
    global: '',
    extra: () => Boolean(document.getElementById('server-backup-timeline')),
  },
  {
    route: '/samsara/system/audit',
    label: 'Web 审计 / server',
    panel: '#server-audit-panel',
    global: '',
    extra: () => Boolean(document.getElementById('server-audit-list')),
  },
];

export default {
  name: 'legacy-shell',
  description: 'batch 2-4：旧页面按页加载内核后的冒烟验证',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    for (const page of PAGES) {
      await navigate(page.route);
      await until(
        `window.SamsaraUI && document.querySelector(${JSON.stringify(page.panel)})`,
        20000,
        page.label,
      );
      if (page.global) {
        await until(`Boolean(window.${page.global})`, 20000, page.label + ' 脚本加载');
      }
      await sleep(800);
      const state = await evaluate(`(() => ({
        path: location.pathname,
        panel: (() => {
          const node = document.querySelector(${JSON.stringify(page.panel)});
          return node ? !node.hidden && getComputedStyle(node).display !== 'none' : false;
        })(),
        global: !page.global || Boolean(window.${page.global}),
        extra: (${page.extra.toString()})(),
        errors: window.__e2eErrors || [],
      }))()`);
      check(page.label, state.path === page.route && state.panel && state.global
        && state.extra && state.errors.length === 0, JSON.stringify(state));
    }

    const errors = await evaluate('window.__e2eErrors || []');
    check('legacy-shell 没有未捕获脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));
    await sleep(100);
  },
};
