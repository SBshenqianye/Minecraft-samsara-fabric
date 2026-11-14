export default {
  name: 'lottery',
  description: '经济业务四子页：概览大屏、轮次/奖池/待领取/历史、物品价值表（搜索排序）、保险列表',
  async run(ctx) {
    const { check, evaluate, until, navigate, sleep } = ctx;

    await navigate('/samsara/economy');
    await until("window.SamsaraUI && document.getElementById('panel-lottery')"
      + " && document.querySelectorAll('#lottery-stats .stat').length >= 10", 25000, '经济业务概览');
    await until("document.querySelectorAll('#lottery-values .lottery-value-row').length >= 1",
      25000, '物品价值表');
    await sleep(300);

    const overview = await evaluate(`(() => ({
      dashCards: document.querySelectorAll('#lottery-dashboard .dash-card').length,
      dashValue: document.querySelector('#lottery-dashboard .dash-value')?.textContent || '',
      stats: document.querySelectorAll('#lottery-stats .stat').length,
      values: document.querySelectorAll('#lottery-values .lottery-value-row').length,
      poolHeads: document.querySelectorAll('#panel-lottery .lottery-pool-head').length,
      pool: Boolean(document.getElementById('lottery-pool')),
      bettingPool: Boolean(document.getElementById('lottery-betting-pool')),
      roundStats: document.querySelectorAll('#lottery-round .stat').length,
      history: document.querySelectorAll('#lottery-history .lottery-row').length,
      claims: Boolean(document.getElementById('lottery-claims-panel')),
      insurance: Boolean(document.getElementById('lottery-insurance-panel')),
      dashboardVisible: !document.getElementById('lottery-dashboard').hidden,
      roundHidden: document.getElementById('lottery-round-panel').hidden,
      navTitle: document.querySelector('#page-nav .nav-title')?.textContent.trim() || '',
      navItems: [...document.querySelectorAll('#page-nav .nav-item')].map((n) => n.textContent.trim()),
      grid: getComputedStyle(document.getElementById('panel-lottery')).display,
      legacyPanel: Boolean(document.getElementById('panel-lottery')?.querySelector('iframe')),
    }))()`);
    check('概览大屏四张卡片与统计行',
      overview.dashCards === 4 && overview.dashValue.endsWith('VP') && overview.stats >= 13,
      JSON.stringify({ cards: overview.dashCards, value: overview.dashValue, stats: overview.stats }));
    check('概览只显示 dashboard 且为网格布局',
      overview.dashboardVisible && overview.roundHidden === true
      && overview.grid === 'grid' && overview.navTitle.startsWith('经济业务')
      && overview.navItems.length === 1 && overview.navItems[0] === '概览'
      && !overview.legacyPanel,
      JSON.stringify({ visible: overview.dashboardVisible, grid: overview.grid,
        nav: overview.navItems, legacy: overview.legacyPanel }));
    check('奖池/待领取/保险/历史面板齐全',
      overview.poolHeads === 2 && overview.pool && overview.bettingPool
      && overview.roundStats === 10 && overview.claims && overview.insurance
      && overview.values >= 1,
      JSON.stringify({ poolHeads: overview.poolHeads, roundStats: overview.roundStats,
        values: overview.values, history: overview.history }));

    // ---- 死人乐透子页 ----
    await navigate('/samsara/economy/lottery');
    await until("document.querySelector('.section-subnav [data-subnav=\"lottery\"]')"
      + " && !document.getElementById('lottery-round-panel').hidden", 20000, '死人乐透子页');
    await sleep(400);
    const lotteryTab = await evaluate(`(() => ({
      dashboardHidden: document.getElementById('lottery-dashboard').hidden,
      roundVisible: !document.getElementById('lottery-round-panel').hidden,
      claimsVisible: !document.getElementById('lottery-claims-panel').hidden,
      historyVisible: !document.getElementById('lottery-history-panel').hidden,
      insuranceHidden: document.getElementById('lottery-insurance-panel').hidden,
      navItems: [...document.querySelectorAll('#page-nav .nav-item')].map((n) => n.textContent.trim()),
      badge: document.getElementById('lottery-round-badge')?.textContent.trim() || '',
      claimsCount: document.getElementById('lottery-claims-count')?.textContent.trim() || '',
      draw: Boolean(document.getElementById('lottery-draw')),
      cancel: Boolean(document.getElementById('lottery-cancel')),
    }))()`);
    check('死人乐透子页只显示轮次/待领取/历史',
      lotteryTab.dashboardHidden && lotteryTab.roundVisible && lotteryTab.claimsVisible
      && lotteryTab.historyVisible && lotteryTab.insuranceHidden
      && lotteryTab.draw && lotteryTab.cancel && lotteryTab.badge.length > 0,
      JSON.stringify(lotteryTab));
    check('死人乐透页内导航含轮次/待领取/历史',
      ['状态', '当前轮次', '待领取', '历史开奖'].every((label) => lotteryTab.navItems.includes(label))
      && !lotteryTab.navItems.includes('物品价值表'),
      JSON.stringify(lotteryTab.navItems));

    // ---- 物品价值表搜索 ----
    const search = await evaluate(`(async () => {
      const input = document.getElementById('lottery-values-search');
      const before = document.querySelectorAll('#lottery-values .lottery-value-row').length;
      input.value = 'iron';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((resolve) => setTimeout(resolve, 900));
      const rows = [...document.querySelectorAll('#lottery-values .lottery-value-row')];
      const result = {
        before,
        after: rows.length,
        text: rows.map((row) => row.textContent).join(' ').slice(0, 120),
        count: document.getElementById('lottery-values-count')?.textContent || '',
      };
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise((resolve) => setTimeout(resolve, 900));
      result.restored = document.querySelectorAll('#lottery-values .lottery-value-row').length;
      return result;
    })()`);
    check('物品价值表搜索走服务端并恢复',
      search.restored === search.before
      && (search.after === search.before || search.after > 0)
      && search.count.includes('/'), JSON.stringify(search));

    // ---- 保险子页 ----
    await navigate('/samsara/economy/insurance');
    await until("document.querySelector('.section-subnav [data-subnav=\"insurance\"]')"
      + " && !document.getElementById('lottery-insurance-panel').hidden", 20000, '保险子页');
    await sleep(400);
    const insurance = await evaluate(`(() => ({
      roundHidden: document.getElementById('lottery-round-panel').hidden,
      claimsHidden: document.getElementById('lottery-claims-panel').hidden,
      summary: document.getElementById('lottery-insurance-summary')?.textContent.trim() || '',
      rows: document.querySelectorAll('#lottery-insurance .lottery-row').length,
      navItems: [...document.querySelectorAll('#page-nav .nav-item')].map((n) => n.textContent.trim()),
    }))()`);
    check('保险子页只显示保险并带汇总',
      insurance.roundHidden && insurance.claimsHidden && insurance.summary.includes('单')
      && insurance.navItems.join('|') === '保险', JSON.stringify(insurance));

    // ---- 服务器基金子页：价值表 + 内嵌基金仓库 ----
    await navigate('/samsara/economy/fund');
    await until("document.querySelector('.fund-inventory-embed .slot')", 25000, '服务器基金页');
    const fund = await evaluate(`(() => ({
      valuesVisible: !document.getElementById('lottery-values-panel').hidden,
      dashboardHidden: document.getElementById('lottery-dashboard').hidden,
      slots: document.querySelectorAll('.fund-inventory-embed .slot').length,
      navItems: [...document.querySelectorAll('#page-nav .nav-item')].map((n) => n.textContent.trim()),
      subnav: [...document.querySelectorAll('.section-subnav .subnav-item')].map((n) => n.textContent.trim()),
    }))()`);
    check('服务器基金页复用价值表与基金仓库',
      fund.valuesVisible && fund.dashboardHidden && fund.slots > 0
      && fund.navItems.join('|') === '物品价值表'
      && !fund.subnav.includes('基金仓库'), JSON.stringify(fund));

    const errors = await evaluate('window.__e2eErrors || []');
    check('经济业务页没有未捕获脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));

    await sleep(100);
  },
};
