'use strict';

// PageSpec/FormSpec 配置编辑器（F-168）。所有文本用 textContent 写入。
(function () {
  const UI = window.SamsaraUI;
  const KIT = window.SamsaraUIKit;
  if (!UI) return;

  const $ = (id) => document.getElementById(id);
  const state = {
    pages: [],
    loaded: false,
    current: null,
    filter: '',
    edits: new Map(),
    pending: null,
    navEnabled: true,
    sourceFilter: null,
  };

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function key(page, field) {
    return page.id + '|' + field.path;
  }

  function setMessage(text, kind) {
    const box = $('config-editor-message');
    if (!box) return;
    box.textContent = text || '';
    box.className = 'message' + (kind ? ' ' + kind : '');
  }

  function pageById(id) {
    return state.pages.find((page) => page.id === id) || null;
  }

  function sourcePages() {
    if (!state.sourceFilter) return state.pages;
    return state.pages.filter((page) => (page.source || 'samsara') === state.sourceFilter);
  }

  async function load(preferredId) {
    const result = await UI.api('/api/config/pages');
    state.pages = Array.isArray(result.pages) ? result.pages : [];
    state.modNames = result.modNames || {};
    state.loaded = true;
    state.edits.clear();
    const pages = sourcePages();
    const target = preferredId && pages.some((page) => page.id === preferredId) ? preferredId
      : state.current && pages.some((page) => page.id === state.current.id) ? state.current.id
        : pages.length ? pages[0].id : '';
    state.current = target ? pageById(target) : null;
    render();
  }

  function filteredPages() {
    const query = state.filter.trim().toLowerCase();
    let pages = state.pages;
    if (state.sourceFilter) {
      pages = pages.filter((page) => (page.source || 'samsara') === state.sourceFilter);
    }
    if (!query) return pages;
    return pages.filter((page) => {
      if ((page.title + ' ' + page.fileName + ' ' + page.source + ' ' + page.path)
        .toLowerCase().includes(query)) return true;
      return (page.form && page.form.fields || []).some((field) =>
        (field.path + ' ' + field.group + ' ' + field.description)
          .toLowerCase().includes(query));
    });
  }

  function render() {
    if (state.navEnabled) {
      renderGlobalNav();
    } else {
      const nav = $('global-nav');
      if (nav) {
        nav.replaceChildren();
        nav.hidden = true;
      }
      const shell = document.querySelector('main.app-shell');
      if (shell) shell.classList.add('no-nav');
    }
    renderBody();
    const total = sourcePages().length;
    const count = $('config-editor-count');
    if (count) count.textContent = String(total);
    const summary = $('config-editor-summary');
    if (summary) {
      summary.textContent = state.filter
        ? '匹配 ' + filteredPages().length + ' / ' + total + ' 个页面'
        : total + ' 个页面';
    }
  }

  function renderGlobalNav() {
    if (!UI.buildNav) return;
    const groups = new Map();
    for (const page of filteredPages()) {
      const source = page.source || 'samsara';
      const label = source === 'samsara' ? 'samsara 配置'
        : source === 'server' ? '服务器本体' : '第三方模组';
      if (!groups.has(label)) groups.set(label, []);
      groups.get(label).push(page);
    }
    const navItems = [];
    for (const [label, pages] of groups) {
      if (!pages.length) continue;
      const children = pages.map((page) => ({
        label: page.title || page.fileName,
        count: (page.form && page.form.fields || []).length,
        target: page.id,
        onClick: () => {
          state.current = page;
          state.edits.clear();
          render();
        },
      }));
      navItems.push({
        label,
        count: pages.length,
        target: pages[0].id,
        onClick: () => {
          state.current = pages[0];
          state.edits.clear();
          render();
        },
        children,
      });
    }
    UI.buildNav('配置编辑器', navItems, []);
    if (state.current) UI.setNavActive(state.current.id);
  }

  function renderBody() {
    const body = $('config-editor-body');
    if (!body) return;
    body.replaceChildren();
    const page = state.current;
    if (!page) {
      body.append(el('p', 'muted', '请选择左侧配置页面。'));
      return;
    }
    const head = el('div', 'config-editor-page-head');
    head.append(el('h3', '', page.title || page.fileName));
    head.append(el('span', 'config-editor-badge', page.format || 'unknown'));
    head.append(el('span', 'config-editor-badge',
      page.source === 'samsara' ? 'samsara' : page.source === 'server' ? '服务器本体' : '第三方模组'));
    if (page.requiresConfirmation) head.append(el('span', 'config-editor-badge', '需预览确认'));
    if (!page.writable) head.append(el('span', 'config-editor-badge', '只读'));

    const actions = el('div', 'config-editor-actions');
    if (page.customWidget) {
      const open = el('button', 'ghost', '打开专用编辑器');
      open.type = 'button';
      open.addEventListener('click', () => UI.setActiveTab(page.customWidget));
      actions.append(open);
    }
    if (page.writable) {
      const save = el('button', 'primary', '预览并保存');
      save.type = 'button';
      save.addEventListener('click', () => preview());
      actions.append(save);
    }
    const refresh = el('button', 'ghost', '刷新');
    refresh.type = 'button';
    refresh.addEventListener('click', () => load(page.id).catch(showError));
    actions.append(refresh);
    head.append(actions);
    body.append(head);

    const meta = el('p', 'muted small',
      (page.path || '') + (page.revision ? ' · revision ' + page.revision.slice(0, 12) : ''));
    body.append(meta);

    if (page.readOnlyReason) {
      body.append(el('div', 'config-editor-readonly', page.readOnlyReason));
    }
    if (page.customWidget || !page.writable) {
      // 专用编辑器 / 只读页面不在通用表单里重复渲染字段，避免把对象值显示成 [object Object]。
      return;
    }
    if (!page.form || !(page.form.fields || []).length) {
      if (page.readOnlyReason) return;
      body.append(el('p', 'muted small', '这个文件没有可编辑字段。'));
      return;
    }

    const query = state.filter.trim().toLowerCase();
    const groups = new Map();
    for (const group of page.form.groups || []) groups.set(group.id, []);
    for (const field of page.form.fields || []) {
      if (!groups.has(field.group)) groups.set(field.group, []);
      groups.get(field.group).push(field);
    }
    for (const [groupId, fields] of groups) {
      const visible = fields.filter((field) => !query
        || (field.path + ' ' + field.description + ' ' + field.label + ' ' + groupId)
          .toLowerCase().includes(query));
      if (!visible.length) continue;
      const details = el('details', 'config-editor-group');
      details.open = true;
      const summary = el('summary', '', groupId + ' · ' + visible.length);
      details.append(summary);
      const content = el('div', 'group-body');
      for (const field of visible) content.append(renderField(page, field));
      details.append(content);
      body.append(details);
    }
  }

  function renderField(page, field) {
    const row = el('div', 'entry');
    row.dataset.path = field.path;
    if (field.restart) row.classList.add('restart');
    if (field.secret) row.classList.add('entry-wide');

    const label = el('div', 'entry-key');
    const name = el('span', 'entry-path', field.label || field.path);
    label.append(name);
    if (field.restart) label.append(el('span', 'badge restart', '需重启'));
    if (field.secret) label.append(el('span', 'badge', '敏感'));
    if (field.readOnly) label.append(el('span', 'badge', '只读'));
    row.append(label);

    const description = el('div', 'entry-desc', field.description || '');
    row.append(description);

    const control = el('div', 'entry-control');
    control.append(buildControl(page, field));
    const error = el('div', 'entry-error');
    error.dataset.path = field.path;
    control.append(error);
    row.append(control);
    return row;
  }

  function currentValue(page, field) {
    const edit = state.edits.get(key(page, field));
    if (edit !== undefined) return edit;
    if (Object.prototype.hasOwnProperty.call(page.values || {}, field.path)) {
      return page.values[field.path];
    }
    return field.defaultValue;
  }

  function setEdit(page, field, value) {
    state.edits.set(key(page, field), value);
    const error = document.querySelector('#config-editor-body .entry-error[data-path="'
      + cssEscape(field.path) + '"]');
    if (error) error.textContent = '';
  }

  function buildControl(page, field) {
    const value = currentValue(page, field);
    if (field.readOnly || !page.writable) {
      const text = value != null && typeof value === 'object' ? pretty(value)
        : (value == null ? '' : String(value));
      return el('div', 'entry-readonly', text);
    }
    if (field.widget === 'chat' && window.SamsaraChatText) {
      return window.SamsaraChatText.create({
        value: value == null ? '' : String(value),
        rows: 3,
        onChange: (next) => setEdit(page, field, next),
      }).element;
    }
    switch (field.type) {
      case 'bool': {
        if (KIT && KIT.control) {
          return KIT.control({ type: 'bool', onLabel: '启用', offLabel: '关闭' },
            value === true || String(value) === 'true',
            (next) => setEdit(page, field, next));
        }
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.checked = value === true || String(value) === 'true';
        input.addEventListener('change', () => setEdit(page, field, input.checked));
        return input;
      }
      case 'enum': {
        const choices = field.choices || [];
        const select = document.createElement('select');
        for (const choice of choices) {
          const option = document.createElement('option');
          option.value = choice.value;
          option.textContent = choice.label || choice.value;
          select.append(option);
        }
        select.value = String(value == null ? '' : value);
        select.addEventListener('change', () => setEdit(page, field, select.value));
        return select;
      }
      case 'int':
      case 'number': {
        const input = document.createElement('input');
        input.type = 'number';
        if (field.min != null) input.min = field.min;
        if (field.max != null) input.max = field.max;
        input.step = field.step != null ? field.step : field.type === 'int' ? '1' : 'any';
        input.value = value == null ? '' : String(value);
        input.addEventListener('input', () => setEdit(page, field, input.value));
        return input;
      }
      case 'json':
      case 'multiline': {
        const area = document.createElement('textarea');
        area.rows = field.type === 'json' ? 8 : 4;
        area.spellcheck = false;
        area.value = field.type === 'json' ? pretty(value) : (value == null ? '' : String(value));
        area.addEventListener('input', () => setEdit(page, field, area.value));
        return area;
      }
      case 'password': {
        const input = document.createElement('input');
        input.type = 'password';
        input.autocomplete = 'new-password';
        input.placeholder = field.placeholder || '留空表示不修改';
        input.value = '';
        input.addEventListener('input', () => setEdit(page, field, input.value));
        return input;
      }
      case 'url': {
        const input = document.createElement('input');
        input.type = 'url';
        input.value = value == null ? '' : String(value);
        input.addEventListener('input', () => setEdit(page, field, input.value));
        return input;
      }
      default: {
        const input = document.createElement('input');
        input.type = 'text';
        input.value = value == null ? '' : String(value);
        input.addEventListener('input', () => setEdit(page, field, input.value));
        return input;
      }
    }
  }

  function pretty(value) {
    if (value == null || value === '') return '';
    try {
      return JSON.stringify(value, null, 2);
    } catch (error) {
      return String(value);
    }
  }

  function collectValues(page) {
    const values = {};
    const errors = {};
    for (const field of page.form.fields || []) {
      const raw = state.edits.get(key(page, field));
      if (raw === undefined) continue;
      if (field.secret && String(raw).trim() === '') continue;
      if (field.type === 'json') {
        const text = String(raw).trim();
        if (!text) {
          values[field.path] = null;
          continue;
        }
        try {
          values[field.path] = JSON.parse(text);
        } catch (error) {
          errors[field.path] = 'JSON 格式错误：' + error.message;
        }
      } else if (field.type === 'int') {
        values[field.path] = raw === '' ? null : Number.parseInt(raw, 10);
      } else if (field.type === 'number') {
        values[field.path] = raw === '' ? null : Number.parseFloat(raw);
      } else {
        values[field.path] = raw;
      }
    }
    return { values, errors };
  }

  function clearErrors() {
    document.querySelectorAll('#config-editor-body .entry-error').forEach((node) => {
      node.textContent = '';
    });
  }

  function showFieldErrors(fields) {
    clearErrors();
    for (const [path, message] of Object.entries(fields || {})) {
      const error = document.querySelector('#config-editor-body .entry-error[data-path="'
        + cssEscape(path) + '"]');
      if (error) error.textContent = message;
      else setMessage(path + '：' + message, 'error');
    }
  }

  async function preview() {
    const page = state.current;
    if (!page) return;
    setMessage('正在校验…');
    const collected = collectValues(page);
    if (Object.keys(collected.errors).length) {
      showFieldErrors(collected.errors);
      setMessage('请先修正字段错误。', 'error');
      return;
    }
    try {
      const result = await UI.api('/api/config/preview', {
        method: 'POST',
        body: JSON.stringify({ file: page.id, values: collected.values }),
      });
      if (!result.ok) {
        showFieldErrors(result.fields);
        setMessage(result.error || '预览失败', 'error');
        return;
      }
      state.pending = {
        page,
        values: collected.values,
        revision: result.revision || page.revision || '',
      };
      openPreview(result);
    } catch (error) {
      setMessage(error.message || '预览失败', 'error');
    }
  }

  function openPreview(result) {
    const dialog = $('config-editor-preview-dialog');
    const sub = $('config-editor-preview-sub');
    const diff = $('config-editor-preview-diff');
    const errors = $('config-editor-preview-errors');
    if (!dialog || !sub || !diff) return;
    sub.textContent = (result.file || '') + ' · 将修改 ' + (result.changed || 0)
      + ' 项' + (result.requiresConfirmation ? ' · 确认后写入并备份' : '');
    diff.textContent = result.diff || '没有字段变化。';
    if (errors) {
      errors.hidden = true;
      errors.textContent = '';
    }
    if (typeof dialog.showModal === 'function') dialog.showModal();
  }

  async function confirmSave() {
    const pending = state.pending;
    const dialog = $('config-editor-preview-dialog');
    if (!pending) return;
    const page = pending.page;
    try {
      const result = await UI.api('/api/config', {
        method: 'PUT',
        body: JSON.stringify({
          file: page.id,
          source: page.source,
          values: pending.values,
          revision: pending.revision,
          confirm: true,
        }),
      });
      if (!result.ok) {
        showFieldErrors(result.fields);
        const errors = $('config-editor-preview-errors');
        if (errors) {
          errors.hidden = false;
          errors.textContent = result.error || '保存失败';
        }
        return;
      }
      state.pending = null;
      state.edits.clear();
      if (dialog && typeof dialog.close === 'function') dialog.close();
      setMessage(result.message || '已保存', 'ok');
      await load(page.id);
    } catch (error) {
      const body = error && error.body ? error.body : {};
      showFieldErrors(body.fields);
      const errors = $('config-editor-preview-errors');
      if (errors) {
        errors.hidden = false;
        errors.textContent = body.error || error.message || '保存失败';
      }
    }
  }

  async function reloadPage() {
    const page = state.current;
    try {
      await UI.api('/api/reload', { method: 'POST' });
    } catch (error) {
      // 第三方/服务器配置不依赖 samsara 重载；继续刷新页面即可。
    }
    await load(page && page.id);
    setMessage('已重新读取配置。', 'ok');
  }

  function showError(error) {
    setMessage(error && error.message ? error.message : '请求失败', 'error');
  }

  function cssEscape(value) {
    if (window.CSS && CSS.escape) return CSS.escape(String(value));
    return String(value).replace(/["\\]/g, '\\$&');
  }

  function setNavEnabled(enabled) {
    state.navEnabled = enabled !== false;
  }

  /**
   * 配置来源过滤：samsara / server / third-party / null。
   * 保证 /samsara/config/editor 只显示 samsara 自有配置，其他模组页只显示 third-party。
   */
  function setSourceFilter(source) {
    const next = source === 'samsara' || source === 'server' || source === 'third-party'
      ? source : null;
    if (state.sourceFilter === next) return;
    state.sourceFilter = next;
    if (!state.loaded) return;
    const pages = sourcePages();
    if (state.current && !pages.some((page) => page.id === state.current.id)) {
      state.current = pages.length ? pages[0] : null;
      state.edits.clear();
    }
    render();
  }

  function openPage(id) {
    const page = pageById(id);
    if (!page) return false;
    state.current = page;
    state.edits.clear();
    render();
    return true;
  }

  function activate() {
    state.navEnabled = true;
    if (!state.loaded) {
      load().catch(showError);
      return;
    }
    // 切回本页时其他页面可能已重建 #global-nav，这里必须重新渲染导航与正文。
    render();
  }

  const search = $('config-editor-search');
  if (search) {
    search.addEventListener('input', () => {
      state.filter = search.value || '';
      render();
    });
  }
  const cancel = $('config-editor-preview-cancel');
  if (cancel) cancel.addEventListener('click', () => {
    state.pending = null;
    const dialog = $('config-editor-preview-dialog');
    if (dialog && typeof dialog.close === 'function') dialog.close();
  });
  const confirm = $('config-editor-preview-confirm');
  if (confirm) confirm.addEventListener('click', confirmSave);

  UI.onConnect(() => load().catch(showError));
  if (UI.isActiveTab && UI.isActiveTab('config-editor')) activate();
  window.SamsaraConfigEditor = {
    activate,
    save: preview,
    reload: reloadPage,
    load,
    openPage,
    setNavEnabled,
    setSourceFilter,
    getSourceFilter: () => state.sourceFilter,
  };
})();
