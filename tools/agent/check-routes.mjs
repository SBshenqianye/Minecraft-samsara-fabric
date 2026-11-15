#!/usr/bin/env node
/**
 * 路由 ↔ 静态资源白名单一致性检查。
 *
 * 背景：前端新增/保留一条 SPA 路径（`web-src/src/router.js` 的 path 或 alias）时，
 * 服务端必须在 `WebSecurity.staticResource` 里同样列出，否则用户直接打开该地址
 * 会得到 404（页面本身能跑，但书签/旧链接失效）。2026-11-15 的
 * `/identity-inventory`、`/sky/containers` 就是这样漏出去的。
 *
 * 规则：
 * - router 里的静态路径/别名（不含 `:` 参数）必须出现在白名单里 → 缺失即失败。
 * - 白名单里多出来的 SPA 路径 → 只警告（可能是刻意保留的旧链接）。
 * - 动态路由（`/mods/:modId`）由 WebSecurity 的正则分支覆盖，跳过。
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const repo = path.resolve(import.meta.dirname, '../..');
const routerFile = path.join(repo, 'web-src/src/router.js');
const securityFile = path.join(
  repo,
  'mod/src/main/java/com/shenqianye/samsara/web/WebSecurity.java',
);

function fail(message) {
  console.error('[check-routes] ' + message);
  process.exit(1);
}

for (const file of [routerFile, securityFile]) {
  if (!fs.existsSync(file)) fail('缺少文件: ' + file);
}

const normalize = (value) => {
  if (!value) return '';
  let p = value.trim();
  if (!p.startsWith('/')) p = '/' + p;
  if (p.length > 1 && p.endsWith('/')) p = p.slice(0, -1);
  return p;
};

const routerSource = fs.readFileSync(routerFile, 'utf8');
const routes = new Set();
const add = (value) => {
  const p = normalize(value);
  if (!p || p === '/') return;
  routes.add(p);
};

for (const m of routerSource.matchAll(/path:\s*'([^']+)'/g)) add(m[1]);
for (const m of routerSource.matchAll(/alias:\s*'([^']+)'/g)) add(m[1]);
for (const m of routerSource.matchAll(/alias:\s*\[([^\]]+)\]/g)) {
  for (const q of m[1].matchAll(/'([^']+)'/g)) add(q[1]);
}
// 简易 helper：vanilla('...') / legacyX('path', ['alias'], ...)
for (const m of routerSource.matchAll(/(?:vanilla|legacyConfig|legacyWorld|legacyCommunity)\('([^']+)'/g)) {
  add(m[1]);
}
for (const m of routerSource.matchAll(/(?:vanilla|legacyConfig|legacyWorld|legacyCommunity)\('([^']+)',\s*\[([^\]]+)\]/g)) {
  for (const q of m[2].matchAll(/'([^']+)'/g)) add(q[1]);
}

const securitySource = fs.readFileSync(securityFile, 'utf8');
const whitelist = new Set();
for (const m of securitySource.matchAll(/"(\/[^"]*)"/g)) {
  const p = normalize(m[1]);
  if (!p) continue;
  if (p.startsWith('/api') || p.startsWith('/vue') || p.startsWith('/web')
      || p.startsWith('/items') || p.startsWith('/ui') || p.startsWith('/armor')) {
    continue;
  }
  whitelist.add(p);
}

const isDynamic = (p) => p.includes(':');
const missing = [...routes].filter((p) => !isDynamic(p) && !whitelist.has(p)).sort();
const extra = [...whitelist]
  .filter((p) => p !== '/' && p !== '/index.html' && p !== '/openapi.json' && !routes.has(p))
  // 工作区/分区根路径（如 /samsara、/samsara/sky）是导航入口，不是独立页面。
  .filter((p) => ![...routes].some((r) => r.startsWith(p + '/')))
  .sort();

if (extra.length) {
  console.warn('[check-routes] 白名单里多出的路径（可能是有意保留的旧链接）：');
  for (const p of extra) console.warn('  - ' + p);
}

if (missing.length) {
  console.error('[check-routes] router 里有、WebSecurity 白名单里没有（直达会 404）：');
  for (const p of missing) console.error('  - ' + p);
  console.error('修法：把路径补到 WebSecurity.staticResource 的 switch，或删除该路由/别名。');
  process.exit(1);
}

console.log(`[check-routes] OK：router ${routes.size} 条路径/别名，白名单 ${whitelist.size} 条。`);
