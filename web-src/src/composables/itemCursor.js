import { reactive } from 'vue';

/**
 * 全局物品光标：所有物品栏（容器、玩家、身份、基金、Web 背包）共用同一份
 * “手上的物品”，这样可以在不同窗口之间直接点击转移，而不需要存取按钮。
 *
 * 拿起时源槽位会立刻按实时写入落库；放下时目标物品栏再落库。
 * 若中途关闭页面，物品会停留在光标上，因此界面必须始终显示光标 chip。
 */
// 页面可能被拆成多个异步 chunk；把状态挂到 globalThis 上，
// 保证各 chunk 里的模块副本仍共享同一份光标状态。
const shared = globalThis.__samsaraItemCursor
  || (globalThis.__samsaraItemCursor = reactive({
    item: null,
    sourceId: 0,
    sourceIndex: null,
    sequence: 0,
  }));

export function nextWorkbenchId() {
  shared.sequence += 1;
  return shared.sequence;
}

export const itemCursor = shared;

export function clearItemCursor() {
  itemCursor.item = null;
  itemCursor.sourceId = 0;
  itemCursor.sourceIndex = null;
}

export function hasItemCursor() {
  return !!itemCursor.item;
}
