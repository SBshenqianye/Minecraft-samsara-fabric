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
    await navigate(base + '/vanilla/config');
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
    // 后续整页导航时自动补 token，避免极少数渲染进程重载丢失 sessionStorage。
    await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
      source: `sessionStorage.setItem('samsara-token', ${JSON.stringify(token)});`,
    });
    await until("document.querySelectorAll('#server-config-files > details.file-block').length >= 6", 25000, '本体配置文件');

    const webVersion = await evaluate(`(async () => {
      const status = await window.SamsaraUI.api('/api/status');
      return { header: document.getElementById('app-version')?.textContent || '',
        web: status.webVersion, mod: status.version };
    })()`);
    check('Web 版本号独立于模组版本', Boolean(webVersion.web)
        && webVersion.header === 'v' + webVersion.web, JSON.stringify(webVersion));

    const pageKit = await evaluate(`(() => {
      const result = window.SamsaraPageKit.selfTest();
      result.cleaned = !window.SamsaraPageKit.has('__pagekit_self_test__');
      return result;
    })()`);
    check('页面生命周期基础设施可用', pageKit.mounted === 1 && pageKit.unmounted === 1
        && pageKit.disposed === true && pageKit.pending === 0
        && pageKit.listenerRemoved === 1 && pageKit.cleaned === true,
        JSON.stringify(pageKit));

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

    const motdEditor = await evaluate(`(() => {
      const entry = [...document.querySelectorAll('#server-config-files .entry')]
        .find((node) => node.querySelector('.entry-key')?.textContent.includes('motd'));
      const editor = entry?.querySelector('.chat-text-editor');
      const input = editor?.querySelector('.chat-text-input');
      const cells = editor ? [...editor.querySelectorAll('.chat-text-color-grid .claim-color-cell')] : [];
      const before = input ? input.value : '';
      if (cells[10]) cells[10].click();
      const after = input ? input.value : '';
      if (input) {
        input.value = before;
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }
      return { found: Boolean(editor), cells: cells.length,
        inserted: after !== before && after.includes('§'),
        serverList: Boolean(editor?.querySelector('.server-list-card')),
        twoLines: editor?.querySelectorAll('.server-list-line').length || 0,
        hasSignal: Boolean(editor?.querySelector('.server-list-signal')),
        plainToggle: Boolean(editor?.querySelector('.chat-text-toggle[aria-pressed]')),
        lineWhiteSpace: editor?.querySelector('.server-list-line')
          ? getComputedStyle(editor.querySelector('.server-list-line')).whiteSpace : '',
        cardWidth: Math.round(editor?.querySelector('.server-list-card')?.getBoundingClientRect().width || 0),
        previewSpans: editor?.querySelectorAll('.chat-text-rendered span').length || 0 };
    })()`);
    check('MOTD 使用 4×4 固定色板', motdEditor.found && motdEditor.cells === 16,
        JSON.stringify(motdEditor));
    check('MOTD 选色直接插入并渲染', motdEditor.inserted && motdEditor.previewSpans > 0,
        JSON.stringify(motdEditor));
    check('MOTD 预览为服务器列表样式', motdEditor.serverList === true
        && motdEditor.twoLines >= 1 && motdEditor.hasSignal === true
        && motdEditor.plainToggle === false, JSON.stringify(motdEditor));
    check('MOTD 预览不自动换行且宽度充足', motdEditor.lineWhiteSpace === 'nowrap'
        && motdEditor.cardWidth > 360, JSON.stringify(motdEditor));

    const propertyKeys = await evaluate(`(() => {
      const file = [...document.querySelectorAll('#server-config-files > details.file-block')]
        .find((node) => node.id.includes('server-properties'));
      const keys = [...(file?.querySelectorAll('.entry-key') || [])]
        .map((node) => node.textContent.trim());
      return { bad: keys.filter((key) => key.startsWith('\\\\#') || key.startsWith('\\uFEFF') || key.includes('Minecraft server properties')) };
    })()`);
    check('server.properties 注释不会显示为配置项', propertyKeys.bad.length === 0,
        JSON.stringify(propertyKeys));

    const segmentedFit = await evaluate(`(() => {
      const groups = [...document.querySelectorAll('#server-config-files .segmented')];
      return {
        total: groups.length,
        overflow: groups.filter((node) => node.scrollWidth > node.clientWidth + 2).length,
        textOverflow: groups.flatMap((node) => [...node.querySelectorAll('.segmented-btn')])
          .filter((node) => node.scrollWidth > node.clientWidth + 1).length,
      };
    })()`);
    check('分段按钮适配中文与段数宽度', segmentedFit.total > 0
        && segmentedFit.overflow === 0 && segmentedFit.textOverflow === 0,
        JSON.stringify(segmentedFit));

    // MOTD 测试会修改草稿；重新读取，避免影响后续“未保存计数”断言。
    await evaluate(`(async () => {
      if (window.SamsaraServer?.reload) await window.SamsaraServer.reload();
      return true;
    })()`);
    await until("document.querySelectorAll('#server-config-files > details.file-block').length >= 6",
        15000, '服务器配置重载');

    const resetBehavior = await evaluate(`(async () => {
      const find = (key) => [...document.querySelectorAll('#server-config-files .entry')]
        .find((entry) => (entry.querySelector('.entry-key')?.textContent || '')
          .trim().split(/\\s+/)[0] === key);
      const resetOf = (entry) => [...(entry?.querySelectorAll('.entry-actions button') || [])]
        .find((button) => button.textContent.trim() === '恢复默认');
      const save = document.getElementById('server-save');
      resetOf(find('motd'))?.click();
      await new Promise((resolve) => setTimeout(resolve, 120));
      const sameLabel = save.textContent;
      const pauseBefore = find('pause-when-empty-seconds')?.querySelector('input')?.value || '';
      resetOf(find('pause-when-empty-seconds'))?.click();
      await new Promise((resolve) => setTimeout(resolve, 120));
      const pauseAfter = find('pause-when-empty-seconds')?.querySelector('input')?.value || '';
      return { sameLabel, pauseBefore, pauseAfter, changedLabel: save.textContent };
    })()`);
    check('恢复默认使用真实默认值', resetBehavior.pauseAfter === '60'
        && resetBehavior.pauseAfter !== resetBehavior.pauseBefore, JSON.stringify(resetBehavior));
    check('恢复默认无变化时不产生假修改', resetBehavior.sameLabel === '保存并重载',
        JSON.stringify(resetBehavior));

    // 恢复默认测试会修改草稿；再重载一次，避免影响后续断言。
    await evaluate(`(async () => {
      if (window.SamsaraServer?.reload) await window.SamsaraServer.reload();
      return true;
    })()`);
    await until("document.querySelectorAll('#server-config-files > details.file-block').length >= 6",
        15000, '服务器配置重载');

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
      const panel = document.querySelector('#panel-identity');
      const panelText = panel ? panel.textContent : '';
      const zoneLabels = [...document.querySelectorAll('#panel-identity .zone-label > span:first-child')]
        .map((el) => el.textContent.trim());
      const poolSummaries = [...document.querySelectorAll('#panel-identity .pool-section > summary')]
        .map((el) => el.textContent.replace(/\\s+/g, ' ').trim());
      const assignRoles = [...document.querySelectorAll('#assign-role option')]
        .map((el) => el.textContent.trim());
      const identityTerms = {
        hasTypes: panelText.includes('正版账号') && panelText.includes('离线账号'),
        hasOnline: panelText.includes('当前在线'),
        zonesOk: zoneLabels.includes('正版账号 UUID') && zoneLabels.includes('离线账号 UUID'),
        poolsOk: poolSummaries.some((text) => text.startsWith('正版账号 UUID'))
          && poolSummaries.some((text) => text.startsWith('离线账号 UUID')),
        assignOk: assignRoles.some((text) => text.startsWith('正版账号 UUID'))
          && assignRoles.some((text) => text.startsWith('离线账号 UUID')),
        bareOld: panelText.includes('正版 UUID') || panelText.includes('离线 UUID'),
        zones: zoneLabels,
      };
      window.SamsaraUI.setActiveTab('output');
      await new Promise((resolve) => setTimeout(resolve, 600));
      const output = document.querySelectorAll('#output-auto-continue .segmented-btn').length;
      window.SamsaraUI.setActiveTab('permissions');
      await new Promise((resolve) => setTimeout(resolve, 900));
      const permissions = document.querySelectorAll('.perm-row .segmented').length;
      window.SamsaraUI.setActiveTab('lottery');
      await new Promise((resolve) => setTimeout(resolve, 900));
      const lotteryPanel = document.querySelector('#panel-lottery');
      const lotteryTab = [...document.querySelectorAll('.tab[data-panel]')]
        .find((tab) => tab.dataset.panel === 'panel-lottery');
      const lottery = {
        hidden: lotteryPanel ? lotteryPanel.hidden : null,
        iframe: Boolean(document.querySelector('#panel-lottery iframe')),
        stats: document.querySelectorAll('#lottery-stats .stat').length,
        values: document.querySelectorAll('#lottery-values .lottery-value-row').length,
        history: document.querySelectorAll('#lottery-history .lottery-row').length,
        poolHeads: document.querySelectorAll('#panel-lottery .lottery-pool-head').length,
        pool: Boolean(document.getElementById('lottery-pool')),
        bettingPool: Boolean(document.getElementById('lottery-betting-pool')),
        claimsPanel: Boolean(document.getElementById('lottery-claims-panel')),
        tab: lotteryTab ? lotteryTab.textContent.replace(/\\s+/g, ' ').trim() : '',
      };
      window.SamsaraUI.setActiveTab('config');
      await new Promise((resolve) => setTimeout(resolve, 600));
      const configBool = document.querySelectorAll('#files .entry-control .segmented').length;
      return { identity, identityTerms, output, permissions, configBool, lottery };
    })()`);
    check('身份组布尔改为按钮', mods.identity === 2, 'buttons=' + mods.identity);
    check('身份组术语区分账号类型与在线状态',
        mods.identityTerms.hasTypes && mods.identityTerms.hasOnline
          && mods.identityTerms.zonesOk && mods.identityTerms.poolsOk
          && mods.identityTerms.assignOk && !mods.identityTerms.bareOld,
        JSON.stringify(mods.identityTerms));

    const identityData = await evaluate(`(async () => {
      window.SamsaraUI.setActiveTab('identity');
      await new Promise((resolve) => setTimeout(resolve, 900));
      const chip = document.querySelector('#panel-identity .uuid-chip.data');
      const detail = chip ? [...chip.querySelectorAll('.chip-action')]
        .find((node) => node.textContent.trim() === '详情') : null;
      if (detail) detail.click();
      await new Promise((resolve) => setTimeout(resolve, 1200));
      const dialog = document.getElementById('identity-data-dialog');
      const text = document.getElementById('identity-data-content')?.textContent || '';
      const copyButton = [...document.querySelectorAll('#identity-data-content button')]
        .find((node) => node.textContent.includes('复制 UUID'));
      const backupButton = [...document.querySelectorAll('#identity-data-content button')]
        .find((node) => node.textContent.includes('备份数据'));
      const editButton = [...document.querySelectorAll('#identity-data-content button')]
        .find((node) => node.textContent.trim() === '编辑身份组背包');
      const wasOpen = Boolean(dialog && dialog.open);
      if (editButton) editButton.click();
      await new Promise((resolve) => setTimeout(resolve, 1600));
      const bigDialog = document.querySelector('.identity-inventory-dialog');
      const bigSlots = bigDialog ? bigDialog.querySelectorAll('.slot').length : 0;
      const iframe = document.querySelector('#identity-data-content iframe');
      const closeBig = bigDialog?.querySelector('.el-dialog__headerbtn');
      if (closeBig) closeBig.click();
      await new Promise((resolve) => setTimeout(resolve, 400));
      if (dialog && dialog.open) dialog.close();
      return { open: wasOpen, text,
        hasCopy: Boolean(copyButton), hasBackup: Boolean(backupButton),
        hasEdit: Boolean(editButton), hasBackupList: text.includes('备份记录'),
        bigSlots, hasIframe: Boolean(iframe),
        cardButton: [...document.querySelectorAll('#panel-identity .card-actions button')]
          .some((node) => node.textContent.trim() === '编辑身份组背包'),
        hasInventory: text.includes('背包'), hasEnder: text.includes('末影箱'),
        hasAdvancements: text.includes('成就') };
    })()`);
    check('身份组 data UUID 可查看详情', identityData.open && identityData.hasInventory
        && identityData.hasEnder && identityData.hasAdvancements
        && identityData.hasCopy && identityData.hasBackup && identityData.hasEdit
        && identityData.hasBackupList,
        JSON.stringify({ open: identityData.open, hasInventory: identityData.hasInventory,
          hasEnder: identityData.hasEnder, hasAdvancements: identityData.hasAdvancements,
          hasCopy: identityData.hasCopy, hasBackup: identityData.hasBackup,
          hasEdit: identityData.hasEdit, hasBackupList: identityData.hasBackupList,
          text: identityData.text.slice(0, 160) }));
    check('身份组背包改用大窗口且不再使用 iframe',
        identityData.cardButton === true && identityData.hasIframe === false
        && identityData.bigSlots === 41,
        JSON.stringify({ cardButton: identityData.cardButton,
          hasIframe: identityData.hasIframe, bigSlots: identityData.bigSlots }));
    check('输出格式布尔改为按钮', mods.output === 2, 'buttons=' + mods.output);
    check('权限树布尔改为按钮', mods.permissions > 0, 'segmented=' + mods.permissions);
    check('配置页布尔改为按钮', mods.configBool > 0, 'segmented=' + mods.configBool);

    // ---- PageSpec/FormSpec 配置编辑器（F-168，只读检查 + 预览不写盘） ----
    const editor = await evaluate(`(async () => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      window.SamsaraUI.setActiveTab('config-editor');
      await wait(1200);
      const nav = [...document.querySelectorAll('#global-nav .nav-item')];
      const groups = nav.map((node) => node.textContent.trim());
      window.SamsaraConfigEditor?.setSourceFilter?.('third-party');
      await wait(600);
      const thirdNav = [...document.querySelectorAll('#global-nav .nav-item')];
      const third = thirdNav.find((node) => (node.dataset.target || '').startsWith('third-party:'));
      let fields = 0;
      let title = '';
      let previewOpen = false;
      let previewText = '';
      if (third) {
        third.click();
        await wait(900);
        fields = document.querySelectorAll('#config-editor-body .entry').length;
        title = document.querySelector('#config-editor-body h3')?.textContent || '';
        const preview = [...document.querySelectorAll('#config-editor-body button')]
          .find((node) => node.textContent.includes('预览'));
        if (preview) {
          preview.click();
          await wait(1400);
          const dialog = document.getElementById('config-editor-preview-dialog');
          previewOpen = Boolean(dialog && dialog.open);
          previewText = document.getElementById('config-editor-preview-sub')?.textContent || '';
          if (dialog && dialog.open) dialog.close();
        }
      }
      window.SamsaraConfigEditor?.setSourceFilter?.('samsara');
      return { nav: nav.length, groups, fields, title, previewOpen, previewText,
        summary: document.getElementById('config-editor-summary')?.textContent || '' };
    })()`);
    check('配置编辑器只加载 samsara 自有 PageSpec 页面', editor.nav >= 3
        && editor.groups.some((text) => text.includes('samsara'))
        && !editor.groups.some((text) => text.includes('服务器本体'))
        && !editor.groups.some((text) => text.includes('第三方模组')),
        JSON.stringify({ nav: editor.nav, groups: editor.groups }));
    check('配置编辑器 source 过滤后可渲染第三方 FormSpec 字段', editor.fields > 0
        && editor.title.length > 0, JSON.stringify({ fields: editor.fields, title: editor.title }));
    check('配置编辑器预览弹窗可用且不写盘', editor.previewOpen
        && editor.previewText.includes('将修改'), JSON.stringify({ open: editor.previewOpen,
          text: editor.previewText }));

    // ---- Web 公告：列表/表单/预览 ----
    const announcements = await evaluate(`(async () => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      window.SamsaraUI.setActiveTab('announcements');
      await wait(1000);
      const items = document.querySelectorAll('#announcements-list .announcement-item').length;
      const fields = document.querySelectorAll('#announcements-editor .announcement-row').length;
      const preview = [...document.querySelectorAll('#announcements-editor button')]
        .find((node) => node.textContent.trim() === '预览');
      let previewText = '';
      if (preview) {
        preview.click();
        await wait(900);
        previewText = document.querySelector('#announcement-preview')?.textContent || '';
      }
      return { items, fields, previewText,
        summary: document.getElementById('announcements-summary')?.textContent || '',
        navHidden: document.getElementById('global-nav')?.hidden === true,
        navItems: document.querySelectorAll('#global-nav .nav-item').length,
        noNav: document.querySelector('main.app-shell')?.classList.contains('no-nav') === true,
        activePage: window.SamsaraUI.activePage?.(),
        hasScope: Boolean(window.SamsaraUI.pageScope?.()) };
    })()`);
    check('公告页加载列表与编辑表单', announcements.items >= 1
        && announcements.fields >= 5, JSON.stringify({ items: announcements.items,
          fields: announcements.fields }));
    check('公告页不残留上一页导航', announcements.navHidden
        && announcements.navItems === 0 && announcements.noNav
        && announcements.activePage === 'announcements' && announcements.hasScope,
        JSON.stringify({ hidden: announcements.navHidden, items: announcements.navItems,
          noNav: announcements.noNav, page: announcements.activePage,
          scope: announcements.hasScope }));
    check('公告预览显示变量替换结果', announcements.previewText.includes('预览')
        && announcements.previewText.includes('聊天'), JSON.stringify({
          preview: announcements.previewText.slice(0, 160) }));

    const agentWhoami = await evaluate(`window.SamsaraUI.api('/api/agent/whoami')`);
    check('Agent whoami 返回 actor/scope/限流', agentWhoami.ok === true
        && agentWhoami.actor === 'admin' && agentWhoami.admin === true
        && Array.isArray(agentWhoami.scopes) && agentWhoami.scopes.includes('announce')
        && agentWhoami.limits && agentWhoami.limits.readPerMinute > 0,
        JSON.stringify(agentWhoami));

    const idempotency = await evaluate(`(async () => {
      const token = window.SamsaraUI.getToken();
      const key = 'e2e-idem-' + Date.now();
      const headers = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json',
        'Idempotency-Key': key };
      const body = JSON.stringify({ file: 'samsara:commands', values: {} });
      const first = await fetch('/api/config/preview', { method: 'POST', headers, body });
      const second = await fetch('/api/config/preview', { method: 'POST', headers, body });
      return { first: first.status, second: second.status,
        replay: second.headers.get('Idempotency-Replayed') };
    })()`);
    check('Idempotency-Key 重放同一写请求', idempotency.first === 200
        && idempotency.second === 200 && idempotency.replay === 'true',
        JSON.stringify(idempotency));

    check('经济业务标签为原生面板', mods.lottery && mods.lottery.hidden === false
        && mods.lottery.iframe === false && mods.lottery.stats >= 10
        && mods.lottery.values >= 1 && mods.lottery.poolHeads >= 2
        && mods.lottery.pool && mods.lottery.bettingPool
        && mods.lottery.claimsPanel
        && mods.lottery.tab.includes('经济业务'), JSON.stringify(mods.lottery || {}));

    const lotteryPools = await evaluate(`(async () => {
      const data = await window.SamsaraUI.api('/api/lottery');
      const user = await fetch('/api/user/lottery').then((response) => response.json());
      return {
        adminFields: ['activePot', 'bettingPot', 'activePotItems', 'bettingPotItems']
          .every((key) => key in data),
        claims: Array.isArray(data.claims),
        countingOwnPot: typeof data.countingDay?.ownPot === 'number',
        seed: typeof data.countingDay?.seedValue === 'number',
        gold: Boolean(data.gold && typeof data.gold.price === 'number'
          && typeof data.gold.blockValue === 'number'),
        userGold: Boolean(user.gold && typeof user.gold.blockValue === 'number'),
        userActive: Boolean(user.active && 'pot' in user.active
          && Array.isArray(user.active.items)),
        userRoundItems: Array.isArray(user.round?.items),
      };
    })()`);
    check('乐透奖池暴露待开奖/下注轮/待领取', lotteryPools.adminFields
        && lotteryPools.claims && lotteryPools.countingOwnPot && lotteryPools.userActive
        && lotteryPools.userRoundItems, JSON.stringify(lotteryPools));
    check('乐透种子与金价信息可用', lotteryPools.seed && lotteryPools.gold
        && lotteryPools.userGold, JSON.stringify({
          seed: lotteryPools.seed, gold: lotteryPools.gold, userGold: lotteryPools.userGold }));

    await navigate(base + '/samsara/economy/lottery');
    await until("location.pathname === '/samsara/economy/lottery'"
        + " && document.querySelector('.section-subnav [data-subnav=\"lottery\"]')",
        20000, '经济业务死人乐透页');
    const lotteryNav = await evaluate(`(async () => {
      await new Promise((resolve) => setTimeout(resolve, 700));
      const items = [...document.querySelectorAll('#global-nav .nav-item')]
        .map((node) => node.textContent.trim());
      return {
        title: document.querySelector('#global-nav .nav-title')?.textContent.trim() || '',
        items,
        saveHidden: document.getElementById('save')?.hidden,
      };
    })()`);
    check('经济业务死人乐透页使用通用左侧导航', lotteryNav.title.includes('经济业务')
        && lotteryNav.items.length >= 3 && lotteryNav.items.includes('当前轮次')
        && lotteryNav.items.includes('待领取') && lotteryNav.items.includes('历史开奖')
        && !lotteryNav.items.includes('物品价值表')
        && lotteryNav.saveHidden === true,
        JSON.stringify(lotteryNav));
    await evaluate("document.querySelector('.section-subnav [data-subnav=\"fund\"]').click(); true;");
    await until("location.pathname === '/samsara/economy/fund'", 15000, '经济业务服务器基金页');
    const fundNav = await evaluate(`(async () => {
      await new Promise((resolve) => setTimeout(resolve, 700));
      return [...document.querySelectorAll('#global-nav .nav-item')]
        .map((node) => node.textContent.trim());
    })()`);
    check('经济业务服务器基金页包含物品价值表', fundNav.includes('服务器基金')
        && fundNav.includes('物品价值表'), JSON.stringify(fundNav));

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
      fillToggle.checked = false;
      fillToggle.dispatchEvent(new Event('change', { bubbles: true }));
      await wait(220);
      const multiRawCells = Number(canvas.dataset.protectedCells);
      fillToggle.checked = true;
      fillToggle.dispatchEvent(new Event('change', { bubbles: true }));
      await wait(220);
      const multiFilledCells = Number(canvas.dataset.protectedCells);
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
        multiRawCells, multiFilledCells,
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
    check('闭合填充开关改变多旗闭合区域', claim.multiFilledCells > claim.multiRawCells,
        JSON.stringify({ raw: claim.multiRawCells, filled: claim.multiFilledCells }));

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
    await navigate(base + '/vanilla/console');
    await until("window.SamsaraUI && document.getElementById('server-console-panel')"
        + " && !document.getElementById('server-console-panel').hidden", 20000, 'RCON 控制台路由');
    const consoleState = await evaluate(`(async () => {
      const input = document.getElementById('server-console-command');
      const send = document.getElementById('server-console-send');
      input.value = 'list';
      send.click();
      await new Promise((resolve) => setTimeout(resolve, 1600));
      return {
        panelVisible: !document.getElementById('server-console-panel')?.hidden,
        output: document.getElementById('server-console-output')?.textContent || '',
        message: document.getElementById('server-console-message')?.textContent || '',
        path: location.pathname,
        hash: location.hash,
      };
    })()`);
    check('RCON 控制台可执行命令并回显', consoleState.panelVisible
        && consoleState.output.includes('> list') && consoleState.output.length > 8
        && consoleState.message.includes('完成'), JSON.stringify({
          output: consoleState.output.slice(0, 120), message: consoleState.message }));
    check('RCON 控制台入口使用规范路径且不写 hash', consoleState.path === '/vanilla/console'
        && consoleState.hash === '', JSON.stringify({ path: consoleState.path, hash: consoleState.hash }));

    const consoleComplete = await evaluate(`(async () => {
      const input = document.getElementById('server-console-command');
      input.value = 'samsara ';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
      await new Promise((resolve) => setTimeout(resolve, 1200));
      const box = document.getElementById('server-console-complete');
      return {
        hidden: box ? box.hidden : true,
        items: box ? box.querySelectorAll('.console-complete-item').length : 0,
        preview: box ? box.querySelector('.console-complete-preview')?.textContent || '' : '',
        first: box ? box.querySelector('.console-complete-text')?.textContent || '' : '',
      };
    })()`);
    check('RCON 控制台 Tab 补全与预览', !consoleComplete.hidden
        && consoleComplete.items > 0 && consoleComplete.preview.includes('将补全为')
        && consoleComplete.first.length > 0, JSON.stringify(consoleComplete));

    const betComplete = await evaluate(`(async () => {
      const data = await window.SamsaraUI.api('/api/server/console/complete', {
        method: 'POST',
        body: JSON.stringify({ command: 'samsara lottery bet ', cursor: 20 }),
      });
      return { ok: data.ok, texts: (data.suggestions || []).map((item) => item.text) };
    })()`);
    check('乐透下注命令提供确认/取消/快速通道', betComplete.ok
        && ['confirm', 'cancel', 'now'].every((text) => betComplete.texts.includes(text)),
        JSON.stringify(betComplete));

    // ---- 容器索引 API（P1）：创建测试箱子、扫描、列表、读取 ----
    const runConsole = (command) => fetch(base + '/api/server/console', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ command }),
    });
    await runConsole('forceload add 0 0');
    await runConsole('setblock 8 100 8 minecraft:chest');
    await runConsole('item replace block 8 100 8 container.0 with minecraft:diamond 3');
    await new Promise((resolve) => setTimeout(resolve, 400));
    const scanResult = await fetch(base + '/api/containers/scan', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ dimension: 'minecraft:overworld', x: 8, z: 8, radius: 16 }),
    }).then((response) => response.json());
    check('容器索引扫描接口可用', scanResult.ok === true && scanResult.indexSize >= 1,
        JSON.stringify(scanResult));
    await new Promise((resolve) => setTimeout(resolve, 1200));
    const containerList = await fetch(base
        + '/api/containers?dimension=minecraft:overworld&x=8&z=8&radius=16',
        { headers: authHeaders }).then((response) => response.json());
    const chest = (containerList.containers || []).find((row) =>
        row.x === 8 && row.y === 100 && row.z === 8);
    check('容器索引列出测试箱子', containerList.ok === true && !!chest
        && chest.type === 'minecraft:chest' && chest.nonEmpty >= 1,
        JSON.stringify(containerList));
    const containerRead = await fetch(base
        + '/api/containers/read?dimension=minecraft:overworld&x=8&y=100&z=8',
        { headers: authHeaders }).then((response) => response.json());
    check('容器读取返回完整槽位', containerRead.ok === true
        && containerRead.container && containerRead.container.slots.length === 27
        && containerRead.container.slots[0].length > 0,
        JSON.stringify(containerRead));
    const itemSearch = await fetch(base
        + '/api/containers?dimension=minecraft:overworld&q=diamond',
        { headers: authHeaders }).then((response) => response.json());
    check('容器支持按物品 ID/名称搜索', itemSearch.ok === true && itemSearch.total >= 1
        && (itemSearch.containers || []).some((row) => row.x === 8 && row.z === 8),
        JSON.stringify({ total: itemSearch.total, q: itemSearch.q }));
    const emptySearch = await fetch(base
        + '/api/containers?dimension=minecraft:overworld&q=definitely-not-an-item',
        { headers: authHeaders }).then((response) => response.json());
    check('容器物品搜索无匹配时返回空', emptySearch.ok === true && emptySearch.total === 0,
        JSON.stringify({ total: emptySearch.total }));

    const itemSchema = await fetch(base + '/api/items/schema',
        { headers: authHeaders }).then((response) => response.json());
    check('物品结构化 schema 提供附魔/药水/属性枚举', itemSchema.ok === true
        && (itemSchema.schema?.enchantments || []).length > 0
        && (itemSchema.schema?.potions || []).length > 0
        && (itemSchema.schema?.attributes || []).length > 0,
        JSON.stringify({ enchant: itemSchema.schema?.enchantments?.length,
          potion: itemSchema.schema?.potions?.length,
          attribute: itemSchema.schema?.attributes?.length }));
    const stoneDescribe = await fetch(base + '/api/items/describe', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ id: 'minecraft:stone', count: 1 }),
    }).then((response) => response.json());
    check('未附魔石头不发光且结构化字段完整', stoneDescribe.ok === true
        && stoneDescribe.item?.glint === false && stoneDescribe.item?.id === 'minecraft:stone'
        && Array.isArray(stoneDescribe.item?.enchantments)
        && Array.isArray(stoneDescribe.item?.attributes),
        JSON.stringify({ glint: stoneDescribe.item?.glint, id: stoneDescribe.item?.id }));

    const rawSnbt = await fetch(base + '/api/items/raw', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ snbt: stoneDescribe.item?.snbt || '', count: 1 }),
    }).then((response) => response.json());
    check('原始 NBT 编辑接口可用', rawSnbt.ok === true && rawSnbt.item?.id === 'minecraft:stone',
        JSON.stringify({ ok: rawSnbt.ok, id: rawSnbt.item?.id, error: rawSnbt.error }));

    const webInv = await fetch(base + '/api/inventory/web', { headers: authHeaders })
      .then((response) => response.json());
    check('Web 幽灵背包可读取且固定 54 格', webInv.ok === true
        && webInv.size === 54 && webInv.slots?.length === 54, JSON.stringify({ size: webInv.size }));
    const webStone = await (await fetch(base + '/api/inventory/item', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ id: 'minecraft:stone', count: 2 }),
    })).json();
    const empty54 = Array(54).fill('');
    const webSlots = empty54.slice();
    webSlots[0] = webStone.encoded;
    const webWrite = await fetch(base + '/api/inventory/web/write', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ slots: webSlots }),
    }).then((response) => response.json());
    check('Web 幽灵背包可写入并保留编码', webWrite.ok === true
        && webWrite.slots?.[0] === webStone.encoded, JSON.stringify({ ok: webWrite.ok }));
    const webDeposit = await fetch(base + '/api/inventory/web/deposit', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ encoded: webStone.encoded }),
    }).then((response) => response.json());
    check('Web 幽灵背包支持存入物品', webDeposit.ok === true, JSON.stringify({ ok: webDeposit.ok }));
    const webWithdraw = await fetch(base + '/api/inventory/web/withdraw', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ index: 0, count: 1 }),
    }).then((response) => response.json());
    check('Web 幽灵背包支持取回物品', webWithdraw.ok === true && webWithdraw.item?.count === 1,
        JSON.stringify({ ok: webWithdraw.ok }));
    await fetch(base + '/api/inventory/web/write', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ slots: Array(54).fill('') }),
    });

    const fundInv = await fetch(base + '/api/lottery/fund', { headers: authHeaders })
      .then((response) => response.json());
    check('基金仓库可读取且至少 54 格', fundInv.ok === true
        && fundInv.size >= 54 && fundInv.slots?.length >= 54 && fundInv.minSize === 54,
        JSON.stringify({ size: fundInv.size, minSize: fundInv.minSize }));
    const fundWrite = await fetch(base + '/api/lottery/fund/write', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ slots: fundInv.slots || [] }),
    }).then((response) => response.json());
    check('基金仓库可写回且保持编码', fundWrite.ok === true
        && (fundInv.slots || []).every((slot, index) => (fundWrite.slots?.[index] || '') === (slot || '')),
        JSON.stringify({ ok: fundWrite.ok }));
    const expanded = (fundInv.slots || []).concat(Array(9).fill(''));
    const fundExpand = await fetch(base + '/api/lottery/fund/write', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ slots: expanded }),
    }).then((response) => response.json());
    check('基金仓库没有 54 格硬上限（可扩容）', fundExpand.ok === true
        && fundExpand.size === expanded.length,
        JSON.stringify({ ok: fundExpand.ok, size: fundExpand.size }));
    await fetch(base + '/api/lottery/fund/write', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ slots: fundInv.slots || [] }),
    });

    const identityList = await fetch(base + '/api/identity/data/list', { headers: authHeaders })
      .then((response) => response.json());
    check('data UUID 列表来自 identity/playerdata 磁盘扫描', identityList.ok === true
        && Array.isArray(identityList.data)
        && identityList.data.every((row) => typeof row.uuid === 'string'
          && 'label' in row && 'group' in row && 'backups' in row),
        JSON.stringify({ ok: identityList.ok, count: identityList.data?.length }));
    const identityModel = await fetch(base + '/api/identity', { headers: authHeaders })
      .then((response) => response.json());
    const labelTarget = identityList.data?.[0]?.uuid
      || identityModel.config?.groups?.find((group) => group.data)?.data;
    if (labelTarget) {
      const labelSet = await fetch(base + '/api/identity/data/label', {
        method: 'POST', headers: authHeaders,
        body: JSON.stringify({ uuid: labelTarget, name: 'E2E 临时命名', note: 'e2e' }),
      }).then((response) => response.json());
      const afterLabel = await fetch(base + '/api/identity', { headers: authHeaders })
        .then((response) => response.json());
      const saved = afterLabel.config?.dataLabels?.[labelTarget]?.name === 'E2E 临时命名';
      await fetch(base + '/api/identity/data/label', {
        method: 'POST', headers: authHeaders,
        body: JSON.stringify({ uuid: labelTarget, name: '', note: '' }),
      });
      check('data UUID 名称/备注可保存并清除', labelSet.ok === true && saved === true,
          JSON.stringify({ ok: labelSet.ok, saved }));
      const backups = await fetch(
          base + '/api/identity/data/backups?uuid=' + encodeURIComponent(labelTarget),
          { headers: authHeaders }).then((response) => response.json());
      check('data UUID 备份记录可列出', backups.ok === true
          && Array.isArray(backups.backups) && typeof backups.root === 'string',
          JSON.stringify({ ok: backups.ok, count: backups.backups?.length }));
    }

    const auditPage = await fetch(base + '/api/audit?offset=0&limit=5',
        { headers: authHeaders }).then((response) => response.json());
    const auditPage2 = await fetch(base + '/api/audit?offset=5&limit=5',
        { headers: authHeaders }).then((response) => response.json());
    check('Web 审计支持分页懒加载', auditPage.ok === true
        && (auditPage.entries || []).length <= 5
        && (auditPage2.entries || []).length <= 5
        && (auditPage.entries?.[0]?.id || '') !== (auditPage2.entries?.[0]?.id || ''),
        JSON.stringify({ first: auditPage.entries?.length, second: auditPage2.entries?.length }));
    const beforeWrite = containerRead.container;
    const movedSlots = beforeWrite.slots.slice();
    movedSlots[1] = movedSlots[0];
    movedSlots[0] = '';
    const writeResponse = await fetch(base + '/api/containers/write', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({
        dimension: 'minecraft:overworld', x: 8, y: 100, z: 8,
        revision: beforeWrite.revision, slots: movedSlots,
      }),
    });
    const writeBody = await writeResponse.json();
    check('容器写入成功并提升 revision', writeResponse.status === 200
        && writeBody.ok === true && writeBody.container
        && writeBody.container.revision > beforeWrite.revision,
        JSON.stringify({ status: writeResponse.status, body: writeBody }));
    const conflictResponse = await fetch(base + '/api/containers/write', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({
        dimension: 'minecraft:overworld', x: 8, y: 100, z: 8,
        revision: beforeWrite.revision, slots: movedSlots,
      }),
    });
    const conflictBody = await conflictResponse.json();
    check('容器写入 revision 冲突返回 409', conflictResponse.status === 409
        && conflictBody.ok === false && conflictBody.currentRevision >= beforeWrite.revision,
        JSON.stringify({ status: conflictResponse.status, body: conflictBody }));
    const auditAfterContainer = await fetch(base + '/api/audit', { headers: authHeaders })
        .then((response) => response.json());
    const containerAudit = (auditAfterContainer.entries || []).find((entry) =>
        entry.action === '/api/containers/write');
    check('容器写入产生 Web 审计快照', !!containerAudit
        && containerAudit.rollbackable === false
        && (containerAudit.note || '').includes('8,100,8'),
        JSON.stringify(containerAudit));

    // ---- 一级分栏：切到“本体”后经济业务标签不能残留 ----
    const sectionSwitch = await evaluate(`(async () => {
      const lottery = document.getElementById('panel-lottery');
      document.querySelector('.top-tab[data-section="mods"]')?.click();
      await new Promise((resolve) => setTimeout(resolve, 200));
      window.SamsaraUI.setActiveTab('lottery');
      await new Promise((resolve) => setTimeout(resolve, 200));
      document.querySelector('.top-tab[data-section="server"]')?.click();
      await new Promise((resolve) => setTimeout(resolve, 400));
      const visible = lottery ? getComputedStyle(lottery).display !== 'none' : false;
      return { visible };
    })()`);
    check('切到本体后经济业务面板隐藏', sectionSwitch.visible === false,
        JSON.stringify(sectionSwitch));

    // ---- Web 审计：写请求前快照，可预览回滚 ----
    const noAuthAudit = await fetch(base + '/api/audit');
    check('Web 审计需要管理端 token', noAuthAudit.status === 401,
        'status=' + noAuthAudit.status);
    await navigate(base + '/samsara/system/audit');
    await until("window.SamsaraUI && document.getElementById('server-audit-panel')"
        + " && !document.getElementById('server-audit-panel').hidden", 20000, 'Web 审计路由');
    const auditState = await evaluate(`(async () => {
      await new Promise((resolve) => setTimeout(resolve, 3000));
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
        path: location.pathname,
        hash: location.hash,
        section: document.querySelector('.section-tab.active')?.textContent.trim() || '',
      };
    })()`);
    check('Web 审计有写请求快照记录', auditState.panelVisible && auditState.rows >= 1
        && auditState.text.includes('文件') && auditState.rowsWithRollback >= 1,
        JSON.stringify({ rows: auditState.rows, rollback: auditState.rowsWithRollback,
          text: auditState.text.slice(0, 120), panel: auditState.panelVisible,
          message: auditState.loadMessage }));
    check('Web 审计预览可用', auditState.previewMessage.includes('快照')
        && auditState.hash === '' && auditState.path === '/samsara/system/audit'
        && auditState.section === '系统', JSON.stringify({
          message: auditState.previewMessage, hash: auditState.hash, path: auditState.path,
          section: auditState.section }));
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

    // ---- 容器 Web UI（Vue）：路由、地图/列表、槽位、结构化编辑器、预设保存 ----
    await navigate(base + '/containers');
    await until("document.querySelector('.container-page')", 20000, '容器页');
    await until("document.querySelector('.container-row') || document.querySelector('.container-message.error')",
        30000, '容器列表');
    const containerUi = await evaluate(`(async () => {
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      window.__containerErrors = [];
      window.addEventListener('error', (event) => window.__containerErrors.push(String(event.message)));
      window.addEventListener('unhandledrejection', (event) => window.__containerErrors.push(String(event.reason)));
      const rows = [...document.querySelectorAll('.container-row')];
      const target = rows.find((row) => row.textContent.includes('8, 100, 8')) || rows[0];
      if (!target) return { error: 'no row', text: document.body.innerText.slice(0, 200) };
      target.click();
      const deadline = Date.now() + 8000;
      while (!document.querySelector('.slot-grid') && Date.now() < deadline) {
        await sleep(100);
      }
      const slots = document.querySelectorAll('.slot').length;
      const itemSlot = [...document.querySelectorAll('.slot')]
        .find((slot) => slot.querySelector('.item-icon-view'));
      const emptySlot = [...document.querySelectorAll('.slot')]
        .find((slot) => !slot.querySelector('.item-icon-view'));
      const defaultMode = document.querySelector('.mode-switch button.active')?.textContent.trim() || '';
      const preset = [...document.querySelectorAll('.mode-switch button')]
        .find((button) => button.textContent.trim() === '预设');
      preset?.click();
      await sleep(250);
      // 空槽点击不应该产生修改。
      emptySlot?.click();
      await sleep(220);
      const saveBefore = [...document.querySelectorAll('button')]
        .find((button) => button.textContent.trim().startsWith('保存修改'));
      const emptyDirty = !!saveBefore && !saveBefore.disabled;
      // 悬停 filled 槽位应出现 MC 风格 tooltip。
      itemSlot?.dispatchEvent(new MouseEvent('mouseenter', {
        bubbles: false, clientX: 420, clientY: 320,
      }));
      await sleep(150);
      const tooltip = document.querySelector('.mc-item-tooltip');
      const tooltipText = tooltip?.textContent || '';
      const nativeTitles = document.querySelectorAll('.item-icon-view[title]').length;
      itemSlot?.click();
      await sleep(900);
      const editor = !!document.querySelector('.item-editor');
      const editorInputs = document.querySelectorAll('.item-editor input, .item-editor textarea, .item-editor select').length;
      const rawSnbt = document.querySelector('.editor-raw textarea')?.value || '';
      const floating = !!document.querySelector('.workbench-floating-cursor');
      // 预设模式下用点击拿起/放下模拟游戏内搬运：第一次点击已经拿起，这里放到空槽。
      emptySlot?.click();
      await sleep(900);
      const save = [...document.querySelectorAll('button')]
        .find((button) => button.textContent.trim().startsWith('保存修改'));
      const saveEnabled = !!save && !save.disabled;
      save?.click();
      await sleep(500);
      const overlay = document.querySelector('.el-overlay');
      const box = document.querySelector('.el-message-box');
      const modalFixed = overlay ? getComputedStyle(overlay).position === 'fixed' : false;
      const modalVisible = box ? getComputedStyle(box).display !== 'none'
        && box.getBoundingClientRect().width > 100 : false;
      const confirm = [...document.querySelectorAll('.el-message-box__btns button')]
        .find((button) => button.textContent.trim() === '确认写入');
      confirm?.click();
      await sleep(4200);
      const messages = [...document.querySelectorAll('.container-message')]
        .map((node) => ({ cls: node.className, text: node.textContent }));
      return {
        rows: rows.length,
        slots,
        hasItem: !!itemSlot,
        hasGlint: !!document.querySelector('.slot .item-icon-view.has-glint'),
        map: !!document.querySelector('.map-picker'),
        mapMarkers: document.querySelectorAll('.map-marker').length,
        mapTiles: [...document.querySelectorAll('.map-picker .map-tile')]
          .filter((img) => img.complete && img.naturalWidth > 0).length,
        mapBrokenTiles: [...document.querySelectorAll('.map-picker .map-tile')]
          .filter((img) => img.complete && img.naturalWidth === 0).length,
        mapHeight: document.querySelector('.map-picker')?.clientHeight || 0,
        mapFoot: document.querySelector('.map-picker .map-foot')?.textContent || '',
        defaultMode,
        emptyDirty,
        tooltip: Boolean(tooltip),
        tooltipText,
        nativeTitles,
        rawSnbt,
        floating,
        editor,
        editorInputs,
        saveEnabled,
        modalFixed,
        modalVisible,
        message: document.querySelector('.container-message.ok')?.textContent || '',
        messages,
        modalStillOpen: !!document.querySelector('.el-message-box'),
        modalOpened: !!confirm,
        page: !!document.querySelector('.container-page'),
        errors: window.__containerErrors.slice(),
      };
    })()`);
    check('容器页路由可打开并列出容器', containerUi.page === true && containerUi.rows >= 1,
        JSON.stringify(containerUi));
    check('容器页显示 27 格槽位与物品且无附魔物品不发光', containerUi.slots === 27
        && containerUi.hasItem === true && containerUi.hasGlint === false,
        JSON.stringify(containerUi));
    check('容器页地图选择箱子并显示标记', containerUi.map === true
        && containerUi.mapMarkers >= 1 && containerUi.mapTiles >= 1,
        JSON.stringify({ map: containerUi.map, markers: containerUi.mapMarkers,
          tiles: containerUi.mapTiles }));
    check('容器页默认使用模拟模式', containerUi.defaultMode === '模拟',
        JSON.stringify({ mode: containerUi.defaultMode }));
    check('空槽点击不产生假修改', containerUi.emptyDirty === false,
        JSON.stringify({ emptyDirty: containerUi.emptyDirty }));
    check('槽位悬停显示 MC 风格 tooltip', containerUi.tooltip === true
        && containerUi.tooltipText.includes('minecraft:'),
        JSON.stringify({ tooltip: containerUi.tooltip, text: containerUi.tooltipText.slice(0, 120) }));
    check('物品 tooltip 中文且不再被原生 NBT 文本覆盖', containerUi.nativeTitles === 0
        && /[\u4e00-\u9fff]/.test(containerUi.tooltipText),
        JSON.stringify({ nativeTitles: containerUi.nativeTitles,
          text: containerUi.tooltipText.slice(0, 160) }));
    check('原始 SNBT 编辑框预填物品原本组件', containerUi.rawSnbt.includes('id')
        && containerUi.rawSnbt.length > 12,
        JSON.stringify({ rawSnbt: containerUi.rawSnbt.slice(0, 160) }));
    check('容器地图与世界地图同缩放口径、更高且无裂图瓦片',
        containerUi.mapFoot.includes('格/像素') && containerUi.mapHeight >= 440
        && containerUi.mapBrokenTiles === 0,
        JSON.stringify({ foot: containerUi.mapFoot, height: containerUi.mapHeight,
          broken: containerUi.mapBrokenTiles }));
    check('拿起物品后光标物品跟随鼠标', containerUi.floating === true,
        JSON.stringify({ floating: containerUi.floating }));
    check('确认弹窗为固定遮罩而不是页面底部元素', containerUi.modalFixed === true
        && containerUi.modalVisible === true,
        JSON.stringify({ fixed: containerUi.modalFixed, visible: containerUi.modalVisible }));
    check('容器为主格显示结构化组件编辑器', containerUi.editor === true
        && containerUi.editorInputs > 0 && containerUi.saveEnabled === true,
        JSON.stringify(containerUi));
    check('容器页预设模式点击搬运并保存成功', containerUi.modalOpened === true
        && containerUi.modalStillOpen === false && containerUi.message.includes('已写入'),
        JSON.stringify(containerUi));
    check('容器页没有未捕获脚本错误', (containerUi.errors || []).length === 0,
        (containerUi.errors || []).slice(0, 3).join(' | '));
    await fetch(base + '/api/server/console', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ command: 'setblock 8 100 8 minecraft:air' }),
    });

    // ---- 简单页逐路由验证（Vue page/view 直接打开） ----
    const simpleRoutes = [
      ['/config-editor', 'panel-config-editor', '/config-editor'],
      ['/announcements', 'panel-announcements', '/announcements'],
      ['/output', 'panel-output', '/output'],
      ['/permissions', 'panel-permissions', '/permissions'],
      ['/identity', 'panel-identity', '/identity'],
      ['/world-stack', 'panel-world-stack', '/world-stack'],
      ['/lottery', 'panel-lottery', '/lottery'],
      ['/server/config', 'server-config-panel', '/vanilla/config'],
      ['/server/backup', 'server-backup-panel', '/vanilla/backup'],
      ['/server/console', 'server-console-panel', '/vanilla/console'],
      ['/server/audit', 'server-audit-panel', '/samsara/system/audit'],
      ['/vanilla/audit', 'server-audit-panel', '/samsara/system/audit'],
    ];
    for (const [path, panelId, expectedPath] of simpleRoutes) {
      await navigate(base + path);
      await until("window.SamsaraUI && (() => { const p = document.getElementById('"
          + panelId + "'); return p && !p.hidden && getComputedStyle(p).display !== 'none'; })()",
          20000, '路由 ' + path);
      const routeState = await evaluate(`(() => {
        const panel = document.getElementById(${JSON.stringify(panelId)});
        return {
          path: location.pathname,
          panelVisible: panel ? !panel.hidden
            && getComputedStyle(panel).display !== 'none' : false,
          header: !!document.querySelector('.app-header'),
          tokenDialog: document.getElementById('token-dialog')?.open === true,
        };
      })()`);
      check('简单页路由 ' + path + ' 可打开', routeState.path === expectedPath
          && routeState.panelVisible && routeState.header && !routeState.tokenDialog,
          JSON.stringify(routeState));
    }

    // ---- 复杂页逐路由验证：配置、地图、圈地范围 ----
    const complexRoutes = [
      ['/config', 'panel-config', `(() => ({
        files: document.querySelectorAll('#files details.file-block').length,
        chips: document.querySelectorAll('#status-chips .status-chip').length,
      }))()`],
      ['/map', 'panel-map', `(() => ({
        canvas: !!document.getElementById('map-canvas'),
        stats: (document.getElementById('map-stats')?.textContent || '').trim().length,
      }))()`],
      ['/claim-range', 'panel-claim-range', `(() => {
        const canvas = document.getElementById('claim-sim-canvas');
        return { canvas: !!canvas, width: canvas ? canvas.getBoundingClientRect().width : 0 };
      })()`],
    ];
    for (const [path, panelId, probe] of complexRoutes) {
      await navigate(base + path);
      await until("window.SamsaraUI && (() => { const p = document.getElementById('"
          + panelId + "'); return p && !p.hidden && getComputedStyle(p).display !== 'none'; })()",
          20000, '复杂页 ' + path);
      if (path === '/map') {
        await until("document.getElementById('map-stats') && "
            + "document.getElementById('map-stats').textContent.trim().length > 0",
            20000, '地图状态');
      }
      if (path === '/claim-range') {
        await until("(() => { const c = document.getElementById('claim-sim-canvas'); "
            + "return c && c.getBoundingClientRect().width > 0; })()",
            20000, '圈地画布');
      }
      if (path === '/config') {
        await until("document.querySelectorAll('#status-chips .status-chip').length > 0",
            20000, '配置状态');
      }
      const detail = await evaluate(probe);
      if (path === '/config') {
        check('复杂页 /config 渲染配置与状态', detail.files >= 1 && detail.chips >= 1,
            JSON.stringify(detail));
      } else if (path === '/map') {
        check('复杂页 /map 渲染地图', detail.canvas === true && detail.stats > 0,
            JSON.stringify(detail));
      } else {
        check('复杂页 /claim-range 渲染画布', detail.canvas === true && detail.width > 0,
            JSON.stringify(detail));
      }
    }

    // ---- 新信息架构：左侧顶级工作区 + 二级 Tab ----
    await navigate(base + '/vanilla/config');
    await until("window.SamsaraUI && document.querySelector('.workspace-rail')",
        20000, '新信息架构导航');
    const iaVanilla = await evaluate(`(() => ({
      workspaces: [...document.querySelectorAll('.workspace-rail .rail-item[data-workspace]')]
        .map((node) => node.dataset.workspace),
      sections: [...document.querySelectorAll('.section-tab')]
        .map((node) => node.textContent.replace(/\\s+/g, ' ').trim()),
      path: location.pathname,
      panelVisible: (() => {
        const panel = document.getElementById('server-config-panel');
        return !!(panel && !panel.hidden && getComputedStyle(panel).display !== 'none');
      })(),
      crumb: document.querySelector('.ia-crumb-title')?.textContent.trim() || '',
    }))()`);
    check('顶级工作区包含本体与 samsara', iaVanilla.workspaces.includes('vanilla')
        && iaVanilla.workspaces.includes('samsara'), JSON.stringify(iaVanilla.workspaces));
    check('本体二级 Tab 为服务器配置/存档备份/RCON',
        iaVanilla.sections.join('|') === '服务器配置|存档备份|RCON',
        JSON.stringify(iaVanilla.sections));
    check('本体工作区直接路由可用', iaVanilla.path === '/vanilla/config'
        && iaVanilla.panelVisible && iaVanilla.crumb === '服务器配置',
        JSON.stringify(iaVanilla));
    const navFit = await evaluate(`(() => {
      const side = document.querySelector('.ia-side');
      const nav = document.querySelector('#global-nav');
      if (!side || !nav) return { sideW: 0, navW: 0, navH: 0, navScrollH: 0 };
      return {
        sideW: Math.round(side.getBoundingClientRect().width),
        navW: Math.round(nav.getBoundingClientRect().width),
        navH: Math.round(nav.clientHeight),
        navScrollH: nav.scrollHeight,
      };
    })()`);
    check('本体页面导航宽度固定且背景覆盖内容', navFit.navW >= navFit.sideW - 12
        && navFit.navH >= navFit.navScrollH - 2, JSON.stringify(navFit));

    await navigate(base + '/samsara/economy');
    await until("document.querySelector('.section-tab[data-section=\"sky\"]')",
        20000, 'samsara 二级 Tab');
    const iaSamsara = await evaluate(`(() => ({
      sections: [...document.querySelectorAll('.section-tab')]
        .map((node) => node.textContent.replace(/\\s+/g, ' ').trim()),
      path: location.pathname,
      crumb: document.querySelector('.ia-crumb-title')?.textContent.trim() || '',
    }))()`);
    check('samsara 二级 Tab 为经济业务/天网/配置/世界/社区/系统',
        iaSamsara.sections.join('|') === '经济业务|天网|配置|世界|社区|系统',
        JSON.stringify(iaSamsara.sections));
    check('samsara 工作区直接路由可用', iaSamsara.path === '/samsara/economy'
        && iaSamsara.crumb === '概览', JSON.stringify(iaSamsara));

    await until("document.getElementById('panel-lottery')"
        + " && !document.getElementById('panel-lottery').hidden", 20000, '经济业务面板');
    const economyTabs = await evaluate(`(() => ({
      items: [...document.querySelectorAll('.section-subnav .subnav-item')]
        .map((node) => node.textContent.trim()),
      active: document.querySelector('.section-subnav .subnav-item.active')
        ?.textContent.trim() || '',
    }))()`);
    check('经济业务二级 Tab 为概览/服务器基金/死人乐透/保险',
        economyTabs.items.join('|') === '概览|服务器基金|死人乐透|保险'
        && economyTabs.active === '概览', JSON.stringify(economyTabs));
    const economyLayout = await evaluate(`(() => {
      const panel = document.getElementById('panel-lottery');
      const head = panel?.querySelector('#lottery-dashboard .panel-head');
      const title = head?.querySelector(':scope > div:first-child');
      const tools = head?.querySelector('.panel-tools');
      const t = title?.getBoundingClientRect();
      const p = tools?.getBoundingClientRect();
      const grid = panel ? getComputedStyle(panel).display : '';
      return {
        display: grid,
        columns: panel ? getComputedStyle(panel).gridTemplateColumns : '',
        toolbarBelow: !!t && !!p && p.top >= t.bottom - 1,
      };
    })()`);
    check('经济业务为多板块网格且操作栏在横幅下方', economyLayout.display === 'grid'
        && economyLayout.toolbarBelow === true, JSON.stringify(economyLayout));

    // 三级子页导航：天网 / 世界 / 配置 / 社区
    await navigate(base + '/samsara/sky/track');
    await until("document.querySelector('.section-subnav [data-subnav=\"inventory\"]')",
        20000, '天网三级导航');
    const subnavSky = await evaluate(`(() => ({
      items: [...document.querySelectorAll('.section-subnav .subnav-item')]
        .map((node) => node.textContent.trim()),
      section: document.querySelector('.section-tab.active')?.textContent.trim() || '',
      crumb: [...document.querySelectorAll('.ia-crumbs > span')]
        .filter((node) => !node.classList.contains('ia-crumb-sep'))
        .map((node) => node.textContent.trim()),
    }))()`);
    check('天网三级导航为轨迹/Diff/容器/玩家背包（Web 背包已并入全局浮窗）',
        subnavSky.items.join('|') === '轨迹回看|Diff 回看|容器|玩家背包'
        && subnavSky.section === '天网', JSON.stringify(subnavSky));
    check('面包屑为 samsara / 天网 / 轨迹回看',
        subnavSky.crumb.join('|') === 'samsara|天网|轨迹回看', JSON.stringify(subnavSky));

    await navigate(base + '/samsara/world/map');
    await until("document.querySelector('.section-subnav [data-subnav=\"world-stack\"]')",
        20000, '世界三级导航');
    const worldNav = await evaluate(`(() => ({
      items: [...document.querySelectorAll('.section-subnav .subnav-item')]
        .map((node) => node.textContent.trim()),
      stackVisible: (() => {
        const node = document.querySelector('.section-subnav [data-subnav="world-stack"]');
        const rect = node?.getBoundingClientRect();
        return !!rect && rect.width > 0 && rect.height > 0;
      })(),
    }))()`);
    check('世界三级导航可一键进入世界栈', worldNav.items.join('|') === '地图|世界栈'
        && worldNav.stackVisible, JSON.stringify(worldNav));
    await evaluate("document.querySelector('.section-subnav [data-subnav=\"world-stack\"]').click(); true;");
    await until("location.pathname === '/samsara/world/stack'", 15000, '从地图进入世界栈');
    const worldStackPath = await evaluate(`({
      path: location.pathname,
      crumb: [...document.querySelectorAll('.ia-crumbs > span')]
        .filter((node) => !node.classList.contains('ia-crumb-sep'))
        .map((node) => node.textContent.trim()),
    })`);
    check('地图页可一键进入世界栈且面包屑对齐', worldStackPath.path === '/samsara/world/stack'
        && worldStackPath.crumb.join('|') === 'samsara|世界|世界栈',
        JSON.stringify(worldStackPath));

    // 配置编辑器只显示 samsara 自有配置
    await navigate(base + '/samsara/config/editor');
    await until("document.querySelector('#config-editor-body .config-editor-page-head h3')",
        25000, '配置编辑器页面');
    const configFilter = await evaluate(`(() => {
      const navText = document.querySelector('#global-nav')?.textContent || '';
      const body = document.querySelector('#config-editor-body');
      const meta = body?.querySelector('p.muted.small')?.textContent || '';
      return {
        navText,
        meta,
        serverPropsVisible: /server\\.properties|whitelist\\.json|ops\\.json/.test(meta),
        serverGroupVisible: navText.includes('服务器本体'),
        thirdGroupVisible: navText.includes('第三方模组'),
      };
    })()`);
    check('samsara 配置编辑器不显示服务器本体/第三方配置',
        !configFilter.serverPropsVisible && !configFilter.serverGroupVisible
        && !configFilter.thirdGroupVisible, JSON.stringify(configFilter));

    // 工作区导航默认收起并记住手动固定
    const railState = await evaluate(`(() => {
      const rail = document.querySelector('.workspace-rail');
      return { collapsed: rail?.classList.contains('collapsed') === true,
        hasToggle: Boolean(rail?.querySelector('.rail-toggle')),
        stored: localStorage.getItem('samsara.ia.rail') };
    })()`);
    check('工作区导航默认收起且可手动固定', railState.collapsed === true
        && railState.hasToggle === true, JSON.stringify(railState));

    await navigate(base + '/samsara/economy/lottery');
    await until("document.querySelector('.section-tab[data-section=\"sky\"]')", 20000,
        '回到经济业务以测量吸附');

    await cdp.send('Input.dispatchMouseEvent', {
      type: 'mouseWheel', x: 720, y: 520, deltaX: 0, deltaY: 520,
    });
    await sleep(700);
    const stickyLayout = await evaluate(`(() => {
      const box = (selector) => {
        const node = document.querySelector(selector);
        if (!node) return null;
        const rect = node.getBoundingClientRect();
        return { top: Math.round(rect.top), height: Math.round(rect.height) };
      };
      const section = box('.section-tabs');
      const minTop = (section ? section.top + section.height : 0) + 1;
      const candidates = [...document.querySelectorAll(
        '.content .page-banner-title, .content .panel > .panel-head > div:first-child > h2')];
      const card = candidates.find((node) => {
        const rect = node.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0
          && getComputedStyle(node).display !== 'none' && rect.top >= minTop;
      });
      const cardRect = card ? card.getBoundingClientRect() : null;
      return {
        y: Math.round(window.scrollY),
        scrollHeight: document.documentElement.scrollHeight,
        viewport: document.documentElement.clientHeight,
        header: box('.ia-header'),
        section,
        rail: box('.workspace-rail'),
        nav: box('#global-nav'),
        navTitle: box('.ia-side .section-subnav .subnav-title')
          || box('#global-nav .nav-title'),
        cardTitle: cardRect ? { top: Math.round(cardRect.top),
          text: card.textContent.trim().slice(0, 24) } : null,
        overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth + 2,
      };
    })()`);
    if (stickyLayout.y < 200) {
      await evaluate("document.documentElement.scrollTop = 520; document.body.scrollTop = 520; true;");
      await sleep(500);
      Object.assign(stickyLayout, await evaluate(`(() => {
        const box = (selector) => {
          const node = document.querySelector(selector);
          if (!node) return null;
          const rect = node.getBoundingClientRect();
          return { top: Math.round(rect.top), height: Math.round(rect.height) };
        };
        return { y: Math.round(window.scrollY), header: box('.ia-header'),
          section: box('.section-tabs'), rail: box('.workspace-rail'), nav: box('#global-nav') };
      })()`));
    }
    check('管理端滚动时 header / 二级栏 / 页内导航分层吸附',
        stickyLayout.y > 0 && stickyLayout.header?.top === 0
        && Math.abs(stickyLayout.section.top - stickyLayout.header.height) <= 2
        && stickyLayout.nav.top >= stickyLayout.section.top + stickyLayout.section.height
        && stickyLayout.rail.top >= stickyLayout.header.height - 2
        && !stickyLayout.overflow, JSON.stringify(stickyLayout));
    const sectionBottom = stickyLayout.section
      ? stickyLayout.section.top + stickyLayout.section.height : 0;
    check('页内导航固定在二级栏下方，不跟随卡片标题跳动', Boolean(stickyLayout.navTitle)
        && stickyLayout.navTitle.top >= sectionBottom
        && stickyLayout.navTitle.top <= sectionBottom + 160,
        JSON.stringify({ navTitle: stickyLayout.navTitle, section: stickyLayout.section,
          cardTitle: stickyLayout.cardTitle }));
    await evaluate("window.scrollBy(0, 2); true;");
    await sleep(120);
    const pageScrollbar = await evaluate(`(() => {
      const bar = document.querySelector('.page-scrollbar');
      const thumb = document.querySelector('.page-scrollbar-thumb');
      return {
        exists: !!bar,
        visible: bar?.classList.contains('visible') === true,
        thumbHeight: Math.round(thumb?.getBoundingClientRect().height || 0),
        nativeWidth: window.innerWidth - document.documentElement.clientWidth,
      };
    })()`);
    check('页面滚动条为浮层且不占布局宽度', pageScrollbar.exists
        && pageScrollbar.visible && pageScrollbar.thumbHeight > 0
        && pageScrollbar.nativeWidth === 0, JSON.stringify(pageScrollbar));
    const railHandle = await evaluate(`(() => {
      const node = document.querySelector('.rail-handle');
      const rail = document.querySelector('.workspace-rail');
      return { handle: !!node && getComputedStyle(node).display !== 'none',
        collapsed: rail?.classList.contains('collapsed') === true,
        left: node ? Math.round(node.getBoundingClientRect().left) : -1 };
    })()`);
    check('工作区收起后把手贴最外侧且可展开', railHandle.handle
        && railHandle.collapsed && railHandle.left === 0, JSON.stringify(railHandle));
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: 1400, height: 900, deviceScaleFactor: 1, mobile: false,
    });
    await sleep(500);
    const railPinned = await evaluate(`(async () => {
      document.querySelector('.rail-toggle')?.click();
      await new Promise((resolve) => setTimeout(resolve, 450));
      const rail = document.querySelector('.workspace-rail');
      const left = Math.round(rail.getBoundingClientRect().left);
      const pinned = rail.classList.contains('pinned');
      document.querySelector('.rail-toggle')?.click();
      await new Promise((resolve) => setTimeout(resolve, 350));
      return { left, pinned, restored: rail.classList.contains('collapsed') };
    })()`);
    check('工作区固定展开时贴网页最左侧', railPinned.left === 0
        && railPinned.pinned === true && railPinned.restored === true,
        JSON.stringify(railPinned));

    // 真实鼠标：移入把手不收回、点击工作区不收回、移出后收回并延迟显示把手。
    const handlePos = await evaluate(`(() => {
      const node = document.querySelector('.rail-handle');
      const rect = node.getBoundingClientRect();
      return { x: Math.round(rect.left + Math.min(12, rect.width / 2)),
        y: Math.round(rect.top + rect.height / 2) };
    })()`);
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: handlePos.x, y: handlePos.y });
    await sleep(420);
    const railHoverOpen = await evaluate(`(() => {
      const rail = document.querySelector('.workspace-rail');
      const handle = document.querySelector('.rail-handle');
      return { closed: rail.classList.contains('collapsed'),
        handle: !!(handle.offsetWidth || handle.offsetHeight),
        x: Math.round(rail.getBoundingClientRect().x) };
    })()`);
    check('鼠标移入把手后工作区导航展开且把手隐藏', railHoverOpen.closed === false
        && railHoverOpen.handle === false && railHoverOpen.x === 0,
        JSON.stringify(railHoverOpen));

    const railBox = await evaluate(`(() => {
      const rect = document.querySelector('.workspace-rail').getBoundingClientRect();
      return { x: Math.round(rect.left + 100), y: Math.round(rect.top + 60) };
    })()`);
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: railBox.x, y: railBox.y });
    await sleep(320);
    const railInside = await evaluate(`!document.querySelector('.workspace-rail').classList.contains('collapsed')`);
    check('鼠标在导航栏内部时不会误收回', railInside === true, 'inside=' + railInside);

    const workspacePoint = await evaluate(`(() => {
      const node = document.querySelector('.rail-item[data-workspace="samsara"]');
      const rect = node.getBoundingClientRect();
      return { x: Math.round(rect.left + rect.width / 2),
        y: Math.round(rect.top + rect.height / 2) };
    })()`);
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: workspacePoint.x, y: workspacePoint.y });
    await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: workspacePoint.x, y: workspacePoint.y, button: 'left', clickCount: 1 });
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: workspacePoint.x, y: workspacePoint.y, button: 'left', clickCount: 1 });
    await sleep(800);
    const railAfterClick = await evaluate(`(() => {
      const rail = document.querySelector('.workspace-rail');
      return { closed: rail.classList.contains('collapsed'), path: location.pathname };
    })()`);
    check('点击工作区后导航栏保持展开', railAfterClick.closed === false,
        JSON.stringify(railAfterClick));

    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 1100, y: 700 });
    await sleep(500);
    const railAfterLeave = await evaluate(`(() => {
      const rail = document.querySelector('.workspace-rail');
      const handle = document.querySelector('.rail-handle');
      return { closed: rail.classList.contains('collapsed'),
        handle: !!(handle.offsetWidth || handle.offsetHeight) };
    })()`);
    check('鼠标离开后工作区导航收回并显示把手', railAfterLeave.closed === true
        && railAfterLeave.handle === true, JSON.stringify(railAfterLeave));

    await evaluate("window.scrollTo(0, 0); true;");
    await sleep(200);

    await evaluate("document.querySelector('.section-tab[data-section=\"sky\"]').click(); true;");
    await until("location.pathname === '/samsara/sky/track'", 15000, '天网二级切换');
    const skyRoute = await evaluate(`({
      path: location.pathname,
      active: document.querySelector('.section-tab.active')?.textContent.trim() || '',
      track: !!document.querySelector('[data-page="track"]'),
    })`);
    check('二级 Tab 切换会更新路由与页面', skyRoute.path === '/samsara/sky/track'
        && skyRoute.active === '天网' && skyRoute.track === true, JSON.stringify(skyRoute));
    const skyBanner = await evaluate(`(() => ({
      banner: document.getElementById('action-title')?.textContent.trim() || '',
      bannerTop: Math.round(document.querySelector('.page-hero')
        ?.getBoundingClientRect().top || -1),
      contentBanner: !!document.querySelector('[data-page="track"] .page-banner'),
    }))()`);
    check('天网页面使用统一顶部横幅', skyBanner.banner === '轨迹回看'
        && skyBanner.bannerTop >= 0 && skyBanner.contentBanner === false,
        JSON.stringify(skyBanner));

    await navigate(base + '/samsara/sky/containers');
    await until("document.querySelector('.container-page.embedded')", 25000, '天网容器页');
    await until("document.querySelector('.container-page.embedded .container-row')"
        + " || document.querySelector('.container-page.embedded .container-message.error')",
        30000, '天网容器列表');
    const skyContainer = await evaluate(`(async () => {
      const rows = [...document.querySelectorAll('.container-page.embedded .container-row')];
      const row = rows.find((node) => node.textContent.includes('8, 100, 8')) || rows[0];
      if (row) {
        row.click();
        const deadline = Date.now() + 8000;
        while (!document.querySelector('.container-page.embedded .slot-grid')
            && Date.now() < deadline) {
          await new Promise((resolve) => setTimeout(resolve, 100));
        }
      }
      return {
        embedded: !!document.querySelector('.container-page.embedded'),
        rows: rows.length,
        slots: document.querySelectorAll('.container-page.embedded .slot').length,
        grid: !!document.querySelector('.container-page.embedded .slot-grid'),
        section: document.querySelector('.section-tab.active')?.textContent.trim() || '',
      };
    })()`);
    check('天网容器入口复用容器页', skyContainer.embedded
        && skyContainer.section === '天网', JSON.stringify(skyContainer));
    check('天网容器槽位网格可读取', skyContainer.rows >= 1
        && skyContainer.grid && skyContainer.slots === 27, JSON.stringify(skyContainer));

    await navigate(base + '/config');
    await until("document.getElementById('panel-config') && !document.getElementById('panel-config').hidden",
        20000, '旧 /config 别名');
    const legacyAlias = await evaluate(`({
      path: location.pathname,
      panel: !document.getElementById('panel-config').hidden,
    })`);
    check('旧 /config 别名仍可用', legacyAlias.path === '/config' && legacyAlias.panel,
        JSON.stringify(legacyAlias));

    // ---- 玩家背包：在线读写、revision 冲突、离线安全队列 ----
    const invHeaders = authHeaders;
    const invGet = async (player, scope = 'inventory') => {
      const response = await fetch(base + '/api/inventory?player=' + encodeURIComponent(player)
        + '&scope=' + scope, { headers: invHeaders });
      return { status: response.status, body: await response.json() };
    };
    await fetch(base + '/api/server/console', {
      method: 'POST', headers: invHeaders,
      body: JSON.stringify({ command: 'player E2EInv spawn' }),
    });
    let invOnline = null;
    for (let i = 0; i < 30; i++) {
      await sleep(500);
      const current = await invGet('E2EInv');
      if (current.status === 200 && current.body.online) {
        invOnline = current.body;
        break;
      }
    }
    check('在线玩家背包可读取', Boolean(invOnline) && invOnline.slots.length === 41
        && invOnline.writable === true, JSON.stringify({ online: invOnline?.online,
          slots: invOnline?.slots?.length, writable: invOnline?.writable }));
    if (invOnline) {
      const originalSlots = invOnline.slots.slice();
      const diamond = await (await fetch(base + '/api/inventory/item', {
        method: 'POST', headers: invHeaders,
        body: JSON.stringify({ id: 'minecraft:diamond', count: 5 }),
      })).json();
      const changedSlots = [diamond.encoded, ...originalSlots.slice(1)];
      const write = await fetch(base + '/api/inventory/write', {
        method: 'POST', headers: invHeaders,
        body: JSON.stringify({ player: 'E2EInv', scope: 'inventory',
          revision: invOnline.revision, slots: changedSlots }),
      });
      const writeBody = await write.json();
      check('在线玩家背包可写入并保留物品编码', write.status === 200
          && writeBody.ok === true && writeBody.queued === false, JSON.stringify(writeBody).slice(0, 300));
      const after = await invGet('E2EInv');
      check('在线玩家背包写入后重新读取一致', after.status === 200
          && after.body.slots[0] === diamond.encoded
          && after.body.items.some((item) => item.id === 'minecraft:diamond'
            && item.count === 5), JSON.stringify({ slot0: after.body.slots[0]?.length,
              items: after.body.items?.length }));
      const conflict = await fetch(base + '/api/inventory/write', {
        method: 'POST', headers: invHeaders,
        body: JSON.stringify({ player: 'E2EInv', scope: 'inventory',
          revision: invOnline.revision, slots: originalSlots }),
      });
      check('在线玩家背包 revision 冲突返回 409', conflict.status === 409,
          'status=' + conflict.status);
      const restore = await fetch(base + '/api/inventory/write', {
        method: 'POST', headers: invHeaders,
        body: JSON.stringify({ player: 'E2EInv', scope: 'inventory',
          revision: after.body.revision, slots: originalSlots }),
      });
      check('在线玩家背包测试写入可还原', restore.status === 200, 'status=' + restore.status);
    }

    await fetch(base + '/api/server/console', {
      method: 'POST', headers: invHeaders,
      body: JSON.stringify({ command: 'player E2EInv kill' }),
    });
    let invOffline = null;
    for (let i = 0; i < 20; i++) {
      await sleep(500);
      const current = await invGet('E2EInv');
      if (current.status === 200 && current.body.online === false) {
        invOffline = current.body;
        break;
      }
    }
    check('离线玩家背包只读且可入队', Boolean(invOffline)
        && invOffline.writable === false && invOffline.queueWritable === true,
        JSON.stringify({ online: invOffline?.online, writable: invOffline?.writable }));
    if (invOffline) {
      const originalSlots = invOffline.slots.slice();
      const emerald = await (await fetch(base + '/api/inventory/item', {
        method: 'POST', headers: invHeaders,
        body: JSON.stringify({ id: 'minecraft:emerald', count: 2 }),
      })).json();
      const queued = await fetch(base + '/api/inventory/write', {
        method: 'POST', headers: invHeaders,
        body: JSON.stringify({ player: 'E2EInv', scope: 'inventory',
          revision: invOffline.revision, slots: [emerald.encoded, ...originalSlots.slice(1)] }),
      });
      const queuedBody = await queued.json();
      check('离线玩家写入进入安全队列', queued.status === 200
          && queuedBody.ok === true && queuedBody.queued === true, JSON.stringify(queuedBody).slice(0, 300));
      const pending = await invGet('E2EInv');
      check('离线队列内容可回读并标记 pending', pending.body.pendingWrite === true
          && pending.body.slots[0] === emerald.encoded,
          JSON.stringify({ pending: pending.body.pendingWrite }));

      await fetch(base + '/api/server/console', {
        method: 'POST', headers: invHeaders,
        body: JSON.stringify({ command: 'player E2EInv spawn' }),
      });
      let applied = null;
      for (let i = 0; i < 30; i++) {
        await sleep(500);
        const current = await invGet('E2EInv');
        if (current.status === 200 && current.body.online
            && current.body.slots[0] === emerald.encoded) {
          applied = current.body;
          break;
        }
      }
      check('离线队列在玩家登录后应用', Boolean(applied)
          && applied.pendingWrite === false
          && applied.items.some((item) => item.id === 'minecraft:emerald'), 'applied=' + Boolean(applied));
      if (applied) {
        await fetch(base + '/api/inventory/write', {
          method: 'POST', headers: invHeaders,
          body: JSON.stringify({ player: 'E2EInv', scope: 'inventory',
            revision: applied.revision, slots: originalSlots }),
        });
      }
      await fetch(base + '/api/server/console', {
        method: 'POST', headers: invHeaders,
        body: JSON.stringify({ command: 'player E2EInv kill' }),
      });
    }

    // 浏览器极少数情况下会在重负载后丢掉 sessionStorage；这里显式补一次 token。
    await evaluate(`sessionStorage.setItem('samsara-token', ${JSON.stringify(token)}); true`);
    await navigate(base + '/samsara/sky/inventory');
    try {
      await until("document.querySelector('.inventory-layout')", 40000, '玩家背包页');
    } catch (e) {
      const state = await evaluate(`(() => ({
        path: location.pathname,
        ready: document.readyState,
        token: Boolean(sessionStorage.getItem('samsara-token')),
        layout: Boolean(document.querySelector('.inventory-layout')),
        connect: Boolean(document.querySelector('.inventory-connect')),
        error: document.querySelector('.container-message.error')?.textContent || '',
        text: document.body.innerText.slice(0, 400),
      }))()`);
      throw new Error('玩家背包页: ' + JSON.stringify(state));
    }
    await until("document.querySelectorAll('.inventory-main .slot').length === 41",
        20000, '玩家背包槽位');
    const inventoryUi = await evaluate(`(async () => {
      await new Promise((resolve) => setTimeout(resolve, 900));
      return ({
      page: !!document.querySelector('[data-page="inventory"]'),
      slots: document.querySelectorAll('.inventory-main .slot').length,
      players: document.querySelectorAll('.inventory-player').length,
      scope: !!document.querySelector('.inventory-scope'),
      defaultMode: document.querySelector('.inventory-main .mode-switch button.active')?.textContent.trim() || '',
      map: !!document.querySelector('.inventory-map-block .map-picker'),
      mapMarkers: document.querySelectorAll('.inventory-map-block .map-marker').length,
      mapTiles: [...document.querySelectorAll('.inventory-map-block .map-tile')]
        .filter((img) => img.complete && img.naturalWidth > 0).length,
      rows: [...document.querySelectorAll('.inventory-main .workbench-row')]
        .map((row) => row.className.replace('workbench-row ', '').trim()),
      gearTop: (() => {
        const gear = document.querySelector('.inventory-main .workbench-row-gear');
        const main = document.querySelector('.inventory-main .workbench-row-main');
        return !!gear && !!main && gear.getBoundingClientRect().top < main.getBoundingClientRect().top;
      })(),
      hotbarBelow: (() => {
        const main = document.querySelector('.inventory-main .workbench-row-main');
        const hotbar = document.querySelector('.inventory-main .workbench-row-hotbar');
        return !!main && !!hotbar
          && hotbar.getBoundingClientRect().top > main.getBoundingClientRect().bottom;
      })(),
      section: document.querySelector('.section-tab.active')?.textContent.trim() || '',
      // 所有分区格子必须与主背包等宽：装备/副手曾经被压成 9 列平分。
      gearSlotWidth: document.querySelector('.inventory-main .workbench-row-gear .slot')
        ?.getBoundingClientRect().width || 0,
      mainSlotWidth: document.querySelector('.inventory-main .workbench-row-main .slot')
        ?.getBoundingClientRect().width || 0,
      gearColumns: (() => {
        const grid = document.querySelector('.inventory-main .workbench-row-gear .slot-grid');
        return grid ? getComputedStyle(grid).gridTemplateColumns.split(' ').length : 0;
      })(),
      errors: window.__e2eErrors?.length || 0,
      });
    })()`);
    check('天网玩家背包页可打开', inventoryUi.page && inventoryUi.slots === 41
        && inventoryUi.players >= 1 && inventoryUi.scope === true
        && inventoryUi.defaultMode === '模拟'
        && inventoryUi.section === '天网', JSON.stringify(inventoryUi));
    check('玩家背包地图显示在线/离线玩家标记', inventoryUi.map === true
        && inventoryUi.mapMarkers >= 1 && inventoryUi.mapTiles >= 1,
        JSON.stringify({ map: inventoryUi.map, markers: inventoryUi.mapMarkers,
          tiles: inventoryUi.mapTiles }));
    check('玩家背包按游戏 GUI 排布：装备在上、快捷栏在下且留间隔',
        inventoryUi.gearTop === true && inventoryUi.hotbarBelow === true
        && inventoryUi.rows.some((row) => row.includes('workbench-row-gap')),
        JSON.stringify({ rows: inventoryUi.rows, gearTop: inventoryUi.gearTop,
          hotbarBelow: inventoryUi.hotbarBelow }));
    check('玩家背包装备/副手格子与主背包等宽且不再平分成 9 列',
        inventoryUi.gearColumns === 1 && inventoryUi.gearSlotWidth >= 40
        && Math.abs(inventoryUi.gearSlotWidth - inventoryUi.mainSlotWidth) <= 1,
        JSON.stringify({ gearColumns: inventoryUi.gearColumns,
          gear: inventoryUi.gearSlotWidth, main: inventoryUi.mainSlotWidth }));

    // Web 背包已改为 samsara 全局浮窗（右侧「背包」标签 / E 键），旧路由重定向回玩家背包。
    await navigate(base + '/samsara/sky/web-inventory');
    await sleep(800);
    const legacyPath = await evaluate('location.pathname');
    check('旧 Web 背包链接重定向到玩家背包', legacyPath === '/samsara/sky/inventory', legacyPath);

    await until("document.querySelector('.inventory-layout')", 30000, '玩家背包页');
    // 预置 1 件物品，用于验证浮窗内搬运会实时写入（无需保存/确认）。
    const drawerSeed = await (await fetch(base + '/api/inventory/item', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ id: 'minecraft:stone', count: 1 }),
    })).json();
    const drawerSeedSlots = Array(54).fill('');
    drawerSeedSlots[0] = drawerSeed.encoded;
    await fetch(base + '/api/inventory/web/write', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ slots: drawerSeedSlots }),
    });
    await evaluate(`(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'e', bubbles: true }));
      return true;
    })()`);
    try {
      await until("document.querySelector('.global-inventory-panel')"
          + " && document.querySelectorAll('.global-inventory-panel .slot').length === 54",
          30000, '全局背包浮窗');
    } catch (e) {
      const state = await evaluate(`(() => ({
        tab: !!document.querySelector('.global-inventory-tab'),
        panel: !!document.querySelector('.global-inventory-panel'),
        slots: document.querySelectorAll('.global-inventory-panel .slot').length,
        overlay: !!document.querySelector('.el-overlay'),
        dialog: !!document.querySelector('dialog[open]'),
        token: Boolean(sessionStorage.getItem('samsara-token')),
        message: document.querySelector('.global-inventory-panel .container-message')?.textContent || '',
        connect: !!document.querySelector('.global-inventory-connect'),
        text: document.body.innerText.slice(0, 160),
      }))()`);
      throw new Error('全局背包浮窗: ' + JSON.stringify(state));
    }
    await until("document.querySelector('.global-inventory-panel .creative-library .creative-slot')",
        20000, '浮窗创造物品库');
    const webInventoryUi = await evaluate(`(async () => {
      await new Promise((resolve) => setTimeout(resolve, 300));
      return ({
        panel: !!document.querySelector('.global-inventory-panel'),
        slots: document.querySelectorAll('.global-inventory-panel .slot').length,
        modeSwitch: !!document.querySelector('.global-inventory-panel .mode-switch'),
        saveButton: [...document.querySelectorAll('.global-inventory-panel button')]
          .some((button) => button.textContent.trim().startsWith('保存')),
        library: !!document.querySelector('.global-inventory-panel .creative-library .creative-slot'),
        debugTab: [...document.querySelectorAll('.global-inventory-panel .creative-tabs button')]
          .some((button) => button.textContent.trim() === '调试方块'),
        workbenchLibrary: !!document.querySelector('.global-inventory-panel .workbench-side h4'),
        noLibraryElsewhere: !document.querySelector('.workbench-library-tabs'),
        tabInDom: !!document.querySelector('.global-inventory-tab'),
        tabVisible: (() => {
          const tab = document.querySelector('.global-inventory-tab');
          return !!tab && tab.offsetParent !== null;
        })(),
        nav: [...document.querySelectorAll('.subnav-item')].map((n) => n.textContent.trim()),
      });
    })()`);
    check('Web 幽灵背包为全局浮窗：E 键唤起、54 格 + 创造物品库，无预设/模拟，浮窗打开时标签隐藏',
        webInventoryUi.panel === true && webInventoryUi.slots === 54
        && webInventoryUi.modeSwitch === false && webInventoryUi.saveButton === false
        && webInventoryUi.library === true && webInventoryUi.tabVisible === false
        && webInventoryUi.debugTab === true && webInventoryUi.noLibraryElsewhere === true
        && !webInventoryUi.nav.includes('Web 背包'),
        JSON.stringify(webInventoryUi));

    // 浮窗内搬运必须实时落库：点起 → 放下 → 服务端立刻可见，不需要保存按钮或确认弹窗。
    await evaluate(`(() => {
      document.querySelectorAll('.global-inventory-panel .slot')[0].click();
      return true;
    })()`);
    await sleep(400);
    await evaluate(`(() => {
      document.querySelectorAll('.global-inventory-panel .slot')[1].click();
      return true;
    })()`);
    await sleep(1500);
    const drawerLive = await fetch(base + '/api/inventory/web', { headers: authHeaders })
      .then((response) => response.json());
    check('Web 背包槽位搬运实时写入且无确认弹窗',
        (drawerLive.slots?.[0] || '') === '' && (drawerLive.slots?.[1] || '') !== '',
        JSON.stringify({ first: drawerLive.slots?.[0], second: drawerLive.slots?.[1] }));
    await fetch(base + '/api/inventory/web/write', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ slots: Array(54).fill('') }),
    });

    // 收起浮窗后标签必须重新出现（标签与页面互斥）。
    await evaluate(`(() => {
      [...document.querySelectorAll('.global-inventory-panel button')]
        .find((button) => button.textContent.trim() === '×')?.click();
      return true;
    })()`);
    await sleep(500);
    const drawerTabBack = await evaluate(`(() => {
      const tab = document.querySelector('.global-inventory-tab');
      return !!tab && tab.offsetParent !== null;
    })()`);
    check('收起 Web 背包浮窗后右侧背包标签重新出现', drawerTabBack === true, String(drawerTabBack));

    await navigate(base + '/samsara/economy/fund');
    await until("document.querySelector('.fund-inventory-embed .slot')", 30000, '服务器基金页内嵌基金仓库');
    const fundInventoryUi = await evaluate(`(() => ({
      embedded: !!document.querySelector('.fund-inventory-embed'),
      legacyValues: !!document.getElementById('lottery-values-panel'),
      legacyReadonlyFund: !!document.getElementById('lottery-fund-panel'),
      slots: document.querySelectorAll('.fund-inventory-embed .slot').length,
      modeSwitch: !!document.querySelector('.fund-inventory-embed .mode-switch'),
      expand: !!document.querySelector('.fund-inventory-embed .pool-head button'),
      search: !!document.querySelector('.fund-inventory-embed .fund-search input'),
      sort: !!document.querySelector('.fund-inventory-embed .fund-search select'),
      nav: [...document.querySelectorAll('.subnav-item')].map((n) => n.textContent.trim()),
    }))()`);
    check('基金仓库内嵌服务器基金页且可搜索排序扩容、无预设/模拟分段', fundInventoryUi.embedded === true
        && fundInventoryUi.legacyValues === true && fundInventoryUi.legacyReadonlyFund === false
        && fundInventoryUi.slots >= 54
        && fundInventoryUi.modeSwitch === false && fundInventoryUi.expand === true
        && fundInventoryUi.search === true && fundInventoryUi.sort === true
        && !fundInventoryUi.nav.includes('基金仓库'), JSON.stringify(fundInventoryUi));

    // 实时模式：外部写入后 Web 应自动同步；模拟/预设不覆盖本地未提交修改。
    const beforeLive = await fetch(base + '/api/lottery/fund', { headers: authHeaders })
        .then((response) => response.json());
    const externalSlots = (beforeLive.slots || []).slice();
    externalSlots[0] = 'CgMABWNvdW50AAAABQgAAmlkABFtaW5lY3JhZnQ6ZGlhbW9uZAA=';
    await fetch(base + '/api/lottery/fund/write', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ slots: externalSlots }),
    });
    await sleep(2800);
    const liveSync = await evaluate(`(() => ({
      message: document.querySelector('.fund-inventory-embed .container-message.ok')?.textContent || '',
      filled: document.querySelectorAll('.fund-inventory-embed .slot.filled').length,
    }))()`);
    check('基金仓库实时同步外部写入', liveSync.filled >= 1
        || liveSync.message.includes('同步'), JSON.stringify(liveSync));

    // Web 内搬运同样实时落库：不需要保存按钮，也不需要二次确认。
    const fundMove = await evaluate(`(() => {
      const slots = [...document.querySelectorAll('.fund-inventory-embed .slot')];
      const from = slots.findIndex((slot) => slot.classList.contains('filled'));
      const to = slots.findIndex((slot, index) => index !== from && !slot.classList.contains('filled'));
      if (from < 0 || to < 0) return null;
      slots[from].click();
      return { from, to };
    })()`);
    if (fundMove) {
      await sleep(400);
      await evaluate('document.querySelectorAll(\'.fund-inventory-embed .slot\')['
        + fundMove.to + '].click(); true;');
      await sleep(1500);
      const afterMove = await fetch(base + '/api/lottery/fund', { headers: authHeaders })
        .then((response) => response.json());
      check('基金仓库槽位搬运实时写入且无确认弹窗',
          (afterMove.slots?.[fundMove.from] || '') === ''
          && (afterMove.slots?.[fundMove.to] || '') !== '',
          JSON.stringify({ move: fundMove, from: afterMove.slots?.[fundMove.from],
            to: afterMove.slots?.[fundMove.to] }));
    }
    await fetch(base + '/api/lottery/fund/write', {
      method: 'POST', headers: authHeaders,
      body: JSON.stringify({ slots: beforeLive.slots || [] }),
    });

    // ---- 天网：轨迹回看与 Diff 回看入口 ----
    const trackApi = await fetch(base + '/api/track?days=7&limit=5', { headers: authHeaders });
    const trackBody = await trackApi.json();
    check('轨迹回看只读 API 可列出最近轨迹', trackApi.status === 200
        && trackBody.ok === true && Array.isArray(trackBody.tracks)
        && trackBody.tracks.every((track) => String(track.command || '').startsWith('/samsara track view')),
        JSON.stringify({ status: trackApi.status, count: trackBody.count,
          first: trackBody.tracks?.[0]?.command }));
    const skyTrailApi = await fetch(base + '/api/map/player-trails?limit=120', { headers: authHeaders });
    const skyTrailBody = await skyTrailApi.json();
    check('天网玩家长轨迹 API 只返回玩家且带长窗口', skyTrailApi.status === 200
        && skyTrailBody.ok === true && Array.isArray(skyTrailBody.players)
        && skyTrailBody.windowMs >= 3600000
        && skyTrailBody.players.every((player) => Array.isArray(player.trail)
          && player.trail.length <= 120),
        JSON.stringify({ status: skyTrailApi.status, windowMs: skyTrailBody.windowMs,
          players: skyTrailBody.players?.length }));
    const diffStatusApi = await fetch(base + '/api/diff/view/status', { headers: authHeaders });
    const diffStatusBody = await diffStatusApi.json();
    check('Diff 回看状态 API 可用', diffStatusApi.status === 200 && diffStatusBody.ok === true,
        JSON.stringify(diffStatusBody).slice(0, 200));

    await navigate(base + '/samsara/sky/track');
    await until("document.querySelector('[data-page=\"track\"] .track-filters')",
        25000, '轨迹回看页');
    await until("document.querySelector('[data-page=\"track\"] .track-row')"
        + " || document.querySelector('[data-page=\"track\"]')?.innerText.includes('没有可回看')",
        20000, '轨迹回看列表');
    const trackUi = await evaluate(`({
      page: !!document.querySelector('[data-page="track"]'),
      rows: document.querySelectorAll('[data-page="track"] .track-row').length,
      links: document.querySelectorAll('[data-page="track"] a[href="/samsara/sky/diff"]').length
        + document.querySelectorAll('[data-page="track"] a[href="/samsara/world/map"]').length,
      section: document.querySelector('.section-tab.active')?.textContent.trim() || '',
    })`);
    check('轨迹回看页可打开并复用只读列表', trackUi.page && trackUi.rows >= 1
        && trackUi.section === '天网', JSON.stringify(trackUi));

    await navigate(base + '/samsara/sky/diff');
    await until("document.querySelector('[data-page=\"diff\"] .diff-grid')",
        25000, 'Diff 回看页');
    const diffUi = await evaluate(`({
      page: !!document.querySelector('[data-page="diff"]'),
      stack: document.querySelectorAll('[data-page="diff"] .diff-grid label').length,
      status: Boolean(document.querySelector('[data-page="diff"] .diff-status')),
      section: document.querySelector('.section-tab.active')?.textContent.trim() || '',
    })`);
    check('Diff 回看页可打开并显示状态', diffUi.page && diffUi.stack >= 4
        && diffUi.status && diffUi.section === '天网', JSON.stringify(diffUi));

    // ---- 其他模组：工作区入口 + 文件夹树 + 文件 Tab ----
    const pagesResp = await fetch(base + '/api/config/pages', { headers: authHeaders });
    const pagesBody = await pagesResp.json();
    const thirdPages = (pagesBody.pages || []).filter((page) => page.source === 'third-party');
    if (thirdPages.length) {
      const first = thirdPages[0];
      const relative = String(first.id || '').startsWith('third-party:')
        ? String(first.id).substring('third-party:'.length).replace(/\\/g, '/')
        : String(first.fileName || '').replace(/\\/g, '/');
      const modId = relative.includes('/') ? relative.split('/')[0] : relative;
      await navigate(base + '/mods/' + encodeURIComponent(modId));
      await until("document.querySelector('.mod-config-layout .mod-file-tab')"
          + " && document.querySelectorAll('.file-node').length >= 1",
          25000, '其他模组配置树');
      await until("document.querySelectorAll('.mod-editor-host #config-editor-body .entry').length > 0",
          25000, '其他模组配置字段');
      await until("document.querySelector('.rail-item[data-mod-id=" + JSON.stringify(modId) + "]')",
          20000, '其他模组工作区入口');
      const modUi = await evaluate(`({
        path: location.pathname,
        rail: [...document.querySelectorAll('.rail-item[data-mod-id]')]
          .map((node) => node.dataset.modId),
        tree: document.querySelectorAll('.file-node').length,
        tabs: [...document.querySelectorAll('.mod-file-tab')]
          .map((node) => node.textContent.trim()),
        fields: document.querySelectorAll('.mod-editor-host #config-editor-body .entry').length,
        panelHead: (() => {
          const head = document.querySelector('.mod-editor-host #panel-config-editor .panel-head');
          return head ? getComputedStyle(head).display : 'missing';
        })(),
        section: document.querySelector('.section-tab.active')?.textContent.trim() || '',
      })`);
      check('其他模组工作区入口可见', modUi.rail.includes(modId),
          JSON.stringify({ modId, rail: modUi.rail }));
      check('其他模组文件夹树与文件 Tab 渲染', modUi.tree >= 1
          && modUi.tabs.length >= 1 && modUi.fields > 0 && modUi.panelHead === 'none',
          JSON.stringify(modUi));
      check('其他模组文件页复用 PageSpec 编辑器', modUi.fields > 0,
          'fields=' + modUi.fields);
    } else {
      check('其他模组工作区入口可见', true, '当前测试服没有第三方配置');
    }

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
        userViewFlag: window.SamsaraUserView === true,
        tpText: document.body.innerText.includes('TP 命令'),
        tpHtml: document.body.innerHTML.includes('TP 命令'),
        markerTitle: document.querySelector('.map-player-marker')?.title || '',
        userCalls: resources.filter((url) => url.includes('/api/user/')).length,
        adminCalls: resources.filter((url) => url.includes('/api/config')
          || url.includes('/api/server') || url.includes('/api/world-stack')
          || url.includes('/api/permissions')).length,
        errors: window.__e2eErrors.slice(),
      };
    })()`);
    const userShell = await evaluate(`({
      topbar: document.querySelectorAll('.user-topbar.app-header').length,
      nav: document.querySelectorAll('.user-nav .user-nav-item').length,
      navLabels: [...document.querySelectorAll('.user-nav .user-nav-item')]
        .map((node) => node.textContent.trim()),
      title: document.title,
      anchors: ['user-section-status', 'user-section-worlds', 'user-section-lottery',
        'user-section-values', 'user-section-map']
        .filter((id) => document.getElementById(id)).length,
      overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth + 2,
    })`);
    check('用户端使用新的公开信息壳层与锚点导航', userShell.topbar === 1
        && userShell.nav === 5 && userShell.anchors === 5
        && userShell.title === 'samsara · 服务器公开信息'
        && userShell.navLabels.includes('地图') && !userShell.overflow,
        JSON.stringify(userShell));

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
    check('用户端地图不显示也不提供 TP 命令', userPage.userViewFlag
        && !userPage.tpText && !userPage.tpHtml
        && !userPage.markerTitle.includes('TP 命令'),
        JSON.stringify({ userView: userPage.userViewFlag, text: userPage.tpText,
          html: userPage.tpHtml, marker: userPage.markerTitle }));
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

    // ---- 路由切回管理端：Vue 外壳与旧页面逻辑仍可用 ----
    await navigate(base + '/');
    await until("window.SamsaraUI && document.querySelector('.app-header')",
        20000, '返回管理端');
    const adminReturn = await evaluate(`(() => ({
      header: !!document.querySelector('.app-header'),
      nav: !!document.getElementById('mods-tabs'),
      locked: document.body.classList.contains('locked'),
      panel: !!document.getElementById('panel-config'),
    }))()`);
    check('路由切回管理端仍可用', adminReturn.header && adminReturn.nav
        && adminReturn.panel, JSON.stringify(adminReturn));

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
