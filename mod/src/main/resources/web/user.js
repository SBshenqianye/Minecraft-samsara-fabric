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

  async function loadSummary() {
    const message = $('user-message');
    try {
      const [status, worlds] = await Promise.all([
        api('/api/user/status'),
        api('/api/user/worlds'),
      ]);
      renderStatus(status);
      renderWorlds(worlds);
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
  function start() {
    loadSummary();
    window.setInterval(loadSummary, 5000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => window.setTimeout(start, 0));
  } else {
    window.setTimeout(start, 0);
  }
})();
