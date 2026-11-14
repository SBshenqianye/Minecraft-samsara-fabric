'use strict';

// 通用物品 UI：图标 + 悬浮详情，价值表 / 基金 / 其他页面复用。
(function () {
  let tip = null;
  let hideTimer = 0;

  function fmtVp(value) {
    const n = Number(value || 0);
    if (!Number.isFinite(n)) return '0 VP';
    const text = Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
    return text + ' VP';
  }

  function make(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function ensureTip() {
    if (tip) return tip;
    tip = make('div', 'item-tooltip');
    tip.setAttribute('role', 'tooltip');
    document.body.append(tip);
    return tip;
  }

  function fillTip(row) {
    const box = ensureTip();
    box.replaceChildren();
    const iconBox = make('div', 'item-tooltip-icon');
    if (row.icon) {
      const img = document.createElement('img');
      img.src = '/items/' + row.icon + '.png';
      img.alt = '';
      iconBox.append(img);
    } else {
      iconBox.textContent = String(row.zh || row.name || '?').slice(0, 1);
      iconBox.classList.add('text');
    }
    const body = make('div', 'item-tooltip-body');
    body.append(make('div', 'item-tooltip-name', row.zh || row.name || row.id || '—'));
    if (row.zh && row.name && row.zh !== row.name) {
      body.append(make('div', 'item-tooltip-en', row.name));
    }
    if (row.id) body.append(make('div', 'item-tooltip-id', row.id));
    const meta = [];
    if (row.category) meta.push(row.category);
    if (row.count != null) meta.push(row.count + ' 个');
    meta.push(Number(row.value || 0) > 0 ? fmtVp(row.value) : '不收（0 VP）');
    body.append(make('div', 'item-tooltip-meta', meta.join(' · ')));
    box.append(iconBox, body);
    return box;
  }

  function show(row, anchor) {
    window.clearTimeout(hideTimer);
    const box = fillTip(row);
    box.classList.add('open');
    const rect = anchor.getBoundingClientRect();
    const boxRect = box.getBoundingClientRect();
    let left = rect.right + 10;
    let top = rect.top + rect.height / 2 - boxRect.height / 2;
    if (left + boxRect.width > window.innerWidth - 8) {
      left = Math.max(8, rect.left - boxRect.width - 10);
    }
    if (top + boxRect.height > window.innerHeight - 8) {
      top = window.innerHeight - boxRect.height - 8;
    }
    if (top < 8) top = 8;
    box.style.left = Math.round(left) + 'px';
    box.style.top = Math.round(top) + 'px';
  }

  function hide() {
    if (!tip) return;
    hideTimer = window.setTimeout(() => tip.classList.remove('open'), 80);
  }

  // 物品图标统一使用构建期预渲染的 /items/<id>.png（与原版物品栏模型一致）。
  function icon(row, options) {
    const opts = options || {};
    const box = make('span', 'item-icon ' + (opts.size || 'sm'));
    if (row.icon) {
      const img = document.createElement('img');
      img.src = '/items/' + row.icon + '.png';
      img.alt = '';
      img.onerror = () => {
        img.remove();
        box.classList.add('text');
        box.textContent = String(row.zh || row.name || '?').slice(0, 1);
      };
      if (row.id === 'minecraft:enchanted_golden_apple') img.classList.add('glint');
      box.append(img);
    } else {
      box.classList.add('text');
      box.textContent = String(row.zh || row.name || '?').slice(0, 1);
    }
    box.tabIndex = 0;
    box.setAttribute('aria-label', row.zh || row.name || row.id || '物品');
    box.addEventListener('mouseenter', () => show(row, box));
    box.addEventListener('mouseleave', hide);
    box.addEventListener('focus', () => show(row, box));
    box.addEventListener('blur', hide);
    return box;
  }

  window.addEventListener('scroll', hide, true);

  window.SamsaraItem = { icon, fmtVp, show, hide };
})();
