#!/usr/bin/env node
// 框架守卫：禁止重新引入旧命令式内核、旧静态脚本与全局 window.Samsara*。
// 用法：node tools/agent/check-framework.mjs（已接入 web-src 的 npm run check）

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const srcDir = path.join(root, 'web-src/src');
const webDir = path.join(root, 'mod/src/main/resources/web');

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

// 1. 旧内核目录必须不存在。
for (const legacyDir of [path.join(srcDir, 'legacy-core'), path.join(srcDir, 'legacy')]) {
  if (fs.existsSync(legacyDir)) {
    violations.push(`${relative(legacyDir)}: 旧内核目录必须删除，算法迁到 pages/<page>/`);
  }
}

if (fs.existsSync(srcDir)) {
  for (const file of walk(srcDir)) {
    const rel = relative(file);
    const text = fs.readFileSync(file, 'utf8');
    const isPage = rel.startsWith('web-src/src/pages/');
    const isComponent = rel.startsWith('web-src/src/components/');

    if (/<\/?LegacyPanel\b|import\s+LegacyPanel/.test(text) && !rel.endsWith('.css')) {
      violations.push(`${rel}: 禁止重新引入 LegacyPanel`);
    }

    if (/useLegacyPage/.test(text)) {
      violations.push(`${rel}: 禁止使用 useLegacyPage；页面必须写原生 Vue`);
    }

    if (/(?:from\s+|import\s*\(\s*|import\s+)['"][^'"]*legacy-core\//.test(text)) {
      violations.push(`${rel}: 禁止 import legacy-core`);
    }

    if (/(?:from\s+|import\s*\(\s*|import\s+)['"][^'"]*\/legacy\//.test(text)) {
      violations.push(`${rel}: 禁止 import src/legacy/`);
    }

    if (/\bwindow\.Samsara[A-Za-z]/.test(text)) {
      violations.push(`${rel}: 禁止依赖 window.Samsara* 全局`);
    }

    if (/\.innerHTML\s*=|insertAdjacentHTML|document\.write\s*\(/.test(text)) {
      violations.push(`${rel}: 禁止 innerHTML / insertAdjacentHTML / document.write`);
    }

    if ((isPage || isComponent) && /\bfetch\s*\(/.test(text)) {
      violations.push(`${rel}: 页面/组件禁止直接 fetch；请使用 useApi`);
    }

    if (/['"]\/(legacy\.html|legacy-user\.html|user\.html|style\.css|app\.js|page-kit\.js)['"]/.test(text)
        && !rel.endsWith('.css')) {
      violations.push(`${rel}: 引用了已删除的旧静态资源路径`);
    }
  }
}

// 2. 禁止重新新增旧静态脚本。
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

console.log('框架守卫通过：没有旧内核目录 / window.Samsara* / 旧静态脚本');
