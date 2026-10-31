'use strict';

// 轻量粒子预览：不加载任何贴图/第三方库，用 Canvas 2D 画带景深的粒子。
// 同一个 canvas 重复 attach 会取消旧动画，避免切换下拉框后叠加。

(function () {
  const STYLES = {
    crit: { colors: ['#fff7c2', '#ffe066', '#ffc857'], shape: 'star', glow: 1.0, drift: 22 },
    enchant: { colors: ['#b5f5ff', '#67e8f9', '#38bdf8'], shape: 'glyph', glow: .9, drift: 16 },
    smoke: { colors: ['#a3a3a3', '#737373', '#d4d4d4'], shape: 'blob', glow: .25, drift: 20 },
    large_smoke: { colors: ['#8a8a8a', '#525252', '#c4c4c4'], shape: 'blob', glow: .2, drift: 26 },
    cloud: { colors: ['#e5e7eb', '#cbd5e1', '#f8fafc'], shape: 'blob', glow: .15, drift: 18 },
    flame: { colors: ['#ffd166', '#fb923c', '#ef4444'], shape: 'flame', glow: 1.0, drift: 26 },
    soul_fire_flame: { colors: ['#99f6e4', '#2dd4bf', '#0ea5e9'], shape: 'flame', glow: 1.0, drift: 24 },
    end_rod: { colors: ['#f5f3ff', '#ddd6fe', '#c4b5fd'], shape: 'streak', glow: .95, drift: 14 },
    happy_villager: { colors: ['#86efac', '#22c55e', '#bbf7d0'], shape: 'star', glow: .9, drift: 20 },
    composter: { colors: ['#a3e635', '#65a30d', '#d9f99d'], shape: 'dot', glow: .5, drift: 18 },
    portal: { colors: ['#d8b4fe', '#a855f7', '#7c3aed'], shape: 'swirl', glow: 1.0, drift: 22 },
    witch: { colors: ['#c084fc', '#7e22ce', '#e9d5ff'], shape: 'dot', glow: .7, drift: 20 },
    note: { colors: ['#fef08a', '#facc15', '#fde68a'], shape: 'note', glow: .8, drift: 18 },
    heart: { colors: ['#fb7185', '#e11d48', '#fda4af'], shape: 'heart', glow: .8, drift: 16 },
    angry_villager: { colors: ['#9ca3af', '#4b5563', '#d1d5db'], shape: 'blob', glow: .2, drift: 16 },
    snowflake: { colors: ['#f0f9ff', '#bae6fd', '#e0f2fe'], shape: 'flake', glow: .7, drift: 14 },
    white_ash: { colors: ['#f8fafc', '#e2e8f0', '#cbd5e1'], shape: 'flake', glow: .45, drift: 12 },
    ash: { colors: ['#a8a29e', '#78716c', '#d6d3d1'], shape: 'dot', glow: .2, drift: 12 },
  };

  const DEFAULT_STYLE = { colors: ['#5eead4', '#2dd4bf', '#99f6e4'], shape: 'dot', glow: .6, drift: 16 };
  const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const visibility = new WeakMap();
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

  function makeParticle(w, h, style) {
    return {
      x: rand(6, w - 6),
      y: rand(6, h - 6),
      z: rand(.35, 1.35),
      vx: rand(-style.drift, style.drift) * .018,
      vy: rand(-style.drift * .7, style.drift * .2) * .018,
      life: rand(0, Math.PI * 2),
      spin: rand(-.08, .08),
      color: style.colors[Math.floor(Math.random() * style.colors.length)],
      size: rand(1.4, 3.4),
    };
  }

  function drawStar(ctx, x, y, r, color, glow) {
    ctx.save();
    ctx.translate(x, y);
    if (glow > .1) {
      const g = ctx.createRadialGradient(0, 0, 0, 0, 0, r * 2.2);
      g.addColorStop(0, color + 'aa');
      g.addColorStop(.55, color + '33');
      g.addColorStop(1, 'transparent');
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.arc(0, 0, r * 2.2, 0, Math.PI * 2); ctx.fill();
    }
    ctx.fillStyle = color;
    ctx.beginPath();
    for (let i = 0; i < 8; i++) {
      const angle = i * Math.PI / 4;
      const radius = i % 2 === 0 ? r * 1.5 : r * .28;
      const px = Math.cos(angle) * radius;
      const py = Math.sin(angle) * radius;
      if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    }
    ctx.closePath(); ctx.fill();
    ctx.restore();
  }

  function drawBlob(ctx, x, y, r, color, alpha, spread) {
    const radius = r * (spread || 2.0);
    const g = ctx.createRadialGradient(x, y, 0, x, y, radius);
    g.addColorStop(0, color + 'dd');
    g.addColorStop(.55, color + '66');
    g.addColorStop(1, 'transparent');
    ctx.globalAlpha = alpha;
    ctx.fillStyle = g;
    ctx.beginPath(); ctx.arc(x, y, radius, 0, Math.PI * 2); ctx.fill();
    ctx.globalAlpha = 1;
  }

  function drawHeart(ctx, x, y, r, color) {
    ctx.save();
    ctx.translate(x, y);
    ctx.scale(.1 * r, .1 * r);
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.moveTo(0, 3);
    ctx.bezierCurveTo(-8, -5, -5, -11, 0, -6);
    ctx.bezierCurveTo(5, -11, 8, -5, 0, 3);
    ctx.closePath(); ctx.fill();
    ctx.restore();
  }

  function drawNote(ctx, x, y, r, color) {
    ctx.save();
    ctx.translate(x, y);
    ctx.fillStyle = color;
    ctx.font = `${Math.round(r * 4)}px system-ui, sans-serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('♪', 0, 0);
    ctx.restore();
  }

  function drawFlake(ctx, x, y, r, color, angle) {
    ctx.save();
    ctx.translate(x, y);
    ctx.rotate(angle);
    ctx.strokeStyle = color;
    ctx.lineWidth = Math.max(.6, r * .38);
    ctx.lineCap = 'round';
    for (let i = 0; i < 3; i++) {
      const a = i * Math.PI / 3;
      ctx.beginPath();
      ctx.moveTo(-Math.cos(a) * r * 1.8, -Math.sin(a) * r * 1.8);
      ctx.lineTo(Math.cos(a) * r * 1.8, Math.sin(a) * r * 1.8);
      ctx.stroke();
    }
    ctx.restore();
  }

  function drawParticle(ctx, p, style, alphaScale) {
    const scale = alphaScale == null ? 1 : alphaScale;
    const alpha = Math.max(0, Math.min(1, (.25 + p.z * .65) * scale));
    ctx.globalAlpha = alpha;
    const r = p.size * p.z;
    switch (style.shape) {
      case 'star':
        drawStar(ctx, p.x, p.y, r * 1.5, p.color, style.glow);
        break;
      case 'flame':
        drawBlob(ctx, p.x, p.y - r * .4, r * 1.1, p.color, alpha * .9);
        ctx.fillStyle = p.color;
        ctx.beginPath();
        ctx.moveTo(p.x, p.y - r * 2.1);
        ctx.quadraticCurveTo(p.x + r * 1.5, p.y, p.x, p.y + r * 1.2);
        ctx.quadraticCurveTo(p.x - r * 1.5, p.y, p.x, p.y - r * 2.1);
        ctx.fill();
        break;
      case 'streak':
        drawBlob(ctx, p.x, p.y, r * .75, p.color, alpha * .7);
        ctx.strokeStyle = p.color;
        ctx.lineWidth = Math.max(.7, r * .45);
        ctx.beginPath();
        ctx.moveTo(p.x, p.y - r * 2.8);
        ctx.lineTo(p.x, p.y + r * 1.6);
        ctx.stroke();
        break;
      case 'heart':
        drawHeart(ctx, p.x, p.y, r * 1.8, p.color);
        break;
      case 'note':
        drawNote(ctx, p.x, p.y, r, p.color);
        break;
      case 'flake':
        drawFlake(ctx, p.x, p.y, r * 1.3, p.color, p.life);
        break;
      case 'swirl':
        drawBlob(ctx, p.x, p.y, r * .9, p.color, alpha * .8);
        ctx.strokeStyle = p.color;
        ctx.lineWidth = Math.max(.6, r * .3);
        ctx.beginPath();
        for (let i = 0; i < 8; i++) {
          const a = p.life + i * .5;
          const rr = r * (1 + i * .3);
          const px = p.x + Math.cos(a) * rr;
          const py = p.y + Math.sin(a) * rr;
          if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
        }
        ctx.stroke();
        break;
      case 'glyph':
        drawBlob(ctx, p.x, p.y, r * .8, p.color, alpha * .7);
        ctx.strokeStyle = p.color;
        ctx.lineWidth = Math.max(.6, r * .3);
        ctx.beginPath();
        ctx.arc(p.x, p.y, r * 1.9, p.life, p.life + Math.PI * 1.4);
        ctx.stroke();
        break;
      case 'blob':
        drawBlob(ctx, p.x, p.y, r * 1.2, p.color, alpha * .46, 1.55);
        ctx.fillStyle = p.color + '66';
        ctx.beginPath(); ctx.arc(p.x, p.y, r * 1.05, 0, Math.PI * 2); ctx.fill();
        break;
      default:
        drawBlob(ctx, p.x, p.y, r * .9, p.color, alpha * .75);
        ctx.fillStyle = p.color;
        ctx.beginPath(); ctx.arc(p.x, p.y, r * .65, 0, Math.PI * 2); ctx.fill();
    }
    ctx.globalAlpha = 1;
  }

  function paintBackground(ctx, width, height, theme) {
    if (theme === 'day') {
      const sky = ctx.createLinearGradient(0, 0, 0, height);
      sky.addColorStop(0, '#5a9cf8');
      sky.addColorStop(.62, '#9ecbff');
      sky.addColorStop(1, '#e5f3ff');
      ctx.fillStyle = sky;
      ctx.fillRect(0, 0, width, height);
      const sun = ctx.createRadialGradient(width * .82, height * .18, 0, width * .82, height * .18, width * .22);
      sun.addColorStop(0, 'rgba(255,255,235,.95)');
      sun.addColorStop(.35, 'rgba(255,244,190,.55)');
      sun.addColorStop(1, 'rgba(255,244,190,0)');
      ctx.fillStyle = sun;
      ctx.fillRect(0, 0, width, height);
      return;
    }
    if (theme === 'night') {
      ctx.fillStyle = '#01030a';
      ctx.fillRect(0, 0, width, height);
      ctx.fillStyle = 'rgba(255,255,255,.45)';
      for (let i = 0; i < 9; i++) {
        const x = (i * 37 + 13) % width;
        const y = (i * 19 + 7) % height;
        ctx.fillRect(x, y, 1, 1);
      }
      return;
    }
    const bg = ctx.createRadialGradient(width * .5, height * .55, 0, width * .5, height * .55, width * .7);
    bg.addColorStop(0, 'rgba(255,255,255,.06)');
    bg.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = bg;
    ctx.fillRect(0, 0, width, height);
  }

  function makeBurst(style, x, y) {
    const out = [];
    const count = 14;
    for (let i = 0; i < count; i++) {
      const angle = (i / count) * Math.PI * 2 + rand(-.2, .2);
      const speed = rand(.08, .28);
      out.push({
        x, y,
        z: rand(.9, 1.4),
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed - .04,
        life: rand(0, Math.PI * 2),
        spin: rand(-.08, .08),
        color: style.colors[Math.floor(Math.random() * style.colors.length)],
        size: rand(1.8, 4.2),
        age: 0,
        ttl: rand(.7, 1.25),
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
    const ctx = canvas.getContext('2d');
    const style = styleFor(name);
    const width = canvas.clientWidth || 132;
    const height = canvas.clientHeight || 52;
    const dpr = Math.min(3, Math.max(2, window.devicePixelRatio || 1));
    canvas.width = Math.max(1, Math.round(width * dpr));
    canvas.height = Math.max(1, Math.round(height * dpr));
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    const particles = [];
    const count = 18;
    for (let i = 0; i < count; i++) particles.push(makeParticle(width, height, style));
    const burst = [];
    canvas.style.cursor = 'crosshair';
    if (canvas.__samsaraPointer) {
      canvas.removeEventListener('pointerdown', canvas.__samsaraPointer);
    }
    canvas.__samsaraPointer = (event) => {
      const rect = canvas.getBoundingClientRect();
      const x = Math.max(2, Math.min(width - 2, event.clientX - rect.left));
      const y = Math.max(2, Math.min(height - 2, event.clientY - rect.top));
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
      const visible = visibility.get(canvas) !== false;
      if (!visible) {
        canvas.__samsaraRaf = requestAnimationFrame(frame);
        return;
      }
      const dt = Math.min(50, last ? now - last : 16);
      last = now;
      ctx.clearRect(0, 0, width, height);
      paintBackground(ctx, width, height, opts.background);

      for (const p of particles) {
        if (!reduceMotion) {
          p.x += p.vx * dt;
          p.y += p.vy * dt;
          p.life += p.spin * dt * .04;
          if (p.x < -8) p.x = width + 8;
          if (p.x > width + 8) p.x = -8;
          if (p.y < -8) p.y = height + 8;
          if (p.y > height + 8) p.y = -8;
        }
        drawParticle(ctx, p, style);
      }
      for (let i = burst.length - 1; i >= 0; i--) {
        const p = burst[i];
        p.age += dt / 1000;
        if (p.age >= p.ttl) {
          burst.splice(i, 1);
          continue;
        }
        p.x += p.vx * dt;
        p.y += p.vy * dt;
        p.vy += .02 * (dt / 1000);
        drawParticle(ctx, p, style, 1 - p.age / p.ttl);
      }
      canvas.__samsaraRaf = requestAnimationFrame(frame);
    }
    if (observer) observer.observe(canvas);
    visibility.set(canvas, true);
    canvas.__samsaraRaf = requestAnimationFrame(frame);
  }

  window.SamsaraParticles = { attach, styleFor };
})();
