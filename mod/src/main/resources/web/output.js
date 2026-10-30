'use strict';

// 输出格式编辑：手动改写与渲染预览同时可见。
// 所有用户文本都用 textContent/value 写入，绝不使用 innerHTML。

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
  drag: null,
};

const $o = (id) => document.getElementById(id);

function outputMessage(text, kind) {
  const el = $o('output-message');
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
    renderOutputMessages();
    selectOutputMessage(O.key);
    outputMessage('已读取 ' + O.model.messages.length + ' 条输出消息', 'ok');
  } catch (e) {
    outputMessage(e.status === 401 ? 'token 不正确，请先连接' : '读取输出格式失败：' + e.message, 'error');
  }
}

function ensureOutputLoaded() {
  if (!O.model && window.SamsaraUI.getToken()) loadOutput();
}

function renderOutputMessages() {
  const container = $o('output-messages');
  container.replaceChildren();
  if (!O.model) return;
  const query = $o('output-search').value.trim().toLowerCase();
  const groups = new Map();
  for (const message of O.model.messages) {
    const haystack = (message.key + ' ' + message.group + ' ' + message.description).toLowerCase();
    if (query && !haystack.includes(query)) continue;
    if (!groups.has(message.group)) groups.set(message.group, []);
    groups.get(message.group).push(message);
  }
  for (const [group, messages] of groups) {
    const details = document.createElement('details');
    details.className = 'output-group';
    details.open = true;
    const title = document.createElement('summary');
    title.className = 'group-title';
    title.textContent = group;
    details.append(title);
    for (const message of messages) {
      const button = document.createElement('button');
      button.className = 'output-message-item' + (message.key === O.key ? ' active' : '');
      button.textContent = message.key;
      button.title = message.description;
      button.addEventListener('click', () => selectOutputMessage(message.key));
      details.append(button);
    }
    container.append(details);
  }
}

function selectOutputMessage(key) {
  O.key = key;
  const message = selectedMessage();
  O.parts = message ? cloneParts(message.parts) : [];
  renderOutputMessages();
  renderOutputEditor();
  scheduleOutputPreview();
}

function renderOutputEditor() {
  const message = selectedMessage();
  const editor = $o('output-editor');
  const disabled = $o('output-disabled');
  const palette = $o('output-palette');
  const buttons = $o('output-buttons');
  const saveButton = $o('output-save');
  editor.replaceChildren();
  $o('output-errors').replaceChildren();
  if (!message) {
    disabled.hidden = true;
    editor.hidden = true;
    palette.hidden = true;
    buttons.hidden = true;
    $o('output-meta').textContent = '';
    saveButton.disabled = true;
    return;
  }
  $o('output-meta').textContent = message.key + ' · ' + message.description;
  const editable = message.editable !== false;
  disabled.hidden = editable;
  editor.hidden = !editable;
  palette.hidden = !editable;
  buttons.hidden = !editable;
  saveButton.disabled = !editable;
  if (!editable) {
    disabled.textContent = message.disabledReason || '当前消息不可编辑';
    return;
  }

  const canvas = document.createElement('div');
  canvas.className = 'token-canvas';
  canvas.addEventListener('dragover', (event) => event.preventDefault());
  canvas.addEventListener('drop', (event) => {
    if (event.target !== canvas) return;
    event.preventDefault();
    dropAt(O.parts.length);
  });
  O.parts.forEach((part, index) => canvas.append(renderToken(part, index)));
  editor.append(canvas);
  if (O.parts.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'muted small';
    empty.textContent = '模板为空；请从变量面板拖入变量，或恢复默认。';
    editor.append(empty);
  }
  renderOutputPalette(message);
  renderOutputButtons(message);
}

function renderToken(part, index) {
  const token = document.createElement('span');
  token.className = 'token';
  token.dataset.index = String(index);
  token.addEventListener('dragover', (event) => {
    event.preventDefault();
    event.stopPropagation();
    token.classList.add('drop-target');
  });
  token.addEventListener('dragleave', () => token.classList.remove('drop-target'));
  token.addEventListener('drop', (event) => {
    event.preventDefault();
    event.stopPropagation();
    token.classList.remove('drop-target');
    dropAt(index);
  });

  const handle = document.createElement('span');
  handle.className = 'token-handle';
  handle.textContent = '⋮⋮';
  handle.title = '拖动排序';
  handle.draggable = true;
  handle.addEventListener('dragstart', (event) => {
    O.drag = { kind: 'part', index };
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', JSON.stringify(O.drag));
  });
  token.append(handle);

  if (part.text !== undefined) {
    token.classList.add('token-text');
    const area = document.createElement('span');
    area.className = 'token-edit';
    area.contentEditable = 'true';
    area.spellcheck = false;
    area.textContent = part.text;
    area.addEventListener('input', () => {
      part.text = area.innerText;
      scheduleOutputPreview();
    });
    area.addEventListener('paste', plainPaste);
    token.append(area);
  } else if (part.prefix !== undefined) {
    token.classList.add('token-prefix');
    const area = document.createElement('span');
    area.className = 'token-edit';
    area.contentEditable = 'true';
    area.spellcheck = false;
    area.textContent = part.prefix;
    area.title = '首行聊天前缀，可直接修改';
    area.addEventListener('input', () => {
      part.prefix = area.innerText;
      scheduleOutputPreview();
    });
    area.addEventListener('paste', plainPaste);
    token.append(area);
  } else if (part.var !== undefined) {
    token.classList.add('token-var');
    token.draggable = true;
    token.addEventListener('dragstart', (event) => {
      O.drag = { kind: 'part', index };
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', JSON.stringify(O.drag));
    });
    const chip = document.createElement('span');
    chip.className = 'chip chip-var';
    chip.textContent = '{' + part.var + '}';
    const variable = (selectedMessage().variables || []).find((item) => item.key === part.var);
    chip.title = variable ? variable.label + ' · 样例：' + variable.sample : part.var;
    token.append(chip);
    if (!variable || !variable.required) {
      token.append(deleteButton(() => removePart(index)));
    } else {
      const lock = document.createElement('span');
      lock.className = 'chip-lock';
      lock.textContent = '必需';
      lock.title = '必需变量不能删除；可以移动';
      token.append(lock);
    }
  } else if (part.button !== undefined) {
    token.classList.add('token-button');
    token.draggable = true;
    token.addEventListener('dragstart', (event) => {
      O.drag = { kind: 'part', index };
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', JSON.stringify(O.drag));
    });
    const definition = (selectedMessage().buttons || []).find((item) => item.key === part.button);
    const chip = document.createElement('span');
    chip.className = 'chip chip-button';
    chip.textContent = '[' + (part.label || (definition ? definition.label : part.button)) + ']';
    chip.title = definition ? definition.command : part.button;
    token.append(chip);
    if (!definition || !definition.required) {
      token.append(deleteButton(() => removePart(index)));
    } else {
      const lock = document.createElement('span');
      lock.className = 'chip-lock';
      lock.textContent = '必需';
      token.append(lock);
    }
  }
  return token;
}

function plainPaste(event) {
  event.preventDefault();
  const text = (event.clipboardData || window.clipboardData).getData('text/plain');
  document.execCommand('insertText', false, text);
}

function deleteButton(onClick) {
  const button = document.createElement('button');
  button.className = 'chip-delete';
  button.textContent = '×';
  button.title = '删除';
  button.addEventListener('click', onClick);
  return button;
}

function renderOutputPalette(message) {
  const container = $o('output-palette');
  container.replaceChildren();
  for (const variable of message.variables || []) {
    const chip = document.createElement('span');
    chip.className = 'chip chip-var palette-chip';
    chip.textContent = '{' + variable.key + '}';
    chip.title = variable.label + (variable.required ? ' · 必需' : ' · 可选') + ' · 样例：' + variable.sample;
    chip.draggable = true;
    chip.addEventListener('dragstart', (event) => {
      O.drag = { kind: 'var', key: variable.key };
      event.dataTransfer.effectAllowed = 'copy';
      event.dataTransfer.setData('text/plain', JSON.stringify(O.drag));
    });
    chip.addEventListener('click', () => {
      O.parts.push({ var: variable.key });
      renderOutputEditor();
      scheduleOutputPreview();
    });
    container.append(chip);
  }
}

function renderOutputButtons(message) {
  const container = $o('output-buttons');
  container.replaceChildren();
  for (const button of message.buttons || []) {
    const chip = document.createElement('span');
    chip.className = 'chip chip-button palette-chip';
    chip.textContent = '[' + button.label + ']';
    chip.title = button.command + ' · ' + (button.required ? '必需按钮' : '可选按钮');
    chip.draggable = true;
    chip.addEventListener('dragstart', (event) => {
      O.drag = { kind: 'button', key: button.key };
      event.dataTransfer.effectAllowed = 'copy';
      event.dataTransfer.setData('text/plain', JSON.stringify(O.drag));
    });
    chip.addEventListener('click', () => {
      O.parts.push({ button: button.key });
      renderOutputEditor();
      scheduleOutputPreview();
    });
    container.append(chip);
  }
}

function removePart(index) {
  O.parts.splice(index, 1);
  renderOutputEditor();
  scheduleOutputPreview();
}

function dropAt(index) {
  const drag = O.drag;
  O.drag = null;
  if (!drag) return;
  if (drag.kind === 'part') {
    if (drag.index === index) return;
    const [part] = O.parts.splice(drag.index, 1);
    const target = drag.index < index ? index - 1 : index;
    O.parts.splice(target, 0, part);
  } else if (drag.kind === 'var') {
    O.parts.splice(index, 0, { var: drag.key });
  } else if (drag.kind === 'button') {
    O.parts.splice(index, 0, { button: drag.key });
  }
  renderOutputEditor();
  scheduleOutputPreview();
}

function scheduleOutputPreview() {
  if (O.previewTimer) clearTimeout(O.previewTimer);
  O.previewTimer = setTimeout(renderOutputPreview, 250);
}

async function renderOutputPreview() {
  const container = $o('output-preview');
  container.replaceChildren();
  if (!O.key) return;
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
    error.textContent = '预览失败：' + e.message;
    container.append(error);
  }
}

async function saveOutput() {
  const message = selectedMessage();
  if (!message) return;
  const result = await window.SamsaraUI.api('/api/output', {
    method: 'PUT',
    body: JSON.stringify({ key: O.key, parts: O.parts }),
  });
  O.model = result.output;
  O.parts = cloneParts(selectedMessage()?.parts || O.parts);
  renderOutputMessages();
  renderOutputEditor();
  await renderOutputPreview();
  outputMessage(result.message || '已保存并重载', 'ok');
}

async function reloadOutput() {
  const result = await window.SamsaraUI.api('/api/output/reload', { method: 'POST' });
  O.model = result.output;
  if (!O.model.messages.some((message) => message.key === O.key)) {
    O.key = O.model.messages.length ? O.model.messages[0].key : '';
  }
  renderOutputMessages();
  selectOutputMessage(O.key);
  outputMessage(result.message || '输出模板已重载', 'ok');
}

function restoreOutputDefault() {
  const message = selectedMessage();
  if (!message) return;
  O.parts = cloneParts(message.defaultParts);
  renderOutputEditor();
  renderOutputPreview();
  outputMessage('已恢复系统默认模板；点“保存并重载”后生效', 'ok');
}

function showOutputErrors(body) {
  const container = $o('output-errors');
  container.replaceChildren();
  if (!body || !body.fields) return;
  for (const [field, message] of Object.entries(body.fields)) {
    const line = document.createElement('div');
    line.textContent = field + '：' + message;
    container.append(line);
  }
}

$o('output-search').addEventListener('input', renderOutputMessages);
$o('output-editor').addEventListener('dragover', (event) => event.preventDefault());
$o('output-editor').addEventListener('drop', (event) => {
  if (event.target !== $o('output-editor')) return;
  event.preventDefault();
  dropAt(O.parts.length);
});
$o('output-save').addEventListener('click', async () => {
  try {
    await saveOutput();
  } catch (e) {
    showOutputErrors(e.body);
    outputMessage((e.body && e.body.error) || ('保存失败：' + e.message), 'error');
  }
});
$o('output-reload').addEventListener('click', async () => {
  try {
    await reloadOutput();
  } catch (e) {
    outputMessage('重新读取失败：' + e.message, 'error');
  }
});
$o('output-default').addEventListener('click', restoreOutputDefault);

window.SamsaraOutput = { load: loadOutput, ensureLoaded: ensureOutputLoaded };
window.SamsaraUI.onConnect(loadOutput);
