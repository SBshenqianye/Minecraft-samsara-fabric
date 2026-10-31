'use strict';

// 纯像素粒子预览：不加载贴图，所有粒子用 1 CSS 像素的像素点绘制。
// 每个粒子是一张 4~7 像素的小 sprite，带明暗层次，接近 MC 原版粒子的像素感。

(function () {
  const STYLES = {
    crit: { colors: ['#fff7b0', '#ffe066', '#ffc21a'], sprite: 'crit', alpha: .95, drift: 13 },
    enchant: { colors: ['#b5f5ff', '#67e8f9', '#38bdf8'], sprite: 'sparkle', alpha: .9, drift: 11 },
    smoke: { colors: ['#9aa0a6', '#c2c7cc', '#71767c'], sprite: 'smoke', alpha: .42, drift: 11 },
    large_smoke: { colors: ['#858b91', '#b0b5ba', '#62686e'], sprite: 'smoke', alpha: .38, drift: 14 },
    cloud: { colors: ['#e5e7eb', '#f8fafc', '#cbd5e1'], sprite: 'smoke', alpha: .34, drift: 9 },
    flame: { colors: ['#ffd166', '#fb923c', '#ef4444'], sprite: 'flame', alpha: .95, drift: 14 },
    soul_fire_flame: { colors: ['#99f6e4', '#2dd4bf', '#38bdf8'], sprite: 'flame', alpha: .95, drift: 13 },
    end_rod: { colors: ['#f5f3ff', '#ddd6fe', '#c4b5fd'], sprite: 'streak', alpha: .95, drift: 8 },
    happy_villager: { colors: ['#86efac', '#22c55e', '#bbf7d0'], sprite: 'star', alpha: .9, drift: 11 },
    composter: { colors: ['#a3e635', '#65a30d', '#d9f99d'], sprite: 'dot2', alpha: .7, drift: 10 },
    portal: { colors: ['#d8b4fe', '#a855f7', '#7c3aed'], sprite: 'swirl', alpha: .95, drift: 12 },
    witch: { colors: ['#c084fc', '#7e22ce', '#e9d5ff'], sprite: 'dot1', alpha: .8, drift: 11 },
    note: { colors: ['#fef08a', '#facc15', '#fde68a'], sprite: 'note', alpha: .9, drift: 10 },
    heart: { colors: ['#fb7185', '#e11d48', '#fda4af'], sprite: 'heart', alpha: .9, drift: 9 },
    angry_villager: { colors: ['#9ca3af', '#4b5563', '#d1d5db'], sprite: 'smoke', alpha: .35, drift: 9 },
    snowflake: { colors: ['#f0f9ff', '#bae6fd', '#e0f2fe'], sprite: 'flake', alpha: .85, drift: 8 },
    white_ash: { colors: ['#f8fafc', '#e2e8f0', '#cbd5e1'], sprite: 'dot1', alpha: .65, drift: 7 },
    ash: { colors: ['#a8a29e', '#78716c', '#d6d3d1'], sprite: 'dot1', alpha: .5, drift: 7 },
  };

  const DEFAULT_STYLE = { colors: ['#5eead4', '#2dd4bf', '#99f6e4'], sprite: 'dot1', alpha: .8, drift: 9 };

  // '.' 透明，'#' 主色，'+' 亮部，'-' 暗部。
  const SPRITES = {
    crit: [
      '..+..',
      '.+#+.',
      '+###+',
      '.+#+.',
      '..+..',
    ],
    star: [
      '..#..',
      '.+#+.',
      '##+##',
      '.+#+.',
      '..#..',
    ],
    sparkle: [
      '#...#',
      '.+.+.',
      '..+..',
      '.+.+.',
      '#...#',
    ],
    smoke: [
      '..##..',
      '.####.',
      '##+###',
      '######',
      '.####.',
      '..##..',
    ],
    flame: [
      '..+..',
      '..+..',
      '.++#.',
      '.##+#',
      '#####',
      '.###.',
    ],
    streak: [
      '.+.',
      '.#.',
      '.#.',
      '.#.',
      '.-.',
    ],
    dot2: [
      '.##.',
      '#++#',
      '#++#',
      '.##.',
    ],
    dot1: ['+'],
    note: [
      '..##.',
      '..#.#',
      '..#..',
      '###..',
      '###..',
      '.#...',
    ],
    heart: [
      '.##.##.',
      '#######',
      '#######',
      '.#####.',
      '..###..',
      '...#...',
    ],
    swirl: [
      '..##.',
      '.#..#',
      '#.+.#',
      '#..#.',
      '.##..',
    ],
    flake: [
      '#..#..#',
      '.#.#.#.',
      '..###..',
      '##+#+##',
      '..###..',
      '.#.#.#.',
      '#..#..#',
    ],
  };

  const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const visibility = new WeakMap();
  const shadeCache = new Map();
  let observer = null;

  function ensureObserver() {
    if (observer || !('IntersectionObserver' in window)) return;
    observer = new IntersectionObserver((entries) => {
      for (const entry of entries) visibility.set(entry.target, entry.isIntersecting);
    }, { rootMargin: '80px' });
  }

  function styleFor(name) {
    return STYLES[name] || DEFAULT_STYLE;
  }

  function rand(min, max) {
    return min + Math.random() * (max - min);
  }

  function shade(color, amount) {
    const key = color + '|' + amount;
    const cached = shadeCache.get(key);
    if (cached) return cached;
    const hex = color.replace('#', '');
    const full = hex.length === 3
      ? hex.split('').map((ch) => ch + ch).join('')
      : hex;
    const value = parseInt(full, 16);
    if (!Number.isFinite(value)) return color;
    const target = amount > 0 ? 255 : 0;
    const k = Math.abs(amount);
    const parts = [(value >> 16) & 255, (value >> 8) & 255, value & 255].map((channel) =>
      Math.round(channel + (target - channel) * k));
    const out = '#' + parts.map((channel) => channel.toString(16).padStart(2, '0')).join('');
    shadeCache.set(key, out);
    return out;
  }

  function makeParticle(w, h, style) {
    return {
      x: rand(2, w - 2),
      y: rand(2, h - 2),
      vx: rand(-style.drift, style.drift),
      vy: rand(-style.drift * .7, style.drift * .2),
      color: style.colors[Math.floor(Math.random() * style.colors.length)],
    };
  }

  function drawSprite(ctx, cx, cy, spriteName, color, alpha) {
    const sprite = SPRITES[spriteName] || SPRITES.dot1;
    const height = sprite.length;
    let width = 0;
    for (const row of sprite) width = Math.max(width, row.length);
    const x0 = Math.round(cx - width / 2);
    const y0 = Math.round(cy - height / 2);
    const light = shade(color, .45);
    const dark = shade(color, -.38);
    ctx.globalAlpha = alpha;
    for (let y = 0; y < height; y++) {
      const row = sprite[y];
      for (let x = 0; x < row.length; x++) {
        const ch = row.charAt(x);
        if (ch === '.') continue;
        ctx.fillStyle = ch === '+' ? light : ch === '-' ? dark : color;
        ctx.fillRect(x0 + x, y0 + y, 1, 1);
      }
    }
    ctx.globalAlpha = 1;
  }

  function paintBackground(ctx, w, h, theme) {
    if (theme === 'day') {
      const bands = [
        ['#4f8ed6', 0, .34],
        ['#6eaaf0', .34, .28],
        ['#9ac7f5', .62, .22],
        ['#d6ebfb', .84, .16],
      ];
      for (const [color, start, size] of bands) {
        ctx.fillStyle = color;
        ctx.fillRect(0, Math.floor(h * start), w, Math.ceil(h * size) + 1);
      }
      // 像素太阳
      const sx = Math.floor(w * .82);
      const sy = Math.floor(h * .18);
      ctx.fillStyle = '#fff7b0';
      ctx.fillRect(sx, sy, 3, 3);
      ctx.fillStyle = '#ffe066';
      ctx.fillRect(sx - 1, sy + 1, 5, 1);
      ctx.fillRect(sx + 1, sy - 1, 1, 5);
      return;
    }
    if (theme === 'night') {
      ctx.fillStyle = '#000000';
      ctx.fillRect(0, 0, w, h);
      ctx.fillStyle = '#dbeafe';
      for (let i = 0; i < 12; i++) {
        const x = (i * 17 + 5) % w;
        const y = (i * 11 + 3) % h;
        ctx.fillRect(x, y, 1, 1);
      }
      return;
    }
    ctx.fillStyle = '#0b1017';
    ctx.fillRect(0, 0, w, h);
    ctx.fillStyle = '#1b2633';
    for (let i = 0; i < 8; i++) {
      ctx.fillRect((i * 13 + 7) % w, (i * 7 + 3) % h, 1, 1);
    }
  }

  function makeBurst(style, x, y) {
    const out = [];
    const count = 14;
    for (let i = 0; i < count; i++) {
      const angle = (i / count) * Math.PI * 2 + rand(-.2, .2);
      const speed = rand(8, 24);
      out.push({
        x: Math.round(x),
        y: Math.round(y),
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed - 4,
        color: style.colors[Math.floor(Math.random() * style.colors.length)],
        age: 0,
        ttl: rand(.65, 1.15),
      });
    }
    return out;
  }

  function attach(canvas, name, options) {
    if (!canvas || !canvas.getContext) return;
    const opts = options || {};
    if (canvas.__samsaraRaf) {
      cancelAnimationFrame(canvas.__samsaraRaf);
      canvas.__samsaraRaf = 0;
    }
    ensureObserver();
    const style = styleFor(name);
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    if (canvas.isConnected && !canvas.clientWidth) {
      // 还在布局中，下一帧再量。
      requestAnimationFrame(() => attach(canvas, name, opts));
      return;
    }
    const width = Math.max(1, Math.round(canvas.clientWidth || 130));
    const height = Math.max(1, Math.round(canvas.clientHeight || 50));
    canvas.width = Math.round(width * dpr);
    canvas.height = Math.round(height * dpr);
    canvas.style.imageRendering = 'pixelated';
    const ctx = canvas.getContext('2d');
    ctx.imageSmoothingEnabled = false;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    const particles = [];
    for (let i = 0; i < 16; i++) particles.push(makeParticle(width, height, style));
    const burst = [];

    canvas.style.cursor = 'crosshair';
    if (canvas.__samsaraPointer) {
      canvas.removeEventListener('pointerdown', canvas.__samsaraPointer);
    }
    canvas.__samsaraPointer = (event) => {
      const rect = canvas.getBoundingClientRect();
      const x = (event.clientX - rect.left) / (rect.width / width);
      const y = (event.clientY - rect.top) / (rect.height / height);
      burst.push(...makeBurst(style, x, y));
    };
    canvas.addEventListener('pointerdown', canvas.__samsaraPointer);

    let last = 0;
    function frame(now) {
      if (!canvas.isConnected) {
        if (observer) observer.unobserve(canvas);
        canvas.__samsaraRaf = 0;
        return;
      }
      if (visibility.get(canvas) === false) {
        canvas.__samsaraRaf = requestAnimationFrame(frame);
        return;
      }
      const dt = Math.min(50, last ? now - last : 16) / 1000;
      last = now;
      ctx.clearRect(0, 0, width, height);
      paintBackground(ctx, width, height, opts.background);

      for (const p of particles) {
        if (!reduceMotion) {
          p.x += p.vx * dt;
          p.y += p.vy * dt;
          if (p.x < -4) p.x = width + 4;
          if (p.x > width + 4) p.x = -4;
          if (p.y < -4) p.y = height + 4;
          if (p.y > height + 4) p.y = -4;
        }
        drawSprite(ctx, p.x, p.y, style.sprite, p.color, style.alpha);
      }

      for (let i = burst.length - 1; i >= 0; i--) {
        const p = burst[i];
        p.age += dt;
        if (p.age >= p.ttl) {
          burst.splice(i, 1);
          continue;
        }
        p.x += p.vx * dt;
        p.y += p.vy * dt;
        p.vy += 34 * dt;
        drawSprite(ctx, p.x, p.y, style.sprite, p.color, style.alpha * (1 - p.age / p.ttl));
      }
      canvas.__samsaraRaf = requestAnimationFrame(frame);
    }
    if (observer) observer.observe(canvas);
    visibility.set(canvas, true);
    canvas.__samsaraRaf = requestAnimationFrame(frame);
  }

  window.SamsaraParticles = { attach, styleFor };
})();
