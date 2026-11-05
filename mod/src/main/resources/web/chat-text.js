'use strict';

// § 颜色/格式文本编辑器：输入框保留原始代码，工具栏插入代码，预览直接渲染。
// 预览和输入都允许查看纯文本；不把用户文本放进 innerHTML。
(function () {
  const COLORS = [
    ['0', '黑', '#000000'], ['1', '深蓝', '#0000aa'], ['2', '深绿', '#00aa00'],
    ['3', '深青', '#00aaaa'], ['4', '深红', '#aa0000'], ['5', '深紫', '#aa00aa'],
    ['6', '金', '#ffaa00'], ['7', '灰', '#aaaaaa'], ['8', '深灰', '#555555'],
    ['9', '蓝', '#5555ff'], ['a', '绿', '#55ff55'], ['b', '青', '#55ffff'],
    ['c', '红', '#ff5555'], ['d', '粉', '#ff55ff'], ['e', '黄', '#ffff55'],
    ['f', '白', '#ffffff'],
  ];
  const FORMATS = [
    ['l', '粗', 'font-weight:700'],
    ['o', '斜', 'font-style:italic'],
    ['n', '下划线', 'text-decoration:underline'],
    ['m', '删除线', 'text-decoration:line-through'],
    ['k', '乱码', 'opacity:.55;letter-spacing:1px'],
    ['r', '重置', ''],
  ];
  const COLOR_HEX = Object.fromEntries(COLORS.map(([code, label, hex]) => [code, hex]));

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function create(options) {
    const opts = options || {};
    const wrap = el('div', 'chat-text-editor');
    const toolbar = el('div', 'chat-text-toolbar');
    const input = el('textarea', 'chat-text-input');
    input.rows = opts.rows || 2;
    input.spellcheck = false;
    input.value = opts.value == null ? '' : String(opts.value);
    input.placeholder = opts.placeholder || '支持 § 颜色/格式代码，例如 §e[公告] §f欢迎';

    const previewHead = el('div', 'chat-text-preview-head');
    const preview = el('div', 'chat-text-preview');
    const toggle = el('button', 'chat-text-toggle', '纯文本');
    toggle.type = 'button';
    let plaintext = false;
    previewHead.append(el('span', 'chat-text-preview-label', '预览'), toggle);

    const insert = (code) => {
      const start = input.selectionStart == null ? input.value.length : input.selectionStart;
      const end = input.selectionEnd == null ? start : input.selectionEnd;
      input.value = input.value.slice(0, start) + '§' + code + input.value.slice(end);
      const pos = start + 2;
      input.setSelectionRange(pos, pos);
      input.focus();
      refresh();
    };

    const swatches = el('div', 'chat-text-swatches');
    for (const [code, label, hex] of COLORS) {
      const button = el('button', 'chat-text-swatch');
      button.type = 'button';
      button.title = '§' + code + ' ' + label;
      button.setAttribute('aria-label', '插入颜色 §' + code + ' ' + label);
      button.style.background = hex;
      button.addEventListener('click', () => insert(code));
      swatches.append(button);
    }
    const formats = el('div', 'chat-text-formats');
    for (const [code, label, style] of FORMATS) {
      const button = el('button', 'chat-text-format', label);
      button.type = 'button';
      button.title = '§' + code + ' ' + label;
      button.style.cssText = style;
      button.addEventListener('click', () => insert(code));
      formats.append(button);
    }
    toolbar.append(swatches, formats);

    function refresh() {
      if (typeof opts.onChange === 'function') opts.onChange(input.value);
      renderPreview(input.value);
    }

    function renderPreview(text) {
      preview.replaceChildren();
      if (plaintext) {
        preview.append(el('pre', 'chat-text-raw', text));
        return;
      }
      preview.append(render(text));
    }

    input.addEventListener('input', refresh);
    toggle.addEventListener('click', () => {
      plaintext = !plaintext;
      toggle.textContent = plaintext ? '预览' : '纯文本';
      toggle.setAttribute('aria-pressed', plaintext ? 'true' : 'false');
      renderPreview(input.value);
    });

    wrap.append(toolbar, input, previewHead, preview);
    renderPreview(input.value);
    return {
      element: wrap,
      input,
      getValue: () => input.value,
      setValue(value) {
        input.value = value == null ? '' : String(value);
        renderPreview(input.value);
      },
      refresh,
    };
  }

  function render(text) {
    const root = el('div', 'chat-text-rendered');
    let current = el('span');
    root.append(current);
    let style = {};
    let buffer = '';
    const flush = () => {
      if (!buffer) return;
      const span = el('span', null, buffer);
      span.style.color = style.color || '';
      span.style.fontWeight = style.bold ? '700' : '';
      span.style.fontStyle = style.italic ? 'italic' : '';
      span.style.textDecoration = [style.underline ? 'underline' : '',
        style.strike ? 'line-through' : ''].filter(Boolean).join(' ') || '';
      span.style.opacity = style.obfuscated ? '.55' : '';
      span.style.letterSpacing = style.obfuscated ? '1px' : '';
      current.append(span);
      buffer = '';
    };
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (c !== '§' && c !== '&' || i + 1 >= text.length) {
        buffer += c;
        continue;
      }
      const code = text[i + 1].toLowerCase();
      if (code === 'x') {
        const hex = [];
        for (let j = 0; j < 6; j++) {
          const at = i + 2 + j * 2;
          if (text[at] !== '§' && text[at] !== '&') break;
          hex.push(text[at + 1] || '');
        }
        if (hex.length === 6 && hex.every((value) => /[0-9a-f]/i.test(value))) {
          flush();
          style.color = '#' + hex.join('');
          i += 13;
          continue;
        }
      }
      if (COLOR_HEX[code]) {
        flush();
        style = { ...style, color: COLOR_HEX[code] };
        i++;
        continue;
      }
      if ('lonmk'.includes(code)) {
        flush();
        if (code === 'l') style.bold = true;
        if (code === 'o') style.italic = true;
        if (code === 'n') style.underline = true;
        if (code === 'm') style.strike = true;
        if (code === 'k') style.obfuscated = true;
        i++;
        continue;
      }
      if (code === 'r') {
        flush();
        style = {};
        i++;
        continue;
      }
      buffer += c;
    }
    flush();
    return root;
  }

  window.SamsaraChatText = { create, render };
})();
