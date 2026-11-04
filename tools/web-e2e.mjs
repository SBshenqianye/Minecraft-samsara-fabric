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
      window.addEventListener('error', (event) => window.__e2eErrors.push(
        String(event.message) + (event.error && event.error.stack ? '\\n' + event.error.stack : '')));
      window.addEventListener('unhandledrejection', (event) => window.__e2eErrors.push(
        String(event.reason) + (event.reason && event.reason.stack ? '\\n' + event.reason.stack : '')));
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
    if (timeline.nodes === 0) {
      check('时间线节点渲染', true, '测试服没有本体备份，跳过节点断言');
      check('时间线仅一个当前节点', true, '测试服没有本体备份，跳过节点断言');
    } else {
      check('时间线节点渲染', timeline.nodes >= 1, 'nodes=' + timeline.nodes);
      check('时间线仅一个当前节点', timeline.current === 1, 'current=' + timeline.current);
    }
    if (timeline.hasRollback) {
      check('回滚预览打开', timeline.previewTitle.startsWith('恢复预览'), timeline.previewTitle);
    }

    // ---- 世界栈操作时间线（模态浮窗） ----
    const stackTimeline = await evaluate(`(async () => {
      window.SamsaraUI.setActiveTab('world-stack');
      await new Promise((resolve) => setTimeout(resolve, 300));
      await window.SamsaraWorldStack.load();
      await new Promise((resolve) => setTimeout(resolve, 500));
      const openButton = document.getElementById('world-stack-timeline-open');
      openButton?.click();
      await new Promise((resolve) => setTimeout(resolve, 900));
      const dialog = document.getElementById('world-stack-timeline-dialog');
      const nodes = [...document.querySelectorAll('#world-stack-timeline .timeline-node')];
      const nav = document.querySelector('#global-nav .nav-title')?.textContent.trim() || '';
      const buttons = [...document.querySelectorAll('#world-stack-timeline button')];
      const undo = buttons.filter((node) => node.textContent.trim() === '撤销这次操作');
      const enabled = undo.find((node) => !node.disabled) || null;
      const disabled = undo.find((node) => node.disabled) || null;
      const exports = buttons.filter((node) => node.textContent.trim() === '导出快照')
        .map((node) => ({
          id: node.closest('.timeline-node')?.dataset.id || '',
          disabled: node.disabled,
        }));
      let previewTitle = '';
      let previewText = '';
      if (enabled) {
        enabled.click();
        await new Promise((resolve) => setTimeout(resolve, 700));
        const preview = document.getElementById('world-stack-timeline-preview');
        previewTitle = preview?.querySelector('.world-stack-preview-title')?.textContent || '';
        previewText = preview?.textContent || '';
        if (preview) preview.hidden = true;
      }
      const model = await window.SamsaraUI.api('/api/world-stack');
      const result = {
        nav,
        dialogOpen: Boolean(dialog?.open),
        nodes: nodes.map((node) => ({
          id: node.dataset.id,
          branchId: node.dataset.branchId,
          operation: node.dataset.operation,
          restorable: node.dataset.restorable,
          legacy: node.dataset.legacy,
          depth: node.dataset.depth,
          current: node.classList.contains('current'),
          text: node.textContent,
        })),
        enabled: Boolean(enabled),
        disabled: Boolean(disabled),
        exports,
        previewTitle,
        previewText,
        snapshotText: document.getElementById('world-stack-snapshots')?.textContent || '',
        pendingRestore: model.pendingRestore || null,
      };
      dialog?.close();
      return result;
    })()`);
    check('世界栈时间线导航', stackTimeline.nav.startsWith('世界栈'), stackTimeline.nav);
    check('世界栈时间线由按钮打开模态浮窗', stackTimeline.dialogOpen === true,
        String(stackTimeline.dialogOpen));
    if (stackTimeline.nodes.length === 0) {
      check('世界栈时间线空态', true, '测试服没有世界栈操作备份，跳过节点断言');
    } else {
      check('世界栈时间线节点渲染', stackTimeline.nodes.length >= 1,
          'nodes=' + stackTimeline.nodes.length);
      check('世界栈时间线固定 branchId', stackTimeline.nodes.every((node) => node.branchId === 'world-stack'),
          JSON.stringify(stackTimeline.nodes.map((node) => node.branchId)));
      check('世界栈时间线线性不缩进', stackTimeline.nodes.every((node) => node.depth === '0'),
          JSON.stringify(stackTimeline.nodes.map((node) => node.depth)));
      check('世界栈时间线仅一个当前节点',
          stackTimeline.nodes.filter((node) => node.current).length <= 1,
          'current=' + stackTimeline.nodes.filter((node) => node.current).length);
      const legacy = stackTimeline.nodes.find((node) => node.legacy === 'true');
      if (legacy) {
        check('旧备份标记只读不可撤销', legacy.restorable === 'false'
            && legacy.text.includes('旧备份'), legacy.id);
      } else {
        check('旧备份标记只读不可撤销', true, '测试服没有旧备份节点，跳过');
      }
      if (stackTimeline.enabled) {
        check('可撤销节点预览打开', stackTimeline.previewTitle.startsWith('撤销预览'),
            stackTimeline.previewTitle);
        check('预览显示当前受影响数据', stackTimeline.previewText.includes('当前受影响数据'),
            stackTimeline.previewText.slice(0, 120));
      } else {
        check('可撤销节点预览打开', true, '测试服没有可撤销节点，跳过');
        check('预览显示当前受影响数据', true, '测试服没有可撤销节点，跳过');
      }
      if (stackTimeline.disabled) {
        check('不可撤销节点按钮禁用', true);
      } else {
        check('不可撤销节点按钮禁用', true, '测试服没有不可撤销节点，跳过');
      }
      const exportEnabled = stackTimeline.exports.find((item) => !item.disabled);
      const exportDisabled = stackTimeline.exports.find((item) => item.disabled);
      check('可撤销节点可导出快照', Boolean(exportEnabled), JSON.stringify(stackTimeline.exports));
      if (exportDisabled) {
        check('旧备份/撤销节点禁用导出', true, exportDisabled.id);
      } else {
        check('旧备份/撤销节点禁用导出', true, '测试服没有旧备份节点，跳过');
      }
      check('快照占用面板渲染', stackTimeline.snapshotText.includes('安全快照'),
          stackTimeline.snapshotText.slice(0, 80));
      check('预览不会排队撤销', stackTimeline.pendingRestore === null,
          JSON.stringify(stackTimeline.pendingRestore));
    }

    // ---- 栈层图片：卡片 16:9 视觉区、上传鉴权/类型校验、删除后回退 ----
    const iconLayout = await evaluate(`(async () => {
      [...document.querySelectorAll('.top-tab')].find((tab) => tab.dataset.section === 'mods')?.click();
      window.SamsaraUI.setActiveTab('world-stack');
      await window.SamsaraWorldStack.load();
      await new Promise((resolve) => setTimeout(resolve, 700));
      const cards = [...document.querySelectorAll('#world-stack-list .world-stack-card')];
      const visuals = [...document.querySelectorAll('#world-stack-list .world-stack-card-visual')];
      const frames = [...document.querySelectorAll('#world-stack-list .world-stack-icon-frame')];
      const frame = frames[0]?.getBoundingClientRect();
      const header = document.querySelector('.app-header')?.getBoundingClientRect();
      const link = document.querySelector('.app-header .user-view-link')?.getBoundingClientRect();
      return {
        cards: cards.length,
        visuals: visuals.length,
        frames: frames.length,
        width: frame ? Math.round(frame.width) : 0,
        height: frame ? Math.round(frame.height) : 0,
        badge: document.querySelector('#world-stack-list .world-stack-icon-badge')?.textContent || '',
        source: document.querySelector('#world-stack-list .world-stack-icon-badge')?.dataset.source || '',
        headerTopGap: header && link ? Math.round(link.top - header.top) : -1,
        headerBottomGap: header && link ? Math.round(header.bottom - link.bottom) : -1,
        overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth + 2,
      };
    })()`);
    check('栈层卡片有 16:9 图标视觉区', iconLayout.cards >= 1
        && iconLayout.visuals === iconLayout.cards && iconLayout.frames === iconLayout.cards
        && iconLayout.width >= 120 && iconLayout.height > 0
        && Math.abs(iconLayout.width / iconLayout.height - 16 / 9) < 0.2,
        JSON.stringify(iconLayout));
    check('图标区有回退标识且不撑破布局', !iconLayout.overflow
        && ['map', 'save', 'upload', 'placeholder'].includes(iconLayout.source)
        && iconLayout.badge.length > 0, JSON.stringify(iconLayout));
    check('管理端右上角按钮与 header 有留白', iconLayout.headerTopGap >= 4
        && iconLayout.headerBottomGap >= 4,
        JSON.stringify({ top: iconLayout.headerTopGap, bottom: iconLayout.headerBottomGap }));

    const authHeaders = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };
    const iconUploadPath = base + '/api/world-stack/icon';
    const png1x1 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk'
      + '+A8AAQUBAScY42YAAAAASUVORK5CYII=';
    const noAuth = await fetch(iconUploadPath, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: 'world', mime: 'image/png', data: png1x1 }),
    });
    check('图标上传需要管理端 token', noAuth.status === 401, 'status=' + noAuth.status);
    const badType = await fetch(iconUploadPath, {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({
        id: 'world', mime: 'image/svg+xml',
        data: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>').toString('base64'),
      }),
    });
    const badTypeBody = await badType.json().catch(() => ({}));
    check('图标上传拒绝 SVG/HTML 伪装', badType.status === 400
        && String(badTypeBody.error || '').length > 0, JSON.stringify(badTypeBody));
    const badMagic = await fetch(iconUploadPath, {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({
        id: 'world', mime: 'image/png',
        data: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>').toString('base64'),
      }),
    });
    const badMagicBody = await badMagic.json().catch(() => ({}));
    check('图标上传校验 magic bytes', badMagic.status === 400
        && String(badMagicBody.error || '').includes('文件头'), JSON.stringify(badMagicBody));

    const modelBefore = await (await fetch(base + '/api/world-stack', { headers: authHeaders })).json();
    const targetStack = (modelBefore.stacks || []).find((stack) => stack.id === 'world')
      || (modelBefore.stacks || [])[0];
    if (!targetStack || (targetStack.icon && targetStack.icon.custom)) {
      console.log('  SKIP  图标上传/删除回退（测试服已存在自定义图标，避免覆盖用户文件）');
    } else {
      const uploaded = await fetch(iconUploadPath, {
        method: 'POST', headers: authHeaders,
        body: JSON.stringify({ id: targetStack.id, mime: 'image/png', data: png1x1 }),
      });
      const uploadedBody = await uploaded.json().catch(() => ({}));
      check('图标上传成功并返回自定义来源', uploaded.status === 200
          && uploadedBody.ok === true && uploadedBody.icon?.source === 'upload'
          && uploadedBody.icon?.custom === true, JSON.stringify(uploadedBody));
      let iconBytes = null;
      if (uploadedBody.icon?.url) {
        const image = await fetch(base + uploadedBody.icon.url);
        iconBytes = image.status;
        check('图标 URL 可公开只读且带版本号', image.status === 200
            && String(image.headers.get('content-type') || '').includes('image/png')
            && uploadedBody.icon.url.includes('v='), 'status=' + image.status);
      } else {
        check('图标 URL 可公开只读且带版本号', false, '上传响应缺少 icon.url');
      }
      const uploadedView = await evaluate(`(async () => {
        await window.SamsaraWorldStack.load();
        await new Promise((resolve) => setTimeout(resolve, 500));
        const badge = document.querySelector('#world-stack-list .world-stack-icon-badge');
        const image = document.querySelector('#world-stack-list .world-stack-icon-image');
        return {
          badge: badge?.textContent || '',
          source: badge?.dataset.source || '',
          src: image?.getAttribute('src') || '',
        };
      })()`);
      check('上传后卡片显示自定义图标', uploadedView.source === 'upload'
          && uploadedView.badge.includes('自定义')
          && uploadedView.src.includes('/api/user/worlds/icon'), JSON.stringify(uploadedView));

      const deleted = await fetch(base + '/api/world-stack/icon/delete', {
        method: 'POST', headers: authHeaders,
        body: JSON.stringify({ id: targetStack.id }),
      });
      const deletedBody = await deleted.json().catch(() => ({}));
      check('删除自定义图标后回到回退图', deleted.status === 200
          && deletedBody.ok === true && deletedBody.icon?.source !== 'upload',
          JSON.stringify(deletedBody));
      const deletedView = await evaluate(`(async () => {
        await window.SamsaraWorldStack.load();
        await new Promise((resolve) => setTimeout(resolve, 500));
        const badge = document.querySelector('#world-stack-list .world-stack-icon-badge');
        const image = document.querySelector('#world-stack-list .world-stack-icon-image');
        return {
          badge: badge?.textContent || '',
          source: badge?.dataset.source || '',
          src: image?.getAttribute('src') || '',
        };
      })()`);
      check('删除后卡片恢复回退标识', deletedView.source !== 'upload'
          && !deletedView.badge.includes('自定义'), JSON.stringify(deletedView));
      if (iconBytes !== 404) {
        console.log('  note 自定义图标测试已完成并清理');
      }
    }

    // ---- 归档入口：只保留卡片按钮 + 模态预览，不排队 ----
    const archiveEntry = await evaluate(`(async () => {
      [...document.querySelectorAll('.top-tab')].find((tab) => tab.dataset.section === 'mods')?.click();
      window.SamsaraUI.setActiveTab('world-stack');
      await window.SamsaraWorldStack.load();
      await new Promise((resolve) => setTimeout(resolve, 500));
      const card = [...document.querySelectorAll('#world-stack-list .world-stack-card')]
        .find((node) => !node.classList.contains('top'));
      const button = [...(card?.querySelectorAll('button') || [])]
        .find((node) => node.textContent.trim().startsWith('归档'));
      button?.click();
      await new Promise((resolve) => setTimeout(resolve, 900));
      const dialog = document.getElementById('world-stack-archive-dialog');
      const preview = document.getElementById('world-stack-archive-preview');
      const model = await window.SamsaraUI.api('/api/world-stack');
      const result = {
        cardButton: Boolean(button),
        dialogOpen: Boolean(dialog?.open),
        previewTitle: preview?.querySelector('.world-stack-preview-title')?.textContent || '',
        legacyPanel: Boolean(document.getElementById('world-stack-archive-panel')),
        legacyList: Boolean(document.getElementById('world-stack-archive-list')),
        pendingArchive: model.pendingArchive || null,
      };
      dialog?.close();
      return result;
    })()`);
    check('归档只保留卡片按钮并打开模态预览', archiveEntry.cardButton === true
        && archiveEntry.dialogOpen === true
        && archiveEntry.previewTitle.startsWith('归档预览')
        && archiveEntry.legacyPanel === false && archiveEntry.legacyList === false,
        JSON.stringify(archiveEntry));
    check('归档预览不会排队', archiveEntry.pendingArchive === null,
        JSON.stringify(archiveEntry.pendingArchive));

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

    // ---- 权限分段控件：保存 deny → 回显 → 恢复原等级 ----
    const permLevels = await evaluate(`(async () => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      window.SamsaraUI.setActiveTab('permissions');
      await wait(900);
      const api = window.SamsaraUI.api;
      const model = await api('/api/permissions');
      const leaves = [];
      const walk = (nodes) => {
        for (const node of nodes || []) {
          if (!node.children || node.children.length === 0) leaves.push(node);
          else walk(node.children);
        }
      };
      walk(model.tree || []);
      const node = leaves.find((item) => item.path === 'where broadcast')
        || leaves.find((item) => item.path.startsWith('where'))
        || leaves[0];
      if (!node) return { error: '没有可编辑的权限叶子' };
      const before = node.level || 'inherit';
      const rowFor = (path) => document.getElementById('perm-node-' + encodeURIComponent(path));
      const active = (path) => rowFor(path)?.querySelector('.perm-levels .segmented-btn.active')?.dataset.value || '';
      const effective = (path) => {
        const row = rowFor(path);
        if (!row) return '';
        const badge = [...row.querySelectorAll('.perm-badge')]
          .find((item) => item.textContent.startsWith('实际：'));
        return badge ? badge.textContent.replace('实际：', '').trim() : '';
      };
      const click = async (path, value) => {
        const row = rowFor(path);
        const button = row?.querySelector('.perm-levels .segmented-btn[data-value="' + value + '"]');
        if (!button) throw new Error('找不到分段按钮: ' + value);
        button.click();
        await wait(1000);
      };
      let denied = null;
      try {
        await click(node.path, 'deny');
        denied = { active: active(node.path), effective: effective(node.path) };
      } finally {
        // 无论 UI 成功与否都通过 API 恢复，避免污染测试服权限配置
        await api('/api/permissions', {
          method: 'PUT',
          body: JSON.stringify({ nodes: [{ path: node.path, level: before }] }),
        });
        if (window.SamsaraPermissions && window.SamsaraPermissions.load) {
          await window.SamsaraPermissions.load();
        }
        await wait(700);
      }
      return {
        path: node.path,
        before,
        denied,
        restored: { active: active(node.path), effective: effective(node.path) },
      };
    })()`);
    check('权限分段控件保存 deny 并回显',
      permLevels.denied && permLevels.denied.active === 'deny' && permLevels.denied.effective === 'deny',
      JSON.stringify(permLevels));
    check('权限分段控件恢复原等级',
      permLevels.restored && permLevels.restored.active === permLevels.before,
      JSON.stringify(permLevels));

    // ---- 圈地范围：条件显示 / 色板 / 图案网格 / 朝向 / 缩放平移 ----
    const claim = await evaluate(`(async () => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      window.SamsaraUI.setActiveTab('claim-range');
      await wait(800);
      const preview = document.getElementById('claim-range-preview');
      const canvas = document.getElementById('claim-sim-canvas');
      const modeRow = document.getElementById('cfg-entry-' + encodeURIComponent('claim-range|mode'));
      const fixedRow = document.getElementById('cfg-entry-' + encodeURIComponent('claim-range|fixedDistance'));
      const colorsRow = document.getElementById('cfg-entry-' + encodeURIComponent('claim-range|colorDistances'));
      const navFor = (row) => document.querySelector('#global-nav .nav-item[data-target="' + row.id + '"]');
      const clickMode = (value) => {
        const button = modeRow.querySelector('.segmented-btn[data-value="' + value + '"]');
        if (button) button.click();
      };

      const initial = {
        nav: document.querySelector('#global-nav .nav-title')?.textContent.trim() || '',
        fixedHidden: fixedRow.hidden,
        colorsHidden: colorsRow.hidden,
        mode: modeRow.querySelector('.segmented-btn.active')?.dataset.value || '',
      };

      clickMode('fixed');
      await wait(160);
      const afterFixed = {
        fixedHidden: fixedRow.hidden,
        colorsHidden: colorsRow.hidden,
        fixedNavHidden: navFor(fixedRow)?.hidden,
        colorsNavHidden: navFor(colorsRow)?.hidden,
        mode: modeRow.querySelector('.segmented-btn.active')?.dataset.value || '',
      };

      clickMode('dye');
      await wait(160);
      const afterDye = {
        fixedHidden: fixedRow.hidden,
        colorsHidden: colorsRow.hidden,
        fixedNavHidden: navFor(fixedRow)?.hidden,
        colorsNavHidden: navFor(colorsRow)?.hidden,
        mode: modeRow.querySelector('.segmented-btn.active')?.dataset.value || '',
      };

      // 4×4 色板
      const colorTrigger = preview.querySelector('[data-role="canvas-pattern-color-trigger"]');
      colorTrigger.click();
      await wait(160);
      const colorPop = document.querySelector('.claim-color-popover');
      const colorGrid = colorPop?.querySelector('.claim-color-grid');
      const colorCells = colorPop ? [...colorPop.querySelectorAll('.claim-color-cell')] : [];
      const colorColumns = colorGrid
        ? getComputedStyle(colorGrid).gridTemplateColumns.split(' ').filter(Boolean).length : 0;
      const colorMeta = colorCells.map((cell) => cell.querySelector('.claim-color-meta')?.textContent || '');
      colorCells[0]?.click();
      await wait(160);
      const colorClosed = !document.querySelector('.claim-color-popover');

      // 图案网格（带 canvas 预览）
      const patternTrigger = preview.querySelector('[data-role="canvas-pattern-trigger"]');
      patternTrigger.click();
      await wait(160);
      const patternPop = document.querySelector('.claim-pattern-popover');
      const patternCells = patternPop ? [...patternPop.querySelectorAll('.claim-pattern-cell')] : [];
      const patternCanvases = patternPop ? patternPop.querySelectorAll('canvas').length : 0;
      const patternNames = patternCells.map((cell) => cell.querySelector('.claim-pattern-name')?.textContent || '');
      const patternGridColumns = patternPop
        ? getComputedStyle(patternPop.querySelector('.claim-pattern-grid')).gridTemplateColumns
          .split(' ').filter(Boolean).length : 0;
      patternPop?.querySelector('.claim-pattern-cell[data-pattern="border"]')?.click();
      await wait(160);
      const patternClosed = !document.querySelector('.claim-pattern-popover');

      // 旗帜合成器必须复用同一套浮窗组件，不能退回原生下拉框
      const composer = document.getElementById('claim-banner-composer');
      const composerSelects = composer ? composer.querySelectorAll('select').length : -1;
      const composerTriggers = composer ? composer.querySelectorAll(
          '[data-role="base-color-trigger"], [data-role="layer-pattern-trigger"], [data-role="layer-color-trigger"]').length : 0;
      const composerCanvases = composer ? composer.querySelectorAll('.claim-trigger-canvas').length : 0;
      const composerPatternText = composer
        ? composer.querySelector('[data-role="layer-pattern-trigger"] .claim-trigger-label')?.textContent || ''
        : '';

      // 单旗 border + 白色（距离 1）= 3×3 边框周长 8 格（不填内部）
      const summary = () => document.querySelector('.claim-preview-summary')?.textContent || '';
      const borderSummary = summary();
      const borderCells = Number(canvas.dataset.protectedCells);

      // 单旗 border + 橙色（距离 2）= 5×5 边框周长 16 格（不填内部）
      const borderColorTrigger = preview.querySelector('[data-role="canvas-pattern-color-trigger"]');
      borderColorTrigger.click();
      await wait(140);
      document.querySelector('.claim-color-popover .claim-color-cell[data-index="1"]')?.click();
      await wait(180);
      const border5Summary = summary();
      const border5Cells = Number(canvas.dataset.protectedCells);
      borderColorTrigger.click();
      await wait(140);
      document.querySelector('.claim-color-popover .claim-color-cell[data-index="0"]')?.click();
      await wait(180);

      // 画布“闭合填充”开关：关闭后只显示原始线段，dataset 同步
      const fillToggle = document.getElementById('claim-fill-closed');
      const fillBefore = canvas.dataset.fillClosed || '';
      if (fillToggle) {
        fillToggle.checked = false;
        fillToggle.dispatchEvent(new Event('change', { bubbles: true }));
        await wait(180);
      }
      const fillOff = canvas.dataset.fillClosed || '';
      if (fillToggle) {
        fillToggle.checked = true;
        fillToggle.dispatchEvent(new Event('change', { bubbles: true }));
        await wait(180);
      }
      const fillOn = canvas.dataset.fillClosed || '';

      // 单旗 cross + 白色 = 中心 + 4 条斜向射线 = 5
      patternTrigger.click();
      await wait(120);
      document.querySelector('.claim-pattern-popover .claim-pattern-cell[data-pattern="cross"]')?.click();
      await wait(160);
      const crossSummary = summary();
      const crossCells = Number(canvas.dataset.protectedCells);

      // 朝向控件与画布同步
      const eastBtn = document.querySelector('#claim-facing .segmented-btn[data-value="east"]');
      eastBtn?.click();
      await wait(160);
      const facingEast = {
        active: document.querySelector('#claim-facing .segmented-btn.active')?.dataset.value || '',
        canvas: canvas.dataset.facing || '',
        summary: summary(),
      };
      const rotateButton = [...preview.querySelectorAll('.claim-sim-toolbar button')]
        .find((button) => button.textContent.trim() === '旋转');
      rotateButton?.click();
      await wait(160);
      const rotated = {
        active: document.querySelector('#claim-facing .segmented-btn.active')?.dataset.value || '',
        canvas: canvas.dataset.facing || '',
        summary: summary(),
      };

      // 连续缩放 + 拖拽平移 + 平移后仍能放置旗帜
      const resetButton = document.getElementById('claim-zoom-reset');
      resetButton.click();
      await wait(160);
      const rect = canvas.getBoundingClientRect();
      const beforeRadius = Number(canvas.dataset.viewRadius);
      const centerX = rect.left + rect.width * 0.5;
      const centerY = rect.top + rect.height * 0.5;
      canvas.dispatchEvent(new WheelEvent('wheel', {
        deltaY: -120, clientX: centerX, clientY: centerY, bubbles: true, cancelable: true,
      }));
      await wait(100);
      const afterZoomRadius = Number(canvas.dataset.viewRadius);

      const panX = rect.left + rect.width * 0.28;
      const panY = rect.top + rect.height * 0.72;
      const beforeCenter = canvas.dataset.viewCenter;
      canvas.dispatchEvent(new PointerEvent('pointerdown', {
        bubbles: true, clientX: panX, clientY: panY, button: 0, pointerId: 7, pointerType: 'mouse',
      }));
      canvas.dispatchEvent(new PointerEvent('pointermove', {
        bubbles: true, clientX: panX + 70, clientY: panY + 40, button: 0, pointerId: 7, pointerType: 'mouse',
      }));
      canvas.dispatchEvent(new PointerEvent('pointerup', {
        bubbles: true, clientX: panX + 70, clientY: panY + 40, button: 0, pointerId: 7, pointerType: 'mouse',
      }));
      await wait(120);
      const afterCenter = canvas.dataset.viewCenter;

      const bannerBefore = Number(canvas.dataset.bannerCount);
      const placeX = rect.left + rect.width * 0.72;
      const placeY = rect.top + rect.height * 0.28;
      canvas.dispatchEvent(new PointerEvent('pointerdown', {
        bubbles: true, clientX: placeX, clientY: placeY, button: 0, pointerId: 8, pointerType: 'mouse',
      }));
      canvas.dispatchEvent(new PointerEvent('pointerup', {
        bubbles: true, clientX: placeX, clientY: placeY, button: 0, pointerId: 8, pointerType: 'mouse',
      }));
      await wait(450);
      const bannerAfter = Number(canvas.dataset.bannerCount);

      // 多面旗帜闭合区域：三面 straight_cross + 颜色 3（默认距离 4）
      // 单旗应为 17 格（中心 + 4×4 射线）；三面围成 4×4 矩形后应填充内部，共 49 格。
      [...preview.querySelectorAll('.claim-sim-toolbar button')]
        .find((button) => button.textContent.trim() === '清空')?.click();
      await wait(180);
      patternTrigger.click();
      await wait(140);
      document.querySelector('.claim-pattern-popover .claim-pattern-cell[data-pattern="straight_cross"]')?.click();
      await wait(180);
      colorTrigger.click();
      await wait(140);
      const paletteCells = [...document.querySelectorAll('.claim-color-popover .claim-color-cell')];
      paletteCells[3]?.click();
      await wait(180);
      const draftDistance = Number(canvas.dataset.patternDistance);
      resetButton.click();
      await wait(180);

      const rect2 = canvas.getBoundingClientRect();
      const cellPx = (560 - 52) / 25;
      const toScreen = (gx, gz) => ({
        x: rect2.left + (26 + (gx + 12.5) * cellPx) * (rect2.width / 560),
        y: rect2.top + (26 + (gz + 12.5) * cellPx) * (rect2.height / 560),
      });
      let singleStraightCrossCells = 0;
      let singleDistance = 0;
      let singleRayCells = 0;
      const points = [[0, 0, 9], [0, 4, 10], [4, 0, 11]];
      for (let i = 0; i < points.length; i += 1) {
        const [gx, gz, pointerId] = points[i];
        const point = toScreen(gx, gz);
        canvas.dispatchEvent(new PointerEvent('pointerdown', {
          bubbles: true, clientX: point.x, clientY: point.y, button: 0,
          pointerId, pointerType: 'mouse',
        }));
        canvas.dispatchEvent(new PointerEvent('pointerup', {
          bubbles: true, clientX: point.x, clientY: point.y, button: 0,
          pointerId, pointerType: 'mouse',
        }));
        await wait(650);
        if (i === 0) {
          singleStraightCrossCells = Number(canvas.dataset.protectedCells);
          singleDistance = Number(canvas.dataset.patternDistance);
          singleRayCells = Number(canvas.dataset.rayCells);
        }
      }
      const multiBannerCount = Number(canvas.dataset.bannerCount);
      const multiBannerCells = Number(canvas.dataset.protectedCells);
      const multiBannerPattern = canvas.dataset.pattern;
      const multiBannerColor = Number(canvas.dataset.patternColor);

      return {
        initial, afterFixed, afterDye,
        colorCells: colorCells.length,
        colorColumns,
        colorHasDistance: colorMeta.every((text) => text.includes('格')),
        colorSelected: colorCells.filter((cell) => cell.classList.contains('selected')).length,
        colorClosed,
        patternCells: patternCells.length,
        patternCanvases,
        patternNamesOk: patternNames.includes('全边框') && patternNames.includes('交叉'),
        patternGridColumns,
        patternClosed,
        composerSelects, composerTriggers, composerCanvases, composerPatternText,
        borderSummary, borderCells, border5Summary, border5Cells,
        fillBefore, fillOff, fillOn,
        crossSummary, crossCells,
        facingEast, rotated,
        beforeRadius, afterZoomRadius, beforeCenter, afterCenter,
        bannerBefore, bannerAfter,
        draftDistance, singleStraightCrossCells, singleDistance, singleRayCells,
        multiBannerCount, multiBannerCells, multiBannerPattern, multiBannerColor,
        overflow: document.documentElement.scrollWidth > window.innerWidth + 1,
      };
    })()`);

    check('圈地范围导航', claim.initial.nav.startsWith('圈地范围'), claim.initial.nav);
    check('点击 fixed 后 fixedDistance 立即出现',
        claim.afterFixed.fixedHidden === false && claim.afterFixed.mode === 'fixed',
        JSON.stringify(claim.afterFixed));
    check('点击 fixed 后 colorDistances 立即隐藏',
        claim.afterFixed.colorsHidden === true, JSON.stringify(claim.afterFixed));
    check('左侧导航同步 fixed 条件显示',
        claim.afterFixed.fixedNavHidden === false && claim.afterFixed.colorsNavHidden === true,
        JSON.stringify(claim.afterFixed));
    check('点击 dye 后反向切换',
        claim.afterDye.fixedHidden === true && claim.afterDye.colorsHidden === false
          && claim.afterDye.mode === 'dye', JSON.stringify(claim.afterDye));
    check('色板为 4×4 网格', claim.colorCells === 16 && claim.colorColumns === 4,
        'cells=' + claim.colorCells + ' cols=' + claim.colorColumns);
    check('色板每格显示距离', claim.colorHasDistance);
    check('色板单选状态与关闭', claim.colorSelected === 1 && claim.colorClosed,
        'selected=' + claim.colorSelected + ' closed=' + claim.colorClosed);
    check('图案浮窗列出 15 种图案', claim.patternCells === 15 && claim.patternNamesOk,
        'cells=' + claim.patternCells);
    check('图案浮窗含 canvas 预览', claim.patternCanvases >= 15, 'canvas=' + claim.patternCanvases);
    check('图案浮窗 4 列网格', claim.patternGridColumns === 4, 'cols=' + claim.patternGridColumns);
    check('图案浮窗可关闭', claim.patternClosed);
    check('旗帜合成器复用颜色/图案浮窗（无原生下拉框）',
        claim.composerSelects === 0 && claim.composerTriggers >= 3,
        'selects=' + claim.composerSelects + ' triggers=' + claim.composerTriggers);
    check('合成器图案触发器带预览图标与图案名',
        claim.composerCanvases >= 1 && claim.composerPatternText.length > 0,
        'canvas=' + claim.composerCanvases + ' label=' + claim.composerPatternText);
    check('单面 border 白色距离 1 = 3×3 边框（9 格）', claim.borderCells === 9
        && claim.borderSummary.includes('保护 9 区块'), 'cells=' + claim.borderCells);
    check('单面 border 橙色距离 2 = 5×5 边框（17 格）', claim.border5Cells === 17
        && claim.border5Summary.includes('保护 17 区块'), 'cells=' + claim.border5Cells);
    check('调试画布可切换原始线段/闭合填充', claim.fillBefore === '1'
        && claim.fillOff === '0' && claim.fillOn === '1',
        JSON.stringify({ before: claim.fillBefore, off: claim.fillOff, on: claim.fillOn }));
    check('单面 cross 只出射线（5 格，不填充）', claim.crossCells === 5
        && claim.crossSummary.includes('保护 5 区块'), 'cells=' + claim.crossCells);
    check('朝向控件与画布同步（东）',
        claim.facingEast.active === 'east' && claim.facingEast.canvas === 'east'
          && claim.facingEast.summary.includes('旗面朝 东'), JSON.stringify(claim.facingEast));
    check('旋转后控件/画布/摘要同步（南）',
        claim.rotated.active === 'south' && claim.rotated.canvas === 'south'
          && claim.rotated.summary.includes('旗面朝 南'), JSON.stringify(claim.rotated));
    check('滚轮以光标为中心连续缩放', claim.afterZoomRadius < claim.beforeRadius,
        claim.beforeRadius + ' → ' + claim.afterZoomRadius);
    check('空白拖拽可平移', claim.afterCenter !== claim.beforeCenter,
        claim.beforeCenter + ' → ' + claim.afterCenter);
    check('缩放/平移后仍能放置旗帜', claim.bannerAfter === claim.bannerBefore + 1,
        claim.bannerBefore + ' → ' + claim.bannerAfter);
    check('单面 straight_cross 只出射线（17 格，不填充）',
        claim.singleStraightCrossCells === 17,
        'cells=' + claim.singleStraightCrossCells + ' rays=' + claim.singleRayCells
          + ' draftD=' + claim.draftDistance + ' singleD=' + claim.singleDistance);
    check('多面旗帜闭合区域在 Web 预览中填充（49 格）',
        claim.multiBannerCount === 3 && claim.multiBannerCells === 49
          && claim.multiBannerPattern === 'straight_cross' && claim.multiBannerColor === 3,
        JSON.stringify({
          count: claim.multiBannerCount,
          cells: claim.multiBannerCells,
          pattern: claim.multiBannerPattern,
          color: claim.multiBannerColor,
        }));

    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: 800, height: 900, deviceScaleFactor: 1, mobile: false,
    });
    await sleep(500);
    const narrow = await evaluate(`(async () => {
      window.SamsaraUI.setActiveTab('claim-range');
      await new Promise((resolve) => setTimeout(resolve, 500));
      return {
        overflow: document.documentElement.scrollWidth > window.innerWidth + 1,
        canvasWidth: document.getElementById('claim-sim-canvas')?.getBoundingClientRect().width || 0,
        viewport: window.innerWidth,
      };
    })()`);
    await cdp.send('Emulation.clearDeviceMetricsOverride');
    check('窄屏圈地页无横向溢出', narrow.overflow === false && narrow.canvasWidth <= narrow.viewport,
        JSON.stringify(narrow));

    // ---- 地图页冒烟 + 世界栈总览 / 在线玩家 ----
    await evaluate("window.SamsaraUI.setActiveTab('map'); true;");
    await until("document.getElementById('map-message') && (document.getElementById('map-message').textContent.includes('失败') || document.getElementById('map-stats').textContent.trim().length > 0)", 20000, '地图数据');
    const mapState = await evaluate(`(async () => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const toggle = document.getElementById('map-overview-toggle');
      // E2E 注入一个假玩家，验证总览玩家标记与 TP 命令入口；不依赖测试服是否有人在线。
      const originalApi = window.SamsaraUI.api;
      window.SamsaraUI.api = async (path, options) => {
        if (path === '/api/map/players') {
          return {
            ok: true,
            players: [{
              name: 'E2EPlayer', uuid: 'e2e-player', stackId: 'world',
              dimension: 'overworld', dimensionId: 'minecraft:overworld',
              x: 0, y: 64, z: 0, yaw: 0, health: 20,
            }],
          };
        }
        return originalApi.call(window.SamsaraUI, path, options);
      };
      toggle?.click();
      await wait(1100);
      const overview = document.getElementById('map-stack-overview');
      const blocks = [...document.querySelectorAll('#map-overview-stacks .map-stack-block')];
      const first = blocks[0] || null;
      const panels = first ? [...first.querySelectorAll('.map-stack-panel')] : [];
      const dims = panels.map((panel) => [...panel.classList]
        .find((name) => name.startsWith('dim-')) || '');
      const leftColumn = first ? first.querySelector('.map-stack-column') : null;
      const leftChildren = leftColumn ? [...leftColumn.children] : [];
      const voidGaps = document.querySelectorAll('#map-overview-stacks .map-stack-void').length;
      const status = document.getElementById('map-overview-status')?.textContent || '';
      const canvases = first ? first.querySelectorAll('.map-stack-thumb').length : 0;
      const endInSide = Boolean(first?.querySelector('.map-stack-end .map-stack-panel'));
      const playersData = await window.SamsaraUI.api('/api/map/players');
      const online = Array.isArray(playersData.players) ? playersData.players.length : -1;
      const markerTexts = () => [...document.querySelectorAll('#map-stack-overview .map-player-marker')]
        .map((node) => node.textContent || '');
      let markers = markerTexts().length;
      const deadline = Date.now() + 2500;
      while (online > 0 && !markerTexts().some((text) => text.includes('E2EPlayer'))
          && Date.now() < deadline) {
        await wait(150);
        markers = markerTexts().length;
      }
      const markerName = markerTexts().find((text) => text.includes('E2EPlayer'))
        || markerTexts()[0] || '';
      const markerNode = [...document.querySelectorAll('#map-stack-overview .map-player-marker')]
        .find((node) => (node.textContent || '').includes('E2EPlayer'));
      const markerAvatar = markerNode?.querySelector('.avatar img');
      const markerAvatarSrc = markerAvatar?.getAttribute('src') || '';
      const markerTip = Boolean(markerNode?.querySelector('.pin-tip'));
      const markerTransform = markerNode ? getComputedStyle(markerNode).transform : '';
      window.SamsaraUI.api = originalApi;
      const target = panels.find((panel) => panel.classList.contains('dim-overworld'));
      const targetStack = target?.closest('.map-stack-block')?.dataset.stack || '';
      target?.click();
      await wait(600);
      const baseCanvas = document.getElementById('map-canvas');
      const playerCanvas = document.getElementById('map-players');
      return {
        canvas: Boolean(baseCanvas),
        playerLayer: Boolean(playerCanvas && baseCanvas
          && playerCanvas.width === baseCanvas.width
          && playerCanvas.height === baseCanvas.height
          && playerCanvas.width > 0),
        stats: document.getElementById('map-stats')?.textContent.trim() || '',
        message: document.getElementById('map-message')?.textContent || '',
        hasMarkStale: typeof window.SamsaraMap?.markStale === 'function',
        overviewBlocks: blocks.length,
        panels: panels.length,
        dims,
        dimGapBetween: leftChildren[1]?.classList.contains('map-dim-gap') || false,
        voidGaps,
        status,
        canvases,
        endInSide,
        online,
        markers,
        markerName,
        markerAvatar: Boolean(markerAvatar),
        markerAvatarSrc,
        markerTip,
        markerTransform,
        detailVisible: !document.getElementById('map-stage-anchor')?.hidden,
        overviewHidden: Boolean(overview?.hidden),
        selectedStack: document.getElementById('map-stack')?.value || '',
        targetStack,
      };
    })()`);
    check('地图页仍可读取 /api/map', mapState.canvas
        && mapState.stats.length > 0 && !mapState.message.includes('失败'),
        JSON.stringify(mapState));
    check('地图页提供几何缓存失效入口', mapState.hasMarkStale);
    check('详细地图玩家独立图层与底图同尺寸', mapState.playerLayer,
        'playerLayer=' + mapState.playerLayer);
    check('世界栈总览按栈层渲染', mapState.overviewBlocks >= 1,
        'blocks=' + mapState.overviewBlocks);
    check('每栈含主世界/下界/末地三面板', mapState.panels >= 3
        && mapState.dims[0] === 'dim-overworld' && mapState.dims[1] === 'dim-the_nether'
        && mapState.dims.includes('dim-the_end') && mapState.canvases >= 3,
        JSON.stringify({ dims: mapState.dims, canvases: mapState.canvases }));
    check('主世界与下界上下排列并留虚空缝隙', mapState.dimGapBetween === true);
    check('末地面板并排在右侧', mapState.endInSide === true);
    check('栈与栈之间留虚空缝隙', mapState.voidGaps === Math.max(0, mapState.overviewBlocks - 1),
        'blocks=' + mapState.overviewBlocks + ' gaps=' + mapState.voidGaps);
    check('总览显示在线玩家状态', mapState.status.includes('在线'), mapState.status);
    check('在线玩家标记渲染在所属栈/维度', mapState.online === 1
        && mapState.markers > 0 && mapState.markerName.includes('E2EPlayer'),
        'online=' + mapState.online + ' markers=' + mapState.markers
          + ' name=' + mapState.markerName);
    check('玩家标记为头像 pin 且有本地回退', mapState.markerAvatar === true
        && mapState.markerTip === true
        && (mapState.markerAvatarSrc.startsWith('data:image/svg+xml')
          || mapState.markerAvatarSrc.startsWith('data:image/png')
          || mapState.markerAvatarSrc.includes('/api/user/map/avatar')
          || mapState.markerAvatarSrc.includes('textures.minecraft.net'))
        && mapState.markerTransform !== 'none',
        JSON.stringify({ avatar: mapState.markerAvatar, src: mapState.markerAvatarSrc.slice(0, 60),
          tip: mapState.markerTip, transform: mapState.markerTransform }));
    check('总览状态显示在线人数', mapState.status.includes('在线 1 人'), mapState.status);
    check('点击总览面板进入详细地图', mapState.detailVisible && mapState.overviewHidden
        && mapState.selectedStack === mapState.targetStack,
        JSON.stringify({ detail: mapState.detailVisible, hidden: mapState.overviewHidden,
          selected: mapState.selectedStack, target: mapState.targetStack }));

    // ---- 地图实时层：实体/轨迹/最近操作 ----
    await fetch(base + '/api/server/console', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ command: 'forceload add 0 0' }),
    });
    const e2eEntityCommand = 'summon minecraft:pig 0 100 0 '
      + '{Tags:["samsara_e2e"],NoAI:1b,Silent:1b,Invulnerable:1b}';
    const e2eEntitySpawn = await fetch(base + '/api/server/console', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ command: e2eEntityCommand }),
    });
    check('为实时层创建临时实体', e2eEntitySpawn.status === 200,
        'status=' + e2eEntitySpawn.status);
    const liveMap = await evaluate(`(async () => {
      window.SamsaraUI.setActiveTab('map');
      await new Promise((resolve) => setTimeout(resolve, 1800));
      const data = await window.SamsaraUI.api('/api/map/live');
      const player = (data.players || [])[0] || null;
      const entity = (data.entities || [])[0] || null;
      const liveCanvas = document.getElementById('map-live');
      return {
        ok: data.ok,
        players: Array.isArray(data.players) ? data.players.length : -1,
        entities: Array.isArray(data.entities) ? data.entities.length : -1,
        operations: Array.isArray(data.operations) ? data.operations.length : -1,
        chatArray: Array.isArray(data.chat),
        systemArray: Array.isArray(data.system),
        rarityPresent: Array.isArray(data.entities)
          && data.entities.every((item) => typeof item.rarity === 'number'),
        rareToggle: Boolean(document.getElementById('map-show-rare-names')),
        playerTrailArray: Boolean(player && Array.isArray(player.trail)),
        entityTrailArray: Boolean(entity && Array.isArray(entity.trail)),
        sensitiveEntityUuid: Boolean(entity && entity.uuid),
        liveCanvas: Boolean(liveCanvas && liveCanvas.width > 0),
        liveStatus: document.getElementById('map-live-status')?.textContent || '',
        activityRows: document.querySelectorAll('#map-activity .map-activity-row').length,
        hasEntityToggle: Boolean(document.getElementById('map-show-entities')),
        hasTrailToggle: Boolean(document.getElementById('map-show-trails')),
        hasSeparationToggle: Boolean(document.getElementById('map-show-separation')),
      };
    })()`);
    check('地图实时层 API 返回实体/轨迹/操作/发言', liveMap.ok === true
        && liveMap.players >= 0 && liveMap.entities >= 1 && liveMap.operations >= 0
        && liveMap.entityTrailArray && liveMap.chatArray, JSON.stringify(liveMap));
    check('实时层返回系统提示/稀有度并提供稀有名字开关', liveMap.systemArray === true
        && liveMap.rarityPresent === true && liveMap.rareToggle === true,
        JSON.stringify({ system: liveMap.systemArray, rarity: liveMap.rarityPresent,
          toggle: liveMap.rareToggle }));
    check('地图实时层画布与开关可用', liveMap.liveCanvas && liveMap.liveStatus.includes('在线')
        && liveMap.hasEntityToggle && liveMap.hasTrailToggle && liveMap.hasSeparationToggle,
        JSON.stringify(liveMap));
    check('管理端实时层可返回实体 UUID', liveMap.sensitiveEntityUuid === true,
        JSON.stringify({ uuid: liveMap.sensitiveEntityUuid }));

    // ---- F-164 地图区块网格 ----
    const chunkGrid = await evaluate(`(async () => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const box = document.getElementById('map-show-chunks');
      const legend = document.getElementById('map-chunk-grid-legend');
      const calls = [];
      const originalFetch = window.fetch;
      window.fetch = (...args) => {
        calls.push(String(args[0]));
        return originalFetch(...args);
      };
      box.checked = true;
      box.dispatchEvent(new Event('change', { bubbles: true }));
      await wait(120);
      const on = { checked: box.checked, legend: legend.textContent,
        stored: localStorage.getItem('samsara.map.showChunks') };
      document.getElementById('map-zoom-out').click();
      await wait(80);
      document.getElementById('map-zoom-out').click();
      await wait(160);
      const thinned = legend.textContent;
      const unrelated = calls.filter((url) => !url.includes('/api/map/tile')
        && !url.includes('/api/map/players') && !url.includes('/api/map/live')).length;
      window.fetch = originalFetch;
      box.checked = false;
      box.dispatchEvent(new Event('change', { bubbles: true }));
      await wait(100);
      const off = { checked: box.checked, legend: legend.textContent,
        stored: localStorage.getItem('samsara.map.showChunks') };
      // 留为开启，供稍后刷新页面验证持久化。
      box.checked = true;
      box.dispatchEvent(new Event('change', { bubbles: true }));
      await wait(80);
      return { on, thinned, off, nonTileRequests: unrelated };
    })()`);
    check('区块网格勾选即时开启并写入本地', chunkGrid.on.checked
        && chunkGrid.on.legend.includes('开')
        && chunkGrid.on.stored === '1', JSON.stringify(chunkGrid.on));
    check('区块网格缩放后按 16×16 抽稀', chunkGrid.thinned.includes('16×16'),
        chunkGrid.thinned);
    check('区块网格开关不触发额外请求', chunkGrid.nonTileRequests === 0,
        'requests=' + chunkGrid.nonTileRequests);
    check('区块网格取消立即关闭', !chunkGrid.off.checked
        && chunkGrid.off.legend.includes('关')
        && chunkGrid.off.stored === '0', JSON.stringify(chunkGrid.off));

    // ---- F-165/F-166 Web 区块操作面板（只预览，不执行写操作） ----
    const chunkPanel = await evaluate(`(async () => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const mode = document.getElementById('map-chunk-mode');
      const spawns = document.getElementById('map-show-spawns');
      const players = document.getElementById('map-show-players');
      spawns.checked = false;
      spawns.dispatchEvent(new Event('change', { bubbles: true }));
      players.checked = false;
      players.dispatchEvent(new Event('change', { bubbles: true }));
      mode.checked = true;
      mode.dispatchEvent(new Event('change', { bubbles: true }));
      const canvas = document.getElementById('map-canvas');
      const rect = canvas.getBoundingClientRect();
      const x = rect.left + rect.width * 0.22;
      const y = rect.top + rect.height * 0.78;
      canvas.dispatchEvent(new PointerEvent('pointerdown', {
        bubbles: true, clientX: x, clientY: y, button: 0, pointerId: 41, pointerType: 'mouse',
      }));
      canvas.dispatchEvent(new PointerEvent('pointerup', {
        bubbles: true, clientX: x, clientY: y, button: 0, pointerId: 41, pointerType: 'mouse',
      }));
      canvas.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: x, clientY: y }));
      await wait(250);
      const title = document.querySelector('#map-selection .map-selection-title')?.textContent || '';
      const buttons = [...document.querySelectorAll('.map-chunk-actions button')]
        .map((button) => button.textContent);
      const preview = [...document.querySelectorAll('.map-chunk-actions button')]
        .find((button) => button.textContent === '预览回溯');
      preview?.click();
      await wait(3000);
      const status = document.querySelector('.map-chunk-status')?.textContent || '';
      mode.checked = false;
      mode.dispatchEvent(new Event('change', { bubbles: true }));
      return { title, buttons, status };
    })()`);
    check('区块操作可选中单区块', chunkPanel.title.startsWith('区块 ('),
        chunkPanel.title);
    check('区块面板提供预览/执行/回看', chunkPanel.buttons.includes('预览回溯')
        && chunkPanel.buttons.includes('执行回溯（需确认）')
        && chunkPanel.buttons.includes('只读回看'), JSON.stringify(chunkPanel.buttons));
    check('区块回溯预览返回摘要', chunkPanel.status.includes('候选')
        && (chunkPanel.status.includes('将改动') || chunkPanel.status.includes('失败')),
        chunkPanel.status.slice(0, 160));

    // ---- 保留曲线编辑器 / 容量预估 ----
    const curve = await evaluate(`(async () => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      window.SamsaraUI.setActiveTab('config');
      await wait(1800);
      const editor = document.querySelector('.curve-editor');
      if (!editor) return { found: false };
      const chart = editor.querySelector('.curve-chart');
      const points = () => {
        try { return JSON.parse(chart?.dataset.points || '[]'); } catch (e) { return []; }
      };
      const initialRows = points().length;
      const estimateText = editor.querySelector('.curve-estimate')?.textContent || '';
      const add = editor.querySelectorAll('.curve-actions button')[0];
      add.click();
      await wait(300);
      const addedRows = points().length;
      const remove = editor.querySelectorAll('.curve-actions button')[1];
      remove.click();
      await wait(300);
      const removedRows = points().length;
      const estimateAfter = editor.querySelector('.curve-estimate')?.textContent || '';
      return {
        found: true, hasCanvas: !!chart, initialRows, addedRows, removedRows,
        estimateText, estimateAfter, aria: chart?.getAttribute('aria-label') || '',
      };
    })()`);
    check('保留曲线使用坐标系拖拽编辑器', curve.found === true && curve.hasCanvas === true
        && curve.initialRows >= 2, JSON.stringify({ found: curve.found, rows: curve.initialRows }));
    check('保留曲线初始容量预估可见',
        curve.estimateText.includes('容量预估') && curve.estimateText.includes('有限档稳态合计'),
        curve.estimateText.slice(0, 120));
    check('曲线编辑器提供可读的点位标签', curve.aria.includes('%'), curve.aria.slice(0, 120));
    check('曲线编辑器可增删档位', curve.addedRows === curve.initialRows + 1
        && curve.removedRows === curve.initialRows,
        JSON.stringify({ added: curve.addedRows, removed: curve.removedRows }));
    check('增删档位后容量预估刷新', curve.estimateAfter.includes('有限档稳态合计'),
        curve.estimateAfter.slice(0, 120));

    const diffApi = await evaluate(`(async () => {
      const summary = await window.SamsaraUI.api(
        '/api/diff/chunk?stack=world&dim=overworld&cx=0&cz=0');
      const view = await window.SamsaraUI.api('/api/diff/view');
      return {
        summaryOk: summary.ok === true,
        edits: summary.editCount,
        hasProtected: summary.protected === true,
        canApply: summary.canApply === true,
        viewOk: view.ok === true,
        sessions: view.sessions,
      };
    })()`);
    check('单区块 diff 摘要 API 可用', diffApi.summaryOk && diffApi.edits >= 0,
        JSON.stringify(diffApi));
    if (diffApi.hasProtected) {
      check('真实圈地保护的单区块拒绝回溯', !diffApi.canApply, JSON.stringify(diffApi));
    } else {
      console.log('  SKIP  真实圈地保护的单区块拒绝回溯（测试服当前没有圈地；由单测覆盖闸门）');
    }
    check('Diff 回看状态 API 可用', diffApi.viewOk && diffApi.sessions === 0,
        JSON.stringify(diffApi));

    // ---- Web RCON 控制台 ----
    const noAuthConsole = await fetch(base + '/api/server/console', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ command: 'list' }),
    });
    check('RCON 控制台需要管理端 token', noAuthConsole.status === 401,
        'status=' + noAuthConsole.status);
    const consoleState = await evaluate(`(async () => {
      [...document.querySelectorAll('.top-tab')].find((tab) => tab.dataset.section === 'server')?.click();
      await new Promise((resolve) => setTimeout(resolve, 300));
      document.querySelector('.tab[data-server-panel="server-console-panel"]')?.click();
      await new Promise((resolve) => setTimeout(resolve, 300));
      const input = document.getElementById('server-console-command');
      const send = document.getElementById('server-console-send');
      input.value = 'list';
      send.click();
      await new Promise((resolve) => setTimeout(resolve, 1600));
      return {
        panelVisible: !document.getElementById('server-console-panel')?.hidden,
        output: document.getElementById('server-console-output')?.textContent || '',
        message: document.getElementById('server-console-message')?.textContent || '',
        hash: location.hash,
      };
    })()`);
    check('RCON 控制台可执行命令并回显', consoleState.panelVisible
        && consoleState.output.includes('> list') && consoleState.output.length > 8
        && consoleState.message.includes('完成'), JSON.stringify({
          output: consoleState.output.slice(0, 120), message: consoleState.message }));
    check('RCON 控制台入口哈希', consoleState.hash === '#server-console', consoleState.hash);

    // ---- Web 审计：写请求前快照，可预览回滚 ----
    const noAuthAudit = await fetch(base + '/api/audit');
    check('Web 审计需要管理端 token', noAuthAudit.status === 401,
        'status=' + noAuthAudit.status);
    const auditState = await evaluate(`(async () => {
      [...document.querySelectorAll('.top-tab')].find((tab) => tab.dataset.section === 'server')?.click();
      await new Promise((resolve) => setTimeout(resolve, 300));
      document.querySelector('.tab[data-server-panel="server-audit-panel"]')?.click();
      await new Promise((resolve) => setTimeout(resolve, 800));
      const rows = [...document.querySelectorAll('#server-audit-list .server-audit-item')];
      const first = rows[0];
      const loadMessage = document.getElementById('server-audit-message')?.textContent || '';
      const diffRow = rows.find((row) => row.textContent.includes('可回滚')) || first;
      const preview = diffRow?.querySelector('button');
      let previewMessage = '';
      let previewDiff = '';
      let diffVisible = false;
      let changes = 0;
      if (preview) {
        preview.click();
        await new Promise((resolve) => setTimeout(resolve, 700));
        previewMessage = document.getElementById('server-audit-message')?.textContent || '';
        previewDiff = document.getElementById('server-audit-diff')?.textContent || '';
        diffVisible = !document.getElementById('server-audit-diff-wrap')?.hidden;
        changes = document.querySelectorAll('#server-audit-changes .server-audit-change').length;
      }
      return {
        panelVisible: !document.getElementById('server-audit-panel')?.hidden,
        loadMessage,
        rows: rows.length,
        text: first?.textContent || '',
        rowsWithRollback: rows.filter((row) => row.textContent.includes('可回滚')).length,
        previewMessage,
        previewDiff,
        diffVisible,
        changes,
        hash: location.hash,
      };
    })()`);
    check('Web 审计有写请求快照记录', auditState.panelVisible && auditState.rows >= 1
        && auditState.text.includes('文件') && auditState.rowsWithRollback >= 1,
        JSON.stringify({ rows: auditState.rows, rollback: auditState.rowsWithRollback,
          text: auditState.text.slice(0, 120), panel: auditState.panelVisible,
          message: auditState.loadMessage }));
    check('Web 审计预览可用', auditState.previewMessage.includes('快照')
        && auditState.hash === '#server-audit', JSON.stringify({
          message: auditState.previewMessage, hash: auditState.hash }));
    check('Web 审计可查看文件级 diff', auditState.diffVisible === true
        && auditState.changes >= 0
        && (auditState.previewDiff.includes('---') || auditState.previewDiff.includes('没有可显示')),
        JSON.stringify({ visible: auditState.diffVisible, changes: auditState.changes,
          diff: auditState.previewDiff.slice(0, 160) }));

    // ---- 刷新页面验证区块网格本地持久化 ----
    await navigate(base + '/#map');
    await until("window.SamsaraUI && document.getElementById('map-show-chunks')", 20000,
        '刷新后的地图页');
    await evaluate("window.SamsaraUI.setActiveTab('map'); true;");
    await until("document.getElementById('map-stats') && "
        + "document.getElementById('map-stats').textContent.trim().length > 0", 20000,
        '刷新后的地图数据');
    const persistedGrid = await evaluate(`(() => ({
      checked: document.getElementById('map-show-chunks').checked,
      legend: document.getElementById('map-chunk-grid-legend').textContent,
      stored: localStorage.getItem('samsara.map.showChunks'),
    }))()`);
    check('区块网格勾选刷新页面后保持', persistedGrid.checked
        && persistedGrid.legend.includes('开') && persistedGrid.stored === '1',
        JSON.stringify(persistedGrid));

    // ---- 用户端只读页：无需 token，不加载管理端脚本，不调用管理端 API ----
    await navigate(base + '/user');
    await until("window.SamsaraUI && document.querySelectorAll('#user-status .status-chip').length > 0",
        20000, '用户端状态');
    await until("document.getElementById('map-stats') && "
        + "document.getElementById('map-stats').textContent.trim().length > 0", 20000,
        '用户端地图数据');
    const userPage = await evaluate(`(async () => {
      window.__e2eErrors = [];
      window.addEventListener('error', (event) => window.__e2eErrors.push(String(event.message)));
      window.addEventListener('unhandledrejection', (event) => window.__e2eErrors.push(
        String(event.reason)));
      await new Promise((resolve) => setTimeout(resolve, 900));
      const resources = performance.getEntriesByType('resource').map((entry) => entry.name);
      const mapCanvas = document.getElementById('map-canvas');
      const playerCanvas = document.getElementById('map-players');
      const header = document.querySelector('.app-header')?.getBoundingClientRect();
      const link = document.querySelector('.user-view-link')?.getBoundingClientRect();
      const userLive = await window.SamsaraUI.api('/api/user/map/live');
      const userWorlds = await window.SamsaraUI.api('/api/user/worlds');
      const worldStacks = Array.isArray(userWorlds.stacks) ? userWorlds.stacks : [];
      const livePlayers = Array.isArray(userLive.players) ? userLive.players : [];
      const liveEntities = Array.isArray(userLive.entities) ? userLive.entities : [];
      return {
        apiBase: window.SamsaraMapApiBase,
        publicToken: window.SamsaraUI?.getToken?.(),
        statusChips: document.querySelectorAll('#user-status .status-chip').length,
        worldCards: document.querySelectorAll('#user-worlds .world-stack-card').length,
        worldVisuals: document.querySelectorAll('#user-worlds .world-stack-card-visual').length,
        worldFrames: document.querySelectorAll('#user-worlds .world-stack-icon-frame').length,
        worldText: document.getElementById('user-worlds')?.textContent || '',
        worldStacks,
        headerBottomGap: header && link ? Math.round(header.bottom - link.bottom) : -1,
        headerTopGap: header && link ? Math.round(link.top - header.top) : -1,
        horizontalOverflow: document.documentElement.scrollWidth
          > document.documentElement.clientWidth + 2,
        mapStats: document.getElementById('map-stats')?.textContent.trim() || '',
        livePlayers: livePlayers.length,
        liveEntities: liveEntities.length,
        liveChat: Array.isArray(userLive.chat) ? userLive.chat.length : -1,
        liveChatHasUuid: Array.isArray(userLive.chat)
          && userLive.chat.some((item) => 'uuid' in item || 'actorUuid' in item),
        liveSystem: Array.isArray(userLive.system) ? userLive.system.length : -1,
        liveSystemHasUuid: Array.isArray(userLive.system)
          && userLive.system.some((item) => 'uuid' in item || 'actorUuid' in item),
        liveHasUuid: livePlayers.some((item) => 'uuid' in item)
          || liveEntities.some((item) => 'uuid' in item),
        liveHasHealth: livePlayers.some((item) => 'health' in item)
          || liveEntities.some((item) => 'health' in item),
        overlaySized: Boolean(playerCanvas && mapCanvas
          && playerCanvas.width === mapCanvas.width
          && playerCanvas.height === mapCanvas.height
          && playerCanvas.width > 0),
        adminScript: Boolean(document.querySelector('script[src="/app.js"]')),
        userCalls: resources.filter((url) => url.includes('/api/user/')).length,
        adminCalls: resources.filter((url) => url.includes('/api/config')
          || url.includes('/api/server') || url.includes('/api/world-stack')
          || url.includes('/api/permissions')).length,
        errors: window.__e2eErrors.slice(),
      };
    })()`);
    check('用户端无需 token 并走用户 API', userPage.apiBase === '/api/user/map'
        && userPage.publicToken === 'public' && userPage.userCalls >= 2,
        JSON.stringify({ base: userPage.apiBase, token: userPage.publicToken,
          calls: userPage.userCalls }));
    check('用户端显示服务器概况/世界栈/种子', userPage.statusChips >= 3
        && userPage.worldCards >= 1 && userPage.worldText.includes('种子')
        && userPage.worldText.includes('在线'), JSON.stringify({ chips: userPage.statusChips,
          cards: userPage.worldCards, text: userPage.worldText.slice(0, 120) }));
    check('用户端世界栈卡片复用图标视觉区', userPage.worldVisuals === userPage.worldCards
        && userPage.worldFrames === userPage.worldCards
        && !userPage.worldText.includes('stack-icons')
        && !userPage.worldText.includes('icon.png'),
        JSON.stringify({ cards: userPage.worldCards, visuals: userPage.worldVisuals }));
    check('用户端右上角按钮与 header 有留白且不溢出', userPage.headerBottomGap >= 4
        && userPage.headerTopGap >= 4 && !userPage.horizontalOverflow,
        JSON.stringify({ top: userPage.headerTopGap, bottom: userPage.headerBottomGap,
          overflow: userPage.horizontalOverflow }));
    check('用户端地图复用玩家独立图层', userPage.mapStats.length > 0 && userPage.overlaySized,
        JSON.stringify({ stats: userPage.mapStats.slice(0, 80), overlay: userPage.overlaySized }));
    check('用户端实时层保留实体/轨迹/发言但不含 UUID/生命', userPage.liveEntities >= 1
        && userPage.liveHasUuid === false
        && userPage.liveHasHealth === false
        && userPage.liveChat >= 0 && userPage.liveChatHasUuid === false,
        JSON.stringify({ players: userPage.livePlayers,
          entities: userPage.liveEntities, uuid: userPage.liveHasUuid,
          health: userPage.liveHasHealth, chat: userPage.liveChat,
          chatUuid: userPage.liveChatHasUuid }));
    check('用户端系统提示只保留时间/文本', userPage.liveSystem >= 0
        && userPage.liveSystemHasUuid === false,
        JSON.stringify({ system: userPage.liveSystem, uuid: userPage.liveSystemHasUuid }));
    check('用户端世界栈栈顶在前、stack_0 在最后', userPage.worldStacks.length >= 1
        && userPage.worldStacks[0].top === true
        && userPage.worldStacks.every((stack, index) => index === 0
          || Number(stack.index) < Number(userPage.worldStacks[index - 1].index))
        && userPage.worldStacks.every((stack) => String(stack.label || '').length > 0),
        JSON.stringify(userPage.worldStacks));
    check('用户端不加载管理端脚本/不调用管理 API', userPage.adminScript === false
        && userPage.adminCalls === 0, JSON.stringify({ script: userPage.adminScript,
          adminCalls: userPage.adminCalls }));
    check('用户端没有未捕获脚本错误', userPage.errors.length === 0,
        userPage.errors.slice(0, 3).join(' | '));
    await fetch(base + '/api/server/console', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ command: 'kill @e[tag=samsara_e2e]' }),
    });
    await fetch(base + '/api/server/console', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ command: 'forceload remove 0 0' }),
    });

    if (process.env.SAMSARA_E2E_SCREENSHOT) {
      // 供人工快速看布局；默认不写文件。
      await evaluate("document.getElementById('map-overview-toggle')?.click(); true;");
      await sleep(1200);
      const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
      fs.writeFileSync(process.env.SAMSARA_E2E_SCREENSHOT, Buffer.from(shot.data, 'base64'));
      console.log('screenshot: ' + process.env.SAMSARA_E2E_SCREENSHOT);
    }

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
