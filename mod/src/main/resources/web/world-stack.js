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
    archiveConfirmed: false,
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
      importPreview: $('world-stack-import-preview'),
      archivePreview: $('world-stack-archive-preview'),
      jobs: $('world-stack-jobs'),
      refresh: $('world-stack-refresh'),
      overview: $('world-stack-overview'),
      layers: $('world-stack-layers'),
      orderPanel: $('world-stack-order-panel'),
      importPanel: $('world-stack-import-panel'),
      archivePanel: $('world-stack-archive-panel'),
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
      state.archiveConfirmed = false;
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
    renderImportPreview();
    renderArchivePreview();
    renderJobs();
    renderNav();
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
      appendRows(card, [
        ['种子', String(stack.seed)],
        ['出生点', stack.spawnX + ', ' + stack.spawnY + ', ' + stack.spawnZ],
        ['出生历史', stack.spawnCycles + ' 轮'],
        ['维度 region', dims || '—'],
        ['数据大小', fmtBytes(stack.dataBytes)],
        ['来源', sourceName(stack.source) + (stack.importedFrom ? ' · ' + stack.importedFrom : '')],
        ['创建时间', fmtTime(stack.createdAt)],
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
    if (orderPreview) {
      orderPreview.textContent = changed
        ? '原：' + old.join(' → ') + ' ／ 新：' + state.order.join(' → ')
          + (topMoved ? '（保存后提升栈顶，需重启）' : '')
        : '顺序未改变';
    }
    if (orderSave) orderSave.disabled = !changed || Boolean(state.model && state.model.restartRequired);
    if (state.order.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '当前没有栈层。';
      order.append(empty);
      return;
    }
    state.order.forEach((id, index) => {
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
      up.title = '上移';
      up.disabled = index === 0;
      up.addEventListener('click', () => moveOrder(index, index - 1));
      const down = document.createElement('button');
      down.type = 'button';
      down.className = 'ghost';
      down.textContent = '↓';
      down.title = '下移';
      down.disabled = index === state.order.length - 1;
      down.addEventListener('click', () => moveOrder(index, index + 1));
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
    run.textContent = '确认导入（需重启）';
    run.disabled = !state.importConfirmed;
    run.addEventListener('click', runImport);
    importPreview.append(confirm, run);
  }

  async function previewImport() {
    const { importPath, importPosition, importAfter } = ensureEls();
    if (!importPath) return;
    const path = importPath.value.trim();
    try {
      const data = await post('/api/world-stack/import/preview', {
        path,
        position: importPosition ? importPosition.value : 'bottom',
        afterId: importAfter ? importAfter.value : '',
        deleteSource: false,
      });
      state.preview = data;
      state.importConfirmed = false;
      renderImportPreview();
      window.SamsaraUI.scrollToTarget?.('world-stack-import-panel');
    } catch (error) {
      setMessage(window.SamsaraUI.authMessage(error, '导入预览失败'), 'error');
    }
  }

  async function runImport() {
    const { importPath, importPosition, importAfter } = ensureEls();
    if (!state.importConfirmed) return;
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
        renderImportPreview();
        setMessage((data.errors && data.errors[0]) || data.error || '导入排队失败', 'error');
        return;
      }
      state.preview = null;
      await load();
      setMessage('已排队：' + data.jobId + '。重启服务器后执行；源目录保持不变。', 'ok');
    } catch (error) {
      setMessage(window.SamsaraUI.authMessage(error, '导入排队失败'), 'error');
    }
  }

  async function previewArchive(id) {
    try {
      const data = await post('/api/world-stack/archive/preview', { id });
      state.archive = data;
      state.archiveConfirmed = false;
      renderArchivePreview();
      window.SamsaraUI.scrollToTarget?.('world-stack-archive-panel');
    } catch (error) {
      setMessage(window.SamsaraUI.authMessage(error, '归档预览失败'), 'error');
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
        ['备份位置', 'world/samsara/stack-archive/' + preview.targetId + '-<job>'],
        ['执行时机', '服务器重启时；先备份再移除'],
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
    run.textContent = '确认归档（需重启）';
    run.disabled = !state.archiveConfirmed;
    run.addEventListener('click', runArchive);
    archivePreview.append(confirm, run);
  }

  async function runArchive() {
    if (!state.archiveConfirmed || !state.archive) return;
    try {
      const data = await post('/api/world-stack/archive', { id: state.archive.targetId });
      if (!data.ok) {
        state.archive = data;
        state.archiveConfirmed = false;
        renderArchivePreview();
        setMessage((data.errors && data.errors[0]) || data.error || '归档排队失败', 'error');
        return;
      }
      state.archive = null;
      await load();
      setMessage('已排队：' + data.jobId + '。重启服务器后归档；备份会保留。', 'ok');
    } catch (error) {
      setMessage(window.SamsaraUI.authMessage(error, '归档排队失败'), 'error');
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

  function renderJobs() {
    const { jobs } = ensureEls();
    if (!jobs) return;
    jobs.replaceChildren();
    const model = state.model || {};
    if (model.pendingImport) {
      jobs.append(jobCard({
        id: model.pendingImport.jobId,
        type: 'import',
        status: 'queued',
        stage: '等待重启',
        progress: 0,
        message: '重启后导入到 ' + (model.pendingImport.position === 'top'
          ? '栈顶 world' : model.pendingImport.targetId)
          + '；源 ' + model.pendingImport.source,
        log: [],
      }));
    }
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
    const list = (model.jobs || []).slice();
    if (model.lastReport && !list.some((job) => job.id === model.lastReport.id)) {
      list.unshift(model.lastReport);
    }
    if (list.length === 0 && !model.pendingImport && !model.pendingArchive
        && !model.pendingPromote) {
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
    add('world-stack-jobs-panel', '任务与结果', e.jobsPanel);
    window.SamsaraUI.buildNav('世界栈导航', items, targets);
  }

  function bind() {
    const e = ensureEls();
    if (!e.list) return;
    if (e.refresh) e.refresh.addEventListener('click', load);
    if (e.orderSave) e.orderSave.addEventListener('click', saveOrder);
    if (e.importPreviewButton) e.importPreviewButton.addEventListener('click', previewImport);
    if (e.importPosition) {
      e.importPosition.addEventListener('change', () => {
        renderAfterSelect();
        state.preview = null;
        renderImportPreview();
      });
    }
    if (e.importPath) {
      e.importPath.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') previewImport();
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
