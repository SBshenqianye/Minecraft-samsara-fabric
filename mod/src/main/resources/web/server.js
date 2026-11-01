'use strict';

// 「本体」页：服务器配置 + 存档备份。
// 只通过 window.SamsaraUI.api 调后端；所有文本用 textContent 写入。

(function () {
  const UI = window.SamsaraUI;
  if (!UI) return;
  const $ = (id) => document.getElementById(id);

  const state = {
    section: 'mods',
    serverPanel: 'server-config-panel',
    files: [],
    activeId: 'server-properties',
    dirty: false,
    model: null,
    restorePreview: null,
  };

  function message(text, kind) {
    const el = $('server-config-message') || $('server-backup-message');
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

  // ---- 一级分栏 ----

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
      hint: '保存会先备份到 samsara/server-config-backups；白名单/OP/封禁立即生效，properties/eula 需重启',
    },
    'server-backup-panel': {
      title: '存档备份',
      hint: '创建/恢复都在停服后、下次启动前执行；恢复前会保留当前 world',
    },
  };

  const SERVER_HASH = {
    'server-config-panel': '#server',
    'server-backup-panel': '#server-backup',
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
    if (config) config.hidden = id !== 'server-config-panel';
    if (backups) backups.hidden = id !== 'server-backup-panel';
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
      try { history.replaceState(null, '', SERVER_HASH[id] || '#server'); } catch (e) { /* file:// 等场景忽略 */ }
      renderServerNav();
    }
    if (id === 'server-backup-panel') loadBackupsOnly();
  }

  // 与其它页面一致：本体也使用左侧全局导航，而不是面板内再放一套主导航。
  function renderServerNav() {
    if (state.section !== 'server') return;
    if (state.serverPanel === 'server-backup-panel') {
      const backups = (state.model && state.model.backups) || [];
      const items = [
        { label: '备份列表', target: 'server-backup-list' },
        { label: '任务与回滚', target: 'server-job-list' },
      ];
      const targets = items
          .map((item) => ({ id: item.target, el: document.getElementById(item.target) }))
          .filter((item) => item.el);
      UI.buildNav('存档备份', items, targets, { count: backups.length });
      return;
    }
    const items = state.files.map((file) => ({
      label: file.name,
      target: 'server-file-' + file.id,
      onClick: () => selectFile(file.id),
    }));
    UI.buildNav('服务器配置', items, [], { count: state.files.length });
    const file = activeFile();
    if (file) UI.setNavActive('server-file-' + file.id);
  }

  // ---- 数据 ----

  async function loadAll() {
    try {
      const data = await UI.api('/api/server');
      state.model = data;
      state.files = data.configFiles || [];
      message('', '');
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

  // ---- 服务器配置 ----

  function renderConfig() {
    const list = $('server-config-list');
    const count = $('server-config-count');
    if (count) count.textContent = String(state.files.length);
    if (!list) return;
    list.textContent = '';
    for (const file of state.files) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'server-config-file' + (file.id === state.activeId ? ' active' : '');
      const strong = document.createElement('strong');
      strong.textContent = file.name;
      const small = document.createElement('small');
      small.textContent = (file.exists ? formatBytes(file.bytes) : '不存在')
          + (file.restartRequired ? ' · 需重启' : ' · 即时');
      button.append(strong, small);
      button.addEventListener('click', () => selectFile(file.id));
      list.appendChild(button);
    }
    renderActiveFile();
  }

  function activeFile() {
    return state.files.find((file) => file.id === state.activeId) || state.files[0] || null;
  }

  function renderActiveFile() {
    const file = activeFile();
    if (!file) return;
    state.activeId = file.id;
    const title = $('server-config-title');
    const path = $('server-config-path');
    const warning = $('server-config-warning');
    const editor = $('server-config-editor');
    if (title) title.textContent = file.name;
    if (path) {
      const root = state.model && state.model.serverRoot;
      if (file.path) {
        path.textContent = file.path;
      } else if (root) {
        const sep = root.includes('\\') ? '\\' : '/';
        path.textContent = root.replace(/[\\/]+$/, '') + sep + file.name;
      } else {
        path.textContent = file.name;
      }
    }
    if (warning) {
      warning.textContent = file.error
          ? '读取警告：' + file.error
          : (file.restartRequired
              ? '保存后需重启服务器生效；写入前会自动备份到 samsara/server-config-backups。'
              : '保存后立即重新载入；写入前会自动备份到 samsara/server-config-backups。');
    }
    if (editor) {
      editor.value = file.content || '';
      editor.dataset.file = file.id;
      state.dirty = false;
    }
    for (const button of document.querySelectorAll('.server-config-file')) {
      const strong = button.querySelector('strong');
      button.classList.toggle('active', !!strong && strong.textContent === file.name);
    }
  }

  function selectFile(id) {
    if (state.dirty && !window.confirm('当前文件有未保存修改，切换会丢失。继续？')) {
      renderServerNav();
      return;
    }
    state.activeId = id;
    renderConfig();
    renderServerNav();
  }

  async function saveConfig() {
    const file = activeFile();
    const editor = $('server-config-editor');
    if (!file || !editor) return;
    const button = $('server-save');
    if (button) button.disabled = true;
    message('保存中…', '');
    try {
      const data = await UI.api('/api/server/config', {
        method: 'POST',
        body: JSON.stringify({ id: file.id, content: editor.value }),
      });
      state.files = data.configFiles || state.files;
      state.dirty = false;
      renderConfig();
      message(data.restartRequired
          ? (data.message || '已保存，需要重启服务器生效')
          : (data.message || '已保存并立即生效'), data.applied || !data.restartRequired ? 'ok' : 'warning');
    } catch (e) {
      message(UI.authMessage(e, '保存失败'), 'error');
    } finally {
      if (button) button.disabled = false;
    }
  }

  // ---- 备份 ----

  function renderBackups(data) {
    const stats = $('server-backup-stats');
    const count = $('server-backup-count');
    if (count) count.textContent = String((data.backups || []).length);
    if (stats) {
      stats.textContent = '';
      const items = [
        ['备份数', String((data.backups || []).length)],
        ['world', data.worldName || '—'],
        ['world 大小', formatBytes(data.worldBytes)],
        ['可用空间', formatBytes(data.freeBytes)],
        ['保留', (data.keep === 0 ? '不自动清理' : '最新 ' + data.keep + ' 份')],
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
    renderBackupList(data.backups || []);
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

  function renderBackupList(backups) {
    const list = $('server-backup-list');
    if (!list) return;
    list.textContent = '';
    if (!backups.length) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '还没有本体备份。点右上角“创建备份”，停服重启后生成。';
      list.appendChild(empty);
      return;
    }
    for (const backup of backups) {
      const card = document.createElement('div');
      card.className = 'server-backup-card';
      const head = document.createElement('div');
      head.className = 'server-backup-card-head';
      const main = document.createElement('div');
      main.className = 'server-backup-card-main';
      const title = document.createElement('strong');
      title.textContent = backup.id;
      const meta = document.createElement('small');
      meta.textContent = formatTime(backup.createdAt)
          + ' · ' + formatBytes(backup.zipBytes)
          + ' · world=' + (backup.worldName || '—')
          + ' · ' + (backup.fileCount || 0) + ' 个文件'
          + (backup.sha256 ? ' · sha256 ' + backup.sha256.slice(0, 12) + '…' : '');
      main.append(title, meta);
      if (backup.pendingRestore) {
        const pending = document.createElement('span');
        pending.className = 'world-stack-badge running';
        pending.textContent = '已排队恢复';
        main.appendChild(pending);
      }
      if (backup.error) {
        const error = document.createElement('small');
        error.textContent = '警告：' + backup.error;
        main.appendChild(error);
      }
      const actions = document.createElement('div');
      actions.className = 'server-backup-card-actions';

      const preview = document.createElement('button');
      preview.type = 'button';
      preview.className = 'ghost';
      preview.textContent = '预览恢复';
      preview.addEventListener('click', () => previewRestore(backup.id));
      actions.appendChild(preview);

      const download = document.createElement('button');
      download.type = 'button';
      download.className = 'ghost';
      download.textContent = '下载';
      download.addEventListener('click', () => downloadBackup(backup));
      actions.appendChild(download);

      const del = document.createElement('button');
      del.type = 'button';
      del.className = 'ghost danger';
      del.textContent = '删除';
      del.addEventListener('click', () => deleteBackup(backup.id));
      actions.appendChild(del);

      head.append(main, actions);
      card.appendChild(head);
      list.appendChild(card);
    }
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

  // 与世界栈的导入/归档一致：先预览，勾选确认后再排队。
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

  function renderCreatePreview(preview) {
    const ok = Boolean(preview && preview.ok);
    const box = previewBox(ok ? '创建备份预览：可以执行' : '创建备份预览：需要修正');
    if (!box) return;
    appendRows(box, [
      ['world', preview.worldName || '—'],
      ['world 大小', formatBytes(preview.worldBytes)],
      ['可用空间', formatBytes(preview.freeBytes)],
      ['需要空间', formatBytes(preview.neededBytes)],
      ['保留', preview.keep === 0 ? '不自动清理' : '最新 ' + preview.keep + ' 份'],
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
    check.addEventListener('change', () => {
      run.disabled = !check.checked;
    });
    const text = document.createElement('span');
    text.textContent = '我确认：停服并重新启动后打包 world、服务器配置与 config/samsara；源 world 保持不变';
    confirm.append(check, text);
    const run = document.createElement('button');
    run.type = 'button';
    run.className = 'primary';
    run.textContent = '确认创建（需重启）';
    run.disabled = true;
    run.addEventListener('click', () => confirmCreate());
    box.append(confirm, run);
  }

  async function confirmCreate() {
    backupMessage('正在排队…', '');
    try {
      const data = await UI.api('/api/server/backups', {
        method: 'POST',
        body: JSON.stringify({ confirm: true }),
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
    check.addEventListener('change', () => {
      run.disabled = !check.checked;
    });
    const span = document.createElement('span');
    span.textContent = '我确认：恢复会替换当前 world；恢复前的世界保留在 pre-restore-<job>/world';
    confirm.append(check, span);
    const run = document.createElement('button');
    run.type = 'button';
    run.className = 'danger';
    run.textContent = '确认排队恢复（需重启）';
    run.disabled = true;
    run.addEventListener('click', () => confirmRestore(id, check));
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
      if (!response.ok) {
        throw new Error('HTTP ' + response.status);
      }
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
  const editor = $('server-config-editor');
  if (editor) editor.addEventListener('input', () => { state.dirty = true; });
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

  UI.onConnect(() => {
    if (state.section === 'server') loadAll();
  });

  const initialHash = location.hash || '';
  if (initialHash === '#server' || initialHash === '#server-backup') {
    setSection('server');
    if (initialHash === '#server-backup') setServerPanel('server-backup-panel');
  }

  window.SamsaraServer = {
    activate: () => setSection('server'),
    reload: () => loadAll(),
    state,
  };
})();
