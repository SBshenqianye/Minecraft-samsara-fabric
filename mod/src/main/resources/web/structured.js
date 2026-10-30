'use strict';

// 轻量结构化输入框架：用同一套 DOM 组件编辑常见程序结构（字符串/布尔/数字/
// 枚举/列表/键值映射/嵌套对象）。不依赖任何前端框架，也不引入用户环境负担。

(function () {
  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function button(text, title, onClick) {
    const node = el('button', 'struct-btn ghost', text);
    node.type = 'button';
    if (title) node.title = title;
    node.addEventListener('click', onClick);
    return node;
  }

  function scalarInput(value, onChange, type) {
    const input = el('input', 'struct-input');
    input.type = type || 'text';
    input.value = value == null ? '' : String(value);
    input.addEventListener('input', () => {
      if (input.type === 'number') {
        onChange(input.value === '' ? null : Number(input.value));
      } else {
        onChange(input.value);
      }
    });
    return input;
  }

  function booleanInput(value, onChange) {
    const label = el('label', 'struct-bool');
    const input = el('input');
    input.type = 'checkbox';
    input.checked = value === true;
    input.addEventListener('change', () => onChange(input.checked));
    label.append(input, document.createTextNode(' 启用'));
    return label;
  }

  function enumInput(value, choices, onChange) {
    const select = el('select', 'struct-input');
    for (const choice of choices || []) {
      const option = el('option', null, choice);
      option.value = choice;
      select.append(option);
    }
    select.value = value == null ? '' : String(value);
    select.addEventListener('change', () => onChange(select.value));
    return select;
  }

  function stringList(values, onChange) {
    const wrap = el('div', 'struct-list');
    const rows = [];
    function emit() {
      onChange(rows.map((row) => row.input.value.trim()).filter(Boolean));
    }
    function add(value) {
      const row = el('div', 'struct-row');
      const input = scalarInput(value, emit);
      row.input = input;
      row.append(input, button('×', '删除', () => {
        const index = rows.indexOf(row);
        if (index >= 0) rows.splice(index, 1);
        row.remove();
        emit();
      }));
      rows.push(row);
      wrap.append(row);
    }
    (Array.isArray(values) ? values : []).forEach(add);
    wrap.append(button('+ 添加一项', '添加', () => {
      add('');
      emit();
    }));
    return wrap;
  }

  function keyValueMap(values, choices, onChange) {
    const wrap = el('div', 'struct-map');
    const rows = [];
    const map = values && typeof values === 'object' && !Array.isArray(values)
      ? Object.assign({}, values) : {};
    function emit() {
      const out = {};
      for (const row of rows) {
        const key = row.key.value.trim();
        if (key) out[key] = row.value.value;
      }
      onChange(out);
    }
    function add(key, value) {
      const row = el('div', 'struct-row struct-kv');
      const keyInput = scalarInput(key, emit);
      keyInput.placeholder = '命令路径 / 键';
      const valueInput = choices && choices.length
        ? enumInput(value, choices, emit)
        : scalarInput(value, emit);
      row.key = keyInput;
      row.value = valueInput;
      row.append(keyInput, valueInput, button('×', '删除', () => {
        const index = rows.indexOf(row);
        if (index >= 0) rows.splice(index, 1);
        row.remove();
        emit();
      }));
      rows.push(row);
      wrap.append(row);
    }
    Object.entries(map).forEach(([key, value]) => add(key, value));
    wrap.append(button('+ 添加一行', '添加', () => {
      add('', choices && choices.length ? choices[0] : '');
      emit();
    }));
    return wrap;
  }

  function any(value, onChange) {
    if (Array.isArray(value)) {
      return stringList(value.map((item) => String(item)), onChange);
    }
    if (value && typeof value === 'object') {
      return keyValueMap(value, [], (next) => onChange(next));
    }
    if (typeof value === 'boolean') {
      return booleanInput(value, onChange);
    }
    if (typeof value === 'number') {
      return scalarInput(value, onChange, 'number');
    }
    return scalarInput(value, onChange);
  }

  window.SamsaraStructured = {
    stringList,
    keyValueMap,
    booleanInput,
    enumInput,
    scalarInput,
    any,
  };
})();
