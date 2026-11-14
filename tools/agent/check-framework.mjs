#!/usr/bin/env node
// 框架守卫：阻止新页面重新引入旧命令式内核或绕过 useApi/useLegacyPage 约定。
// 用法：node tools/agent/check-framework.mjs（已接入 web-src 的 npm run check）

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const srcDir = path.join(root, 'web-src/src');
const webDir = path.join(root, 'mod/src/main/resources/web');

const LEGACY_CORE_IMPORT_ALLOW = new Set([
  'web-src/src/legacy/admin.js',
  'web-src/src/legacy/user.js',
  'web-src/src/pages/ConfigEditorPage.vue',
  'web-src/src/pages/IdentityPage.vue',
  'web-src/src/pages/LotteryPage.vue',
  'web-src/src/pages/MapPage.vue',
  'web-src/src/pages/ModConfigPage.vue',
  'web-src/src/pages/OutputPage.vue',
  'web-src/src/pages/ServerPage.vue',
  'web-src/src/pages/WorldStackPage.vue',
]);

const LEGACY_PAGE_ALLOW = new Set([
  'web-src/src/pages/ConfigEditorPage.vue',
  'web-src/src/pages/IdentityPage.vue',
  'web-src/src/pages/LotteryPage.vue',
  'web-src/src/pages/MapPage.vue',
  'web-src/src/pages/ModConfigPage.vue',
  'web-src/src/pages/OutputPage.vue',
  'web-src/src/pages/ServerPage.vue',
  'web-src/src/pages/WorldStackPage.vue',
]);

const LEGACY_IMPORT_ALLOW = new Set([
  'web-src/src/layouts/AdminLayout.vue',
  'web-src/src/pages/OutputPage.vue',
  'web-src/src/pages/UserPage.vue',
  'web-src/src/legacy/admin.js',
  'web-src/src/legacy/user.js',
  'web-src/src/composables/useLegacyPage.js',
]);

// 框架自身需要桥接旧全局，不属于“新页面依赖旧内核”。
const FRAMEWORK_META_ALLOW = new Set([
  'web-src/src/main.js',
  'web-src/src/layouts/AdminLayout.vue',
  'web-src/src/components/FrameworkDialogHost.vue',
  'web-src/src/composables/useLegacyPage.js',
]);

const violations = [];

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return walk(full);
    return /\.(vue|js|css)$/.test(entry.name) ? [full] : [];
  });
}

function relative(file) {
  return path.relative(root, file).replaceAll('\\', '/');
}

for (const file of walk(srcDir)) {
  const rel = relative(file);
  const text = fs.readFileSync(file, 'utf8');
  const isLegacyCore = rel.startsWith('web-src/src/legacy-core/');
  const isPage = rel.startsWith('web-src/src/pages/');
  const isComponent = rel.startsWith('web-src/src/components/');

  if (/<\/?LegacyPanel\b|import\s+LegacyPanel/.test(text) && !rel.endsWith('.css')) {
    violations.push(`${rel}: 禁止重新引入 LegacyPanel`);
  }

  if (/\.innerHTML\s*=|insertAdjacentHTML|document\.write\s*\(/.test(text)) {
    violations.push(`${rel}: 禁止 innerHTML / insertAdjacentHTML / document.write`);
  }

  if (!isLegacyCore
      && /(?:from\s+|import\s*\(\s*|import\s+)['"][^'"]*legacy-core\//.test(text)
      && !LEGACY_CORE_IMPORT_ALLOW.has(rel)) {
    violations.push(`${rel}: 新代码禁止直接 import legacy-core（先抽 model 或走 useLegacyPage）`);
  }

  if (rel !== 'web-src/src/composables/useLegacyPage.js'
      && text.includes('useLegacyPage') && !LEGACY_PAGE_ALLOW.has(rel)) {
    violations.push(`${rel}: useLegacyPage 仅允许现有复杂页面使用；新页面请写原生 Vue`);
  }

  if (!isLegacyCore
      && /(?:from\s+|import\s*\(\s*|import\s+)['"][^'"]*\/legacy\//.test(text)
      && !LEGACY_IMPORT_ALLOW.has(rel)) {
    violations.push(`${rel}: 禁止从 src/legacy/ 引入旧壳逻辑`);
  }

  if (!LEGACY_PAGE_ALLOW.has(rel) && !FRAMEWORK_META_ALLOW.has(rel)
      && !isLegacyCore && /\bwindow\.Samsara[A-Za-z]/.test(text)) {
    violations.push(`${rel}: 新页面禁止依赖 window.Samsara* 全局内核`);
  }

  if ((isPage || isComponent) && /\bfetch\s*\(/.test(text)) {
    violations.push(`${rel}: 页面/组件禁止直接 fetch；请使用 useApi`);
  }

  if (/['"]\/(legacy\.html|legacy-user\.html|user\.html|style\.css|app\.js|page-kit\.js)['"]/.test(text)
      && !isLegacyCore && !rel.endsWith('.css')) {
    violations.push(`${rel}: 引用了已删除的旧静态资源路径`);
  }
}

if (fs.existsSync(webDir)) {
  for (const entry of fs.readdirSync(webDir, { withFileTypes: true })) {
    if (entry.isFile() && entry.name.endsWith('.js')) {
      violations.push(`mod/src/main/resources/web/${entry.name}: 禁止新增旧静态脚本`);
    }
  }
}

if (violations.length) {
  console.error('框架守卫失败：');
  for (const item of violations) console.error('  - ' + item);
  process.exit(1);
}

console.log('框架守卫通过：没有新增 LegacyPanel / 旧静态脚本 / 直接 legacy-core 依赖');
