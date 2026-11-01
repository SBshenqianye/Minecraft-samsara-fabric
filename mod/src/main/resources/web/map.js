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
    raf: 0,
    fitPending: false,
    shading: true,
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
      renderSelection();
      fitView();
      state.fitPending = true;
      refreshCount();
      renderNav();
      draw();
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
          ? 'world（栈顶 / 最新）'
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
    const chips = [
      ['栈层', stack ? stack.label : '无'],
      ['栈序号', stack ? '#' + ((stack.index || 0) + 1) : '—'],
      ['出生点', spawn ? spawn.x + ', ' + spawn.y + ', ' + spawn.z : '—'],
      ['出生历史', cycles + ' 轮'],
      ['当前地形', dim ? dim.regions.length + ' 个 region' : '无'],
      ['保护区块', dim ? String(dim.protectedCount || 0) : '0'],
      ['旗帜', dim ? String(dim.bannerCount || 0) : '0'],
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
      targets.push({ id: 'map-overview', el: stats });
      items.push({ label: '全服统计', count: totalProtected, target: 'map-overview' });
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
    if (zoom) zoom.textContent = state.scale + ' 格/像素';
    if (zoomIn) zoomIn.disabled = state.scale <= 0.0625;
    if (zoomOut) zoomOut.disabled = state.scale >= 16;
  }

  function resize() {
    const { canvas, stage } = ensureEls();
    if (!canvas || !stage) return;
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

  function ensureTile(rx, rz) {
    const key = tileKey(rx, rz);
    if (state.tiles.has(key) || state.pending.has(key)) return;
    state.pending.add(key);
    const url = '/api/map/tile?stack=' + encodeURIComponent(state.stackId)
      + '&dim=' + encodeURIComponent(state.dimId)
      + '&rx=' + rx + '&rz=' + rz + '&scale=' + tileScale()
      + '&shade=' + (state.shading ? 1 : 0);
    fetch(url, { headers: window.SamsaraUI.authHeaders ? window.SamsaraUI.authHeaders() : {} })
      .then(async (response) => {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        const blob = await response.blob();
        if ('createImageBitmap' in window) {
          return createImageBitmap(blob);
        }
        return new Promise((resolve, reject) => {
          const image = new Image();
          image.onload = () => resolve(image);
          image.onerror = reject;
          image.src = URL.createObjectURL(blob);
        });
      })
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

  function pointInPolygon(x, z, points) {
    let inside = false;
    for (let i = 0, j = points.length - 1; i < points.length; j = i, i += 1) {
      const xi = points[i].x;
      const zi = points[i].z;
      const xj = points[j].x;
      const zj = points[j].z;
      if (((zi > z) !== (zj > z))
          && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
        inside = !inside;
      }
    }
    return inside;
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

  function bannerCells(banner) {
    const claimRange = state.model && state.model.claimRange;
    const cells = new Set();
    if (!claimRange || !banner.patterns) return cells;
    const offset = FACING_OFFSET[banner.facing] || 0;
    const baseX = Math.floor(banner.x / 16);
    const baseZ = Math.floor(banner.z / 16);
    for (const pattern of banner.patterns) {
      const names = claimRange.patternDirections && claimRange.patternDirections[pattern.id];
      if (!Array.isArray(names) || names.length === 0) continue;
      const distance = distanceForColor(pattern.color);
      const dirs = names.map((name) => DIRECTION_NAMES.indexOf(name))
        .filter((index) => index >= 0)
        .map((index) => (index + offset) % 8);
      const endpoints = [];
      cells.add(baseX + ',' + baseZ);
      for (const dir of dirs) {
        const direction = DIRECTIONS[dir];
        for (let step = 1; step <= distance; step += 1) {
          cells.add((baseX + direction.dx * step) + ',' + (baseZ + direction.dz * step));
        }
        endpoints.push({
          x: baseX + direction.dx * distance,
          z: baseZ + direction.dz * distance,
        });
      }
      if (endpoints.length >= 3) {
        endpoints.sort((a, b) => Math.atan2(a.z - baseZ, a.x - baseX)
          - Math.atan2(b.z - baseZ, b.x - baseX));
        for (let i = 0; i < endpoints.length; i += 1) {
          const a = endpoints[i];
          const b = endpoints[(i + 1) % endpoints.length];
          const steps = Math.max(Math.abs(b.x - a.x), Math.abs(b.z - a.z));
          for (let step = 0; step <= steps; step += 1) {
            const t = steps === 0 ? 0 : step / steps;
            cells.add(Math.round(a.x + (b.x - a.x) * t) + ','
              + Math.round(a.z + (b.z - a.z) * t));
          }
        }
        const minX = Math.min(...endpoints.map((point) => point.x));
        const maxX = Math.max(...endpoints.map((point) => point.x));
        const minZ = Math.min(...endpoints.map((point) => point.z));
        const maxZ = Math.max(...endpoints.map((point) => point.z));
        for (let x = minX; x <= maxX; x += 1) {
          for (let z = minZ; z <= maxZ; z += 1) {
            if (pointInPolygon(x, z, endpoints)) cells.add(x + ',' + z);
          }
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
    const spawn = ensureEls().spawns && ensureEls().spawns.checked ? spawnAt(x, y) : null;
    const banner = bannerAt(x, y);
    const dim = currentDim();
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
      button.textContent = '已复制';
      window.setTimeout(() => { button.textContent = label; }, 1600);
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
      button.textContent = '复制失败，请手动选择';
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
      ['范围', bannerCells(banner).size + ' 区块'],
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
    const { canvas, stage, stack, dimension, reload, fit, zoomIn, zoomOut, claims, spawns, shading }
      = ensureEls();
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
    if (fit) {
      fit.addEventListener('click', () => {
        fitView();
        draw();
      });
    }
    if (zoomIn) zoomIn.addEventListener('click', () => setScale(state.scale / 2));
    if (zoomOut) zoomOut.addEventListener('click', () => setScale(state.scale * 2));
    if (claims) claims.addEventListener('change', draw);
    if (spawns) spawns.addEventListener('change', draw);
    if (shading) {
      state.shading = shading.checked;
      shading.addEventListener('change', () => {
        state.shading = shading.checked;
        state.tiles.clear();
        state.pending.clear();
        draw();
      });
    }

    canvas.addEventListener('pointerdown', (event) => {
      if (event.button !== 0) return;
      const rect = canvas.getBoundingClientRect();
      const x = event.clientX - rect.left;
      const y = event.clientY - rect.top;
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
      canvas.style.cursor = (spawn || bannerAt(x, y)) ? 'pointer' : 'grab';
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
    if (!state.model && !state.loading) {
      loadModel();
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
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bind);
  } else {
    bind();
  }
})();
