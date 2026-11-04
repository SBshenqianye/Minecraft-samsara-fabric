'use strict';

// 地图标签：地形底图来自服务端扫描 region 文件的 PNG tile；
// 圈地白名单/旗帜来自 claims.json。只读展示，不提供编辑入口。
(function () {
  const STORE_CHUNKS = 'samsara.map.showChunks';
  // 管理端默认 /api/map；用户端只读页在加载 map.js 前把 base 设为 /api/user/map。
  const MAP_API_BASE = (window.SamsaraMapApiBase || '/api/map').replace(/\/+$/, '');
  // 在线玩家：位置每 250ms 取一次快照，独立图层在两次快照之间做线性插值。
  // 插值只重绘玩家，不重绘地形/圈地/旗帜，因此可以按显示器帧率跑。
  const PLAYER_POLL_MS = 250;
  const PLAYER_INTERP_MS = 240;
  const PLAYER_TELEPORT_SNAP = 256;
  const state = {
    model: null,
    stackId: null,
    dimId: null,
    scale: 4,
    centerX: 0,
    centerZ: 0,
    cssWidth: 0,
    cssHeight: 0,
    dpr: 1,
    tiles: new Map(),
    pending: new Set(),
    selected: null,
    selectedSpawn: null,
    selectedChunk: null,
    chunkSummary: null,
    statsKey: '',
    hover: null,
    drag: null,
    dragMoved: false,
    loading: false,
    stale: false,
    raf: 0,
    fitPending: false,
    shading: true,
    overview: false,
    overviewKey: '',
    overviewPanels: [],
    thumbTiles: new Map(),
    players: [],
    playersFetchedAt: 0,
    playersLoading: false,
    live: null,
    liveFetching: false,
    liveTimer: 0,
    showEntities: true,
    showTrails: true,
    showRareNames: true,
    entityHold: new Map(),
    showSeparation: false,
    playerMotion: new Map(),
    playerAvatars: new Map(),
    playerRaf: 0,
    playerTimer: 0,
    showPlayers: true,
    selectedLive: null,
    hoverLive: null,
    showChunks: storedFlag(STORE_CHUNKS, false),
  };

  const DIRECTION_NAMES = [
    'north', 'northeast', 'east', 'southeast',
    'south', 'southwest', 'west', 'northwest',
  ];
  const DIRECTIONS = [
    { dx: 0, dz: -1 }, { dx: 1, dz: -1 }, { dx: 1, dz: 0 }, { dx: 1, dz: 1 },
    { dx: 0, dz: 1 }, { dx: -1, dz: 1 }, { dx: -1, dz: 0 }, { dx: -1, dz: -1 },
  ];
  const FACING_OFFSET = { north: 0, east: 2, south: 4, west: 6 };

  const $ = (id) => document.getElementById(id);
  let els = null;

  function storedFlag(key, fallback) {
    try {
      const value = window.localStorage.getItem(key);
      if (value == null) return fallback;
      return value === '1' || value === 'true';
    } catch (error) {
      return fallback;
    }
  }

  function storeFlag(key, value) {
    try {
      window.localStorage.setItem(key, value ? '1' : '0');
    } catch (error) {
      // 隐私模式/存储被禁用时只保留本次页面状态。
    }
  }

  function ensureEls() {
    if (els) return els;
    els = {
      message: $('map-message'),
      stats: $('map-stats'),
      stage: $('map-stage-anchor'),
      canvas: $('map-canvas'),
      playerCanvas: $('map-players'),
      liveCanvas: $('map-live'),
      tooltip: $('map-tooltip'),
      selection: $('map-selection'),
      stack: $('map-stack'),
      dimension: $('map-dimension'),
      zoom: $('map-zoom-label'),
      reload: $('map-reload'),
      fit: $('map-fit'),
      zoomIn: $('map-zoom-in'),
      zoomOut: $('map-zoom-out'),
      claims: $('map-show-claims'),
      chunks: $('map-show-chunks'),
      chunkMode: $('map-chunk-mode'),
      chunkLegend: $('map-chunk-grid-legend'),
      spawns: $('map-show-spawns'),
      shading: $('map-shading'),
      showPlayers: $('map-show-players'),
      showEntities: $('map-show-entities'),
      showTrails: $('map-show-trails'),
      showRareNames: $('map-show-rare-names'),
      showSeparation: $('map-show-separation'),
      liveStatus: $('map-live-status'),
      activity: $('map-activity'),
      overviewToggle: $('map-overview-toggle'),
      overview: $('map-stack-overview'),
      overviewStacks: $('map-overview-stacks'),
      overviewStatus: $('map-overview-status'),
      anchors: $('map-dim-anchors'),
    };
    if (els.canvas) {
      els.ctx = els.canvas.getContext('2d');
      els.ctx.imageSmoothingEnabled = false;
    }
    if (els.playerCanvas) {
      els.playerCtx = els.playerCanvas.getContext('2d');
      els.playerCtx.imageSmoothingEnabled = false;
    }
    if (els.liveCanvas) {
      els.liveCtx = els.liveCanvas.getContext('2d');
      els.liveCtx.imageSmoothingEnabled = false;
    }
    return els;
  }

  function setMessage(text, kind) {
    const el = ensureEls().message;
    if (!el) return;
    el.textContent = text || '';
    el.className = 'message' + (kind ? ' ' + kind : '');
  }

  function stacks() {
    return state.model && Array.isArray(state.model.stacks) ? state.model.stacks : [];
  }

  function currentStack() {
    const all = stacks();
    return all.find((stack) => stack.id === state.stackId)
      || all.find((stack) => stack.top)
      || all[0] || null;
  }

  function dimensions() {
    const stack = currentStack();
    return stack && Array.isArray(stack.dimensions) ? stack.dimensions : [];
  }

  function allDimensions() {
    const out = [];
    for (const stack of stacks()) {
      for (const dim of stack.dimensions || []) {
        out.push({ stack, dim });
      }
    }
    return out;
  }

  function currentDim() {
    return dimensions().find((dim) => dim.id === state.dimId) || dimensions()[0] || null;
  }

  function spawnPoints() {
    const stack = currentStack();
    const dim = currentDim();
    if (!stack || !dim || dim.dim === 'the_end') return [];
    const history = stack.spawnHistory;
    if (!history || !Array.isArray(history.cycles)) return [];
    // API 里出生点按 overworld/nether 两个键返回；the_nether 是维度短名。
    const key = dim.dim === 'the_nether' ? 'nether' : dim.dim;
    const out = [];
    for (const cycle of history.cycles) {
      const target = cycle[key];
      if (!target) continue;
      out.push({ cycle, target, x: target.x, z: target.z });
    }
    return out;
  }

  function safeId(value) {
    return String(value || '').replace(/[^a-z0-9]+/gi, '-');
  }

  function chunkSet(dim) {
    if (!dim || !Array.isArray(dim.protectedChunks)) return new Set();
    if (!dim._chunkSet) {
      dim._chunkSet = new Set(dim.protectedChunks.map(([x, z]) => x + ',' + z));
    }
    return dim._chunkSet;
  }

  async function loadModel() {
    if (state.loading) return;
    state.loading = true;
    setMessage('正在扫描存档中的地图与圈地数据…');
    try {
      const data = await window.SamsaraUI.api(MAP_API_BASE);
      state.model = data;
      state.stale = false;
      state.overviewKey = '';
      state.selected = null;
      state.selectedSpawn = null;
      state.selectedChunk = null;
      state.chunkSummary = null;
      const allStacks = stacks();
      if (!state.stackId || !allStacks.some((stack) => stack.id === state.stackId)) {
        const top = allStacks.find((stack) => stack.top) || allStacks[0];
        state.stackId = top ? top.id : null;
      }
      const dims = dimensions();
      if (!state.dimId || !dims.some((dim) => dim.id === state.dimId)) {
        state.dimId = dims.length ? dims[0].id : null;
      }
      setMessage('');
      renderControls();
      renderStats();
      if (state.overview) {
        renderOverview();
      } else {
        renderSelection();
        fitView();
        state.fitPending = true;
        draw();
      }
      refreshCount();
      renderNav();
      loadPlayers();
      loadLive();
    } catch (error) {
      setMessage(window.SamsaraUI.authMessage(error, '地图数据读取失败'), 'error');
    } finally {
      state.loading = false;
    }
  }

  function renderControls() {
    const { stack: stackSelect, dimension } = ensureEls();
    if (stackSelect) {
      stackSelect.replaceChildren();
      for (const stack of stacks()) {
        const option = document.createElement('option');
        option.value = stack.id;
        option.textContent = stack.top
          ? 'world（栈顶）'
          : stack.id + '（第 ' + ((stack.index || 0) + 1) + ' 层）';
        stackSelect.append(option);
      }
      stackSelect.value = state.stackId || '';
      stackSelect.disabled = stacks().length === 0;
    }
    if (!dimension) return;
    dimension.replaceChildren();
    for (const dim of dimensions()) {
      const option = document.createElement('option');
      option.value = dim.id;
      option.textContent = dim.name + '（' + (dim.protectedCount || 0) + ' 区块 / '
          + (dim.bannerCount || 0) + ' 旗）';
      dimension.append(option);
    }
    dimension.value = state.dimId || '';
    dimension.disabled = dimensions().length === 0;
    applyOverviewUi();
  }

  function renderStats() {
    const { stats } = ensureEls();
    if (!stats) return;
    const stack = currentStack();
    const dim = currentDim();
    const all = allDimensions();
    const totalProtected = all.reduce((sum, item) => sum + (item.dim.protectedCount || 0), 0);
    const totalBanners = all.reduce((sum, item) => sum + (item.dim.bannerCount || 0), 0);
    const cycles = stack && stack.spawnHistory && Array.isArray(stack.spawnHistory.cycles)
      ? stack.spawnHistory.cycles.length : 0;
    const spawn = stack ? stack.spawn : null;
    const totalPlayers = state.players.length;
    const currentPlayers = stack && dim ? playersFor(stack.id, dim.dim).length : 0;
    const chips = state.overview
      ? [
        ['栈层', String(stacks().length)],
        ['维度', String(all.length)],
        ['在线玩家', totalPlayers + ' 人'],
        ['全服圈地', totalProtected + ' 区块 / ' + totalBanners + ' 旗'],
      ]
      : [
        ['栈层', stack ? stack.label : '无'],
        ['栈序号', stack ? '#' + ((stack.index || 0) + 1) : '—'],
        ['出生点', spawn ? spawn.x + ', ' + spawn.y + ', ' + spawn.z : '—'],
        ['出生历史', cycles + ' 轮'],
        ['当前地形', dim ? dim.regions.length + ' 个 region' : '无'],
        ['保护区块', dim ? String(dim.protectedCount || 0) : '0'],
        ['旗帜', dim ? String(dim.bannerCount || 0) : '0'],
        ['在线玩家', currentPlayers + ' / ' + totalPlayers + ' 人'],
        ['全服圈地', totalProtected + ' 区块 / ' + totalBanners + ' 旗'],
      ];
    const key = chips.map(([label, value]) => label + '=' + value).join('\n');
    if (key === state.statsKey) return;
    state.statsKey = key;
    stats.replaceChildren();
    for (const [label, value] of chips) {
      const chip = document.createElement('span');
      chip.className = 'map-stat';
      const name = document.createElement('span');
      name.textContent = label;
      const strong = document.createElement('strong');
      strong.textContent = value;
      chip.append(name, strong);
      stats.append(chip);
    }
  }

  function renderNav() {
    if (!window.SamsaraUI) return;
    const targets = [];
    const items = [];
    const stats = ensureEls().stats;
    const stage = ensureEls().stage;
    const selection = ensureEls().selection;
    const anchors = ensureEls().anchors;
    if (state.overview) {
      const overview = ensureEls().overview;
      if (overview) {
        const totalProtected = allDimensions()
          .reduce((sum, item) => sum + (item.dim.protectedCount || 0), 0);
        targets.push({ id: 'map-stack-overview', el: overview });
        items.push({
          label: '世界栈总览',
          count: totalProtected,
          target: 'map-stack-overview',
        });
      }
      for (const stack of stacks()) {
        const id = 'map-stack-overview-' + safeId(stack.id);
        const el = document.getElementById(id);
        if (!el) continue;
        targets.push({ id, el });
        items.push({
          label: stack.label,
          count: (stack.dimensions || []).reduce((sum, dim) => sum + (dim.protectedCount || 0), 0),
          target: id,
        });
      }
      window.SamsaraUI.buildNav('地图导航', items, targets);
      return;
    }
    const stackAnchors = new Map();
    const dimAnchors = new Map();
    if (anchors) {
      anchors.replaceChildren();
      for (const stack of stacks()) {
        const stackAnchor = document.createElement('span');
        stackAnchor.id = 'map-stack-' + safeId(stack.id);
        anchors.append(stackAnchor);
        stackAnchors.set(stack.id, stackAnchor.id);
        for (const dim of stack.dimensions || []) {
          const anchor = document.createElement('span');
          anchor.id = 'map-dim-' + safeId(stack.id) + '-' + safeId(dim.id);
          anchors.append(anchor);
          dimAnchors.set(stack.id + '|' + dim.id, anchor.id);
        }
      }
    }
    if (stats) {
      const totalProtected = allDimensions()
        .reduce((sum, item) => sum + (item.dim.protectedCount || 0), 0);
      targets.push({ id: 'map-stats', el: stats });
      items.push({ label: '全服统计', count: totalProtected, target: 'map-stats' });
    }
    for (const stack of stacks()) {
      const stackTotal = (stack.dimensions || [])
        .reduce((sum, dim) => sum + (dim.protectedCount || 0), 0);
      const children = [];
      for (const dim of stack.dimensions || []) {
        const anchorId = dimAnchors.get(stack.id + '|' + dim.id) || 'map-overview';
        children.push({
          label: dim.name,
          count: dim.protectedCount || 0,
          target: anchorId,
          onClick: () => {
            if (stack.id !== state.stackId) switchStack(stack.id);
            if (dim.id !== state.dimId) switchDimension(dim.id);
            const select = ensureEls().dimension;
            if (select) select.value = dim.id;
            window.SamsaraUI.scrollToTarget?.('map-stage-anchor');
          },
        });
      }
      items.push({
        label: stack.label,
        count: stackTotal,
        target: stackAnchors.get(stack.id) || 'map-overview',
        children,
        onClick: () => {
          if (stack.id !== state.stackId) switchStack(stack.id);
          window.SamsaraUI.scrollToTarget?.('map-stage-anchor');
        },
      });
    }
    if (selection) {
      targets.push({ id: 'map-stage-anchor', el: stage });
      targets.push({ id: 'map-detail', el: selection });
      items.push({ label: state.selectedSpawn ? '选中出生点' : '选中旗帜', target: 'map-detail' });
    }
    window.SamsaraUI.buildNav('地图导航', items.filter((item) => item.target), targets);
  }

  // ---- 在线玩家 / 世界栈总览 ----

  function playerName(player) {
    return player && player.name ? player.name : '玩家';
  }

  function avatarKey(player) {
    return String((player && (player.uuid || player.name)) || '').trim().toLowerCase();
  }

  function avatarFallback(player) {
    const name = playerName(player).trim();
    const initial = /^[A-Za-z0-9\u4e00-\u9fff]/.test(name)
      ? name.charAt(0).toUpperCase() : '?';
    let hash = 0;
    for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0;
    const hue = Math.abs(hash) % 360;
    const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 48 48">'
      + '<rect width="48" height="48" rx="9" fill="hsl(' + hue + ' 38% 30%)"/>'
      + '<rect x="10" y="9" width="28" height="20" rx="5" fill="hsl(' + hue + ' 52% 52%)"/>'
      + '<circle cx="24" cy="19" r="6" fill="hsl(' + hue + ' 45% 82%)"/>'
      + '<rect x="9" y="30" width="30" height="11" rx="5" fill="hsl(' + hue + ' 48% 46%)"/>'
      + '<text x="24" y="39" text-anchor="middle" font-family="system-ui,sans-serif" '
      + 'font-size="10" font-weight="700" fill="#f8fafc">' + initial + '</text></svg>';
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
  }

  function playerAvatarEntry(player) {
    const key = avatarKey(player) || 'unknown';
    let entry = state.playerAvatars.get(key);
    if (entry) return entry;
    const uuid = String((player && player.uuid) || '').trim();
    const name = String((player && player.name) || '').trim();
    let url = '';
    if (uuid) {
      url = '/api/user/map/avatar?uuid=' + encodeURIComponent(uuid);
    } else if (name) {
      url = '/api/user/map/avatar?name=' + encodeURIComponent(name);
    }
    entry = {
      url,
      fallback: avatarFallback(player),
      remoteLoaded: false,
      loading: false,
      failed: false,
      image: null,
      waiters: new Set(),
    };
    state.playerAvatars.set(key, entry);
    return entry;
  }

  /** 只取皮肤头部正面 8×8 像素（帽子层叠加），避免整张皮肤缩成小图。 */
  function headDataUrl(entry) {
    if (!entry || !entry.image) return entry ? entry.fallback : '';
    if (entry.headUrl) return entry.headUrl;
    try {
      const canvas = document.createElement('canvas');
      canvas.width = 64;
      canvas.height = 64;
      const ctx = canvas.getContext('2d');
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(entry.image, 8, 8, 8, 8, 0, 0, 64, 64);
      try { ctx.drawImage(entry.image, 40, 8, 8, 8, 0, 0, 64, 64); } catch (e) { /* 旧版 64×32 无帽子层 */ }
      entry.headUrl = canvas.toDataURL('image/png');
    } catch (e) {
      entry.headUrl = entry.fallback;
    }
    return entry.headUrl;
  }

  function ensureAvatarLoaded(entry) {
    if (!entry || entry.remoteLoaded || entry.loading || entry.failed || !entry.url) return;
    entry.loading = true;
    const image = new Image();
    image.referrerPolicy = 'no-referrer';
    image.decoding = 'async';
    image.onload = () => {
      entry.loading = false;
      entry.remoteLoaded = true;
      entry.image = image;
      const head = headDataUrl(entry);
      for (const waiter of entry.waiters) {
        if (waiter.isConnected) waiter.src = head;
      }
      entry.waiters.clear();
      schedulePlayerDraw();
    };
    image.onerror = () => {
      entry.loading = false;
      entry.failed = true;
      entry.waiters.clear();
    };
    image.src = entry.url;
  }

  /** 先立即显示本地通用头像，再异步换成皮肤头部；失败就保持本地图，布局不跳动。 */
  function applyAvatarImage(img, player) {
    const entry = playerAvatarEntry(player);
    img.src = entry.remoteLoaded ? headDataUrl(entry) : entry.fallback;
    if (!entry.remoteLoaded) {
      entry.waiters.add(img);
      ensureAvatarLoaded(entry);
    }
    return entry;
  }

  function playersFor(stackId, dimension) {
    return state.players.filter((player) => player.stackId === stackId
      && player.dimension === dimension);
  }

  function dimensionLabel(dim) {
    if (dim === 'the_nether') return '下界';
    if (dim === 'the_end') return '末地';
    return '主世界';
  }

  function stackLabelOf(stackId) {
    const stack = stacks().find((item) => item.id === stackId);
    return stack ? stack.label : (stackId || '未知栈层');
  }

  function fmtCoord(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) return '0';
    return Number.isInteger(number) ? String(number) : number.toFixed(1);
  }

  function playerCommand(player) {
    return '/execute in ' + player.dimensionId + ' run tp ' + playerName(player)
      + ' ' + fmtCoord(player.x) + ' ' + fmtCoord(player.y) + ' ' + fmtCoord(player.z);
  }

  async function copyPlainText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (error) {
      // 非安全上下文/浏览器拒绝时退回隐藏 textarea。
    }
    const area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.append(area);
    area.select();
    let ok = false;
    try {
      ok = document.execCommand('copy');
    } catch (error) {
      ok = false;
    } finally {
      area.remove();
    }
    return ok;
  }

  async function copyPlayerCommand(player) {
    const ok = await copyPlainText(playerCommand(player));
    setMessage(ok ? '已复制 ' + playerName(player) + ' 的 TP 命令' : '复制失败，请手动复制',
      ok ? 'ok' : 'error');
  }

  function updatePlayerStatus() {
    const el = ensureEls().overviewStatus;
    if (!el) return;
    const time = state.playersFetchedAt
      ? new Date(state.playersFetchedAt).toLocaleTimeString() : '—';
    const interval = PLAYER_POLL_MS >= 1000
      ? (PLAYER_POLL_MS / 1000) + ' 秒' : PLAYER_POLL_MS + ' 毫秒';
    el.textContent = '在线 ' + state.players.length + ' 人 · ' + interval + '刷新 · ' + time;
  }

  function playerKey(player) {
    return String((player && (player.uuid || player.name)) || '') + '|'
      + String((player && player.dimension) || '');
  }

  /** 根据上一帧的位置与本次快照，生成一个短插值；传送/换维度时直接吸附。 */
  function syncPlayerMotion(players, now) {
    const keep = new Set();
    for (const player of players) {
      const key = playerKey(player);
      keep.add(key);
      const x = Number(player.x) || 0;
      const z = Number(player.z) || 0;
      const previous = state.playerMotion.get(key);
      let fromX = x;
      let fromZ = z;
      let duration = 0;
      if (previous) {
        const progressed = previous.duration > 0
          ? Math.min(1, Math.max(0, (now - previous.startedAt) / previous.duration))
          : 1;
        fromX = previous.fromX + (previous.toX - previous.fromX) * progressed;
        fromZ = previous.fromZ + (previous.toZ - previous.fromZ) * progressed;
        const distance = Math.hypot(x - fromX, z - fromZ);
        if (distance > 0.05 && distance <= PLAYER_TELEPORT_SNAP) {
          duration = PLAYER_INTERP_MS;
        } else {
          fromX = x;
          fromZ = z;
        }
      }
      state.playerMotion.set(key, {
        fromX, fromZ, toX: x, toZ: z, startedAt: now, duration,
      });
    }
    for (const key of state.playerMotion.keys()) {
      if (!keep.has(key)) state.playerMotion.delete(key);
    }
  }

  function playerDisplayPosition(player, now) {
    const motion = state.playerMotion.get(playerKey(player));
    if (!motion || motion.duration <= 0) {
      return { x: Number(player.x) || 0, z: Number(player.z) || 0 };
    }
    const t = Math.min(1, Math.max(0, (now - motion.startedAt) / motion.duration));
    return {
      x: motion.fromX + (motion.toX - motion.fromX) * t,
      z: motion.fromZ + (motion.toZ - motion.fromZ) * t,
    };
  }

  function playerMotionActive(now) {
    for (const motion of state.playerMotion.values()) {
      if (motion.duration > 0 && now < motion.startedAt + motion.duration) return true;
    }
    return false;
  }

  async function loadPlayers() {
    if (!state.model || state.playersLoading) return;
    if (window.SamsaraUI && window.SamsaraUI.getToken && !window.SamsaraUI.getToken()) return;
    state.playersLoading = true;
    try {
      const data = await window.SamsaraUI.api(MAP_API_BASE + '/players');
      const players = Array.isArray(data.players) ? data.players : [];
      syncPlayerMotion(players, performance.now());
      state.players = players;
      state.playersFetchedAt = Date.now();
      updatePlayerStatus();
      if (state.overview) {
        updateOverviewPlayers();
        renderStats();
      } else {
        renderStats();
        // 只重绘玩家图层；地形、圈地、旗帜不再因为轮询而重绘。
        schedulePlayerDraw();
      }
    } catch (error) {
      // 玩家轮询失败不打断地图浏览；连接失效由统一 API 处理。
    } finally {
      state.playersLoading = false;
    }
  }

  function dimColors(dim) {
    if (dim === 'the_nether') {
      return {
        bg1: '#2a0f0f', bg2: '#160808',
        fill: 'rgba(248, 113, 113, .42)', line: 'rgba(251, 146, 60, .9)',
        placeholder: 'rgba(248, 113, 113, .09)',
      };
    }
    if (dim === 'the_end') {
      return {
        bg1: '#1b1230', bg2: '#0d0818',
        fill: 'rgba(167, 139, 250, .42)', line: 'rgba(196, 181, 253, .9)',
        placeholder: 'rgba(167, 139, 250, .09)',
      };
    }
    return {
      bg1: '#0b1b1c', bg2: '#081012',
      fill: 'rgba(45, 212, 191, .42)', line: 'rgba(94, 234, 212, .9)',
      placeholder: 'rgba(45, 212, 191, .09)',
    };
  }

  function regionCenter(stack, dim) {
    const bounds = dim && dim.bounds;
    if (bounds && (bounds.maxX > bounds.minX || bounds.maxZ > bounds.minZ)) {
      return { x: (bounds.minX + bounds.maxX) / 2, z: (bounds.minZ + bounds.maxZ) / 2 };
    }
    const cycles = stack && stack.spawnHistory && Array.isArray(stack.spawnHistory.cycles)
      ? stack.spawnHistory.cycles : [];
    if (dim && dim.dim === 'the_nether' && cycles.length > 0) {
      const cycle = cycles.find((item) => item.current) || cycles[cycles.length - 1];
      if (cycle && cycle.nether) return { x: cycle.nether.x, z: cycle.nether.z };
    }
    if (dim && dim.dim === 'the_end') {
      return { x: 0, z: 0 };
    }
    if (stack && stack.spawn) {
      return { x: stack.spawn.x, z: stack.spawn.z };
    }
    if (dim && Array.isArray(dim.regions) && dim.regions.length > 0) {
      return {
        x: (dim.regions[0][0] + .5) * 512,
        z: (dim.regions[0][1] + .5) * 512,
      };
    }
    return { x: 0, z: 0 };
  }

  function panelView(stack, dim, canvasW, canvasH) {
    const regions = dim && Array.isArray(dim.regions) ? dim.regions : [];
    const center = regionCenter(stack, dim);
    let rx = Math.floor(center.x / 512);
    let rz = Math.floor(center.z / 512);
    if (regions.length > 0 && !regions.some(([x, z]) => x === rx && z === rz)) {
      let best = regions[0];
      let bestDistance = Infinity;
      for (const [x, z] of regions) {
        const distance = Math.abs(x - rx) + Math.abs(z - rz);
        if (distance < bestDistance) {
          bestDistance = distance;
          best = [x, z];
        }
      }
      rx = best[0];
      rz = best[1];
    }
    const regionX = rx * 512;
    const regionZ = rz * 512;
    const aspect = canvasW / canvasH;
    let viewW;
    let viewH;
    if (aspect >= 1) {
      viewW = 512;
      viewH = Math.round(512 / aspect);
    } else {
      viewH = 512;
      viewW = Math.round(512 * aspect);
    }
    viewW = Math.max(1, Math.min(512, viewW));
    viewH = Math.max(1, Math.min(512, viewH));
    let viewX = center.x - viewW / 2;
    let viewZ = center.z - viewH / 2;
    viewX = Math.max(regionX, Math.min(regionX + 512 - viewW, viewX));
    viewZ = Math.max(regionZ, Math.min(regionZ + 512 - viewH, viewZ));
    return {
      rx, rz, regionX, regionZ, viewX, viewZ, viewW, viewH,
      canvasW, canvasH, hasRegion: regions.length > 0,
    };
  }

  function overviewThumbKey(panel) {
    return panel.stack.id + '|' + panel.dim.dim + '|' + panel.view.rx + '|'
      + panel.view.rz + '|8|' + (state.shading ? 1 : 0);
  }

  function drawOverviewPanel(panel) {
    if (!panel || !panel.ctx) return;
    const { ctx } = panel;
    const view = panel.view;
    const { canvasW, canvasH } = view;
    const colors = dimColors(panel.dim.dim);
    ctx.clearRect(0, 0, canvasW, canvasH);
    const gradient = ctx.createLinearGradient(0, 0, 0, canvasH);
    gradient.addColorStop(0, colors.bg1);
    gradient.addColorStop(1, colors.bg2);
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, canvasW, canvasH);

    if (panel.image && view.hasRegion) {
      const sx = (view.viewX - view.regionX) / 8;
      const sy = (view.viewZ - view.regionZ) / 8;
      const sw = view.viewW / 8;
      const sh = view.viewH / 8;
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(panel.image, sx, sy, sw, sh, 0, 0, canvasW, canvasH);
    } else {
      ctx.strokeStyle = colors.placeholder;
      ctx.lineWidth = 1;
      for (let x = -canvasH; x < canvasW; x += 18) {
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x + canvasH, canvasH);
        ctx.stroke();
      }
      ctx.fillStyle = 'rgba(226, 232, 240, .58)';
      ctx.font = '12px system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(view.hasRegion ? '地形加载中…' : '暂无 region 数据', canvasW / 2, canvasH / 2);
    }

    const showClaims = !ensureEls().claims || ensureEls().claims.checked;
    if (showClaims) {
      const chunkW = (16 / view.viewW) * canvasW;
      const chunkH = (16 / view.viewH) * canvasH;
      for (const [cx, cz] of panel.dim.protectedChunks || []) {
        const blockX = cx * 16;
        const blockZ = cz * 16;
        if (blockX + 16 < view.viewX || blockX > view.viewX + view.viewW
            || blockZ + 16 < view.viewZ || blockZ > view.viewZ + view.viewH) {
          continue;
        }
        const x = (blockX - view.viewX) / view.viewW * canvasW;
        const z = (blockZ - view.viewZ) / view.viewH * canvasH;
        ctx.fillStyle = colors.fill;
        ctx.fillRect(x, z, Math.max(1, chunkW), Math.max(1, chunkH));
      }
      ctx.strokeStyle = colors.line;
      ctx.lineWidth = 1;
      ctx.strokeRect(.5, .5, canvasW - 1, canvasH - 1);
    }

    if (panel.dim.dim === 'overworld' && panel.stack.spawn) {
      const spawn = panel.stack.spawn;
      const x = (spawn.x - view.viewX) / view.viewW * canvasW;
      const z = (spawn.z - view.viewZ) / view.viewH * canvasH;
      if (x >= 0 && x <= canvasW && z >= 0 && z <= canvasH) {
        ctx.save();
        ctx.translate(x, z);
        ctx.rotate(Math.PI / 4);
        ctx.fillStyle = '#f472b6';
        ctx.strokeStyle = '#fdf2f8';
        ctx.lineWidth = 1;
        ctx.fillRect(-4, -4, 8, 8);
        ctx.strokeRect(-4, -4, 8, 8);
        ctx.restore();
      }
    }
  }

  function loadOverviewThumb(panel) {
    if (!panel.view.hasRegion) {
      panel.tileState = 'missing';
      drawOverviewPanel(panel);
      return;
    }
    const key = overviewThumbKey(panel);
    if (state.thumbTiles.has(key)) {
      panel.image = state.thumbTiles.get(key);
      panel.tileState = panel.image ? 'done' : 'missing';
      drawOverviewPanel(panel);
      return;
    }
    panel.tileState = 'loading';
    fetchTileImage(panel.stack.id, panel.dim.dim, panel.view.rx, panel.view.rz, 8, state.shading)
      .then((image) => {
        state.thumbTiles.set(key, image);
        panel.image = image;
        panel.tileState = 'done';
        drawOverviewPanel(panel);
      })
      .catch(() => {
        state.thumbTiles.set(key, null);
        panel.image = null;
        panel.tileState = 'missing';
        drawOverviewPanel(panel);
      });
  }

  function markerPosition(player, panel) {
    const { view } = panel;
    let x = (player.x - view.viewX) / view.viewW * 100;
    let z = (player.z - view.viewZ) / view.viewH * 100;
    const outside = x < 0 || x > 100 || z < 0 || z > 100;
    return {
      x: Math.max(3, Math.min(97, x)),
      // pin 的头部在坐标点上方，顶部多留一点空间避免被缩略图圆角裁掉。
      z: Math.max(16, Math.min(95, z)),
      outside,
    };
  }

  function applyPlayerMarker(marker, player, panel) {
    const position = markerPosition(player, panel);
    marker.style.left = position.x + '%';
    marker.style.top = position.z + '%';
    marker.classList.toggle('outside', position.outside);
    marker.__player = player;
    marker.title = playerName(player) + ' · ' + stackLabelOf(player.stackId)
      + ' · ' + dimensionLabel(player.dimension)
      + '\n坐标 ' + fmtCoord(player.x) + ', ' + fmtCoord(player.y) + ', ' + fmtCoord(player.z)
      + (position.outside ? '\n（在缩略图范围外，标记已贴边）' : '')
      + '\n点击复制 TP 命令';
    marker.setAttribute('aria-label', playerName(player) + '，点击复制 TP 命令');
  }

  function createPlayerMarker(player, panel) {
    const marker = document.createElement('button');
    marker.type = 'button';
    marker.className = 'map-player-marker';
    const avatar = document.createElement('span');
    avatar.className = 'avatar';
    const image = document.createElement('img');
    image.alt = '';
    image.decoding = 'async';
    image.referrerPolicy = 'no-referrer';
    applyAvatarImage(image, player);
    avatar.append(image);
    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = playerName(player);
    const tip = document.createElement('span');
    tip.className = 'pin-tip';
    tip.setAttribute('aria-hidden', 'true');
    marker.append(avatar, name, tip);
    marker.addEventListener('click', (event) => {
      event.stopPropagation();
      copyPlayerCommand(marker.__player || player);
    });
    applyPlayerMarker(marker, player, panel);
    return marker;
  }

  function updateOverviewPlayers() {
    const showPlayers = !ensureEls().showPlayers || ensureEls().showPlayers.checked;
    state.showPlayers = showPlayers;
    for (const panel of state.overviewPanels) {
      const players = playersFor(panel.stack.id, panel.dim.dim);
      panel.meta.textContent = (panel.dim.protectedCount || 0) + ' 区块 · '
        + (panel.dim.bannerCount || 0) + ' 旗 · ' + players.length + ' 人';
      if (!showPlayers || !panel.view.hasRegion) {
        panel.playerLayer.replaceChildren();
        panel.markers = new Map();
        continue;
      }
      const markers = panel.markers || new Map();
      const keep = new Set();
      for (const player of players) {
        const key = playerKey(player);
        keep.add(key);
        let marker = markers.get(key);
        if (marker) {
          applyPlayerMarker(marker, player, panel);
        } else {
          marker = createPlayerMarker(player, panel);
          markers.set(key, marker);
          panel.playerLayer.append(marker);
        }
      }
      for (const [key, marker] of markers) {
        if (!keep.has(key)) {
          marker.remove();
          markers.delete(key);
        }
      }
      panel.markers = markers;
    }
    for (const stack of stacks()) {
      const el = document.getElementById('map-stack-players-' + safeId(stack.id));
      if (el) el.textContent = '在线 ' + playersForStack(stack.id).length + ' 人';
    }
    updatePlayerStatus();
  }

  function playersForStack(stackId) {
    return state.players.filter((player) => player.stackId === stackId);
  }

  function createOverviewPanel(stack, dim, canvasW, canvasH) {
    const panelEl = document.createElement('div');
    panelEl.className = 'map-stack-panel dim-' + dim.dim;
    panelEl.tabIndex = 0;
    panelEl.setAttribute('role', 'button');
    panelEl.setAttribute('aria-label', stack.label + ' · ' + dim.name + '，点击查看详细地图');
    const view = panelView(stack, dim, canvasW, canvasH);
    const canvas = document.createElement('canvas');
    canvas.className = 'map-stack-thumb';
    canvas.width = canvasW;
    canvas.height = canvasH;
    const head = document.createElement('div');
    head.className = 'map-stack-panel-head';
    const name = document.createElement('span');
    name.className = 'map-stack-panel-name';
    name.textContent = dim.name;
    const meta = document.createElement('span');
    meta.className = 'map-stack-panel-meta';
    head.append(name, meta);
    const playerLayer = document.createElement('div');
    playerLayer.className = 'map-stack-player-layer';
    const hint = document.createElement('div');
    hint.className = 'map-stack-panel-hint';
    hint.textContent = '点击查看详细地图';
    panelEl.append(canvas, head, playerLayer, hint);
    const panel = {
      stack, dim, view, canvas, ctx: canvas.getContext('2d'),
      playerLayer, panelEl, meta, image: null, tileState: 'idle',
    };
    state.overviewPanels.push(panel);
    drawOverviewPanel(panel);
    loadOverviewThumb(panel);
    const open = () => openDetailed(stack.id, dim.dim);
    panelEl.addEventListener('click', (event) => {
      if (event.target.closest('.map-player-marker')) return;
      open();
    });
    panelEl.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        open();
      }
    });
    return panelEl;
  }

  function renderOverview() {
    const e = ensureEls();
    if (!e.overviewStacks) return;
    const ordered = stacks();
    const overviewKey = JSON.stringify(ordered.map((stack) => [
      stack.id, stack.label, stack.seed, stack.index, stack.spawn,
      (stack.dimensions || []).map((dim) => [
        dim.dim, dim.protectedCount, dim.bannerCount,
        (dim.regions || []).length, dim.bounds,
      ]),
    ]));
    if (overviewKey === state.overviewKey && state.overviewPanels.length > 0) {
      updateOverviewPlayers();
      return;
    }
    state.overviewKey = overviewKey;
    e.overviewStacks.replaceChildren();
    state.overviewPanels = [];
    if (ordered.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'map-overview-empty';
      empty.textContent = '还没有可显示的世界栈。';
      e.overviewStacks.append(empty);
      updatePlayerStatus();
      return;
    }
    ordered.forEach((stack, index) => {
      const block = document.createElement('section');
      block.className = 'map-stack-block';
      block.id = 'map-stack-overview-' + safeId(stack.id);
      block.dataset.stack = stack.id;
      const head = document.createElement('div');
      head.className = 'map-stack-block-head';
      const title = document.createElement('div');
      title.className = 'map-stack-block-title';
      const strong = document.createElement('strong');
      strong.textContent = stack.label;
      const meta = document.createElement('span');
      const spawn = stack.spawn;
      meta.textContent = '栈序 #' + ((stack.index || 0) + 1)
        + ' · 种子 ' + (stack.seed || '—')
        + (spawn ? ' · 出生点 ' + spawn.x + ', ' + spawn.y + ', ' + spawn.z : '');
      title.append(strong, meta);
      const players = document.createElement('span');
      players.className = 'map-stack-block-players';
      players.id = 'map-stack-players-' + safeId(stack.id);
      players.textContent = '在线 ' + playersForStack(stack.id).length + ' 人';
      head.append(title, players);

      const body = document.createElement('div');
      body.className = 'map-stack-block-body';
      const left = document.createElement('div');
      left.className = 'map-stack-column';
      const dims = new Map((stack.dimensions || []).map((dim) => [dim.dim, dim]));
      const overworld = dims.get('overworld');
      const nether = dims.get('the_nether');
      const end = dims.get('the_end');
      if (overworld) left.append(createOverviewPanel(stack, overworld, 320, 180));
      const gap = document.createElement('div');
      gap.className = 'map-dim-gap';
      const gapLabel = document.createElement('span');
      gapLabel.textContent = '虚空 / 高空';
      gap.append(gapLabel);
      left.append(gap);
      if (nether) left.append(createOverviewPanel(stack, nether, 320, 180));
      const right = document.createElement('div');
      right.className = 'map-stack-end';
      if (end) right.append(createOverviewPanel(stack, end, 180, 225));
      body.append(left, right);
      block.append(head, body);
      e.overviewStacks.append(block);
      if (index < ordered.length - 1) {
        const voidGap = document.createElement('div');
        voidGap.className = 'map-stack-void';
        const label = document.createElement('span');
        label.textContent = '虚空 / 高空';
        voidGap.append(label);
        e.overviewStacks.append(voidGap);
      }
    });
    updateOverviewPlayers();
  }

  function applyOverviewUi() {
    const {
      stage, overview, overviewToggle, stack, dimension, fit, zoomIn, zoomOut, spawns,
    } = ensureEls();
    if (stage) stage.hidden = state.overview;
    if (overview) overview.hidden = !state.overview;
    if (overviewToggle) {
      overviewToggle.classList.toggle('active', state.overview);
      overviewToggle.setAttribute('aria-pressed', state.overview ? 'true' : 'false');
      overviewToggle.textContent = state.overview ? '详细地图' : '世界栈总览';
    }
    if (stack) stack.disabled = state.overview || stacks().length === 0;
    if (dimension) dimension.disabled = state.overview || dimensions().length === 0;
    if (fit) fit.disabled = state.overview;
    if (spawns) spawns.disabled = state.overview;
    updateZoomLabel();
  }

  function setOverview(active) {
    state.overview = Boolean(active);
    applyOverviewUi();
    if (state.overview) {
      stopPlayerDraw();
      renderOverview();
      loadPlayers();
      window.SamsaraUI?.scrollToTarget?.('map-stack-overview');
    } else {
      resize();
      draw();
    }
    renderStats();
    renderNav();
  }

  function openDetailed(stackId, dimId) {
    state.overview = false;
    if (stackId && stackId !== state.stackId) switchStack(stackId);
    if (dimId && dimId !== state.dimId) switchDimension(dimId);
    applyOverviewUi();
    resize();
    draw();
    renderStats();
    renderNav();
    window.SamsaraUI?.scrollToTarget?.('map-stage-anchor');
  }

  function fitView() {
    const dim = currentDim();
    const { canvas } = ensureEls();
    if (!canvas) return;
    const width = state.cssWidth || canvas.clientWidth || 800;
    const height = state.cssHeight || canvas.clientHeight || 520;
    const hasClaims = dim && ((dim.protectedCount || 0) > 0 || (dim.bannerCount || 0) > 0);
    if (!dim || !dim.bounds || !hasClaims) {
      // 没有圈地时不要把整张世界地图塞进视口（大存档可能有几百个 region）。
      state.centerX = 0;
      state.centerZ = 0;
      state.scale = 4;
      updateZoomLabel();
      return;
    }
    const b = dim.bounds;
    const spanX = Math.max(1, b.maxX - b.minX);
    const spanZ = Math.max(1, b.maxZ - b.minZ);
    const need = Math.max(spanX / Math.max(80, width - 80),
      spanZ / Math.max(80, height - 80));
    state.scale = [0.0625, 0.125, 0.25, 0.5, 1, 2, 4, 8, 16]
      .find((value) => value >= need) || 16;
    state.centerX = (b.minX + b.maxX) / 2;
    state.centerZ = (b.minZ + b.maxZ) / 2;
    updateZoomLabel();
  }

  function updateZoomLabel() {
    const { zoom, zoomIn, zoomOut } = ensureEls();
    if (zoom) zoom.textContent = state.overview ? '总览模式' : state.scale + ' 格/像素';
    if (zoomIn) zoomIn.disabled = state.overview || state.scale <= 0.0625;
    if (zoomOut) zoomOut.disabled = state.overview || state.scale >= 16;
  }

  function resize() {
    const { canvas, stage, playerCanvas, liveCanvas } = ensureEls();
    if (!canvas || !stage || state.overview) return;
    const width = Math.max(320, Math.round(stage.clientWidth));
    const height = Math.max(380, Math.min(820, Math.round(window.innerHeight * 0.64)));
    const dpr = window.devicePixelRatio || 1;
    if (width === state.cssWidth && height === state.cssHeight && dpr === state.dpr) return;
    state.cssWidth = width;
    state.cssHeight = height;
    state.dpr = dpr;
    canvas.style.width = width + 'px';
    canvas.style.height = height + 'px';
    canvas.width = Math.round(width * dpr);
    canvas.height = Math.round(height * dpr);
    const ctx = canvas.getContext('2d');
    ctx.imageSmoothingEnabled = false;
    els.ctx = ctx;
    if (playerCanvas) {
      playerCanvas.style.width = width + 'px';
      playerCanvas.style.height = height + 'px';
      playerCanvas.width = Math.round(width * dpr);
      playerCanvas.height = Math.round(height * dpr);
      const playerCtx = playerCanvas.getContext('2d');
      playerCtx.imageSmoothingEnabled = false;
      els.playerCtx = playerCtx;
    }
    if (liveCanvas) {
      liveCanvas.style.width = width + 'px';
      liveCanvas.style.height = height + 'px';
      liveCanvas.width = Math.round(width * dpr);
      liveCanvas.height = Math.round(height * dpr);
      const liveCtx = liveCanvas.getContext('2d');
      liveCtx.imageSmoothingEnabled = false;
      els.liveCtx = liveCtx;
    }
    draw();
  }

  function worldToScreen(x, z) {
    return {
      x: (x - state.centerX) / state.scale + state.cssWidth / 2,
      y: (z - state.centerZ) / state.scale + state.cssHeight / 2,
    };
  }

  function screenToWorld(x, y) {
    return {
      x: state.centerX + (x - state.cssWidth / 2) * state.scale,
      z: state.centerZ + (y - state.cssHeight / 2) * state.scale,
    };
  }

  function tileScale() {
    return Math.min(8, Math.max(1, state.scale));
  }

  function tileKey(rx, rz) {
    return state.stackId + '|' + state.dimId + '|' + rx + '|' + rz + '|'
      + tileScale() + '|' + (state.shading ? 1 : 0);
  }

  function tileUrl(stackId, dimId, rx, rz, scale, shade) {
    return MAP_API_BASE + '/tile?stack=' + encodeURIComponent(stackId)
      + '&dim=' + encodeURIComponent(dimId)
      + '&rx=' + rx + '&rz=' + rz + '&scale=' + scale
      + '&shade=' + (shade ? 1 : 0);
  }

  // ---- 世界栈卡片图标：自定义上传 > 存档 icon.png > 出生点地图缩略图 ----

  function stackMapRegion(stack) {
    const rawSpawn = stack && (stack.spawn || (Number.isFinite(Number(stack.spawnX))
      ? { x: stack.spawnX, y: stack.spawnY, z: stack.spawnZ } : null));
    const spawn = rawSpawn;
    let rx = spawn && Number.isFinite(Number(spawn.x))
      ? Math.floor(Number(spawn.x) / 512) : null;
    let rz = spawn && Number.isFinite(Number(spawn.z))
      ? Math.floor(Number(spawn.z) / 512) : null;
    const dimensions = Array.isArray(stack && stack.dimensions) ? stack.dimensions : [];
    const overworld = dimensions.find((dim) => dim && dim.dim === 'overworld') || dimensions[0];
    const regions = overworld && Array.isArray(overworld.regions)
      ? overworld.regions.filter((item) => Array.isArray(item) && item.length >= 2) : [];
    const contains = (x, z) => regions.some((item) => item[0] === x && item[1] === z);
    if (rx != null && rz != null && (regions.length === 0 || contains(rx, rz))) {
      return { rx, rz };
    }
    if (regions.length === 0) return null;
    if (rx == null || rz == null) {
      return { rx: regions[0][0], rz: regions[0][1] };
    }
    // 出生点所在 region 没有生成时，选离出生点最近的一个，避免卡片长期空白。
    let best = regions[0];
    let bestDistance = Infinity;
    for (const item of regions) {
      const dx = (item[0] + 0.5) * 512 - Number(spawn.x);
      const dz = (item[1] + 0.5) * 512 - Number(spawn.z);
      const distance = dx * dx + dz * dz;
      if (distance < bestDistance) {
        bestDistance = distance;
        best = item;
      }
    }
    return { rx: best[0], rz: best[1] };
  }

  function stackMapTileUrl(stack) {
    const region = stackMapRegion(stack);
    if (!region) return '';
    return tileUrl(stack.id, 'overworld', region.rx, region.rz, 4, true);
  }

  function stackIconLabel(source) {
    return ({
      upload: '自定义图标',
      save: '存档图标',
      map: '出生点地图',
      placeholder: '暂无图标',
    })[source] || '暂无图标';
  }

  function buildStackIcon(stack, options) {
    const opts = options || {};
    const visual = document.createElement('div');
    visual.className = 'world-stack-card-visual';
    const frame = document.createElement('div');
    frame.className = 'world-stack-icon-frame';
    const image = document.createElement('img');
    image.className = 'world-stack-icon-image';
    image.alt = (stack.label || stack.id || '世界栈') + ' 图标';
    image.loading = 'lazy';
    image.decoding = 'async';
    image.referrerPolicy = 'no-referrer';
    image.hidden = true;
    const placeholder = document.createElement('div');
    placeholder.className = 'world-stack-icon-placeholder';
    placeholder.textContent = '暂无图标';
    const badge = document.createElement('span');
    badge.className = 'world-stack-icon-badge';
    badge.textContent = stackIconLabel('placeholder');
    badge.dataset.source = 'placeholder';

    const candidates = [];
    if (stack.icon && stack.icon.url) {
      candidates.push({ url: stack.icon.url, source: stack.icon.source || 'upload' });
    }
    const mapUrl = stackMapTileUrl(stack);
    if (mapUrl) candidates.push({ url: mapUrl, source: 'map' });
    let next = 0;
    const showNext = () => {
      if (next >= candidates.length) {
        image.hidden = true;
        image.removeAttribute('src');
        placeholder.hidden = false;
        badge.textContent = stackIconLabel('placeholder');
        badge.dataset.source = 'placeholder';
        return;
      }
      const candidate = candidates[next++];
      image.dataset.source = candidate.source;
      badge.textContent = stackIconLabel(candidate.source);
      badge.dataset.source = candidate.source;
      image.hidden = false;
      placeholder.hidden = true;
      image.src = candidate.url;
    };
    image.addEventListener('load', () => {
      image.hidden = false;
      placeholder.hidden = true;
    });
    image.addEventListener('error', showNext);
    if (candidates.length) showNext();
    else {
      placeholder.hidden = false;
      image.hidden = true;
    }
    frame.append(image, placeholder, badge);

    if (opts.admin) {
      const tools = document.createElement('div');
      tools.className = 'world-stack-icon-tools';
      const upload = document.createElement('button');
      upload.type = 'button';
      upload.className = 'ghost';
      upload.textContent = '上传图片';
      upload.addEventListener('click', (event) => {
        event.stopPropagation();
        opts.onUpload?.();
      });
      tools.append(upload);
      if (stack.icon && stack.icon.custom) {
        const reset = document.createElement('button');
        reset.type = 'button';
        reset.className = 'ghost danger';
        reset.textContent = '恢复默认';
        reset.addEventListener('click', (event) => {
          event.stopPropagation();
          opts.onDelete?.();
        });
        tools.append(reset);
      }
      visual.append(frame, tools);
    } else {
      visual.append(frame);
    }
    return visual;
  }

  function blobToImage(blob) {
    if ('createImageBitmap' in window) {
      return createImageBitmap(blob);
    }
    return new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => {
        URL.revokeObjectURL(image.src);
        resolve(image);
      };
      image.onerror = reject;
      image.src = URL.createObjectURL(blob);
    });
  }

  function fetchTileImage(stackId, dimId, rx, rz, scale, shade) {
    return fetch(tileUrl(stackId, dimId, rx, rz, scale, shade), {
      headers: window.SamsaraUI.authHeaders ? window.SamsaraUI.authHeaders() : {},
    }).then(async (response) => {
      if (!response.ok) throw new Error('HTTP ' + response.status);
      return blobToImage(await response.blob());
    });
  }

  function ensureTile(rx, rz) {
    const key = tileKey(rx, rz);
    if (state.tiles.has(key) || state.pending.has(key)) return;
    state.pending.add(key);
    fetchTileImage(state.stackId, state.dimId, rx, rz, tileScale(), state.shading)
      .then((image) => {
        state.tiles.set(key, image);
        scheduleDraw();
      })
      .catch(() => {
        state.tiles.set(key, null);
      })
      .finally(() => state.pending.delete(key));
  }

  function scheduleDraw() {
    if (state.raf) return;
    state.raf = requestAnimationFrame(() => {
      state.raf = 0;
      draw();
    });
  }

  function chunkGridStep(chunkPixels) {
    if (chunkPixels >= 6) return 1;
    if (chunkPixels >= 2) return 4;
    return 16;
  }

  function updateChunkLegend(chunkPixels) {
    const el = ensureEls().chunkLegend;
    if (!el) return;
    if (!state.showChunks) {
      el.textContent = '区块网格 关';
      return;
    }
    const step = chunkGridStep(chunkPixels);
    el.textContent = step > 1
      ? '区块网格 开 · 抽稀 ' + step + '×' + step
      : '区块网格 开';
  }

  /** 只读叠加：按当前缩放画 16 格区块边界；缩小到看不清时按 4/16 区块抽稀。 */
  function drawChunkGrid(ctx, width, height) {
    if (!state.showChunks) return;
    const chunkPixels = 16 / state.scale;
    const step = chunkGridStep(chunkPixels);
    const stepBlocks = 16 * step;
    const left = screenToWorld(0, 0);
    const right = screenToWorld(width, height);
    const startX = Math.floor(left.x / stepBlocks) * stepBlocks;
    const startZ = Math.floor(left.z / stepBlocks) * stepBlocks;
    ctx.save();
    ctx.beginPath();
    ctx.rect(0, 0, width, height);
    ctx.clip();
    ctx.lineWidth = 1;
    ctx.strokeStyle = step === 1
      ? 'rgba(148, 163, 184, .22)' : 'rgba(148, 163, 184, .38)';
    ctx.beginPath();
    for (let wx = startX; wx <= right.x; wx += stepBlocks) {
      const screen = worldToScreen(wx, 0);
      const x = Math.round(screen.x) + .5;
      ctx.moveTo(x, 0);
      ctx.lineTo(x, height);
    }
    for (let wz = startZ; wz <= right.z; wz += stepBlocks) {
      const screen = worldToScreen(0, wz);
      const y = Math.round(screen.y) + .5;
      ctx.moveTo(0, y);
      ctx.lineTo(width, y);
    }
    ctx.stroke();
    // region 边界（512 格）加重，方便定位大范围。
    if (stepBlocks < 512) {
      const regionStartX = Math.floor(left.x / 512) * 512;
      const regionStartZ = Math.floor(left.z / 512) * 512;
      ctx.strokeStyle = 'rgba(94, 234, 212, .35)';
      ctx.beginPath();
      for (let wx = regionStartX; wx <= right.x; wx += 512) {
        const x = Math.round(worldToScreen(wx, 0).x) + .5;
        ctx.moveTo(x, 0);
        ctx.lineTo(x, height);
      }
      for (let wz = regionStartZ; wz <= right.z; wz += 512) {
        const y = Math.round(worldToScreen(0, wz).y) + .5;
        ctx.moveTo(0, y);
        ctx.lineTo(width, y);
      }
      ctx.stroke();
    }
    ctx.restore();
    updateChunkLegend(chunkPixels);
  }

  function draw() {
    const { canvas, ctx } = ensureEls();
    if (!canvas || !ctx) return;
    if (!state.cssWidth || !state.cssHeight) {
      resize();
      return;
    }
    const dim = currentDim();
    const width = state.cssWidth;
    const height = state.cssHeight;
    ctx.setTransform(state.dpr, 0, 0, state.dpr, 0, 0);
    ctx.clearRect(0, 0, width, height);
    ctx.fillStyle = '#0a1220';
    ctx.fillRect(0, 0, width, height);

    if (!dim) {
      ctx.fillStyle = 'rgba(226,232,240,.6)';
      ctx.font = '13px system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('没有找到已加载的地图', width / 2, height / 2);
      drawPlayerLayer(performance.now());
      return;
    }

    const tilePixels = 512 / state.scale;
    for (const [rx, rz] of dim.regions) {
      const topLeft = worldToScreen(rx * 512, rz * 512);
      if (topLeft.x > width || topLeft.y > height
          || topLeft.x + tilePixels < 0 || topLeft.y + tilePixels < 0) {
        continue;
      }
      const key = tileKey(rx, rz);
      const image = state.tiles.get(key);
      if (image) {
        ctx.drawImage(image, topLeft.x, topLeft.y, tilePixels, tilePixels);
      } else {
        ctx.fillStyle = '#121c2c';
        ctx.fillRect(topLeft.x, topLeft.y, tilePixels, tilePixels);
        ensureTile(rx, rz);
      }
      ctx.strokeStyle = 'rgba(148,163,184,.18)';
      ctx.lineWidth = 1;
      ctx.strokeRect(topLeft.x + .5, topLeft.y + .5, tilePixels - 1, tilePixels - 1);
    }

    drawChunkGrid(ctx, width, height);

    const showClaims = !ensureEls().claims || ensureEls().claims.checked;
    if (showClaims) {
      const protectedSet = chunkSet(dim);
      ctx.fillStyle = 'rgba(45, 212, 191, .34)';
      const chunkPixels = Math.max(1, 16 / state.scale);
      for (const [cx, cz] of dim.protectedChunks) {
        const point = worldToScreen(cx * 16, cz * 16);
        if (point.x > width || point.y > height
            || point.x + chunkPixels < 0 || point.y + chunkPixels < 0) {
          continue;
        }
        ctx.fillRect(point.x, point.y, Math.max(1, chunkPixels - .2), Math.max(1, chunkPixels - .2));
      }
      ctx.strokeStyle = 'rgba(94, 234, 212, .95)';
      ctx.lineWidth = Math.max(1, Math.min(2, chunkPixels * .12));
      for (const [cx, cz] of dim.protectedChunks) {
        const point = worldToScreen(cx * 16, cz * 16);
        if (point.x > width || point.y > height
            || point.x + chunkPixels < 0 || point.y + chunkPixels < 0) {
          continue;
        }
        const right = point.x + chunkPixels;
        const bottom = point.y + chunkPixels;
        ctx.beginPath();
        if (!protectedSet.has((cx + 1) + ',' + cz)) {
          ctx.moveTo(right, point.y); ctx.lineTo(right, bottom);
        }
        if (!protectedSet.has((cx - 1) + ',' + cz)) {
          ctx.moveTo(point.x, point.y); ctx.lineTo(point.x, bottom);
        }
        if (!protectedSet.has(cx + ',' + (cz + 1))) {
          ctx.moveTo(point.x, bottom); ctx.lineTo(right, bottom);
        }
        if (!protectedSet.has(cx + ',' + (cz - 1))) {
          ctx.moveTo(point.x, point.y); ctx.lineTo(right, point.y);
        }
        ctx.stroke();
      }
    }

    drawSelectedRange(ctx);
    drawChunkSelection(ctx);
    drawBanners(ctx, dim, width, height);
    if (!ensureEls().spawns || ensureEls().spawns.checked) {
      drawSpawns(ctx, width, height);
    }
    drawSeparationCircles(ctx, width, height);
    drawScale(ctx, width, height);
    ctx.fillStyle = 'rgba(226,232,240,.65)';
    ctx.font = '11px system-ui, sans-serif';
    ctx.textAlign = 'left';
    ctx.fillText('北 ↑', 18, 20);

    if (state.selected) {
      const point = worldToScreen(state.selected.x, state.selected.z);
      ctx.strokeStyle = 'rgba(251, 191, 36, .95)';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(point.x, point.y, 10, 0, Math.PI * 2);
      ctx.stroke();
    }

    // 地形/圈地重绘后同步玩家图层；移动中的玩家继续按帧插值。
    const now = performance.now();
    drawPlayerLayer(now);
    drawLiveLayer();
    if (playerMotionActive(now) && playerLayerActive()) schedulePlayerDraw();
  }

  function drawSeparationCircles(ctx, width, height) {
    if (!state.showSeparation) return;
    const stack = currentStack();
    const history = stack && stack.spawnHistory;
    const separation = history && history.separation;
    const radius = separation ? Number(separation.effective) : 0;
    if (!history || !Array.isArray(history.cycles) || !radius) return;
    ctx.save();
    ctx.setLineDash([7, 7]);
    ctx.lineWidth = 1.2;
    for (const cycle of history.cycles) {
      const center = worldToScreen(Number(cycle.x) || 0, Number(cycle.z) || 0);
      const pixels = radius / state.scale;
      if (center.x + pixels < 0 || center.y + pixels < 0
          || center.x - pixels > width || center.y - pixels > height) {
        continue;
      }
      ctx.strokeStyle = cycle.current ? 'rgba(251, 191, 36, .42)' : 'rgba(251, 191, 36, .23)';
      ctx.beginPath();
      ctx.arc(center.x, center.y, pixels, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.restore();
  }

  function liveColor(item) {
    if (item.kind === 'player') return 'rgba(96, 165, 250, .42)';
    const category = String(item.category || '');
    if (category === 'monster') return 'rgba(248, 113, 113, .36)';
    if (category === 'creature' || category === 'axolotls') return 'rgba(94, 234, 212, .32)';
    return 'rgba(148, 163, 184, .32)';
  }

  function liveKey(item) {
    return String(item.kind || '') + '|' + String(item.name || '') + '|'
      + String(item.type || '') + '|' + String(item.x) + ',' + String(item.z);
  }

  function liveEmphasis(item) {
    const key = liveKey(item);
    if (state.selectedLive && liveKey(state.selectedLive) === key) return 1;
    if (state.hoverLive && liveKey(state.hoverLive) === key) return 0.55;
    return 0;
  }

  function currentLiveItems() {
    const live = state.live;
    const stack = currentStack();
    const dim = currentDim();
    if (!live || !stack || !dim) return [];
    const match = (item) => item.stackId === stack.id && item.dimension === dim.dim;
    return [
      ...(Array.isArray(live.players) ? live.players.filter(match) : []),
      ...(Array.isArray(live.entities) ? live.entities.filter(match) : []),
    ];
  }

  function drawTrail(ctx, item, emphasis) {
    const trail = Array.isArray(item.trail) ? item.trail : [];
    if (trail.length < 2) return;
    ctx.beginPath();
    for (let i = 0; i < trail.length; i++) {
      const point = worldToScreen(Number(trail[i].x) || 0, Number(trail[i].z) || 0);
      if (i === 0) ctx.moveTo(point.x, point.y);
      else ctx.lineTo(point.x, point.y);
    }
    const tail = worldToScreen(Number(item.x) || 0, Number(item.z) || 0);
    ctx.lineTo(tail.x, tail.y);
    if (emphasis >= 1) {
      ctx.strokeStyle = 'rgba(251, 191, 36, .9)';
      ctx.lineWidth = item.kind === 'player' ? 2.6 : 2.1;
    } else if (emphasis > 0) {
      ctx.strokeStyle = 'rgba(226, 232, 240, .55)';
      ctx.lineWidth = item.kind === 'player' ? 2.0 : 1.6;
    } else {
      ctx.strokeStyle = liveColor(item);
      ctx.lineWidth = item.kind === 'player' ? 1.5 : 1.1;
    }
    ctx.stroke();
  }

  function drawLiveLayer() {
    const { liveCanvas, liveCtx } = ensureEls();
    if (!liveCanvas || !liveCtx) return;
    const width = state.cssWidth;
    const height = state.cssHeight;
    if (!width || !height || state.overview) return;
    liveCtx.setTransform(state.dpr, 0, 0, state.dpr, 0, 0);
    liveCtx.clearRect(0, 0, width, height);
    const live = state.live;
    const stack = currentStack();
    const dim = currentDim();
    if (!live || !stack || !dim) return;
    const players = (live.players || []).filter((item) => item.stackId === stack.id
      && item.dimension === dim.dim);
    const entities = (live.entities || []).filter((item) => item.stackId === stack.id
      && item.dimension === dim.dim);
    if (state.showTrails) {
      for (const item of players) drawTrail(liveCtx, item, liveEmphasis(item));
      for (const item of entities) {
        if (Number(item.rarity || 0) <= 1) drawTrail(liveCtx, item, liveEmphasis(item));
      }
    }
    if (state.showEntities) {
      const tierInterval = [0, 1200, 2500, 4000];
      const stamp = Date.now();
      const seen = new Set();
      entities.forEach((item, index) => {
        const key = String(item.kind) + '|' + String(item.type) + '|'
          + String(item.name) + '|' + index;
        seen.add(key);
        const tier = Math.max(0, Math.min(3, Number(item.rarity || 0)));
        const interval = tierInterval[tier] || 0;
        let hold = state.entityHold.get(key);
        if (!hold || interval === 0 || stamp - hold.at >= interval) {
          hold = { x: Number(item.x) || 0, z: Number(item.z) || 0, at: stamp };
          state.entityHold.set(key, hold);
        }
        const point = worldToScreen(hold.x, hold.z);
        if (point.x < -8 || point.y < -8 || point.x > width + 8 || point.y > height + 8) {
          return;
        }
        const emphasis = liveEmphasis(item);
        const color = liveColor(item);
        const radius = tier === 0 ? 3.4 : item.category === 'monster' ? 3 : 2.4;
        liveCtx.beginPath();
        liveCtx.arc(point.x, point.y, radius, 0, Math.PI * 2);
        liveCtx.fillStyle = emphasis >= 1 ? 'rgba(251, 191, 36, .95)'
          : emphasis > 0 ? 'rgba(226, 232, 240, .8)'
            : tier === 0 ? 'rgba(244, 114, 182, .95)' : color.replace(/\.\d+\)$/, '.85)');
        liveCtx.fill();
        liveCtx.strokeStyle = 'rgba(8, 12, 17, .65)';
        liveCtx.lineWidth = .8;
        liveCtx.stroke();
        if (emphasis > 0 || tier === 0) {
          liveCtx.beginPath();
          liveCtx.arc(point.x, point.y, emphasis >= 1 ? 7 : 5, 0, Math.PI * 2);
          liveCtx.strokeStyle = emphasis >= 1 ? 'rgba(251, 191, 36, .95)'
            : tier === 0 ? 'rgba(244, 114, 182, .55)' : 'rgba(226, 232, 240, .5)';
          liveCtx.lineWidth = emphasis >= 1 ? 1.8 : 1.1;
          liveCtx.stroke();
        }
        if (tier === 0 && state.showRareNames) {
          const label = String(item.customName || item.name || item.type || '')
            .replace('minecraft:', '');
          if (label) {
            liveCtx.save();
            liveCtx.font = 'bold 10px system-ui, sans-serif';
            liveCtx.textAlign = 'center';
            liveCtx.textBaseline = 'bottom';
            liveCtx.lineWidth = 3;
            liveCtx.strokeStyle = 'rgba(8, 12, 17, .9)';
            liveCtx.strokeText(label, point.x, point.y - 7);
            liveCtx.fillStyle = '#fbcfe8';
            liveCtx.fillText(label, point.x, point.y - 7);
            liveCtx.restore();
          }
        }
      });
      for (const key of state.entityHold.keys()) {
        if (!seen.has(key)) state.entityHold.delete(key);
      }
    }
  }

  /** 当前详细地图上离屏幕坐标最近的实时玩家/实体（12px 内）。 */
  function liveAt(x, y) {
    let best = null;
    let bestDistance = 12;
    for (const item of currentLiveItems()) {
      const point = worldToScreen(Number(item.x) || 0, Number(item.z) || 0);
      const distance = Math.hypot(point.x - x, point.y - y);
      if (distance <= bestDistance) {
        bestDistance = distance;
        best = item;
      }
    }
    return best;
  }

  function selectLive(item) {
    state.selectedLive = item ? { ...item } : null;
    drawLiveLayer();
    schedulePlayerDraw();
    renderActivity();
  }

  function operationLabel(operation) {
    const action = operation.action === 'break' ? '破坏'
      : operation.action === 'place' ? '放置' : '改变';
    const block = operation.after && operation.action === 'break'
      ? operation.before : operation.after;
    const shortBlock = String(block || 'block').replace('minecraft:', '');
    return action + ' ' + shortBlock + ' · ' + operation.x + ', ' + operation.y + ', '
      + operation.z;
  }

  function renderActivity() {
    const { activity, liveStatus } = ensureEls();
    const live = state.live || {};
    const operations = Array.isArray(live.operations) ? live.operations : [];
    const online = Array.isArray(live.players) ? live.players.length : 0;
    const entityCount = Array.isArray(live.entities) ? live.entities.length : 0;
    if (liveStatus) {
      liveStatus.textContent = live.generatedAt
        ? '在线 ' + online + ' 人 · 实体 ' + entityCount + ' · 操作 ' + operations.length
          + ' · ' + new Date(live.generatedAt).toLocaleTimeString()
        : '读取中…';
    }
    if (!activity) return;
    activity.replaceChildren();
    if (state.selectedLive) {
      const item = state.selectedLive;
      const card = document.createElement('div');
      card.className = 'map-live-detail';
      const title = document.createElement('div');
      title.className = 'map-live-detail-title';
      const name = document.createElement('strong');
      name.textContent = item.name || (item.kind === 'player' ? '玩家' : '实体');
      const meta = document.createElement('span');
      meta.className = 'muted small';
      meta.textContent = (item.kind === 'player' ? '玩家' : (item.type || '实体'))
        + ' · ' + item.stackId + '/' + item.dimension
        + ' · ' + item.x + ', ' + item.y + ', ' + item.z
        + (item.health == null ? '' : ' · 生命 ' + item.health);
      title.append(name, meta);
      const actions = document.createElement('div');
      actions.className = 'map-live-detail-actions';
      const locate = document.createElement('button');
      locate.type = 'button';
      locate.className = 'ghost';
      locate.textContent = '定位';
      locate.addEventListener('click', () => {
        state.centerX = Number(item.x) || 0;
        state.centerZ = Number(item.z) || 0;
        if (state.scale > 2) state.scale = 2;
        draw();
        drawLiveLayer();
      });
      actions.append(locate);
      if (item.kind === 'player') {
        const copy = document.createElement('button');
        copy.type = 'button';
        copy.className = 'ghost';
        copy.textContent = '复制 TP 命令';
        copy.addEventListener('click', () => copyPlayerCommand(item));
        actions.append(copy);
      }
      const clear = document.createElement('button');
      clear.type = 'button';
      clear.className = 'ghost';
      clear.textContent = '取消选中';
      clear.addEventListener('click', () => selectLive(null));
      actions.append(clear);
      card.append(title, actions);
      activity.append(card);
    }
    const chats = Array.isArray(live.chat) ? live.chat : [];
    const systems = Array.isArray(live.system) ? live.system : [];
    if (systems.length > 0) {
      const sysHead = document.createElement('div');
      sysHead.className = 'map-live-section';
      sysHead.textContent = '系统提示（' + systems.length + '）';
      activity.append(sysHead);
      for (const tip of systems.slice(-10).reverse()) {
        const row = document.createElement('div');
        row.className = 'map-activity-row';
        const time = document.createElement('span');
        time.className = 'map-activity-time';
        time.textContent = formatTime(tip.timeMs);
        const text = document.createElement('span');
        text.className = 'map-activity-text';
        text.textContent = tip.text || '';
        row.append(time, text);
        activity.append(row);
      }
    }
    if (chats.length > 0) {
      const chatHead = document.createElement('div');
      chatHead.className = 'map-live-section';
      chatHead.textContent = '最近发言（' + chats.length + '）';
      activity.append(chatHead);
      for (const chat of chats.slice(-12).reverse()) {
        const row = document.createElement('div');
        row.className = 'map-activity-row';
        const time = document.createElement('span');
        time.className = 'map-activity-time';
        time.textContent = formatTime(chat.timeMs);
        const actor = document.createElement('span');
        actor.className = 'map-activity-actor';
        actor.textContent = chat.name || '玩家';
        const text = document.createElement('span');
        text.className = 'map-activity-text';
        text.textContent = chat.text || '';
        row.append(time, actor, text);
        activity.append(row);
      }
      const opHead = document.createElement('div');
      opHead.className = 'map-live-section';
      opHead.textContent = '最近方块操作（' + operations.length + '）';
      activity.append(opHead);
    }
    if (operations.length === 0) {
      const empty = document.createElement('span');
      empty.className = 'map-activity-empty';
      empty.textContent = '还没有捕获到方块操作；玩家破坏/放置方块后会出现在这里。';
      activity.append(empty);
      return;
    }
    for (const operation of operations.slice(0, 40)) {
      const row = document.createElement('div');
      row.className = 'map-activity-row';
      row.title = '点击定位到该操作';
      const time = document.createElement('span');
      time.className = 'map-activity-time';
      time.textContent = formatTime(operation.timeMs);
      const actor = document.createElement('span');
      actor.className = 'map-activity-actor';
      actor.textContent = operation.actor || '环境';
      const text = document.createElement('span');
      text.className = 'map-activity-text';
      text.textContent = operationLabel(operation) + ' · ' + operation.stackId
        + '/' + operation.dimension;
      row.append(time, actor, text);
      row.addEventListener('click', () => {
        openDetailed(operation.stackId, operation.dimension);
        state.centerX = Number(operation.x) || 0;
        state.centerZ = Number(operation.z) || 0;
        if (state.scale > 2) state.scale = 2;
        draw();
      });
      activity.append(row);
    }
  }

  async function loadLive() {
    if (!state.model || state.liveFetching) return;
    if (window.SamsaraUI && window.SamsaraUI.getToken && !window.SamsaraUI.getToken()) return;
    state.liveFetching = true;
    try {
      const data = await window.SamsaraUI.api(MAP_API_BASE + '/live');
      state.live = data;
      renderActivity();
      drawLiveLayer();
      renderStats();
    } catch (error) {
      // 实时层失败不阻塞地图；保留上一帧。
    } finally {
      state.liveFetching = false;
    }
  }

  function drawSpawns(ctx, width, height) {
    const points = spawnPoints();
    for (const point of points) {
      const screen = worldToScreen(point.x, point.z);
      if (screen.x < -20 || screen.y < -20 || screen.x > width + 20 || screen.y > height + 20) {
        continue;
      }
      const selected = state.selectedSpawn
        && state.selectedSpawn.cycle.index === point.cycle.index;
      const size = selected ? 11 : (state.scale <= 1 ? 9 : 7);
      ctx.save();
      ctx.translate(screen.x, screen.y);
      ctx.rotate(Math.PI / 4);
      ctx.fillStyle = '#f472b6';
      ctx.strokeStyle = selected ? '#fef3c7' : '#fdf2f8';
      ctx.lineWidth = selected ? 2.5 : 1.5;
      ctx.fillRect(-size / 2, -size / 2, size, size);
      ctx.strokeRect(-size / 2, -size / 2, size, size);
      ctx.restore();
      if (point.cycle.current) {
        ctx.strokeStyle = 'rgba(244, 114, 182, .75)';
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        ctx.arc(screen.x, screen.y, size + 5, 0, Math.PI * 2);
        ctx.stroke();
      }
    }
  }

  function distanceForColor(color) {
    const claimRange = state.model && state.model.claimRange;
    if (!claimRange) return 1;
    const index = Math.max(0, Math.min(15, Number(color) || 0));
    const base = claimRange.mode === 'fixed'
      ? claimRange.fixedDistance
      : ((claimRange.colorDistances && claimRange.colorDistances[index]) ?? index + 1);
    return Math.max(1, Math.min(claimRange.maxDistance || 16, Math.round(base)));
  }

  /** 单面旗帜只显示图案射线，不做任何填充；填充面属于多旗全局结果。 */
  function bannerCells(banner) {
    const claimRange = state.model && state.model.claimRange;
    const cells = new Set();
    if (!claimRange || !banner.patterns) return cells;
    const offset = FACING_OFFSET[banner.facing] || 0;
    const baseX = Math.floor(banner.x / 16);
    const baseZ = Math.floor(banner.z / 16);
    cells.add(baseX + ',' + baseZ);
    for (const pattern of banner.patterns) {
      const names = claimRange.patternDirections && claimRange.patternDirections[pattern.id];
      if (!Array.isArray(names) || names.length === 0) continue;
      const distance = distanceForColor(pattern.color);
      const dirs = names.map((name) => DIRECTION_NAMES.indexOf(name))
        .filter((index) => index >= 0)
        .map((index) => (index + offset) % 8);
      for (const dir of dirs) {
        const direction = DIRECTIONS[dir];
        for (let step = 1; step <= distance; step += 1) {
          cells.add((baseX + direction.dx * step) + ',' + (baseZ + direction.dz * step));
        }
      }
    }
    return cells;
  }

  function boundaryCells(cells) {
    const out = [];
    for (const key of cells) {
      const [x, z] = key.split(',').map(Number);
      if (!cells.has((x + 1) + ',' + z) || !cells.has((x - 1) + ',' + z)
          || !cells.has(x + ',' + (z + 1)) || !cells.has(x + ',' + (z - 1))) {
        out.push([x, z]);
      }
    }
    return out;
  }

  function drawSelectedRange(ctx) {
    if (!state.selected) return;
    const cells = bannerCells(state.selected);
    if (cells.size === 0) return;
    const chunkPixels = Math.max(1, 16 / state.scale);
    ctx.fillStyle = 'rgba(251, 191, 36, .13)';
    for (const key of cells) {
      const [cx, cz] = key.split(',').map(Number);
      const point = worldToScreen(cx * 16, cz * 16);
      ctx.fillRect(point.x, point.y, Math.max(1, chunkPixels - .2), Math.max(1, chunkPixels - .2));
    }
    ctx.fillStyle = 'rgba(251, 191, 36, .95)';
    for (const [cx, cz] of boundaryCells(cells)) {
      const point = worldToScreen(cx * 16, cz * 16);
      ctx.fillRect(point.x + chunkPixels * .35, point.y + chunkPixels * .35,
        Math.max(2, chunkPixels * .3), Math.max(2, chunkPixels * .3));
    }
  }

  /** 区块操作模式：高亮选中的单区块（不属于保存型回溯或回看本身）。 */
  function drawChunkSelection(ctx) {
    if (!state.selectedChunk) return;
    const { cx, cz } = state.selectedChunk;
    const point = worldToScreen(cx * 16, cz * 16);
    const size = Math.max(2, 16 / state.scale);
    ctx.save();
    ctx.strokeStyle = 'rgba(251, 191, 36, .95)';
    ctx.lineWidth = 2;
    ctx.setLineDash([6, 4]);
    ctx.strokeRect(point.x + .5, point.y + .5, size, size);
    ctx.restore();
    ctx.fillStyle = 'rgba(251, 191, 36, .1)';
    ctx.fillRect(point.x, point.y, size, size);
  }

  function drawBanners(ctx, dim, width, height) {
    const banners = Array.isArray(dim.banners) ? dim.banners : [];
    const icon = window.SamsaraBanners;
    for (const banner of banners) {
      const point = worldToScreen(banner.x, banner.z);
      if (point.x < -20 || point.y < -20 || point.x > width + 20 || point.y > height + 20) {
        continue;
      }
      const pattern = banner.patterns && banner.patterns[0];
      const color = pattern ? pattern.color : 0;
      if (state.scale <= 1 && icon) {
        const layers = (banner.patterns || []).map((item) => ({
          id: item.id, color: item.color,
        }));
        const size = 26;
        ctx.save();
        ctx.globalAlpha = .98;
        icon.draw(ctx, layers, '#9d9d97',
          point.x - size / 2, point.y - size * .72, size, size);
        ctx.restore();
      } else {
        const size = state.selected && state.selected === banner ? 7 : 5;
        ctx.fillStyle = '#f8fafc';
        ctx.beginPath();
        ctx.arc(point.x, point.y, size + 1.5, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = icon
          ? (icon.dyeColors[Math.max(0, Math.min(15, color))] || '#2dd4bf')
          : '#2dd4bf';
        ctx.beginPath();
        ctx.arc(point.x, point.y, size, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }

  function drawPlayerMarker(ctx, player, position, width, height) {
    const point = worldToScreen(position.x, position.z);
    if (point.x < -30 || point.y < -30 || point.x > width + 30 || point.y > height + 30) {
      return;
    }
    const entry = playerAvatarEntry(player);
    ensureAvatarLoaded(entry);
    const avatarSize = 20;
    const avatarX = point.x - avatarSize / 2;
    const avatarY = point.y - 38;
    ctx.save();
    if (entry.remoteLoaded && entry.image) {
      try {
        ctx.drawImage(entry.image, 8, 8, 8, 8, avatarX, avatarY, avatarSize, avatarSize);
        try { ctx.drawImage(entry.image, 40, 8, 8, 8, avatarX, avatarY, avatarSize, avatarSize); } catch (e) { /* 旧版皮肤 */ }
      } catch (e) {
        ctx.drawImage(entry.image, avatarX, avatarY, avatarSize, avatarSize);
      }
      ctx.strokeStyle = '#eff6ff';
      ctx.lineWidth = 1.4;
      ctx.strokeRect(avatarX + .5, avatarY + .5, avatarSize - 1, avatarSize - 1);
    } else {
      ctx.beginPath();
      ctx.arc(point.x, point.y - 28, 9, 0, Math.PI * 2);
      ctx.fillStyle = '#60a5fa';
      ctx.fill();
      ctx.strokeStyle = '#eff6ff';
      ctx.lineWidth = 1.4;
      ctx.stroke();
      const initial = /^[A-Za-z0-9\u4e00-\u9fff]/.test(playerName(player))
        ? playerName(player).charAt(0).toUpperCase() : '?';
      ctx.fillStyle = '#0f172a';
      ctx.font = 'bold 9px system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(initial, point.x, point.y - 28);
    }
    // 底部尖端 + 小圆点始终对准玩家真实坐标。
    ctx.beginPath();
    ctx.moveTo(point.x - 4, avatarY + avatarSize);
    ctx.lineTo(point.x + 4, avatarY + avatarSize);
    ctx.lineTo(point.x, point.y - 10);
    ctx.closePath();
    ctx.fillStyle = 'rgba(96, 165, 250, .9)';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(point.x, point.y, 2.6, 0, Math.PI * 2);
    ctx.fillStyle = '#eff6ff';
    ctx.fill();

    const label = playerName(player);
    const textX = point.x + 12;
    const textY = avatarY + avatarSize / 2 + 4;
    ctx.font = 'bold 11px system-ui, sans-serif';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'alphabetic';
    ctx.lineWidth = 3;
    ctx.strokeStyle = 'rgba(8, 12, 17, .92)';
    ctx.strokeText(label, textX, textY);
    ctx.fillStyle = '#dbeafe';
    ctx.fillText(label, textX, textY);
    ctx.restore();
  }

  /** 玩家独立画布：每次只清空并重画在线玩家，不碰地形图层。 */
  function drawPlayerLayer(now) {
    const { playerCanvas, playerCtx } = ensureEls();
    if (!playerCanvas || !playerCtx) return;
    const width = state.cssWidth;
    const height = state.cssHeight;
    if (!width || !height) return;
    playerCtx.setTransform(state.dpr, 0, 0, state.dpr, 0, 0);
    playerCtx.clearRect(0, 0, width, height);
    if (!state.showPlayers || state.overview) return;
    const stack = currentStack();
    const dim = currentDim();
    if (!stack || !dim) return;
    for (const player of playersFor(stack.id, dim.dim)) {
      drawPlayerMarker(playerCtx, player, playerDisplayPosition(player, now), width, height);
      if (state.selectedLive && liveKey(state.selectedLive) === liveKey(player)) {
        const point = worldToScreen(Number(player.x) || 0, Number(player.z) || 0);
        playerCtx.beginPath();
        playerCtx.arc(point.x, point.y - 28, 15, 0, Math.PI * 2);
        playerCtx.strokeStyle = 'rgba(251, 191, 36, .95)';
        playerCtx.lineWidth = 2;
        playerCtx.stroke();
      }
    }
  }

  function playerLayerActive() {
    return state.showPlayers && !state.overview
      && (!window.SamsaraUI?.isActiveTab || window.SamsaraUI.isActiveTab('map'));
  }

  function playerFrame(now) {
    state.playerRaf = 0;
    drawPlayerLayer(now);
    if (playerMotionActive(now) && playerLayerActive()) {
      state.playerRaf = requestAnimationFrame(playerFrame);
    }
  }

  function schedulePlayerDraw() {
    if (state.playerRaf) return;
    if (!playerLayerActive()) {
      drawPlayerLayer(performance.now());
      return;
    }
    state.playerRaf = requestAnimationFrame(playerFrame);
  }

  function stopPlayerDraw() {
    if (state.playerRaf) cancelAnimationFrame(state.playerRaf);
    state.playerRaf = 0;
  }

  function playerAt(screenX, screenY) {
    const stack = currentStack();
    const dim = currentDim();
    if (!stack || !dim || !state.showPlayers) return null;
    let best = null;
    let bestDistance = 13 * 13;
    const now = performance.now();
    for (const player of playersFor(stack.id, dim.dim)) {
      const position = playerDisplayPosition(player, now);
      const point = worldToScreen(position.x, position.z);
      const dx = point.x - screenX;
      const dy = point.y - screenY;
      const distance = dx * dx + dy * dy;
      if (distance < bestDistance) {
        bestDistance = distance;
        best = player;
      }
    }
    return best;
  }

  function drawScale(ctx, width, height) {
    const options = [16, 32, 64, 128, 256, 512, 1024, 2048, 4096];
    const target = 130 * state.scale;
    const blocks = options.find((value) => value >= target) || 1024;
    const pixels = blocks / state.scale;
    const x = 18;
    const y = height - 22;
    ctx.strokeStyle = 'rgba(226,232,240,.8)';
    ctx.fillStyle = 'rgba(226,232,240,.8)';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(x, y - 5);
    ctx.lineTo(x, y);
    ctx.lineTo(x + pixels, y);
    ctx.lineTo(x + pixels, y - 5);
    ctx.stroke();
    ctx.font = '11px system-ui, sans-serif';
    ctx.textAlign = 'left';
    ctx.fillText(blocks + ' 格 · ' + state.scale + ' 格/像素'
      + ' · 区块网格 ' + (state.showChunks ? '开' : '关'), x, y - 9);
  }

  function bannerAt(screenX, screenY) {
    const dim = currentDim();
    if (!dim) return null;
    let best = null;
    let bestDistance = 14 * 14;
    for (const banner of dim.banners || []) {
      const point = worldToScreen(banner.x, banner.z);
      const dx = point.x - screenX;
      const dy = point.y - screenY;
      const distance = dx * dx + dy * dy;
      if (distance < bestDistance) {
        bestDistance = distance;
        best = banner;
      }
    }
    return best;
  }

  function spawnAt(screenX, screenY) {
    let best = null;
    let bestDistance = 15 * 15;
    for (const point of spawnPoints()) {
      const screen = worldToScreen(point.x, point.z);
      const dx = screen.x - screenX;
      const dy = screen.y - screenY;
      const distance = dx * dx + dy * dy;
      if (distance < bestDistance) {
        bestDistance = distance;
        best = point;
      }
    }
    return best;
  }

  function updateTooltip(event) {
    const { tooltip } = ensureEls();
    if (!tooltip) return;
    const rect = ensureEls().canvas.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;
    const player = playerAt(x, y);
    const spawn = ensureEls().spawns && ensureEls().spawns.checked ? spawnAt(x, y) : null;
    const banner = bannerAt(x, y);
    const dim = currentDim();
    if (player) {
      tooltip.textContent = [
        playerName(player) + ' · ' + stackLabelOf(player.stackId)
          + ' · ' + dimensionLabel(player.dimension),
        '坐标 ' + fmtCoord(player.x) + ', ' + fmtCoord(player.y) + ', ' + fmtCoord(player.z),
        '点击复制 TP 命令',
      ].join('\n');
      tooltip.hidden = false;
      tooltip.style.left = Math.min(rect.width - 240, x + 14) + 'px';
      tooltip.style.top = Math.min(rect.height - 70, y + 12) + 'px';
      return;
    }
    if (spawn) {
      const stack = currentStack();
      const target = spawn.target;
      tooltip.textContent = [
        (stack ? stack.label : '') + ' · 第 ' + (spawn.cycle.index + 1) + ' 轮',
        '坐标 ' + target.x + ', ' + target.z
          + (target.yEstimated ? '（Y 估算 ' + target.y + '）' : ' · Y ' + target.y),
        spawnSourceName(spawn.cycle.source) + ' · ' + formatTime(spawn.cycle.createdAt),
      ].join('\n');
      tooltip.hidden = false;
      tooltip.style.left = Math.min(rect.width - 240, x + 14) + 'px';
      tooltip.style.top = Math.min(rect.height - 70, y + 12) + 'px';
      return;
    }
    if (banner) {
      const pattern = banner.patterns && banner.patterns[0];
      const lines = [
        (banner.owner || '未知主人') + (pattern ? ' · ' + pattern.id : ''),
        '旗帜 (' + banner.x + ', ' + banner.y + ', ' + banner.z + ') · 朝' + facingName(banner.facing),
      ];
      tooltip.textContent = lines.join('\n');
      tooltip.hidden = false;
      tooltip.style.left = Math.min(rect.width - 220, x + 14) + 'px';
      tooltip.style.top = Math.min(rect.height - 60, y + 12) + 'px';
      return;
    }
    if (dim) {
      const world = screenToWorld(x, y);
      const cx = Math.floor(world.x / 16);
      const cz = Math.floor(world.z / 16);
      const protectedChunk = chunkSet(dim).has(cx + ',' + cz);
      tooltip.textContent = '区块 (' + cx + ', ' + cz + ')\n'
        + (protectedChunk ? '已圈地 / 受保护' : '未圈地') + ' · 方块 ' + Math.round(world.x) + ', '
        + Math.round(world.z);
      tooltip.hidden = false;
      tooltip.style.left = Math.min(rect.width - 220, x + 14) + 'px';
      tooltip.style.top = Math.min(rect.height - 60, y + 12) + 'px';
    }
  }

  function facingName(facing) {
    return ({ north: '北', east: '东', south: '南', west: '西' })[facing] || facing || '?';
  }

  function spawnSourceName(source) {
    return ({
      initial: '首次生成',
      'same-world-reset': '同世界重置',
      detected: '启动检测',
      migrated: '旧数据迁移',
      'new-world': '新世界入栈',
    })[source] || source || '未知来源';
  }

  function formatTime(value) {
    if (!value || value <= 0) return '时间未知';
    try {
      return new Date(value).toLocaleString();
    } catch (error) {
      return String(value);
    }
  }

  function teleportCommand(target) {
    return '/execute in ' + target.dimension + ' run tp <玩家> '
      + target.x + ' ' + target.y + ' ' + target.z;
  }

  async function copyText(text, button, normalLabel) {
    const label = normalLabel || '⧉';
    const done = () => {
      button.textContent = '✓';
      button.title = '已复制';
      window.setTimeout(() => {
        button.textContent = label;
        button.title = '复制传送命令';
      }, 1400);
    };
    try {
      await navigator.clipboard.writeText(text);
      done();
      return;
    } catch (error) {
      // 非安全上下文/浏览器拒绝时退回隐藏 textarea。
    }
    const area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.append(area);
    area.select();
    try {
      document.execCommand('copy');
      done();
    } catch (error) {
      button.textContent = '!';
      button.title = '复制失败，请手动选择';
    } finally {
      area.remove();
    }
  }

  function selectBanner(banner) {
    state.selected = banner;
    state.selectedSpawn = null;
    state.selectedChunk = null;
    state.chunkSummary = null;
    renderSelection();
    draw();
  }

  function selectSpawn(point) {
    state.selectedSpawn = point;
    state.selected = null;
    state.selectedChunk = null;
    state.chunkSummary = null;
    renderSelection();
    draw();
  }

  function appendRows(parent, rows) {
    const list = document.createElement('dl');
    list.className = 'map-selection-list';
    for (const [key, value] of rows) {
      const dt = document.createElement('dt');
      dt.textContent = key;
      const dd = document.createElement('dd');
      dd.textContent = value;
      list.append(dt, dd);
    }
    parent.append(list);
  }

  function renderChunkSelection(selection, title) {
    const stack = currentStack();
    const dim = currentDim();
    const { cx, cz } = state.selectedChunk;
    title.textContent = '区块 (' + cx + ', ' + cz + ')';
    appendRows(selection, [
      ['栈层', stack ? stack.label : '—'],
      ['维度', dim ? dim.name : '—'],
      ['方块范围', (cx * 16) + '~' + (cx * 16 + 15) + ', ' + (cz * 16) + '~' + (cz * 16 + 15)],
      ['说明', '回溯会写方块；回看只发客户端假方块，不写世界'],
    ]);

    const players = stack && dim ? playersFor(stack.id, dim.dim) : [];
    const playerRow = document.createElement('label');
    playerRow.className = 'map-chunk-player';
    const playerLabel = document.createElement('span');
    playerLabel.textContent = '回看观看者';
    const playerSelect = document.createElement('select');
    for (const player of players) {
      const option = document.createElement('option');
      option.value = player.name;
      option.textContent = player.name;
      playerSelect.append(option);
    }
    if (players.length === 0) {
      const option = document.createElement('option');
      option.value = '';
      option.textContent = '当前维度没有在线玩家';
      playerSelect.append(option);
      playerSelect.disabled = true;
    }
    playerRow.append(playerLabel, playerSelect);
    selection.append(playerRow);

    const actions = document.createElement('div');
    actions.className = 'map-chunk-actions';
    const preview = document.createElement('button');
    preview.type = 'button';
    preview.className = 'ghost';
    preview.textContent = '预览回溯';
    const restore = document.createElement('button');
    restore.type = 'button';
    restore.className = 'ghost';
    restore.textContent = '执行回溯（需确认）';
    const view = document.createElement('button');
    view.type = 'button';
    view.className = 'ghost';
    view.textContent = '只读回看';
    view.disabled = players.length === 0;
    const stop = document.createElement('button');
    stop.type = 'button';
    stop.className = 'ghost';
    stop.textContent = '停止回看';
    stop.disabled = players.length === 0;
    actions.append(preview, restore, view, stop);
    selection.append(actions);

    const status = document.createElement('div');
    status.className = 'map-chunk-status';
    status.textContent = '先预览，再执行；执行会写前备份并受圈地/倒流保护约束。';
    selection.append(status);
    const setStatus = (text, kind) => {
      status.textContent = text;
      status.className = 'map-chunk-status' + (kind ? ' ' + kind : '');
    };
    const body = (extra) => JSON.stringify(Object.assign({
      stack: stack ? stack.id : '',
      dim: dim ? dim.dim : '',
      cx,
      cz,
    }, extra || {}));
    const call = (path, options) => window.SamsaraUI.api(path, options);

    preview.addEventListener('click', async () => {
      setStatus('正在读取 diff 摘要…');
      try {
        const result = await call('/api/diff/chunk/restore', {
          method: 'POST',
          body: body({ preview: true }),
        });
        if (!result.ok) {
          setStatus('预览失败：' + (result.error || '未知错误'), 'error');
          return;
        }
        const changed = result.changedBlocks < 0 ? '未加载' : result.changedBlocks + ' 格';
        setStatus('diff ' + result.editCount + ' 条 · 候选 ' + result.candidateBlocks
          + ' · 将改动 ' + changed + ' · 目标 ' + formatTime(result.target)
          + (result.protected ? ' · ⚠ 圈地保护' : '')
          + (result.rewindRunning ? ' · ⚠ 倒流进行中' : ''));
      } catch (e) {
        setStatus('预览失败：' + (e && e.message ? e.message : '请求失败'), 'error');
      }
    });

    restore.addEventListener('click', async () => {
      if (!window.confirm('执行单区块回溯？会写方块并先备份 .mca。\n区块 (' + cx + ', ' + cz + ')')) {
        return;
      }
      setStatus('正在执行回溯…');
      try {
        const result = await call('/api/diff/chunk/restore', {
          method: 'POST',
          body: body({ confirm: true }),
        });
        if (!result.ok) {
          setStatus('回溯失败：' + (result.error || '未知错误'), 'error');
          return;
        }
        setStatus(result.message || ('已回溯，改动 ' + result.applied + ' 格'), 'ok');
        window.SamsaraMap?.markStale?.();
      } catch (e) {
        setStatus('回溯失败：' + (e && e.message ? e.message : '请求失败'), 'error');
      }
    });

    view.addEventListener('click', async () => {
      const player = playerSelect.value;
      if (!player) return;
      setStatus('正在启动只读回看…');
      try {
        const result = await call('/api/diff/view', {
          method: 'POST',
          body: body({ action: 'start', player, speed: 1 }),
        });
        setStatus(result.message || '回看已启动', 'ok');
      } catch (e) {
        setStatus('回看启动失败：' + (e && e.message ? e.message : '请求失败'), 'error');
      }
    });

    stop.addEventListener('click', async () => {
      const player = playerSelect.value;
      if (!player) return;
      try {
        const result = await call('/api/diff/view', {
          method: 'POST',
          body: JSON.stringify({ action: 'stop', player }),
        });
        setStatus(result.message || '已停止回看', 'ok');
      } catch (e) {
        setStatus('停止回看失败：' + (e && e.message ? e.message : '请求失败'), 'error');
      }
    });
  }


  function renderSelection() {
    const { selection } = ensureEls();
    if (!selection) return;
    selection.hidden = !state.selected && !state.selectedSpawn && !state.selectedChunk;
    selection.replaceChildren();
    const title = document.createElement('div');
    title.className = 'map-selection-title';
    selection.append(title);
    if (!state.selected && !state.selectedSpawn && !state.selectedChunk) {
      title.textContent = '地图详情';
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '点击地图上的旗帜或出生点查看详情；勾选“区块操作”后可点选区块做回溯/回看。拖动平移，滚轮缩放。';
      selection.append(empty);
      return;
    }
    if (state.selectedChunk) {
      renderChunkSelection(selection, title);
      return;
    }
    if (state.selectedSpawn) {
      const stack = currentStack();
      const dim = currentDim();
      const point = state.selectedSpawn;
      const target = point.target;
      title.textContent = '出生点历史';
      appendRows(selection, [
        ['栈层', stack ? stack.label : '—'],
        ['轮次', '第 ' + (point.cycle.index + 1) + ' 轮'
          + (point.cycle.current ? '（当前）' : '')],
        ['维度', dim ? dim.name : '—'],
        ['坐标', target.x + ', ' + target.y + ', ' + target.z],
        ['来源', spawnSourceName(point.cycle.source)],
        ['时间', formatTime(point.cycle.createdAt)],
        ['Y', target.yEstimated ? '估算值（地形未生成/不可用）' : '地表高度'],
      ]);
      const command = teleportCommand(target);
      const code = document.createElement('div');
      code.className = 'map-command';
      const codeText = document.createElement('span');
      codeText.className = 'map-command-text';
      codeText.textContent = command;
      const copy = document.createElement('button');
      copy.type = 'button';
      copy.className = 'map-copy-inline';
      copy.textContent = '⧉';
      copy.title = '复制传送命令';
      copy.setAttribute('aria-label', '复制传送命令');
      copy.addEventListener('click', () => copyText(command, copy, '⧉'));
      code.append(codeText, copy);
      selection.append(code);
      const note = document.createElement('p');
      note.className = 'map-hud-note';
      const notes = ['把 <玩家> 换成游戏内 ID；传送执行属于模组玩法层，Web 只提供坐标与命令。'];
      if (dim && dim.dim === 'the_nether') {
        notes.push('下界坐标按主世界出生点 /8 推算，若基地不在对应坐标请以游戏内实际基地为准。');
      }
      if (target.yEstimated) {
        notes.push('Y 为估算值，传送后请确认落点。');
      }
      note.textContent = notes.join(' ');
      selection.append(note);
      return;
    }
    title.textContent = '选中的旗帜';
    const banner = state.selected;
    appendRows(selection, [
      ['主人', banner.owner || '未知'],
      ['坐标', banner.x + ', ' + banner.y + ', ' + banner.z],
      ['区块', banner.chunkX + ', ' + banner.chunkZ],
      ['朝向', facingName(banner.facing)],
      ['图案', (banner.patterns || []).map((item) => item.id + '（' + item.color + '）').join('、') || '无'],
      ['单旗射线', bannerCells(banner).size + ' 区块'],
    ]);
  }

  function setScale(next, anchorX, anchorY) {
    const before = screenToWorld(anchorX == null ? state.cssWidth / 2 : anchorX,
      anchorY == null ? state.cssHeight / 2 : anchorY);
    state.scale = Math.max(0.0625, Math.min(16, next));
    const after = screenToWorld(anchorX == null ? state.cssWidth / 2 : anchorX,
      anchorY == null ? state.cssHeight / 2 : anchorY);
    state.centerX += before.x - after.x;
    state.centerZ += before.z - after.z;
    updateZoomLabel();
    draw();
  }

  function switchDimension(id) {
    state.dimId = id;
    state.tiles.clear();
    state.pending.clear();
    state.selected = null;
    state.selectedSpawn = null;
    state.selectedChunk = null;
    state.chunkSummary = null;
    renderStats();
    renderSelection();
    fitView();
    draw();
    renderNav();
  }

  function switchStack(id) {
    state.stackId = id;
    const dims = dimensions();
    state.dimId = dims.length ? dims[0].id : null;
    state.tiles.clear();
    state.pending.clear();
    state.selected = null;
    state.selectedSpawn = null;
    state.selectedChunk = null;
    state.chunkSummary = null;
    renderControls();
    renderStats();
    renderSelection();
    fitView();
    draw();
    renderNav();
  }

  function bind() {
    document.documentElement.style.setProperty('--map-player-interp',
      (PLAYER_INTERP_MS / 1000) + 's');
    const {
      canvas, stage, stack, dimension, reload, fit, zoomIn, zoomOut, claims, chunks,
      chunkMode, spawns, shading, showPlayers, overviewToggle,
    } = ensureEls();
    if (!canvas || !stage) return;
    new ResizeObserver(resize).observe(stage);
    resize();
    if (stack) {
      stack.addEventListener('change', () => switchStack(stack.value));
    }
    if (dimension) {
      dimension.addEventListener('change', () => switchDimension(dimension.value));
    }
    if (reload) reload.addEventListener('click', () => loadModel());
    if (overviewToggle) {
      overviewToggle.addEventListener('click', () => setOverview(!state.overview));
    }
    if (fit) {
      fit.addEventListener('click', () => {
        fitView();
        draw();
      });
    }
    if (zoomIn) zoomIn.addEventListener('click', () => setScale(state.scale / 2));
    if (zoomOut) zoomOut.addEventListener('click', () => setScale(state.scale * 2));
    if (claims) {
      claims.addEventListener('change', () => {
        if (state.overview) {
          for (const panel of state.overviewPanels) drawOverviewPanel(panel);
        } else {
          draw();
        }
      });
    }
    if (chunks) {
      chunks.checked = state.showChunks;
      chunks.addEventListener('change', () => {
        state.showChunks = chunks.checked;
        storeFlag(STORE_CHUNKS, state.showChunks);
        updateChunkLegend(16 / state.scale);
        if (!state.overview) {
          scheduleDraw();
        }
      });
      updateChunkLegend(16 / state.scale);
    }
    if (chunkMode) {
      chunkMode.addEventListener('change', () => {
        if (!chunkMode.checked && state.selectedChunk) {
          state.selectedChunk = null;
          state.chunkSummary = null;
          renderSelection();
          draw();
        }
      });
    }
    if (spawns) spawns.addEventListener('change', draw);
    const { showEntities, showTrails, showSeparation } = ensureEls();
    if (showEntities) {
      state.showEntities = showEntities.checked;
      showEntities.addEventListener('change', () => {
        state.showEntities = showEntities.checked;
        drawLiveLayer();
      });
    }
    if (showTrails) {
      state.showTrails = showTrails.checked;
      showTrails.addEventListener('change', () => {
        state.showTrails = showTrails.checked;
        drawLiveLayer();
      });
    }
    const showRareNames = els.showRareNames;
    if (showRareNames) {
      state.showRareNames = showRareNames.checked;
      showRareNames.addEventListener('change', () => {
        state.showRareNames = showRareNames.checked;
        drawLiveLayer();
      });
    }
    if (showSeparation) {
      state.showSeparation = showSeparation.checked;
      showSeparation.addEventListener('change', () => {
        state.showSeparation = showSeparation.checked;
        draw();
      });
    }
    if (showPlayers) {
      state.showPlayers = showPlayers.checked;
      showPlayers.addEventListener('change', () => {
        state.showPlayers = showPlayers.checked;
        if (state.overview) updateOverviewPlayers();
        else schedulePlayerDraw();
      });
    }
    if (shading) {
      state.shading = shading.checked;
      shading.addEventListener('change', () => {
        state.shading = shading.checked;
        state.tiles.clear();
        state.pending.clear();
        state.thumbTiles.clear();
        if (state.overview) {
          for (const panel of state.overviewPanels) loadOverviewThumb(panel);
        } else {
          draw();
        }
      });
    }

    canvas.addEventListener('pointerdown', (event) => {
      if (event.button !== 0) return;
      const rect = canvas.getBoundingClientRect();
      const x = event.clientX - rect.left;
      const y = event.clientY - rect.top;
      // 玩家标记由 click 复制命令；pointerdown 不开始拖拽，避免误移动地图。
      if (playerAt(x, y)) return;
      const live = liveAt(x, y);
      if (live) {
        selectLive(live);
        return;
      }
      const spawn = ensureEls().spawns && ensureEls().spawns.checked ? spawnAt(x, y) : null;
      if (spawn) {
        selectSpawn(spawn);
        return;
      }
      const banner = bannerAt(x, y);
      if (banner) {
        selectBanner(banner);
        return;
      }
      state.drag = { x: event.clientX, y: event.clientY, centerX: state.centerX, centerZ: state.centerZ };
      state.dragMoved = false;
      try { canvas.setPointerCapture(event.pointerId); } catch (e) { /* 合成事件/不支持时忽略 */ }
      canvas.style.cursor = 'grabbing';
    });
    canvas.addEventListener('pointermove', (event) => {
      const rect = canvas.getBoundingClientRect();
      if (state.drag) {
        const dx = event.clientX - state.drag.x;
        const dy = event.clientY - state.drag.y;
        if (Math.abs(dx) + Math.abs(dy) > 3) state.dragMoved = true;
        state.centerX = state.drag.centerX - dx * state.scale;
        state.centerZ = state.drag.centerZ - dy * state.scale;
        draw();
        return;
      }
      const x = event.clientX - rect.left;
      const y = event.clientY - rect.top;
      const live = liveAt(x, y);
      const liveKeyNow = live ? liveKey(live) : '';
      if (liveKeyNow !== (state.hoverLive ? liveKey(state.hoverLive) : '')) {
        state.hoverLive = live ? { ...live } : null;
        drawLiveLayer();
      }
      const spawn = ensureEls().spawns && ensureEls().spawns.checked ? spawnAt(x, y) : null;
      canvas.style.cursor = (live || playerAt(x, y) || spawn || bannerAt(x, y)) ? 'pointer' : 'grab';
      updateTooltip(event);
    });
    canvas.addEventListener('pointerleave', () => {
      if (ensureEls().tooltip) ensureEls().tooltip.hidden = true;
      if (state.hoverLive) {
        state.hoverLive = null;
        drawLiveLayer();
      }
    });
    canvas.addEventListener('pointerup', (event) => {
      state.drag = null;
      canvas.style.cursor = 'grab';
      try { canvas.releasePointerCapture(event.pointerId); } catch (e) { /* ignore */ }
    });
    canvas.addEventListener('click', (event) => {
      if (state.dragMoved) {
        state.dragMoved = false;
        return;
      }
      const rect = canvas.getBoundingClientRect();
      const x = event.clientX - rect.left;
      const y = event.clientY - rect.top;
      const player = playerAt(x, y);
      if (player) {
        selectLive({ ...player, kind: 'player' });
        copyPlayerCommand(player);
        return;
      }
      const live = liveAt(x, y);
      if (live) {
        selectLive(live);
        return;
      }
      const spawn = ensureEls().spawns && ensureEls().spawns.checked ? spawnAt(x, y) : null;
      if (spawn) {
        selectSpawn(spawn);
        return;
      }
      const banner = bannerAt(x, y);
      if (banner) {
        selectBanner(banner);
        return;
      }
      const chunkMode = ensureEls().chunkMode && ensureEls().chunkMode.checked;
      if (chunkMode) {
        const world = screenToWorld(x, y);
        state.selectedChunk = {
          cx: Math.floor(world.x / 16),
          cz: Math.floor(world.z / 16),
        };
        state.selected = null;
        state.selectedSpawn = null;
        state.chunkSummary = null;
        renderSelection();
        draw();
        return;
      }
      // 点击地图空白处自动清除选择，不再单独放“清除选择”按钮。
      if (state.selected || state.selectedSpawn || state.selectedChunk) {
        state.selected = null;
        state.selectedSpawn = null;
        state.selectedChunk = null;
        state.chunkSummary = null;
        renderSelection();
        draw();
      }
    });
    canvas.addEventListener('wheel', (event) => {
      event.preventDefault();
      const rect = canvas.getBoundingClientRect();
      const x = event.clientX - rect.left;
      const y = event.clientY - rect.top;
      setScale(event.deltaY < 0 ? state.scale / 2 : state.scale * 2, x, y);
    }, { passive: false });

    renderSelection();
    if (!state.playerTimer) {
      state.playerTimer = window.setInterval(() => {
        if (window.SamsaraUI?.isActiveTab?.('map')) loadPlayers();
      }, PLAYER_POLL_MS);
    }
    if (!state.liveTimer) {
      state.liveTimer = window.setInterval(() => {
        if (window.SamsaraUI?.isActiveTab?.('map')) loadLive();
      }, 1000);
    }
    if (window.SamsaraBanners) {
      window.SamsaraBanners.ready(() => scheduleDraw());
    }
    if (state.model) {
      renderControls();
      renderStats();
      renderNav();
      draw();
    }
    if (window.SamsaraUI && window.SamsaraUI.onConnect) {
      window.SamsaraUI.onConnect(() => loadModel());
    }
  }

  function activate() {
    const elements = ensureEls();
    if (!elements.canvas) return;
    applyOverviewUi();
    if (state.stale && !state.loading) {
      // 圈地范围保存后 claims.json 可能没变，必须重新拉 /api/map 才能拿到新几何。
      loadModel();
      return;
    }
    if (!state.model && !state.loading) {
      loadModel();
      return;
    }
    if (state.overview) {
      renderOverview();
      renderStats();
      renderNav();
    } else {
      resize();
      if (state.fitPending) {
        state.fitPending = false;
        fitView();
      }
      renderStats();
      renderNav();
      renderSelection();
      draw();
    }
    loadPlayers();
  }

  function refreshCount() {
    const totalProtected = allDimensions()
      .reduce((sum, item) => sum + (item.dim.protectedCount || 0), 0);
    const totalBanners = allDimensions()
      .reduce((sum, item) => sum + (item.dim.bannerCount || 0), 0);
    window.SamsaraUI?.setTabCount('map', totalProtected);
    const tab = document.querySelector('.tab[data-panel="panel-map"]');
    if (tab) {
      tab.title = '全服已保护 ' + totalProtected + ' 个区块 / ' + totalBanners + ' 面旗帜';
      tab.setAttribute('aria-label', '地图，全服已保护 ' + totalProtected + ' 个区块');
    }
  }

  window.SamsaraMap = {
    activate,
    refreshCount,
    reload: loadModel,
    loadModel,
    markStale: () => { state.stale = true; },
  };

  window.SamsaraStackIcon = {
    build: buildStackIcon,
    mapTileUrl: stackMapTileUrl,
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bind);
  } else {
    bind();
  }
})();
