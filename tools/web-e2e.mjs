#!/usr/bin/env node
// 零依赖 Web 冒烟 E2E：用系统 Edge/Chrome 的 headless + CDP 驱动真实页面。
//
// 用法（先启动测试服）：
//   node tools/web-e2e.mjs
//   node tools/web-e2e.mjs --base http://127.0.0.1:8730 --token testtoken
//
// 只读/可回退：不会保存配置、不会创建/恢复备份；玩家选择测试会新增后立即删除草稿。
// 退出码 0 表示全部通过。

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const args = parseArgs(process.argv.slice(2));
const base = (args.base || process.env.SAMSARA_WEB || 'http://127.0.0.1:8730').replace(/\/+$/, '');
const token = args.token || process.env.SAMSARA_TOKEN || 'testtoken';
const explicitBrowser = args.browser || process.env.SAMSARA_BROWSER;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function parseArgs(argv) {
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

function browserPath() {
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

async function freePort() {
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

class CDP {
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
      this.ws.onerror = (event) => { clearTimeout(timer); reject(new Error('CDP 连接失败: ' + event.message)); };
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

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: Boolean(ok), detail });
  console.log((ok ? '  PASS  ' : '  FAIL  ') + name + (detail ? '  · ' + detail : ''));
}

async function main() {
  if (typeof WebSocket !== 'function') {
    throw new Error('当前 Node 没有全局 WebSocket；需要 Node 22+（本项目使用 24）');
  }
  const executable = browserPath();
  if (!executable) {
    throw new Error('找不到 Edge/Chrome；用 --browser <path> 指定');
  }
  const port = await freePort();
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'samsara-e2e-'));
  console.log('samsara · Web 冒烟 E2E');
  console.log('base   : ' + base);
  console.log('browser: ' + executable);
  console.log('profile: ' + profile);
  console.log('');

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

  let cdp = null;
  try {
    for (let i = 0; i < 100 && !devtools; i++) await sleep(100);
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

    cdp = new CDP(page.webSocketDebuggerUrl);
    await cdp.open();
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');

    async function evaluate(expression, awaitPromise = true) {
      const result = await cdp.send('Runtime.evaluate', {
        expression,
        awaitPromise,
        returnByValue: true,
        userGesture: true,
      });
      if (result.exceptionDetails) {
        throw new Error('页面脚本异常: '
            + (result.exceptionDetails.exception?.description || result.exceptionDetails.text));
      }
      return result.result.value;
    }

    async function until(expression, timeoutMs = 12000, label = expression) {
      const deadline = Date.now() + timeoutMs;
      let lastError = '';
      while (Date.now() < deadline) {
        try {
          if (await evaluate('Boolean(' + expression + ')')) return true;
        } catch (e) {
          lastError = e.message;
        }
        await sleep(120);
      }
      throw new Error('等待超时: ' + label + (lastError ? ' · ' + lastError : ''));
    }

    async function navigate(url) {
      const loaded = cdp.once('Page.loadEventFired', 20000).catch(() => null);
      await cdp.send('Page.navigate', { url });
      await loaded;
      await until("document.readyState === 'complete'", 15000, '页面加载');
    }

    // ---- 连接与本体页 ----
    await navigate(base + '/#server');
    await until("window.SamsaraUI && document.getElementById('token-connect')", 10000, '前端脚本');
    await sleep(300);
    await evaluate(`
      window.__e2eErrors = [];
      window.addEventListener('error', (event) => window.__e2eErrors.push(String(event.message)));
      window.addEventListener('unhandledrejection', (event) => window.__e2eErrors.push(String(event.reason)));
      document.getElementById('token-input').value = ${JSON.stringify(token)};
      document.getElementById('token-connect').click();
      true;
    `);
    try {
      await until("document.getElementById('token-dialog').open === false", 10000, '连接 token');
    } catch (e) {
      // 首次加载时按钮可能还没绑定；再点一次。
      await evaluate("document.getElementById('token-connect').click(); true;");
      await until("document.getElementById('token-dialog').open === false", 20000, '连接 token（重试）');
    }
    await until("document.querySelectorAll('#server-config-files > details.file-block').length >= 6", 25000, '本体配置文件');

    const server = await evaluate(`(() => ({
      nav: document.querySelector('#global-nav .nav-title')?.textContent.trim() || '',
      tabs: [...document.querySelectorAll('#server-tabs .tab')].map((tab) => tab.textContent.replace(/\\s+/g, ' ').trim()),
      files: document.querySelectorAll('#server-config-files > details.file-block').length,
      groups: document.querySelectorAll('#server-config-files .group-block').length,
      entries: document.querySelectorAll('#server-config-files .entry').length,
      missingDesc: [...document.querySelectorAll('#server-config-files .entry')]
        .filter((entry) => !entry.querySelector('.entry-desc')?.textContent.trim()).length,
      segmented: document.querySelectorAll('#server-config-files .entry .segmented').length,
      saveLabel: document.getElementById('server-save').textContent.trim(),
      duplicateIds: (() => {
        const ids = [...document.querySelectorAll('[id]')].map((node) => node.id);
        return ids.length - new Set(ids).size;
      })(),
    }))()`);
    check('本体导航标题', server.nav.startsWith('服务器配置'), server.nav);
    check('六个配置文件同页', server.files === 6, 'files=' + server.files);
    check('配置分组渲染', server.groups >= 4, 'groups=' + server.groups);
    check('配置条目渲染', server.entries >= 70, 'entries=' + server.entries);
    check('每个条目都有中文说明', server.missingDesc === 0, 'missing=' + server.missingDesc);
    check('布尔/枚举使用分段按钮', server.segmented >= 20, 'segmented=' + server.segmented);
    check('保存按钮初始状态', server.saveLabel === '保存并重载', server.saveLabel);
    check('页面没有重复 id', server.duplicateIds === 0, 'duplicates=' + server.duplicateIds);

    const collapse = await evaluate(`(() => {
      const file = [...document.querySelectorAll('#server-config-files > details.file-block')]
        .find((node) => node.id.includes('server-properties'));
      const group = file.querySelector(':scope > .file-body > details.group-block');
      file.open = false;
      const fileClosed = file.open === false;
      file.open = true;
      group.open = false;
      const groupClosed = group.open === false;
      group.open = true;
      return { fileClosed, groupClosed };
    })()`);
    check('文件可折叠', collapse.fileClosed);
    check('分组可折叠', collapse.groupClosed);

    const search = await evaluate(`(() => {
      const input = document.getElementById('server-config-search');
      input.value = 'motd';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      const visible = document.querySelectorAll('#server-config-files > details.file-block').length;
      const keys = [...document.querySelectorAll('#server-config-files .entry-key')].map((node) => node.textContent.trim());
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      return { visible, keys, restored: document.querySelectorAll('#server-config-files > details.file-block').length };
    })()`);
    check('搜索只显示匹配项', search.visible === 1 && search.keys.length === 1, JSON.stringify(search.keys));
    check('清空搜索恢复全部文件', search.restored === 6, 'files=' + search.restored);

    // ---- 玩家选择（只改草稿，不保存）----
    const picker = await evaluate(`(async () => {
      const file = document.getElementById('server-file-whitelist');
      const button = [...file.querySelectorAll('button')].find((node) => node.textContent.trim() === '从玩家选择');
      if (!button) return { opened: false, rows: 0 };
      button.click();
      await new Promise((resolve) => setTimeout(resolve, 500));
      return {
        opened: document.getElementById('player-dialog').open === true,
        rows: document.querySelectorAll('#player-list .player-row').length,
      };
    })()`);
    check('玩家选择弹窗可打开', picker.opened);
    if (picker.rows > 0) {
      const afterPick = await evaluate(`(async () => {
        const before = document.querySelectorAll('#server-file-whitelist .server-json-item').length;
        document.querySelector('#player-list .player-row').click();
        await new Promise((resolve) => setTimeout(resolve, 400));
        const items = [...document.querySelectorAll('#server-file-whitelist .server-json-item')];
        const last = items[items.length - 1];
        const values = last ? [...last.querySelectorAll('.struct-row input')].map((input) => input.value) : [];
        const after = items.length;
        if (last) {
          const remove = [...last.querySelectorAll('.server-json-head button')]
            .find((node) => node.textContent.trim() === '删除');
          if (remove) remove.click();
        }
        await new Promise((resolve) => setTimeout(resolve, 200));
        return { before, after, values, saveLabel: document.getElementById('server-save').textContent.trim() };
      })()`);
      check('从玩家选择填入 UUID/名字', afterPick.after === afterPick.before + 1
          && afterPick.values.some((value) => value.length > 10), JSON.stringify(afterPick.values));
      check('删除草稿后未保存计数归零', afterPick.saveLabel === '保存并重载', afterPick.saveLabel);
    } else {
      check('玩家选择列表', true, '测试服没有已知玩家，跳过选中校验');
    }

    // ---- 备份时间线 ----
    const timeline = await evaluate(`(async () => {
      [...document.querySelectorAll('#server-tabs .tab')]
        .find((tab) => tab.dataset.serverPanel === 'server-backup-panel').click();
      await new Promise((resolve) => setTimeout(resolve, 1000));
      const nodes = [...document.querySelectorAll('#server-backup-timeline .timeline-node')];
      const rollback = [...document.querySelectorAll('#server-backup-timeline button')]
        .find((node) => node.textContent.trim() === '回滚到这里');
      let previewTitle = '';
      if (rollback) {
        rollback.click();
        await new Promise((resolve) => setTimeout(resolve, 600));
        previewTitle = document.querySelector('#server-backup-preview .world-stack-preview-title')?.textContent || '';
        document.getElementById('server-backup-preview').hidden = true;
      }
      return {
        nav: document.querySelector('#global-nav .nav-title')?.textContent.trim() || '',
        nodes: nodes.length,
        current: nodes.filter((node) => node.classList.contains('current')).length,
        hasRollback: Boolean(rollback),
        previewTitle,
      };
    })()`);
    check('存档备份导航', timeline.nav.startsWith('存档备份'), timeline.nav);
    check('时间线节点渲染', timeline.nodes >= 1, 'nodes=' + timeline.nodes);
    check('时间线仅一个当前节点', timeline.current === 1, 'current=' + timeline.current);
    if (timeline.hasRollback) {
      check('回滚预览打开', timeline.previewTitle.startsWith('恢复预览'), timeline.previewTitle);
    }

    // ---- form-kit 推广 ----
    const mods = await evaluate(`(async () => {
      [...document.querySelectorAll('.top-tab')].find((tab) => tab.dataset.section === 'mods').click();
      await new Promise((resolve) => setTimeout(resolve, 500));
      window.SamsaraUI.setActiveTab('identity');
      await new Promise((resolve) => setTimeout(resolve, 600));
      const identity = document.querySelectorAll('#identity-enabled .segmented-btn').length;
      window.SamsaraUI.setActiveTab('output');
      await new Promise((resolve) => setTimeout(resolve, 600));
      const output = document.querySelectorAll('#output-auto-continue .segmented-btn').length;
      window.SamsaraUI.setActiveTab('permissions');
      await new Promise((resolve) => setTimeout(resolve, 900));
      const permissions = document.querySelectorAll('.perm-row .segmented').length;
      window.SamsaraUI.setActiveTab('config');
      await new Promise((resolve) => setTimeout(resolve, 600));
      const configBool = document.querySelectorAll('#files .entry-control .segmented').length;
      return { identity, output, permissions, configBool };
    })()`);
    check('身份组布尔改为按钮', mods.identity === 2, 'buttons=' + mods.identity);
    check('输出格式布尔改为按钮', mods.output === 2, 'buttons=' + mods.output);
    check('权限树布尔改为按钮', mods.permissions > 0, 'segmented=' + mods.permissions);
    check('配置页布尔改为按钮', mods.configBool > 0, 'segmented=' + mods.configBool);

    const errors = await evaluate('window.__e2eErrors || []');
    check('没有未捕获的页面脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));
  } finally {
    if (cdp) cdp.close();
    try { child.kill(); } catch (e) { /* ignore */ }
    await sleep(300);
    try { fs.rmSync(profile, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  }

  const failed = results.filter((result) => !result.ok);
  console.log('');
  console.log('结果：' + (results.length - failed.length) + ' / ' + results.length + ' 通过');
  if (failed.length) {
    console.log('失败：');
    for (const item of failed) console.log('  - ' + item.name + (item.detail ? ' · ' + item.detail : ''));
    process.exitCode = 1;
  }
}

main().catch((error) => {
  console.error('');
  console.error('E2E 执行失败：' + (error && error.stack || error));
  process.exitCode = 1;
});
