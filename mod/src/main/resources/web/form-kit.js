'use strict';

// 共享表单/UX 组件：条目行、可折叠块、分段按钮、按 spec 生成控件。
// 目标：新页面只声明“字段 + 类型 + 说明”，不再各写一套输入框和布尔下拉。
(function () {
  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function button(text, className, onClick) {
    const node = el('button', className || '', text);
    node.type = 'button';
    if (onClick) node.addEventListener('click', onClick);
    return node;
  }

  function optionList(choices) {
    return (choices || []).map((choice) => (typeof choice === 'object' && choice !== null
      ? { value: String(choice.value), label: choice.label == null ? String(choice.value) : String(choice.label) }
      : { value: String(choice), label: String(choice) }));
  }

  /** 分段按钮组；用于布尔、对立两项、少量枚举。 */
  function buttonGroup(choices, value, onChange, options) {
    const wrap = el('div', 'segmented' + (options && options.className ? ' ' + options.className : ''));
    wrap.setAttribute('role', 'group');
    const items = optionList(choices);
    const buttons = [];
    const select = (next, fire) => {
      const text = String(next);
      for (const item of buttons) {
        const active = item.dataset.value === text;
        item.classList.toggle('active', active);
        item.setAttribute('aria-pressed', active ? 'true' : 'false');
      }
      if (fire && typeof onChange === 'function') onChange(text);
    };
    for (const choice of items) {
      const node = button(choice.label, 'segmented-btn', () => {
        if (node.disabled) return;
        if (node.classList.contains('active')) return;
        select(choice.value, true);
      });
      node.dataset.value = choice.value;
      if (options && options.title) node.title = options.title;
      buttons.push(node);
      wrap.append(node);
    }
    select(value == null ? '' : value, false);
    wrap.setValue = (next) => select(next, false);
    return wrap;
  }

  /** 布尔值：默认用“开/关”按钮，不再用复选框/下拉框。 */
  function toggle(value, onChange, options) {
    const opts = options || {};
    return buttonGroup(
      [
        { value: 'true', label: opts.onLabel || '开' },
        { value: 'false', label: opts.offLabel || '关' },
      ],
      value ? 'true' : 'false',
      (next) => onChange(next === 'true'),
      { title: opts.title }
    );
  }

  function textInput(options) {
    const opts = options || {};
    const input = el('input');
    input.type = opts.type || 'text';
    if (opts.value != null) input.value = String(opts.value);
    if (opts.placeholder) input.placeholder = opts.placeholder;
    if (opts.min != null) input.min = String(opts.min);
    if (opts.max != null) input.max = String(opts.max);
    if (opts.step != null) input.step = String(opts.step);
    if (opts.autocomplete) input.autocomplete = opts.autocomplete;
    if (opts.mono) input.style.fontFamily = 'var(--mono)';
    if (opts.onInput) input.addEventListener('input', () => opts.onInput(input.value));
    if (opts.onChange) input.addEventListener('change', () => opts.onChange(input.value));
    return input;
  }

  function multilineInput(options) {
    const opts = options || {};
    const area = el('textarea', opts.className || '');
    if (opts.value != null) area.value = String(opts.value);
    if (opts.placeholder) area.placeholder = opts.placeholder;
    area.spellcheck = false;
    if (opts.rows) area.rows = opts.rows;
    if (opts.onInput) area.addEventListener('input', () => opts.onInput(area.value));
    return area;
  }

  /**
   * spec:
   * { type: 'bool'|'enum'|'int'|'text'|'password'|'multiline'|'url',
   *   choices, min, max, step, placeholder, onLabel, offLabel }
   */
  function control(spec, value, onChange) {
    const s = spec || { type: 'text' };
    switch (s.type) {
      case 'bool':
        return toggle(value === true || value === 'true', onChange,
            { onLabel: s.onLabel || '开', offLabel: s.offLabel || '关', title: s.description });
      case 'enum':
        return buttonGroup(s.choices, value == null ? '' : String(value), onChange,
            { className: s.wrapClassName });
      case 'int':
      case 'number':
        return textInput({
          type: 'number',
          value: value == null ? '' : value,
          min: s.min,
          max: s.max,
          step: s.step == null ? 1 : s.step,
          placeholder: s.placeholder,
          onInput: (next) => onChange(next),
        });
      case 'password':
        return textInput({
          type: 'password',
          value: value == null ? '' : value,
          placeholder: s.placeholder || '敏感值',
          autocomplete: 'new-password',
          onInput: (next) => onChange(next),
        });
      case 'multiline':
        return multilineInput({
          value: value == null ? '' : value,
          placeholder: s.placeholder,
          rows: s.rows || 4,
          className: 'entry-textarea',
          onInput: (next) => onChange(next),
        });
      case 'url':
        return textInput({
          type: 'url',
          value: value == null ? '' : value,
          placeholder: s.placeholder || 'https://…',
          onInput: (next) => onChange(next),
        });
      default:
        return textInput({
          type: 'text',
          value: value == null ? '' : value,
          placeholder: s.placeholder || '空',
          mono: s.mono,
          onInput: (next) => onChange(next),
        });
    }
  }

  function badges(list) {
    const out = [];
    for (const item of list || []) {
      if (!item || !item.text) continue;
      out.push({ text: item.text, kind: item.kind || '' });
    }
    return out;
  }

  /**
   * 统一的配置条目行：左键名 + 说明，中间控件，右侧操作。
   */
  function field(options) {
    const opts = options || {};
    const row = el('div', 'entry' + (opts.className ? ' ' + opts.className : ''));
    if (opts.id) row.id = opts.id;
    const main = el('div');
    const keyLine = el('div', 'entry-key');
    keyLine.append(document.createTextNode(opts.label == null ? '' : String(opts.label)));
    for (const badge of badges(opts.badges)) {
      const node = el('span', 'badge' + (badge.kind ? ' ' + badge.kind : ''), badge.text);
      keyLine.append(document.createTextNode(' '), node);
    }
    main.append(keyLine);
    if (opts.description) main.append(el('div', 'entry-desc', opts.description));
    const controlWrap = el('div', 'entry-control');
    if (opts.control) controlWrap.append(opts.control);
    const actionsWrap = el('div', 'entry-actions');
    for (const action of opts.actions || []) {
      if (action) actionsWrap.append(action);
    }
    row.append(main, controlWrap, actionsWrap);
    return row;
  }

  /**
   * 可折叠块：默认 <details class="file-block">，与 samsara 配置页一致。
   * body 可以是 DOM 或 DOM 数组。
   */
  function collapsible(options) {
    const opts = options || {};
    const details = el('details', 'file-block' + (opts.className ? ' ' + opts.className : ''));
    if (opts.id) details.id = opts.id;
    details.open = opts.open !== false;
    const summary = el('summary', 'file-summary');
    const title = el('span', 'file-title', opts.title || '');
    summary.append(title);
    if (opts.path) summary.append(el('span', 'file-path', opts.path));
    if (opts.count != null) {
      const count = el('span', 'count-pill file-count', String(opts.count));
      summary.append(count);
    }
    if (opts.summaryExtra) summary.append(opts.summaryExtra);
    details.append(summary);
    const body = el('div', 'file-body');
    for (const node of [].concat(opts.body || [])) {
      if (node) body.append(node);
    }
    details.append(body);
    return details;
  }

  function group(title, count, body, options) {
    const opts = options || {};
    const details = el('details', 'group-block' + (opts.className ? ' ' + opts.className : ''));
    if (opts.id) details.id = opts.id;
    details.open = opts.open !== false;
    const summary = el('summary', 'group-title');
    summary.append(el('span', null, title));
    if (count != null) summary.append(el('span', 'count-pill group-count', String(count)));
    details.append(summary);
    for (const node of [].concat(body || [])) {
      if (node) details.append(node);
    }
    return details;
  }

  window.SamsaraUIKit = {
    el,
    button,
    buttonGroup,
    toggle,
    control,
    field,
    collapsible,
    group,
    textInput,
    multilineInput,
  };
})();
