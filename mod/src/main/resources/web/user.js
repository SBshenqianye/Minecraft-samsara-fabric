'use strict';

// 用户端只读页：不加载管理端 app.js，不接触 token、配置、备份、权限接口。
// map.js 与 style.css 与管理端共用；这里只补充最小 SamsaraUI 适配层和公开摘要。
(function () {
  window.SamsaraMapApiBase = '/api/user/map';
  window.SamsaraUserView = true;

  const $ = (id) => document.getElementById(id);

  async function api(path, options) {
    const response = await fetch(path, Object.assign({}, options, {
      headers: Object.assign({ 'Content-Type': 'application/json' },
        options && options.headers ? options.headers : {}),
    }));
    let body = null;
    try { body = await response.json(); } catch (error) { body = null; }
    if (!response.ok) {
      const error = new Error((body && body.error) || ('HTTP ' + response.status));
      error.status = response.status;
      error.body = body;
      throw error;
    }
    return body;
  }

  window.SamsaraUI = {
    api,
    authHeaders: () => ({}),
    getToken: () => 'public',
    authMessage: (error, fallback) => fallback
      + (error && error.message ? '：' + error.message : ''),
    isActiveTab: () => true,
    setActiveTab: () => {},
    buildNav: () => {},
    setTabCount: () => {},
    scrollToTarget: (id) => {
      const target = document.getElementById(id);
      if (target) target.scrollIntoView({ block: 'start', behavior: 'smooth' });
    },
    onConnect: (callback) => {
      if (typeof callback === 'function') callback();
    },
  };

  function chip(label, value) {
    const item = document.createElement('div');
    item.className = 'status-chip';
    const name = document.createElement('span');
    name.textContent = label;
    const strong = document.createElement('strong');
    strong.textContent = value;
    item.append(name, strong);
    return item;
  }

  function makeEl(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function vp(value) {
    const n = Number(value || 0);
    const text = Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
    return text + ' VP';
  }

  function renderStatus(status) {
    const root = $('user-status');
    if (!root) return;
    root.replaceChildren();
    root.append(
      chip('在线玩家', String(status.online ?? 0) + ' / ' + String(status.maxPlayers ?? 0)),
      chip('服务器版本', status.version || '未知'),
      chip('MOTD', status.motd || '—'),
      chip('运行 tick', String(status.tickCount ?? 0)),
    );
  }

  function renderWorlds(data) {
    const root = $('user-worlds');
    if (!root) return;
    root.replaceChildren();
    const stacks = Array.isArray(data.stacks) ? data.stacks : [];
    if (stacks.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'map-overview-empty';
      empty.textContent = '还没有可显示的世界栈。';
      root.append(empty);
      return;
    }
    for (const stack of stacks) {
      const card = document.createElement('div');
      card.className = 'world-stack-card' + (stack.top ? ' top' : '');
      const head = document.createElement('div');
      head.className = 'world-stack-card-head';
      const title = document.createElement('div');
      title.className = 'world-stack-card-title';
      const name = document.createElement('strong');
      name.textContent = stack.label || stack.id || 'world';
      const meta = document.createElement('span');
      meta.className = 'muted small';
      meta.textContent = '栈序 #' + ((stack.index || 0) + 1)
        + (stack.top ? ' · 当前栈顶' : '');
      title.append(name, meta);
      const stats = document.createElement('div');
      stats.className = 'stat-row';
      const seed = document.createElement('div');
      seed.className = 'stat';
      const seedLabel = document.createElement('span');
      seedLabel.textContent = '种子';
      const seedValue = document.createElement('strong');
      seedValue.textContent = String(stack.seed ?? '—');
      seed.append(seedLabel, seedValue);
      const online = document.createElement('div');
      online.className = 'stat';
      const onlineLabel = document.createElement('span');
      onlineLabel.textContent = '在线';
      const onlineValue = document.createElement('strong');
      onlineValue.textContent = String(stack.online ?? 0) + ' 人';
      online.append(onlineLabel, onlineValue);
      stats.append(seed, online);
      head.append(title);
      const body = document.createElement('div');
      body.className = 'world-stack-card-body';
      const main = document.createElement('div');
      main.className = 'world-stack-card-main';
      main.append(stats);
      body.append(main);
      if (window.SamsaraStackIcon?.build) {
        body.append(window.SamsaraStackIcon.build(stack, { admin: false }));
      }
      card.append(head, body);
      root.append(card);
    }
  }

  function renderLotteryItems(rootId, items, emptyText) {
    const root = $(rootId);
    if (!root) return;
    root.replaceChildren();
    const list = Array.isArray(items) ? items : [];
    if (!list.length) {
      root.append(makeEl('div', 'lottery-empty', emptyText));
      return;
    }
    for (const item of list) {
      const card = makeEl('div', 'lottery-item-card');
      card.append(window.SamsaraItem.icon(item, { size: 'md' }));
      const body = makeEl('div', 'lottery-item-body');
      body.append(
        makeEl('span', 'lottery-item-name', item.zh || item.name || item.item || '—'),
        makeEl('span', 'lottery-item-meta', (item.count || 0) + ' 个 · ' + vp(item.value)),
      );
      card.append(body);
      root.append(card);
    }
  }

  function renderLottery(data) {
    const root = $('user-lottery');
    if (!root) return;
    root.replaceChildren();
    if (!data || !data.enabled) {
      root.append(chip('状态', '未开启'));
      return;
    }
    const round = data.round || {};
    const active = data.active || {};
    root.append(
      chip('待开奖轮', active.day || '—'),
      chip('本轮种子', String(active.seedValue ?? 0) + ' VP'),
      chip('待开奖奖池', String(active.pot ?? 0) + ' VP'),
      chip('待开奖票数', String(active.tickets ?? 0) + ' 张 / '
        + String(active.players ?? 0) + ' 人'),
      chip('开奖', active.drawTime || '—'),
      chip('可下注轮', round.day || '—'),
      chip('下注轮已入池', String(round.ownPot ?? round.pot ?? 0) + ' VP'),
      chip('已售', String(round.tickets ?? 0) + ' 张'),
      chip('截止', round.cutoff || '—'),
      chip('滚存', String(data.rollover ?? 0) + ' VP'),
    );
    renderLotteryItems('user-pool', active.items, '待开奖奖池为空。');
    renderLotteryItems('user-betting-pool', round.items, '下注轮还没有人投入物品。');
    if (data.fund) {
      root.append(chip('服务器基金', String(data.fund.value ?? 0) + ' VP · '
        + String(data.fund.count ?? 0) + ' 项'));
    }
    if (data.gold) {
      root.append(
        chip('国际金价', data.gold.enabled
          ? Number(data.gold.price || 0).toFixed(2) + ' USD/oz' : '未启用'),
        chip('金块估值', vp(data.gold.blockValue)),
      );
    }
    if (data.insurance && data.insurance.enabled) {
      root.append(chip('保险', '保单 ' + (data.insurance.total ?? 0)
        + ' · 生效 ' + (data.insurance.active ?? 0)
        + ' · 待审核 ' + (data.insurance.pending ?? 0)));
    }
    const fundRoot = $('user-fund');
    if (fundRoot) {
      fundRoot.replaceChildren();
      const items = data.fund && Array.isArray(data.fund.items) ? data.fund.items : [];
      for (const item of items) {
        const card = makeEl('div', 'lottery-item-card');
        card.append(window.SamsaraItem.icon(item, { size: 'md' }));
        const body = makeEl('div', 'lottery-item-body');
        body.append(
          makeEl('span', 'lottery-item-name', item.zh || item.name || item.item || '—'),
          makeEl('span', 'lottery-item-meta', (item.count || 0) + ' 个 · ' + vp(item.value)),
        );
        card.append(body);
        fundRoot.append(card);
      }
    }
    const last = data.last;
    if (last) {
      const winners = Array.isArray(last.winners) ? last.winners.join('、') : '';
      root.append(
        chip('上期死亡', String(last.deathCount ?? 0) + ' 个'),
        chip('上期中奖', String(last.winnerCount ?? 0) + ' 人'),
        chip('上期奖池', String(last.pot ?? 0) + ' VP' + (last.rollover ? ' · 滚存' : '')),
      );
      if (winners) root.append(chip('上期中奖者', winners));
    } else {
      root.append(chip('上期', '暂无记录'));
    }
  }

  async function loadSummary() {
    const message = $('user-message');
    try {
      const [status, worlds, lottery] = await Promise.all([
        api('/api/user/status'),
        api('/api/user/worlds'),
        api('/api/user/lottery'),
      ]);
      renderStatus(status);
      renderWorlds(worlds);
      renderLottery(lottery);
      if (message) {
        message.textContent = '';
        message.className = 'message';
      }
    } catch (error) {
      if (message) {
        message.textContent = '公开信息读取失败：' + (error.message || '未知错误');
        message.className = 'message error';
      }
    }
  }

  // map.js 在 user.js 之后加载；等 DOMContentLoaded 后再渲染世界栈卡片，
  // 这样卡片可以复用 map.js 导出的 SamsaraStackIcon（地图回退/图标区域）。
  let userValueTable = null;

  function start() {
    if ($('user-values') && window.SamsaraValueTable) {
      userValueTable = window.SamsaraValueTable.create({
        rootId: 'user-values',
        scrollId: 'user-values-scroll',
        emptyId: 'user-values-empty',
        countId: 'user-values-count',
        searchId: 'user-values-search',
        categoryId: 'user-values-category',
        sortId: 'user-values-sort',
        endpoint: '/api/user/lottery/values',
        fetch: api,
        onError: (text) => {
          const message = $('user-message');
          if (message) {
            message.textContent = text;
            message.className = 'message error';
          }
        },
      });
      userValueTable.refresh();
    }
    loadSummary();
    window.setInterval(loadSummary, 5000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => window.setTimeout(start, 0));
  } else {
    window.setTimeout(start, 0);
  }
})();
