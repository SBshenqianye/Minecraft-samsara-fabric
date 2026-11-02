'use strict';

// 世界栈：本地 world 导入、栈位排序、归档，以及重启时任务的进度/结果。
(function () {
  const state = {
    model: null,
    loading: false,
    order: [],
    savedOrder: [],
    preview: null,
    archive: null,
    importConfirmed: false,
    importError: '',
    importBusy: false,
    archiveConfirmed: false,
    archiveError: '',
    archiveBusy: false,
    timeline: null,
    timelinePreview: null,
    timelineConfirmed: false,
    timelineError: '',
    snapshots: null,
  };

  const $ = (id) => document.getElementById(id);
  let els = null;

  function ensureEls() {
    if (els) return els;
    els = {
      message: $('world-stack-message'),
      stats: $('world-stack-stats'),
      list: $('world-stack-list'),
      order: $('world-stack-order'),
      orderPreview: $('world-stack-order-preview'),
      orderSave: $('world-stack-order-save'),
      importPath: $('world-stack-import-path'),
      importPosition: $('world-stack-import-position'),
      importAfter: $('world-stack-import-after'),
      importAfterWrap: $('world-stack-after-wrap'),
      importPreviewButton: $('world-stack-import-preview-button'),
      importError: $('world-stack-import-error'),
      importQueue: $('world-stack-import-queue'),
      importPreview: $('world-stack-import-preview'),
      archiveError: $('world-stack-archive-error'),
      archiveList: $('world-stack-archive-list'),
      archivePreview: $('world-stack-archive-preview'),
      timeline: $('world-stack-timeline'),
      timelineError: $('world-stack-timeline-error'),
      timelinePreview: $('world-stack-timeline-preview'),
      snapshots: $('world-stack-snapshots'),
      snapshotRefresh: $('world-stack-snapshot-refresh'),
      files: $('world-stack-files'),
      jobs: $('world-stack-jobs'),
      refresh: $('world-stack-refresh'),
      overview: $('world-stack-overview'),
      layers: $('world-stack-layers'),
      orderPanel: $('world-stack-order-panel'),
      importPanel: $('world-stack-import-panel'),
      archivePanel: $('world-stack-archive-panel'),
      timelinePanel: $('world-stack-timeline-panel'),
      filesPanel: $('world-stack-files-panel'),
      jobsPanel: $('world-stack-jobs-panel'),
    };
    return els;
  }

  function setMessage(text, kind) {
    const el = ensureEls().message;
    if (!el) return;
    el.textContent = text || '';
    el.className = 'message' + (kind ? ' ' + kind : '');
  }

  // 操作按钮在页面下方，顶部 message 常常在视口外；失败时同时写进操作面板内的错误框。
  function renderInlineError(el, text) {
    if (!el) return;
    el.replaceChildren();
    el.hidden = !text;
    if (text) appendList(el, 'world-stack-error-list', [text]);
  }

  function fail(panelId, errorEl, text) {
    setMessage(text, 'error');
    renderInlineError(errorEl, text);
    window.SamsaraUI?.scrollToTarget?.(panelId);
  }

  function stacks() {
    return state.model && Array.isArray(state.model.stacks) ? state.model.stacks : [];
  }

  function nonTop() {
    return stacks().filter((stack) => !stack.top)
      .sort((a, b) => a.index - b.index);
  }

  function byId(id) {
    return stacks().find((stack) => stack.id === id) || null;
  }

  function fmtBytes(value) {
    const bytes = Number(value) || 0;
    if (bytes <= 0) return '0 B';
    const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
    let size = bytes;
    let unit = 0;
    while (size >= 1024 && unit < units.length - 1) {
      size /= 1024;
      unit += 1;
    }
    return size.toFixed(unit === 0 ? 0 : 1) + ' ' + units[unit];
  }

  function fmtTime(value) {
    if (!value) return '—';
    try { return new Date(value).toLocaleString(); } catch (e) { return String(value); }
  }

  function sourceName(source) {
    return ({
      current: '当前世界',
      layer: '栈层',
      imported: '导入',
      demoted: '被压入的栈顶',
      'new-world': '新世界',
      initial: '初始',
      legacy: '旧数据',
    })[source] || source || '—';
  }

  function appendChip(parent, label, value) {
    const chip = document.createElement('span');
    chip.className = 'map-stat';
    const name = document.createElement('span');
    name.textContent = label;
    const strong = document.createElement('strong');
    strong.textContent = value;
    chip.append(name, strong);
    parent.append(chip);
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

  async function post(path, payload) {
    try {
      return await window.SamsaraUI.api(path, {
        method: 'POST',
        body: JSON.stringify(payload || {}),
      });
    } catch (error) {
      if (error.status === 400 && error.body) return error.body;
      throw error;
    }
  }

  async function load() {
    if (state.loading) return;
    state.loading = true;
    setMessage('正在读取世界栈…');
    try {
      const data = await window.SamsaraUI.api('/api/world-stack');
      state.model = data;
      state.order = stacks().slice().sort((a, b) => a.index - b.index)
        .map((stack) => stack.id);
      state.savedOrder = state.order.slice();
      state.preview = null;
      state.archive = null;
      state.importConfirmed = false;
      state.importError = '';
      state.importBusy = false;
      state.archiveConfirmed = false;
      state.archiveError = '';
      state.archiveBusy = false;
      try {
        state.timeline = await window.SamsaraUI.api('/api/world-stack/timeline');
      } catch (error) {
        state.timeline = (data && data.timeline) || { nodes: [] };
      }
      state.timelinePreview = null;
      state.timelineConfirmed = false;
      state.timelineError = '';
      try {
        state.snapshots = await window.SamsaraUI.api('/api/world-stack/timeline/snapshots');
      } catch (error) {
        state.snapshots = { ok: false, totalBytes: 0, snapshots: [] };
      }
      setMessage('');
      render();
    } catch (error) {
      setMessage(window.SamsaraUI.authMessage(error, '世界栈读取失败'), 'error');
    } finally {
      state.loading = false;
    }
  }

  function render() {
    renderStats();
    renderList();
    renderOrder();
    renderAfterSelect();
    renderImportQueue();
    renderImportState();
    renderArchiveList();
    renderArchiveState();
    renderTimelineError();
    renderTimelinePreview();
    renderSnapshots();
    renderFiles();
    renderJobs();
    // 存档备份页也会调用 load() 预取世界栈数据；只有世界栈标签真正激活时才接管左侧导航。
    if (window.SamsaraUI?.isActiveTab?.('world-stack')) renderNav();
  }

  function renderStats() {
    const { stats } = ensureEls();
    if (!stats) return;
    stats.replaceChildren();
    const model = state.model || {};
    const top = stacks().find((stack) => stack.top);
    appendChip(stats, '栈层', String(stacks().length));
    appendChip(stats, '栈顶', top ? top.id : '—');
    appendChip(stats, '总数据', fmtBytes(model.totalBytes));
    appendChip(stats, '待执行', model.restartRequired ? '需要重启' : '无');
    appendChip(stats, '导入根', (model.importRoots || []).length
      ? model.importRoots.join(' · ') : '仅服务器根');
    const report = model.lastReport;
    appendChip(stats, '上次任务', report ? report.status + ' · ' + (report.message || '') : '无');
  }

  function renderList() {
    const { list } = ensureEls();
    if (!list) return;
    list.replaceChildren();
    if (stacks().length === 0) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '没有栈层数据；服务器启动后会自动创建 world 栈顶。';
      list.append(empty);
      return;
    }
    const ordered = stacks().slice().sort((a, b) => b.index - a.index);
    for (const stack of ordered) {
      const card = document.createElement('article');
      card.className = 'world-stack-card' + (stack.top ? ' top' : '');
      const head = document.createElement('div');
      head.className = 'world-stack-card-head';
      const title = document.createElement('div');
      title.className = 'world-stack-card-title';
      const name = document.createElement('strong');
      name.textContent = stack.top ? 'world' : stack.id;
      const badges = document.createElement('span');
      badges.className = 'world-stack-badges';
      const index = document.createElement('span');
      index.className = 'world-stack-badge';
      index.textContent = '#' + (stack.index + 1);
      badges.append(index);
      if (stack.top) {
        const topBadge = document.createElement('span');
        topBadge.className = 'world-stack-badge top';
        topBadge.textContent = '栈顶';
        badges.append(topBadge);
      }
      if (stack.loaded) {
        const loaded = document.createElement('span');
        loaded.className = 'world-stack-badge ok';
        loaded.textContent = '已加载';
        badges.append(loaded);
      }
      const src = document.createElement('span');
      src.className = 'world-stack-badge';
      src.textContent = sourceName(stack.source);
      badges.append(src);
      title.append(name, badges);
      const actions = document.createElement('div');
      actions.className = 'panel-tools';
      if (!stack.top) {
        const archive = document.createElement('button');
        archive.type = 'button';
        archive.className = 'ghost danger';
        archive.textContent = '归档…';
        archive.addEventListener('click', () => previewArchive(stack.id));
        actions.append(archive);
      }
      head.append(title, actions);
      card.append(head);
      const dims = (stack.dimensions || [])
        .map((dim) => dim.id + ' ' + (dim.regions || 0))
        .join(' · ');
      const version = stack.versionName
        ? stack.versionName + (stack.snapshot ? ' · 快照' : '')
          + (stack.dataVersion ? ' · DataVersion ' + stack.dataVersion : '')
        : (stack.dataVersion ? 'DataVersion ' + stack.dataVersion : '—');
      appendRows(card, [
        ['世界名', stack.saveName || '—'],
        ['存档创建', fmtTime(stack.saveCreatedAt)],
        ['最后游玩', fmtTime(stack.lastPlayed)],
        ['存档版本', version],
        ['种子', String(stack.seed)],
        ['出生点', stack.spawnX + ', ' + stack.spawnY + ', ' + stack.spawnZ],
        ['出生历史', stack.spawnCycles + ' 轮'],
        ['维度 region', dims || '—'],
        ['数据大小', fmtBytes(stack.dataBytes)],
        ['来源', sourceName(stack.source) + (stack.importedFrom ? ' · ' + stack.importedFrom : '')],
        ['加入栈时间', fmtTime(stack.createdAt)],
        ['导入时间', stack.importedAt ? fmtTime(stack.importedAt) : '—'],
        ['元数据', stack.metaPath],
        ['维度', stack.dimensionsPath],
      ]);
      list.append(card);
    }
  }

  function renderOrder() {
    const { order, orderPreview, orderSave } = ensureEls();
    if (!order) return;
    order.replaceChildren();
    const old = state.savedOrder;
    const changed = state.order.join('|') !== old.join('|');
    const topMoved = changed && state.order[state.order.length - 1] !== 'world';
    const display = (ids) => ids.slice().reverse().join(' → ');
    if (orderPreview) {
      orderPreview.textContent = changed
        ? '原：' + display(old) + ' ／ 新：' + display(state.order)
          + (topMoved ? '（保存后提升栈顶，需重启）' : '')
        : '顺序未改变（从上到下 = 栈顶 → 栈底）';
    }
    if (orderSave) orderSave.disabled = !changed || Boolean(state.model && state.model.restartRequired);
    if (state.order.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '当前没有栈层。';
      order.append(empty);
      return;
    }
    // state.order 仍按栈底→栈顶保存给 API；界面从上到下显示栈顶→栈底。
    const view = state.order.slice().reverse();
    view.forEach((id, visualIndex) => {
      const index = state.order.indexOf(id);
      const stack = byId(id);
      const row = document.createElement('div');
      row.className = 'world-stack-order-item' + (stack && stack.top ? ' top' : '');
      row.draggable = true;
      row.dataset.id = id;
      const handle = document.createElement('span');
      handle.className = 'world-stack-drag';
      handle.textContent = '⋮⋮';
      handle.title = '拖动排序';
      const label = document.createElement('div');
      label.className = 'world-stack-order-label';
      const strong = document.createElement('strong');
      strong.textContent = stack && stack.top
        ? 'world（栈顶）' : id + '（第 ' + (index + 1) + ' 层）';
      if (visualIndex === 0) strong.textContent += ' · 最上层';
      if (visualIndex === view.length - 1) strong.textContent += ' · 最下层';
      const meta = document.createElement('span');
      meta.className = 'muted small';
      meta.textContent = stack
        ? '顺序 index ' + (stack.index + 1) + ' · seed ' + stack.seed
          + ' · ' + fmtBytes(stack.dataBytes)
        : '';
      label.append(strong, meta);
      const tools = document.createElement('div');
      tools.className = 'world-stack-order-tools';
      const up = document.createElement('button');
      up.type = 'button';
      up.className = 'ghost';
      up.textContent = '↑';
      up.title = '向栈顶方向移动';
      up.disabled = index === state.order.length - 1;
      up.addEventListener('click', () => moveOrder(index, index + 1));
      const down = document.createElement('button');
      down.type = 'button';
      down.className = 'ghost';
      down.textContent = '↓';
      down.title = '向栈底方向移动';
      down.disabled = index === 0;
      down.addEventListener('click', () => moveOrder(index, index - 1));
      tools.append(up, down);
      row.append(handle, label, tools);
      row.addEventListener('dragstart', (event) => {
        event.dataTransfer.setData('text/plain', id);
        row.classList.add('dragging');
      });
      row.addEventListener('dragend', () => row.classList.remove('dragging'));
      row.addEventListener('dragover', (event) => {
        event.preventDefault();
        row.classList.add('drop-target');
      });
      row.addEventListener('dragleave', () => row.classList.remove('drop-target'));
      row.addEventListener('drop', (event) => {
        event.preventDefault();
        row.classList.remove('drop-target');
        const dragged = event.dataTransfer.getData('text/plain');
        const from = state.order.indexOf(dragged);
        const to = state.order.indexOf(id);
        if (from >= 0 && to >= 0 && from !== to) moveOrder(from, to);
      });
      order.append(row);
    });
  }

  function moveOrder(from, to) {
    if (to < 0 || to >= state.order.length || from === to) return;
    const next = state.order.slice();
    const [item] = next.splice(from, 1);
    next.splice(to, 0, item);
    state.order = next;
    renderOrder();
  }

  function renderAfterSelect() {
    const { importAfter, importAfterWrap, importPosition } = ensureEls();
    if (!importAfter || !importAfterWrap) return;
    const current = importAfter.value;
    importAfter.replaceChildren();
    const list = nonTop();
    for (let i = 0; i < list.length; i += 1) {
      const lower = list[i];
      const upper = i + 1 < list.length ? list[i + 1].id : 'world（栈顶）';
      const option = document.createElement('option');
      option.value = lower.id;
      option.textContent = lower.id + ' 与 ' + upper + ' 之间';
      importAfter.append(option);
    }
    if (list.length === 0) {
      const option = document.createElement('option');
      option.value = '';
      option.textContent = '没有可插入的旧栈层';
      option.disabled = true;
      importAfter.append(option);
    }
    if (current && nonTop().some((stack) => stack.id === current)) {
      importAfter.value = current;
    }
    const between = importPosition && importPosition.value === 'between';
    importAfterWrap.hidden = !between;
  }

  function pendingImports() {
    const model = state.model || {};
    if (Array.isArray(model.pendingImports) && model.pendingImports.length) {
      return model.pendingImports;
    }
    return model.pendingImport ? [model.pendingImport] : [];
  }

  function importPositionLabel(item) {
    if (item.position === 'top') return '栈顶（替换 world）';
    if (item.position === 'between') return '插到 ' + (item.afterId || '?') + ' 之上';
    return '栈底';
  }

  function renderImportQueue() {
    const { importQueue } = ensureEls();
    if (!importQueue) return;
    importQueue.replaceChildren();
    const list = pendingImports();
    importQueue.hidden = list.length === 0;
    if (list.length === 0) return;
    const head = document.createElement('p');
    head.className = 'muted small';
    head.textContent = '待导入队列（' + list.length
      + ' 项）：重启时按下列顺序依次执行，不需要多次重启。';
    importQueue.append(head);
    list.forEach((item, index) => {
      const row = document.createElement('div');
      row.className = 'world-stack-queue-item';
      const title = document.createElement('strong');
      title.textContent = '#' + (index + 1);
      const label = document.createElement('span');
      label.className = 'grow';
      label.textContent = (item.sourceWorldName || item.source || '世界')
        + ' → ' + importPositionLabel(item)
        + (item.position === 'top' ? '' : ' · ' + (item.targetId || '—'));
      label.title = item.source || '';
      const size = document.createElement('span');
      size.className = 'muted small';
      size.textContent = fmtBytes(item.sourceBytes);
      const cancel = document.createElement('button');
      cancel.type = 'button';
      cancel.className = 'ghost danger';
      cancel.textContent = '取消';
      cancel.title = '取消这条尚未执行的导入';
      cancel.addEventListener('click', () => cancelImport(item.jobId));
      row.append(title, label, size, cancel);
      importQueue.append(row);
    });
  }

  async function cancelImport(jobId) {
    if (!jobId) return;
    if (!window.confirm('取消排队中的导入 ' + jobId + '？源目录不会被修改。')) return;
    try {
      const data = await post('/api/world-stack/import/cancel', { jobId });
      if (!data.ok) {
        setMessage(data.error || '取消导入失败', 'error');
        return;
      }
      await load();
      setMessage('已取消 ' + jobId + '；队列剩余 ' + data.remaining + ' 项。', 'ok');
    } catch (error) {
      setMessage(window.SamsaraUI.authMessage(error, '取消导入失败'), 'error');
    }
  }

  function renderImportState() {
    const { importPreviewButton, importError } = ensureEls();
    if (importPreviewButton) {
      importPreviewButton.disabled = state.importBusy;
      importPreviewButton.textContent = state.importBusy ? '处理中…' : '预览导入';
    }
    renderInlineError(importError, state.importError);
    renderImportPreview();
  }

  function renderArchiveList() {
    const { archiveList } = ensureEls();
    if (!archiveList) return;
    archiveList.replaceChildren();
    const list = nonTop().slice().sort((a, b) => b.index - a.index);
    if (list.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '没有可归档的旧栈层；world 是栈顶，不能归档。导入新栈层或提升其他栈层后，这里会出现归档入口。';
      archiveList.append(empty);
      return;
    }
    const head = document.createElement('p');
    head.className = 'muted small';
    head.textContent = '可归档的旧栈层（不含栈顶 world）。点“归档…”先预览，确认后排队，重启时先备份再移除。';
    archiveList.append(head);
    for (const stack of list) {
      const row = document.createElement('div');
      row.className = 'world-stack-queue-item';
      const title = document.createElement('strong');
      title.textContent = stack.id;
      const label = document.createElement('span');
      label.className = 'grow';
      label.textContent = '第 ' + (stack.index + 1) + ' 层 · '
        + (stack.saveName || '未命名世界') + ' · ' + fmtBytes(stack.dataBytes)
        + ' · 存档创建 ' + fmtTime(stack.saveCreatedAt);
      const action = document.createElement('button');
      action.type = 'button';
      action.className = 'ghost danger';
      action.textContent = '归档…';
      action.addEventListener('click', () => previewArchive(stack.id));
      row.append(title, label, action);
      archiveList.append(row);
    }
  }

  function renderArchiveState() {
    const { archiveError } = ensureEls();
    renderInlineError(archiveError, state.archiveError);
    renderArchivePreview();
  }

  function renderTimelineError() {
    const { timelineError } = ensureEls();
    renderInlineError(timelineError, state.timelineError);
  }

  function renderImportPreview() {
    const { importPreview } = ensureEls();
    if (!importPreview) return;
    importPreview.replaceChildren();
    const preview = state.preview;
    if (!preview) {
      importPreview.hidden = true;
      return;
    }
    importPreview.hidden = false;
    const title = document.createElement('div');
    title.className = 'world-stack-preview-title';
    title.textContent = preview.ok ? '导入预览：可以执行' : '导入预览：需要修正';
    importPreview.append(title);
    if (preview.source) {
      appendRows(importPreview, [
        ['源目录', preview.source.canonicalPath || preview.source.path],
        ['布局', preview.source.layout],
        ['世界名', preview.source.worldName],
        ['种子', String(preview.source.seed)],
        ['出生点', preview.source.spawnX + ', ' + preview.source.spawnY + ', ' + preview.source.spawnZ],
        ['源大小', fmtBytes(preview.source.sizeBytes) + ' / ' + preview.source.fileCount + ' 文件'],
        ['玩家文件', String(preview.source.playerFiles)],
        ['session.lock', preview.source.sessionLocked ? '仍被占用（拒绝）' : '未占用'],
      ]);
    }
    appendRows(importPreview, [
      ['位置', preview.position === 'top' ? '栈顶（替换当前 world）'
        : preview.position === 'between'
          ? '插到 ' + preview.afterId + ' 与 '
            + (preview.betweenUpper || 'world（栈顶）') + ' 之间'
          : '栈底'],
      ['目标 id', preview.targetId || '—'],
      ['目标 index', String(preview.targetIndex + 1)],
      ['需要空间', fmtBytes(preview.neededBytes) + '（可用 ' + fmtBytes(preview.freeBytes) + '）'],
      ['玩家冲突', String(preview.playerConflicts) + ' 个（保留服务器现有）'],
      ['执行时机', '服务器重启时；源目录只读'],
    ]);
    if (Array.isArray(preview.warnings) && preview.warnings.length) {
      const box = document.createElement('div');
      box.className = 'world-stack-warning';
      appendList(box, 'world-stack-warning-list', preview.warnings);
      importPreview.append(box);
    }
    if (Array.isArray(preview.steps) && preview.steps.length) {
      const box = document.createElement('div');
      box.className = 'world-stack-steps';
      const h = document.createElement('strong');
      h.textContent = '将执行';
      const ol = document.createElement('ol');
      for (const step of preview.steps) {
        const li = document.createElement('li');
        li.textContent = step;
        ol.append(li);
      }
      box.append(h, ol);
      importPreview.append(box);
    }
    if (Array.isArray(preview.errors) && preview.errors.length) {
      const box = document.createElement('div');
      box.className = 'world-stack-error';
      appendList(box, 'world-stack-error-list', preview.errors);
      importPreview.append(box);
      return;
    }
    const confirm = document.createElement('label');
    confirm.className = 'check-line world-stack-confirm';
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = state.importConfirmed;
    checkbox.addEventListener('change', () => {
      state.importConfirmed = checkbox.checked;
      renderImportPreview();
    });
    const text = document.createElement('span');
    text.textContent = preview.position === 'top'
      ? '我确认：先把当前 world 压入旧栈并备份，再把源世界复制为新的 world；重启后生效'
      : '我确认：复制源目录到新栈层，源目录保持不变；重启后生效';
    confirm.append(checkbox, text);
    const run = document.createElement('button');
    run.type = 'button';
    run.className = 'primary';
    run.textContent = state.importBusy ? '排队中…' : '确认导入（需重启）';
    run.disabled = !state.importConfirmed || state.importBusy;
    run.addEventListener('click', runImport);
    importPreview.append(confirm, run);
  }

  async function previewImport() {
    const { importPath, importPosition, importAfter, importError } = ensureEls();
    if (!importPath || state.importBusy) return;
    const path = importPath.value.trim();
    state.importBusy = true;
    state.importError = '';
    renderImportState();
    try {
      const data = await post('/api/world-stack/import/preview', {
        path,
        position: importPosition ? importPosition.value : 'bottom',
        afterId: importAfter ? importAfter.value : '',
        deleteSource: false,
      });
      state.preview = data;
      state.importConfirmed = false;
      window.SamsaraUI.scrollToTarget?.('world-stack-import-panel');
    } catch (error) {
      state.preview = null;
      fail('world-stack-import-panel', importError,
        window.SamsaraUI.authMessage(error, '导入预览失败'));
    } finally {
      state.importBusy = false;
      renderImportState();
    }
  }

  async function runImport() {
    const { importPath, importPosition, importAfter, importError } = ensureEls();
    if (!state.importConfirmed || state.importBusy) return;
    state.importBusy = true;
    state.importError = '';
    renderImportState();
    try {
      const data = await post('/api/world-stack/import', {
        path: importPath ? importPath.value.trim() : '',
        position: importPosition ? importPosition.value : 'bottom',
        afterId: importAfter ? importAfter.value : '',
        deleteSource: false,
      });
      if (!data.ok) {
        state.preview = data;
        state.importConfirmed = false;
        fail('world-stack-import-panel', importError,
          (data.errors && data.errors[0]) || data.error || '导入排队失败');
        return;
      }
      state.preview = null;
      await load();
      const queuedMessage = data.job && data.job.message ? data.job.message : ('已排队：' + data.jobId);
      setMessage(queuedMessage + '。重启服务器后按队列依次执行；源目录保持不变。', 'ok');
    } catch (error) {
      fail('world-stack-import-panel', importError,
        window.SamsaraUI.authMessage(error, '导入排队失败'));
    } finally {
      state.importBusy = false;
      renderImportState();
    }
  }

  async function previewArchive(id) {
    const { archiveError } = ensureEls();
    if (state.archiveBusy) return;
    state.archiveBusy = true;
    state.archiveError = '';
    renderArchiveState();
    try {
      const data = await post('/api/world-stack/archive/preview', { id });
      state.archive = data;
      state.archiveConfirmed = false;
      window.SamsaraUI.scrollToTarget?.('world-stack-archive-panel');
    } catch (error) {
      state.archive = null;
      fail('world-stack-archive-panel', archiveError,
        window.SamsaraUI.authMessage(error, '归档预览失败'));
    } finally {
      state.archiveBusy = false;
      renderArchiveState();
    }
  }

  function renderArchivePreview() {
    const { archivePreview } = ensureEls();
    if (!archivePreview) return;
    archivePreview.replaceChildren();
    const preview = state.archive;
    if (!preview) {
      archivePreview.hidden = true;
      return;
    }
    archivePreview.hidden = false;
    const title = document.createElement('div');
    title.className = 'world-stack-preview-title';
    title.textContent = preview.ok ? '归档预览：' + preview.targetId : '归档预览：需要修正';
    archivePreview.append(title);
    if (preview.source) {
      appendRows(archivePreview, [
        ['栈层', preview.targetId],
        ['数据大小', fmtBytes(preview.source.sizeBytes)],
        ['备份位置', preview.backupPath || '服务器级 samsara/world-stack-archive/' + preview.targetId + '-<job>'],
        ['执行时机', '服务器重启时；先备份再移除；备份不跟随存档'],
      ]);
    }
    if (Array.isArray(preview.steps) && preview.steps.length) {
      const box = document.createElement('div');
      box.className = 'world-stack-steps';
      const h = document.createElement('strong');
      h.textContent = '将执行';
      const ol = document.createElement('ol');
      for (const step of preview.steps) {
        const li = document.createElement('li');
        li.textContent = step;
        ol.append(li);
      }
      box.append(h, ol);
      archivePreview.append(box);
    }
    if (Array.isArray(preview.warnings) && preview.warnings.length) {
      const box = document.createElement('div');
      box.className = 'world-stack-warning';
      appendList(box, 'world-stack-warning-list', preview.warnings);
      archivePreview.append(box);
    }
    if (Array.isArray(preview.errors) && preview.errors.length) {
      const box = document.createElement('div');
      box.className = 'world-stack-error';
      appendList(box, 'world-stack-error-list', preview.errors);
      archivePreview.append(box);
      return;
    }
    const confirm = document.createElement('label');
    confirm.className = 'check-line world-stack-confirm';
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = state.archiveConfirmed;
    checkbox.addEventListener('change', () => {
      state.archiveConfirmed = checkbox.checked;
      renderArchivePreview();
    });
    const text = document.createElement('span');
    text.textContent = '我确认：重启后把该栈层移到 stack-archive 备份并从栈清单移除，world 仍为栈顶';
    confirm.append(checkbox, text);
    const run = document.createElement('button');
    run.type = 'button';
    run.className = 'danger';
    run.textContent = state.archiveBusy ? '排队中…' : '确认归档（需重启）';
    run.disabled = !state.archiveConfirmed || state.archiveBusy;
    run.addEventListener('click', runArchive);
    archivePreview.append(confirm, run);
  }

  async function runArchive() {
    const { archiveError } = ensureEls();
    if (!state.archiveConfirmed || !state.archive || state.archiveBusy) return;
    state.archiveBusy = true;
    state.archiveError = '';
    renderArchiveState();
    try {
      const data = await post('/api/world-stack/archive', { id: state.archive.targetId });
      if (!data.ok) {
        state.archive = data;
        state.archiveConfirmed = false;
        fail('world-stack-archive-panel', archiveError,
          (data.errors && data.errors[0]) || data.error || '归档排队失败');
        return;
      }
      state.archive = null;
      await load();
      setMessage('已排队：' + data.jobId + '。重启服务器后归档；备份会保留。', 'ok');
    } catch (error) {
      fail('world-stack-archive-panel', archiveError,
        window.SamsaraUI.authMessage(error, '归档排队失败'));
    } finally {
      state.archiveBusy = false;
      renderArchiveState();
    }
  }

  // ---- 操作时间线 ----

  function operationName(value) {
    return ({
      import: '导入',
      promote: '提升栈顶',
      archive: '归档',
      restore: '撤销',
    })[value] || value || '操作';
  }

  function renderTimeline() {
    const { timeline } = ensureEls();
    if (!timeline) return;
    timeline.replaceChildren();
    const data = state.timeline || { nodes: [] };
    const nodes = Array.isArray(data.nodes) ? data.nodes : [];
    const model = state.model || {};
    let pendingCount = 0;
    const appendPending = (operation, titleText, metaText, jobId, cancelJobId) => {
      pendingCount += 1;
      const item = document.createElement('div');
      item.className = 'timeline-node pending';
      item.dataset.operation = operation;
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
      strong.textContent = titleText;
      const idText = document.createElement('span');
      idText.className = 'muted small mono';
      idText.textContent = jobId || 'queued';
      title.append(strong, idText);
      const badges = document.createElement('div');
      badges.className = 'timeline-badges';
      const badge = document.createElement('span');
      badge.className = 'world-stack-badge warn';
      badge.textContent = '待执行 · 需重启';
      badges.append(badge);
      head.append(title, badges);
      const meta = document.createElement('p');
      meta.className = 'muted small';
      meta.textContent = metaText;
      card.append(head, meta);
      if (cancelJobId) {
        const actions = document.createElement('div');
        actions.className = 'timeline-actions';
        const cancel = document.createElement('button');
        cancel.type = 'button';
        cancel.className = 'ghost danger';
        cancel.textContent = '取消排队';
        cancel.addEventListener('click', () => cancelImport(cancelJobId));
        actions.append(cancel);
        card.append(actions);
      }
      item.append(rail, card);
      timeline.append(item);
    };
    for (const pending of pendingImports()) {
      appendPending('import', '导入 · ' + fmtTime(pending.createdAt),
        (pending.sourceWorldName || pending.source || '世界')
          + ' → ' + (pending.position === 'top' ? '栈顶 world'
            : importPositionLabel(pending) + ' ' + (pending.targetId || ''))
          + ' · ' + fmtBytes(pending.sourceBytes)
          + ' · 源 ' + (pending.source || '—'),
        pending.jobId, pending.jobId);
    }
    if (model.pendingArchive) {
      appendPending('archive', '归档 · ' + fmtTime(model.pendingArchive.createdAt),
        '栈层 ' + model.pendingArchive.id + ' → ' + (model.pendingArchive.archivePath || 'stack-archive'),
        model.pendingArchive.jobId, '');
    }
    if (model.pendingPromote) {
      appendPending('promote', '提升栈顶 · ' + fmtTime(model.pendingPromote.createdAt),
        (model.pendingPromote.demoteId || '原 world') + ' 压入旧栈，'
          + (model.pendingPromote.promoteId || '目标层') + ' 成为新 world',
        model.pendingPromote.jobId, '');
    }
    if (model.pendingRestore) {
      appendPending('restore', '撤销操作 · ' + fmtTime(model.pendingRestore.createdAt),
        '撤销 ' + (model.pendingRestore.operationId || model.pendingRestore.id || '—'),
        model.pendingRestore.jobId, '');
    }
    if (nodes.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = pendingCount > 0
        ? '以上任务会在下次重启时执行；完成后才会生成可撤销的时间线节点。'
        : '还没有世界栈操作快照。导入/提升/归档完成后会在这里生成节点；'
          + '旧备份没有 operation.json 时只读展示。';
      timeline.append(empty);
      return;
    }
    const doneHead = document.createElement('p');
    doneHead.className = 'muted small';
    doneHead.textContent = '已完成操作（仅末端节点可撤销）';
    timeline.append(doneHead);
    for (const node of nodes) {
      const item = document.createElement('div');
      item.className = 'timeline-node' + (node.current ? ' current' : '');
      item.style.setProperty('--depth', String(node.depth || 0));
      item.dataset.id = node.id || '';
      item.dataset.branchId = node.branchId || '';
      item.dataset.operation = node.operation || '';
      item.dataset.restorable = String(Boolean(node.restorable));
      item.dataset.legacy = String(Boolean(node.legacy));
      item.dataset.depth = String(node.depth || 0);
      if (node.depth > 0) item.classList.add('branch');

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
      strong.textContent = operationName(node.operation) + ' · ' + fmtTime(node.createdAt);
      const idText = document.createElement('span');
      idText.className = 'muted small mono';
      idText.textContent = node.id;
      title.append(strong, idText);

      const badges = document.createElement('div');
      badges.className = 'timeline-badges';
      const operation = document.createElement('span');
      operation.className = 'world-stack-badge';
      operation.textContent = operationName(node.operation);
      badges.append(operation);
      if (node.current) {
        const current = document.createElement('span');
        current.className = 'world-stack-badge ok';
        current.textContent = '当前时间线';
        badges.append(current);
      }
      if (node.restoredAt) {
        const restored = document.createElement('span');
        restored.className = 'world-stack-badge done';
        restored.textContent = '已撤销 ' + fmtTime(node.restoredAt);
        badges.append(restored);
      }
      if (node.legacy) {
        const legacy = document.createElement('span');
        legacy.className = 'world-stack-badge warn';
        legacy.textContent = '旧备份 · 只读';
        badges.append(legacy);
      } else if (node.restorable) {
        const ok = document.createElement('span');
        ok.className = 'world-stack-badge ok';
        ok.textContent = '可撤销';
        badges.append(ok);
      } else {
        const readonly = document.createElement('span');
        readonly.className = 'world-stack-badge';
        readonly.textContent = '不可撤销';
        badges.append(readonly);
      }
      head.append(title, badges);

      const meta = document.createElement('p');
      meta.className = 'muted small';
      const affected = Array.isArray(node.affectedIds) && node.affectedIds.length
        ? ' · 层 ' + node.affectedIds.join(' / ') : '';
      meta.textContent = 'job=' + (node.jobId || '—')
        + affected
        + (node.parentId ? ' · 上一节点 ' + node.parentId : ' · 时间线起点')
        + (node.restorableReason ? ' · ' + node.restorableReason : '');
      card.append(head, meta);

      const timelineActions = document.createElement('div');
      timelineActions.className = 'timeline-actions';
      const undo = document.createElement('button');
      undo.type = 'button';
      undo.className = 'danger';
      undo.textContent = '撤销这次操作';
      undo.disabled = !node.restorable;
      undo.title = node.restorable
        ? '停服后、下次启动前撤销；先预览并勾选确认'
        : (node.restorableReason || '该节点没有完整可逆快照');
      undo.addEventListener('click', () => previewTimelineRestore(node.id));
      timelineActions.append(undo);

      const jobButton = document.createElement('button');
      jobButton.type = 'button';
      jobButton.className = 'ghost';
      jobButton.textContent = '查看 job';
      const jobBox = document.createElement('pre');
      jobBox.className = 'world-stack-log';
      jobBox.hidden = true;
      jobBox.textContent = timelineJobText(node);
      jobButton.addEventListener('click', () => { jobBox.hidden = !jobBox.hidden; });
      timelineActions.append(jobButton);

      const manifestButton = document.createElement('button');
      manifestButton.type = 'button';
      manifestButton.className = 'ghost';
      manifestButton.textContent = '查看 manifest 摘要';
      const manifestBox = document.createElement('div');
      manifestBox.className = 'world-stack-warning';
      manifestBox.hidden = true;
      appendList(manifestBox, 'world-stack-warning-list',
        timelineManifestLines(node));
      manifestButton.addEventListener('click', () => {
        manifestBox.hidden = !manifestBox.hidden;
      });
      timelineActions.append(manifestButton);

      const exportButton = document.createElement('button');
      exportButton.type = 'button';
      exportButton.className = 'ghost';
      exportButton.textContent = '导出快照';
      exportButton.disabled = Boolean(node.legacy) || node.operation === 'restore';
      exportButton.title = exportButton.disabled
        ? '旧备份或撤销任务没有 operation.json，不能导出操作快照'
        : '下载 operation.json（含前后完整栈清单与 job 摘要）';
      exportButton.addEventListener('click', () => exportTimelineNode(node));
      timelineActions.append(exportButton);
      card.append(timelineActions, jobBox, manifestBox);

      item.append(rail, card);
      timeline.append(item);
    }
  }

  function timelineJobText(node) {
    const model = state.model || {};
    const jobs = Array.isArray(model.jobs) ? model.jobs.slice() : [];
    if (model.lastReport) jobs.unshift(model.lastReport);
    const job = jobs.find((item) => item && item.id === node.jobId);
    const lines = [
      'job=' + (node.jobId || '—'),
      'operation=' + (node.operation || '—'),
      'backup=' + (node.backupDir || '—'),
    ];
    if (job) {
      lines.push('status=' + (job.status || '—') + ' · ' + (job.stage || ''));
      lines.push('message=' + (job.message || ''));
      if (Array.isArray(job.log) && job.log.length) {
        lines.push('--- 日志 ---');
        lines.push(...job.log.slice(-20));
      }
    } else {
      lines.push('（任务报告已清理或只在备份快照中保留）');
    }
    return lines.join('\n');
  }

  function timelineManifestLines(node) {
    const lines = [];
    const before = Array.isArray(node.beforeSummary) ? node.beforeSummary : [];
    const after = Array.isArray(node.afterSummary) ? node.afterSummary : [];
    lines.push('操作前：');
    lines.push(...(before.length ? before : ['—']));
    lines.push('操作后：');
    lines.push(...(after.length ? after : ['—']));
    if (Array.isArray(node.notes) && node.notes.length) {
      lines.push('备注：');
      lines.push(...node.notes);
    }
    return lines;
  }

  async function exportTimelineNode(node) {
    const { timelineError } = ensureEls();
    state.timelineError = '';
    renderTimelineError();
    try {
      const data = await window.SamsaraUI.api(
        '/api/world-stack/timeline/export?id=' + encodeURIComponent(node.id));
      if (!data.ok) {
        fail('world-stack-timeline-panel', timelineError, data.error || '导出操作快照失败');
        return;
      }
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = (data.id || node.id || 'world-stack') + '-operation.json';
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1200);
      setMessage('已导出 ' + link.download, 'ok');
    } catch (error) {
      fail('world-stack-timeline-panel', timelineError,
        window.SamsaraUI.authMessage(error, '导出操作快照失败'));
    }
  }

  async function loadSnapshots() {
    const { timelineError } = ensureEls();
    try {
      state.snapshots = await window.SamsaraUI.api('/api/world-stack/timeline/snapshots');
      renderSnapshots();
    } catch (error) {
      fail('world-stack-timeline-panel', timelineError,
        window.SamsaraUI.authMessage(error, '读取快照占用失败'));
    }
  }

  function renderSnapshots() {
    const { snapshots } = ensureEls();
    if (!snapshots) return;
    snapshots.replaceChildren();
    const data = state.snapshots || { snapshots: [] };
    const list = Array.isArray(data.snapshots) ? data.snapshots : [];
    if (list.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '没有撤销前安全快照。pre-restore-* 永不自动删除；需要释放空间时在这里手动清理。';
      snapshots.append(empty);
      return;
    }
    const head = document.createElement('p');
    head.className = 'muted small';
    head.textContent = '共 ' + list.length + ' 份，占用 ' + fmtBytes(data.totalBytes)
      + '。这些是撤销/恢复前的安全网，不会被 backupKeep 自动清理。';
    snapshots.append(head);
    for (const item of list) {
      const row = document.createElement('div');
      row.className = 'world-stack-snapshot';
      const info = document.createElement('div');
      info.className = 'world-stack-snapshot-info';
      const name = document.createElement('strong');
      name.className = 'mono';
      name.textContent = item.name || '—';
      const meta = document.createElement('span');
      meta.className = 'muted small';
      const parts = [fmtBytes(item.bytes), fmtTime(item.createdAt || item.modifiedAt)];
      if (item.operation) parts.push(operationName(item.operation));
      if (item.operationId) parts.push(item.operationId);
      meta.textContent = parts.join(' · ');
      info.append(name, meta);
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'ghost danger';
      remove.textContent = '删除快照';
      remove.addEventListener('click', () => cleanupSnapshot(item));
      row.append(info, remove);
      snapshots.append(row);
    }
  }

  async function cleanupSnapshot(item) {
    const name = item && item.name ? item.name : '';
    const { timelineError } = ensureEls();
    if (!name) return;
    if (!window.confirm('删除安全快照 ' + name + '？此操作只删除撤销前保留的当前状态副本，'
      + '不会删除源世界、归档或操作备份。')) {
      return;
    }
    state.timelineError = '';
    renderTimelineError();
    try {
      const data = await post('/api/world-stack/timeline/cleanup', {
        name,
        confirm: true,
      });
      if (!data.ok) {
        fail('world-stack-timeline-panel', timelineError, data.error || '删除快照失败');
        return;
      }
      setMessage(data.message || '已删除快照', 'ok');
      await loadSnapshots();
    } catch (error) {
      fail('world-stack-timeline-panel', timelineError,
        window.SamsaraUI.authMessage(error, '删除快照失败'));
    }
  }

  async function previewTimelineRestore(id) {
    const { timelineError } = ensureEls();
    state.timelineError = '';
    renderTimelineError();
    try {
      const data = await post('/api/world-stack/timeline/preview', { id });
      state.timelinePreview = data;
      state.timelineConfirmed = false;
      renderTimelinePreview();
      window.SamsaraUI.scrollToTarget?.('world-stack-timeline-panel');
    } catch (error) {
      state.timelinePreview = null;
      fail('world-stack-timeline-panel', timelineError,
        window.SamsaraUI.authMessage(error, '撤销预览失败'));
      renderTimelinePreview();
    }
  }

  function renderTimelinePreview() {
    const { timelinePreview } = ensureEls();
    if (!timelinePreview) return;
    timelinePreview.replaceChildren();
    const preview = state.timelinePreview;
    if (!preview) {
      timelinePreview.hidden = true;
      return;
    }
    timelinePreview.hidden = false;
    const title = document.createElement('div');
    title.className = 'world-stack-preview-title';
    title.textContent = preview.ok ? '撤销预览：可以执行' : '撤销预览：不可执行';
    timelinePreview.append(title);
    appendRows(timelinePreview, [
      ['节点', preview.id || '—'],
      ['操作', operationName(preview.operation)],
      ['job', preview.jobId || '—'],
      ['受影响层', (preview.affectedIds || []).join(' / ') || '—'],
      ['备份目录', preview.backupDir || '—'],
      ['备份大小', fmtBytes(preview.backupBytes)],
      ['当前受影响数据', fmtBytes(preview.currentBytes)],
      ['预计需要', fmtBytes(preview.neededBytes) + '（可用 ' + fmtBytes(preview.freeBytes) + '）'],
      ['执行时机', '停服后、下次启动前；先写 pre-restore 快照'],
    ]);
    if (Array.isArray(preview.beforeSummary) && preview.beforeSummary.length) {
      const box = document.createElement('div');
      box.className = 'world-stack-steps';
      const h = document.createElement('strong');
      h.textContent = '将恢复到（操作前栈清单）';
      appendList(box, 'world-stack-warning-list', preview.beforeSummary);
      box.prepend(h);
      timelinePreview.append(box);
    }
    if (Array.isArray(preview.warnings) && preview.warnings.length) {
      const box = document.createElement('div');
      box.className = 'world-stack-warning';
      appendList(box, 'world-stack-warning-list', preview.warnings);
      timelinePreview.append(box);
    }
    if (Array.isArray(preview.steps) && preview.steps.length) {
      const box = document.createElement('div');
      box.className = 'world-stack-steps';
      const h = document.createElement('strong');
      h.textContent = '将执行';
      const ol = document.createElement('ol');
      for (const step of preview.steps) {
        const li = document.createElement('li');
        li.textContent = step;
        ol.append(li);
      }
      box.append(h, ol);
      timelinePreview.append(box);
    }
    if (Array.isArray(preview.errors) && preview.errors.length) {
      const box = document.createElement('div');
      box.className = 'world-stack-error';
      appendList(box, 'world-stack-error-list', preview.errors);
      timelinePreview.append(box);
      return;
    }
    const confirm = document.createElement('label');
    confirm.className = 'check-line world-stack-confirm';
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = state.timelineConfirmed;
    checkbox.addEventListener('change', () => {
      state.timelineConfirmed = checkbox.checked;
      renderTimelinePreview();
    });
    const text = document.createElement('span');
    text.textContent = '我确认：停服后、下次启动前撤销这次操作；当前栈状态先写入 pre-restore-<job>，'
      + '失败自动回滚；源世界、归档与用户备份不会自动删除';
    confirm.append(checkbox, text);
    const run = document.createElement('button');
    run.type = 'button';
    run.className = 'danger';
    run.textContent = '确认撤销（需重启）';
    run.disabled = !state.timelineConfirmed;
    run.addEventListener('click', runTimelineRestore);
    timelinePreview.append(confirm, run);
  }

  async function runTimelineRestore() {
    const preview = state.timelinePreview;
    const { timelineError } = ensureEls();
    if (!preview || !state.timelineConfirmed) return;
    state.timelineError = '';
    renderTimelineError();
    try {
      const data = await post('/api/world-stack/timeline/restore', {
        id: preview.id,
        confirm: true,
      });
      if (!data.ok) {
        state.timelinePreview = data.preview || preview;
        state.timelineConfirmed = false;
        fail('world-stack-timeline-panel', timelineError,
          (data.errors && data.errors[0]) || data.error || '撤销排队失败');
        renderTimelinePreview();
        return;
      }
      state.timelinePreview = null;
      state.timelineConfirmed = false;
      await load();
      setMessage('已排队：' + data.jobId + '（' + data.operationId
        + '）。请停服并重新启动，撤销会在世界打开前执行。', 'ok');
    } catch (error) {
      fail('world-stack-timeline-panel', timelineError,
        window.SamsaraUI.authMessage(error, '撤销排队失败'));
    }
  }

  async function saveOrder() {
    if (state.order.join('|') === state.savedOrder.join('|')) return;
    try {
      const data = await post('/api/world-stack/reorder', { ids: state.order });
      if (!data.ok) {
        setMessage(data.error || '排序保存失败', 'error');
        return;
      }
      await load();
      if (data.requiresRestart) {
        setMessage('已排队：' + (data.jobId || '') + '。重启后提升栈顶；原 world 会压入旧栈。', 'ok');
      } else {
        setMessage('排序已保存；目录未改名，下次重启仍保持新顺序。', 'ok');
      }
    } catch (error) {
      setMessage(window.SamsaraUI.authMessage(error, '排序保存失败'), 'error');
    }
  }

  async function copyPath(text, button) {
    try {
      await navigator.clipboard.writeText(text);
      button.textContent = '✓';
      window.setTimeout(() => { button.textContent = '⧉'; }, 1400);
      return;
    } catch (error) {
      // fallback below
    }
    const area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.append(area);
    area.select();
    try {
      document.execCommand('copy');
      button.textContent = '✓';
      window.setTimeout(() => { button.textContent = '⧉'; }, 1400);
    } catch (error) {
      button.textContent = '!';
    } finally {
      area.remove();
    }
  }

  function renderFiles() {
    const { files } = ensureEls();
    if (!files) return;
    files.replaceChildren();
    const list = state.model && Array.isArray(state.model.files) ? state.model.files : [];
    if (list.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '暂无文件位置信息。';
      files.append(empty);
      return;
    }
    for (const node of list) {
      const row = document.createElement('div');
      row.className = 'world-stack-file' + (node.exists ? '' : ' missing');
      row.style.paddingLeft = (Math.max(0, node.depth) * 14 + 4) + 'px';
      row.title = node.path;
      const icon = document.createElement('span');
      icon.className = 'world-stack-file-icon';
      icon.textContent = node.exists ? (node.kind === 'dir' ? '▸' : '·') : '×';
      const label = document.createElement('span');
      label.className = 'world-stack-file-label';
      label.textContent = node.label;
      const note = document.createElement('span');
      note.className = 'world-stack-file-note';
      note.textContent = node.note || '';
      const size = document.createElement('span');
      size.className = 'world-stack-file-size';
      size.textContent = node.bytes >= 0 ? fmtBytes(node.bytes) : '';
      const copy = document.createElement('button');
      copy.type = 'button';
      copy.className = 'world-stack-file-copy';
      copy.textContent = '⧉';
      copy.title = '复制路径';
      copy.addEventListener('click', (event) => {
        event.stopPropagation();
        copyPath(node.path, copy);
      });
      row.append(icon, label, note, size, copy);
      row.addEventListener('click', () => copyPath(node.path, copy));
      files.append(row);
    }
  }

  function renderJobs() {
    const { jobs } = ensureEls();
    if (!jobs) return;
    jobs.replaceChildren();
    const model = state.model || {};
    const importQueue = pendingImports();
    importQueue.forEach((pending, index) => {
      jobs.append(jobCard({
        id: pending.jobId,
        type: 'import',
        status: 'queued',
        stage: '等待重启 · 队列第 ' + (index + 1) + ' 项',
        progress: 0,
        message: '重启后导入到 ' + (pending.position === 'top'
          ? '栈顶 world' : pending.targetId)
          + '；源 ' + pending.source,
        log: [],
      }));
    });
    if (model.pendingArchive) {
      jobs.append(jobCard({
        id: model.pendingArchive.jobId,
        type: 'archive',
        status: 'queued',
        stage: '等待重启',
        progress: 0,
        message: '重启后归档 ' + model.pendingArchive.id,
        log: [],
      }));
    }
    if (model.pendingPromote) {
      jobs.append(jobCard({
        id: model.pendingPromote.jobId,
        type: 'promote',
        status: 'queued',
        stage: '等待重启',
        progress: 0,
        message: '重启后把 ' + model.pendingPromote.promoteId
          + ' 提升为栈顶；原 world 压入 ' + model.pendingPromote.demoteId,
        log: [],
      }));
    }
    if (model.pendingRestore) {
      jobs.append(jobCard({
        id: model.pendingRestore.jobId,
        type: 'restore',
        status: 'queued',
        stage: '等待重启',
        progress: 0,
        message: '重启后撤销世界栈操作 ' + model.pendingRestore.operationId,
        log: [],
      }));
    }
    const list = (model.jobs || []).slice();
    if (model.lastReport && !list.some((job) => job.id === model.lastReport.id)) {
      list.unshift(model.lastReport);
    }
    if (list.length === 0 && importQueue.length === 0 && !model.pendingArchive
        && !model.pendingPromote && !model.pendingRestore) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '暂无导入/归档任务。排序是立即生效的，不会在这里生成任务。';
      jobs.append(empty);
      return;
    }
    for (const job of list) jobs.append(jobCard(job));
  }

  function jobCard(job) {
    const card = document.createElement('article');
    card.className = 'world-stack-job ' + (job.status || '');
    const head = document.createElement('div');
    head.className = 'world-stack-job-head';
    const title = document.createElement('strong');
    title.textContent = (job.type || 'job') + ' · ' + (job.id || '—');
    const status = document.createElement('span');
    status.className = 'world-stack-badge ' + (job.status || '');
    status.textContent = job.status || '—';
    head.append(title, status);
    card.append(head);
    const meta = document.createElement('p');
    meta.className = 'muted small';
    meta.textContent = (job.stage || '') + (job.progress ? ' · ' + job.progress + '%' : '')
      + (job.message ? ' · ' + job.message : '');
    card.append(meta);
    if (job.log && job.log.length) {
      const details = document.createElement('details');
      const summary = document.createElement('summary');
      summary.textContent = '日志（' + job.log.length + ' 行）';
      const pre = document.createElement('pre');
      pre.className = 'world-stack-log';
      pre.textContent = job.log.slice(-40).join('\n');
      details.append(summary, pre);
      card.append(details);
    }
    return card;
  }

  function renderNav() {
    if (!window.SamsaraUI) return;
    const e = ensureEls();
    const targets = [];
    const items = [];
    const add = (id, label, el) => {
      if (!el) return;
      targets.push({ id, el });
      items.push({ label, target: id });
    };
    add('world-stack-overview', '概览', e.overview);
    add('world-stack-layers', '栈层', e.layers);
    add('world-stack-order-panel', '栈位排序', e.orderPanel);
    add('world-stack-import-panel', '导入', e.importPanel);
    add('world-stack-archive-panel', '归档', e.archivePanel);
    add('world-stack-timeline-panel', '操作时间线', e.timelinePanel);
    add('world-stack-jobs-panel', '任务与结果', e.jobsPanel);
    window.SamsaraUI.buildNav('世界栈导航', items, targets);
  }

  function bind() {
    const e = ensureEls();
    if (!e.list) return;
    if (e.refresh) e.refresh.addEventListener('click', load);
    if (e.snapshotRefresh) e.snapshotRefresh.addEventListener('click', loadSnapshots);
    if (e.orderSave) e.orderSave.addEventListener('click', saveOrder);
    if (e.importPreviewButton) e.importPreviewButton.addEventListener('click', previewImport);
    if (e.importPosition) {
      e.importPosition.addEventListener('change', () => {
        renderAfterSelect();
        state.preview = null;
        state.importError = '';
        renderImportState();
      });
    }
    if (e.importPath) {
      e.importPath.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') previewImport();
      });
      e.importPath.addEventListener('input', () => {
        if (!state.importError) return;
        state.importError = '';
        renderImportState();
      });
    }
    if (window.SamsaraUI && window.SamsaraUI.onConnect) {
      window.SamsaraUI.onConnect(() => load());
    }
    if (state.model) render();
  }

  function activate() {
    if (!state.model && !state.loading) {
      load();
    } else {
      render();
    }
  }

  function refreshCount() {
    const count = stacks().length;
    window.SamsaraUI?.setTabCount('world-stack', count);
    const tab = document.querySelector('.tab[data-panel="panel-world-stack"]');
    if (tab) {
      tab.title = '世界栈：' + count + ' 层';
      tab.setAttribute('aria-label', '世界栈，' + count + ' 层');
    }
  }

  window.SamsaraWorldStack = {
    activate,
    load,
    reload: load,
    refreshCount,
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bind);
  } else {
    bind();
  }
})();
