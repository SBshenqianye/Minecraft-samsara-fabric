<template>
  <section class="output-edit-wrap">
    <div class="column-title">字段改写</div>
    <div id="output-disabled" class="output-disabled" :hidden="editable">{{ disabledReason || '当前消息不可编辑' }}</div>
    <div id="output-meta" class="output-meta">{{ meta }}</div>
    <div
      id="output-editor"
      ref="editorEl"
      class="rich-editor"
      contenteditable="true"
      spellcheck="false"
      aria-label="输出消息编辑画布"
      :hidden="!editable"
      @beforeinput="recordHistory"
      @input="onInput"
      @keydown="onKeydown"
      @paste="onPaste"
      @dragover.prevent="onDragOver"
      @dragleave="onDragLeave"
      @drop.prevent="onDrop"
    ></div>
    <div id="output-errors" class="output-errors">
      <div v-for="text in store.errors.value" :key="text">{{ text }}（保存会被服务端拒绝）</div>
    </div>
    <div class="editor-hint">
      拖入按钮和变量是原子标签，不能改文字；按钮可以改标签文字。
      选择消息后会恢复原始模板；重置会恢复系统模板。
    </div>
    <h3 class="palette-title">变量 <span id="output-var-count" class="count-pill">{{ variables.length }}</span></h3>
    <div id="output-palette" class="palette" :hidden="!editable">
      <span
        v-for="variable in variables"
        :key="variable.key"
        class="chip chip-var palette-chip"
        draggable="true"
        :title="variable.label + (variable.required ? ' · 必需' : ' · 可选') + ' · 样例：' + variable.sample"
        @dragstart="startPaletteDrag({ kind: 'var', key: variable.key }, $event)"
        @click="insertPart({ var: variable.key })"
      >&#123;{{ variable.key }}&#125;</span>
    </div>
    <h3 class="palette-title">按钮 <span id="output-button-count" class="count-pill">{{ buttons.length }}</span></h3>
    <div id="output-buttons" class="palette" :hidden="!editable">
      <span
        v-for="button in buttons"
        :key="button.key"
        class="chip chip-button palette-chip"
        draggable="true"
        :title="button.command + ' · ' + (button.required ? '必需按钮' : '可选按钮')"
        @dragstart="startPaletteDrag({ kind: 'button', key: button.key }, $event)"
        @click="insertPart({ button: button.key })"
      >[{{ button.label }}]</span>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue';
import { buttonLabel, buttonTitle } from './model.js';

const props = defineProps({
  store: { type: Object, required: true },
});

const editorEl = ref(null);
const dragPayload = ref(null);
const dragElement = ref(null);
const undoStack = ref([]);
const redoStack = ref([]);
let filling = false;

const selected = computed(() => props.store.selected.value);
const variables = computed(() => selected.value?.variables || []);
const buttons = computed(() => selected.value?.buttons || []);
const editable = computed(() => Boolean(selected.value) && selected.value.editable !== false);
const disabledReason = computed(() => selected.value?.disabledReason || '');
const meta = computed(() => (selected.value
  ? selected.value.key + ' · ' + selected.value.description
    + ' · 变量 ' + variables.value.length + ' · 按钮 ' + buttons.value.length
  : ''));

function make(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function createPrefixNode(text) {
  const node = make('span', 'rt-prefix-text', text);
  node.dataset.prefix = '1';
  node.contentEditable = 'true';
  node.spellcheck = false;
  node.title = '首行前缀（纯文本，可直接编辑）';
  return node;
}

function chipDeleteButton(chip) {
  const button = make('button', 'rt-x', '×');
  button.type = 'button';
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
  });
  return button;
}

function makeChipDraggable(chip) {
  chip.draggable = true;
  chip.addEventListener('dragstart', (event) => {
    dragElement.value = chip;
    dragPayload.value = null;
    event.dataTransfer.effectAllowed = 'move';
    try { event.dataTransfer.setData('text/plain', 'samsara-part'); } catch { /* ignore */ }
  });
  chip.addEventListener('dragend', () => {
    dragElement.value = null;
    dragPayload.value = null;
  });
}

function createVarNode(key) {
  const variable = variables.value.find((item) => item.key === key);
  const chip = make('span', 'rt-chip rt-var', '{' + key + '}');
  chip.dataset.var = key;
  chip.contentEditable = 'false';
  chip.title = variable
    ? variable.label + ' · 样例：' + variable.sample + (variable.required ? ' · 必需' : '')
    : '未知变量：' + key;
  if (!variable) chip.classList.add('rt-unknown');
  chip.append(chipDeleteButton(chip));
  makeChipDraggable(chip);
  return chip;
}

function createButtonNode(part) {
  const chip = make('span', 'rt-chip rt-button');
  chip.dataset.button = part.button;
  chip.contentEditable = 'false';
  chip.title = buttonTitle(part, selected.value);
  chip.append(make('span', null, '['));
  const labelNode = make('span', 'rt-button-label', buttonLabel(part, selected.value));
  labelNode.contentEditable = 'true';
  labelNode.spellcheck = false;
  labelNode.title = '按钮标签可编辑；按钮命令固定';
  chip.append(labelNode, make('span', null, ']'), chipDeleteButton(chip));
  makeChipDraggable(chip);
  return chip;
}

function ensureTail() {
  const editor = editorEl.value;
  if (!editor) return;
  const last = editor.lastChild;
  if (!last || last.nodeType !== Node.TEXT_NODE) editor.append(document.createTextNode('\u200B'));
}

function renderParts(parts) {
  const editor = editorEl.value;
  if (!editor) return;
  filling = true;
  editor.replaceChildren();
  for (const part of parts || []) {
    if (part.text !== undefined) editor.append(document.createTextNode(part.text));
    else if (part.prefix !== undefined) editor.append(createPrefixNode(part.prefix));
    else if (part.var !== undefined) editor.append(createVarNode(part.var));
    else if (part.button !== undefined) editor.append(createButtonNode(part));
  }
  ensureTail();
  filling = false;
}

function currentRange() {
  const editor = editorEl.value;
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

function syncPartsFromDom() {
  if (filling) return;
  const editor = editorEl.value;
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
      const definition = (buttons.value || []).find((item) => item.key === node.dataset.button);
      const part = { button: node.dataset.button };
      if (label && (!definition || label !== definition.label)) part.label = label;
      parts.push(part);
      return;
    }
    if (node.tagName === 'BR') {
      buffer += '\n';
      return;
    }
    if (node.tagName === 'DIV' || node.tagName === 'P') {
      if (buffer && !buffer.endsWith('\n')) buffer += '\n';
      for (const child of node.childNodes) walk(child);
      if (buffer && !buffer.endsWith('\n')) buffer += '\n';
      return;
    }
    for (const child of node.childNodes) walk(child);
  };
  for (const child of editor.childNodes) walk(child);
  flush();
  props.store.setParts(parts);
}

function recordHistory() {
  if (filling) return;
  syncPartsFromDom();
  const snapshot = JSON.stringify(props.store.parts.value);
  if (undoStack.value.length && undoStack.value[undoStack.value.length - 1] === snapshot) return;
  undoStack.value = [...undoStack.value, snapshot].slice(-120);
  redoStack.value = [];
}

function restoreSnapshot(snapshot) {
  props.store.setParts(JSON.parse(snapshot));
  props.store.bumpEditor();
}

function undoEditor() {
  if (!undoStack.value.length) return;
  syncPartsFromDom();
  redoStack.value = [...redoStack.value, JSON.stringify(props.store.parts.value)];
  restoreSnapshot(undoStack.value[undoStack.value.length - 1]);
  undoStack.value = undoStack.value.slice(0, -1);
}

function redoEditor() {
  if (!redoStack.value.length) return;
  syncPartsFromDom();
  undoStack.value = [...undoStack.value, JSON.stringify(props.store.parts.value)];
  restoreSnapshot(redoStack.value[redoStack.value.length - 1]);
  redoStack.value = redoStack.value.slice(0, -1);
}

function insertPart(part) {
  const editor = editorEl.value;
  if (!editor) return;
  recordHistory();
  const node = part.var !== undefined ? createVarNode(part.var) : createButtonNode(part);
  const range = normalizeChipInsertionRange(currentRange());
  range.deleteContents();
  range.insertNode(node);
  ensureTail();
  setCaretAfter(node);
  syncPartsFromDom();
}

function insertNewlineAtSelection() {
  const editor = editorEl.value;
  if (!editor) return;
  let range = currentRange();
  let node = range.startContainer;
  if (node.nodeType === Node.TEXT_NODE) node = node.parentElement;
  if (node?.closest?.('.rt-button-label')) return;
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
  ensureTail();
  setCaretAfter(newline);
  syncPartsFromDom();
}

function insertPlainText(text) {
  const editor = editorEl.value;
  if (!editor) return;
  const range = normalizeTextInsertionRange(currentRange());
  let node = range.startContainer;
  if (node.nodeType === Node.TEXT_NODE) node = node.parentElement;
  const inButtonLabel = Boolean(node?.closest?.('.rt-button-label'));
  const value = inButtonLabel ? text.replace(/\s*\n+\s*/g, ' ') : text.replace(/\r\n?/g, '\n');
  if (!value) return;
  recordHistory();
  const lines = value.split('\n');
  range.deleteContents();
  let last = null;
  for (let i = 0; i < lines.length; i += 1) {
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
    }
  }
  ensureTail();
  if (last) setCaretAfter(last);
  syncPartsFromDom();
}

function onInput() {
  if (filling) return;
  ensureTail();
  syncPartsFromDom();
}

function onKeydown(event) {
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
  const editor = editorEl.value;
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
    candidate = direction === 'back' ? container.childNodes[offset - 1] : container.childNodes[offset];
  }
  if (!candidate) return;
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
    return;
  }
  if (candidate.nodeType === Node.ELEMENT_NODE && candidate.tagName === 'BR') {
    event.preventDefault();
    recordHistory();
    candidate.remove();
    syncPartsFromDom();
  }
}

function onPaste(event) {
  event.preventDefault();
  const text = (event.clipboardData || window.clipboardData).getData('text/plain');
  if (text) insertPlainText(text);
}

function onDragOver(event) {
  event.preventDefault();
  editorEl.value?.classList.add('drop-active');
}

function onDragLeave(event) {
  if (!editorEl.value?.contains(event.relatedTarget)) {
    editorEl.value?.classList.remove('drop-active');
  }
}

function onDrop(event) {
  event.preventDefault();
  const editor = editorEl.value;
  if (!editor) return;
  editor.classList.remove('drop-active');
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
  if (dragElement.value) {
    range.deleteContents();
    range.insertNode(dragElement.value);
    ensureTail();
    setCaretAfter(dragElement.value);
    dragElement.value = null;
  } else if (dragPayload.value) {
    const part = dragPayload.value.kind === 'var'
      ? { var: dragPayload.value.key }
      : { button: dragPayload.value.key };
    const node = part.var !== undefined ? createVarNode(part.var) : createButtonNode(part);
    range.deleteContents();
    range.insertNode(node);
    ensureTail();
    setCaretAfter(node);
    dragPayload.value = null;
  } else {
    return;
  }
  syncPartsFromDom();
}

function startPaletteDrag(payload, event) {
  dragElement.value = null;
  dragPayload.value = payload;
  event.dataTransfer.effectAllowed = 'copy';
  try {
    event.dataTransfer.setData('text/plain', payload.kind === 'var' ? 'samsara-var' : 'samsara-button');
  } catch { /* ignore */ }
}

watch(
  () => [props.store.key.value, props.store.editorVersion.value],
  async () => {
    await nextTick();
    renderParts(props.store.parts.value);
    if (!props.store.parts.value.length && selected.value) props.store.schedulePreview();
  },
  { immediate: true },
);
</script>
