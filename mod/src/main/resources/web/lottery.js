'use strict';

// 经济业务（死人乐透 / 服务器基金 / 保险）：管理端标签与独立页共用。
// token 只放 sessionStorage；所有渲染都用 textContent，不拼 innerHTML。
(function () {
  const $ = (id) => document.getElementById(id);
  const KEY = 'samsara-token';
  let token = sessionStorage.getItem(KEY) || '';
  let valueTable = null;
  let currentTab = 'overview';

  const LOTTERY_PANELS = [
    'lottery-dashboard', 'lottery-overview', 'lottery-round-panel',
    'lottery-values-panel', 'lottery-fund-panel', 'lottery-claims-panel',
    'lottery-insurance-panel', 'lottery-history-panel',
  ];
  const LOTTERY_TABS = {
    overview: ['lottery-dashboard'],
    fund: ['lottery-fund-panel', 'lottery-values-panel'],
    lottery: ['lottery-overview', 'lottery-round-panel', 'lottery-claims-panel',
      'lottery-history-panel'],
    insurance: ['lottery-insurance-panel'],
  };

  function headers() {
    return token ? { Authorization: 'Bearer ' + token } : {};
  }

  async function api(path, options) {
    const response = await fetch(path, Object.assign({}, options, {
      headers: Object.assign({ 'Content-Type': 'application/json' }, headers(),
        options && options.headers ? options.headers : {}),
    }));
    let body = null;
    try { body = await response.json(); } catch (error) { body = null; }
    if (!response.ok) {
      const error = new Error((body && body.error) || ('HTTP ' + response.status));
      error.status = response.status;
      throw error;
    }
    return body;
  }

  function make(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function vp(value) {
    const n = Number(value || 0);
    if (!Number.isFinite(n)) return '0 VP';
    const text = Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
    return text + ' VP';
  }

  function dayText(id) {
    if (!id) return '—';
    if (id.startsWith('game-')) return '第 ' + id.slice(5) + ' 游戏日';
    return id;
  }

  function timeText(ms) {
    if (!ms) return '—';
    try { return new Date(ms).toLocaleString(); } catch (error) { return String(ms); }
  }

  function dateText(ms) {
    if (!ms) return '—';
    try { return new Date(ms).toLocaleDateString(); } catch (error) { return String(ms); }
  }

  function stat(label, value) {
    const item = make('div', 'stat');
    item.append(make('span', null, label), make('strong', null, value));
    return item;
  }

  function pill(text, tone) {
    return make('span', 'lottery-pill' + (tone ? ' ' + tone : ''), text);
  }

  function empty(text) {
    return make('div', 'lottery-empty', text);
  }

  function message(text, isError) {
    const el = $('lottery-message');
    if (!el) return;
    el.textContent = text || '';
    el.className = isError ? 'message error' : 'message';
  }

  function renderStats(model) {
    const root = $('lottery-stats');
    if (!root) return;
    const betting = model.bettingDay || {};
    const counting = model.countingDay || {};
    const fund = model.fund || {};
    const gold = model.gold || {};
    const goldPrice = Number(gold.price || 0).toFixed(2);
    root.replaceChildren(
      stat('开关', model.enabled ? '启用' : '关闭'),
      stat('模式', model.mode === 'real' ? '现实日' : '游戏日'),
      stat('国际金价', gold.enabled ? goldPrice + ' USD/oz' : '未启用'),
      stat('金块估值', vp(gold.blockValue)),
      stat('统计中', dayText(counting.day)),
      stat('实时死亡', String(counting.deathCount ?? 0)),
      stat('本轮种子', vp(counting.seedValue)),
      stat('待开奖奖池', vp(model.activePot ?? counting.pot)),
      stat('待开奖票数', String(counting.tickets ?? 0)),
      stat('可下注日', dayText(betting.day)),
      stat('下注轮已入池', vp(model.bettingPot ?? betting.ownPot ?? betting.pot)),
      stat('滚存', vp(model.rollover)),
      stat('服务器基金', vp(fund.value)),
    );
    const badge = $('lottery-count');
    if (badge) badge.textContent = String(counting.tickets ?? 0);
  }

  function renderRound(model) {
    const round = $('lottery-round');
    const badge = $('lottery-round-badge');
    const betting = model.bettingDay || {};
    const counting = model.countingDay || {};
    if (badge) {
      const state = counting.state || '';
      const tone = state === 'BETTING' ? 'ok' : state === 'CANCELLED' ? 'danger' : 'warn';
      badge.className = 'lottery-pill' + (tone ? ' ' + tone : '');
      badge.textContent = state === 'BETTING' ? '统计中'
        : state === 'SETTLED' ? '已结算'
          : state === 'CANCELLED' ? '已取消' : (state || '—');
    }
    if (round) {
      round.replaceChildren(
        stat('统计日', dayText(counting.day)),
        stat('实时死亡', String(counting.deathCount ?? 0)),
        stat('本轮种子', vp(counting.seedValue)),
        stat('待开奖奖池', vp(model.activePot ?? counting.pot)),
        stat('待开奖票数', String(counting.tickets ?? 0) + ' 张'),
        stat('可下注日', dayText(betting.day)),
        stat('下注轮已入池', vp(model.bettingPot ?? betting.ownPot)),
        stat('已售', String(betting.tickets ?? 0) + ' 张'),
        stat('截止', betting.cutoff || '—'),
        stat('开奖', betting.drawTime || '—'),
      );
    }
    const bets = $('lottery-bets');
    if (!bets) return;
    bets.replaceChildren();
    const sections = [
      { title: '待开奖 · ' + dayText(counting.day), rows: counting.bets,
        empty: '待开奖轮还没有人下注。' },
      { title: '下注中 · ' + dayText(betting.day), rows: betting.bets,
        empty: '下注轮还没有人下注。' },
    ];
    for (const section of sections) {
      bets.append(make('div', 'lottery-section-title', section.title));
      const rows = Array.isArray(section.rows) ? section.rows : [];
      if (!rows.length) {
        bets.append(empty(section.empty));
        continue;
      }
      for (const bet of rows) {
        const row = make('div', 'lottery-row');
        const main = make('div', 'lottery-row-main');
        main.append(
          make('span', 'lottery-row-title', bet.name || '—'),
          make('span', 'lottery-row-meta', '猜 ' + (bet.guess ?? '—') + ' 人 · '
            + (bet.tickets ?? 1) + ' 张 · 投入 ' + vp(bet.value)),
        );
        row.append(main);
        bets.append(row);
      }
    }
  }

  function fillItemCards(root, items, emptyText) {
    if (!root) return;
    root.replaceChildren();
    const list = Array.isArray(items) ? items : [];
    if (!list.length) {
      root.append(empty(emptyText));
      return;
    }
    for (const item of list) {
      const card = make('div', 'lottery-item-card');
      card.append(window.SamsaraItem.icon(item, { size: 'md' }));
      const body = make('div', 'lottery-item-body');
      body.append(
        make('span', 'lottery-item-name', item.zh || item.name || item.item || '—'),
        make('span', 'lottery-item-meta', (item.count || 0) + ' 个 · ' + vp(item.value)),
      );
      card.append(body);
      root.append(card);
    }
  }

  function renderItemCards(rootId, items, emptyText) {
    fillItemCards($(rootId), items, emptyText);
  }

  function renderClaims(model) {
    const root = $('lottery-claims');
    const badge = $('lottery-claims-count');
    const claims = Array.isArray(model.claims) ? model.claims : [];
    if (badge) badge.textContent = claims.length + ' 笔';
    if (!root) return;
    root.replaceChildren();
    if (!claims.length) {
      root.append(empty('没有待领取物品。'));
      return;
    }
    for (const claim of claims) {
      const card = make('div', 'lottery-claim');
      const head = make('div', 'lottery-row-main');
      head.append(
        make('span', 'lottery-row-title', claim.owner || '—'),
        make('span', 'lottery-row-meta',
          (claim.reason || '待领取') + ' · ' + timeText(claim.at)),
      );
      const items = make('div', 'lottery-items');
      fillItemCards(items, claim.items, '没有物品。');
      card.append(head, items);
      root.append(card);
    }
  }

  function renderPool(model) {
    renderItemCards('lottery-pool', model.activePotItems, '待开奖奖池为空。');
    renderItemCards('lottery-betting-pool', model.bettingPotItems, '下注轮还没有人投入物品。');
  }

  function renderFund(model) {
    const total = $('lottery-fund-total');
    const fund = model.fund || {};
    if (total) total.textContent = vp(fund.value);
    renderItemCards('lottery-fund', fund.items, '基金为空。');
    const root = $('lottery-fund');
    const host = root ? root.parentElement : null;
    if (host && !document.getElementById('lottery-fund-inventory-link')) {
      const link = make('button', 'ghost lottery-fund-inventory-link', '打开基金仓库');
      link.id = 'lottery-fund-inventory-link';
      link.type = 'button';
      link.addEventListener('click', () => {
        if (window.SamsaraRouter?.push) {
          window.SamsaraRouter.push({ path: '/samsara/economy/fund-inventory' });
        } else {
          window.location.href = '/samsara/economy/fund-inventory';
        }
      });
      host.insertBefore(link, root);
    }
  }

  const STATUS = {
    ACTIVE: ['生效中', 'ok'],
    PENDING: ['待审核', 'warn'],
    CLAIMED: ['已理赔', ''],
    REVIVE_PENDING: ['待复活', 'warn'],
    REVIVED: ['已复活', 'ok'],
    REVIVE_FAILED: ['复活失败', 'danger'],
    EXPIRED: ['已过期', ''],
    VOID: ['已作废', 'danger'],
  };

  function renderInsurance(model) {
    const root = $('lottery-insurance');
    const summary = $('lottery-insurance-summary');
    const ins = model.insurance || {};
    const rows = Array.isArray(ins.policies) ? ins.policies : [];
    const pending = rows.filter((p) => p.status === 'PENDING').length;
    if (summary) {
      summary.textContent = rows.length + ' 单' + (pending ? ' · ' + pending + ' 待审核' : '');
    }
    if (!root) return;
    root.replaceChildren();
    if (!rows.length) {
      root.append(empty('还没有保单。'));
      return;
    }
    for (const policy of rows.slice(0, 20)) {
      const row = make('div', 'lottery-row');
      const main = make('div', 'lottery-row-main');
      main.append(
        make('span', 'lottery-row-title', policy.id || '—'),
        make('span', 'lottery-row-meta',
          (policy.owner || '—') + ' · 价值 ' + vp(policy.value)
          + ' · 保费 ' + vp(policy.premium)
          + ' · 到期 ' + dateText(policy.expiresAt)
          + (policy.voidReason ? ' · ' + policy.voidReason : '')),
      );
      const meta = STATUS[policy.status] || [policy.status || '—', ''];
      row.append(main, pill(meta[0], meta[1]));
      root.append(row);
    }
    if (rows.length > 20) {
      root.append(empty('另有 ' + (rows.length - 20) + ' 单未显示。'));
    }
  }

  function renderHistory(model) {
    const root = $('lottery-history');
    if (!root) return;
    root.replaceChildren();
    const rows = Array.isArray(model.history) ? model.history : [];
    if (!rows.length) {
      root.append(empty('还没有已结算的期次。'));
      return;
    }
    for (const result of rows) {
      const row = make('div', 'lottery-row');
      const main = make('div', 'lottery-row-main');
      main.append(
        make('span', 'lottery-row-title',
          dayText(result.day) + ' · 死亡 ' + (result.deathCount ?? 0)),
        make('span', 'lottery-row-meta',
          '中奖票 ' + (result.winnerCount ?? 0)
          + ' · 奖池 ' + vp(result.pot)
          + ' · 抽成 ' + vp(result.rake)
          + (Array.isArray(result.winners) && result.winners.length
            ? ' · ' + result.winners.join('、') : '')),
      );
      row.append(main);
      if (result.rollover) row.append(pill('滚存', 'warn'));
      root.append(row);
    }
  }

  function dashboardCard(title, tag) {
    const card = make('article', 'dash-card');
    const head = make('div', 'dash-card-head');
    head.append(make('h3', null, title), make('span', null, tag));
    card.append(head);
    return card;
  }

  function renderDashboard(model) {
    const root = $('lottery-dashboard-body');
    if (!root) return;
    root.replaceChildren();

    const fund = model.fund || { value: 0, items: [] };
    const betting = model.bettingDay || {};
    const counting = model.countingDay || {};
    const policies = (model.insurance && model.insurance.policies) || [];
    const pot = Number(model.activePot || betting.pot || 0);
    const rollover = Number(model.rollover || 0);
    const tickets = Number(betting.tickets || 0);

    const grid = make('div', 'economy-dash-grid');

    // 服务器基金
    const fundCard = dashboardCard('服务器基金', 'FUND');
    const fundValue = Number(fund.value || 0);
    fundCard.append(make('strong', 'dash-value', vp(fundValue)));
    fundCard.append(make('p', 'dash-sub',
        '基金物品 ' + (fund.items || []).length + ' 件 · 与奖池/滚存共同构成实物资产'));
    const fundBar = make('div', 'dash-bar');
    const fundSeg = make('span');
    const fundTotal = Math.max(1, fundValue + pot + rollover);
    fundSeg.style.width = Math.round(fundValue / fundTotal * 100) + '%';
    fundBar.append(fundSeg);
    fundCard.append(fundBar);
    const fundIcons = make('div', 'dash-icons');
    for (const item of (fund.items || []).slice(0, 6)) {
      const icon = window.SamsaraItem?.icon ? window.SamsaraItem.icon(item, { size: 'sm' }) : null;
      fundIcons.append(icon || make('span', 'dash-item-chip', item.zh || item.name || item.id));
    }
    if (!fundIcons.children.length) {
      fundIcons.append(make('span', 'dash-empty', '暂无基金物品'));
    }
    fundCard.append(fundIcons);
    grid.append(fundCard);

    // 死人乐透
    const lotCard = dashboardCard('死人乐透', 'LOTTERY');
    lotCard.append(make('strong', 'dash-value', vp(pot)));
    lotCard.append(make('p', 'dash-sub',
        (betting.day ? '第 ' + String(betting.day).replace(/^game-/, '') + ' 游戏日' : '当前轮次')
        + ' · ' + tickets + ' 张下注 · 滚存 ' + vp(rollover)));
    const donut = make('div', 'dash-donut');
    const potTotal = Math.max(1, pot + rollover);
    const potPct = Math.round(pot / potTotal * 100);
    donut.style.background = 'conic-gradient(var(--accent) 0 ' + potPct
      + '%, #f59e0b ' + potPct + '% 100%)';
    donut.append(make('strong', null, vp(pot)));
    lotCard.append(donut);
    const lotLegend = make('div', 'dash-legend');
    lotLegend.append(
      make('span', null, '● 奖池 ' + vp(pot)),
      make('span', null, '● 滚存 ' + vp(rollover)));
    lotCard.append(lotLegend);
    grid.append(lotCard);

    // 保险
    const insCard = dashboardCard('保险', 'INSURANCE');
    const statusCounts = {};
    for (const policy of policies) {
      const status = String(policy.status || 'OTHER').toUpperCase();
      statusCounts[status] = (statusCounts[status] || 0) + 1;
    }
    const activeCount = statusCounts.ACTIVE || 0;
    insCard.append(make('strong', 'dash-value', String(activeCount)));
    insCard.append(make('p', 'dash-sub',
        '生效 ' + activeCount + ' · 总保单 ' + policies.length
        + (counting.deathCount != null ? ' · 昨日死亡 ' + counting.deathCount : '')));
    const insBar = make('div', 'dash-bar dash-bar-stack');
    const colorByStatus = {
      ACTIVE: '#0d9488', CLAIMED: '#2563eb', REVIVED: '#14b8a6',
      REVIVE_FAILED: '#ef4444', VOID: '#94a3b8', OTHER: '#cbd5e1',
    };
    const totalPolicies = Math.max(1, policies.length);
    for (const [status, count] of Object.entries(statusCounts)) {
      const seg = make('span');
      seg.style.width = Math.round(count / totalPolicies * 100) + '%';
      seg.style.background = colorByStatus[status] || '#cbd5e1';
      insBar.append(seg);
    }
    insCard.append(insBar);
    const insLegend = make('div', 'dash-legend');
    for (const [status, count] of Object.entries(statusCounts)) {
      const chip = make('span');
      const dot = make('i');
      dot.style.background = colorByStatus[status] || '#cbd5e1';
      chip.append(dot, document.createTextNode(status + ' ' + count));
      insLegend.append(chip);
    }
    if (!policies.length) insLegend.append(make('span', 'dash-empty', '暂无保单'));
    insCard.append(insLegend);
    grid.append(insCard);

    // 历史开奖走势
    const history = (model.history || []).slice(0, 8).reverse();
    const histCard = dashboardCard('历史开奖走势', 'HISTORY');
    histCard.classList.add('dash-card-wide');
    if (!history.length) {
      histCard.append(make('p', 'dash-empty', '暂无历史开奖。'));
    } else {
      const chart = make('div', 'dash-history');
      const maxPot = Math.max(1, ...history.map((row) => Number(row.pot || 0)));
      for (const row of history) {
        const col = make('div', 'dash-history-col');
        const bar = make('div', 'dash-history-bar');
        bar.style.height = Math.max(3, Math.round(Number(row.pot || 0) / maxPot * 100)) + '%';
        if (Number(row.winnerCount || 0) > 0) bar.classList.add('has-winner');
        col.append(bar);
        col.append(make('span', null, String(row.day || '').replace(/^game-/, '')));
        chart.append(col);
      }
      histCard.append(chart);
    }
    grid.append(histCard);
    root.append(grid);
  }

  function render(model) {
    renderDashboard(model);
    renderStats(model);
    renderRound(model);
    renderPool(model);
    renderClaims(model);
    renderFund(model);
    renderInsurance(model);
    renderHistory(model);
    const pre = $('lottery-json');
    if (pre) pre.textContent = JSON.stringify(model, null, 2);
  }

  async function load() {
    // 管理端登录后才写入 token；每次刷新都重新读，避免脚本先于登录执行时一直为空。
    const stored = sessionStorage.getItem(KEY);
    if (stored) token = stored;
    if (!token) { message('请先填写 token。', true); return; }
    try {
      const model = await api('/api/lottery');
      render(model);
      message('已刷新 · ' + new Date().toLocaleTimeString());
    } catch (error) {
      message('读取失败：' + (error.message || '未知错误'), true);
    }
  }

  async function action(path, confirmText) {
    if (!token) { message('请先填写 token。', true); return; }
    if (!window.confirm(confirmText)) return;
    try {
      const result = await api(path, { method: 'POST', body: '{}' });
      message((result && result.message) || '完成');
      await load();
    } catch (error) {
      message('操作失败：' + (error.message || '未知错误'), true);
    }
  }

  function connect() {
    const input = $('lottery-token');
    token = (input && input.value ? input.value : '').trim();
    if (!token) { message('token 不能为空。', true); return; }
    sessionStorage.setItem(KEY, token);
    load();
    if (valueTable) valueTable.refresh();
  }

  function refreshAll() {
    load();
    if (valueTable) valueTable.refresh();
  }

  function start() {
    const input = $('lottery-token');
    if (input && token) input.value = token;
    const connectButton = $('lottery-connect');
    const refreshButton = $('lottery-refresh');
    const drawButton = $('lottery-draw');
    const cancelButton = $('lottery-cancel');
    if (connectButton) connectButton.addEventListener('click', connect);
    if (refreshButton) refreshButton.addEventListener('click', refreshAll);
    const dashboardRefresh = $('lottery-dashboard-refresh');
    if (dashboardRefresh) dashboardRefresh.addEventListener('click', refreshAll);
    if (drawButton) drawButton.addEventListener('click', () =>
      action('/api/lottery/draw', '确认手动开奖当前统计日？'));
    if (cancelButton) cancelButton.addEventListener('click', () =>
      action('/api/lottery/cancel', '确认取消当前统计日并全额退款？'));
    if ($('lottery-values') && window.SamsaraValueTable) {
      valueTable = window.SamsaraValueTable.create({
        rootId: 'lottery-values',
        scrollId: 'lottery-values-scroll',
        emptyId: 'lottery-values-empty',
        countId: 'lottery-values-count',
        searchId: 'lottery-values-search',
        categoryId: 'lottery-values-category',
        sortId: 'lottery-values-sort',
        endpoint: '/api/lottery/values',
        fetch: (url) => {
          const stored = sessionStorage.getItem(KEY);
          if (stored) token = stored;
          return api(url);
        },
        onError: (text) => message(text, true),
      });
    }
    if (token) {
      load();
      // 独立页直接打开价值表；管理端由标签激活时再懒加载，避免每次进后台都拉整表。
      if ($('lottery-token') && valueTable) valueTable.refresh();
    }
  }

  function buildNav() {
    const UI = window.SamsaraUI;
    if (!UI || !UI.buildNav) return;
    const sections = [
      ['lottery-dashboard', '概览'],
      ['lottery-overview', '状态'],
      ['lottery-round-panel', '当前轮次'],
      ['lottery-fund-panel', '服务器基金'],
      ['lottery-values-panel', '物品价值表'],
      ['lottery-claims-panel', '待领取'],
      ['lottery-insurance-panel', '保险'],
      ['lottery-history-panel', '历史开奖'],
    ];
    const items = [];
    const targets = [];
    for (const [id, label] of sections) {
      const element = $(id);
      if (!element || element.hidden) continue;
      items.push({ label, target: id });
      targets.push({ id, el: element });
    }
    UI.buildNav('经济业务', items, targets);
  }

  function setTab(tab) {
    currentTab = LOTTERY_TABS[tab] ? tab : 'overview';
    const visible = new Set(LOTTERY_TABS[currentTab]);
    for (const id of LOTTERY_PANELS) {
      const element = $(id);
      if (element) element.hidden = !visible.has(id);
    }
    const stack = $('panel-lottery');
    if (stack) {
      for (const name of Object.keys(LOTTERY_TABS)) {
        stack.classList.toggle('lottery-tab-' + name, name === currentTab);
      }
    }
    buildNav();
    // 首次激活时面板可能还没完成布局，下一帧再建一次导航。
    requestAnimationFrame(buildNav);
  }

  // 供管理端“业务信息 → 经济业务”标签直接调用，不通过 iframe 嵌套。
  window.samsaraLottery = {
    activate: () => {
      setTab(currentTab);
      load();
      if (valueTable) valueTable.ensure();
    },
    load,
    buildNav,
    setTab,
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
