'use strict';

// 输出格式编辑：单块内容可编辑画布。
// 文字是可编辑文本；变量/按钮是不可编辑的原子标签；按钮标签可改。
// 续行前缀由服务端自动添加，画布中用浅色虚拟前缀提示，不写入模板。

const OUTPUT_COLORS = {
  black: '#000000', dark_blue: '#0000aa', dark_green: '#00aa00', dark_aqua: '#00aaaa',
  dark_red: '#aa0000', dark_purple: '#aa00aa', gold: '#ffaa00', gray: '#aaaaaa',
  dark_gray: '#555555', blue: '#5555ff', green: '#55ff55', aqua: '#55ffff',
  red: '#ff5555', light_purple: '#ff55ff', yellow: '#ffff55', white: '#ffffff',
};

const O = {
  model: null,
  key: '',
  parts: [],
  previewTimer: null,
  dragElement: null,
  dragPayload: null,
  filling: false,
  undoStack: [],
  redoStack: [],
};

const $o = (id) => document.getElementById(id);

function outputMessage(text, kind) {
  const el = $o('output-message');
  if (!el) return;
  el.textContent = text || '';
  el.className = 'message' + (kind ? ' ' + kind : '');
}

function selectedMessage() {
  if (!O.model) return null;
  return O.model.messages.find((message) => message.key === O.key) || null;
}

function cloneParts(parts) {
  return JSON.parse(JSON.stringify(parts || []));
}

async function loadOutput() {
  try {
    O.model = await window.SamsaraUI.api('/api/output');
    const exists = O.model.messages.some((message) => message.key === O.key);
    if (!exists) O.key = O.model.messages.length ? O.model.messages[0].key : '';
    selectOutputMessage(O.key, true);
    outputMessage('已读取 ' + O.model.messages.length + ' 条输出消息', 'ok');
    refreshCount();
    return true;
  } catch (e) {
    outputMessage(window.SamsaraUI.authMessage(e, '读取输出格式失败'), 'error');
    return false;
  }
}

function ensureOutputLoaded() {
  if (!O.model && window.SamsaraUI.getToken()) loadOutput();
}

function refreshCount() {
  if (!O.model) return;
  window.SamsaraUI.setTabCount('output', O.model.messages.length);
  if (window.SamsaraUI.isActiveTab('output')) renderOutputNav();
}

function renderOutputNav() {
  if (!O.model) return;
  const query = $o('output-search').value.trim().toLowerCase();
  const groups = new Map();
  let shown = 0;
  for (const message of O.model.messages) {
    const haystack = (message.key + ' ' + message.group + ' ' + message.description).toLowerCase();
    if (query && !haystack.includes(query)) continue;
    shown++;
    if (!groups.has(message.group)) groups.set(message.group, []);
    groups.get(message.group).push(message);
  }
  const navItems = [];
  for (const [group, messages] of groups) {
    navItems.push({
      label: group,
      count: messages.length,
      onClick: () => {
        if (messages.length) selectOutputMessage(messages[0].key);
      },
      children: messages.map((message) => ({
        label: message.key,
        target: 'out-msg-' + encodeURIComponent(message.key),
        onClick: () => selectOutputMessage(message.key),
      })),
    });
  }
  window.SamsaraUI.buildNav('输出消息 ' + shown + ' / ' + O.model.messages.length, navItems, []);
  window.SamsaraUI.setNavActive('out-msg-' + encodeURIComponent(O.key));
}

function selectOutputMessage(key, silent) {
  O.key = key;
  const message = selectedMessage();
  O.parts = message ? cloneParts(message.parts) : [];
  O.undoStack = [];
  O.redoStack = [];
  if (window.SamsaraUI.isActiveTab('output')) renderOutputNav();
  renderOutputEditor();
  scheduleOutputPreview();
  if (!silent) outputMessage('');
}

function renderOutputEditor() {
  const message = selectedMessage();
  const editor = $o('output-editor');
  const disabled = $o('output-disabled');
  const palette = $o('output-palette');
  const buttons = $o('output-buttons');
  if (!editor) return;
  $o('output-errors').replaceChildren();
  if (!message) {
    disabled.hidden = true;
    editor.hidden = true;
    palette.hidden = true;
    buttons.hidden = true;
    $o('output-meta').textContent = '';
    return;
  }
  $o('output-meta').textContent = message.key + ' · ' + message.description
      + ' · 变量 ' + (message.variables || []).length + ' · 按钮 ' + (message.buttons || []).length;
  const editable = message.editable !== false;
  disabled.hidden = editable;
  editor.hidden = !editable;
  palette.hidden = !editable;
  buttons.hidden = !editable;
  if (!editable) {
    disabled.textContent = message.disabledReason || '当前消息不可编辑';
    return;
  }
  fillEditor(O.parts);
  renderOutputPalette(message);
  renderOutputButtons(message);
  updateEditorCounts();
  showClientErrors();
}

// ---- 画布渲染 ----

function fillEditor(parts) {
  const editor = $o('output-editor');
  if (!editor) return;
  O.filling = true;
  editor.replaceChildren();
  renderParts(parts);
  ensureTail();
  O.filling = false;
}

function renderParts(parts) {
  const editor = $o('output-editor');
  let lineIndex = 0;
  let atLineStart = true;

  const ensureVirtualPrefix = () => {
    if (atLineStart && lineIndex > 0) {
      editor.append(createVirtualPrefix());
      atLineStart = false;
    }
  };

  const appendText = (text) => {
    if (!text) return;
    let start = 0;
    for (let i = 0; i < text.length; i++) {
      if (text.charAt(i) !== '\n') continue;
      if (i > start) {
        ensureVirtualPrefix();
        editor.append(document.createTextNode(text.slice(start, i)));
        atLineStart = false;
      }
      editor.append(document.createTextNode('\n'));
      lineIndex++;
      atLineStart = true;
      start = i + 1;
    }
    if (start < text.length) {
      ensureVirtualPrefix();
      editor.append(document.createTextNode(text.slice(start)));
      atLineStart = false;
    }
  };

  for (const part of parts) {
    if (part.text !== undefined) {
      appendText(part.text);
    } else if (part.prefix !== undefined) {
      ensureVirtualPrefix();
      editor.append(createPrefixNode(part.prefix));
      atLineStart = false;
    } else if (part.var !== undefined) {
      ensureVirtualPrefix();
      editor.append(createVarNode(part.var));
      atLineStart = false;
    } else if (part.button !== undefined) {
      ensureVirtualPrefix();
      editor.append(createButtonNode(part));
      atLineStart = false;
    }
  }
}

function createPrefixNode(text) {
  const node = document.createElement('span');
  node.className = 'rt-prefix-text';
  node.dataset.prefix = '1';
  node.contentEditable = 'true';
  node.spellcheck = false;
  node.textContent = text;
  node.title = '首行前缀（纯文本，可直接编辑）';
  return node;
}

function createVarNode(key) {
  const variable = (selectedMessage()?.variables || []).find((item) => item.key === key);
  const chip = document.createElement('span');
  chip.className = 'rt-chip rt-var';
  chip.dataset.var = key;
  chip.contentEditable = 'false';
  chip.textContent = '{' + key + '}';
  chip.title = variable
    ? variable.label + ' · 样例：' + variable.sample + (variable.required ? ' · 必需' : '')
    : '未知变量：' + key;
  if (!variable) chip.classList.add('rt-unknown');
  chip.append(chipDeleteButton(chip));
  makeChipDraggable(chip);
  return chip;
}

function createButtonNode(part) {
  const message = selectedMessage();
  const definition = (message?.buttons || []).find((item) => item.key === part.button);
  const label = part.label || (definition ? definition.label : part.button);
  const chip = document.createElement('span');
  chip.className = 'rt-chip rt-button';
  chip.dataset.button = part.button;
  chip.contentEditable = 'false';
  chip.title = definition ? definition.command + (definition.required ? ' · 必需' : '') : '未知按钮：' + part.button;
  chip.append(document.createTextNode('['));
  const labelNode = document.createElement('span');
  labelNode.className = 'rt-button-label';
  labelNode.contentEditable = 'true';
  labelNode.spellcheck = false;
  labelNode.textContent = label;
  labelNode.title = '按钮标签可编辑；按钮命令固定';
  chip.append(labelNode, document.createTextNode(']'), chipDeleteButton(chip));
  makeChipDraggable(chip);
  return chip;
}

function createVirtualPrefix() {
  const span = document.createElement('span');
  span.className = 'rt-cont-prefix';
  span.dataset.virtual = '1';
  span.contentEditable = 'false';
  span.textContent = '  ▸ ';
  span.title = '续行前缀由服务端自动添加，不写入模板';
  span.addEventListener('mousedown', (event) => {
    event.preventDefault();
    $o('output-editor')?.focus();
    setCaretAfter(span);
  });
  return span;
}

function ensureTail() {
  const editor = $o('output-editor');
  if (!editor) return;
  const last = editor.lastChild;
  if (!last || last.nodeType !== Node.TEXT_NODE) {
    editor.append(document.createTextNode('\u200B'));
  }
}

function chipDeleteButton(chip) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'rt-x';
  button.textContent = '×';
  button.title = '删除这个标签';
  button.tabIndex = -1;
  button.addEventListener('mousedown', (event) => event.preventDefault());
  button.addEventListener('click', (event) => {
    event.preventDefault();
    event.stopPropagation();
    recordHistory();
    chip.remove();
    ensureTail();
    syncPartsFromDom();
    scheduleOutputPreview();
  });
  return button;
}

function makeChipDraggable(chip) {
  chip.draggable = true;
  chip.addEventListener('dragstart', (event) => {
    O.dragElement = chip;
    O.dragPayload = null;
    event.dataTransfer.effectAllowed = 'move';
    try { event.dataTransfer.setData('text/plain', 'samsara-part'); } catch (e) { /* ignore */ }
  });
  chip.addEventListener('dragend', () => {
    O.dragElement = null;
    O.dragPayload = null;
  });
}

// ---- 面板 ----

function renderOutputPalette(message) {
  const container = $o('output-palette');
  if (!container) return;
  container.replaceChildren();
  for (const variable of message.variables || []) {
    const chip = document.createElement('span');
    chip.className = 'chip chip-var palette-chip';
    chip.textContent = '{' + variable.key + '}';
    chip.title = variable.label + (variable.required ? ' · 必需' : ' · 可选') + ' · 样例：' + variable.sample;
    chip.draggable = true;
    chip.addEventListener('dragstart', (event) => {
      O.dragElement = null;
      O.dragPayload = { kind: 'var', key: variable.key };
      event.dataTransfer.effectAllowed = 'copy';
      try { event.dataTransfer.setData('text/plain', 'samsara-var'); } catch (e) { /* ignore */ }
    });
    chip.addEventListener('click', () => insertPartAtSelection({ var: variable.key }));
    container.append(chip);
  }
}

function renderOutputButtons(message) {
  const container = $o('output-buttons');
  if (!container) return;
  container.replaceChildren();
  for (const button of message.buttons || []) {
    const chip = document.createElement('span');
    chip.className = 'chip chip-button palette-chip';
    chip.textContent = '[' + button.label + ']';
    chip.title = button.command + ' · ' + (button.required ? '必需按钮' : '可选按钮');
    chip.draggable = true;
    chip.addEventListener('dragstart', (event) => {
      O.dragElement = null;
      O.dragPayload = { kind: 'button', key: button.key };
      event.dataTransfer.effectAllowed = 'copy';
      try { event.dataTransfer.setData('text/plain', 'samsara-button'); } catch (e) { /* ignore */ }
    });
    chip.addEventListener('click', () => insertPartAtSelection({ button: button.key }));
    container.append(chip);
  }
}

function updateEditorCounts() {
  const message = selectedMessage();
  const vars = $o('output-var-count');
  const buttons = $o('output-button-count');
  if (vars) vars.textContent = String((message?.variables || []).length);
  if (buttons) buttons.textContent = String((message?.buttons || []).length);
}

// ---- 光标与插入 ----

function currentRange() {
  const editor = $o('output-editor');
  if (!editor) return null;
  const selection = window.getSelection();
  if (selection && selection.rangeCount) {
    const range = selection.getRangeAt(0);
    if (editor.contains(range.startContainer)) return range.cloneRange();
  }
  const range = document.createRange();
  range.selectNodeContents(editor);
  range.collapse(false);
  return range;
}

function setCaretAfter(node) {
  const selection = window.getSelection();
  const range = document.createRange();
  range.setStartAfter(node);
  range.collapse(true);
  selection.removeAllRanges();
  selection.addRange(range);
}

function normalizeChipInsertionRange(range) {
  let node = range.startContainer;
  if (node.nodeType === Node.TEXT_NODE) node = node.parentElement;
  if (!node) return range;
  const chip = node.closest?.('.rt-chip');
  if (chip) {
    range.setStartAfter(chip);
    range.collapse(true);
    return range;
  }
  const prefix = node.closest?.('[data-prefix]');
  if (prefix) {
    range.setStartAfter(prefix);
    range.collapse(true);
  }
  return range;
}

function normalizeTextInsertionRange(range) {
  let node = range.startContainer;
  if (node.nodeType === Node.TEXT_NODE) node = node.parentElement;
  if (!node) return range;
  const chip = node.closest?.('.rt-chip');
  if (chip && !node.closest?.('.rt-button-label')) {
    range.setStartAfter(chip);
    range.collapse(true);
  }
  return range;
}

function insertPartAtSelection(part) {
  const editor = $o('output-editor');
  if (!editor) return;
  recordHistory();
  const node = part.var !== undefined ? createVarNode(part.var) : createButtonNode(part);
  const range = normalizeChipInsertionRange(currentRange());
  range.deleteContents();
  range.insertNode(node);
  ensureTail();
  setCaretAfter(node);
  syncPartsFromDom();
  scheduleOutputPreview();
}

function insertNewlineAtSelection() {
  const editor = $o('output-editor');
  if (!editor) return;
  const selection = window.getSelection();
  let range = currentRange();
  let node = range.startContainer;
  if (node.nodeType === Node.TEXT_NODE) node = node.parentElement;
  if (node?.closest?.('.rt-button-label')) {
    return;
  }
  if (node?.closest?.('[data-prefix]')) {
    const prefix = node.closest('[data-prefix]');
    range = document.createRange();
    range.setStartAfter(prefix);
    range.collapse(true);
  }
  recordHistory();
  range.deleteContents();
  const newline = document.createTextNode('\n');
  range.insertNode(newline);
  const virtual = createVirtualPrefix();
  const afterNewline = document.createRange();
  afterNewline.setStartAfter(newline);
  afterNewline.collapse(true);
  afterNewline.insertNode(virtual);
  ensureTail();
  setCaretAfter(virtual);
  syncPartsFromDom();
  scheduleOutputPreview();
}

function insertPlainText(text) {
  const editor = $o('output-editor');
  if (!editor) return;
  const range = normalizeTextInsertionRange(currentRange());
  let node = range.startContainer;
  if (node.nodeType === Node.TEXT_NODE) node = node.parentElement;
  const inButtonLabel = !!node?.closest?.('.rt-button-label');
  const value = inButtonLabel ? text.replace(/\s*\n+\s*/g, ' ') : text.replace(/\r\n?/g, '\n');
  if (!value) return;
  recordHistory();
  const lines = value.split('\n');
  range.deleteContents();
  let last = null;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i]) {
      const textNode = document.createTextNode(lines[i]);
      if (!last) range.insertNode(textNode);
      else {
        const r = document.createRange();
        r.setStartAfter(last);
        r.collapse(true);
        r.insertNode(textNode);
      }
      last = textNode;
    }
    if (i < lines.length - 1) {
      const newline = document.createTextNode('\n');
      if (!last) range.insertNode(newline);
      else {
        const r = document.createRange();
        r.setStartAfter(last);
        r.collapse(true);
        r.insertNode(newline);
      }
      last = newline;
      if (!inButtonLabel) {
        const virtual = createVirtualPrefix();
        const r = document.createRange();
        r.setStartAfter(last);
        r.collapse(true);
        r.insertNode(virtual);
        last = virtual;
      }
    }
  }
  ensureTail();
  if (last) setCaretAfter(last);
  syncPartsFromDom();
  scheduleOutputPreview();
}

// ---- DOM → parts ----

function syncPartsFromDom() {
  if (O.filling) return;
  const editor = $o('output-editor');
  if (!editor) return;
  const parts = [];
  let buffer = '';
  const flush = () => {
    if (buffer) {
      parts.push({ text: buffer });
      buffer = '';
    }
  };
  const walk = (node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      buffer += node.nodeValue.replace(/\u200B/g, '').replace(/\r\n?/g, '\n');
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return;
    if (node.dataset?.virtual !== undefined) return;
    if (node.dataset?.prefix !== undefined) {
      flush();
      parts.push({ prefix: node.textContent.replace(/\r\n?/g, '\n') });
      return;
    }
    if (node.dataset?.var !== undefined) {
      flush();
      parts.push({ var: node.dataset.var });
      return;
    }
    if (node.dataset?.button !== undefined) {
      flush();
      const labelNode = node.querySelector('.rt-button-label');
      const label = labelNode ? labelNode.textContent : '';
      const definition = (selectedMessage()?.buttons || [])
          .find((item) => item.key === node.dataset.button);
      const part = { button: node.dataset.button };
      if (label && (!definition || label !== definition.label)) part.label = label;
      parts.push(part);
      return;
    }
    const tag = node.tagName;
    if (tag === 'BR') {
      buffer += '\n';
      return;
    }
    if (tag === 'DIV' || tag === 'P') {
      if (buffer && !buffer.endsWith('\n')) buffer += '\n';
      for (const child of node.childNodes) walk(child);
      if (buffer && !buffer.endsWith('\n')) buffer += '\n';
      return;
    }
    for (const child of node.childNodes) walk(child);
  };
  for (const child of editor.childNodes) walk(child);
  flush();
  O.parts = parts;
  showClientErrors();
}

// ---- 历史 ----

function recordHistory() {
  if (O.filling) return;
  syncPartsFromDom();
  const snapshot = JSON.stringify(O.parts);
  if (O.undoStack.length && O.undoStack[O.undoStack.length - 1] === snapshot) return;
  O.undoStack.push(snapshot);
  if (O.undoStack.length > 120) O.undoStack.shift();
  O.redoStack = [];
}

function restoreSnapshot(snapshot) {
  O.parts = JSON.parse(snapshot);
  fillEditor(O.parts);
  scheduleOutputPreview();
}

function undoEditor() {
  if (!O.undoStack.length) return;
  syncPartsFromDom();
  O.redoStack.push(JSON.stringify(O.parts));
  restoreSnapshot(O.undoStack.pop());
}

function redoEditor() {
  if (!O.redoStack.length) return;
  syncPartsFromDom();
  O.undoStack.push(JSON.stringify(O.parts));
  restoreSnapshot(O.redoStack.pop());
}

// ---- 校验与预览 ----

function clientErrors() {
  const message = selectedMessage();
  const errors = [];
  if (!message) return errors;
  if (!O.parts.length) errors.push('模板不能为空');
  const varKeys = [];
  const buttonKeys = [];
  for (const part of O.parts) {
    if (part.var !== undefined) varKeys.push(part.var);
    if (part.button !== undefined) buttonKeys.push(part.button);
  }
  const knownVars = new Set((message.variables || []).map((item) => item.key));
  const knownButtons = new Set((message.buttons || []).map((item) => item.key));
  const seenVars = new Set();
  const seenButtons = new Set();
  for (const key of varKeys) {
    if (!knownVars.has(key)) errors.push('未知变量：' + key);
    if (seenVars.has(key)) errors.push('变量重复：' + key);
    else seenVars.add(key);
  }
  for (const key of buttonKeys) {
    if (!knownButtons.has(key)) errors.push('未知按钮：' + key);
    if (seenButtons.has(key)) errors.push('按钮重复：' + key);
    else seenButtons.add(key);
  }
  const missing = (message.variables || []).filter((variable) => variable.required && !seenVars.has(variable.key));
  const missingButtons = (message.buttons || []).filter((button) => button.required && !seenButtons.has(button.key));
  if (missing.length) errors.push('缺少必需变量：' + missing.map((item) => item.key).join(', '));
  if (missingButtons.length) errors.push('缺少必需按钮：' + missingButtons.map((item) => item.label).join(', '));
  return errors;
}

function showClientErrors() {
  const container = $o('output-errors');
  if (!container) return;
  container.replaceChildren();
  for (const text of clientErrors()) {
    const line = document.createElement('div');
    line.textContent = text + '（保存会被服务端拒绝）';
    container.append(line);
  }
}

function scheduleOutputPreview() {
  if (O.previewTimer) clearTimeout(O.previewTimer);
  O.previewTimer = setTimeout(renderOutputPreview, 260);
}

async function renderOutputPreview() {
  const container = $o('output-preview');
  if (!container) return;
  container.replaceChildren();
  if (!O.key) return;
  const errors = clientErrors();
  if (errors.length) {
    const hint = document.createElement('p');
    hint.className = 'muted small';
    hint.textContent = '修正模板后才会生成预览：' + errors.join('；');
    container.append(hint);
    return;
  }
  try {
    const result = await window.SamsaraUI.api('/api/output/preview', {
      method: 'POST',
      body: JSON.stringify({ key: O.key, parts: O.parts }),
    });
    container.replaceChildren();
    for (const fragment of result.fragments || []) {
      const span = document.createElement('span');
      span.textContent = fragment.text;
      if (fragment.color && OUTPUT_COLORS[fragment.color]) {
        span.style.color = OUTPUT_COLORS[fragment.color];
      }
      if (fragment.bold) span.style.fontWeight = '700';
      if (fragment.italic) span.style.fontStyle = 'italic';
      if (fragment.underline) span.style.textDecorationLine = 'underline';
      if (fragment.strikethrough) {
        span.style.textDecorationLine = (span.style.textDecorationLine || '') + ' line-through';
      }
      if (fragment.clickable) {
        span.className = 'preview-button';
        span.title = (fragment.clickCommand || '') + ' ' + (fragment.hover || '');
      }
      container.append(span);
    }
  } catch (e) {
    const error = document.createElement('p');
    error.className = 'message error';
    error.textContent = window.SamsaraUI.authMessage(e, '预览失败');
    container.append(error);
  }
}

// ---- 保存/重载 ----

async function saveOutput() {
  const message = selectedMessage();
  if (!message) return false;
  syncPartsFromDom();
  try {
    const result = await window.SamsaraUI.api('/api/output', {
      method: 'PUT',
      body: JSON.stringify({ key: O.key, parts: O.parts }),
    });
    O.model = result.output;
    O.parts = cloneParts(selectedMessage()?.parts || O.parts);
    O.undoStack = [];
    O.redoStack = [];
    if (window.SamsaraUI.isActiveTab('output')) renderOutputNav();
    renderOutputEditor();
    await renderOutputPreview();
    outputMessage(result.message || '已保存并重载', 'ok');
    refreshCount();
    return true;
  } catch (e) {
    showOutputErrors(e.body);
    outputMessage(window.SamsaraUI.authMessage(e, '保存失败'), 'error');
    return false;
  }
}

async function reloadOutput() {
  try {
    const result = await window.SamsaraUI.api('/api/output/reload', { method: 'POST' });
    O.model = result.output;
    if (!O.model.messages.some((message) => message.key === O.key)) {
      O.key = O.model.messages.length ? O.model.messages[0].key : '';
    }
    selectOutputMessage(O.key, true);
    outputMessage(result.message || '输出模板已重载', 'ok');
    refreshCount();
    return true;
  } catch (e) {
    outputMessage(window.SamsaraUI.authMessage(e, '重新读取失败'), 'error');
    return false;
  }
}

function restoreOutputDefault() {
  const message = selectedMessage();
  if (!message) return;
  O.parts = cloneParts(message.defaultParts);
  O.undoStack = [];
  O.redoStack = [];
  renderOutputEditor();
  renderOutputPreview();
  outputMessage('已恢复系统默认模板；点“保存并重载”后生效', 'ok');
}

function showOutputErrors(body) {
  const container = $o('output-errors');
  if (!container) return;
  container.replaceChildren();
  if (!body || !body.fields) return;
  for (const [field, message] of Object.entries(body.fields)) {
    const line = document.createElement('div');
    line.textContent = field + '：' + message;
    container.append(line);
  }
}

// ---- 事件 ----

function editorKeydown(event) {
  const mod = event.ctrlKey || event.metaKey;
  if (mod && !event.shiftKey && event.key.toLowerCase() === 'z') {
    event.preventDefault();
    undoEditor();
    return;
  }
  if (mod && (event.key.toLowerCase() === 'y' || (event.shiftKey && event.key.toLowerCase() === 'z'))) {
    event.preventDefault();
    redoEditor();
    return;
  }
  if (event.key === 'Enter') {
    event.preventDefault();
    insertNewlineAtSelection();
    return;
  }
  if (event.key === 'Backspace' || event.key === 'Delete') {
    handleAtomicDelete(event, event.key === 'Backspace' ? 'back' : 'forward');
  }
}

function handleAtomicDelete(event, direction) {
  const editor = $o('output-editor');
  const selection = window.getSelection();
  if (!editor || !selection || !selection.rangeCount) return;
  const range = selection.getRangeAt(0);
  if (!range.collapsed) return;
  const container = range.startContainer;
  const offset = range.startOffset;
  let candidate = null;
  if (container.nodeType === Node.TEXT_NODE) {
    if (direction === 'back' && offset === 0) candidate = container.previousSibling;
    if (direction === 'forward' && offset === container.length) candidate = container.nextSibling;
  } else {
    candidate = direction === 'back'
      ? container.childNodes[offset - 1]
      : container.childNodes[offset];
  }
  if (!candidate) return;
  if (candidate.nodeType === Node.ELEMENT_NODE && candidate.dataset?.virtual !== undefined) {
    event.preventDefault();
    if (direction === 'back') mergePreviousLine(candidate);
    return;
  }
  let chip = null;
  if (candidate.nodeType === Node.ELEMENT_NODE) {
    chip = candidate.classList?.contains('rt-chip') ? candidate : candidate.querySelector?.('.rt-chip');
  }
  if (chip && editor.contains(chip)) {
    event.preventDefault();
    recordHistory();
    chip.remove();
    ensureTail();
    syncPartsFromDom();
    scheduleOutputPreview();
    return;
  }
  if (candidate.nodeType === Node.ELEMENT_NODE && candidate.tagName === 'BR') {
    event.preventDefault();
    recordHistory();
    candidate.remove();
    syncPartsFromDom();
    scheduleOutputPreview();
  }
}

function mergePreviousLine(virtual) {
  const editor = $o('output-editor');
  if (!editor) return;
  recordHistory();
  let previous = virtual.previousSibling;
  virtual.remove();
  if (previous && previous.nodeType === Node.TEXT_NODE && previous.nodeValue.endsWith('\n')) {
    previous.nodeValue = previous.nodeValue.slice(0, -1);
    if (!previous.nodeValue) previous.remove();
    const range = document.createRange();
    if (previous && previous.parentNode) {
      range.setStart(previous, previous.nodeValue.length);
    } else {
      range.selectNodeContents(editor);
      range.collapse(false);
    }
    range.collapse(true);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
  }
  syncPartsFromDom();
  scheduleOutputPreview();
}

function editorPaste(event) {
  event.preventDefault();
  const text = (event.clipboardData || window.clipboardData).getData('text/plain');
  if (!text) return;
  insertPlainText(text);
}

function editorDrop(event) {
  event.preventDefault();
  const editor = $o('output-editor');
  editor.classList.remove('drop-active');
  if (!editor) return;
  let range = null;
  if (document.caretRangeFromPoint) {
    range = document.caretRangeFromPoint(event.clientX, event.clientY);
  } else if (document.caretPositionFromPoint) {
    const position = document.caretPositionFromPoint(event.clientX, event.clientY);
    if (position) {
      range = document.createRange();
      range.setStart(position.offsetNode, position.offset);
      range.collapse(true);
    }
  }
  if (!range || !editor.contains(range.startContainer)) {
    range = document.createRange();
    range.selectNodeContents(editor);
    range.collapse(false);
  }
  range = normalizeChipInsertionRange(range);
  recordHistory();
  if (O.dragElement) {
    range.deleteContents();
    range.insertNode(O.dragElement);
    ensureTail();
    setCaretAfter(O.dragElement);
    O.dragElement = null;
  } else if (O.dragPayload) {
    const part = O.dragPayload.kind === 'var'
      ? { var: O.dragPayload.key }
      : { button: O.dragPayload.key };
    const node = part.var !== undefined ? createVarNode(part.var) : createButtonNode(part);
    range.deleteContents();
    range.insertNode(node);
    ensureTail();
    setCaretAfter(node);
    O.dragPayload = null;
  } else {
    return;
  }
  syncPartsFromDom();
  scheduleOutputPreview();
}

function wireEvents() {
  const editor = $o('output-editor');
  if (!editor) return;
  editor.addEventListener('beforeinput', () => {
    if (!O.filling) recordHistory();
  });
  editor.addEventListener('input', () => {
    if (O.filling) return;
    ensureTail();
    syncPartsFromDom();
    scheduleOutputPreview();
  });
  editor.addEventListener('keydown', editorKeydown);
  editor.addEventListener('paste', editorPaste);
  editor.addEventListener('dragover', (event) => {
    event.preventDefault();
    editor.classList.add('drop-active');
  });
  editor.addEventListener('dragleave', (event) => {
    if (!editor.contains(event.relatedTarget)) editor.classList.remove('drop-active');
  });
  editor.addEventListener('drop', editorDrop);
  $o('output-search').addEventListener('input', renderOutputNav);
}

wireEvents();
window.SamsaraOutput = {
  load: loadOutput,
  ensureLoaded: ensureOutputLoaded,
  activate: () => {
    if (!O.model) ensureOutputLoaded();
    else {
      renderOutputNav();
      renderOutputEditor();
    }
  },
  save: saveOutput,
  reload: reloadOutput,
  restoreDefault: restoreOutputDefault,
  refreshCount,
};
window.SamsaraUI.onConnect(loadOutput);
