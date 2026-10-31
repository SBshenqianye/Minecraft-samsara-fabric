'use strict';

// 地图标签：地形底图来自服务端扫描 region 文件的 PNG tile；
// 圈地白名单/旗帜来自 claims.json。只读展示，不提供编辑入口。
(function () {
  const state = {
    model: null,
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
    hover: null,
    drag: null,
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
      dimension: $('map-dimension'),
      zoom: $('map-zoom-label'),
      reload: $('map-reload'),
      fit: $('map-fit'),
      zoomIn: $('map-zoom-in'),
      zoomOut: $('map-zoom-out'),
      claims: $('map-show-claims'),
      shading: $('map-shading'),
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

  function dimensions() {
    return state.model && Array.isArray(state.model.dimensions)
      ? state.model.dimensions : [];
  }

  function currentDim() {
    return dimensions().find((dim) => dim.id === state.dimId) || dimensions()[0] || null;
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
    const { dimension } = ensureEls();
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
    const dim = currentDim();
    const all = dimensions();
    const totalProtected = all.reduce((sum, item) => sum + (item.protectedCount || 0), 0);
    const totalBanners = all.reduce((sum, item) => sum + (item.bannerCount || 0), 0);
    const chips = [
      ['维度', all.length + ' 个'],
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
    if (stats) {
      const totalProtected = dimensions().reduce((sum, dim) => sum + (dim.protectedCount || 0), 0);
      targets.push({ id: 'map-overview', el: stats });
      items.push({ label: '全服统计', count: totalProtected, target: 'map-overview' });
    }
    for (const dim of dimensions()) {
      items.push({
        label: dim.name,
        count: dim.protectedCount || 0,
        target: stage ? 'map-stage-anchor' : '',
        onClick: () => {
          if (dim.id !== state.dimId) switchDimension(dim.id);
          const dimension = ensureEls().dimension;
          if (dimension) dimension.value = dim.id;
          if (stage) window.SamsaraUI.scrollToTarget?.('map-stage-anchor');
        },
      });
    }
    if (selection) {
      targets.push({ id: 'map-stage-anchor', el: stage });
      targets.push({ id: 'map-detail', el: selection });
      items.push({ label: '选中旗帜', target: 'map-detail' });
    }
    window.SamsaraUI.buildNav('地图导航', items.filter((item) => item.target), targets);
  }

  function fitView() {
    const dim = currentDim();
    const { canvas } = ensureEls();
    if (!canvas) return;
    const width = state.cssWidth || canvas.clientWidth || 800;
    const height = state.cssHeight || canvas.clientHeight || 520;
    if (!dim || !dim.bounds) {
      state.centerX = 0; state.centerZ = 0; state.scale = 4;
      updateZoomLabel();
      return;
    }
    const b = dim.bounds;
    const spanX = Math.max(1, b.maxX - b.minX);
    const spanZ = Math.max(1, b.maxZ - b.minZ);
    const need = Math.max(spanX / Math.max(80, width - 80),
      spanZ / Math.max(80, height - 80));
    state.scale = [0.25, 0.5, 1, 2, 4, 8].find((value) => value >= need) || 8;
    state.centerX = (b.minX + b.maxX) / 2;
    state.centerZ = (b.minZ + b.maxZ) / 2;
    updateZoomLabel();
  }

  function updateZoomLabel() {
    const { zoom, zoomIn, zoomOut } = ensureEls();
    if (zoom) zoom.textContent = state.scale + ' 格/像素';
    if (zoomIn) zoomIn.disabled = state.scale <= 0.25;
    if (zoomOut) zoomOut.disabled = state.scale >= 8;
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
    return state.scale < 1 ? 1 : state.scale;
  }

  function tileKey(rx, rz) {
    return state.dimId + '|' + rx + '|' + rz + '|' + tileScale() + '|' + (state.shading ? 1 : 0);
  }

  function ensureTile(rx, rz) {
    const key = tileKey(rx, rz);
    if (state.tiles.has(key) || state.pending.has(key)) return;
    state.pending.add(key);
    const url = '/api/map/tile?dim=' + encodeURIComponent(state.dimId)
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
        const base = icon.dyeColors[Math.max(0, Math.min(15, color))] || '#f9fffe';
        const patternId = pattern ? pattern.id : 'border';
        const size = 26;
        ctx.save();
        ctx.globalAlpha = .98;
        icon.draw(ctx, [{ id: patternId, color: color === 15 ? 0 : 15 }], base,
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
    const options = [16, 32, 64, 128, 256, 512, 1024];
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

  function updateTooltip(event) {
    const { tooltip } = ensureEls();
    if (!tooltip) return;
    const rect = ensureEls().canvas.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;
    const banner = bannerAt(x, y);
    const dim = currentDim();
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

  function selectBanner(banner) {
    state.selected = banner;
    renderSelection();
    draw();
  }

  function renderSelection() {
    const { selection } = ensureEls();
    if (!selection) return;
    selection.replaceChildren();
    const title = document.createElement('div');
    title.className = 'map-selection-title';
    title.textContent = '选中的旗帜';
    selection.append(title);
    if (!state.selected) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '点击地图上的旗帜查看主人、图案和坐标；拖动平移，滚轮缩放。';
      selection.append(empty);
      return;
    }
    const banner = state.selected;
    const rows = [
      ['主人', banner.owner || '未知'],
      ['坐标', banner.x + ', ' + banner.y + ', ' + banner.z],
      ['区块', banner.chunkX + ', ' + banner.chunkZ],
      ['朝向', facingName(banner.facing)],
      ['图案', (banner.patterns || []).map((item) => item.id + '（' + item.color + '）').join('、') || '无'],
      ['范围', bannerCells(banner).size + ' 区块'],
    ];
    const list = document.createElement('dl');
    list.className = 'map-selection-list';
    for (const [key, value] of rows) {
      const dt = document.createElement('dt');
      dt.textContent = key;
      const dd = document.createElement('dd');
      dd.textContent = value;
      list.append(dt, dd);
    }
    selection.append(list);
    const clear = document.createElement('button');
    clear.type = 'button';
    clear.className = 'ghost';
    clear.textContent = '清除选择';
    clear.addEventListener('click', () => {
      state.selected = null;
      renderSelection();
      draw();
    });
    selection.append(clear);
  }

  function setScale(next, anchorX, anchorY) {
    const before = screenToWorld(anchorX == null ? state.cssWidth / 2 : anchorX,
      anchorY == null ? state.cssHeight / 2 : anchorY);
    state.scale = Math.max(0.25, Math.min(8, next));
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
    renderStats();
    renderSelection();
    fitView();
    draw();
    renderNav();
  }

  function bind() {
    const { canvas, stage, dimension, reload, fit, zoomIn, zoomOut, claims, shading } = ensureEls();
    if (!canvas || !stage) return;
    new ResizeObserver(resize).observe(stage);
    resize();
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
      const banner = bannerAt(x, y);
      if (banner) {
        selectBanner(banner);
        return;
      }
      state.drag = { x: event.clientX, y: event.clientY, centerX: state.centerX, centerZ: state.centerZ };
      canvas.setPointerCapture(event.pointerId);
      canvas.style.cursor = 'grabbing';
    });
    canvas.addEventListener('pointermove', (event) => {
      const rect = canvas.getBoundingClientRect();
      if (state.drag) {
        state.centerX = state.drag.centerX - (event.clientX - state.drag.x) * state.scale;
        state.centerZ = state.drag.centerZ - (event.clientY - state.drag.y) * state.scale;
        draw();
        return;
      }
      const x = event.clientX - rect.left;
      const y = event.clientY - rect.top;
      canvas.style.cursor = bannerAt(x, y) ? 'pointer' : 'grab';
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
      const rect = canvas.getBoundingClientRect();
      const banner = bannerAt(event.clientX - rect.left, event.clientY - rect.top);
      if (banner) selectBanner(banner);
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
    const totalProtected = dimensions()
      .reduce((sum, dim) => sum + (dim.protectedCount || 0), 0);
    const totalBanners = dimensions()
      .reduce((sum, dim) => sum + (dim.bannerCount || 0), 0);
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
