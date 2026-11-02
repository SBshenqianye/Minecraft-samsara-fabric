'use strict';

// 地图标签：地形底图来自服务端扫描 region 文件的 PNG tile；
// 圈地白名单/旗帜来自 claims.json。只读展示，不提供编辑入口。
(function () {
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
    playerTimer: 0,
    showPlayers: true,
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

  function ensureEls() {
    if (els) return els;
    els = {
      message: $('map-message'),
      stats: $('map-stats'),
      stage: $('map-stage-anchor'),
      canvas: $('map-canvas'),
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
      spawns: $('map-show-spawns'),
      shading: $('map-shading'),
      showPlayers: $('map-show-players'),
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
      const data = await window.SamsaraUI.api('/api/map');
      state.model = data;
      state.stale = false;
      state.overviewKey = '';
      state.selected = null;
      state.selectedSpawn = null;
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
    stats.replaceChildren();
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
    el.textContent = '在线 ' + state.players.length + ' 人 · 2 秒刷新 · ' + time;
  }

  async function loadPlayers() {
    if (!state.model) return;
    if (window.SamsaraUI && window.SamsaraUI.getToken && !window.SamsaraUI.getToken()) return;
    try {
      const data = await window.SamsaraUI.api('/api/map/players');
      state.players = Array.isArray(data.players) ? data.players : [];
      state.playersFetchedAt = Date.now();
      updatePlayerStatus();
      if (state.overview) {
        updateOverviewPlayers();
        renderStats();
      } else {
        renderStats();
        draw();
      }
    } catch (error) {
      // 玩家轮询失败不打断地图浏览；连接失效由统一 API 处理。
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

  function createPlayerMarker(player, panel) {
    const { view } = panel;
    let x = (player.x - view.viewX) / view.viewW * 100;
    let z = (player.z - view.viewZ) / view.viewH * 100;
    const outside = x < 0 || x > 100 || z < 0 || z > 100;
    x = Math.max(2, Math.min(98, x));
    z = Math.max(5, Math.min(95, z));
    const marker = document.createElement('button');
    marker.type = 'button';
    marker.className = 'map-player-marker' + (outside ? ' outside' : '');
    marker.style.left = x + '%';
    marker.style.top = z + '%';
    marker.title = playerName(player) + ' · ' + stackLabelOf(player.stackId)
      + ' · ' + dimensionLabel(player.dimension)
      + '\n坐标 ' + fmtCoord(player.x) + ', ' + fmtCoord(player.y) + ', ' + fmtCoord(player.z)
      + (outside ? '\n（在缩略图范围外，标记已贴边）' : '')
      + '\n点击复制 TP 命令';
    marker.setAttribute('aria-label', playerName(player) + '，点击复制 TP 命令');
    const dot = document.createElement('span');
    dot.className = 'dot';
    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = playerName(player);
    marker.append(dot, name);
    marker.addEventListener('click', (event) => {
      event.stopPropagation();
      copyPlayerCommand(player);
    });
    return marker;
  }

  function updateOverviewPlayers() {
    const showPlayers = !ensureEls().showPlayers || ensureEls().showPlayers.checked;
    state.showPlayers = showPlayers;
    for (const panel of state.overviewPanels) {
      const players = playersFor(panel.stack.id, panel.dim.dim);
      panel.meta.textContent = (panel.dim.protectedCount || 0) + ' 区块 · '
        + (panel.dim.bannerCount || 0) + ' 旗 · ' + players.length + ' 人';
      panel.playerLayer.replaceChildren();
      if (!showPlayers || !panel.view.hasRegion) continue;
      for (const player of players) {
        panel.playerLayer.append(createPlayerMarker(player, panel));
      }
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
    const { canvas, stage } = ensureEls();
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
    return '/api/map/tile?stack=' + encodeURIComponent(stackId)
      + '&dim=' + encodeURIComponent(dimId)
      + '&rx=' + rx + '&rz=' + rz + '&scale=' + scale
      + '&shade=' + (shade ? 1 : 0);
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
    drawBanners(ctx, dim, width, height);
    if (!ensureEls().spawns || ensureEls().spawns.checked) {
      drawSpawns(ctx, width, height);
    }
    drawPlayers(ctx, dim, width, height);
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

  function drawPlayers(ctx, dim, width, height) {
    if (!state.showPlayers) return;
    const stack = currentStack();
    if (!stack || !dim) return;
    for (const player of playersFor(stack.id, dim.dim)) {
      const point = worldToScreen(player.x, player.z);
      if (point.x < -30 || point.y < -30 || point.x > width + 30 || point.y > height + 30) {
        continue;
      }
      ctx.save();
      ctx.beginPath();
      ctx.arc(point.x, point.y, 8, 0, Math.PI * 2);
      ctx.fillStyle = 'rgba(96, 165, 250, .22)';
      ctx.fill();
      ctx.beginPath();
      ctx.arc(point.x, point.y, 5, 0, Math.PI * 2);
      ctx.fillStyle = '#60a5fa';
      ctx.fill();
      ctx.strokeStyle = '#eff6ff';
      ctx.lineWidth = 1.6;
      ctx.stroke();
      const label = playerName(player);
      const textX = point.x + 9;
      const textY = point.y - 7;
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
  }

  function playerAt(screenX, screenY) {
    const stack = currentStack();
    const dim = currentDim();
    if (!stack || !dim || !state.showPlayers) return null;
    let best = null;
    let bestDistance = 13 * 13;
    for (const player of playersFor(stack.id, dim.dim)) {
      const point = worldToScreen(player.x, player.z);
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
    ctx.fillText(blocks + ' 格 · ' + state.scale + ' 格/像素', x, y - 9);
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
    renderSelection();
    draw();
  }

  function selectSpawn(point) {
    state.selectedSpawn = point;
    state.selected = null;
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

  function renderSelection() {
    const { selection } = ensureEls();
    if (!selection) return;
    selection.hidden = !state.selected && !state.selectedSpawn;
    selection.replaceChildren();
    const title = document.createElement('div');
    title.className = 'map-selection-title';
    selection.append(title);
    if (!state.selected && !state.selectedSpawn) {
      title.textContent = '地图详情';
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '点击地图上的旗帜或出生点查看详情；拖动平移，滚轮缩放。';
      selection.append(empty);
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
    renderControls();
    renderStats();
    renderSelection();
    fitView();
    draw();
    renderNav();
  }

  function bind() {
    const {
      canvas, stage, stack, dimension, reload, fit, zoomIn, zoomOut, claims, spawns,
      shading, showPlayers, overviewToggle,
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
    if (spawns) spawns.addEventListener('change', draw);
    if (showPlayers) {
      state.showPlayers = showPlayers.checked;
      showPlayers.addEventListener('change', () => {
        state.showPlayers = showPlayers.checked;
        if (state.overview) updateOverviewPlayers();
        else draw();
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
      const spawn = ensureEls().spawns && ensureEls().spawns.checked ? spawnAt(x, y) : null;
      canvas.style.cursor = (playerAt(x, y) || spawn || bannerAt(x, y)) ? 'pointer' : 'grab';
      updateTooltip(event);
    });
    canvas.addEventListener('pointerleave', () => {
      if (ensureEls().tooltip) ensureEls().tooltip.hidden = true;
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
        copyPlayerCommand(player);
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
      // 点击地图空白处自动清除选择，不再单独放“清除选择”按钮。
      if (state.selected || state.selectedSpawn) {
        state.selected = null;
        state.selectedSpawn = null;
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
      }, 2000);
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

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bind);
  } else {
    bind();
  }
})();
