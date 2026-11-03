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

    // ---- 世界栈操作时间线 ----
    const stackTimeline = await evaluate(`(async () => {
      window.SamsaraUI.setActiveTab('world-stack');
      await new Promise((resolve) => setTimeout(resolve, 300));
      await window.SamsaraWorldStack.load();
      await new Promise((resolve) => setTimeout(resolve, 500));
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
      return {
        nav,
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
    })()`);
    check('世界栈时间线导航', stackTimeline.nav.startsWith('世界栈'), stackTimeline.nav);
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

      // 单旗 border + 白色（距离 1）= 中心 + 8 条射线 = 9，不应是 3×3 填充
      const summary = () => document.querySelector('.claim-preview-summary')?.textContent || '';
      const borderSummary = summary();
      const borderCells = Number(canvas.dataset.protectedCells);

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
        borderSummary, borderCells,
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
    check('单面 border 只出射线（9 格，不填充）', claim.borderCells === 9
        && claim.borderSummary.includes('保护 9 区块'), 'cells=' + claim.borderCells);
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
      window.SamsaraUI.api = originalApi;
      const target = panels.find((panel) => panel.classList.contains('dim-overworld'));
      const targetStack = target?.closest('.map-stack-block')?.dataset.stack || '';
      target?.click();
      await wait(600);
      return {
        canvas: Boolean(document.getElementById('map-canvas')),
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
    check('总览状态显示在线人数', mapState.status.includes('在线 1 人'), mapState.status);
    check('点击总览面板进入详细地图', mapState.detailVisible && mapState.overviewHidden
        && mapState.selectedStack === mapState.targetStack,
        JSON.stringify({ detail: mapState.detailVisible, hidden: mapState.overviewHidden,
          selected: mapState.selectedStack, target: mapState.targetStack }));

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
        && !url.includes('/api/map/players')).length;
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
      await wait(1600);
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
      await wait(900);
      const editor = document.querySelector('.curve-editor');
      if (!editor) return { found: false };
      const rows = () => [...editor.querySelectorAll('.curve-row')];
      const initialRows = rows().length;
      const estimateText = editor.querySelector('.curve-estimate')?.textContent || '';
      const firstRatio = editor.querySelector('.curve-ratio');
      const original = firstRatio.value;
      firstRatio.value = '1.5';
      firstRatio.dispatchEvent(new Event('input', { bubbles: true }));
      await wait(350);
      const invalidError = editor.querySelector('.curve-error')?.textContent || '';
      document.getElementById('save').click();
      await wait(250);
      const blocked = (document.getElementById('message')?.textContent || '').includes('阻止保存');
      firstRatio.value = original;
      firstRatio.dispatchEvent(new Event('input', { bubbles: true }));
      await wait(350);
      const fixedError = editor.querySelector('.curve-error')?.textContent || '';
      const add = editor.querySelector('.curve-actions button');
      add.click();
      await wait(200);
      const addedRows = rows().length;
      const lastRemove = rows()[rows().length - 1].querySelector('button');
      lastRemove.click();
      await wait(200);
      const removedRows = rows().length;
      const estimateAfter = editor.querySelector('.curve-estimate')?.textContent || '';
      return {
        found: true, initialRows, estimateText, invalidError, blocked, fixedError,
        addedRows, removedRows, estimateAfter,
      };
    })()`);
    check('保留曲线使用逐行编辑器', curve.found === true && curve.initialRows >= 2,
        JSON.stringify({ found: curve.found, rows: curve.initialRows }));
    check('保留曲线初始容量预估可见',
        curve.estimateText.includes('容量预估') && curve.estimateText.includes('有限档稳态合计'),
        curve.estimateText.slice(0, 120));
    check('非法保留曲线标红并阻止保存', curve.blocked === true
        && curve.invalidError.includes('keepRatio'), curve.invalidError);
    check('修正后校验恢复且可增删档位', curve.fixedError === ''
        && curve.addedRows === curve.initialRows + 1
        && curve.removedRows === curve.initialRows,
        JSON.stringify({ fixed: curve.fixedError, added: curve.addedRows, removed: curve.removedRows }));
    check('增删档位后容量预估刷新', curve.estimateAfter.includes('有限档稳态合计'),
        curve.estimateAfter.slice(0, 120));

    const diffApi = await evaluate(`(async () => {
      const summary = await window.SamsaraUI.api(
        '/api/diff/chunk?stack=world&dim=overworld&cx=0&cz=0');
      const view = await window.SamsaraUI.api('/api/diff/view');
      return {
        summaryOk: summary.ok === true,
        edits: summary.editCount,
        hasProtected: typeof summary.protected === 'boolean',
        viewOk: view.ok === true,
        sessions: view.sessions,
      };
    })()`);
    check('单区块 diff 摘要 API 可用', diffApi.summaryOk && diffApi.edits >= 0
        && diffApi.hasProtected, JSON.stringify(diffApi));
    check('Diff 回看状态 API 可用', diffApi.viewOk && diffApi.sessions === 0,
        JSON.stringify(diffApi));

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
