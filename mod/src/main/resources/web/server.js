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
    if (state.section === 'server') {
      setServerPanel(state.serverPanel);
      loadAll();
    } else {
      UI.setActiveTab(UI.state.tab || 'config');
    }
  }

  function setServerPanel(id) {
    state.serverPanel = id;
    for (const tab of document.querySelectorAll('.sub-tab')) {
      tab.classList.toggle('active', tab.dataset.serverPanel === id);
    }
    const config = $('server-config-panel');
    const backups = $('server-backup-panel');
    if (config) config.hidden = id !== 'server-config-panel';
    if (backups) backups.hidden = id !== 'server-backup-panel';
    if (id === 'server-backup-panel') loadBackupsOnly();
  }

  // ---- 数据 ----

  async function loadAll() {
    try {
      const data = await UI.api('/api/server');
      state.model = data;
      state.files = data.configFiles || [];
      renderConfig();
      renderBackups(data);
      renderJobs(data.jobs || []);
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
    } catch (e) {
      backupMessage(UI.authMessage(e, '读取备份列表失败'), 'error');
    }
  }

  // ---- 服务器配置 ----

  function renderConfig() {
    const list = $('server-config-list');
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
    if (path) path.textContent = file.path || file.name;
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
      return;
    }
    state.activeId = id;
    renderConfig();
  }

  async function saveConfig() {
    const file = activeFile();
    const editor = $('server-config-editor');
    if (!file || !editor) return;
    const button = $('server-config-save');
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
        const chip = document.createElement('span');
        chip.className = 'stat-chip';
        const strong = document.createElement('strong');
        strong.textContent = value;
        chip.append(label + ' ', strong);
        stats.appendChild(chip);
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

      card.append(main, actions);
      list.appendChild(card);
    }
  }

  async function createBackup() {
    if (!window.confirm('创建备份会排队，停服并重新启动后执行。继续？')) return;
    backupMessage('正在排队…', '');
    try {
      const data = await UI.api('/api/server/backups', {
        method: 'POST',
        body: JSON.stringify({ confirm: true }),
      });
      backupMessage(data.message || '已排队，请停服并重新启动。', 'ok');
      await loadBackupsOnly();
    } catch (e) {
      backupMessage(UI.authMessage(e, '创建备份失败'), 'error');
    }
  }

  async function previewRestore(id) {
    const box = $('server-backup-preview');
    if (!box) return;
    box.hidden = false;
    box.textContent = '';
    const title = document.createElement('strong');
    title.textContent = '恢复预览：' + id;
    box.appendChild(title);
    try {
      const data = await UI.api('/api/server/backups/preview-restore', {
        method: 'POST',
        body: JSON.stringify({ id }),
      });
      const preview = data.preview || {};
      const meta = document.createElement('p');
      meta.className = 'muted small';
      meta.textContent = '备份大小 ' + formatBytes(preview.backupBytes)
          + ' · 当前 world ' + formatBytes(preview.worldBytes)
          + ' · 可用 ' + formatBytes(preview.freeBytes)
          + ' · 备份 world=' + (preview.worldName || '—');
      box.appendChild(meta);
      if ((preview.errors || []).length) {
        const errors = document.createElement('ul');
        errors.className = 'server-backup-error-list';
        for (const error of preview.errors) {
          const li = document.createElement('li');
          li.textContent = error;
          errors.appendChild(li);
        }
        box.appendChild(errors);
        return;
      }
      const steps = document.createElement('ul');
      for (const step of preview.steps || []) {
        const li = document.createElement('li');
        li.textContent = step;
        steps.appendChild(li);
      }
      box.appendChild(steps);
      if ((preview.warnings || []).length) {
        const warn = document.createElement('p');
        warn.className = 'muted small';
        warn.textContent = preview.warnings.join('；');
        box.appendChild(warn);
      }
      const label = document.createElement('label');
      label.className = 'check-line';
      const check = document.createElement('input');
      check.type = 'checkbox';
      check.id = 'server-restore-confirm';
      const span = document.createElement('span');
      span.textContent = '我已了解会替换当前 world；停服后重启执行';
      label.append(check, span);
      box.appendChild(label);
      const confirmButton = document.createElement('button');
      confirmButton.type = 'button';
      confirmButton.className = 'danger';
      confirmButton.textContent = '确认排队恢复';
      confirmButton.addEventListener('click', () => confirmRestore(id, check));
      box.appendChild(confirmButton);
    } catch (e) {
      const error = document.createElement('p');
      error.className = 'message error';
      error.textContent = UI.authMessage(e, '恢复预览失败');
      box.appendChild(error);
    }
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
      status.className = 'muted small';
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
  for (const tab of document.querySelectorAll('.sub-tab')) {
    tab.addEventListener('click', () => setServerPanel(tab.dataset.serverPanel));
  }
  const editor = $('server-config-editor');
  if (editor) editor.addEventListener('input', () => { state.dirty = true; });
  const save = $('server-config-save');
  if (save) save.addEventListener('click', saveConfig);
  const configRefresh = $('server-config-refresh');
  if (configRefresh) configRefresh.addEventListener('click', loadAll);
  const backupRefresh = $('server-backup-refresh');
  if (backupRefresh) backupRefresh.addEventListener('click', loadBackupsOnly);
  const backupCreate = $('server-backup-create');
  if (backupCreate) backupCreate.addEventListener('click', createBackup);

  UI.onConnect(() => {
    if (state.section === 'server') loadAll();
  });

  if ((location.hash || '') === '#server') {
    setSection('server');
  }

  window.SamsaraServer = {
    activate: () => setSection('server'),
    reload: () => loadAll(),
    state,
  };
})();
