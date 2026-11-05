'use strict';

// 经济业务（死人乐透 / 服务器基金 / 保险）：管理端标签与独立页共用。
// token 只放 sessionStorage；所有渲染都用 textContent，不拼 innerHTML。
(function () {
  const $ = (id) => document.getElementById(id);
  const KEY = 'samsara-token';
  let token = sessionStorage.getItem(KEY) || '';
  let valueTable = null;

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
    root.replaceChildren(
      stat('开关', model.enabled ? '启用' : '关闭'),
      stat('模式', model.mode === 'real' ? '现实日' : '游戏日'),
      stat('可下注日', dayText(betting.day)),
      stat('统计中', dayText(counting.day)),
      stat('实时死亡', String(counting.deathCount ?? 0)),
      stat('本轮票数', String(betting.tickets ?? 0)),
      stat('本轮奖池', vp(betting.pot)),
      stat('服务器基金', vp(fund.value)),
    );
    const badge = $('lottery-count');
    if (badge) badge.textContent = String(betting.tickets ?? 0);
  }

  function renderRound(model) {
    const round = $('lottery-round');
    const badge = $('lottery-round-badge');
    const betting = model.bettingDay || {};
    if (badge) {
      const state = betting.state || '';
      const tone = state === 'BETTING' ? 'ok' : state === 'CANCELLED' ? 'danger' : 'warn';
      badge.className = 'lottery-pill' + (tone ? ' ' + tone : '');
      badge.textContent = state === 'BETTING' ? '可下注'
        : state === 'SETTLED' ? '已结算'
          : state === 'CANCELLED' ? '已取消' : (state || '—');
    }
    if (round) {
      round.replaceChildren(
        stat('截止', betting.cutoff || '—'),
        stat('开奖', betting.drawTime || '—'),
        stat('奖池', vp(betting.pot)),
        stat('已售', String(betting.tickets ?? 0) + ' 张'),
        stat('参与', String((betting.bets || []).length) + ' 人'),
      );
    }
    const bets = $('lottery-bets');
    if (!bets) return;
    bets.replaceChildren();
    const rows = Array.isArray(betting.bets) ? betting.bets : [];
    if (!rows.length) {
      bets.append(empty('本期还没有人下注。'));
      return;
    }
    for (const bet of rows) {
      const row = make('div', 'lottery-row');
      const main = make('div', 'lottery-row-main');
      main.append(
        make('span', 'lottery-row-title', bet.name || '—'),
        make('span', 'lottery-row-meta', '猜 ' + (bet.guess ?? '—') + ' 人 · 投入 ' + vp(bet.value)),
      );
      row.append(main);
      bets.append(row);
    }
  }

  function renderFund(model) {
    const root = $('lottery-fund');
    const total = $('lottery-fund-total');
    const fund = model.fund || {};
    if (total) total.textContent = vp(fund.value);
    if (!root) return;
    root.replaceChildren();
    const items = Array.isArray(fund.items) ? fund.items : [];
    if (!items.length) {
      root.append(empty('基金为空。'));
      return;
    }
    for (const item of items) {
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

  const STATUS = {
    ACTIVE: ['生效中', 'ok'],
    PENDING: ['待审核', 'warn'],
    CLAIMED: ['已理赔', ''],
    REVIVE_PENDING: ['待复活', 'warn'],
    REVIVED: ['已复活', 'ok'],
    REVIVE_FAILED: ['复活失败', 'danger'],
    EXPIRED: ['已过期', ''],
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
          + ' · 到期 ' + dateText(policy.expiresAt)),
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

  function render(model) {
    renderStats(model);
    renderRound(model);
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
      ['lottery-overview', '概览'],
      ['lottery-round-panel', '当前轮次'],
      ['lottery-values-panel', '物品价值表'],
      ['lottery-fund-panel', '服务器基金'],
      ['lottery-insurance-panel', '保险'],
      ['lottery-history-panel', '历史开奖'],
    ];
    const items = [];
    const targets = [];
    for (const [id, label] of sections) {
      const element = $(id);
      if (!element) continue;
      items.push({ label, target: id });
      targets.push({ id, el: element });
    }
    UI.buildNav('经济业务', items, targets);
  }

  // 供管理端“业务信息 → 经济业务”标签直接调用，不通过 iframe 嵌套。
  window.samsaraLottery = {
    activate: () => {
      buildNav();
      load();
      if (valueTable) valueTable.ensure();
    },
    load,
    buildNav,
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
