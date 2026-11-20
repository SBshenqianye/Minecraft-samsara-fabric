#!/usr/bin/env node
// 全站 UX 基线巡检：390px 窄屏溢出 + 暗色对比度 + 键盘可达性。
// 用法：node tools/agent/ux-sweep.mjs --base http://127.0.0.1:8722 --token <token>

import { closePage, launchPage, parseArgs, sleep } from '../e2e/harness.mjs';

const args = parseArgs(process.argv.slice(2));
const base = (args.base || 'http://127.0.0.1:8722').replace(/\/+$/, '');
const token = args.token || process.env.SAMSARA_TOKEN || 'testtoken';

const ROUTES = [
  '/', '/samsara/economy', '/samsara/economy/overview', '/samsara/economy/current',
  '/samsara/economy/values', '/samsara/economy/fund', '/samsara/economy/fund-inventory',
  '/samsara/economy/lottery', '/samsara/economy/insurance', '/samsara/economy/history',
  '/samsara/sky/track', '/samsara/sky/diff', '/samsara/sky/containers',
  '/samsara/sky/inventory', '/samsara/sky/web-inventory',
  '/samsara/config', '/samsara/config/editor', '/samsara/config/advanced',
  '/samsara/config/permissions', '/samsara/config/output', '/samsara/config/claim-range',
  '/samsara/world', '/samsara/world/stack', '/samsara/world/map',
  '/samsara/community', '/samsara/community/announcements', '/samsara/community/identity',
  '/samsara/system', '/samsara/system/audit', '/samsara/system/storage',
  '/vanilla', '/vanilla/config', '/vanilla/backup', '/vanilla/console', '/vanilla/audit',
  '/server', '/server/config', '/server/backup', '/server/console', '/server/audit',
  '/containers', '/identity-inventory-inline', '/identity-inventory', '/user',
  '/lottery', '/track', '/diff', '/sky/containers', '/inventory', '/config-editor',
  '/permissions', '/announcements', '/mods/puppet-player-config.json',
];

const session = await launchPage({ profilePrefix: 'samsara-ux-' });
const { cdp } = session;

async function evaluate(expression) {
  const result = await cdp.send('Runtime.evaluate', {
    expression, awaitPromise: true, returnByValue: true, userGesture: true,
  });
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
  }
  return result.result.value;
}

await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `sessionStorage.setItem('samsara-token', ${JSON.stringify(token)});`
    + 'window.__e2e = true; window.__uxErrors = [];'
    + "addEventListener('error', (e) => window.__uxErrors.push(String(e.message)));",
});

await cdp.send('Emulation.setDeviceMetricsOverride', {
  width: 390, height: 844, deviceScaleFactor: 2, mobile: true,
});
await cdp.send('Emulation.setEmulatedMedia', {
  features: [{ name: 'prefers-color-scheme', value: 'dark' }, { name: 'prefers-reduced-motion', value: 'reduce' }],
});

const results = [];
for (const route of ROUTES) {
  const loaded = cdp.once('Page.loadEventFired', 20000).catch(() => null);
  await cdp.send('Page.navigate', { url: base + route });
  await loaded;
  await sleep(1400);
  const data = await evaluate(`(() => {
    const vw = window.innerWidth;
    const offenders = [];
    for (const el of document.querySelectorAll('.content *')) {
      const style = getComputedStyle(el);
      if (style.display === 'none' || style.visibility === 'hidden' || style.position === 'fixed') continue;
      const rect = el.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) continue;
      if (rect.right > vw + 1 || el.scrollWidth > el.clientWidth + 2) {
        offenders.push((el.className && String(el.className).split(' ')[0]) || el.tagName);
      }
      if (offenders.length >= 6) break;
    }
    const focusables = [...document.querySelectorAll(
      'a[href],button,input,select,textarea,[tabindex]')]
      .filter((el) => getComputedStyle(el).display !== 'none' && el.getBoundingClientRect().width > 0);
    const badTabIndex = focusables.filter((el) => Number(el.getAttribute('tabindex')) > 0).length;

    const parse = (value) => {
      const m = /rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)(?:,\\s*([\\d.]+))?\\)/.exec(value || '');
      return m ? [Number(m[1]), Number(m[2]), Number(m[3]), m[4] == null ? 1 : Number(m[4])] : null;
    };
    const lum = (rgb) => {
      const f = rgb.slice(0, 3).map((v) => {
        const s = v / 255;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
      });
      return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2];
    };
    const blend = (top, bottom) => {
      const a = top[3] ?? 1;
      return [
        Math.round(top[0] * a + bottom[0] * (1 - a)),
        Math.round(top[1] * a + bottom[1] * (1 - a)),
        Math.round(top[2] * a + bottom[2] * (1 - a)),
      ];
    };
    const bodyBg = parse(getComputedStyle(document.body).backgroundColor) || [255, 255, 255, 1];
    const bgOf = (el) => {
      const layers = [];
      let node = el;
      while (node && node !== document.documentElement) {
        const style = getComputedStyle(node);
        if (style.backgroundImage && style.backgroundImage !== 'none') return null;
        const bg = parse(style.backgroundColor);
        if (bg && bg[3] > 0) layers.push(bg);
        if (bg && bg[3] >= 1) break;
        node = node.parentElement;
      }
      let result = [bodyBg[0], bodyBg[1], bodyBg[2]];
      for (let i = layers.length - 1; i >= 0; i--) result = blend(layers[i], result);
      return result;
    };
    const lowContrast = [];
    const samples = [...document.querySelectorAll('h1,h2,h3,p,span,button,a,label')].slice(0, 300);
    for (const el of samples) {
      const text = (el.textContent || '').trim();
      if (!text || text.length > 80) continue;
      const style = getComputedStyle(el);
      if (style.display === 'none' || style.visibility === 'hidden') continue;
      // Element Plus 分段选择器的选中底色由独立覆盖层绘制，逐项算对比度会误报。
      if (String(el.className).includes('el-segmented__item')) continue;
      const fg = parse(style.color);
      if (!fg || fg[3] === 0) continue;
      const bg = bgOf(el);
      if (!bg) continue;
      const l1 = lum(fg);
      const l2 = lum(bg);
      const ratio = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
      if (ratio < 3.0) {
        lowContrast.push({ cls: (el.className && String(el.className).split(' ')[0]) || el.tagName,
          ratio: Math.round(ratio * 100) / 100, color: style.color, bg: 'rgb(' + bg.join(',') + ')' });
      }
      if (lowContrast.length >= 8) break;
    }
    return {
      vw,
      scrollWidth: document.documentElement.scrollWidth,
      overflow: document.documentElement.scrollWidth > vw + 1,
      offenders: [...new Set(offenders)],
      focusables: focusables.length,
      badTabIndex,
      lowContrast,
      errors: window.__uxErrors || [],
      bodyBg: getComputedStyle(document.body).backgroundColor,
      panelBg: document.querySelector('.panel') ? getComputedStyle(document.querySelector('.panel')).backgroundColor : '',
    };
  })()`);
  results.push({ route, ...data });
  console.log(JSON.stringify({ route, overflow: data.overflow, offenders: data.offenders,
    focusables: data.focusables, badTabIndex: data.badTabIndex, errors: data.errors.length,
    lowContrast: data.lowContrast }));
}

await closePage(session);
const failed = results.filter((row) => row.overflow || row.errors.length || row.badTabIndex
  || row.lowContrast.length);
console.log('\n== 汇总 ==');
console.log('UX_SUMMARY routes=' + results.length + ' problems=' + failed.length);
for (const row of failed) {
  console.log('- ' + row.route + ' overflow=' + row.overflow
    + ' offenders=' + row.offenders.join(',') + ' errors=' + row.errors.slice(0, 2).join(' | ')
    + ' lowContrast=' + JSON.stringify(row.lowContrast));
}
process.exit(failed.length ? 1 : 0);
