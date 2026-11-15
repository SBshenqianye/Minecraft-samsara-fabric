#!/usr/bin/env node
// 旧内核迁移审计：确认 src/legacy-core 与 src/legacy 已删除，且没有残留全局/导入。
// 用法：node tools/agent/scan-legacy.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const srcDir = path.join(root, 'web-src/src');

const issues = [];

for (const dir of ['legacy-core', 'legacy']) {
  const full = path.join(srcDir, dir);
  if (fs.existsSync(full)) issues.push(`残留目录：web-src/src/${dir}`);
}

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return walk(full);
    return /\.(vue|js)$/.test(entry.name) ? [full] : [];
  });
}

if (fs.existsSync(srcDir)) {
  for (const file of walk(srcDir)) {
    const rel = path.relative(root, file).replaceAll('\\', '/');
    const text = fs.readFileSync(file, 'utf8');
    if (text.includes('window.Samsara')) issues.push(`${rel}: window.Samsara*`);
    if (text.includes('useLegacyPage')) issues.push(`${rel}: useLegacyPage`);
    if (/legacy-core\//.test(text) || /['"][^'"]*\/legacy\//.test(text)) {
      issues.push(`${rel}: 旧内核 import`);
    }
  }
}

if (issues.length) {
  console.error('旧内核审计失败：');
  for (const issue of issues) console.error('  - ' + issue);
  process.exit(1);
}
console.log('旧内核审计通过：legacy-core / legacy 已全部删除，没有 window.Samsara* / useLegacyPage 残留');
