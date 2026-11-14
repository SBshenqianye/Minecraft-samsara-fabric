#!/usr/bin/env node
// 单页面迁移的一键验证：web-src 全量检查 + 页面 section E2E。
// 用法：
//   node tools/agent/verify-page.mjs --page announcements --base http://127.0.0.1:8722 --token <token>
//   node tools/agent/verify-page.mjs --page announcements --no-e2e
//   node tools/agent/verify-page.mjs --list

import { spawnSync } from 'node:child_process';
import path from 'node:path';

const args = process.argv.slice(2);
function arg(name, fallback = '') {
  const index = args.indexOf('--' + name);
  if (index < 0) return fallback;
  const next = args[index + 1];
  return next && !next.startsWith('--') ? next : true;
}
function flag(name) {
  return args.includes('--' + name);
}

if (flag('help') || args.includes('-h')) {
  console.log('用法：node tools/agent/verify-page.mjs --page <page> [--base URL] [--token TOKEN] [--no-check] [--no-e2e]');
  console.log('      node tools/agent/verify-page.mjs --list');
  process.exit(0);
}

const root = process.cwd();
const page = String(arg('page', '')).trim();

if (flag('list')) {
  const result = spawnSync(process.execPath, [path.join(root, 'tools/e2e/run.mjs'), '--list'], {
    cwd: root,
    stdio: 'inherit',
  });
  process.exit(result.status || 0);
}

if (!page || page === 'true') {
  console.error('缺少 --page，例如：--page announcements');
  process.exit(1);
}

const base = String(arg('base', process.env.SAMSARA_WEB || 'http://127.0.0.1:8722'));
const token = String(arg('token', process.env.SAMSARA_TOKEN || ''));
const skipCheck = flag('no-check');
const skipE2E = flag('no-e2e');
if (skipCheck && skipE2E) {
  console.error('--no-check 与 --no-e2e 不能同时使用');
  process.exit(1);
}

let failed = false;

if (!skipCheck) {
  console.log('== npm run check（web-src） ==');
  const result = spawnSync('npm.cmd run check', {
    cwd: path.join(root, 'web-src'),
    stdio: 'inherit',
    shell: true,
  });
  if (result.status !== 0) failed = true;
}

if (!failed && !skipE2E) {
  if (!token) {
    console.error('缺少 --token（或 SAMSARA_TOKEN）；只跑检查可加 --no-e2e');
    process.exit(2);
  }
  console.log('== E2E section: ' + page + ' ==');
  const result = spawnSync(process.execPath, [
    path.join(root, 'tools/e2e/run.mjs'),
    '--base', base,
    '--token', token,
    '--section', page,
  ], {
    cwd: root,
    stdio: 'inherit',
  });
  if (result.status !== 0) failed = true;
}

if (failed) {
  console.error('\n验证失败：请先修复上方失败项。');
  process.exitCode = 1;
} else {
  console.log('\n验证通过：' + page);
}
