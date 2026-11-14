'use strict';

// § 颜色/格式文本编辑器：输入框保留原始代码，工具栏插入代码，预览直接渲染。
// 颜色选择统一为 4×4 色板（与圈地/旗帜颜色浮窗同一套视觉），不再提供纯文本模式。
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

  /** 4×4 MC 固定色板；与圈地颜色浮窗共用 claim-color-* 视觉。 */
  function createPalette(onPick, options) {
    const opts = options || {};
    const grid = el('div', 'claim-color-grid chat-text-color-grid');
    grid.setAttribute('role', 'group');
    grid.setAttribute('aria-label', opts.label || 'Minecraft 16 色');
    for (const [code, label, hex] of COLORS) {
      const cell = el('button', 'claim-color-cell chat-text-color-cell');
      cell.type = 'button';
      cell.title = '§' + code + ' ' + label;
      cell.setAttribute('aria-label', '插入颜色 §' + code + ' ' + label);
      const swatch = el('span', 'claim-color-swatch');
      swatch.style.background = hex;
      const name = el('span', 'claim-color-name', label);
      const meta = el('span', 'claim-color-meta', '§' + code + ' · ' + code.toUpperCase());
      cell.append(swatch, name, meta);
      cell.addEventListener('click', () => onPick(code, label, hex));
      grid.append(cell);
    }
    return grid;
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
    const preview = el('div', opts.serverList ? 'chat-text-preview server-list-preview'
      : 'chat-text-preview');
    const colorToggle = el('button', 'chat-text-toggle', '颜色 4×4');
    colorToggle.type = 'button';
    colorToggle.setAttribute('aria-expanded', 'false');
    previewHead.append(el('span', 'chat-text-preview-label', '预览'), colorToggle);

    const insert = (code) => {
      const start = input.selectionStart == null ? input.value.length : input.selectionStart;
      const end = input.selectionEnd == null ? start : input.selectionEnd;
      input.value = input.value.slice(0, start) + '§' + code + input.value.slice(end);
      const pos = start + 2;
      input.setSelectionRange(pos, pos);
      input.focus();
      refresh();
    };

    const palette = createPalette((code) => insert(code), { label: 'MOTD 颜色' });
    palette.hidden = true;
    colorToggle.addEventListener('click', () => {
      palette.hidden = !palette.hidden;
      colorToggle.setAttribute('aria-expanded', palette.hidden ? 'false' : 'true');
      if (!palette.hidden) palette.querySelector('button')?.focus();
    });
    wrap.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && !palette.hidden) {
        palette.hidden = true;
        colorToggle.setAttribute('aria-expanded', 'false');
        colorToggle.focus();
        event.stopPropagation();
      }
    });

    const formats = el('div', 'chat-text-formats');
    for (const [code, label, style] of FORMATS) {
      const button = el('button', 'chat-text-format', label);
      button.type = 'button';
      button.title = '§' + code + ' ' + label;
      button.style.cssText = style;
      button.addEventListener('click', () => insert(code));
      formats.append(button);
    }
    toolbar.append(colorToggle, formats);

    function refresh() {
      if (typeof opts.onChange === 'function') opts.onChange(input.value);
      renderPreview(input.value);
    }

    function renderPreview(text) {
      preview.replaceChildren();
      if (opts.serverList) {
        preview.append(renderServerList(text, opts));
        return;
      }
      preview.append(render(text));
    }

    input.addEventListener('input', refresh);

    wrap.append(toolbar, palette, input, previewHead, preview);
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

  function renderServerList(text, options) {
    const opts = options || {};
    const card = el('div', 'server-list-card');
    const icon = el('div', 'server-list-icon');
    const iconImg = document.createElement('img');
    iconImg.src = opts.iconUrl || '/items/grass_block.png';
    iconImg.alt = '';
    iconImg.draggable = false;
    icon.append(iconImg);
    const body = el('div', 'server-list-body');
    const title = el('div', 'server-list-title');
    const lines = splitMotd(text);
    const first = renderLine(lines[0]);
    first.classList.add('server-list-line-first');
    title.append(first);
    const second = renderLine(lines[1] || '');
    second.classList.add('server-list-line-second');
    title.append(second);
    const right = el('div', 'server-list-right');
    const status = el('div', 'server-list-status');
    const signal = el('span', 'server-list-signal');
    for (let i = 0; i < 5; i += 1) {
      const bar = el('i');
      bar.style.height = (3 + i * 3) + 'px';
      signal.append(bar);
    }
    const players = el('span', 'server-list-players',
      (opts.online == null ? '0' : String(opts.online)) + '/' 
      + (opts.maxPlayers == null ? '20' : String(opts.maxPlayers)));
    status.append(signal, players);
    const version = el('div', 'server-list-version', opts.version || 'Minecraft 26.3');
    right.append(status, version);
    body.append(title);
    card.append(icon, body, right);
    return card;
  }

  function splitMotd(text) {
    const raw = String(text || '').replace(/\r/g, '');
    if (raw.includes('\n')) {
      const parts = raw.split('\n');
      return [parts[0], parts.slice(1).join(' ')];
    }
    if (raw.length <= 58) return [raw, ''];
    let cut = raw.lastIndexOf(' ', 58);
    if (cut < 24) cut = 58;
    return [raw.slice(0, cut), raw.slice(cut).trim()];
  }

  function renderLine(text) {
    const line = el('div', 'server-list-line');
    line.append(render(text));
    return line;
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

  window.SamsaraChatText = { create, render, createPalette, colors: COLORS };
})();
