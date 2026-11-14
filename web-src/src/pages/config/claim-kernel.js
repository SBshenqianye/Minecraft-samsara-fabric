/**
 * 圈地范围的算法内核：染料色板/图案浮窗、旗帜合成器、图案方向编辑与 canvas 预览。
 * 算法保持旧实现不变，只把数据读写改为外部注入的 getValue/setValue，页面主体由 Vue 渲染。
 */
/* eslint-disable no-useless-assignment */
import { icon as bannerIcon, paintBanner } from './banners.js';

export function createClaimKernel({ getValue, setValue, buttonGroup = null, onReady = null }) {
const DYE_PALETTE = [
  { name: '白', id: 'white', hex: '#f9fffe' },
  { name: '橙', id: 'orange', hex: '#f9801d' },
  { name: '品红', id: 'magenta', hex: '#c74ebd' },
  { name: '淡蓝', id: 'light_blue', hex: '#3ab3da' },
  { name: '黄', id: 'yellow', hex: '#fed83d' },
  { name: '黄绿', id: 'lime', hex: '#80c71f' },
  { name: '粉', id: 'pink', hex: '#f38baa' },
  { name: '灰', id: 'gray', hex: '#474f52' },
  { name: '淡灰', id: 'light_gray', hex: '#9d9d97' },
  { name: '青', id: 'cyan', hex: '#169c9c' },
  { name: '紫', id: 'purple', hex: '#8932b8' },
  { name: '蓝', id: 'blue', hex: '#3c44aa' },
  { name: '棕', id: 'brown', hex: '#835432' },
  { name: '绿', id: 'green', hex: '#5e7c16' },
  { name: '红', id: 'red', hex: '#b02e26' },
  { name: '黑', id: 'black', hex: '#1d1d21' },
];

/** 8 方向，按角度顺序（北→东北→…→西北）；x 向东，z 向南。 */
const CLAIM_DIRECTIONS = [
  { name: '北', dx: 0, dz: -1 },
  { name: '东北', dx: 1, dz: -1 },
  { name: '东', dx: 1, dz: 0 },
  { name: '东南', dx: 1, dz: 1 },
  { name: '南', dx: 0, dz: 1 },
  { name: '西南', dx: -1, dz: 1 },
  { name: '西', dx: -1, dz: 0 },
  { name: '西北', dx: -1, dz: -1 },
];

const CLAIM_PATTERNS = {
  border: { label: '全边框' },
  cross: { label: '交叉' },
  straight_cross: { label: '正向十字' },
  stripe_top: { label: '顶部条纹' },
  stripe_bottom: { label: '底部条纹' },
  stripe_left: { label: '左侧条纹' },
  stripe_right: { label: '右侧条纹' },
  square_top_left: { label: '左上角' },
  square_top_right: { label: '右上角' },
  square_bottom_left: { label: '左下角' },
  square_bottom_right: { label: '右下角' },
  stripe_downleft: { label: '左上斜纹' },
  stripe_downright: { label: '右上斜纹' },
  stripe_center: { label: '居中竖纹' },
  stripe_middle: { label: '居中横纹' },
};

const CLAIM_DIRECTION_NAMES = [
  'north', 'northeast', 'east', 'southeast',
  'south', 'southwest', 'west', 'northwest', 'center',
];
const CLAIM_DIRECTION_LABELS = ['北', '东北', '东', '东南', '南', '西南', '西', '西北', '边框'];
const CLAIM_DIRECTION_GLYPHS = ['↑', '↗', '→', '↘', '↓', '↙', '←', '↖', '▣'];
// 3×3 罗盘位置：列、行（1-based）；center 占正中间
const CLAIM_DIRECTION_CELLS = [
  [2, 1], [3, 1], [3, 2], [3, 3],
  [2, 3], [1, 3], [1, 2], [1, 1], [2, 2],
];
const DEFAULT_CLAIM_PATTERN_DIRECTIONS = {
  stripe_top: ['north'],
  stripe_bottom: ['south'],
  stripe_left: ['west'],
  stripe_right: ['east'],
  square_top_left: ['northwest'],
  square_top_right: ['northeast'],
  square_bottom_left: ['southwest'],
  square_bottom_right: ['southeast'],
  stripe_downleft: ['northeast', 'southwest'],
  stripe_downright: ['northwest', 'southeast'],
  stripe_center: ['north', 'south'],
  stripe_middle: ['east', 'west'],
  cross: ['northeast', 'southeast', 'southwest', 'northwest'],
  straight_cross: ['north', 'east', 'south', 'west'],
  border: ['center'],
};

const CLAIM_FACINGS = [
  { id: 'north', label: '北', offset: 0 },
  { id: 'east', label: '东', offset: 2 },
  { id: 'south', label: '南', offset: 4 },
  { id: 'west', label: '西', offset: 6 },
];

// ---- 可复用浮窗：4×4 染料色板 / 带预览的图案网格 ----

let openClaimPopover = null;

function clampNumber(value, min, max, fallback) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.max(min, Math.min(max, Math.round(parsed)));
}

function colorDistanceValue(index, range) {
  const cfg = range || {};
  const max = clampNumber(cfg.maxDistance, 1, 32, 32);
  const mode = cfg.mode === 'fixed' ? 'fixed' : 'dye';
  const base = mode === 'fixed'
    ? clampNumber(cfg.fixedDistance, 1, max, 1)
    : (Array.isArray(cfg.colorDistances) && cfg.colorDistances[index] != null
      ? Number(cfg.colorDistances[index]) : index + 1);
  return clampNumber(base, 1, max, 1);
}

function closeClaimPopover() {
  if (!openClaimPopover) return;
  const current = openClaimPopover;
  openClaimPopover = null;
  current.cleanup?.();
  current.element.remove();
}

function mountClaimPopover(pop, anchor, focusTarget) {
  closeClaimPopover();
  pop.className = 'claim-popover ' + (pop.className || '');
  document.body.append(pop);
  const margin = 8;
  const rect = anchor.getBoundingClientRect();
  const width = pop.offsetWidth;
  const height = pop.offsetHeight;
  let left = Math.min(Math.max(margin, rect.left), window.innerWidth - width - margin);
  let top = rect.bottom + 6;
  if (top + height > window.innerHeight - margin) top = rect.top - height - 6;
  top = Math.max(margin, top);
  pop.style.left = Math.round(left) + 'px';
  pop.style.top = Math.round(top) + 'px';

  const onPointerDown = (event) => {
    if (!pop.contains(event.target) && !anchor.contains(event.target)) closeClaimPopover();
  };
  const onKeyDown = (event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeClaimPopover();
      try { anchor.focus({ preventScroll: true }); } catch (e) { /* ignore */ }
    }
  };
  const onViewportChange = () => closeClaimPopover();
  document.addEventListener('pointerdown', onPointerDown, true);
  document.addEventListener('keydown', onKeyDown, true);
  window.addEventListener('resize', onViewportChange);
  openClaimPopover = {
    element: pop,
    anchor,
    cleanup: () => {
      document.removeEventListener('pointerdown', onPointerDown, true);
      document.removeEventListener('keydown', onKeyDown, true);
      window.removeEventListener('resize', onViewportChange);
    },
  };
  requestAnimationFrame(() => {
    try { (focusTarget || pop.querySelector('button'))?.focus({ preventScroll: true }); } catch (e) { /* ignore */ }
  });
}

/** 4×4 浮窗色板；16 个 MC 染料颜色，每格显示色块 + 名称 + 序号 + 当前模式距离。 */
function openColorPalette(anchor, options) {
  const opts = options || {};
  const range = opts.range || {};
  const selected = clampNumber(opts.selected, 0, 15, 0);
  const pop = document.createElement('div');
  pop.className = 'claim-popover claim-color-popover';
  pop.setAttribute('role', 'dialog');
  pop.setAttribute('aria-label', opts.title || '选择颜色');

  const head = document.createElement('div');
  head.className = 'claim-popover-head';
  const title = document.createElement('strong');
  title.textContent = opts.title || '选择颜色';
  const hint = document.createElement('span');
  hint.className = 'muted small';
  hint.textContent = opts.hint
    || (range.mode === 'fixed'
      ? '固定模式：所有颜色使用同一距离'
      : '染料模式：距离取该颜色对应的区块数');
  head.append(title, hint);

  const grid = document.createElement('div');
  grid.className = 'claim-color-grid';
  grid.setAttribute('role', 'grid');
  const buttons = [];
  for (let index = 0; index < DYE_PALETTE.length; index += 1) {
    const dye = DYE_PALETTE[index];
    const distance = colorDistanceValue(index, range);
    const cell = document.createElement('button');
    cell.type = 'button';
    cell.className = 'claim-color-cell' + (index === selected ? ' selected' : '');
    cell.dataset.index = String(index);
    cell.setAttribute('role', 'gridcell');
    cell.setAttribute('aria-pressed', index === selected ? 'true' : 'false');
    cell.setAttribute('aria-label', dye.name + '（' + index + '），' + distance + ' 格');
    cell.title = dye.name + '（' + index + '） · ' + distance + ' 格';

    const swatch = document.createElement('span');
    swatch.className = 'claim-color-swatch';
    swatch.style.background = dye.hex;
    const name = document.createElement('span');
    name.className = 'claim-color-name';
    name.textContent = dye.name;
    const meta = document.createElement('span');
    meta.className = 'claim-color-meta';
    meta.textContent = index + ' · ' + distance + ' 格';
    const check = document.createElement('span');
    check.className = 'claim-color-check';
    check.textContent = index === selected ? '✓' : '';
    check.setAttribute('aria-hidden', 'true');

    cell.append(swatch, name, meta, check);
    cell.addEventListener('click', () => {
      closeClaimPopover();
      opts.onPick?.(index);
      try { anchor.focus({ preventScroll: true }); } catch (e) { /* ignore */ }
    });
    buttons.push(cell);
    grid.append(cell);
  }

  grid.addEventListener('keydown', (event) => {
    const current = buttons.indexOf(document.activeElement);
    if (current < 0) return;
    let next = current;
    if (event.key === 'ArrowRight') next = Math.min(15, current + 1);
    else if (event.key === 'ArrowLeft') next = Math.max(0, current - 1);
    else if (event.key === 'ArrowDown') next = Math.min(15, current + 4);
    else if (event.key === 'ArrowUp') next = Math.max(0, current - 4);
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = 15;
    else return;
    event.preventDefault();
    buttons[next].focus({ preventScroll: true });
  });

  const foot = document.createElement('div');
  foot.className = 'claim-popover-foot';
  foot.textContent = range.mode === 'fixed'
    ? '当前 fixedDistance = ' + colorDistanceValue(selected, range) + ' 区块'
    : '颜色 0（白）→ 15（黑）；数字是单方向延伸区块数';

  pop.append(head, grid, foot);
  mountClaimPopover(pop, anchor, buttons[selected] || buttons[0]);
  return pop;
}

/** 4 列图案网格；每格用 SamsaraBanners 画出「当前图案颜色 + 当前旗面颜色」的真实预览。 */
function openPatternGrid(anchor, options) {
  const opts = options || {};
  const selected = CLAIM_PATTERNS[opts.selected] ? opts.selected : 'border';
  const baseDye = DYE_PALETTE[opts.baseColor] || DYE_PALETTE[0];
  const patternDye = DYE_PALETTE[opts.patternColor] || DYE_PALETTE[0];
  const pop = document.createElement('div');
  pop.className = 'claim-popover claim-pattern-popover';
  pop.setAttribute('role', 'dialog');
  pop.setAttribute('aria-label', opts.title || '选择图案');

  const head = document.createElement('div');
  head.className = 'claim-popover-head';
  const title = document.createElement('strong');
  title.textContent = opts.title || '选择图案';
  const hint = document.createElement('span');
  hint.className = 'muted small';
  hint.textContent = '预览：图案颜色 ' + patternDye.name + ' / 旗面颜色 ' + baseDye.name;
  head.append(title, hint);

  const grid = document.createElement('div');
  grid.className = 'claim-pattern-grid';
  const buttons = [];
  Object.entries(CLAIM_PATTERNS).forEach(([id, pattern], index) => {
    const cell = document.createElement('button');
    cell.type = 'button';
    cell.className = 'claim-pattern-cell' + (id === selected ? ' selected' : '');
    cell.dataset.pattern = id;
    cell.setAttribute('aria-pressed', id === selected ? 'true' : 'false');
    cell.setAttribute('aria-label', pattern.label);
    cell.title = pattern.label + '（' + id + '）';

    const iconWrap = document.createElement('span');
    iconWrap.className = 'claim-pattern-cell-icon';
    if (bannerIcon) {
      const icon = bannerIcon([{ id, color: opts.patternColor }], baseDye.hex, 40);
      icon.className = 'claim-pattern-canvas';
      iconWrap.append(icon);
    } else {
      const placeholder = document.createElement('span');
      placeholder.className = 'claim-pattern-placeholder';
      placeholder.textContent = '…';
      iconWrap.append(placeholder);
    }
    const label = document.createElement('span');
    label.className = 'claim-pattern-name';
    label.textContent = pattern.label;
    const code = document.createElement('span');
    code.className = 'claim-pattern-code';
    code.textContent = id;
    const check = document.createElement('span');
    check.className = 'claim-pattern-check';
    check.textContent = id === selected ? '✓' : '';
    check.setAttribute('aria-hidden', 'true');
    cell.append(iconWrap, label, code, check);
    cell.addEventListener('click', () => {
      closeClaimPopover();
      opts.onPick?.(id);
      try { anchor.focus({ preventScroll: true }); } catch (e) { /* ignore */ }
    });
    buttons.push(cell);
    grid.append(cell);
  });

  grid.addEventListener('keydown', (event) => {
    const current = buttons.indexOf(document.activeElement);
    if (current < 0) return;
    let next = current;
    if (event.key === 'ArrowRight') next = Math.min(buttons.length - 1, current + 1);
    else if (event.key === 'ArrowLeft') next = Math.max(0, current - 1);
    else if (event.key === 'ArrowDown') next = Math.min(buttons.length - 1, current + 4);
    else if (event.key === 'ArrowUp') next = Math.max(0, current - 4);
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = buttons.length - 1;
    else return;
    event.preventDefault();
    buttons[next].focus({ preventScroll: true });
  });

  const foot = document.createElement('div');
  foot.className = 'claim-popover-foot';
  foot.textContent = '图案决定单位方向；“边框”按图案颜色距离画正方形边框。图案颜色决定射线/边框长度（区块）。';

  pop.append(head, grid, foot);
  mountClaimPopover(pop, anchor, buttons.find((button) => button.dataset.pattern === selected) || buttons[0]);
  return pop;
}

/** 紧凑颜色触发器：约 30px 色块 + 短名称 + 序号/距离，不再使用 260px 下拉框。 */
function createColorTrigger(options) {
  const opts = options || {};
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'claim-trigger claim-color-trigger';
  button.dataset.role = opts.role || 'color-trigger';
  const swatch = document.createElement('span');
  swatch.className = 'claim-trigger-swatch';
  const name = document.createElement('span');
  name.className = 'claim-trigger-label';
  const meta = document.createElement('span');
  meta.className = 'claim-trigger-meta';
  button.append(swatch, name, meta);

  let value = clampNumber(opts.value, 0, 15, 0);
  const rangeOf = typeof opts.range === 'function' ? opts.range : () => opts.range || {};
  const sync = (next) => {
    if (next != null) value = clampNumber(next, 0, 15, 0);
    const range = rangeOf();
    const dye = DYE_PALETTE[value] || { name: '颜色 ' + value, hex: '#94a3b8' };
    const distance = colorDistanceValue(value, range);
    swatch.style.background = dye.hex;
    name.textContent = dye.name;
    meta.textContent = value + ' · ' + distance + ' 格';
    button.dataset.value = String(value);
    button.title = (opts.title || '颜色') + '：' + dye.name + '（' + value + '） · ' + distance + ' 格';
    button.setAttribute('aria-label', button.title);
  };
  button.addEventListener('click', () => {
    if (openClaimPopover && openClaimPopover.anchor === button) {
      closeClaimPopover();
      return;
    }
    const range = rangeOf();
    openColorPalette(button, {
      title: opts.title || '选择颜色',
      hint: opts.hint,
      selected: value,
      range,
      onPick: (index) => {
        value = index;
        sync();
        opts.onPick?.(index);
      },
    });
  });
  sync(opts.value);
  return { element: button, set: sync, get: () => value };
}

/** 紧凑图案触发器：小预览图标 + 图案名。 */
function createPatternTrigger(options) {
  const opts = options || {};
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'claim-trigger claim-pattern-trigger';
  button.dataset.role = opts.role || 'pattern-trigger';
  const iconWrap = document.createElement('span');
  iconWrap.className = 'claim-trigger-icon';
  const label = document.createElement('span');
  label.className = 'claim-trigger-label';
  button.append(iconWrap, label);

  let value = CLAIM_PATTERNS[opts.value] ? opts.value : 'border';
  const dynamicValue = (option, fallback) => (typeof option === 'function' ? option() : option) ?? fallback;
  const sync = (next) => {
    if (next != null && CLAIM_PATTERNS[next]) value = next;
    iconWrap.replaceChildren();
    if (bannerIcon) {
      const baseHex = (DYE_PALETTE[dynamicValue(opts.baseColor, 0)] || DYE_PALETTE[0]).hex;
      const icon = bannerIcon(
          [{ id: value, color: dynamicValue(opts.patternColor, 0) }], baseHex, 28);
      icon.className = 'claim-trigger-canvas';
      iconWrap.append(icon);
    } else {
      const placeholder = document.createElement('span');
      placeholder.className = 'claim-trigger-placeholder';
      placeholder.textContent = '…';
      iconWrap.append(placeholder);
    }
    label.textContent = CLAIM_PATTERNS[value]?.label || value;
    button.dataset.value = value;
    button.title = (opts.title || '图案') + '：' + label.textContent;
    button.setAttribute('aria-label', button.title);
  };
  button.addEventListener('click', () => {
    if (openClaimPopover && openClaimPopover.anchor === button) {
      closeClaimPopover();
      return;
    }
    openPatternGrid(button, {
      title: opts.title || '选择图案',
      selected: value,
      patternColor: dynamicValue(opts.patternColor, 0),
      baseColor: dynamicValue(opts.baseColor, 0),
      onPick: (id) => {
        value = id;
        sync();
        opts.onPick?.(id);
      },
    });
  });
  sync(opts.value);
  return { element: button, set: sync, get: () => value };
}

function normalizeDirectionNames(list) {
  const source = Array.isArray(list) ? list : [];
  return CLAIM_DIRECTION_NAMES.filter((name) => source.some((item) => String(item).toLowerCase() === name));
}

function createBannerComposer(getSelected, onFill, getRange) {
  const wrap = document.createElement('div');
  wrap.className = 'banner-composer';
  const head = document.createElement('div');
  head.className = 'banner-composer-head';
  const title = document.createElement('strong');
  title.textContent = '旗帜选择器 / 合成器';
  const hint = document.createElement('span');
  hint.className = 'muted small';
  hint.textContent = '旗面颜色与每层图案颜色都用 4×4 色板；图案用带真实预览的网格。颜色决定射线长度，图案决定射线方向。';
  head.append(title, hint);

  const body = document.createElement('div');
  body.className = 'banner-composer-body';
  const preview = document.createElement('canvas');
  preview.className = 'banner-composer-preview';
  preview.width = 64;
  preview.height = 80;
  preview.title = '旗帜预览';

  const controls = document.createElement('div');
  controls.className = 'banner-composer-controls';
  const baseWrap = document.createElement('div');
  baseWrap.className = 'inline-field banner-composer-base';
  const baseLabel = document.createElement('span');
  baseLabel.textContent = '旗面颜色';
  const rangeOf = typeof getRange === 'function' ? getRange : () => ({
    mode: 'dye', fixedDistance: 4, colorDistances: [], maxDistance: 32,
  });
  const baseTrigger = createColorTrigger({
    value: 15,
    title: '旗面颜色',
    role: 'base-color-trigger',
    range: rangeOf,
    onPick: (index) => {
      state.baseColor = index;
      renderLayers();
      refresh();
    },
  });
  baseWrap.append(baseLabel, baseTrigger.element);

  const layers = document.createElement('div');
  layers.className = 'banner-composer-layers';
  const actions = document.createElement('div');
  actions.className = 'banner-composer-actions';
  const add = document.createElement('button');
  add.type = 'button';
  add.className = 'ghost';
  add.textContent = '+ 添加图案层';
  const load = document.createElement('button');
  load.type = 'button';
  load.className = 'ghost';
  load.textContent = '载入选中旗帜';
  const fill = document.createElement('button');
  fill.type = 'button';
  fill.className = 'primary';
  fill.textContent = '填入调试画布';
  actions.append(add, load, fill);
  controls.append(baseWrap, layers, actions);

  const commandWrap = document.createElement('div');
  commandWrap.className = 'banner-composer-command';
  const command = document.createElement('code');
  const copy = document.createElement('button');
  copy.type = 'button';
  copy.className = 'ghost';
  copy.textContent = '复制命令';
  commandWrap.append(command, copy);

  body.append(preview, controls);
  wrap.append(head, body, commandWrap);

  const state = { baseColor: 15, layers: [{ id: 'border', color: 0 }] };
  let layerTriggers = [];

  const commandText = () => {
    const base = DYE_PALETTE[state.baseColor] || DYE_PALETTE[0];
    const parts = state.layers.map((layer) => {
      const color = DYE_PALETTE[layer.color] || DYE_PALETTE[0];
      return '{pattern:"minecraft:' + layer.id + '",color:"' + color.id + '"}';
    });
    return '/give @p minecraft:' + base.id + '_banner[banner_patterns=['
      + parts.join(',') + ']] 1';
  };

  const refresh = () => {
    if (bannerIcon) {
      paintBanner(preview, state.layers,
        (DYE_PALETTE[state.baseColor] || DYE_PALETTE[0]).hex);
    }
    command.textContent = commandText();
    baseTrigger.set(state.baseColor);
    for (const trigger of layerTriggers) trigger.sync?.();
  };

  const renderLayers = () => {
    layers.replaceChildren();
    layerTriggers = [];
    state.layers.forEach((layer, index) => {
      const row = document.createElement('div');
      row.className = 'banner-composer-layer';
      const number = document.createElement('span');
      number.className = 'banner-composer-index';
      number.textContent = String(index + 1);

      const patternTrigger = createPatternTrigger({
        value: layer.id,
        title: '第 ' + (index + 1) + ' 层图案',
        role: 'layer-pattern-trigger',
        patternColor: () => layer.color,
        baseColor: () => state.baseColor,
        onPick: (id) => {
          layer.id = id;
          refresh();
        },
      });
      const colorTrigger = createColorTrigger({
        value: layer.color,
        title: '第 ' + (index + 1) + ' 层图案颜色',
        role: 'layer-color-trigger',
        range: rangeOf,
        onPick: (color) => {
          layer.color = color;
          patternTrigger.set(layer.id);
          refresh();
        },
      });
      layerTriggers.push({
        sync: () => {
          patternTrigger.set(layer.id);
          colorTrigger.set(layer.color);
        },
      });

      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'ghost danger';
      remove.textContent = '×';
      remove.title = '删除这一层';
      remove.addEventListener('click', () => {
        if (state.layers.length <= 1) return;
        state.layers.splice(index, 1);
        renderLayers();
        refresh();
      });
      row.append(number, patternTrigger.element, colorTrigger.element, remove);
      layers.append(row);
    });
  };

  add.addEventListener('click', () => {
    if (state.layers.length >= 8) return;
    state.layers.push({ id: 'border', color: 0 });
    renderLayers();
    refresh();
  });
  load.addEventListener('click', () => {
    const banner = getSelected();
    if (!banner) return;
    state.baseColor = banner.baseColor ?? 0;
    state.layers = (banner.patterns || []).map((layer) => ({ id: layer.id, color: layer.color }));
    if (state.layers.length === 0) state.layers = [{ id: 'border', color: 0 }];
    renderLayers();
    refresh();
  });
  fill.addEventListener('click', () => {
    onFill({
      baseColor: state.baseColor,
      patterns: state.layers.map((layer) => ({ id: layer.id, color: layer.color })),
    });
  });
  copy.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(command.textContent);
      copy.textContent = '已复制';
      setTimeout(() => { copy.textContent = '复制命令'; }, 1200);
    } catch (e) {
      const range = document.createRange();
      range.selectNodeContents(command);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    }
  });

  renderLayers();
  refresh();
  return {
    element: wrap,
    refresh: () => {
      refresh();
      baseTrigger.set(state.baseColor);
      for (const trigger of layerTriggers) trigger.sync?.();
    },
    loadSelected: () => load.click(),
    getState: () => state,
  };
}

function patternDirectionsEditor(file, entries, onTrial) {
  const wrap = document.createElement('div');
  wrap.className = 'pattern-directions';
  wrap.id = 'claim-pattern-directions';
  const head = document.createElement('div');
  head.className = 'pattern-directions-head';
  const title = document.createElement('strong');
  title.textContent = '图案单位方向';
  const hint = document.createElement('span');
  hint.className = 'muted small';
  hint.textContent = '图案只决定单位长度上的方向；颜色负责延长这些方向。方向随旗帜朝向旋转。';
  const reset = document.createElement('button');
  reset.type = 'button';
  reset.className = 'ghost';
  reset.textContent = '恢复默认方向';
  head.append(title, hint, reset);

  const note = document.createElement('div');
  note.className = 'pattern-directions-note';
  note.textContent = '统一采用“看旗人正面视角”：图标上方 = 北，下方 = 南，左侧 = 西，右侧 = 东。'
      + '点 3×3 罗盘上的方向即可开关；正中间的“▣ 边框”按图案颜色距离画正方形边框，颜色 = 边框半径，内部不填充。'
      + '例如“全边框”默认只选边框，白色距离 1 就是 3×3 周长，橙色距离 2 就是 5×5 周长。'
      + '内部由多面旗帜的射线闭合后填充；点“试用”可把该图案放到下方调试画布。';

  const grid = document.createElement('div');
  grid.className = 'pattern-direction-grid';
  const controls = [];
  for (const entry of entries) {
    const id = entry.path.split('.').pop();
    const row = document.createElement('div');
    row.className = 'pattern-direction-row';
    const icon = bannerIcon([{ id: id, color: 15 }], 0, 40);
    if (icon) {
      icon.className = 'pattern-direction-icon';
      icon.title = '原版 ' + id + ' 旗帜图案';
    }
    const meta = document.createElement('div');
    meta.className = 'pattern-direction-meta';
    const label = document.createElement('span');
    label.className = 'pattern-direction-name';
    label.textContent = CLAIM_PATTERNS[id]?.label || id;
    const code = document.createElement('code');
    code.textContent = id;
    meta.append(label, code);

    const picker = document.createElement('div');
    picker.className = 'pattern-direction-picker';
    const buttons = [];
    for (let direction = 0; direction < CLAIM_DIRECTION_NAMES.length; direction += 1) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'pattern-direction-cell'
        + (CLAIM_DIRECTION_NAMES[direction] === 'center' ? ' center' : '');
      button.textContent = CLAIM_DIRECTION_GLYPHS[direction];
      button.title = CLAIM_DIRECTION_LABELS[direction];
      button.style.gridColumn = String(CLAIM_DIRECTION_CELLS[direction][0]);
      button.style.gridRow = String(CLAIM_DIRECTION_CELLS[direction][1]);
      button.addEventListener('click', () => {
        button.classList.toggle('active');
        const selected = buttons
            .map((item, index) => item.classList.contains('active')
              ? CLAIM_DIRECTION_NAMES[index] : null)
            .filter(Boolean);
        row.classList.toggle('empty', selected.length === 0);
        setValue(entry, selected);
      });
      picker.append(button);
      buttons.push(button);
    }

    const setNames = (names) => {
      const normalized = normalizeDirectionNames(names);
      buttons.forEach((button, index) => {
        button.classList.toggle('active', normalized.includes(CLAIM_DIRECTION_NAMES[index]));
      });
      row.classList.toggle('empty', normalized.length === 0);
    };
    setNames(getValue(entry));

    row.append(...(icon ? [icon] : []), meta, picker);
    if (onTrial) {
      const trial = document.createElement('button');
      trial.type = 'button';
      trial.className = 'ghost pattern-trial';
      trial.textContent = '试用';
      trial.title = '在下方调试画布中使用这个图案';
      trial.addEventListener('click', (event) => {
        event.preventDefault();
        event.stopPropagation();
        onTrial(id);
      });
      row.append(trial);
    }
    grid.append(row);
    controls.push({ entry, row, setNames });
  }

  reset.addEventListener('click', () => {
    for (const control of controls) {
      const defaults = control.entry.default
        || DEFAULT_CLAIM_PATTERN_DIRECTIONS[control.entry.path.split('.').pop()]
        || [];
      control.setNames(defaults);
      setValue(control.entry, normalizeDirectionNames(defaults));
    }
  });

  wrap.append(head, note, grid);
  return wrap;
}


function buildIntList(file, entry, values) {
  const wrap = document.createElement('div');
  wrap.className = 'int-list-wrap';

  const tools = document.createElement('div');
  tools.className = 'int-list-tools';
  const hint = document.createElement('span');
  hint.className = 'int-list-hint';
  hint.textContent = '图案颜色 0（白）→ 15（黑）；数字为单方向延伸区块数（旗面颜色只影响外观）';
  const bulk = document.createElement('input');
  bulk.type = 'number';
  bulk.min = String(entry.min == null ? 1 : entry.min);
  bulk.max = String(entry.max == null ? 32 : entry.max);
  bulk.step = '1';
  bulk.value = String(values[0] == null ? (entry.default || [])[0] || 1 : values[0]);
  bulk.title = '把这个值应用到全部 16 种颜色';
  const bulkWrap = document.createElement('label');
  bulkWrap.className = 'int-list-bulk';
  const bulkText = document.createElement('span');
  bulkText.textContent = '统一设为';
  bulkWrap.append(bulkText, bulk);
  const apply = document.createElement('button');
  apply.type = 'button';
  apply.className = 'ghost';
  apply.textContent = '应用到全部';
  tools.append(hint, bulkWrap, apply);

  const grid = document.createElement('div');
  grid.className = 'int-list-grid';
  const defaults = Array.isArray(entry.default) ? entry.default : [];
  const count = defaults.length || values.length || DYE_PALETTE.length;
  const min = entry.min == null ? 1 : entry.min;
  const max = entry.max == null ? 32 : entry.max;
  const inputs = [];
  for (let i = 0; i < count; i += 1) {
    const dye = DYE_PALETTE[i] || { name: '颜色 ' + i, hex: '#94a3b8' };
    const tile = document.createElement('label');
    tile.className = 'int-list-tile';
    tile.title = dye.name + '（颜色 ' + i + '）';
    const swatch = document.createElement('span');
    swatch.className = 'int-list-swatch';
    swatch.style.background = dye.hex;
    const input = document.createElement('input');
    input.type = 'number';
    input.min = String(min);
    input.max = String(max);
    input.step = '1';
    input.value = String(values[i] == null ? (defaults[i] == null ? min : defaults[i]) : values[i]);
    input.setAttribute('aria-label', dye.name + ' 保护距离（区块）');
    input.addEventListener('input', () => {
      const next = inputs.map((el, idx) => {
        const parsed = Math.round(Number(el.value));
        if (!Number.isFinite(parsed)) return Number(defaults[idx] == null ? min : defaults[idx]);
        return Math.max(min, Math.min(max, parsed));
      });
      setValue(entry, next);
    });
    tile.append(swatch, input);
    grid.append(tile);
    inputs.push(input);
  }

  apply.addEventListener('click', () => {
    const parsed = Math.round(Number(bulk.value));
    if (!Number.isFinite(parsed)) return;
    const clamped = Math.max(min, Math.min(max, parsed));
    for (const input of inputs) input.value = String(clamped);
    setValue(entry, inputs.map(() => clamped));
  });

  wrap.append(tools, grid);
  return wrap;
}

function claimRangePreview(file) {
  const section = document.createElement('div');
  section.className = 'claim-preview';

  const head = document.createElement('div');
  head.className = 'claim-preview-head';
  const titles = document.createElement('div');
  const title = document.createElement('strong');
  title.textContent = '保护范围调试画布';
  const sub = document.createElement('span');
  sub.className = 'muted small';
  sub.textContent = '射线会参与全局闭合填充；边框只画正方形周长，不填内部。“闭合填充”开关可对比原始线段与算完闭合后的结果。滚轮缩放、空白处拖动平移、点击放置、拖动移动、右键或 R 旋转。';
  titles.append(title, sub);

  const readValue = (path, fallback) => {
    const entry = file.entries.find((item) => item.path === path);
    return entry ? getValue(entry) : fallback;
  };
  const patternIds = Object.keys(CLAIM_PATTERNS);
  const readConfig = () => ({
    mode: String(readValue('mode', 'dye')),
    fixedDistance: Number(readValue('fixedDistance', 4)) || 4,
    maxDistance: Math.max(1, Math.min(32, Number(readValue('maxDistance', 16)) || 16)),
    colorDistances: (() => {
      const value = readValue('colorDistances', []);
      return Array.isArray(value) ? value.map(Number) : [];
    })(),
    patternDirections: Object.fromEntries(patternIds.map((id) => {
      // 与服务端 ClaimRangeConfig.normalizeDirections 一致：配置为空时回退默认方向。
      const configured = normalizeDirectionNames(readValue('patternDirections.' + id,
          DEFAULT_CLAIM_PATTERN_DIRECTIONS[id] || []));
      // 旧配置迁移：border 过去是八向（或八向 + center），新语义统一为纯边框。
      if (id === 'border' && configured.filter((name) => name !== 'center').length >= 8) {
        configured.length = 0;
        configured.push('center');
      }
      return [id, configured.length ? configured : (DEFAULT_CLAIM_PATTERN_DIRECTIONS[id] || [])];
    })),
  });
  const rangeInfo = () => {
    const cfg = readConfig();
    return {
      mode: cfg.mode,
      fixedDistance: cfg.fixedDistance,
      colorDistances: cfg.colorDistances,
      maxDistance: cfg.maxDistance,
    };
  };

  const state = {
    banners: [],
    selected: -1,
    hover: null,
    drag: null,
    pan: null,
    pendingPlace: null,
    lastSummary: 0,
    fillClosed: true,
    view: { radius: 12, cx: 0, cz: 0 },
    draft: { patternId: 'border', color: 0, baseColor: 15, facing: 'north' },
  };

  const firstLayer = (banner) => (banner && banner.patterns && banner.patterns[0])
    || { id: state.draft.patternId, color: state.draft.color };
  const selectedBanner = () => state.banners[state.selected] || null;
  const activeLayer = () => {
    const banner = selectedBanner();
    return banner ? firstLayer(banner) : state.draft;
  };
  const activeBase = () => {
    const banner = selectedBanner();
    return banner ? Number(banner.baseColor ?? 0) : state.draft.baseColor;
  };

  const controls = document.createElement('div');
  controls.className = 'claim-preview-controls';

  const patternTrigger = createPatternTrigger({
    value: state.draft.patternId,
    title: '图案',
    role: 'canvas-pattern-trigger',
    patternColor: () => state.draft.color,
    baseColor: () => state.draft.baseColor,
    onPick: (id) => {
      activeLayer().id = id;
      state.draft.patternId = id;
      updateSummary();
    },
  });
  const patternColorTrigger = createColorTrigger({
    value: state.draft.color,
    title: '图案颜色',
    role: 'canvas-pattern-color-trigger',
    range: rangeInfo,
    onPick: (color) => {
      activeLayer().color = color;
      state.draft.color = color;
      patternTrigger.set(activeLayer().id);
      updateSummary();
    },
  });
  const baseColorTrigger = createColorTrigger({
    value: state.draft.baseColor,
    title: '旗面颜色',
    role: 'canvas-base-color-trigger',
    range: rangeInfo,
    onPick: (color) => {
      const banner = selectedBanner();
      if (banner) banner.baseColor = color;
      state.draft.baseColor = color;
      patternTrigger.set(activeLayer().id);
      updateSummary();
    },
  });

  let facingGroup = null;
  if (buttonGroup) {
    facingGroup = buttonGroup(
        CLAIM_FACINGS.map((facing) => ({ value: facing.id, label: '朝' + facing.label })),
        state.draft.facing,
        (id) => {
          const banner = selectedBanner();
          if (banner) banner.facing = id;
          state.draft.facing = id;
          updateSummary();
        },
        { className: 'compact' });
    facingGroup.id = 'claim-facing';
    facingGroup.title = '旗帜朝向（只支持 4 个正方向）';
  }
  controls.append(patternTrigger.element, patternColorTrigger.element,
      baseColorTrigger.element);
  if (facingGroup) controls.append(facingGroup);
  head.append(titles, controls);

  const toolbar = document.createElement('div');
  toolbar.className = 'claim-sim-toolbar';
  const chip = document.createElement('span');
  chip.className = 'claim-banner-chip';
  chip.draggable = true;
  chip.textContent = '拖到网格放置旗帜';
  const spacer = document.createElement('span');
  spacer.className = 'spacer';

  const zoomOut = document.createElement('button');
  zoomOut.type = 'button';
  zoomOut.id = 'claim-zoom-out';
  zoomOut.className = 'ghost';
  zoomOut.textContent = '−';
  zoomOut.title = '缩小（视野变大）';
  const zoomLabel = document.createElement('span');
  zoomLabel.id = 'claim-zoom-label';
  zoomLabel.className = 'claim-zoom-label';
  zoomLabel.textContent = '视野 12 格';
  const zoomIn = document.createElement('button');
  zoomIn.type = 'button';
  zoomIn.id = 'claim-zoom-in';
  zoomIn.className = 'ghost';
  zoomIn.textContent = '＋';
  zoomIn.title = '放大（视野变小）';
  const fitBtn = document.createElement('button');
  fitBtn.type = 'button';
  fitBtn.id = 'claim-zoom-fit';
  fitBtn.className = 'ghost';
  fitBtn.textContent = '适应内容';
  const resetBtn = document.createElement('button');
  resetBtn.type = 'button';
  resetBtn.id = 'claim-zoom-reset';
  resetBtn.className = 'ghost';
  resetBtn.textContent = '重置';
  const rotateBtn = document.createElement('button');
  rotateBtn.type = 'button';
  rotateBtn.className = 'ghost';
  rotateBtn.textContent = '旋转';
  const deleteBtn = document.createElement('button');
  deleteBtn.type = 'button';
  deleteBtn.className = 'ghost';
  deleteBtn.textContent = '删除选中';
  const clearBtn = document.createElement('button');
  clearBtn.type = 'button';
  clearBtn.className = 'ghost danger';
  clearBtn.textContent = '清空';
  const fillToggle = document.createElement('label');
  fillToggle.className = 'check-line claim-fill-toggle';
  const fillInput = document.createElement('input');
  fillInput.type = 'checkbox';
  fillInput.id = 'claim-fill-closed';
  fillInput.checked = state.fillClosed;
  fillInput.addEventListener('change', () => {
    state.fillClosed = fillInput.checked;
    updateSummary();
    draw();
  });
  const fillText = document.createElement('span');
  fillText.textContent = '闭合填充';
  fillToggle.append(fillInput, fillText);
  toolbar.append(chip, spacer, zoomOut, zoomLabel, zoomIn, fitBtn, resetBtn,
      rotateBtn, deleteBtn, clearBtn, fillToggle);

  const canvas = document.createElement('canvas');
  canvas.id = 'claim-sim-canvas';
  canvas.className = 'claim-preview-canvas claim-sim-canvas';
  canvas.tabIndex = 0;
  canvas.title = '点击放置旗帜；空白处拖动平移；滚轮缩放；拖动旗帜移动；右键/R 旋转；Delete 删除';
  const summary = document.createElement('div');
  summary.className = 'claim-preview-summary';
  const legend = document.createElement('div');
  legend.className = 'claim-preview-legend';
  legend.textContent = '绿色粒子 = 保护边界；青色线段 = 选中旗帜的射线/边框；琥珀色 = 选中旗帜范围。“闭合填充”关闭时只看原始线段。';

  section.append(head, toolbar, canvas, summary, legend);

  const size = 560;
  const pad = 26;
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(size * dpr);
  canvas.height = Math.round(size * dpr);
  canvas.style.width = '100%';
  canvas.style.maxWidth = size + 'px';
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = false;

  const clampRadius = (value) => Math.max(4, Math.min(64, Math.round(value)));
  const cellSize = () => (size - pad * 2) / (2 * state.view.radius + 1);
  const px = (x) => pad + (x - state.view.cx + state.view.radius) * cellSize();
  const pz = (z) => pad + (z - state.view.cz + state.view.radius) * cellSize();

  const iconCache = new Map();
  const patternIconCanvas = (patterns, baseColor) => {
    const key = JSON.stringify([patterns, baseColor]);
    if (iconCache.has(key)) return iconCache.get(key);
    const base = DYE_PALETTE[baseColor] || DYE_PALETTE[0];
    const iconCanvas = bannerIcon(patterns, base.hex, 64);
    iconCache.set(key, iconCanvas);
    return iconCanvas;
  };

  const distanceOfColor = (color, cfg) => {
    const index = Math.max(0, Math.min(15, Number(color) || 0));
    const base = cfg.mode === 'fixed' ? cfg.fixedDistance
      : (cfg.colorDistances[index] ?? index + 1);
    return Math.max(1, Math.min(cfg.maxDistance, Math.round(base)));
  };

  const distanceOf = (banner, cfg) => {
    const layers = banner.patterns || [];
    return layers.reduce((max, layer) => Math.max(max, distanceOfColor(layer.color, cfg)), 1);
  };

  const rotatedShape = (patternId, facing, cfg) => {
    const names = (cfg.patternDirections && cfg.patternDirections[patternId])
      || DEFAULT_CLAIM_PATTERN_DIRECTIONS[patternId] || [];
    const offset = CLAIM_FACINGS.find((item) => item.id === facing)?.offset || 0;
    const rays = [];
    let center = false;
    for (const name of normalizeDirectionNames(names)) {
      const index = CLAIM_DIRECTION_NAMES.indexOf(name);
      if (name === 'center') {
        center = true;
      } else if (index >= 0) {
        const rotated = (index + offset) % 8;
        if (!rays.includes(rotated)) rays.push(rotated);
      }
    }
    rays.sort((a, b) => a - b);
    return { rays, center };
  };

  /** 单面旗帜的八向射线。 */
  const raySegmentsFor = (banner, cfg) => {
    const out = [];
    for (const layer of banner.patterns || []) {
      const distance = distanceOfColor(layer.color, cfg);
      const shape = rotatedShape(layer.id, banner.facing, cfg);
      for (const index of shape.rays) {
        const dir = CLAIM_DIRECTIONS[index];
        out.push([banner.x, banner.z,
          banner.x + dir.dx * distance, banner.z + dir.dz * distance]);
      }
    }
    return out;
  };

  /** 单面旗帜的边框：图案颜色距离 = 半径，画正方形周长（不填充内部）。 */
  const borderSegmentsFor = (banner, cfg) => {
    const out = [];
    for (const layer of banner.patterns || []) {
      const distance = distanceOfColor(layer.color, cfg);
      const shape = rotatedShape(layer.id, banner.facing, cfg);
      if (!shape.center) continue;
      const x = banner.x;
      const z = banner.z;
      out.push([x - distance, z - distance, x + distance, z - distance]);
      out.push([x + distance, z - distance, x + distance, z + distance]);
      out.push([x + distance, z + distance, x - distance, z + distance]);
      out.push([x - distance, z + distance, x - distance, z - distance]);
    }
    return out;
  };

  /** 单面旗帜的线段：射线 + 边框（用于选中高亮）。 */
  const shapeSegments = (banner, cfg) => [
    ...raySegmentsFor(banner, cfg),
    ...borderSegmentsFor(banner, cfg),
  ];

  /** 单面旗帜：射线 + 边框（含中心），绝不单独填充。 */
  const cellsFor = (banner, cfg) => {
    const cells = new Set();
    // 旗帜所在区块始终属于保护范围；边框只额外贡献周长。
    cells.add(banner.x + ',' + banner.z);
    for (const segment of shapeSegments(banner, cfg)) {
      addLineCells(segment[0], segment[1], segment[2], segment[3],
          (x, z) => cells.add(x + ',' + z));
    }
    return cells;
  };

  const boundaryOf = (cells) => {
    const out = [];
    for (const key of cells) {
      const [x, z] = key.split(',').map(Number);
      if (!cells.has((x + 1) + ',' + z) || !cells.has((x - 1) + ',' + z)
          || !cells.has(x + ',' + (z + 1)) || !cells.has(x + ',' + (z - 1))) {
        out.push([x, z]);
      }
    }
    return out;
  };

  // ---- 与 Geometry.compute 同源的全局「化线为面」 ----
  const pointKey = (x, z) => x + ',' + z;
  const keyPoint = (key) => {
    const [x, z] = key.split(',').map(Number);
    return { x, z };
  };
  const gcd = (a, b) => {
    let x = Math.abs(a);
    let y = Math.abs(b);
    while (y) { const t = x % y; x = y; y = t; }
    return x;
  };
  const directionIndex = (dx, dz) => {
    const sx = Math.sign(dx);
    const sz = Math.sign(dz);
    const key = sx + ',' + sz;
    return { '0,-1': 0, '1,-1': 1, '1,0': 2, '1,1': 3,
      '0,1': 4, '-1,1': 5, '-1,0': 6, '-1,-1': 7 }[key];
  };
  const addGraphPoint = (points, x, z) => {
    points.add(pointKey(x, z));
  };
  const addGraphEdge = (adj, x1, z1, x2, z2) => {
    const a = pointKey(x1, z1);
    const b = pointKey(x2, z2);
    if (!adj.has(a)) adj.set(a, new Set());
    if (!adj.has(b)) adj.set(b, new Set());
    adj.get(a).add(b);
    adj.get(b).add(a);
  };
  const rasterLine = (points, adj, x1, z1, x2, z2) => {
    addGraphPoint(points, x1, z1);
    addGraphPoint(points, x2, z2);
    const steps = gcd(x2 - x1, z2 - z1);
    if (steps === 0) return;
    const sx = (x2 - x1) / steps;
    const sz = (z2 - z1) / steps;
    let px0 = x1;
    let pz0 = z1;
    for (let i = 0; i < steps; i += 1) {
      const nx = px0 + sx;
      const nz = pz0 + sz;
      // 与服务端 Geometry.rasterize 一致：中间格点也必须进入点集，
      // 否则 findFaces 只从端点出发，单旗射线会少掉中间格，Web 预览与服务端不一致。
      addGraphPoint(points, nx, nz);
      addGraphEdge(adj, px0, pz0, nx, nz);
      px0 = nx;
      pz0 = nz;
    }
  };
  const findFaces = (points, adj) => {
    const visited = new Set();
    const faces = [];
    for (const start of points) {
      const neighbors = adj.get(start) || new Set();
      for (const first of neighbors) {
        const edge = start + '->' + first;
        if (visited.has(edge)) continue;
        const face = new Set();
        let cur = start;
        let nxt = first;
        let bounded = true;
        const maxSteps = Math.max(16, points.size * 4);
        for (let step = 0; step < maxSteps; step += 1) {
          visited.add(cur + '->' + nxt);
          face.add(cur);
          if (nxt === start) break;
          const curPoint = keyPoint(cur);
          const nxtPoint = keyPoint(nxt);
          const reverse = directionIndex(curPoint.x - nxtPoint.x, curPoint.z - nxtPoint.z);
          let nextNode = null;
          let best = 9;
          for (const candidate of adj.get(nxt) || []) {
            if (candidate === cur) continue;
            const point = keyPoint(candidate);
            const dir = directionIndex(point.x - nxtPoint.x, point.z - nxtPoint.z);
            const diff = (dir - reverse + 8) % 8;
            if (diff > 0 && diff < best) {
              best = diff;
              nextNode = candidate;
            }
          }
          if (!nextNode) { bounded = false; break; }
          cur = nxt;
          nxt = nextNode;
        }
        if (bounded && face.size >= 3) faces.push(face);
      }
    }
    return faces;
  };
  const scanlineFill = (boundary, adj) => {
    const interior = new Set();
    if (boundary.size < 3) return interior;
    const edges = new Set();
    for (const key of boundary) {
      for (const neighbor of adj.get(key) || []) {
        if (!boundary.has(neighbor)) continue;
        const a = keyPoint(key);
        const b = keyPoint(neighbor);
        const ordered = (a.x < b.x || (a.x === b.x && a.z <= b.z))
          ? [a, b] : [b, a];
        edges.add(ordered[0].x + ',' + ordered[0].z + ',' + ordered[1].x + ',' + ordered[1].z);
      }
    }
    let minZ = Infinity;
    let maxZ = -Infinity;
    for (const key of boundary) {
      const point = keyPoint(key);
      minZ = Math.min(minZ, point.z);
      maxZ = Math.max(maxZ, point.z);
    }
    for (let z = minZ + 1; z < maxZ; z += 1) {
      const intersections = [];
      for (const edge of edges) {
        const [x1, z1, x2, z2] = edge.split(',').map(Number);
        if ((z1 < z && z <= z2) || (z2 < z && z <= z1)) {
          intersections.push(Math.round(x1 + (z - z1) / (z2 - z1) * (x2 - x1)));
        }
      }
      intersections.sort((a, b) => a - b);
      for (let i = 0; i + 1 < intersections.length; i += 2) {
        for (let x = intersections[i]; x <= intersections[i + 1]; x += 1) {
          const key = pointKey(x, z);
          if (!boundary.has(key)) interior.add(key);
        }
      }
    }
    return interior;
  };

  let geometryKey = '';
  let geometryCells = new Set();
  const claimGeometry = (banners, cfg) => {
    const key = JSON.stringify({
      banners,
      mode: cfg.mode,
      fixed: cfg.fixedDistance,
      colors: cfg.colorDistances,
      max: cfg.maxDistance,
      directions: cfg.patternDirections,
      fill: state.fillClosed,
    });
    if (key === geometryKey) return geometryCells;
    const points = new Set();
    const adj = new Map();
    const borderCells = new Set();
    for (const banner of banners) {
      addGraphPoint(points, banner.x, banner.z);
      // 射线参与全局化线为面；边框只做周长，不进入填充图。
      for (const segment of raySegmentsFor(banner, cfg)) {
        rasterLine(points, adj, segment[0], segment[1], segment[2], segment[3]);
      }
      for (const segment of borderSegmentsFor(banner, cfg)) {
        rasterLine(borderCells, new Map(), segment[0], segment[1], segment[2], segment[3]);
      }
    }
    const cells = new Set(points);
    for (const key of borderCells) cells.add(key);
    if (state.fillClosed) {
      for (const face of findFaces(points, adj)) {
        for (const cell of scanlineFill(face, adj)) cells.add(cell);
      }
    }
    geometryKey = key;
    geometryCells = cells;
    return cells;
  };

  const viewCellAt = (event) => {
    const rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    const x = (event.clientX - rect.left) * (size / rect.width);
    const y = (event.clientY - rect.top) * (size / rect.height);
    if (x < 0 || y < 0 || x > size || y > size) return null;
    const cell = cellSize();
    const gx = Math.floor((x - pad) / cell + state.view.cx - state.view.radius);
    const gz = Math.floor((y - pad) / cell + state.view.cz - state.view.radius);
    if (gx < state.view.cx - state.view.radius || gx > state.view.cx + state.view.radius
        || gz < state.view.cz - state.view.radius || gz > state.view.cz + state.view.radius) {
      return null;
    }
    return { x: gx, z: gz };
  };

  const bannerAt = (cell) => state.banners.findIndex((item) => item.x === cell.x && item.z === cell.z);

  const fitView = () => {
    const cfg = readConfig();
    let minX = Infinity;
    let maxX = -Infinity;
    let minZ = Infinity;
    let maxZ = -Infinity;
    const consider = (x, z) => {
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minZ = Math.min(minZ, z);
      maxZ = Math.max(maxZ, z);
    };
    if (state.banners.length === 0) {
      consider(0, 0);
    } else {
      for (const banner of state.banners) {
        const cells = cellsFor(banner, cfg);
        for (const key of cells) {
          const [x, z] = key.split(',').map(Number);
          consider(x, z);
        }
        consider(banner.x, banner.z);
      }
    }
    const margin = 3;
    const cx = (minX + maxX) / 2;
    const cz = (minZ + maxZ) / 2;
    const half = Math.max((maxX - minX) / 2, (maxZ - minZ) / 2) + margin;
    state.view.radius = clampRadius(half);
    state.view.cx = cx;
    state.view.cz = cz;
    updateZoomLabel();
  };

  const resetView = () => {
    state.view.radius = 12;
    state.view.cx = 0;
    state.view.cz = 0;
    updateZoomLabel();
  };

  const updateZoomLabel = () => {
    zoomLabel.textContent = '视野 ' + state.view.radius + ' 格';
    canvas.dataset.viewRadius = String(state.view.radius);
    canvas.dataset.viewCenter = state.view.cx.toFixed(2) + ',' + state.view.cz.toFixed(2);
  };

  const zoomAt = (event, deltaY) => {
    const rect = canvas.getBoundingClientRect();
    if (!rect.width) return;
    const sx = (event.clientX - rect.left) * (size / rect.width);
    const sy = (event.clientY - rect.top) * (size / rect.height);
    const oldRadius = state.view.radius;
    const nextRadius = clampRadius(oldRadius * (deltaY > 0 ? 1.2 : 1 / 1.2));
    if (nextRadius === oldRadius) return;
    const oldCell = (size - pad * 2) / (2 * oldRadius + 1);
    const focusX = (sx - pad) / oldCell + state.view.cx - oldRadius;
    const focusZ = (sy - pad) / oldCell + state.view.cz - oldRadius;
    state.view.radius = nextRadius;
    const newCell = (size - pad * 2) / (2 * nextRadius + 1);
    state.view.cx = focusX - (sx - pad) / newCell + nextRadius;
    state.view.cz = focusZ - (sy - pad) / newCell + nextRadius;
    updateZoomLabel();
  };

  const syncSelectors = () => {
    const banner = selectedBanner();
    if (banner) {
      const layer = firstLayer(banner);
      state.draft.patternId = layer.id;
      state.draft.color = layer.color;
      state.draft.baseColor = Number(banner.baseColor ?? 0);
      state.draft.facing = banner.facing || 'north';
    }
    patternTrigger.set(state.draft.patternId);
    patternColorTrigger.set(state.draft.color);
    baseColorTrigger.set(state.draft.baseColor);
    facingGroup?.setValue(state.draft.facing);
  };

  const rotateBanner = (banner) => {
    if (!banner) return;
    const index = Math.max(0, CLAIM_FACINGS.findIndex((item) => item.id === banner.facing));
    banner.facing = CLAIM_FACINGS[(index + 1) % CLAIM_FACINGS.length].id;
    state.draft.facing = banner.facing;
    facingGroup?.setValue(banner.facing);
    updateSummary();
  };

  const updateSummary = () => {
    const cfg = readConfig();
    const union = claimGeometry(state.banners, cfg);
    const boundary = boundaryOf(union);
    const banner = selectedBanner();
    let text = '已放置 ' + state.banners.length + ' 面 · 保护 ' + union.size
        + ' 区块 · 边界 ' + boundary.length + ' 格 · '
        + (state.fillClosed ? '闭合填充' : '原始线段');
    if (banner) {
      const layers = banner.patterns || [];
      const baseDye = DYE_PALETTE[banner.baseColor ?? 0] || { name: '颜色 ' + (banner.baseColor ?? 0) };
      const layerText = layers.map((layer) => {
        const dye = DYE_PALETTE[layer.color] || { name: '颜色 ' + layer.color };
        return (CLAIM_PATTERNS[layer.id]?.label || layer.id) + '/' + dye.name;
      }).join(' + ');
      const shapes = layers.map((layer) => rotatedShape(layer.id, banner.facing, cfg));
      const dirs = [...new Set(shapes.flatMap((shape) => shape.rays))]
          .map((index) => CLAIM_DIRECTION_LABELS[index])
          .concat(shapes.some((shape) => shape.center) ? ['边框'] : [])
          .join('+') || '无方向';
      const facingLabel = CLAIM_FACINGS.find((item) => item.id === banner.facing)?.label || '北';
      text += ' · 选中：' + (layerText || '无图案')
          + ' · 旗面' + baseDye.name
          + ' · 旗面朝 ' + facingLabel
          + ' · 方向 ' + dirs
          + ' → 距离 ' + distanceOf(banner, cfg) + ' 区块';
      canvas.dataset.facing = banner.facing;
    } else {
      text += ' · 点击网格放置第一面旗帜';
      canvas.dataset.facing = state.draft.facing;
    }
    summary.textContent = text;
    canvas.dataset.bannerCount = String(state.banners.length);
    canvas.dataset.protectedCells = String(union.size);
    canvas.dataset.pattern = banner ? firstLayer(banner).id : state.draft.patternId;
    canvas.dataset.patternColor = String(banner ? firstLayer(banner).color : state.draft.color);
    canvas.dataset.patternDistance = String(distanceOfColor(
        banner ? firstLayer(banner).color : state.draft.color, cfg));
    canvas.dataset.rayCells = String(banner ? cellsFor(banner, cfg).size : 0);
    canvas.dataset.fillClosed = state.fillClosed ? '1' : '0';
  };

  const drawFacing = (banner, cx, cz, cell, isSelected) => {
    const facing = CLAIM_FACINGS.find((item) => item.id === banner.facing) || CLAIM_FACINGS[0];
    const vector = CLAIM_DIRECTIONS[facing.offset];
    const angle = Math.atan2(vector.dz, vector.dx);
    const length = Math.max(cell * 1.15, 16);
    const spread = Math.PI / 6;

    ctx.beginPath();
    ctx.moveTo(cx, cz);
    ctx.arc(cx, cz, length * 1.2, angle - spread, angle + spread);
    ctx.closePath();
    ctx.fillStyle = isSelected ? 'rgba(251, 191, 36, .16)' : 'rgba(94, 234, 212, .09)';
    ctx.fill();

    const ex = cx + vector.dx * length;
    const ez = cz + vector.dz * length;
    ctx.strokeStyle = isSelected ? 'rgba(251, 191, 36, .95)' : 'rgba(226, 232, 240, .72)';
    ctx.lineWidth = isSelected ? 2 : 1.4;
    ctx.beginPath();
    ctx.moveTo(cx, cz);
    ctx.lineTo(ex, ez);
    ctx.stroke();

    const head = Math.max(4, cell * .3);
    const perp = angle + Math.PI / 2;
    ctx.beginPath();
    ctx.moveTo(ex, ez);
    ctx.lineTo(ex - Math.cos(angle) * head + Math.cos(perp) * head * .55,
        ez - Math.sin(angle) * head + Math.sin(perp) * head * .55);
    ctx.lineTo(ex - Math.cos(angle) * head - Math.cos(perp) * head * .55,
        ez - Math.sin(angle) * head - Math.sin(perp) * head * .55);
    ctx.closePath();
    ctx.fillStyle = isSelected ? 'rgba(251, 191, 36, .95)' : 'rgba(226, 232, 240, .72)';
    ctx.fill();

    if (isSelected) {
      ctx.font = 'bold 12px system-ui, sans-serif';
      ctx.fillStyle = '#fbbf24';
      ctx.textAlign = 'left';
      ctx.textBaseline = 'alphabetic';
      ctx.fillText('朝' + facing.label, cx + cell * .55, cz - cell * .55);
    }
  };

  const drawCompass = () => {
    const cx = size - 52;
    const cy = 52;
    const radius = 26;
    ctx.save();
    ctx.fillStyle = 'rgba(8, 12, 17, .74)';
    ctx.strokeStyle = 'rgba(148, 163, 184, .32)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.arc(cx, cy, radius + 9, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    const selectedFacing = selectedBanner()?.facing;
    for (const item of [
      { id: 'north', label: '北', dx: 0, dz: -1 },
      { id: 'east', label: '东', dx: 1, dz: 0 },
      { id: 'south', label: '南', dx: 0, dz: 1 },
      { id: 'west', label: '西', dx: -1, dz: 0 },
    ]) {
      const active = item.id === selectedFacing;
      ctx.strokeStyle = active ? '#fbbf24' : 'rgba(148, 163, 184, .5)';
      ctx.lineWidth = active ? 2.4 : 1.1;
      ctx.beginPath();
      ctx.moveTo(cx, cy);
      ctx.lineTo(cx + item.dx * (radius - 5), cy + item.dz * (radius - 5));
      ctx.stroke();
      ctx.fillStyle = active ? '#fbbf24' : 'rgba(226, 232, 240, .72)';
      ctx.font = (active ? 'bold ' : '') + '11px system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(item.label, cx + item.dx * (radius + 4), cy + item.dz * (radius + 4));
    }
    ctx.fillStyle = 'rgba(226, 232, 240, .9)';
    ctx.beginPath();
    ctx.arc(cx, cy, 2, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  };

  const draw = (now) => {
    if (!canvas.isConnected) return;
    const cfg = readConfig();
    const radius = state.view.radius;
    const cell = cellSize();

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, size, size);
    ctx.fillStyle = '#0a1220';
    ctx.fillRect(0, 0, size, size);

    ctx.strokeStyle = 'rgba(148, 163, 184, .10)';
    ctx.lineWidth = 1;
    const step = cell >= 14 ? 1 : (cell >= 7 ? 2 : 4);
    const firstX = Math.floor(state.view.cx - radius);
    const lastX = Math.ceil(state.view.cx + radius);
    const firstZ = Math.floor(state.view.cz - radius);
    const lastZ = Math.ceil(state.view.cz + radius);
    for (let i = firstX; i <= lastX; i += step) {
      ctx.beginPath();
      ctx.moveTo(px(i), pad);
      ctx.lineTo(px(i), size - pad);
      ctx.stroke();
    }
    for (let i = firstZ; i <= lastZ; i += step) {
      ctx.beginPath();
      ctx.moveTo(pad, pz(i));
      ctx.lineTo(size - pad, pz(i));
      ctx.stroke();
    }

    const union = claimGeometry(state.banners, cfg);

    ctx.fillStyle = 'rgba(45, 212, 191, .07)';
    for (const key of union) {
      const [x, z] = key.split(',').map(Number);
      ctx.fillRect(px(x), pz(z), cell, cell);
    }

    const time = now / 1000;
    const boundary = boundaryOf(union);
    for (const [x, z] of boundary) {
      const seed = ((x * 73856093) ^ (z * 19349663)) >>> 0;
      const baseX = px(x);
      const baseZ = pz(z);
      const edges = [];
      if (!union.has((x + 1) + ',' + z)) edges.push([1, 0]);
      if (!union.has((x - 1) + ',' + z)) edges.push([-1, 0]);
      if (!union.has(x + ',' + (z + 1))) edges.push([0, 1]);
      if (!union.has(x + ',' + (z - 1))) edges.push([0, -1]);
      edges.forEach(([ex, ez], edgeIndex) => {
        const count = 3;
        for (let i = 0; i < count; i += 1) {
          const wobble = ((seed >>> ((edgeIndex * 3 + i) % 16)) % 7) / 7;
          const along = (i + .5) / count + (wobble - .5) * .25;
          let dx = 0;
          let dz = 0;
          if (ex !== 0) {
            dx = ex > 0 ? cell + 1 : -1;
            dz = along * cell;
          } else {
            dx = along * cell;
            dz = ez > 0 ? cell + 1 : -1;
          }
          const alpha = .35 + .45 * (0.5 + 0.5 * Math.sin(time * 3.2 + (seed % 100) / 12 + i));
          const dot = Math.max(1, Math.round(cell * .16));
          ctx.globalAlpha = alpha;
          ctx.fillStyle = i === 1 ? '#a7f3d0' : (i === 2 ? '#5eead4' : '#2dd4bf');
          ctx.fillRect(Math.round(baseX + dx), Math.round(baseZ + dz), dot, dot);
        }
      });
    }
    ctx.globalAlpha = 1;

    const selected = selectedBanner();
    if (selected) {
      const selectedCells = cellsFor(selected, cfg);
      ctx.fillStyle = 'rgba(251, 191, 36, .14)';
      for (const key of selectedCells) {
        const [x, z] = key.split(',').map(Number);
        ctx.fillRect(px(x), pz(z), cell, cell);
      }
      ctx.fillStyle = 'rgba(251, 191, 36, .95)';
      for (const [x, z] of boundaryOf(selectedCells)) {
        ctx.fillRect(Math.round(px(x) + cell * .35), Math.round(pz(z) + cell * .35),
            Math.max(2, cell * .3), Math.max(2, cell * .3));
      }
    }

    state.banners.forEach((banner, index) => {
      const cx = px(banner.x) + cell / 2;
      const cz = pz(banner.z) + cell / 2;
      const isSelected = index === state.selected;
      drawFacing(banner, cx, cz, cell, isSelected);
      if (isSelected) {
        ctx.strokeStyle = 'rgba(251, 191, 36, .95)';
        ctx.lineWidth = 2;
        ctx.strokeRect(px(banner.x) + 1.5, pz(banner.z) + 1.5, cell - 3, cell - 3);
      }
      const icon = patternIconCanvas(banner.patterns || [], banner.baseColor ?? 0);
      const iconSize = Math.max(12, Math.min(cell * .9, 42));
      if (icon) {
        ctx.drawImage(icon, Math.round(cx - iconSize / 2), Math.round(cz - iconSize / 2),
            Math.round(iconSize), Math.round(iconSize));
      }
    });

    const ghost = state.drag ? state.drag.cell : state.hover;
    if (ghost && (state.drag || bannerAt(ghost) < 0)) {
      const ghostBanner = state.drag
        ? Object.assign({}, state.banners[state.drag.index], { x: ghost.x, z: ghost.z })
        : {
          x: ghost.x,
          z: ghost.z,
          patterns: [{ id: state.draft.patternId, color: state.draft.color }],
          baseColor: state.draft.baseColor,
          facing: state.draft.facing,
        };
      const ghostCells = cellsFor(ghostBanner, cfg);
      ctx.fillStyle = 'rgba(251, 191, 36, .08)';
      for (const key of ghostCells) {
        const [gx, gz] = key.split(',').map(Number);
        ctx.fillRect(px(gx), pz(gz), cell, cell);
      }
      const ghostBoundary = boundaryOf(ghostCells);
      ctx.fillStyle = 'rgba(251, 191, 36, .9)';
      for (const [gx, gz] of ghostBoundary) {
        const dot = Math.max(1, Math.round(cell * .16));
        ctx.fillRect(Math.round(px(gx) + cell * .5), Math.round(pz(gz) + cell * .5), dot, dot);
      }

      const gx = px(ghost.x);
      const gz = pz(ghost.z);
      ctx.strokeStyle = 'rgba(94, 234, 212, .85)';
      ctx.setLineDash([4, 3]);
      ctx.lineWidth = 1.5;
      ctx.strokeRect(gx + 1, gz + 1, cell - 2, cell - 2);
      ctx.setLineDash([]);
      ctx.globalAlpha = .45;
      ctx.fillStyle = (DYE_PALETTE[state.draft.baseColor] || { hex: '#2dd4bf' }).hex;
      ctx.fillRect(gx + cell * .3, gz + cell * .3, cell * .4, cell * .4);
      ctx.globalAlpha = 1;
    }

    drawCompass();

    if (now - state.lastSummary > 220) {
      state.lastSummary = now;
      updateSummary();
      updateZoomLabel();
    }
    state.raf = requestAnimationFrame(draw);
  };

  const placeBanner = (cell) => {
    state.banners.push({
      x: cell.x,
      z: cell.z,
      patterns: [{ id: state.draft.patternId, color: state.draft.color }],
      baseColor: state.draft.baseColor,
      facing: state.draft.facing,
    });
    state.selected = state.banners.length - 1;
    syncSelectors();
    updateSummary();
  };

  const schedulePlace = (cell) => {
    if (state.pendingPlace) clearTimeout(state.pendingPlace);
    state.pendingPlace = setTimeout(() => {
      state.pendingPlace = null;
      placeBanner(cell);
    }, 220);
  };

  zoomIn.addEventListener('click', () => {
    state.view.radius = clampRadius(state.view.radius / 1.25);
    updateZoomLabel();
  });
  zoomOut.addEventListener('click', () => {
    state.view.radius = clampRadius(state.view.radius * 1.25);
    updateZoomLabel();
  });
  fitBtn.addEventListener('click', fitView);
  resetBtn.addEventListener('click', resetView);
  rotateBtn.addEventListener('click', () => rotateBanner(selectedBanner()));
  deleteBtn.addEventListener('click', () => {
    if (state.selected < 0 || !state.banners[state.selected]) return;
    state.banners.splice(state.selected, 1);
    state.selected = state.banners.length ? Math.min(state.selected, state.banners.length - 1) : -1;
    syncSelectors();
    updateSummary();
  });
  clearBtn.addEventListener('click', () => {
    state.banners = [];
    state.selected = -1;
    syncSelectors();
    updateSummary();
  });

  chip.addEventListener('dragstart', (event) => {
    try { event.dataTransfer.setData('text/plain', 'samsara-banner'); } catch (e) { /* ignore */ }
    event.dataTransfer.effectAllowed = 'copy';
  });
  canvas.addEventListener('dragover', (event) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
    state.hover = viewCellAt(event);
  });
  canvas.addEventListener('drop', (event) => {
    event.preventDefault();
    const cell = viewCellAt(event);
    if (!cell) return;
    placeBanner(cell);
  });

  canvas.addEventListener('pointermove', (event) => {
    const cell = viewCellAt(event);
    state.hover = cell;
    if (state.drag && cell) state.drag.cell = cell;
    if (state.pan) {
      const rect = canvas.getBoundingClientRect();
      if (rect.width) {
        const dx = (event.clientX - state.pan.startX) * (size / rect.width);
        const dy = (event.clientY - state.pan.startY) * (size / rect.height);
        if (Math.abs(dx) > 3 || Math.abs(dy) > 3) state.pan.moved = true;
        const cellW = cellSize();
        state.view.cx = state.pan.startCx - dx / cellW;
        state.view.cz = state.pan.startCz - dy / cellW;
        updateZoomLabel();
      }
    }
    canvas.style.cursor = state.pan
      ? (state.pan.moved ? 'grabbing' : 'grab')
      : (state.drag ? 'grabbing' : (cell && bannerAt(cell) >= 0 ? 'move' : 'crosshair'));
  });
  canvas.addEventListener('pointerleave', () => {
    state.hover = null;
  });
  canvas.addEventListener('pointerdown', (event) => {
    if (event.button === 2) return;
    canvas.focus();
    const cell = viewCellAt(event);
    const found = cell ? bannerAt(cell) : -1;
    if (event.button === 0 && found >= 0) {
      state.selected = found;
      state.drag = { index: found, cell: { x: cell.x, z: cell.z } };
      syncSelectors();
      try { canvas.setPointerCapture(event.pointerId); } catch (e) { /* ignore */ }
      return;
    }
    if (event.button !== 0) return;
    state.pan = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      startCx: state.view.cx,
      startCz: state.view.cz,
      moved: false,
      cell,
    };
    try { canvas.setPointerCapture(event.pointerId); } catch (e) { /* ignore */ }
    canvas.style.cursor = 'grab';
  });
  canvas.addEventListener('pointerup', (event) => {
    if (state.drag) {
      const cell = viewCellAt(event) || state.drag.cell;
      const banner = state.banners[state.drag.index];
      if (banner) {
        banner.x = cell.x;
        banner.z = cell.z;
      }
      state.drag = null;
      state.hover = null;
      updateSummary();
      return;
    }
    if (state.pan) {
      const pan = state.pan;
      state.pan = null;
      if (!pan.moved && pan.cell) schedulePlace(pan.cell);
      state.hover = viewCellAt(event);
    }
  });
  canvas.addEventListener('pointercancel', () => {
    state.drag = null;
    state.pan = null;
  });
  canvas.addEventListener('contextmenu', (event) => {
    event.preventDefault();
    const cell = viewCellAt(event);
    if (!cell) return;
    const found = bannerAt(cell);
    if (found >= 0) {
      state.selected = found;
      rotateBanner(state.banners[found]);
      syncSelectors();
    }
  });
  canvas.addEventListener('keydown', (event) => {
    if (event.key === 'Delete' || event.key === 'Backspace') {
      if (state.selected >= 0) {
        state.banners.splice(state.selected, 1);
        state.selected = state.banners.length ? Math.min(state.selected, state.banners.length - 1) : -1;
        syncSelectors();
        updateSummary();
        event.preventDefault();
      }
    } else if (event.key === 'r' || event.key === 'R') {
      rotateBanner(selectedBanner());
      syncSelectors();
      event.preventDefault();
    }
  });
  canvas.addEventListener('wheel', (event) => {
    event.preventDefault();
    zoomAt(event, event.deltaY);
  }, { passive: false });
  canvas.addEventListener('dblclick', (event) => {
    event.preventDefault();
    if (state.pendingPlace) {
      clearTimeout(state.pendingPlace);
      state.pendingPlace = null;
    }
    resetView();
  });

  const composer = createBannerComposer(
    () => selectedBanner(),
    (bannerData) => {
      let banner = selectedBanner();
      if (!banner) {
        banner = {
          x: 0, z: 0, patterns: [], baseColor: bannerData.baseColor,
          facing: state.draft.facing,
        };
        state.banners.push(banner);
        state.selected = state.banners.length - 1;
      }
      banner.baseColor = bannerData.baseColor;
      banner.patterns = bannerData.patterns.map((layer) => ({
        id: layer.id, color: layer.color,
      }));
      syncSelectors();
      updateSummary();
      canvas.classList.remove('claim-sim-flash');
      void canvas.offsetWidth;
      canvas.classList.add('claim-sim-flash');
      setTimeout(() => canvas.classList.remove('claim-sim-flash'), 1600);
    },
    rangeInfo);
  composer.element.id = 'claim-banner-composer';
  section.append(composer.element);

  // 首面旗帜：让画布一打开就有可操作对象。
  state.banners.push({
    x: 0,
    z: 0,
    patterns: [{ id: state.draft.patternId, color: state.draft.color }],
    baseColor: state.draft.baseColor,
    facing: state.draft.facing,
  });
  state.selected = 0;
  syncSelectors();
  updateZoomLabel();
  state.raf = requestAnimationFrame(draw);
  updateSummary();

  const api = {
    redraw: () => {
      syncSelectors();
      updateSummary();
      composer.refresh();
    },
    selectPattern: (id) => {
      if (!CLAIM_PATTERNS[id]) return;
      if (!state.banners.length) {
        state.draft.patternId = id;
        placeBanner({ x: 0, z: 0 });
      } else {
        const banner = selectedBanner();
        if (banner) firstLayer(banner).id = id;
        state.draft.patternId = id;
      }
      syncSelectors();
      updateSummary();
      canvas.classList.remove('claim-sim-flash');
      void canvas.offsetWidth;
      canvas.classList.add('claim-sim-flash');
      setTimeout(() => canvas.classList.remove('claim-sim-flash'), 1600);
    },
  };
  section.claimPreview = api;
  if (onReady) onReady(api);
  return section;
}

/** Bresenham：把射线端点之间的区块也纳入单旗射线预览。 */
function addLineCells(x1, z1, x2, z2, add) {
  let x = x1;
  let z = z1;
  const dx = Math.abs(x2 - x1);
  const dz = Math.abs(z2 - z1);
  const sx = x1 < x2 ? 1 : -1;
  const sz = z1 < z2 ? 1 : -1;
  let error = dx - dz;
  for (;;) {
    add(x, z);
    if (x === x2 && z === z2) break;
    const doubled = error * 2;
    if (doubled > -dz) {
      error -= dz;
      x += sx;
    }
    if (doubled < dx) {
      error += dx;
      z += sz;
    }
  }
}

  return { patternDirectionsEditor, claimRangePreview };
}
