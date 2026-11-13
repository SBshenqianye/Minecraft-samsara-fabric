'use strict';

// 「本体」页：服务器配置 + 存档备份时间线。
// 所有配置项走 form-kit 的统一控件；备份走 BackupTimeline 的树模型。

(function () {
  const UI = window.SamsaraUI;
  const KIT = window.SamsaraUIKit;
  if (!UI) return;
  const $ = (id) => document.getElementById(id);

  const state = {
    section: 'mods',
    serverPanel: 'server-config-panel',
    files: [],
    model: null,
    drafts: new Map(),
    search: '',
    openStates: new Map(),
    userCache: null,
    playerPick: null,
  };

  function message(text, kind) {
    const el = $('server-config-message');
    if (!el) return;
    el.textContent = text || '';
    el.className = 'message' + (kind ? ' ' + kind : '');
  }

  function backupMessage(text, kind) {
    const el = $('server-backup-message');
    if (!el) return;
    el.textContent = text || '';
    el.className = 'message' + (kind ? ' ' + kind : '');
  }

  function consoleMessage(text, kind) {
    const el = $('server-console-message');
    if (!el) return;
    el.textContent = text || '';
    el.className = 'message' + (kind ? ' ' + kind : '');
  }

  function appendConsole(text) {
    const el = $('server-console-output');
    if (!el) return;
    const line = String(text == null ? '' : text);
    el.textContent = el.textContent ? el.textContent + '\n' + line : line;
    el.scrollTop = el.scrollHeight;
  }

  const consoleHistory = [];
  let consoleHistoryIndex = -1;
  const consoleComplete = { items: [], selected: 0, range: null };

  function closeConsoleComplete() {
    const box = $('server-console-complete');
    if (box) {
      box.hidden = true;
      box.replaceChildren();
    }
    consoleComplete.items = [];
    consoleComplete.selected = 0;
    consoleComplete.range = null;
  }

  function applyConsoleComplete(item) {
    const input = $('server-console-command');
    if (!input || !item || !consoleComplete.range) return;
    const start = consoleComplete.range.start;
    const end = consoleComplete.range.end;
    const value = input.value;
    const next = value.slice(0, start) + item.text + value.slice(end);
    input.value = next;
    const cursor = start + item.text.length;
    input.setSelectionRange(cursor, cursor);
    closeConsoleComplete();
    input.focus();
  }

  function renderConsoleComplete(result) {
    const box = $('server-console-complete');
    if (!box) return;
    box.replaceChildren();
    consoleComplete.items = Array.isArray(result.suggestions) ? result.suggestions : [];
    consoleComplete.selected = 0;
    consoleComplete.range = result.range || null;
    if (!consoleComplete.items.length || !consoleComplete.range) {
      box.hidden = true;
      return;
    }
    const list = document.createElement('div');
    list.className = 'console-complete-list';
    consoleComplete.items.forEach((item, index) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'console-complete-item';
      button.dataset.index = String(index);
      const text = document.createElement('span');
      text.className = 'console-complete-text';
      text.textContent = item.text;
      button.append(text);
      if (item.tooltip) {
        const tip = document.createElement('span');
        tip.className = 'console-complete-tip';
        tip.textContent = item.tooltip;
        button.append(tip);
      }
      button.addEventListener('mousedown', (event) => event.preventDefault());
      button.addEventListener('click', () => applyConsoleComplete(item));
      list.append(button);
    });
    const footer = document.createElement('div');
    footer.className = 'console-complete-footer';
    const preview = document.createElement('span');
    preview.className = 'console-complete-preview';
    footer.append(preview, document.createTextNode('Tab 补全 · ↑↓ 选择 · Esc 关闭'));
    box.append(list, footer);
    box.hidden = false;
    selectConsoleComplete(0);
  }

  function selectConsoleComplete(index) {
    const box = $('server-console-complete');
    if (!box || !consoleComplete.items.length) return;
    const count = consoleComplete.items.length;
    consoleComplete.selected = (index + count) % count;
    const buttons = [...box.querySelectorAll('.console-complete-item')];
    buttons.forEach((button, i) => button.classList.toggle('active',
        i === consoleComplete.selected));
    const active = buttons[consoleComplete.selected];
    if (active) active.scrollIntoView({ block: 'nearest' });
    const preview = box.querySelector('.console-complete-preview');
    const item = consoleComplete.items[consoleComplete.selected];
    const input = $('server-console-command');
    if (preview && item && input && consoleComplete.range) {
      const next = input.value.slice(0, consoleComplete.range.start) + item.text
          + input.value.slice(consoleComplete.range.end);
      preview.textContent = '将补全为：' + next;
    }
  }

  async function requestConsoleComplete() {
    const input = $('server-console-command');
    if (!input) return;
    try {
      const result = await UI.api('/api/server/console/complete', {
        method: 'POST',
        body: JSON.stringify({ command: input.value, cursor: input.selectionStart }),
      });
      if (!result.ok) {
        closeConsoleComplete();
        return;
      }
      if (Array.isArray(result.suggestions) && result.suggestions.length === 1) {
        consoleComplete.range = result.range;
        applyConsoleComplete(result.suggestions[0]);
        return;
      }
      renderConsoleComplete(result);
    } catch (error) {
      closeConsoleComplete();
      consoleMessage(error.message || '补全失败', 'error');
    }
  }

  function dangerousCommand(command) {
    const raw = String(command || '').trim().toLowerCase();
    return raw === 'stop' || raw.startsWith('stop ')
        || raw === 'restart' || raw.startsWith('restart ')
        || raw.startsWith('ban ') || raw.startsWith('pardon ')
        || raw.startsWith('op ') || raw.startsWith('deop ')
        || raw.startsWith('kick ') || raw.startsWith('whitelist ')
        || raw.startsWith('clear ') || raw.startsWith('kill ')
        || raw.startsWith('fill ') || raw.startsWith('setblock ');
  }

  async function runConsole(command) {
    const raw = String(command || '').trim();
    if (!raw) return;
    if (dangerousCommand(raw)
        && !window.confirm('执行危险命令？\n' + raw + '\n\n这可能不可逆或直接关闭服务器。')) {
      return;
    }
    const input = $('server-console-command');
    const send = $('server-console-send');
    if (send) send.disabled = true;
    consoleMessage('执行中…');
    appendConsole('> ' + raw);
    try {
      const data = await UI.api('/api/server/console', {
        method: 'POST',
        body: JSON.stringify({ command: raw }),
      });
      const lines = Array.isArray(data.output) ? data.output : [data.error || '无输出'];
      for (const line of lines) appendConsole(line);
      consoleMessage('完成 · ' + (data.timeMs || 0) + ' ms'
        + (data.stopping ? ' · 服务器正在停止' : ''), data.ok ? 'ok' : 'error');
      if (consoleHistory[0] !== raw) consoleHistory.unshift(raw);
      while (consoleHistory.length > 50) consoleHistory.pop();
      consoleHistoryIndex = -1;
      if (input) input.value = '';
    } catch (error) {
      appendConsole('错误：' + (error.message || '执行失败'));
      consoleMessage(error.message || '执行失败', 'error');
    } finally {
      if (send) send.disabled = false;
    }
  }

  let auditEntries = [];
  let auditOffset = 0;
  let auditHasMore = false;
  let auditTotal = 0;
  let auditLoading = false;

  function auditMessage(text, kind) {
    const el = $('server-audit-message');
    if (!el) return;
    el.textContent = text || '';
    el.className = 'message' + (kind ? ' ' + kind : '');
  }

  async function loadAudit(reset = true) {
    if (auditLoading) return;
    auditLoading = true;
    if (reset) {
      auditOffset = 0;
      auditEntries = [];
    }
    try {
      const data = await UI.api('/api/audit?offset=' + auditOffset + '&limit=30');
      const page = Array.isArray(data.entries) ? data.entries : [];
      auditEntries = auditEntries.concat(page);
      auditOffset += page.length;
      auditTotal = Number(data.total || 0);
      auditHasMore = data.total != null ? auditOffset < auditTotal : page.length >= 30;
      renderAudit();
      auditMessage('已加载 ' + auditEntries.length
        + (auditTotal ? ' / ' + auditTotal : '')
        + ' 条审计记录' + (auditHasMore ? '；可继续加载更早记录。' : '；写请求前自动快照。'), 'ok');
    } catch (error) {
      auditMessage(error.message || '读取审计失败', 'error');
    } finally {
      auditLoading = false;
      // renderAudit 在请求完成前就执行时会把按钮画成“加载中…”并禁用；
      // 请求结束后必须重绘一次，否则“加载更多”永远停在禁用状态。
      renderAudit();
    }
  }

  function renderAudit() {
    const list = $('server-audit-list');
    if (!list) return;
    list.replaceChildren();
    if (auditEntries.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '还没有审计记录。保存配置、权限、身份组、输出模板或服务器配置后会出现。';
      list.append(empty);
      return;
    }
    for (const entry of auditEntries) {
      const row = document.createElement('div');
      row.className = 'server-audit-item';
      const main = document.createElement('div');
      main.className = 'server-audit-main';
      const title = document.createElement('strong');
      title.textContent = String(entry.action || '') + ' · ' + formatTime(entry.createdAt);
      const meta = document.createElement('span');
      meta.textContent = (entry.rollbackable ? '可回滚' : '不可自动回滚')
        + ' · ' + (entry.fileCount || 0) + ' 文件 · ' + formatBytes(entry.bytes)
        + (entry.note ? ' · ' + entry.note : '');
      main.append(title, meta);
      const actions = document.createElement('div');
      actions.className = 'server-audit-actions';
      const preview = document.createElement('button');
      preview.type = 'button';
      preview.className = 'ghost';
      preview.textContent = '预览';
      preview.addEventListener('click', () => previewAudit(entry));
      actions.append(preview);
      if (entry.rollbackable) {
        const rollback = document.createElement('button');
        rollback.type = 'button';
        rollback.className = 'ghost danger';
        rollback.textContent = '回滚';
        rollback.addEventListener('click', () => rollbackAudit(entry));
        actions.append(rollback);
      }
      row.append(main, actions);
      list.append(row);
    }
    if (auditHasMore) {
      const more = document.createElement('button');
      more.type = 'button';
      more.className = 'ghost server-audit-more';
      more.textContent = auditLoading ? '加载中…' : '加载更早的审计记录';
      more.disabled = auditLoading;
      more.addEventListener('click', () => loadAudit(false));
      list.append(more);
    }
  }

  async function previewAudit(entry) {
    try {
      const data = await UI.api('/api/audit/preview', {
        method: 'POST',
        body: JSON.stringify({ id: entry.id }),
      });
      if (!data.ok) {
        auditMessage(data.error || '预览失败', 'error');
        return;
      }
      const preview = data.entry || entry;
      const changes = Array.isArray(data.changes) ? data.changes : [];
      const changed = Number(data.changedCount || 0);
      auditMessage('快照 ' + preview.id + '：' + (preview.fileCount || 0) + ' 文件 / '
        + formatBytes(preview.bytes) + '；差异 ' + changed + ' 个文件；'
        + (preview.note || ''), 'ok');
      const wrap = $('server-audit-diff-wrap');
      const title = $('server-audit-diff-title');
      const changeBox = $('server-audit-changes');
      const diffBox = $('server-audit-diff');
      const diffsBox = $('server-audit-diffs');
      if (title) title.textContent = '差异 · ' + preview.id + ' · ' + changed + ' 个文件';
      const diffs = Array.isArray(data.diffs) ? data.diffs : [];
      if (changeBox) {
        changeBox.replaceChildren();
        const changedChanges = changes.filter((item) => item.status !== 'unchanged');
        changedChanges.forEach((change, index) => {
          const chip = document.createElement('button');
          chip.type = 'button';
          chip.className = 'server-audit-change ' + change.status;
          chip.textContent = ({ added: '+ ', removed: '- ', modified: '~ ' }[change.status] || '')
            + change.path;
          chip.title = '跳到该文件差异';
          chip.addEventListener('click', () => {
            const target = document.getElementById('audit-diff-' + index);
            if (!target) return;
            target.open = true;
            target.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
          });
          changeBox.append(chip);
        });
        if (changeBox.children.length === 0) {
          const same = document.createElement('span');
          same.className = 'muted small';
          same.textContent = '当前文件与这次快照完全一致（没有差异）。';
          changeBox.append(same);
        }
      }
      if (diffsBox) {
        diffsBox.replaceChildren();
        diffs.forEach((item, index) => {
          const details = document.createElement('details');
          details.className = 'server-audit-file';
          details.id = 'audit-diff-' + index;
          if (index === 0) details.open = true;
          const summary = document.createElement('summary');
          summary.textContent = ({ added: '+ ', removed: '- ', modified: '~ ' }[item.status] || '')
            + item.path;
          const pre = document.createElement('pre');
          const lines = String(item.diff || '').split('\n');
          if (lines.length === 0 || (lines.length === 1 && !lines[0])) {
            const empty = document.createElement('div');
            empty.className = 'server-audit-file-empty';
            empty.textContent = '没有可显示的文本差异。';
            details.append(summary, empty);
          } else {
            for (const line of lines) {
              if (line === '') continue;
              const span = document.createElement('span');
              span.className = line.startsWith('+') ? 'server-audit-line-add'
                : line.startsWith('-') ? 'server-audit-line-del' : 'server-audit-line-ctx';
              span.textContent = line;
              pre.append(span);
            }
            details.append(summary, pre);
          }
          diffsBox.append(details);
        });
        if (diffs.length === 0) {
          const empty = document.createElement('div');
          empty.className = 'muted small';
          empty.textContent = '没有可显示的文本差异。';
          diffsBox.append(empty);
        }
      }
      if (diffBox) {
        diffBox.hidden = diffs.length > 0;
        diffBox.textContent = data.diff || '（没有可显示的文本差异）';
      }
      if (wrap) {
        wrap.hidden = false;
        if (typeof wrap.showModal === 'function') wrap.showModal();
      }
    } catch (error) {
      auditMessage(error.message || '预览失败', 'error');
    }
  }

  async function rollbackAudit(entry) {
    if (!window.confirm('回滚到这次 Web 修改之前？\n' + entry.action
        + '\n时间 ' + formatTime(entry.createdAt)
        + '\n\n会先保存当前状态，再恢复快照文件。')) {
      return;
    }
    try {
      const data = await UI.api('/api/audit/rollback', {
        method: 'POST',
        body: JSON.stringify({ id: entry.id, confirm: true }),
      });
      if (!data.ok) {
        auditMessage(data.error || '回滚失败', 'error');
        return;
      }
      auditMessage(data.message || '已回滚', 'ok');
      await loadAudit();
      await loadAll();
    } catch (error) {
      auditMessage(error.message || '回滚失败', 'error');
    }
  }

  function formatBytes(bytes) {
    const n = Number(bytes || 0);
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB';
    return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }

  function formatTime(ms) {
    const n = Number(ms || 0);
    if (!n) return '—';
    try {
      return new Date(n).toLocaleString();
    } catch (e) {
      return String(n);
    }
  }

  function nowStamp() {
    const date = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    const offset = -date.getTimezoneOffset();
    const sign = offset >= 0 ? '+' : '-';
    const abs = Math.abs(offset);
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
        + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds())
        + ' ' + sign + pad(Math.floor(abs / 60)) + pad(abs % 60);
  }

  // ---- 一级分栏与导航 ----

  function setSection(section) {
    state.section = section === 'server' ? 'server' : 'mods';
    document.body.classList.toggle('server-section', state.section === 'server');
    for (const tab of document.querySelectorAll('.top-tab')) {
      tab.classList.toggle('active', tab.dataset.section === state.section);
    }
    const panel = $('panel-server');
    if (panel) panel.hidden = state.section !== 'server';
    const serverTabs = $('server-tabs');
    const serverActions = $('server-actions');
    if (serverTabs) serverTabs.hidden = state.section !== 'server';
    if (serverActions) serverActions.hidden = state.section !== 'server';
    if (state.section === 'server') {
      setServerPanel(state.serverPanel);
      loadAll();
    } else {
      UI.setActiveTab(UI.state.tab || 'config');
    }
  }

  const SERVER_ACTIONS = {
    'server-config-panel': {
      title: '服务器配置',
      hint: '保存会写入所有已修改文件并热重载；properties/eula 需重启',
    },
    'server-backup-panel': {
      title: '存档备份',
      hint: '时间线可回滚到任意节点；恢复后下一次备份从该点分出新分支',
    },
    'server-console-panel': {
      title: 'RCON 控制台',
      hint: '在服务器线程执行命令；stop 会关闭服务器',
    },
    'server-audit-panel': {
      title: 'Web 审计（全部管理端写请求）',
      hint: '覆盖 samsara、其他模组、服务器配置与 RCON；写前快照，配置类可回滚',
    },
  };

  const SERVER_HASH = {
    'server-config-panel': '#server',
    'server-backup-panel': '#server-backup',
    'server-console-panel': '#server-console',
    'server-audit-panel': '#server-audit',
  };

  function setServerPanel(id) {
    state.serverPanel = id;
    for (const tab of document.querySelectorAll('.tab[data-server-panel]')) {
      tab.classList.toggle('active', tab.dataset.serverPanel === id);
    }
    message('', '');
    backupMessage('', '');
    const config = $('server-config-panel');
    const backups = $('server-backup-panel');
    const consolePanel = $('server-console-panel');
    const auditPanel = $('server-audit-panel');
    if (config) config.hidden = id !== 'server-config-panel';
    if (backups) backups.hidden = id !== 'server-backup-panel';
    if (consolePanel) consolePanel.hidden = id !== 'server-console-panel';
    if (auditPanel) auditPanel.hidden = id !== 'server-audit-panel';
    const action = SERVER_ACTIONS[id] || SERVER_ACTIONS['server-config-panel'];
    const title = $('server-action-title');
    const hint = $('server-action-hint');
    const save = $('server-save');
    const create = $('server-backup-create');
    if (title) title.textContent = action.title;
    if (hint) hint.textContent = action.hint;
    if (save) save.hidden = id !== 'server-config-panel';
    if (create) create.hidden = id !== 'server-backup-panel';
    if (state.section === 'server') {
      if (!window.SamsaraUseRouter) {
        try { history.replaceState(null, '', SERVER_HASH[id] || '#server'); } catch (e) { /* file:// 等场景忽略 */ }
      }
      renderServerNav();
    }
    if (id === 'server-backup-panel') {
      loadBackupsOnly();
      window.SamsaraWorldStack?.load();
    } else if (id === 'server-console-panel') {
      consoleMessage('', '');
      $('server-console-command')?.focus();
    } else if (id === 'server-audit-panel') {
      loadAudit();
    } else {
      updateDirtyIndicator();
    }
  }

  function renderServerNav() {
    if (state.section !== 'server') return;
    document.querySelector('main.app-shell')?.classList.remove('no-side');
    if (state.serverPanel === 'server-backup-panel') {
      const timeline = (state.model && state.model.timeline) || { nodes: [] };
      const items = [
        { label: '备份时间线', target: 'server-backup-timeline', count: (timeline.nodes || []).length },
        { label: '任务与回滚', target: 'server-job-list' },
        { label: '文件位置', target: 'world-stack-files-panel' },
      ];
      const targets = items
          .map((item) => ({ id: item.target, el: document.getElementById(item.target) }))
          .filter((item) => item.el);
      UI.buildNav('存档备份', items, targets, { count: (timeline.nodes || []).length });
      return;
    }
    if (state.serverPanel === 'server-console-panel') {
      // 控制台本身就是单块内容，没有可锚定的章节；不显示页内导航，内容区占满。
      UI.clearNav?.();
      document.querySelector('main.app-shell')?.classList.add('no-side');
      return;
    }
    if (state.serverPanel === 'server-audit-panel') {
      UI.buildNav('Web 审计', [
        { label: '审计记录', target: 'server-audit-list' },
      ], [
        { id: 'server-audit-list', el: document.getElementById('server-audit-list') },
      ]);
      return;
    }
    UI.buildNav('服务器配置', state.navItems || [], state.navTargets || [],
        { count: state.files.length });
  }

  // ---- 数据 ----

  async function loadAll() {
    try {
      const data = await UI.api('/api/server');
      state.model = data;
      state.files = data.configFiles || [];
      state.drafts.clear();
      state.userCache = null;
      message('');
      renderConfig();
      renderBackups(data);
      renderJobs(data.jobs || []);
      renderServerNav();
      return data;
    } catch (e) {
      message(UI.authMessage(e, '读取本体配置失败'), 'error');
      return null;
    }
  }

  async function loadBackupsOnly() {
    try {
      const data = await UI.api('/api/server');
      state.model = data;
      state.files = data.configFiles || [];
      renderBackups(data);
      renderJobs(data.jobs || []);
      renderServerNav();
    } catch (e) {
      backupMessage(UI.authMessage(e, '读取备份列表失败'), 'error');
    }
  }

  // ---- 配置：文件 / 条目结构 ----

  const GROUP_ORDER = ['网络与状态', '世界与玩法', '管理与 RCON', '性能与区块', '其他'];

  // 原版 server.properties 默认值；用于“恢复默认”而不是恢复本次加载值。
  const PROPERTY_DEFAULTS = {
    'accepts-transfers': 'false', 'allow-flight': 'false',
    'broadcast-console-to-ops': 'true', 'broadcast-rcon-to-ops': 'true',
    'bug-report-link': '', 'chat-spam-threshold-seconds': '10',
    'command-spam-threshold-seconds': '10', 'difficulty': 'easy',
    'enable-code-of-conduct': 'false', 'enable-jmx-monitoring': 'false',
    'enable-query': 'false', 'enable-rcon': 'false', 'enable-status': 'true',
    'enforce-secure-profile': 'true', 'enforce-whitelist': 'false',
    'entity-broadcast-range-percentage': '100', 'force-gamemode': 'false',
    'function-permission-level': '2', 'gamemode': 'survival',
    'generate-structures': 'true', 'generator-settings': '{}',
    'hardcore': 'false', 'hide-online-players': 'false',
    'initial-disabled-packs': '', 'initial-enabled-packs': 'vanilla',
    'level-name': 'world', 'level-seed': '', 'level-type': 'minecraft\\:normal',
    'log-ips': 'true', 'management-server-allowed-origins': '',
    'management-server-enabled': 'false', 'management-server-host': 'localhost',
    'management-server-port': '0', 'management-server-secret': '',
    'management-server-tls-enabled': 'true',
    'management-server-tls-keystore': '',
    'management-server-tls-keystore-password': '',
    'max-chained-neighbor-updates': '1000000', 'max-players': '20',
    'max-tick-time': '60000', 'max-world-size': '29999984',
    'motd': 'A Minecraft Server', 'network-compression-threshold': '256',
    'online-mode': 'true', 'op-permission-level': '4',
    'pause-when-empty-seconds': '60', 'player-idle-timeout': '0',
    'prevent-proxy-connections': 'false', 'query.port': '25565',
    'rate-limit': '0', 'rcon.password': '', 'rcon.port': '25575',
    'region-file-compression': 'deflate', 'require-resource-pack': 'false',
    'resource-pack': '', 'resource-pack-id': '', 'resource-pack-prompt': '',
    'resource-pack-sha1': '', 'server-ip': '', 'server-port': '25565',
    'simulation-distance': '10', 'spawn-protection': '16',
    'status-heartbeat-interval': '0', 'sync-chunk-writes': 'true',
    'text-filtering-config': '', 'text-filtering-version': '0',
    'use-native-transport': 'true', 'view-distance': '10', 'white-list': 'false',
    'eula': 'false',
  };

  const PROPERTY_SPECS = {
    'accepts-transfers': { group: '网络与状态', type: 'bool', desc: '是否接受其它服务器通过 transfer 协议转入的玩家' },
    'allow-flight': { group: '世界与玩法', type: 'bool', desc: '允许生存模式飞行；关闭时飞行会被反作弊踢出' },
    'broadcast-console-to-ops': { group: '管理与 RCON', type: 'bool', desc: '把控制台命令的输出广播给 OP' },
    'broadcast-rcon-to-ops': { group: '管理与 RCON', type: 'bool', desc: '把 RCON 命令的输出广播给 OP' },
    'bug-report-link': { group: '网络与状态', type: 'url', desc: '客户端崩溃时显示的反馈链接；留空使用默认' },
    'chat-spam-threshold-seconds': { group: '世界与玩法', type: 'int', min: 0, desc: '聊天刷屏判定窗口（秒）' },
    'command-spam-threshold-seconds': { group: '世界与玩法', type: 'int', min: 0, desc: '命令刷屏判定窗口（秒）' },
    'difficulty': {
      group: '世界与玩法', type: 'enum', choices: [
        { value: 'peaceful', label: '和平' }, { value: 'easy', label: '简单' },
        { value: 'normal', label: '普通' }, { value: 'hard', label: '困难' },
      ], desc: '游戏难度',
    },
    'enable-code-of-conduct': { group: '网络与状态', type: 'bool', desc: '是否要求玩家遵守行为准则' },
    'enable-jmx-monitoring': { group: '管理与 RCON', type: 'bool', desc: '开启 JMX 监控（性能诊断）' },
    'enable-query': { group: '管理与 RCON', type: 'bool', desc: '开启 GameSpy4 查询协议' },
    'enable-rcon': { group: '管理与 RCON', type: 'bool', desc: '开启 RCON 远程控制台' },
    'enable-status': { group: '网络与状态', type: 'bool', desc: '在服务器列表显示在线状态' },
    'enforce-secure-profile': { group: '网络与状态', type: 'bool', desc: '要求正版签名聊天；离线服通常关闭' },
    'enforce-whitelist': { group: '世界与玩法', type: 'bool', desc: '白名单开启时踢出不在名单的玩家' },
    'entity-broadcast-range-percentage': { group: '性能与区块', type: 'int', min: 10, max: 1000, desc: '实体广播范围百分比（10-1000）' },
    'force-gamemode': { group: '世界与玩法', type: 'bool', desc: '加入时强制切换为默认游戏模式' },
    'function-permission-level': { group: '管理与 RCON', type: 'int', min: 0, max: 4, desc: '函数与数据包的默认权限等级（0-4）' },
    'gamemode': {
      group: '世界与玩法', type: 'enum', choices: [
        { value: 'survival', label: '生存' }, { value: 'creative', label: '创造' },
        { value: 'adventure', label: '冒险' }, { value: 'spectator', label: '旁观' },
      ], desc: '新玩家的默认游戏模式',
    },
    'generate-structures': { group: '世界与玩法', type: 'bool', desc: '生成村庄、要塞等结构' },
    'generator-settings': { group: '世界与玩法', type: 'multiline', desc: '自定义世界生成参数（JSON）；留空使用默认' },
    'hardcore': { group: '世界与玩法', type: 'bool', desc: '极限模式：死亡后不可复活' },
    'hide-online-players': { group: '网络与状态', type: 'bool', desc: '在服务器列表隐藏在线玩家' },
    'initial-disabled-packs': { group: '世界与玩法', type: 'multiline', desc: '默认禁用的数据包列表（逗号分隔）' },
    'initial-enabled-packs': { group: '世界与玩法', type: 'multiline', desc: '默认启用的数据包列表（逗号分隔）' },
    'level-name': { group: '世界与玩法', type: 'text', desc: '世界目录名；本体恢复要求与备份的 level-name 一致' },
    'level-seed': { group: '世界与玩法', type: 'text', desc: '世界种子；留空随机生成' },
    'level-type': {
      group: '世界与玩法', type: 'enum', choices: [
        { value: 'minecraft\\:normal', label: '默认' },
        { value: 'minecraft\\:flat', label: '超平坦' },
        { value: 'minecraft\\:large_biomes', label: '巨型生物群系' },
        { value: 'minecraft\\:amplified', label: '放大化' },
        { value: 'minecraft\\:single_biome_surface', label: '单一生物群系' },
      ], desc: '世界生成器类型',
    },
    'log-ips': { group: '网络与状态', type: 'bool', desc: '在日志中记录玩家 IP' },
    'management-server-allowed-origins': { group: '管理与 RCON', type: 'multiline', desc: '管理服务允许的 Origin；逗号分隔' },
    'management-server-enabled': { group: '管理与 RCON', type: 'bool', desc: '开启内置管理服务' },
    'management-server-host': { group: '管理与 RCON', type: 'text', desc: '管理服务监听地址' },
    'management-server-port': { group: '管理与 RCON', type: 'int', min: 1, max: 65535, desc: '管理服务端口' },
    'management-server-secret': { group: '管理与 RCON', type: 'password', desc: '管理服务密钥（敏感）' },
    'management-server-tls-enabled': { group: '管理与 RCON', type: 'bool', desc: '管理服务启用 TLS' },
    'management-server-tls-keystore': { group: '管理与 RCON', type: 'text', desc: 'TLS keystore 文件路径' },
    'management-server-tls-keystore-password': { group: '管理与 RCON', type: 'password', desc: 'TLS keystore 密码（敏感）' },
    'max-chained-neighbor-updates': { group: '性能与区块', type: 'int', min: -1, desc: '链式方块更新上限；-1 不限制' },
    'max-players': { group: '网络与状态', type: 'int', min: 0, desc: '最大在线玩家数' },
    'max-tick-time': { group: '性能与区块', type: 'int', min: -1, desc: '单 tick 超时（毫秒）；-1 关闭看门狗' },
    'max-world-size': { group: '世界与玩法', type: 'int', min: 1, desc: '世界边界半径（方块）' },
    'motd': { group: '网络与状态', type: 'text', chat: true, desc: '服务器列表显示的描述；支持 § 颜色代码，可直接选色并预览' },
    'network-compression-threshold': { group: '性能与区块', type: 'int', min: -1, desc: '网络压缩阈值（字节）；-1 关闭' },
    'online-mode': { group: '网络与状态', type: 'bool', desc: '正版验证；离线服设为 false' },
    'op-permission-level': { group: '管理与 RCON', type: 'int', min: 0, max: 4, desc: 'OP 的默认权限等级（0-4）' },
    'pause-when-empty-seconds': { group: '世界与玩法', type: 'int', min: 0, desc: '无人在线多少秒后暂停；0 不暂停' },
    'player-idle-timeout': { group: '世界与玩法', type: 'int', min: 0, desc: '挂机踢出时间（分钟）；0 不踢' },
    'prevent-proxy-connections': { group: '网络与状态', type: 'bool', desc: '拒绝通过代理 / VPN 连接的玩家' },
    'query.port': { group: '管理与 RCON', type: 'int', min: 1, max: 65535, desc: 'GameSpy4 查询端口' },
    'rate-limit': { group: '网络与状态', type: 'int', min: 0, desc: '每玩家每秒数据包上限；0 不限制' },
    'rcon.password': { group: '管理与 RCON', type: 'password', desc: 'RCON 密码（敏感）；建议只在本机使用' },
    'rcon.port': { group: '管理与 RCON', type: 'int', min: 1, max: 65535, desc: 'RCON 端口' },
    'region-file-compression': {
      group: '性能与区块', type: 'enum', choices: [
        { value: 'deflate', label: 'deflate' }, { value: 'lz4', label: 'lz4' }, { value: 'none', label: 'none' },
      ], desc: 'region 文件压缩算法',
    },
    'require-resource-pack': { group: '网络与状态', type: 'bool', desc: '强制玩家使用资源包' },
    'resource-pack': { group: '网络与状态', type: 'url', desc: '资源包下载地址' },
    'resource-pack-id': { group: '网络与状态', type: 'text', desc: '资源包唯一标识' },
    'resource-pack-prompt': { group: '网络与状态', type: 'text', chat: true, desc: '资源包提示文本；支持 § 颜色代码' },
    'resource-pack-sha1': { group: '网络与状态', type: 'text', desc: '资源包 SHA-1 校验值' },
    'server-ip': { group: '网络与状态', type: 'text', desc: '监听地址；留空表示所有网卡' },
    'server-port': { group: '网络与状态', type: 'int', min: 1, max: 65535, desc: '游戏端口' },
    'simulation-distance': { group: '性能与区块', type: 'int', min: 2, max: 32, desc: '模拟距离（区块）' },
    'spawn-protection': { group: '世界与玩法', type: 'int', min: 0, desc: '出生点保护半径（方块）；0 关闭' },
    'status-heartbeat-interval': { group: '网络与状态', type: 'int', min: 0, desc: '服务器列表状态心跳间隔（秒）' },
    'sync-chunk-writes': { group: '性能与区块', type: 'bool', desc: '强制同步区块写入（更安全但更慢）' },
    'text-filtering-config': { group: '世界与玩法', type: 'text', desc: '聊天过滤服务配置路径' },
    'text-filtering-version': { group: '世界与玩法', type: 'int', desc: '聊天过滤服务协议版本' },
    'use-native-transport': { group: '性能与区块', type: 'bool', desc: 'Linux 上使用原生网络传输' },
    'view-distance': { group: '性能与区块', type: 'int', min: 2, max: 32, desc: '视距（区块）' },
    'white-list': { group: '世界与玩法', type: 'bool', desc: '启用白名单' },
    'eula': { group: '其他', type: 'bool', desc: 'EULA 同意标记；必须为 true 才能启动服务器' },
  };

  const JSON_SPECS = {
    whitelist: {
      addLabel: '+ 手动添加白名单玩家',
      player: true,
      fields: {
        uuid: { desc: '玩家 UUID（正版或离线）', type: 'text', mono: true },
        name: { desc: '玩家名' },
      },
    },
    ops: {
      addLabel: '+ 手动添加 OP',
      player: true,
      fields: {
        uuid: { desc: '玩家 UUID（正版或离线）', type: 'text', mono: true },
        name: { desc: '玩家名' },
        level: {
          desc: 'OP 权限等级', type: 'enum', numeric: true, choices: [
            { value: '1', label: '1' }, { value: '2', label: '2' },
            { value: '3', label: '3' }, { value: '4', label: '4' },
          ],
        },
        bypassesPlayerLimit: { desc: '允许在服务器满员时进入', type: 'bool' },
      },
    },
    'banned-players': {
      addLabel: '+ 手动添加玩家封禁',
      player: true,
      fields: {
        uuid: { desc: '玩家 UUID（正版或离线）', type: 'text', mono: true },
        name: { desc: '玩家名' },
        created: { desc: '封禁时间；添加时自动填入', type: 'text', quick: 'now' },
        source: { desc: '执行封禁的来源', type: 'text' },
        expires: { desc: '到期时间；forever 表示永久', type: 'text', quick: 'forever' },
        reason: { desc: '封禁原因', type: 'text' },
      },
    },
    'banned-ips': {
      addLabel: '+ 手动添加 IP 封禁',
      player: false,
      fields: {
        ip: { desc: 'IP 地址；添加时自动填时间与原因', type: 'text', mono: true },
        created: { desc: '封禁时间；添加时自动填入', type: 'text', quick: 'now' },
        source: { desc: '执行封禁的来源', type: 'text' },
        expires: { desc: '到期时间；forever 表示永久', type: 'text', quick: 'forever' },
        reason: { desc: '封禁原因', type: 'text' },
      },
    },
  };

  const FILE_META = {
    whitelist: {
      className: 'file-access',
      icon: '白',
      badge: '权限 · 准入名单',
      note: '白名单只决定谁能进入服务器；保存后立即重载，但需要先开启 white-list。',
    },
    ops: {
      className: 'file-access',
      icon: 'OP',
      badge: '权限 · 管理员',
      note: 'OP 拥有管理命令权限；等级 4 等同服主，修改前请确认对象。',
    },
    'banned-players': {
      className: 'file-danger',
      icon: '禁',
      badge: '危险 · 玩家封禁',
      note: '玩家封禁会让目标无法进入服务器；保存后立即重载。',
    },
    'banned-ips': {
      className: 'file-danger',
      icon: 'IP',
      badge: '危险 · IP 封禁',
      note: 'IP 封禁可能影响同一网络下的其他玩家；请谨慎添加。',
    },
  };

  function specFor(fileId, key) {
    if (fileId === 'server-properties') {
      return PROPERTY_SPECS[key] || {
        group: '其他',
        type: 'text',
        desc: '自定义或未收录的服务器属性；值会原样写回 server.properties。',
      };
    }
    if (fileId === 'eula') {
      return PROPERTY_SPECS.eula;
    }
    return { group: '其他', type: 'text', desc: '' };
  }

  function splitPropertyLines(content) {
    // 去掉 UTF-8 BOM；properties 的 # 与 ! 都是注释，不能当成可编辑字段。
    return String(content || '').replace(/^\uFEFF/, '').split(/\r?\n/).map((line) => {
      if (line === '') return { type: 'blank', raw: '' };
      if (/^\s*[#!]/.test(line) || /^\s*\\[#!]/.test(line)) {
        return { type: 'comment', raw: line };
      }
      const eq = line.indexOf('=');
      if (eq <= 0) return { type: 'raw', raw: line };
      const value = line.slice(eq + 1);
      return { type: 'prop', key: line.slice(0, eq), value, originalValue: value };
    });
  }

  function parseDraft(file) {
    const content = file.content || '';
    if (file.kind === 'json_array') {
      try {
        const data = JSON.parse(content.trim() === '' ? '[]' : content);
        if (Array.isArray(data)) return { kind: 'json', data, dirty: false };
      } catch (e) {
        return { kind: 'text', text: content, parseError: true, dirty: false };
      }
      return { kind: 'text', text: content, parseError: true, dirty: false };
    }
    if (file.kind === 'properties' || file.kind === 'eula') {
      const tokens = splitPropertyLines(content);
      if (file.kind === 'eula' && !tokens.some((t) => t.type === 'prop' && t.key === 'eula')) {
        tokens.push({ type: 'prop', key: 'eula', value: 'false', originalValue: 'false' });
      }
      return { kind: 'lines', tokens, dirty: false };
    }
    return { kind: 'text', text: content, dirty: false };
  }

  function draftFor(file) {
    if (!state.drafts.has(file.id)) state.drafts.set(file.id, parseDraft(file));
    return state.drafts.get(file.id);
  }

  function markDirty(file, draft) {
    // 每次都按“当前草稿 vs 文件原文”重新计算，避免改回原值后仍显示修改条目（假账）。
    refreshDirty(file);
    updateDirtyIndicator();
  }

  function refreshDirty(file) {
    const draft = state.drafts.get(file.id);
    if (!draft) return;
    if (draft.kind === 'json') {
      const next = buildContent(draft);
      try {
        draft.dirty = JSON.stringify(JSON.parse(next))
            !== JSON.stringify(JSON.parse(file.content || '[]'));
      } catch (e) {
        draft.dirty = true;
      }
    } else if (draft.kind === 'lines') {
      // 只比较“真正会写回的字段/注释原文”，避免 BOM、行尾或多出来的空白造成假修改。
      draft.dirty = !samePropertyLines(draft.tokens, splitPropertyLines(file.content));
    } else {
      const normalize = (text) => String(text || '')
          .replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').replace(/\s+$/, '');
      draft.dirty = normalize(buildContent(draft)) !== normalize(file.content);
    }
    updateDirtyIndicator();
  }

  function samePropertyLines(a, b) {
    const left = a || [];
    const right = b || [];
    if (left.length !== right.length) return false;
    for (let i = 0; i < left.length; i++) {
      const x = left[i];
      const y = right[i];
      if (x.type !== y.type) return false;
      if (x.type === 'prop') {
        if (x.key !== y.key || String(x.value) !== String(y.value)) return false;
      } else if ((x.raw || '') !== (y.raw || '')) {
        return false;
      }
    }
    return true;
  }

  function dirtyCount() {
    let count = 0;
    for (const draft of state.drafts.values()) {
      if (draft.dirty) count++;
    }
    return count;
  }

  function updateDirtyIndicator() {
    const count = dirtyCount();
    const save = $('server-save');
    if (save) save.textContent = count > 0 ? '保存并重载（' + count + '）' : '保存并重载';
  }

  function buildContent(draft) {
    if (!draft) return '';
    if (draft.kind === 'json') return JSON.stringify(draft.data, null, 2) + '\n';
    if (draft.kind === 'lines') {
      return draft.tokens
          .map((token) => (token.type === 'prop' ? token.key + '=' + token.value : (token.raw || '')))
          .join('\n');
    }
    return draft.text || '';
  }

  // ---- 配置渲染 ----

  function captureOpenStates() {
    for (const details of document.querySelectorAll('#server-config-files details[id]')) {
      state.openStates.set(details.id, details.open);
    }
  }

  function isOpen(id, fallback) {
    return state.openStates.has(id) ? state.openStates.get(id) : fallback;
  }

  function fileId(file) {
    return 'server-file-' + file.id;
  }

  function displayPath(file) {
    if (file.path) return file.path;
    const root = state.model && state.model.serverRoot;
    if (!root) return file.name;
    const sep = root.includes('\\') ? '\\' : '/';
    return root.replace(/[\\/]+$/, '') + sep + file.name;
  }

  function entryId(file, token, index) {
    return 'server-entry-' + file.id + '-' + encodeURIComponent(token.key || String(index));
  }

  function jsonItemId(file, index) {
    return 'server-json-' + file.id + '-' + index;
  }

  function renderConfig() {
    const container = $('server-config-files');
    const count = $('server-config-count');
    if (count) count.textContent = String(state.files.length);
    if (!container) return;
    captureOpenStates();
    container.textContent = '';
    const query = state.search.trim().toLowerCase();
    const navItems = [];
    const navTargets = [];
    let shown = 0;
    for (const file of state.files) {
      const draft = draftFor(file);
      const built = buildFileBlock(file, draft, query);
      if (!built) continue;
      shown += built.count;
      container.appendChild(built.el);
      navItems.push(built.nav);
      navTargets.push(...built.targets);
    }
    if (!state.files.length) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '没有可编辑的服务器配置文件。';
      container.appendChild(empty);
    } else if (shown === 0) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '没有匹配的配置项。';
      container.appendChild(empty);
    }
    state.navItems = navItems;
    state.navTargets = navTargets;
    if (state.section === 'server' && state.serverPanel === 'server-config-panel') {
      UI.buildNav('服务器配置', navItems, navTargets, { count: state.files.length });
    }
    updateDirtyIndicator();
  }

  function buildFileBlock(file, draft, query) {
    if (draft.kind === 'json') return buildJsonFile(file, draft, query);
    if (draft.kind === 'lines') return buildLinesFile(file, draft, query);
    return buildTextFile(file, draft);
  }

  function buildLinesFile(file, draft, query) {
    const props = draft.tokens
        .map((token, index) => ({ token, index }))
        .filter(({ token }) => token.type === 'prop');
    const visible = props.filter(({ token }) => {
      const spec = specFor(file.id, token.key);
      return !query || token.key.toLowerCase().includes(query)
          || String(spec.desc || '').toLowerCase().includes(query);
    });
    if (!visible.length) return null;

    const groups = new Map();
    for (const item of visible) {
      const spec = specFor(file.id, item.token.key);
      const name = file.kind === 'eula' ? 'EULA' : (spec.group || '其他');
      if (!groups.has(name)) groups.set(name, []);
      groups.get(name).push(item);
    }
    const groupOrder = file.kind === 'eula'
        ? ['EULA']
        : GROUP_ORDER.filter((name) => groups.has(name)).concat(
            [...groups.keys()].filter((name) => !GROUP_ORDER.includes(name)));

    const body = [];
    const targets = [];
    const navChildren = [];
    if (file.kind === 'properties') {
      const note = document.createElement('p');
      note.className = 'server-file-note';
      note.textContent = '以 # 或 ! 开头的行是注释，不会显示为可编辑字段；这里只列出 key=value。';
      body.push(note);
    }
    for (const name of groupOrder) {
      const items = groups.get(name);
      if (!items.length) continue;
      const groupId = 'server-group-' + file.id + '-'
          + encodeURIComponent(name);
      const entries = [];
      const entryNav = [];
      for (const { token, index } of items) {
        const id = entryId(file, token, index);
        const row = buildPropertyRow(file, draft, token, index, id);
        entries.push(row);
        entryNav.push({ label: token.key, target: id });
        targets.push({ id, el: row });
      }
      const groupEl = KIT.group(name, items.length, entries, {
        id: groupId,
        open: isOpen(groupId, true),
      });
      body.push(groupEl);
      targets.push({ id: groupId, el: groupEl });
      navChildren.push({ label: name, count: items.length, target: groupId, children: entryNav });
    }

    const id = fileId(file);
    const el = KIT.collapsible({
      id,
      title: file.name,
      path: displayPath(file),
      count: visible.length,
      open: isOpen(id, true),
      body,
    });
    targets.push({ id, el });
    return {
      el,
      count: visible.length,
      targets,
      nav: { label: file.name, count: visible.length, target: id, children: navChildren },
    };
  }

  function buildPropertyRow(file, draft, token, index, id) {
    const spec = specFor(file.id, token.key);
    const badges = [];
    if (file.restartRequired) badges.push({ text: '需重启', kind: 'restart' });
    if (spec.type === 'password') badges.push({ text: '敏感' });
    let control;
    if (spec.chat && window.SamsaraChatText) {
      control = window.SamsaraChatText.create({
        value: token.value,
        rows: 3,
        serverList: file.id === 'server-properties' && token.key === 'motd',
        online: state.model && state.model.onlinePlayers != null ? state.model.onlinePlayers : 0,
        maxPlayers: state.model && state.model.maxPlayers != null ? state.model.maxPlayers : 20,
        version: state.model && state.model.version ? state.model.version : 'Minecraft 26.3',
        onChange: (next) => {
          token.value = next == null ? '' : String(next);
          markDirty(file, draft);
        },
      }).element;
    } else {
      control = KIT.control(spec, token.value, (next) => {
        token.value = typeof next === 'boolean' ? String(next) : String(next == null ? '' : next);
        markDirty(file, draft);
      });
    }
    const actions = [];
    if (file.kind === 'properties') {
      const reset = KIT.button('恢复默认', 'ghost', () => {
        // 真正恢复为 Minecraft 原版默认值；保留配置项本身，不删除整行。
        const fallback = token.originalValue != null ? token.originalValue : '';
        token.value = Object.prototype.hasOwnProperty.call(PROPERTY_DEFAULTS, token.key)
          ? PROPERTY_DEFAULTS[token.key] : fallback;
        markDirty(file, draft);
        renderConfig();
      });
      reset.title = Object.prototype.hasOwnProperty.call(PROPERTY_DEFAULTS, token.key)
        ? '恢复为 Minecraft 原版默认值：' + (PROPERTY_DEFAULTS[token.key] || '（空）')
        : '恢复为本次加载时的值；保留配置项，不会删除这一条';
      actions.push(reset);
    }
    return KIT.field({
      id,
      className: spec.chat ? 'entry-wide' : '',
      label: token.key,
      description: spec.desc || '',
      badges,
      control,
      actions,
    });
  }

  function buildJsonFile(file, draft, query) {
    const spec = JSON_SPECS[file.id] || { fields: {}, addLabel: '+ 添加一条', player: false };
    const visible = draft.data
        .map((item, index) => ({ item, index }))
        .filter(({ item }) => {
          if (!query) return true;
          return JSON.stringify(item).toLowerCase().includes(query)
              || Object.keys(item).some((key) => String((spec.fields[key] || {}).desc || '')
                  .toLowerCase().includes(query));
        });
    if (!visible.length && query) return null;

    const list = document.createElement('div');
    list.className = 'server-json-list';
    const targets = [];
    const itemNav = [];
    for (const { item, index } of visible) {
      const id = jsonItemId(file, index);
      const built = buildJsonItem(file, draft, item, index, id, spec);
      list.appendChild(built.el);
      targets.push({ id, el: built.el });
      itemNav.push({ label: built.title, target: id });
    }
    if (!visible.length) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '这个文件还是空数组。';
      list.appendChild(empty);
    }

    const actions = document.createElement('div');
    actions.className = 'server-json-actions';
    const add = KIT.button(spec.addLabel || '+ 添加一条', 'ghost', () => {
      const item = defaultJsonItem(file.id);
      draft.data.push(item);
      clearSearch();
      refreshDirty(file);
      renderConfig();
      scrollToId(jsonItemId(file, draft.data.length - 1));
    });
    actions.appendChild(add);
    if (spec.player) {
      const pick = KIT.button('从玩家选择', 'ghost', () => {
        openPlayerPicker('选择玩家' + (file.id === 'whitelist' ? '加入白名单' : ''), (player) => {
          const item = defaultJsonItem(file.id);
          item.uuid = player.uuid;
          item.name = player.name;
          autoFillBan(file.id, item);
          draft.data.push(item);
          clearSearch();
          refreshDirty(file);
          renderConfig();
          scrollToId(jsonItemId(file, draft.data.length - 1));
        });
      });
      actions.appendChild(pick);
    }
    list.appendChild(actions);

    const id = fileId(file);
    const meta = FILE_META[file.id] || null;
    let summaryExtra = null;
    const body = [];
    if (meta) {
      summaryExtra = document.createElement('span');
      summaryExtra.className = 'server-file-badge';
      summaryExtra.textContent = meta.badge;
      const note = document.createElement('div');
      note.className = 'server-file-note';
      note.textContent = meta.note;
      body.push(note);
    }
    body.push(list);
    const el = KIT.collapsible({
      id,
      title: file.name,
      path: displayPath(file),
      count: draft.data.length,
      open: isOpen(id, true),
      className: meta ? 'server-file-card ' + meta.className : 'server-file-card',
      summaryExtra,
      body,
    });
    if (meta) {
      const titleNode = el.querySelector('.file-title');
      if (titleNode) {
        const icon = document.createElement('span');
        icon.className = 'server-file-icon';
        icon.textContent = meta.icon;
        titleNode.prepend(icon);
      }
    }
    targets.push({ id, el });
    return {
      el,
      count: visible.length || draft.data.length,
      targets,
      nav: { label: file.name, count: draft.data.length, target: id, children: itemNav },
    };
  }

  function buildJsonItem(file, draft, item, index, id, spec) {
    const el = document.createElement('details');
    el.className = 'file-block server-json-item';
    el.id = id;
    el.open = isOpen(id, true);

    const summary = document.createElement('summary');
    summary.className = 'file-summary server-json-head';
    const info = document.createElement('div');
    const title = document.createElement('h4');
    title.className = 'file-title';
    title.textContent = item.name || item.ip || item.uuid || ('条目 ' + (index + 1));
    const path = document.createElement('p');
    path.className = 'file-path';
    path.textContent = item.uuid || item.ip || '';
    info.append(title, path);
    const actions = document.createElement('div');
    actions.className = 'server-json-item-actions';
    if (spec.player) {
      const pick = KIT.button('选择玩家', 'ghost', () => {
        openPlayerPicker('选择玩家', (player) => {
          item.uuid = player.uuid;
          item.name = player.name;
          autoFillBan(file.id, item);
          refreshDirty(file);
          renderConfig();
        });
      });
      actions.appendChild(pick);
    }
    const remove = KIT.button('删除', 'ghost danger', () => {
      draft.data.splice(index, 1);
      refreshDirty(file);
      renderConfig();
    });
    actions.appendChild(remove);
    summary.append(info, actions);
    el.appendChild(summary);

    const body = document.createElement('div');
    body.className = 'file-body';
    const rows = document.createElement('div');
    rows.className = 'struct-list';
    for (const key of Object.keys(item)) {
      const fieldSpec = (spec.fields && spec.fields[key]) || {
        desc: '自定义字段', type: typeof item[key] === 'boolean' ? 'bool'
          : typeof item[key] === 'number' ? 'int' : 'text',
      };
      const row = document.createElement('div');
      row.className = 'struct-row';
      const meta = document.createElement('div');
      meta.className = 'server-field-meta';
      const label = document.createElement('span');
      label.className = 'server-field-label';
      label.textContent = key;
      const desc = document.createElement('small');
      desc.textContent = fieldSpec.desc || '';
      meta.append(label, desc);
      row.appendChild(meta);

      const control = KIT.control(fieldSpec,
          fieldSpec.type === 'bool' ? item[key] === true : item[key],
          (next) => {
            if (fieldSpec.type === 'bool') item[key] = Boolean(next);
            else if (fieldSpec.type === 'enum') {
              item[key] = fieldSpec.numeric ? Number(next) : next;
            } else if (fieldSpec.type === 'int' || fieldSpec.type === 'number') {
              item[key] = next === '' ? 0 : Number(next);
            } else item[key] = next;
            markDirty(file, draft);
          });
      const controlWrap = document.createElement('div');
      controlWrap.className = 'server-field-control';
      controlWrap.appendChild(control);
      if (fieldSpec.quick === 'now') {
        const quick = KIT.button('现在', 'ghost', () => {
          item[key] = nowStamp();
          refreshDirty(file);
          renderConfig();
        });
        controlWrap.appendChild(quick);
      } else if (fieldSpec.quick === 'forever') {
        const quick = KIT.button('永久', 'ghost', () => {
          item[key] = 'forever';
          refreshDirty(file);
          renderConfig();
        });
        controlWrap.appendChild(quick);
      }
      row.appendChild(controlWrap);
      rows.appendChild(row);
    }
    body.appendChild(rows);
    el.appendChild(body);
    return { el, title: title.textContent };
  }

  function buildTextFile(file, draft) {
    const body = document.createElement('div');
    if (draft.parseError) {
      const warn = document.createElement('p');
      warn.className = 'message warning';
      warn.textContent = 'JSON 解析失败，已切换为源码编辑；保存前请先修正格式。';
      body.appendChild(warn);
    }
    const editor = document.createElement('textarea');
    editor.className = 'server-config-editor';
    editor.spellcheck = false;
    editor.value = draft.text || '';
    editor.addEventListener('input', () => {
      draft.text = editor.value;
      markDirty(file, draft);
    });
    body.appendChild(editor);
    const id = fileId(file);
    const el = KIT.collapsible({
      id,
      title: file.name,
      path: displayPath(file),
      count: 1,
      open: isOpen(id, true),
      body,
    });
    return {
      el,
      count: 1,
      targets: [{ id, el }],
      nav: { label: file.name, count: 1, target: id },
    };
  }

  function defaultJsonItem(fileId) {
    if (fileId === 'whitelist') return { uuid: '', name: '' };
    if (fileId === 'ops') return { uuid: '', name: '', level: 4, bypassesPlayerLimit: false };
    if (fileId === 'banned-players') {
      return {
        uuid: '', name: '', created: nowStamp(), source: 'Web 控制台',
        expires: 'forever', reason: 'Banned by an operator.',
      };
    }
    if (fileId === 'banned-ips') {
      return {
        ip: '', created: nowStamp(), source: 'Web 控制台',
        expires: 'forever', reason: 'Banned by an operator.',
      };
    }
    return {};
  }

  function autoFillBan(fileId, item) {
    if (fileId !== 'banned-players' && fileId !== 'banned-ips') return;
    if ('created' in item && !item.created) item.created = nowStamp();
    if ('expires' in item && !item.expires) item.expires = 'forever';
    if ('source' in item && !item.source) item.source = 'Web 控制台';
    if ('reason' in item && !item.reason) item.reason = 'Banned by an operator.';
  }

  function scrollToId(id) {
    if (UI.scrollToTarget) UI.scrollToTarget(id);
    else document.getElementById(id)?.scrollIntoView({ block: 'start' });
  }

  function clearSearch() {
    state.search = '';
    const search = $('server-config-search');
    if (search) search.value = '';
  }

  // ---- 玩家选择 ----

  async function openPlayerPicker(title, onPick) {
    const dialog = $('player-dialog');
    if (!dialog) return;
    state.playerPick = onPick;
    const titleEl = $('player-dialog-title');
    if (titleEl) titleEl.textContent = title || '选择玩家';
    const search = $('player-search');
    if (search) search.value = '';
    const list = $('player-list');
    if (list) {
      list.textContent = '';
      const loading = document.createElement('p');
      loading.className = 'muted small';
      loading.textContent = '正在读取玩家列表…';
      list.appendChild(loading);
    }
    dialog.showModal();
    if (!state.userCache) {
      try {
        const data = await UI.api('/api/server/players');
        state.userCache = data.players || [];
      } catch (e) {
        state.userCache = [];
      }
    }
    renderPlayerList();
  }

  function renderPlayerList() {
    const list = $('player-list');
    if (!list) return;
    list.textContent = '';
    const search = $('player-search');
    const query = (search && search.value || '').trim().toLowerCase();
    const players = (state.userCache || []).filter((player) => !query
        || String(player.name || '').toLowerCase().includes(query)
        || String(player.uuid || '').toLowerCase().includes(query));
    if (!players.length) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '没有匹配的已知玩家；可以先让玩家进一次服，或手动填写。';
      list.appendChild(empty);
      return;
    }
    for (const player of players) {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'player-row';
      const name = document.createElement('strong');
      name.textContent = player.name;
      const meta = document.createElement('span');
      meta.className = 'muted small';
      meta.textContent = (player.online ? '在线 · ' : '') + (player.uuid || '');
      const badge = document.createElement('span');
      badge.className = 'world-stack-badge' + (player.online ? ' ok' : '');
      badge.textContent = player.online ? '在线' : '缓存';
      row.append(name, meta, badge);
      row.addEventListener('click', () => {
        const pick = state.playerPick;
        const dialog = $('player-dialog');
        if (dialog) dialog.close();
        if (pick) pick(player);
      });
      list.appendChild(row);
    }
  }

  // ---- 保存 ----

  async function saveConfig() {
    const button = $('server-save');
    const dirtyFiles = state.files.filter((file) => {
      const draft = state.drafts.get(file.id);
      return draft && draft.dirty;
    });
    if (!dirtyFiles.length) {
      message('没有需要保存的修改。', 'ok');
      return;
    }
    if (button) button.disabled = true;
    message('保存中…', '');
    let saved = 0;
    let restart = false;
    let live = false;
    let lastFiles = null;
    try {
      for (const file of dirtyFiles) {
        const draft = state.drafts.get(file.id);
        const data = await UI.api('/api/server/config', {
          method: 'POST',
          body: JSON.stringify({ id: file.id, content: buildContent(draft) }),
        });
        saved++;
        restart = restart || Boolean(data.restartRequired);
        live = live || Boolean(data.applied);
        lastFiles = data.configFiles || lastFiles;
      }
      if (lastFiles) state.files = lastFiles;
      state.drafts.clear();
      renderConfig();
      message('已保存 ' + saved + ' 个文件'
          + (restart ? '；部分需要重启服务器生效' : (live ? '；已立即生效' : '')),
          restart ? 'warning' : 'ok');
    } catch (e) {
      message(UI.authMessage(e, '保存失败'), 'error');
    } finally {
      if (button) button.disabled = false;
      updateDirtyIndicator();
    }
  }

  // ---- 备份时间线 ----

  function renderBackups(data) {
    const stats = $('server-backup-stats');
    const count = $('server-backup-count');
    const timeline = data.timeline || { nodes: [] };
    if (count) count.textContent = String((timeline.nodes || []).length);
    if (stats) {
      stats.textContent = '';
      const items = [
        ['备份数', String((timeline.nodes || []).length)],
        ['时间线头节点', timeline.headId || '—'],
        ['当前世界', '实时运行（不在此列表）'],
        ['world 大小', formatBytes(data.worldBytes)],
        ['可用空间', formatBytes(data.freeBytes)],
        ['保留', (data.keep === 0 ? '不自动清理' : '最新 ' + data.keep + ' 份 + 祖先')],
      ];
      for (const [label, value] of items) {
        const stat = document.createElement('div');
        stat.className = 'stat';
        const name = document.createElement('span');
        name.textContent = label;
        const strong = document.createElement('strong');
        strong.textContent = value;
        stat.append(name, strong);
        stats.appendChild(stat);
      }
    }
    renderPending(data);
    renderTimeline(timeline);
  }

  function renderPending(data) {
    const box = $('server-backup-pending');
    if (!box) return;
    const pending = data.pendingRestore || data.pendingBackup;
    if (!pending) {
      box.hidden = true;
      box.textContent = '';
      return;
    }
    box.hidden = false;
    box.textContent = '';
    const strong = document.createElement('strong');
    strong.textContent = pending.mode === 'restore' ? '已排队恢复：' : '已排队创建备份：';
    const text = document.createElement('span');
    text.textContent = (pending.id || '') + ' · 请停服并重新启动，任务会在世界打开前执行。';
    box.append(strong, text);
  }

  function renderTimeline(timeline) {
    const list = $('server-backup-timeline');
    if (!list) return;
    list.textContent = '';
    const nodes = timeline.nodes || [];
    if (!nodes.length) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '还没有本体备份。点右上角“创建备份”，停服重启后生成时间线起点。';
      list.appendChild(empty);
      return;
    }
    let previousBranch = '';
    for (const node of nodes) {
      const item = document.createElement('div');
      item.className = 'timeline-node' + (node.current ? ' current' : '');
      item.style.setProperty('--depth', String(node.depth || 0));
      if (node.depth > 0) item.classList.add('branch');
      if (node.branchId && node.branchId !== previousBranch) item.classList.add('branch-start');
      previousBranch = node.branchId || previousBranch;

      const rail = document.createElement('span');
      rail.className = 'timeline-rail';
      rail.setAttribute('aria-hidden', 'true');

      const card = document.createElement('div');
      card.className = 'timeline-card';
      const head = document.createElement('div');
      head.className = 'timeline-card-head';
      const title = document.createElement('div');
      title.className = 'timeline-card-title';
      const strong = document.createElement('strong');
      strong.textContent = formatTime(node.createdAt);
      const idText = document.createElement('span');
      idText.className = 'muted small mono';
      idText.textContent = node.id;
      title.append(strong, idText);
      const badges = document.createElement('div');
      badges.className = 'timeline-badges';
      if (node.current) {
        const badge = document.createElement('span');
        badge.className = 'world-stack-badge ok';
        badge.textContent = '时间线头节点';
        badges.appendChild(badge);
      }
      if (node.restoredAt) {
        const badge = document.createElement('span');
        badge.className = 'world-stack-badge done';
        badge.textContent = '恢复过 ' + formatTime(node.restoredAt);
        badges.appendChild(badge);
      }
      if (node.branchId && node.branchId !== 'main') {
        const badge = document.createElement('span');
        badge.className = 'world-stack-badge';
        badge.textContent = '分支 ' + node.branchId.replace(/^br-/, '');
        badges.appendChild(badge);
      }
      head.append(title, badges);

      const meta = document.createElement('p');
      meta.className = 'muted small';
      meta.textContent = formatBytes(node.zipBytes)
          + ' · world=' + (node.worldName || '—')
          + ' · ' + (node.fileCount || 0) + ' 个文件'
          + (node.parentId ? ' · 父节点 ' + node.parentId : ' · 时间线起点')
          + (node.message ? ' · ' + node.message : '');
      card.append(head, meta);
      if (node.error) {
        const error = document.createElement('p');
        error.className = 'message error';
        error.textContent = '警告：' + node.error;
        card.appendChild(error);
      }

      const actions = document.createElement('div');
      actions.className = 'timeline-actions';
      const rollback = KIT.button('回滚到这里', 'primary', () => previewRestore(node.id));
      actions.appendChild(rollback);
      const download = KIT.button('下载', 'ghost', () => downloadBackup(node));
      actions.appendChild(download);
      const del = KIT.button('删除', 'ghost danger', () => deleteBackup(node.id));
      del.disabled = node.deletable === false;
      if (node.deletable === false) {
        del.title = node.current ? '当前时间线节点不能删除' : '还有后续分支，先删除子节点';
      }
      actions.appendChild(del);
      card.appendChild(actions);

      item.append(rail, card);
      list.appendChild(item);
    }
  }

  async function createBackup() {
    backupMessage('读取创建预览…', '');
    try {
      const preview = await UI.api('/api/server/backups/preview', {
        method: 'POST',
        body: JSON.stringify({}),
      });
      renderCreatePreview(preview);
      backupMessage('', '');
      UI.scrollToTarget?.('server-backup-preview');
    } catch (e) {
      if (e.status === 400 && e.body && typeof e.body === 'object'
          && Object.prototype.hasOwnProperty.call(e.body, 'worldBytes')) {
        renderCreatePreview(e.body);
        UI.scrollToTarget?.('server-backup-preview');
        return;
      }
      backupMessage(UI.authMessage(e, '创建备份预览失败'), 'error');
    }
  }

  function previewBox(titleText) {
    const box = $('server-backup-preview');
    if (!box) return null;
    box.hidden = false;
    box.textContent = '';
    const title = document.createElement('div');
    title.className = 'world-stack-preview-title';
    title.textContent = titleText;
    box.append(title);
    return box;
  }

  function appendRows(parent, rows) {
    const dl = document.createElement('dl');
    dl.className = 'world-stack-rows';
    for (const [key, value] of rows) {
      const dt = document.createElement('dt');
      dt.textContent = key;
      const dd = document.createElement('dd');
      dd.textContent = value;
      dl.append(dt, dd);
    }
    parent.append(dl);
  }

  function appendList(parent, className, lines) {
    const list = document.createElement('ul');
    list.className = className;
    for (const line of lines) {
      const item = document.createElement('li');
      item.textContent = line;
      list.append(item);
    }
    parent.append(list);
  }

  function renderCreatePreview(preview) {
    const ok = Boolean(preview && preview.ok);
    const box = previewBox(ok ? '创建备份预览：可以执行' : '创建备份预览：需要修正');
    if (!box) return;
    appendRows(box, [
      ['world', preview.worldName || '—'],
      ['world 大小', formatBytes(preview.worldBytes)],
      ['可用空间', formatBytes(preview.freeBytes)],
      ['需要空间', formatBytes(preview.neededBytes)],
      ['保留', preview.keep === 0 ? '不自动清理' : '最新 ' + preview.keep + ' 份 + 祖先'],
      ['执行时机', '停服后、下次启动前；源 world 不会被删除'],
    ]);
    if (Array.isArray(preview.steps) && preview.steps.length) {
      const steps = document.createElement('div');
      steps.className = 'world-stack-steps';
      const heading = document.createElement('strong');
      heading.textContent = '将执行';
      const ol = document.createElement('ol');
      for (const step of preview.steps) {
        const li = document.createElement('li');
        li.textContent = step;
        ol.appendChild(li);
      }
      steps.append(heading, ol);
      box.appendChild(steps);
    }
    if (!ok) {
      const error = document.createElement('div');
      error.className = 'world-stack-error';
      appendList(error, 'world-stack-error-list', [preview.error || '预览未通过']);
      box.appendChild(error);
      return;
    }
    const confirm = document.createElement('label');
    confirm.className = 'check-line world-stack-confirm';
    const check = document.createElement('input');
    check.type = 'checkbox';
    check.addEventListener('change', () => { run.disabled = !check.checked; });
    const text = document.createElement('span');
    text.textContent = '我确认：停服并重新启动后打包 world、服务器配置与 config/samsara；源 world 保持不变';
    confirm.append(check, text);

    const noteField = document.createElement('label');
    noteField.className = 'world-stack-note-field';
    const noteLabel = document.createElement('span');
    noteLabel.textContent = '备份说明（可选，显示在时间线上，方便以后辨认）';
    const noteInput = document.createElement('input');
    noteInput.type = 'text';
    noteInput.maxLength = 120;
    noteInput.placeholder = '例如：更新模组前、开新地图前、修复前';
    noteField.append(noteLabel, noteInput);

    const run = KIT.button('确认创建（需重启）', 'primary', () => confirmCreate(noteInput.value));
    run.disabled = true;
    box.append(confirm, noteField, run);
  }

  async function confirmCreate(note) {
    backupMessage('正在排队…', '');
    const text = String(note || '').trim();
    try {
      const data = await UI.api('/api/server/backups', {
        method: 'POST',
        body: JSON.stringify({ confirm: true, note: text }),
      });
      backupMessage(data.message || '已排队，请停服并重新启动。', 'ok');
      const box = $('server-backup-preview');
      if (box) box.hidden = true;
      await loadBackupsOnly();
    } catch (e) {
      backupMessage(UI.authMessage(e, '创建备份失败'), 'error');
    }
  }

  async function previewRestore(id) {
    backupMessage('读取恢复预览…', '');
    try {
      const data = await UI.api('/api/server/backups/preview-restore', {
        method: 'POST',
        body: JSON.stringify({ id }),
      });
      renderRestorePreview(id, data.preview || {});
      backupMessage('', '');
      UI.scrollToTarget?.('server-backup-preview');
    } catch (e) {
      if (e.status === 400 && e.body && e.body.preview) {
        renderRestorePreview(id, e.body.preview);
        UI.scrollToTarget?.('server-backup-preview');
        return;
      }
      backupMessage(UI.authMessage(e, '恢复预览失败'), 'error');
    }
  }

  function renderRestorePreview(id, preview) {
    const ok = Boolean(preview && preview.ok);
    const box = previewBox((ok ? '恢复预览：' : '恢复预览：需要修正 ') + id);
    if (!box) return;
    appendRows(box, [
      ['备份大小', formatBytes(preview.backupBytes)],
      ['备份 world', preview.worldName || '—'],
      ['当前 world', formatBytes(preview.worldBytes)],
      ['可用空间', formatBytes(preview.freeBytes)],
      ['执行时机', '停服后、下次启动前'],
    ]);
    if (Array.isArray(preview.warnings) && preview.warnings.length) {
      const warn = document.createElement('div');
      warn.className = 'world-stack-warning';
      appendList(warn, 'world-stack-warning-list', preview.warnings);
      box.appendChild(warn);
    }
    if (Array.isArray(preview.steps) && preview.steps.length) {
      const steps = document.createElement('div');
      steps.className = 'world-stack-steps';
      const heading = document.createElement('strong');
      heading.textContent = '将执行';
      const ol = document.createElement('ol');
      for (const step of preview.steps) {
        const li = document.createElement('li');
        li.textContent = step;
        ol.appendChild(li);
      }
      steps.append(heading, ol);
      box.appendChild(steps);
    }
    if (!ok || (Array.isArray(preview.errors) && preview.errors.length)) {
      const error = document.createElement('div');
      error.className = 'world-stack-error';
      appendList(error, 'world-stack-error-list',
          preview.errors && preview.errors.length ? preview.errors : ['恢复预览未通过']);
      box.appendChild(error);
      return;
    }
    const confirm = document.createElement('label');
    confirm.className = 'check-line world-stack-confirm';
    const check = document.createElement('input');
    check.type = 'checkbox';
    check.id = 'server-restore-confirm';
    check.addEventListener('change', () => { run.disabled = !check.checked; });
    const span = document.createElement('span');
    span.textContent = '我确认：恢复会替换当前 world；恢复前的世界保留在 pre-restore-<job>/world';
    confirm.append(check, span);
    const run = KIT.button('确认排队恢复（需重启）', 'danger', () => confirmRestore(id, check));
    run.disabled = true;
    box.append(confirm, run);
  }

  async function confirmRestore(id, check) {
    if (!check || !check.checked) {
      backupMessage('请先勾选确认项', 'warning');
      return;
    }
    try {
      const data = await UI.api('/api/server/backups/restore', {
        method: 'POST',
        body: JSON.stringify({ id, confirm: true }),
      });
      backupMessage(data.message || '已排队恢复，请停服并重新启动。', 'ok');
      const box = $('server-backup-preview');
      if (box) box.hidden = true;
      await loadBackupsOnly();
    } catch (e) {
      backupMessage(UI.authMessage(e, '排队恢复失败'), 'error');
    }
  }

  async function deleteBackup(id) {
    if (!window.confirm('删除备份 ' + id + '？源 world 不会受影响。')) return;
    try {
      const data = await UI.api('/api/server/backups/delete', {
        method: 'POST',
        body: JSON.stringify({ id, confirm: true }),
      });
      backupMessage(data.message || '已删除', 'ok');
      await loadBackupsOnly();
    } catch (e) {
      backupMessage(UI.authMessage(e, '删除失败'), 'error');
    }
  }

  async function downloadBackup(backup) {
    if (Number(backup.zipBytes) > 256 * 1024 * 1024) {
      const ok = window.confirm('备份超过 256MB，浏览器下载会占用大量内存。仍要下载？');
      if (!ok) return;
    }
    try {
      const response = await fetch('/api/server/backups/download?id=' + encodeURIComponent(backup.id), {
        headers: UI.authHeaders(),
      });
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = backup.id + '.zip';
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      backupMessage(UI.authMessage(e, '下载失败'), 'error');
    }
  }

  function renderJobs(jobs) {
    const list = $('server-job-list');
    if (!list) return;
    list.textContent = '';
    if (!jobs.length) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '还没有任务记录。';
      list.appendChild(empty);
      return;
    }
    for (const job of jobs) {
      const card = document.createElement('div');
      card.className = 'server-job ' + (job.status || '');
      const head = document.createElement('div');
      head.className = 'server-job-head';
      const title = document.createElement('strong');
      title.textContent = (job.type || 'task') + ' · ' + (job.id || '');
      const status = document.createElement('span');
      status.className = 'world-stack-badge ' + (job.status || '');
      status.textContent = (job.status || '') + ' · ' + (job.stage || '');
      head.append(title, status);
      card.appendChild(head);
      if (job.message) {
        const p = document.createElement('p');
        p.className = 'muted small';
        p.textContent = job.message;
        card.appendChild(p);
      }
      if (job.log && job.log.length) {
        const log = document.createElement('div');
        log.className = 'server-job-log';
        log.textContent = job.log.join('\n');
        card.appendChild(log);
      }
      list.appendChild(card);
    }
  }

  // ---- events ----

  for (const tab of document.querySelectorAll('.top-tab')) {
    tab.addEventListener('click', () => setSection(tab.dataset.section));
  }
  for (const tab of document.querySelectorAll('.tab[data-server-panel]')) {
    tab.addEventListener('click', () => setServerPanel(tab.dataset.serverPanel));
  }
  const search = $('server-config-search');
  if (search) {
    search.addEventListener('input', () => {
      state.search = search.value;
      renderConfig();
    });
  }
  const save = $('server-save');
  if (save) save.addEventListener('click', saveConfig);
  const reload = $('server-reload');
  if (reload) {
    reload.addEventListener('click', async () => {
      if (state.serverPanel === 'server-backup-panel') {
        await loadBackupsOnly();
        backupMessage('已重新读取', 'ok');
      } else {
        const data = await loadAll();
        if (data) message('已重新读取', 'ok');
      }
    });
  }
  const backupCreate = $('server-backup-create');
  if (backupCreate) backupCreate.addEventListener('click', createBackup);

  const consoleSend = $('server-console-send');
  const consoleInput = $('server-console-command');
  const consoleClear = $('server-console-clear');
  if (consoleSend) {
    consoleSend.addEventListener('click', () => runConsole(consoleInput ? consoleInput.value : ''));
  }
  if (consoleInput) {
    consoleInput.addEventListener('input', () => closeConsoleComplete());
    consoleInput.addEventListener('keydown', (event) => {
      const popup = $('server-console-complete');
      const popupOpen = popup && !popup.hidden && consoleComplete.items.length > 0;
      if (event.key === 'Tab') {
        event.preventDefault();
        if (popupOpen) {
          applyConsoleComplete(consoleComplete.items[consoleComplete.selected]);
        } else {
          requestConsoleComplete();
        }
      } else if (event.key === 'Enter') {
        event.preventDefault();
        closeConsoleComplete();
        runConsole(consoleInput.value);
      } else if (event.key === 'Escape') {
        if (popupOpen) {
          event.preventDefault();
          closeConsoleComplete();
        }
      } else if (event.key === 'ArrowUp') {
        event.preventDefault();
        if (popupOpen) {
          selectConsoleComplete(consoleComplete.selected - 1);
        } else if (consoleHistory.length) {
          consoleHistoryIndex = Math.min(consoleHistory.length - 1, consoleHistoryIndex + 1);
          consoleInput.value = consoleHistory[consoleHistoryIndex] || '';
        }
      } else if (event.key === 'ArrowDown') {
        event.preventDefault();
        if (popupOpen) {
          selectConsoleComplete(consoleComplete.selected + 1);
        } else {
          consoleHistoryIndex = Math.max(-1, consoleHistoryIndex - 1);
          consoleInput.value = consoleHistoryIndex < 0 ? '' : (consoleHistory[consoleHistoryIndex] || '');
        }
      }
    });
  }
  if (consoleClear) {
    consoleClear.addEventListener('click', () => {
      const output = $('server-console-output');
      if (output) output.textContent = '';
      consoleMessage('');
    });
  }
  const auditRefresh = $('server-audit-refresh');
  if (auditRefresh) auditRefresh.addEventListener('click', loadAudit);
  const auditDiff = $('server-audit-diff-wrap');
  const auditDiffClose = $('server-audit-diff-close');
  if (auditDiffClose && auditDiff) {
    auditDiffClose.addEventListener('click', () => {
      if (typeof auditDiff.close === 'function') auditDiff.close();
      auditDiff.hidden = true;
    });
    auditDiff.addEventListener('close', () => {
      auditDiff.hidden = true;
    });
  }
  for (const button of document.querySelectorAll('[data-console-command]')) {
    button.addEventListener('click', () => {
      runConsole(button.dataset.consoleCommand || '');
    });
  }
  const consoleFilter = $('server-console-filter');
  if (consoleFilter) {
    consoleFilter.addEventListener('input', () => {
      const query = consoleFilter.value.trim().toLowerCase();
      for (const button of document.querySelectorAll('#server-console-quick [data-console-command]')) {
        button.hidden = Boolean(query) && !(button.textContent + ' ' + (button.dataset.consoleCommand || ''))
          .toLowerCase().includes(query);
      }
      for (const group of document.querySelectorAll('#server-console-quick .console-quick-group')) {
        group.hidden = ![...group.querySelectorAll('button')].some((button) => !button.hidden);
      }
    });
  }
  const consoleCopy = $('server-console-copy');
  if (consoleCopy) {
    consoleCopy.addEventListener('click', async () => {
      const output = $('server-console-output');
      const text = output ? output.textContent || '' : '';
      try {
        await navigator.clipboard.writeText(text);
        consoleMessage('已复制输出', 'ok');
      } catch (e) {
        consoleMessage('复制失败：' + (e.message || '浏览器不允许'), 'error');
      }
    });
  }

  const playerDialog = $('player-dialog');
  const playerCancel = $('player-cancel');
  if (playerCancel && playerDialog) {
    playerCancel.addEventListener('click', () => playerDialog.close());
  }
  const playerSearch = $('player-search');
  if (playerSearch) playerSearch.addEventListener('input', renderPlayerList);

  const configFiles = $('server-config-files');
  if (configFiles) {
    configFiles.addEventListener('toggle', (event) => {
      const target = event.target;
      if (target && target.id) state.openStates.set(target.id, target.open);
    }, true);
  }

  UI.onConnect(() => {
    if (state.section !== 'server') return null;
    if (state.serverPanel === 'server-backup-panel') {
      backupMessage('', '');
      return loadBackupsOnly();
    }
    return loadAll();
  });

  UI.onConnected(() => {
    if (state.section === 'server') renderServerNav();
  });

  const initialHash = window.SamsaraUseRouter ? '' : (location.hash || '');
  if (initialHash === '#server' || initialHash === '#server-backup'
      || initialHash === '#server-console' || initialHash === '#server-audit') {
    setSection('server');
    if (initialHash === '#server-backup') setServerPanel('server-backup-panel');
    if (initialHash === '#server-console') setServerPanel('server-console-panel');
    if (initialHash === '#server-audit') setServerPanel('server-audit-panel');
  }

  window.SamsaraServer = {
    activate: () => setSection('server'),
    showPanel: (id) => {
      state.serverPanel = id;
      setSection('server');
      setServerPanel(id);
    },
    leaveServer: () => {
      state.section = 'mods';
      document.body.classList.remove('server-section');
      for (const tab of document.querySelectorAll('.top-tab')) {
        tab.classList.toggle('active', tab.dataset.section === 'mods');
      }
      const panel = $('panel-server');
      if (panel) panel.hidden = true;
      const serverTabs = $('server-tabs');
      if (serverTabs) serverTabs.hidden = true;
      const serverActions = $('server-actions');
      if (serverActions) serverActions.hidden = true;
    },
    reload: () => loadAll(),
    state,
  };
})();


