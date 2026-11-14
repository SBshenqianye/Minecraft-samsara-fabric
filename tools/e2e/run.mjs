#!/usr/bin/env node
// 按 section 运行 Web E2E。新迁移页面优先用这里，避免每次跑 270 条全量回归。
// 用法：
//   node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section announcements
//   node tools/e2e/run.mjs --list

import {
  closePage,
  createReporter,
  launchPage,
  parseArgs,
  sleep,
} from './harness.mjs';
import { sections } from './sections/index.mjs';

const args = parseArgs(process.argv.slice(2));
const base = (args.base || process.env.SAMSARA_WEB || 'http://127.0.0.1:8730').replace(/\/+$/, '');
const token = args.token || process.env.SAMSARA_TOKEN || 'testtoken';
const explicitBrowser = args.browser || process.env.SAMSARA_BROWSER;

if (args.list) {
  for (const name of Object.keys(sections)) {
    console.log(name + '  ' + (sections[name].description || ''));
  }
  process.exit(0);
}

const names = args.section && args.section !== 'all'
  ? String(args.section).split(',').map((name) => name.trim()).filter(Boolean)
  : Object.keys(sections);
for (const name of names) {
  if (!sections[name]) {
    console.error('未知 section: ' + name + '；可用：' + Object.keys(sections).join(', '));
    process.exit(1);
  }
}

const session = await launchPage({ explicitBrowser, profilePrefix: 'samsara-section-' });
const { cdp, executable, profile } = session;
const { check, summary } = createReporter();

async function evaluate(expression) {
  const result = await cdp.send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
    userGesture: true,
  });
  if (result.exceptionDetails) {
    throw new Error('页面脚本异常: '
      + (result.exceptionDetails.exception?.description || result.exceptionDetails.text));
  }
  return result.result.value;
}

async function until(expression, timeoutMs = 15000, label = expression) {
  const deadline = Date.now() + timeoutMs;
  let lastError = '';
  while (Date.now() < deadline) {
    try {
      if (await evaluate('Boolean(' + expression + ')')) return true;
    } catch (error) {
      lastError = error.message;
    }
    await sleep(120);
  }
  throw new Error('等待超时: ' + label + (lastError ? ' · ' + lastError : ''));
}

async function navigate(path) {
  const loaded = cdp.once('Page.loadEventFired', 20000).catch(() => null);
  await cdp.send('Page.navigate', { url: base + path });
  await loaded;
  await until("document.readyState === 'complete'", 15000, '页面加载');
}

console.log('samsara · Web E2E section');
console.log('base   : ' + base);
console.log('browser: ' + executable);
console.log('profile: ' + profile);
console.log('sections: ' + names.join(', '));
console.log('');

const ctx = {
  base,
  token,
  cdp,
  check,
  evaluate,
  until,
  navigate,
  sleep,
};

try {
  // section 测试默认注入 token；全量 web-e2e.mjs 仍覆盖真实 token 对话框流程。
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `
      sessionStorage.setItem('samsara-token', ${JSON.stringify(token)});
      window.__e2eErrors = [];
      addEventListener('error', (event) => window.__e2eErrors.push(
        String(event.message) + (event.error && event.error.stack ? '\\n' + event.error.stack : '')));
      addEventListener('unhandledrejection', (event) => window.__e2eErrors.push(
        String(event.reason) + (event.reason && event.reason.stack ? '\\n' + event.reason.stack : '')));
    `,
  });

  for (const name of names) {
    console.log('== ' + name + ' ==');
    await sections[name].run(ctx);
    console.log('');
  }
} catch (error) {
  console.error('E2E section 执行失败：' + (error?.stack || error));
  process.exitCode = 1;
} finally {
  await closePage(session);
}

if (summary() > 0) process.exitCode = 1;
