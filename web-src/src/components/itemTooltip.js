import { reactive } from 'vue';

// 全局物品悬浮提示：所有槽位/物品库共用同一套 Minecraft 风格 tooltip。
export const itemTooltip = reactive({
  visible: false,
  item: null,
  x: 0,
  y: 0,
});

const WIDTH = 306;
const HEIGHT = 380;

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

export function showItemTooltip(item, event) {
  if (!item) {
    hideItemTooltip();
    return;
  }
  itemTooltip.item = item;
  itemTooltip.visible = true;
  moveItemTooltip(event);
}

export function moveItemTooltip(event) {
  if (!itemTooltip.visible || !event) return;
  const px = Number(event.clientX || 0) + 18;
  const py = Number(event.clientY || 0) + 18;
  itemTooltip.x = clamp(px, 8, Math.max(8, window.innerWidth - WIDTH));
  itemTooltip.y = clamp(py, 8, Math.max(8, window.innerHeight - HEIGHT));
}

export function hideItemTooltip() {
  itemTooltip.visible = false;
  itemTooltip.item = null;
}
