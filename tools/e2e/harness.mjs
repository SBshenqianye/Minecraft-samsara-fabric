// 零依赖 E2E 基础设施：参数解析、浏览器探测、CDP 客户端与结果统计。
// 业务场景留在 tools/web-e2e.mjs；这里只放可复用的浏览器/协议层。

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) continue;
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith('--')) {
      out[key] = next;
      i++;
    } else {
      out[key] = true;
    }
  }
  return out;
}

function firstExisting(candidates) {
  for (const candidate of candidates) {
    if (candidate && fs.existsSync(candidate)) return candidate;
  }
  return null;
}

export function browserPath(explicitBrowser) {
  if (explicitBrowser) return explicitBrowser;
  const pf = process.env.ProgramFiles || 'C:\\Program Files';
  const pf86 = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';
  const local = process.env.LOCALAPPDATA || '';
  return firstExisting([
    path.join(pf86, 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
    path.join(pf, 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
    path.join(local, 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
    path.join(pf, 'Google', 'Chrome', 'Application', 'chrome.exe'),
    path.join(pf86, 'Google', 'Chrome', 'Application', 'chrome.exe'),
    path.join(local, 'Google', 'Chrome', 'Application', 'chrome.exe'),
  ]);
}

export async function freePort() {
  const net = await import('node:net');
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
  });
}

export class CDP {
  constructor(url) {
    this.url = url;
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Map();
    this.ws = null;
  }

  open() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.url);
      const timer = setTimeout(() => reject(new Error('CDP 连接超时')), 10000);
      this.ws.onopen = () => { clearTimeout(timer); resolve(); };
      this.ws.onerror = (event) => {
        clearTimeout(timer);
        reject(new Error('CDP 连接失败: ' + event.message));
      };
      this.ws.onmessage = (event) => {
        let message;
        try { message = JSON.parse(event.data); } catch (e) { return; }
        if (message.id && this.pending.has(message.id)) {
          const { resolve: ok, reject: fail } = this.pending.get(message.id);
          this.pending.delete(message.id);
          if (message.error) fail(new Error(message.error.message || JSON.stringify(message.error)));
          else ok(message.result);
          return;
        }
        if (message.method) {
          const list = this.listeners.get(message.method) || [];
          this.listeners.set(message.method, []);
          for (const listener of list) listener(message.params);
        }
      };
    });
  }

  send(method, params = {}) {
    const id = this.nextId++;
    const payload = JSON.stringify({ id, method, params });
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(payload);
    });
  }

  once(method, timeoutMs = 15000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('等待 ' + method + ' 超时')), timeoutMs);
      const list = this.listeners.get(method) || [];
      list.push((params) => { clearTimeout(timer); resolve(params); });
      this.listeners.set(method, list);
    });
  }

  close() {
    try { this.ws.close(); } catch (e) { /* ignore */ }
  }
}

export function createReporter() {
  const results = [];
  function check(name, ok, detail = '') {
    results.push({ name, ok: Boolean(ok), detail });
    console.log((ok ? '  PASS  ' : '  FAIL  ') + name + (detail ? '  · ' + detail : ''));
  }
  function summary() {
    const failed = results.filter((result) => !result.ok);
    console.log('');
    console.log('结果：' + (results.length - failed.length) + ' / ' + results.length + ' 通过');
    if (failed.length) {
      console.log('失败：');
      for (const item of failed) {
        console.log('  - ' + item.name + (item.detail ? ' · ' + item.detail : ''));
      }
    }
    return failed.length;
  }
  return { results, check, summary };
}

/**
 * 启动 headless Edge/Chrome 并连上第一个 page target。
 * 调用方负责在 finally 里执行 closePage(session)。
 */
export async function launchPage({ explicitBrowser = null, profilePrefix = 'samsara-e2e-' } = {}) {
  if (typeof WebSocket !== 'function') {
    throw new Error('当前 Node 没有全局 WebSocket；需要 Node 22+（本项目使用 24）');
  }
  const executable = browserPath(explicitBrowser);
  if (!executable) throw new Error('找不到 Edge/Chrome；用 --browser <path> 指定');

  const port = await freePort();
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), profilePrefix));
  const child = spawn(executable, [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--window-size=1440,1000',
    '--remote-debugging-port=' + port,
    '--user-data-dir=' + profile,
    'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });

  let devtools = '';
  child.stderr.on('data', (chunk) => {
    const match = String(chunk).match(/DevTools listening on (ws:\/\/\S+)/);
    if (match) devtools = match[1];
  });

  // 新版 Edge 在部分环境不再把 DevTools 地址写到 stderr，直接探测 /json/version。
  for (let i = 0; i < 100 && !devtools; i++) {
    await sleep(100);
    if (devtools) break;
    try {
      const info = await fetch('http://127.0.0.1:' + port + '/json/version').then((r) => r.json());
      if (info && info.webSocketDebuggerUrl) devtools = info.webSocketDebuggerUrl;
    } catch (e) { /* retry */ }
  }
  if (!devtools) throw new Error('浏览器没有输出 DevTools 地址');

  let targets = [];
  for (let i = 0; i < 50; i++) {
    try {
      const response = await fetch('http://127.0.0.1:' + port + '/json/list');
      targets = await response.json();
      if (targets.some((target) => target.type === 'page')) break;
    } catch (e) { /* retry */ }
    await sleep(100);
  }
  const page = targets.find((target) => target.type === 'page');
  if (!page) throw new Error('找不到页面 target');

  const cdp = new CDP(page.webSocketDebuggerUrl);
  await cdp.open();
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');

  return { child, cdp, executable, profile, port };
}

export async function closePage({ child, cdp, profile }) {
  if (cdp) cdp.close();
  try { child?.kill(); } catch (e) { /* ignore */ }
  await sleep(300);
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch (e) { /* ignore */ }
}
