'use strict';

// 原版 Minecraft 26.3 旗帜贴图 atlas（4×4，每格 64×64）。
// 0 = 旗面底图；1~15 = 圈地支持的图案蒙版。运行时按染料颜色乘算染色，
// 与游戏内“旗面颜色 + 图案颜色”的分层一致。
(function () {
  const ATLAS_BASE64 = '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';
  const CELL = 64;
  const COLS = 4;
  const SLOTS = {
    base: 0,
    border: 1,
    cross: 2,
    straight_cross: 3,
    stripe_top: 4,
    stripe_bottom: 5,
    stripe_left: 6,
    stripe_right: 7,
    square_top_left: 8,
    square_top_right: 9,
    square_bottom_left: 10,
    square_bottom_right: 11,
    stripe_downleft: 12,
    stripe_downright: 13,
    stripe_center: 14,
    stripe_middle: 15,
  };
  const DYE_COLORS = [
    '#f9fffe', '#f9801d', '#c74ebd', '#3ab3da',
    '#fed83d', '#80c71f', '#f9801d', '#474f52',
    '#9d9d97', '#169c9c', '#8932b8', '#3c44aa',
    '#835432', '#5e7c16', '#b02e26', '#1d1d21',
  ];

  let atlas = null;
  let atlasPixels = null;
  let ready = false;
  const readyHooks = [];
  const tintedCache = new Map();

  function loadAtlas() {
    if (!atlas) {
      atlas = new Image();
      atlas.onload = () => {
        ready = true;
        atlasPixels = null;
        tintedCache.clear();
        const hooks = readyHooks.splice(0);
        for (const hook of hooks) {
          try { hook(); } catch (e) { /* ignore */ }
        }
      };
      atlas.src = 'data:image/png;base64,' + ATLAS_BASE64;
    }
    return atlas;
  }

  function onReady(hook) {
    if (ready) hook();
    else {
      loadAtlas();
      readyHooks.push(hook);
    }
  }

  function ensurePixels() {
    if (!ready) return null;
    if (atlasPixels) return atlasPixels;
    const source = loadAtlas();
    const canvas = document.createElement('canvas');
    canvas.width = COLS * CELL;
    canvas.height = COLS * CELL;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(source, 0, 0);
    atlasPixels = ctx.getImageData(0, 0, canvas.width, canvas.height);
    return atlasPixels;
  }

  function hexToRgb(hex) {
    const value = String(hex || '#ffffff').replace('#', '');
    const full = value.length === 3
      ? value.split('').map((part) => part + part).join('')
      : value.padEnd(6, 'f').slice(0, 6);
    const number = parseInt(full, 16);
    return [(number >> 16) & 255, (number >> 8) & 255, number & 255];
  }

  function tinted(slot, color) {
    const key = slot + '|' + color;
    const cached = tintedCache.get(key);
    if (cached) return cached;

    const atlasData = ensurePixels();
    if (!atlasData) return null;
    const canvas = document.createElement('canvas');
    canvas.width = 20;
    canvas.height = 40;
    const ctx = canvas.getContext('2d');
    const output = ctx.createImageData(20, 40);
    const source = atlasData.data;
    const target = output.data;
    const rgb = hexToRgb(color);
    const sx = (slot % COLS) * CELL + 1;
    const sy = Math.floor(slot / COLS) * CELL + 1;
    for (let y = 0; y < 40; y += 1) {
      for (let x = 0; x < 20; x += 1) {
        const from = ((sy + y) * atlasData.width + sx + x) * 4;
        const to = (y * 20 + x) * 4;
        const shade = source[from] / 255;
        target[to] = Math.round(rgb[0] * shade);
        target[to + 1] = Math.round(rgb[1] * shade);
        target[to + 2] = Math.round(rgb[2] * shade);
        target[to + 3] = source[from + 3];
      }
    }
    ctx.putImageData(output, 0, 0);
    tintedCache.set(key, canvas);
    return canvas;
  }

  function colorHex(color) {
    if (typeof color === 'string' && color.startsWith('#')) return color;
    const index = Math.max(0, Math.min(15, Number(color) || 0));
    return DYE_COLORS[index];
  }

  function draw(ctx, patterns, baseColor, x, y, width, height) {
    if (!ready) return false;
    const base = tinted(SLOTS.base, colorHex(baseColor));
    if (!base) return false;

    const poleW = Math.max(2, width * .10);
    const poleX = x + width * .68;
    // 木杆与横梁：贴近 MC 旗帜物品的轮廓，旗面只取方块模型正面 20×40。
    ctx.fillStyle = '#6b4b2a';
    ctx.fillRect(poleX, y + height * .02, poleW, height * .96);
    ctx.fillStyle = '#8a6237';
    ctx.fillRect(poleX, y + height * .02, Math.max(1, poleW * .28), height * .96);
    ctx.fillStyle = '#4e3618';
    ctx.fillRect(x + width * .12, y + height * .03, width * .68, height * .07);

    const clothX = x + width * .16;
    const clothY = y + height * .11;
    const clothW = width * .50;
    const clothH = height * .84;
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(base, clothX, clothY, clothW, clothH);
    for (const pattern of patterns || []) {
      const slot = SLOTS[pattern.id];
      if (slot == null) continue;
      const layer = tinted(slot, colorHex(pattern.color));
      if (layer) ctx.drawImage(layer, clothX, clothY, clothW, clothH);
    }
    return true;
  }

  function icon(patterns, baseColor, size) {
    const px = Math.max(16, Math.round(size || 64));
    const canvas = document.createElement('canvas');
    canvas.width = px;
    canvas.height = px;
    const ctx = canvas.getContext('2d');
    const paintNow = () => {
      ctx.clearRect(0, 0, px, px);
      draw(ctx, patterns, baseColor, 0, 0, px, px);
    };
    paintNow();
    onReady(paintNow);
    return canvas;
  }

  function paint(canvas, patterns, baseColor) {
    if (!canvas) return;
    canvas.width = CELL;
    canvas.height = CELL;
    const ctx = canvas.getContext('2d');
    const paintNow = () => {
      ctx.clearRect(0, 0, CELL, CELL);
      draw(ctx, patterns, baseColor, 0, 0, CELL, CELL);
    };
    paintNow();
    onReady(paintNow);
  }

  window.SamsaraBanners = {
    slots: SLOTS,
    dyeColors: DYE_COLORS,
    colorHex,
    icon,
    paint,
    draw,
    ready: onReady,
  };
})();
