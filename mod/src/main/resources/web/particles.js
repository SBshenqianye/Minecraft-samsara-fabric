'use strict';

// 粒子预览：直接使用 Minecraft 26.3 客户端 particles 贴图的像素数据（打包为内嵌 atlas），
// 按原版 8×8 纹理逐像素绘制，再做染色与透明度处理。
// atlas 槽位布局（8 列）：
//   0-7   generic_0..7（烟、云、传送门、雪、灰烬）
//   8-15  glitter_0..7（末地烛）
//   16-23 spell_0..7（女巫）
//   24-49 sga_a..z（附魔台银河字母）
//   50 critical_hit  51 enchanted_hit  52 flame  53 soul_fire_flame
//   54 heart         55 note           56 angry  57 glint  58 lava
// 贴图取自本机 26.3 客户端 jar：assets/minecraft/textures/particle/。

(function () {
  const ATLAS_BASE64 = 'iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAERUlEQVR4nO1aP0tcQRCft1yadAEJnGAhAQslhaY78gWEKNwnSDApRCRt8EgpwTQpDARskjJNwOIF7KzCNUe0CIidhYVChBjEIjYTZs1e3u3b/7ueT+9++Ny3Ozu7M7OzM/veO4ABAiIiDKKSWGjDAuC6oJtctIfQi0qF0J1gY0xBN/XxVVIeT0WH61TCh24S3KaYiZ5BH9EzcZaV5pYFl/uE0qmd7m38SXBV28Q3Pqi8QC4JcJtgUihJEByiAohdPZk/i5YoQABd8PEJkqnoLEQBE62413S8Krrc1q86swniqoANNoF8eH3HMPEzudGUGwUtJH/KPD5j6Pq6jmHiz2DAYwAMehYYogoIyRop6UngknZ2dnZ4G5VVoztbTMtsoYv2YlklupNb2JhvOh2GHjDoMWAQs0Amdzad0nSnK9OpSjV5sb/Mr6qb+F3ks/Kj9ObUVZEUSDVu6DhMbvA9I9sm5pstQknZjX3HQgW0k5gQ6xlX5VlGxRzmYa4TqV4r2wZX8afeSvyRNuLJjvlOJivg+1xftcfQWqjrpESMUYeIBSbygND44HKCNI1li/C27MGgT9AJEOvuIq6EvjdkLhPECBg7Tuz8Ng9ktgliXTgVXI7cIcZiNoFtg8a87q4CWNXP6qlWX0evQZ/g8i3gKl0/dIsMDtAhj5vybNTTWIR8vnzJgzQGPJ72Yx7XMViqFUohWGygTCqMK3yNdl0eoOOvQSRM7wlcoOLVvRMU9yFvrZx5MMGPH/oJlyBnkq/mk4dT5FLfMWweYjuJDvN/qgiMns/7or3VainPEcX2Lp3/6ZG9ex+91fI8x/X1dfQyADp+NdZ9dtrY2OihU105zhIQo1aO7PMXVBnhx+Qk0qWrywZYXFz8bwT0/BGBqp/Lp2lTvTv2PiAZATR4dn6BZIRiGymKq6t4urzMlabr4+gov96OjKDKAFQ2m01eJnkYmpmZyUip6elp2N3d5XVT0CN6yuD0+/gYDre3oXN6au17cHDAy83NzbAfSqpQVJ7K4gqTkioPKEVrsfIv1XOIlX86P1+iqZT/eXHhJDuDBCiuvDBCEZ1Op2sUKqleAik+1ua3qm1Ain+6e4ffF7fBw729khcJ5V+dnNg9DCNjQKoswPf/efvy2i8bgPa/AN3LdNrvxUumn52doQiAUEUgZQAygiYQ0qqT4qpAKKBTnkCKU+ATwS8MH+5V03oRYPRvZWUF6aJ7sQJTU1NlZcfqcNvAyCXGx8eh0Whw5SkQfZ3NYW1tDXqMQKt//4HWC3pc2HKiqxLYwsIC1Ot1eLI1x6NwazXnhKOjo8sepHA+ifDoMcDYLPCS6rIhClEcluDGgLXbl0K/+PMc4LABb17PAbXRgWFiYgJg6VcGc3sZfP8GcLgFvKQ6tatARtDk8soiz3MeA8jleRr6FwNKcYBWPjCKVxrNZrOrsCoH3+YsADJu3OpF4i/ACLLN2wmpEQAAAABJRU5ErkJggg==';

  const ATLAS_SLOTS = {
    generic: 0,
    glitter: 8,
    spell: 16,
    sga: 24,
    critical_hit: 50,
    enchanted_hit: 51,
    flame: 52,
    soul_fire_flame: 53,
    heart: 54,
    note: 55,
    angry: 56,
    glint: 57,
    lava: 58,
  };

  const genericFrames = Array.from({ length: 8 }, (_, i) => ATLAS_SLOTS.generic + i);
  const glitterFrames = Array.from({ length: 8 }, (_, i) => ATLAS_SLOTS.glitter + i);
  const spellFrames = Array.from({ length: 8 }, (_, i) => ATLAS_SLOTS.spell + i);
  const sgaFrames = Array.from({ length: 26 }, (_, i) => ATLAS_SLOTS.sga + i);

  const STYLES = {
    crit: { frames: [ATLAS_SLOTS.critical_hit], tint: true, colors: ['#ffffff', '#fff1a8', '#ffb84d'], alpha: .95, drift: 13, size: 8, mode: 'fixed' },
    enchant: { frames: sgaFrames, tint: true, colors: ['#e9e4ff', '#c9b6ff', '#b18cff'], alpha: .95, drift: 10, size: 8, mode: 'cycle' },
    smoke: { frames: genericFrames.slice().reverse(), tint: true, colors: ['#8b8b8b', '#b0b0b0', '#6d6d6d'], alpha: .5, drift: 10, size: 10, mode: 'age' },
    large_smoke: { frames: genericFrames.slice().reverse(), tint: true, colors: ['#7e7e7e', '#a6a6a6', '#5f5f5f'], alpha: .42, drift: 12, size: 12, mode: 'age' },
    cloud: { frames: genericFrames.slice().reverse(), tint: true, colors: ['#f5f5f5', '#dedede', '#ffffff'], alpha: .5, drift: 8, size: 12, mode: 'age' },
    flame: { frames: [ATLAS_SLOTS.flame], tint: false, colors: ['#ffffff'], alpha: .95, drift: 12, size: 8, mode: 'fixed' },
    soul_fire_flame: { frames: [ATLAS_SLOTS.soul_fire_flame], tint: false, colors: ['#ffffff'], alpha: .95, drift: 12, size: 8, mode: 'fixed' },
    end_rod: { frames: glitterFrames.slice().reverse(), tint: true, colors: ['#f3ecff', '#d7c6ff', '#bfa5ff'], alpha: .95, drift: 7, size: 8, mode: 'age' },
    happy_villager: { frames: [ATLAS_SLOTS.glint], tint: false, colors: ['#ffffff'], alpha: .9, drift: 10, size: 8, mode: 'fixed' },
    composter: { frames: [ATLAS_SLOTS.glint], tint: false, colors: ['#ffffff'], alpha: .8, drift: 9, size: 7, mode: 'fixed' },
    portal: { frames: genericFrames, tint: true, colors: ['#d8b4fe', '#a855f7', '#c084fc'], alpha: .8, drift: 10, size: 9, mode: 'age' },
    witch: { frames: spellFrames.slice().reverse(), tint: true, colors: ['#e9d5ff', '#c084fc', '#a855f7'], alpha: .9, drift: 9, size: 8, mode: 'age' },
    note: { frames: [ATLAS_SLOTS.note], tint: true, colors: ['#fde68a', '#facc15', '#fef3c7'], alpha: .9, drift: 8, size: 8, mode: 'fixed' },
    heart: { frames: [ATLAS_SLOTS.heart], tint: false, colors: ['#ffffff'], alpha: .9, drift: 7, size: 9, mode: 'fixed' },
    angry_villager: { frames: [ATLAS_SLOTS.angry], tint: false, colors: ['#ffffff'], alpha: .8, drift: 8, size: 9, mode: 'fixed' },
    snowflake: { frames: genericFrames.slice().reverse(), tint: true, colors: ['#ffffff', '#e0f2fe', '#bae6fd'], alpha: .85, drift: 6, size: 8, mode: 'age' },
    white_ash: { frames: [ATLAS_SLOTS.generic], tint: true, colors: ['#f8fafc', '#e2e8f0', '#ffffff'], alpha: .7, drift: 6, size: 6, mode: 'fixed' },
    ash: { frames: [ATLAS_SLOTS.generic], tint: true, colors: ['#a8a29e', '#78716c', '#d6d3d1'], alpha: .6, drift: 6, size: 6, mode: 'fixed' },
  };

  const DEFAULT_STYLE = { frames: [ATLAS_SLOTS.generic], tint: true, colors: ['#5eead4', '#2dd4bf', '#99f6e4'], alpha: .8, drift: 9, size: 8, mode: 'fixed' };

  const atlas = new Image();
  atlas.src = 'data:image/png;base64,' + ATLAS_BASE64;
  let atlasReady = false;
  atlas.addEventListener('load', () => { atlasReady = true; });

  const tintCache = new Map();
  const visibility = new WeakMap();
  const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
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

  function tinted(slot, color) {
    if (!atlasReady) return null;
    const key = slot + '|' + color;
    const cached = tintCache.get(key);
    if (cached) return cached;
    const canvas = document.createElement('canvas');
    canvas.width = 8;
    canvas.height = 8;
    const g = canvas.getContext('2d');
    g.imageSmoothingEnabled = false;
    const sx = (slot % 8) * 8;
    const sy = Math.floor(slot / 8) * 8;
    g.drawImage(atlas, sx, sy, 8, 8, 0, 0, 8, 8);
    g.globalCompositeOperation = 'multiply';
    g.fillStyle = color;
    g.fillRect(0, 0, 8, 8);
    g.globalCompositeOperation = 'destination-in';
    g.drawImage(atlas, sx, sy, 8, 8, 0, 0, 8, 8);
    tintCache.set(key, canvas);
    return canvas;
  }

  function makeParticle(w, h, style) {
    return {
      x: rand(0, w),
      y: rand(0, h),
      vx: rand(-style.drift, style.drift),
      vy: rand(-style.drift * .7, style.drift * .2),
      color: style.colors[Math.floor(Math.random() * style.colors.length)],
      age: rand(0, 2.6),
      ttl: rand(1.6, 3.0),
      frameOffset: Math.floor(Math.random() * style.frames.length),
      scale: rand(.85, 1.15),
    };
  }

  function frameIndex(p, style, now) {
    if (style.mode === 'cycle') {
      return style.frames[(Math.floor(now / 180) + p.frameOffset) % style.frames.length];
    }
    if (style.mode === 'age') {
      const ratio = Math.max(0, Math.min(.999, p.age / p.ttl));
      return style.frames[Math.floor(ratio * style.frames.length)];
    }
    return style.frames[p.frameOffset % style.frames.length];
  }

  function drawParticle(ctx, p, style, now, alpha) {
    if (!atlasReady) return;
    const slot = frameIndex(p, style, now);
    const size = Math.max(4, Math.round(style.size * p.scale));
    const x = Math.round(p.x - size / 2);
    const y = Math.round(p.y - size / 2);
    ctx.globalAlpha = Math.max(0, Math.min(1, alpha * style.alpha));
    if (style.tint) {
      const tint = tinted(slot, p.color);
      if (tint) ctx.drawImage(tint, x, y, size, size);
    } else {
      const sx = (slot % 8) * 8;
      const sy = Math.floor(slot / 8) * 8;
      ctx.drawImage(atlas, sx, sy, 8, 8, x, y, size, size);
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
        ctx.fillRect((i * 17 + 5) % w, (i * 11 + 3) % h, 1, 1);
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
      const speed = rand(10, 28);
      out.push({
        x, y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed - 4,
        color: style.colors[Math.floor(Math.random() * style.colors.length)],
        age: 0,
        ttl: rand(.5, 1.0),
        frameOffset: Math.floor(Math.random() * style.frames.length),
        scale: rand(.8, 1.2),
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
          p.age += dt;
          if (p.age >= p.ttl) {
            p.age = 0;
            p.ttl = rand(1.6, 3.0);
            p.x = rand(0, width);
            p.y = rand(0, height);
            p.color = style.colors[Math.floor(Math.random() * style.colors.length)];
          }
          p.x += p.vx * dt;
          p.y += p.vy * dt;
          if (p.x < -6) p.x = width + 6;
          if (p.x > width + 6) p.x = -6;
          if (p.y < -6) p.y = height + 6;
          if (p.y > height + 6) p.y = -6;
        }
        drawParticle(ctx, p, style, now, 1);
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
        p.vy += 30 * dt;
        drawParticle(ctx, p, style, now, 1 - p.age / p.ttl);
      }
      canvas.__samsaraRaf = requestAnimationFrame(frame);
    }
    if (observer) observer.observe(canvas);
    visibility.set(canvas, true);
    canvas.__samsaraRaf = requestAnimationFrame(frame);
  }

  window.SamsaraParticles = { attach, styleFor };
})();
