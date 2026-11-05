'use strict';

// Web 公告编辑器（F-168 之后的公告系统）。文本只用 textContent 写入。
(function () {
  const UI = window.SamsaraUI;
  if (!UI) return;

  const $ = (id) => document.getElementById(id);
  const state = {
    model: null,
    list: [],
    selected: null,
    loaded: false,
    dirty: false,
  };

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function message(text, kind) {
    const box = $('announcements-message');
    if (!box) return;
    box.textContent = text || '';
    box.className = 'message' + (kind ? ' ' + kind : '');
  }

  function selected() {
    return state.list[state.selected] || null;
  }

  async function load() {
    const model = await UI.api('/api/announcements');
    state.model = model;
    state.list = Array.isArray(model.announcements) ? model.announcements : [];
    state.loaded = true;
    state.dirty = false;
    if (state.selected == null || state.selected >= state.list.length) state.selected = 0;
    render();
  }

  function render() {
    renderList();
    renderEditor();
    const count = $('announcements-count');
    if (count) count.textContent = String(state.list.length);
    const summary = $('announcements-summary');
    if (summary) summary.textContent = state.list.length + ' 条公告'
      + (state.dirty ? ' · 有未保存修改' : '');
  }

  function renderList() {
    const root = $('announcements-list');
    if (!root) return;
    root.replaceChildren();
    state.list.forEach((item, index) => {
      const button = el('button', 'announcement-item');
      button.type = 'button';
      button.classList.toggle('active', index === state.selected);
      button.append(el('span', 'announcement-name', item.name || item.id || '未命名'));
      const badges = el('span', 'announcement-badges');
      badges.append(el('span', 'config-editor-badge', item.trigger || 'manual'));
      if (!item.enabled) badges.append(el('span', 'config-editor-badge', '停用'));
      if (item.bossbar) badges.append(el('span', 'config-editor-badge', 'BossBar'));
      button.append(badges);
      button.addEventListener('click', () => {
        state.selected = index;
        render();
      });
      root.append(button);
    });
    if (!state.list.length) {
      root.append(el('p', 'muted small', '还没有公告，点“+ 新建”。'));
    }
  }

  function renderEditor() {
    const root = $('announcements-editor');
    if (!root) return;
    root.replaceChildren();
    const item = selected();
    if (!item) {
      root.append(el('p', 'muted', '请选择或新建一条公告。'));
      return;
    }
    const head = el('div', 'config-editor-page-head');
    head.append(el('h3', '', item.name || '未命名公告'));
    head.append(el('span', 'config-editor-badge', item.trigger || 'manual'));
    if (item.enabled) head.append(el('span', 'config-editor-badge', '启用'));
    const actions = el('div', 'config-editor-actions');
    actions.append(actionButton('保存', 'primary', () => save()));
    actions.append(actionButton('预览', 'ghost', () => preview()));
    actions.append(actionButton('发送', 'ghost', () => send()));
    actions.append(actionButton('复制', 'ghost', () => duplicate()));
    actions.append(actionButton('删除', 'ghost danger', () => remove()));
    head.append(actions);
    root.append(head);

    const form = el('div', 'announcement-form');
    form.append(section('基础', [
      row('名称', textInput(item, 'name')),
      row('ID', readonlyText(item.id)),
      row('启用', checkbox(item, 'enabled')),
      row('触发', selectInput(item, 'trigger', state.model?.triggers || ['manual', 'interval', 'schedule', 'join'])),
      row('间隔（秒）', numberInput(item, 'intervalSeconds', 10, 86400)),
      row('计划时间', textInput(item, 'startAt', 'yyyy-MM-dd HH:mm')),
      row('优先级', numberInput(item, 'priority', -100, 100)),
    ]));
    form.append(section('目标', [
      row('范围', selectInput(item, 'target', state.model?.targets || ['all', 'permission', 'world', 'dimension', 'players'])),
      row('目标值', textInput(item, 'targetValue', '权限路径 / 维度 / 玩家名，逗号分隔')),
    ]));
    form.append(section('展示', [
      row('聊天', checkbox(item, 'chat')),
      row('聊天文本', textarea(item, 'chatText', 4)),
      row('ActionBar', checkbox(item, 'actionbar')),
      row('ActionBar 文本', textInput(item, 'actionbarText')),
      row('Title', checkbox(item, 'title')),
      row('标题', textInput(item, 'titleText')),
      row('副标题', textInput(item, 'subtitleText')),
      row('淡入/停留/淡出（tick）', tripleNumber(item, 'fadeInTicks', 'stayTicks', 'fadeOutTicks')),
      row('BossBar', checkbox(item, 'bossbar')),
      row('BossBar 文本', textInput(item, 'bossbarText')),
      row('BossBar 颜色', selectInput(item, 'bossbarColor', state.model?.colors || [])),
      row('BossBar 样式', selectInput(item, 'bossbarOverlay', state.model?.overlays || [])),
      row('BossBar 时长（秒）', numberInput(item, 'durationSeconds', 1, 3600)),
      row('声音', checkbox(item, 'sound')),
      row('声音 ID', textInput(item, 'soundId')),
      row('音量 / 音高', pairNumber(item, 'soundVolume', 'soundPitch', 0, 0.5, 1, 2, 0.1)),
    ]));
    form.append(section('交互', [
      row('点击动作', selectInput(item, 'clickAction', state.model?.clickActions || ['none', 'run', 'suggest', 'open_url', 'copy'])),
      row('点击值', textInput(item, 'clickValue', '命令 / URL / 文本')),
      row('点击权限', textInput(item, 'clickPermission', 'run 事件留空要求 OP')),
      row('悬浮文本', textInput(item, 'hoverText')),
    ]));
    root.append(form);

    const previewBox = el('div', 'announcement-preview');
    previewBox.id = 'announcement-preview';
    previewBox.append(el('div', 'announcement-preview-title', '预览'));
    previewBox.append(el('div', 'announcement-preview-body muted small', '点“预览”查看变量替换后的效果。'));
    root.append(previewBox);
  }

  function section(title, rows) {
    const details = el('details', 'config-editor-group');
    details.open = true;
    details.append(el('summary', '', title));
    const body = el('div', 'group-body');
    for (const node of rows) body.append(node);
    details.append(body);
    return details;
  }

  function row(label, control) {
    const node = el('div', 'entry announcement-row');
    node.append(el('div', 'entry-key', label));
    const wrap = el('div', 'entry-control');
    wrap.append(control);
    node.append(wrap);
    return node;
  }

  function actionButton(text, className, handler) {
    const button = el('button', className, text);
    button.type = 'button';
    button.addEventListener('click', handler);
    return button;
  }

  function change(item, key, value) {
    item[key] = value;
    state.dirty = true;
    const summary = $('announcements-summary');
    if (summary) summary.textContent = state.list.length + ' 条公告 · 有未保存修改';
    if (key === 'name') renderList();
  }

  function textInput(item, key, placeholder) {
    const input = el('input');
    input.type = 'text';
    input.value = item[key] == null ? '' : String(item[key]);
    if (placeholder) input.placeholder = placeholder;
    input.addEventListener('input', () => change(item, key, input.value));
    return input;
  }

  function textarea(item, key, rows) {
    const area = el('textarea');
    area.rows = rows || 4;
    area.spellcheck = false;
    area.value = item[key] == null ? '' : String(item[key]);
    area.addEventListener('input', () => change(item, key, area.value));
    return area;
  }

  function readonlyText(value) {
    const input = el('input');
    input.type = 'text';
    input.value = value == null ? '' : String(value);
    input.readOnly = true;
    return input;
  }

  function numberInput(item, key, min, max) {
    const input = el('input');
    input.type = 'number';
    if (min != null) input.min = min;
    if (max != null) input.max = max;
    input.value = item[key] == null ? '' : String(item[key]);
    input.addEventListener('input', () => change(item, key, Number(input.value)));
    return input;
  }

  function checkbox(item, key) {
    const input = el('input');
    input.type = 'checkbox';
    input.checked = item[key] === true;
    input.addEventListener('change', () => change(item, key, input.checked));
    return input;
  }

  function selectInput(item, key, choices) {
    const select = el('select');
    for (const choice of choices || []) {
      const option = el('option', null, String(choice));
      option.value = String(choice);
      select.append(option);
    }
    select.value = String(item[key] == null ? '' : item[key]);
    select.addEventListener('change', () => change(item, key, select.value));
    return select;
  }

  function tripleNumber(item, a, b, c) {
    const wrap = el('div', 'inline-numbers');
    for (const key of [a, b, c]) {
      const input = el('input');
      input.type = 'number';
      input.min = '0';
      input.value = item[key] == null ? '' : String(item[key]);
      input.title = key;
      input.addEventListener('input', () => change(item, key, Number(input.value)));
      wrap.append(input);
    }
    return wrap;
  }

  function pairNumber(item, a, b, minA, minB, maxA, maxB, step) {
    const wrap = el('div', 'inline-numbers');
    for (const [key, min, max] of [[a, minA, maxA], [b, minB, maxB]]) {
      const input = el('input');
      input.type = 'number';
      input.min = min;
      input.max = max;
      input.step = step;
      input.value = item[key] == null ? '' : String(item[key]);
      input.title = key;
      input.addEventListener('input', () => change(item, key, Number(input.value)));
      wrap.append(input);
    }
    return wrap;
  }

  function updateSelectedPreview(preview) {
    const box = $('announcement-preview');
    if (!box) return;
    box.replaceChildren();
    box.append(el('div', 'announcement-preview-title',
      '预览 · ' + (preview.player || '') + ' · ' + ((preview.channels || []).join(' / ') || '无展示')));
    const body = el('div', 'announcement-preview-body');
    if ((preview.chat || []).length) body.append(el('div', 'preview-label', '聊天'));
    for (const line of preview.chat || []) body.append(el('div', 'preview-chat-line', line));
    if (preview.actionbar) body.append(el('div', 'preview-actionbar', 'ActionBar：' + preview.actionbar));
    if (preview.title) body.append(el('div', 'preview-title', 'Title：' + preview.title));
    if (preview.subtitle) body.append(el('div', 'preview-subtitle', 'Subtitle：' + preview.subtitle));
    if (preview.bossbar) body.append(el('div', 'preview-bossbar', 'BossBar：' + preview.bossbar));
    if (preview.sound) body.append(el('div', 'preview-sound', '声音：' + preview.sound));
    box.append(body);
  }

  async function preview() {
    const item = selected();
    if (!item) return;
    try {
      const result = await UI.api('/api/announcements/preview', {
        method: 'POST',
        body: JSON.stringify({ announcement: item }),
      });
      if (!result.ok) {
        message((result.errors || []).join('；') || result.error || '预览失败', 'error');
        return;
      }
      updateSelectedPreview(result);
      message('预览已更新', 'ok');
    } catch (error) {
      message(error.message || '预览失败', 'error');
    }
  }

  async function send() {
    const item = selected();
    if (!item) return;
    try {
      const first = await UI.api('/api/announcements/send', {
        method: 'POST',
        body: JSON.stringify({ announcement: item, dryRun: true }),
      });
      if (!first.ok) {
        message((first.errors || []).join('；') || first.error || '发送失败', 'error');
        return;
      }
      updateSelectedPreview(first.preview || {});
      const ok = window.confirm(first.message + '\n\n确认推送给 ' + first.recipients
        + ' 名玩家？');
      if (!ok) return;
      const result = await UI.api('/api/announcements/send', {
        method: 'POST',
        body: JSON.stringify({ announcement: item, dryRun: false, confirm: true }),
      });
      message(result.message || (result.ok ? '已发送' : '发送失败'), result.ok ? 'ok' : 'error');
    } catch (error) {
      message(error.message || '发送失败', 'error');
    }
  }

  async function save() {
    try {
      const result = await UI.api('/api/announcements', {
        method: 'PUT',
        body: JSON.stringify({ announcements: state.list }),
      });
      if (!result.ok) {
        message((result.errors || []).join('；') || result.error || '保存失败', 'error');
        return;
      }
      state.model = result;
      state.list = result.announcements || state.list;
      state.dirty = false;
      render();
      message(result.message || '公告已保存', 'ok');
    } catch (error) {
      message(error.message || '保存失败', 'error');
    }
  }

  function duplicate() {
    const item = selected();
    if (!item) return;
    const copy = JSON.parse(JSON.stringify(item));
    copy.id = '';
    copy.name = (item.name || '公告') + ' 副本';
    state.list.splice(state.selected + 1, 0, copy);
    state.selected++;
    state.dirty = true;
    render();
  }

  function remove() {
    const item = selected();
    if (!item) return;
    if (!window.confirm('删除公告「' + (item.name || item.id) + '」？')) return;
    state.list.splice(state.selected, 1);
    state.selected = Math.max(0, state.selected - 1);
    state.dirty = true;
    render();
  }

  function add() {
    const item = {
      id: '', name: '新公告', enabled: true, trigger: 'manual', intervalSeconds: 300,
      startAt: '', target: 'all', targetValue: '', chat: true,
      chatText: '&e[公告] &f{player}，你好！', actionbar: false, actionbarText: '',
      title: false, titleText: '', subtitleText: '', bossbar: false, bossbarText: '',
      bossbarColor: 'BLUE', bossbarOverlay: 'PROGRESS', durationSeconds: 8,
      fadeInTicks: 10, stayTicks: 70, fadeOutTicks: 20, sound: false,
      soundId: 'minecraft:ui.toast.in', soundVolume: 1.0, soundPitch: 1.0,
      clickAction: 'none', clickValue: '', clickPermission: '', hoverText: '', priority: 0,
    };
    state.list.push(item);
    state.selected = state.list.length - 1;
    state.dirty = true;
    render();
  }

  async function reload() {
    await load();
    message('已重新读取公告。', 'ok');
  }

  function activate() {
    if (!state.loaded) load().catch((error) => message(error.message || '加载失败', 'error'));
  }

  const addButton = $('announcements-add');
  if (addButton) addButton.addEventListener('click', add);
  const refreshButton = $('announcements-refresh');
  if (refreshButton) refreshButton.addEventListener('click', reload);

  UI.onConnect(() => load().catch((error) => message(error.message || '加载失败', 'error')));
  if (UI.isActiveTab && UI.isActiveTab('announcements')) activate();
  window.SamsaraAnnouncements = {
    activate,
    load,
    save,
    reload,
    preview,
    send,
  };
})();
