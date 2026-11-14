export default {
  name: 'permissions',
  description: '原生权限页：树、搜索、展开/折叠、等级控件、owner 面板',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    await navigate('/samsara/config/permissions');
    await until(
      "window.SamsaraUI && document.querySelector('[data-page=\"permissions\"]')",
      20000,
      '原生权限页',
    );
    await until("document.querySelectorAll('#perm-tree .perm-node').length >= 1",
      20000, '权限树');
    await until("document.getElementById('global-nav')?.hidden === true",
      10000, '权限页隐藏全局导航');

    const state = await evaluate(`(() => ({
      page: !!document.querySelector('[data-page="permissions"]'),
      legacyPanel: !!document.getElementById('panel-permissions'),
      nodes: document.querySelectorAll('#perm-tree .perm-node').length,
      rows: document.querySelectorAll('#perm-tree .perm-row').length,
      levels: document.querySelectorAll('#perm-tree .perm-levels .segmented-btn').length,
      stats: document.querySelectorAll('#perm-stats .stat').length,
      owners: document.querySelectorAll('#perm-owners .owner-row').length,
      hasSearch: !!document.querySelector('#perm-search input'),
      hasRefresh: !!document.getElementById('perm-refresh'),
      navHidden: document.getElementById('global-nav')?.hidden === true,
      noNav: document.querySelector('main.app-shell')?.classList.contains('no-nav') === true,
      errors: window.__e2eErrors || [],
    }))()`);

    check('权限页为原生 Vue 页面', state.page && !state.legacyPanel,
      JSON.stringify({ page: state.page, legacyPanel: state.legacyPanel }));
    check('权限树、统计与 owner 面板渲染',
      state.nodes >= 1 && state.rows >= 1 && state.stats >= 5 && state.owners >= 1,
      JSON.stringify({ nodes: state.nodes, rows: state.rows, stats: state.stats, owners: state.owners }));
    check('权限等级使用分段按钮', state.levels >= state.rows && state.levels > 0,
      JSON.stringify({ levels: state.levels, rows: state.rows }));
    check('权限页不显示全局左侧导航', state.navHidden && state.noNav,
      JSON.stringify({ navHidden: state.navHidden, noNav: state.noNav }));

    const search = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const host = document.querySelector('#perm-search');
      const input = host?.tagName === 'INPUT' ? host : host?.querySelector('input')
        || document.querySelector('input[placeholder="搜索权限路径"]');
      if (!input) return { error: 'search input not found' };
      const before = document.querySelectorAll('#perm-tree .perm-node').length;
      input.value = 'where';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await sleep(300);
      const filtered = document.querySelectorAll('#perm-tree .perm-node').length;
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await sleep(300);
      const restored = document.querySelectorAll('#perm-tree .perm-node').length;
      return { before, filtered, restored, hasSearch: true };
    })()`);
    check('权限搜索可过滤并清空恢复',
      search.before > 0 && search.filtered > 0 && search.filtered < search.before
        && search.restored === search.before,
      JSON.stringify(search));

    const collapse = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const before = document.querySelectorAll('#perm-tree .perm-node').length;
      document.getElementById('perm-collapse-all')?.click();
      await sleep(300);
      const collapsed = document.querySelectorAll('#perm-tree .perm-node').length;
      document.getElementById('perm-expand-all')?.click();
      await sleep(300);
      const expanded = document.querySelectorAll('#perm-tree .perm-node').length;
      return { before, collapsed, expanded };
    })()`);
    check('权限树全部折叠/展开', collapse.collapsed < collapse.before
      && collapse.expanded === collapse.before, JSON.stringify(collapse));

    const levelState = await evaluate(`(() => {
      const row = document.querySelector('#perm-tree .perm-row');
      const active = row?.querySelector('.perm-levels .segmented-btn.active');
      const deny = row?.querySelector('.perm-levels .segmented-btn[data-value="deny"]');
      return {
        active: active?.dataset.value || '',
        hasDeny: !!deny,
        disabled: active?.disabled === true,
      };
    })()`);
    check('权限等级控件可读且包含关闭选项',
      Boolean(levelState.active) && levelState.hasDeny,
      JSON.stringify(levelState));

    const final = await evaluate('window.__e2eErrors || []');
    check('权限页没有未捕获脚本错误', final.length === 0, final.slice(0, 3).join(' | '));

    await sleep(100);
  },
};
