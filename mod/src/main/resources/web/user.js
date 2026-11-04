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

  let userValues = [];
  let userValuesShown = 200;

  function valueIcon(row) {
    const box = makeEl('span', 'lottery-value-icon');
    if (row.icon) {
      const img = document.createElement('img');
      img.src = '/items/' + row.icon + '.png';
      img.alt = '';
      img.onerror = () => img.remove();
      if (row.id === 'minecraft:enchanted_golden_apple') img.classList.add('glint');
      box.append(img);
    } else {
      box.classList.add('text');
      box.textContent = String(row.zh || row.name || '?').slice(0, 1);
    }
    return box;
  }

  function pinyinMatch(row, query) {
    if (!query) return true;
    const py = String(row.py || '');
    const initials = String(row.initials || '');
    if (py.includes(query) || initials.includes(query)) return true;
    const syllables = Array.isArray(row.syllables) ? row.syllables : [];
    if (!syllables.length) return false;
    let dp = new Array(query.length + 1).fill(false);
    dp[0] = true;
    for (const raw of syllables) {
      const syllable = String(raw || '');
      if (!syllable) continue;
      const next = new Array(query.length + 1).fill(false);
      for (let j = 0; j <= query.length; j++) if (dp[j]) next[j] = true;
      for (let j = 0; j < query.length; j++) {
        if (!dp[j]) continue;
        for (let k = 1; k <= syllable.length && j + k <= query.length; k++) {
          if (query.startsWith(syllable.slice(0, k), j)) next[j + k] = true;
        }
      }
      dp = next;
    }
    return dp[query.length];
  }

  function renderUserValues() {
    const root = $('user-values');
    const emptyEl = $('user-values-empty');
    const scroll = $('user-values-scroll');
    const count = $('user-values-count');
    if (!root) return;
    const search = $('user-values-search');
    const sort = $('user-values-sort');
    const query = (search && search.value ? search.value : '').trim().toLowerCase();
    let rows = userValues.filter((row) => {
      if (!query) return true;
      const text = ((row.name || '') + ' ' + (row.zh || '') + ' ' + (row.id || '')
        + ' ' + (row.category || '')).toLowerCase();
      return text.includes(query) || pinyinMatch(row, query);
    });
    const mode = sort ? sort.value : 'value-desc';
    if (mode === 'name') {
      rows.sort((a, b) => String(a.zh || a.name || a.id).localeCompare(String(b.zh || b.name || b.id), 'zh-Hans-CN'));
    } else {
      const dir = mode === 'value-asc' ? 1 : -1;
      rows.sort((a, b) => dir * (Number(a.value || 0) - Number(b.value || 0)));
    }
    if (count) count.textContent = rows.length + ' / ' + userValues.length + ' 项';
    root.replaceChildren();
    if (!rows.length) {
      if (scroll) scroll.hidden = true;
      if (emptyEl) emptyEl.hidden = false;
      return;
    }
    if (scroll) scroll.hidden = false;
    if (emptyEl) emptyEl.hidden = true;
    for (const row of rows.slice(0, userValuesShown)) {
      const line = makeEl('div', 'lottery-value-row');
      const item = makeEl('div', 'lottery-value-item');
      item.append(valueIcon(row));
      const zh = row.zh && row.zh !== row.name ? row.zh : '';
      item.append(makeEl('span', 'lottery-value-name', zh || row.name || row.id));
      item.append(makeEl('span', 'lottery-value-id',
        zh ? (row.name || '') + ' · ' + row.id : row.id));
      line.append(item, makeEl('span', 'lottery-value-cat', row.category || '其他'));
      line.append(makeEl('strong', 'num', vp(row.value)));
      root.append(line);
    }
    if (rows.length > userValuesShown) {
      const more = makeEl('button', 'ghost lottery-more',
        '显示更多（剩余 ' + (rows.length - userValuesShown) + ' 项）');
      more.type = 'button';
      more.addEventListener('click', () => {
        userValuesShown += 300;
        renderUserValues();
      });
      root.append(more);
    }
  }

  async function loadUserValues() {
    try {
      const model = await api('/api/user/lottery/values');
      userValues = Array.isArray(model.values) ? model.values : [];
      userValuesShown = 200;
      renderUserValues();
    } catch (error) {
      // 价值表是附加信息，失败时不影响主摘要。
    }
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

  function renderLottery(data) {
    const root = $('user-lottery');
    if (!root) return;
    root.replaceChildren();
    if (!data || !data.enabled) {
      root.append(chip('状态', '未开启'));
      return;
    }
    const round = data.round || {};
    root.append(
      chip('结算日', round.day || '—'),
      chip('奖池', String(round.pot ?? 0) + ' VP'),
      chip('已售', String(round.tickets ?? 0) + ' 张 / ' + String(round.players ?? 0) + ' 人'),
      chip('开奖', round.drawTime || '—'),
      chip('截止', round.cutoff || '—'),
    );
    if (data.fund) {
      root.append(chip('服务器基金', String(data.fund.value ?? 0) + ' VP · '
        + String(data.fund.count ?? 0) + ' 项'));
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
        if (item.icon) {
          const img = document.createElement('img');
          img.src = '/items/' + item.icon + '.png';
          img.alt = '';
          img.onerror = () => img.remove();
          card.append(img);
        } else {
          card.append(makeEl('span', 'lottery-item-fallback',
            String(item.zh || item.name || '?').slice(0, 1)));
        }
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
  function start() {
    const search = $('user-values-search');
    if (search) {
      search.addEventListener('input', () => {
        userValuesShown = 200;
        renderUserValues();
      });
    }
    const sort = $('user-values-sort');
    if (sort) {
      sort.addEventListener('change', () => {
        userValuesShown = 200;
        renderUserValues();
      });
    }
    loadUserValues();
    loadSummary();
    window.setInterval(loadSummary, 5000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => window.setTimeout(start, 0));
  } else {
    window.setTimeout(start, 0);
  }
})();
