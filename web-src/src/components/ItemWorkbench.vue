<template>
  <div class="item-workbench">
    <div v-if="showToolbar" class="workbench-toolbar">
      <div class="workbench-mode">
        <span class="mode-dot" :class="mode"></span>
        <strong>{{ modeLabel }}</strong>
        <span v-if="modeHint" class="mode-flag" :title="modeHint" :aria-label="modeHint">!</span>
      </div>
      <div class="spacer"></div>
      <span v-if="busy" class="muted small">处理中…</span>
      <button
        v-if="trash"
        class="workbench-trash"
        :class="{ armed: !!cursor }"
        type="button"
        title="垃圾桶：拿起物品后点这里删除（或把物品拖进来）"
        @click="trashCursor"
        @dragover.prevent
        @drop.prevent="trashDrop"
      >
        <img class="workbench-trash-icon" :src="trashIconSrc" alt="垃圾桶" draggable="false" />
      </button>
    </div>

    


    <div class="workbench-body">
      <div
        ref="gridsEl"
        class="workbench-grids"
        :class="{ 'workbench-grids-lazy': hasMore }"
        @scroll.passive="onGridsScroll"
      >
        <div
          v-for="(row, rowIndex) in layoutRows"
          :key="'row-' + rowIndex"
          class="workbench-row"
          :class="['workbench-row-' + row.zone, {
            'workbench-row-gap': row.gap,
            'workbench-row-grid': !!row.grid,
          }]"
          :style="row.grid ? {
            gridTemplateColumns: row.grid.columns || 'repeat(3, 48px)',
            gridTemplateRows: row.grid.rows || 'auto',
            gap: row.grid.gap || '6px',
          } : null"
        >
          <div v-if="doll && row.zone === 'gear'" class="workbench-doll">
            <slot name="doll" />
          </div>
          <section
            v-for="section in row.sections"
            :key="section.label + section.start"
            class="workbench-section"
            :style="section.area ? { gridArea: section.area, alignSelf: section.align || 'start' } : null"
          >
            <div v-if="section.label" class="workbench-section-title">{{ section.label }}</div>
            <ItemSlotGrid
              :slots="slotsFor(section)"
              :items="itemsFor(section)"
              :start="section.start"
              :selected="selectedIndex !== null && selectedIndex >= section.start
                && selectedIndex < section.start + section.size ? selectedIndex - section.start : null"
              :columns="section.columns || 9"
              :has-cursor="!!cursor"
              :editable="editable"
              :hints="section.hints || []"
              @slot-click="onSectionClick(section, $event)"
              @slot-edit="onSectionEdit(section, $event)"
              @slot-hover="onSlotHover"
              @slot-leave="onSlotLeave"
              @drag-start="onSectionDragStart(section, $event)"
              @drag-end="onDragEnd"
              @drop-slot="onSectionDrop(section, $event)"
            />
          </section>
          <button
            v-if="trashInline && rowIndex === layoutRows.length - 1"
            class="workbench-trash-inline"
            :class="{ armed: !!cursor }"
            type="button"
            title="垃圾桶：拿起物品后点这里删除（或把物品拖进来）"
            @click="trashCursor"
            @dragover.prevent
            @drop.prevent="trashDrop"
          >
            <img :src="trashIconSrc" alt="垃圾桶" draggable="false" />
          </button>
        </div>
        <p v-if="!layout.length" class="muted small">没有可显示的槽位。</p>
      </div>

    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import ItemSlotGrid from './ItemSlotGrid.vue';
import { hideItemTooltip, showItemTooltip } from './itemTooltip.js';
import { clearItemCursor, itemCursor, nextWorkbenchId } from '../composables/itemCursor.js';
import { useApi } from '../composables/useApi.js';

const props = defineProps({
  slots: { type: Array, default: () => [] },
  items: { type: Array, default: () => [] },
  layout: { type: Array, default: () => [] },
  schema: { type: Object, default: () => ({}) },
  mode: { type: String, default: 'simulate' },
  itemEndpoint: { type: String, default: '/api/containers/item' },
  revision: { type: Number, default: 0 },
  disabled: { type: Boolean, default: false },
  trash: { type: Boolean, default: true },
  trashInline: { type: Boolean, default: false },
  unlimitedStack: { type: Boolean, default: false },
  autoStack: { type: Boolean, default: false },
  hasMore: { type: Boolean, default: false },
  doll: { type: Boolean, default: false },
  editable: { type: Boolean, default: false },
  showToolbar: { type: Boolean, default: true },
});
const emit = defineEmits(['change', 'select', 'need-more']);
const sourceId = nextWorkbenchId();
const gridsEl = ref(null);

const localSlots = ref([...(props.slots || [])]);
const localItems = ref([...(props.items || [])]);
const selectedIndex = ref(null);
// 光标是全局共享的：在这里拿起后，可以在任意其他物品栏里点击放下。
const cursor = computed({
  get: () => itemCursor.item,
  set: (value) => {
    if (!value) {
      clearItemCursor();
      return;
    }
    itemCursor.item = value;
    if (!itemCursor.sourceId) itemCursor.sourceId = sourceId;
  },
});
const cursorOrigin = computed({
  get: () => (itemCursor.item && itemCursor.sourceId === sourceId ? itemCursor.sourceIndex : null),
  set: (value) => {
    if (value === null || value === undefined) {
      if (itemCursor.sourceId === sourceId) itemCursor.sourceIndex = null;
      return;
    }
    itemCursor.sourceId = sourceId;
    itemCursor.sourceIndex = value;
  },
});
const busy = ref(false);
const workbenchError = ref('');
const workbenchMessage = ref('');
// 动态绑定：避免 Vite 把 /ui/ 资源当成构建期 import（这些文件由后端静态目录提供）。
const trashIconSrc = '/ui/creative_trash.png';
let dragSource = null;
// 同一 encoded + 数量反复出现（拿半组、放回、合并）时不必每次都请求服务端重编码。
const countCache = new Map();
const { json } = useApi();
const api = (path, options) => json(path, options, '物品操作失败');

const modeLabel = computed(() => {
  if (props.mode === 'live') return '实时';
  if (props.mode === 'preset') return '预设';
  return '模拟';
});
const modeHint = computed(() => {
  if (props.mode === 'live') return '每次操作立即写入；离线/未加载目标会排队。';
  if (props.mode === 'preset') return '本地编辑，点保存一次性提交；刷新/重置会丢弃未提交修改。';
  return '只在本地演练，不会写入服务器。';
});

const itemMap = computed(() => {
  const map = new Map();
  for (const item of localItems.value) map.set(item.slot, item);
  return map;
});

// 同一个 row 的分区横向并排；未声明 row 的分区保持原来的逐行堆叠，
// 这样容器/基金仓库等旧布局不受影响，玩家背包可以还原游戏内 GUI 的相对位置。
const layoutRows = computed(() => {
  const rows = [];
  for (const section of props.layout) {
    const name = section.row || '';
    const last = rows[rows.length - 1];
    if (name && last && last.name === name) {
      last.sections.push(section);
      last.gap = last.gap || !!section.gap;
      last.grid = last.grid || section.grid || null;
    } else {
      rows.push({
        name,
        zone: section.zone || (name || 'plain'),
        gap: !!section.gap,
        grid: section.grid || null,
        sections: [section],
      });
    }
  }
  return rows;
});
const selectedItem = computed(() => {
  if (selectedIndex.value === null) return null;
  const slotItem = itemMap.value.get(selectedIndex.value) || null;
  if (slotItem) return slotItem;
  // 点击拿起后槽位为空，编辑器继续编辑“光标上的物品”，放下时写回新槽位。
  if (cursor.value && cursorOrigin.value === selectedIndex.value) return cursor.value;
  return null;
});

watch(() => props.slots, (value) => {
  if (!sameList(value, localSlots.value)) {
    localSlots.value = [...(value || [])];
  }
});
watch(() => props.items, (value) => {
  const incoming = value || [];
  if (JSON.stringify(incoming) !== JSON.stringify(localItems.value)) {
    localItems.value = [...incoming];
  }
});

function sameList(a, b) {
  if ((a || []).length !== (b || []).length) return false;
  for (let i = 0; i < (a || []).length; i++) {
    if ((a[i] || '') !== (b[i] || '')) return false;
  }
  return true;
}

function itemAt(index) {
  return itemMap.value.get(index) || null;
}

function slotsFor(section) {
  return localSlots.value.slice(section.start, section.start + section.size)
    .concat(Array(Math.max(0, section.size - localSlots.value.slice(section.start, section.start + section.size).length)).fill(''));
}

function itemsFor(section) {
  return localItems.value
    .filter((item) => item.slot >= section.start && item.slot < section.start + section.size)
    .map((item) => Object.assign({}, item, { slot: item.slot - section.start }));
}

function commit(reason) {
  emit('change', {
    slots: [...localSlots.value],
    items: [...localItems.value],
    pending: false,
    reason,
    revision: props.revision,
  });
  emit('select', selectedItem.value);
}

function setSlot(index, item) {
  if (index < 0 || index >= localSlots.value.length) return;
  localSlots.value[index] = item ? item.encoded : '';
  localItems.value = localItems.value.filter((row) => row.slot !== index);
  if (item) localItems.value.push(Object.assign({}, item, { slot: index }));
}

/**
 * 无限堆叠仓库（基金仓库）的空位选择：
 * 不重排已有物品，只把新物品放进最早的空格，避免中间出现无意义的空洞。
 */
function firstEmptySlot() {
  return localSlots.value.findIndex((value) => !value);
}

function findMergeTarget(index, item) {
  if (!props.autoStack || !item) return null;
  return localItems.value.find((row) => row.slot !== index && sameSignature(row, item)) || null;
}

async function withCount(item, count) {
  if (!item) return null;
  if (count <= 0) return null;
  if (item.encoded && Number(item.count) === count) return item;
  const key = [item.encoded || item.id || '', count, props.unlimitedStack ? 1 : 0].join('|');
  const cached = countCache.get(key);
  if (cached) return Object.assign({}, item, cached, { count });
  const data = await api(props.itemEndpoint, {
    method: 'POST',
    body: JSON.stringify({ encoded: item.encoded, count, unlimited: props.unlimitedStack }),
  });
  const changed = Object.assign({}, data, { count });
  if (countCache.size > 400) countCache.clear();
  countCache.set(key, changed);
  return Object.assign({}, item, changed);
}

function sameSignature(a, b) {
  if (!a || !b) return false;
  if (a.signature && b.signature) return a.signature === b.signature;
  return a.id === b.id && String(a.components || '') === String(b.components || '');
}

function sectionIndex(section, payload) {
  return section.start + Number(payload?.index ?? 0);
}

function onSectionClick(section, payload) {
  return onSlotClick(Object.assign({}, payload, { index: sectionIndex(section, payload) }));
}

function onSectionEdit(section, payload) {
  hideItemTooltip();
  selectedIndex.value = sectionIndex(section, payload);
  emit('select', selectedItem.value);
}

function onSectionDragStart(section, payload) {
  return onDragStart(Object.assign({}, payload, { index: sectionIndex(section, payload) }));
}

function onSectionDrop(section, payload) {
  return onDrop(Object.assign({}, payload, { index: sectionIndex(section, payload) }));
}

function onSlotHover({ item, event }) {
  if (item) showItemTooltip(item, event);
  else hideItemTooltip();
}

function onSlotLeave() {
  hideItemTooltip();
}



onBeforeUnmount(() => {
  hideItemTooltip();
});

async function onSlotClick({ index, button, shift }) {
  if (props.disabled || busy.value) return;
  selectedIndex.value = index;
  if (shift) {
    await quickMove(index);
    return;
  }
  busy.value = true;
  workbenchError.value = '';
  let changed = false;
  try {
    const slotItem = itemAt(index);
    if (!cursor.value) {
      if (!slotItem) return;
      cursorOrigin.value = index;
      const take = button === 2 ? Math.ceil(slotItem.count / 2) : slotItem.count;
      const picked = await withCount(slotItem, take);
      if (!picked) return;
      cursor.value = picked;
      setSlot(index, take >= slotItem.count ? null : await withCount(slotItem, slotItem.count - take));
      changed = true;
    } else {
      // 基金仓库这类无限堆叠仓库：只要仓库里已有同签名堆叠，就把新物品并进去，
      // 不管目标格是空的、堆叠已满，还是放着另一种物品。这样新物品不会另占一格。
      const mergeTarget = sameSignature(slotItem, cursor.value)
        ? null : findMergeTarget(index, cursor.value);
      if (mergeTarget) {
        const place = button === 2 ? 1 : cursor.value.count;
        setSlot(mergeTarget.slot,
          await withCount(mergeTarget, Number(mergeTarget.count) + place));
        selectedIndex.value = mergeTarget.slot;
        cursor.value = place >= cursor.value.count
          ? null : await withCount(cursor.value, cursor.value.count - place);
        if (!cursor.value) cursorOrigin.value = null;
        changed = true;
      } else if (!slotItem) {
        const place = button === 2 ? 1 : cursor.value.count;
        // 仓库内部搬动尊重用户点选的格子；从外部带入的新物品才落到最早空位，
        // 既保证密集堆积，又不会把用户主动整理的物品又弹回原处。
        const destination = props.autoStack && cursorOrigin.value === null
          ? firstEmptySlot() : index;
        setSlot(destination >= 0 ? destination : index, await withCount(cursor.value, place));
        selectedIndex.value = destination >= 0 ? destination : index;
        cursor.value = place >= cursor.value.count
          ? null : await withCount(cursor.value, cursor.value.count - place);
        if (!cursor.value) cursorOrigin.value = null;
        changed = true;
      } else if (sameSignature(slotItem, cursor.value)) {
        const max = props.unlimitedStack
          ? Number.MAX_SAFE_INTEGER
          : Math.min(slotItem.maxStack || 64, cursor.value.maxStack || 64);
        const space = max - slotItem.count;
        if (space <= 0) return;
        const place = button === 2 ? 1 : Math.min(space, cursor.value.count);
        setSlot(index, await withCount(slotItem, slotItem.count + place));
        cursor.value = place >= cursor.value.count
          ? null : await withCount(cursor.value, cursor.value.count - place);
        changed = true;
      } else if (button !== 2) {
        const previous = slotItem;
        setSlot(index, cursor.value);
        cursor.value = previous;
        cursorOrigin.value = index;
        changed = true;
      }
    }
  } catch (e) {
    ElMessage.error(e.message || String(e));
  } finally {
    busy.value = false;
    if (changed) {
      commit('interact');
    }
  }
}

async function quickMove(index) {
  const item = itemAt(index);
  if (!item) return;
  busy.value = true;
  let changed = false;
  try {
    let target = localItems.value.find((row) =>
      row.slot !== index && sameSignature(row, item)
        && Number(row.count) < Number(row.maxStack || 64));
    if (target) {
      const space = (props.unlimitedStack ? Number.MAX_SAFE_INTEGER
      : Number(target.maxStack || 64)) - Number(target.count);
      const move = Math.min(space, Number(item.count));
      setSlot(target.slot, await withCount(target, Number(target.count) + move));
      setSlot(index, move >= Number(item.count) ? null : await withCount(item, Number(item.count) - move));
      changed = true;
    } else {
      const empty = localSlots.value.findIndex((slot, i) => i !== index && !slot);
      if (empty >= 0) {
        setSlot(empty, item);
        setSlot(index, null);
        selectedIndex.value = empty;
        changed = true;
      }
    }
  } catch (e) {
    ElMessage.error(e.message || String(e));
  } finally {
    busy.value = false;
    if (changed) {
      commit('interact');
    }
  }
}

function onDragStart({ index, event }) {
  dragSource = index;
  if (event?.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', String(index));
  }
}

function onDragEnd() {
  dragSource = null;
}

function trashCursor() {
  if (!cursor.value) {
    workbenchError.value = '';
    ElMessage.info('垃圾桶：先拿起物品再点这里，或把物品拖进来。');
    return;
  }
  cursor.value = null;
  cursorOrigin.value = null;
  ElMessage.success('已丢进垃圾桶。');
  commit('interact');
}

function trashDrop() {
  if (dragSource === null) return;
  const source = dragSource;
  dragSource = null;
  setSlot(source, null);
  ElMessage.success('已丢进垃圾桶。');
  commit('interact');
}

async function onDrop({ index }) {
  if (props.disabled || busy.value || dragSource === null || dragSource === index) return;
  if (cursor.value) return;
  const source = dragSource;
  dragSource = null;
  await onSlotClick({ index: source, button: 0, shift: false });
  await onSlotClick({ index, button: 0, shift: false });
}

async function libraryPick(row) {
  if (props.disabled || busy.value) return;
  busy.value = true;
  workbenchError.value = '';
  try {
    const base = await api(props.itemEndpoint, {
      method: 'POST',
      body: JSON.stringify({ id: row.id, count: 1 }),
    });
    // Shift+点击创造物品：和原版一样拿一整组；不可堆叠物品仍然是 1 个。
    const wanted = row.shift ? Math.max(1, Number(base.maxStack || 1)) : 1;
    const data = wanted > 1
      ? await api(props.itemEndpoint, {
        method: 'POST',
        body: JSON.stringify({ id: row.id, count: wanted }),
      })
      : base;
    const item = Object.assign({}, data, {
      count: wanted,
      signature: data.signature || data.id,
    });
    // 和原版创造模式一样：点击创造物品先放到光标上，再点槽位放下。
    // 旧的光标物品直接被替换（等同删除），不能保留上一个槽位的来源标记。
    itemCursor.sourceId = null;
    itemCursor.sourceIndex = null;
    cursor.value = item;
    cursorOrigin.value = null;
  } catch (e) {
    ElMessage.error(e.message || String(e));
  } finally {
    busy.value = false;
  }
}

function applyEditorItem(item) {
  if (selectedIndex.value === null) return;
  if (!localSlots.value[selectedIndex.value] && cursor.value
      && cursorOrigin.value === selectedIndex.value) {
    cursor.value = item;
  } else {
    setSlot(selectedIndex.value, item);
  }
  commit('edit');
}

function resetSelection() {
  selectedIndex.value = null;
  cursor.value = null;
  cursorOrigin.value = null;
  workbenchError.value = '';
  workbenchMessage.value = '';
}

function onGridsScroll() {
  const node = gridsEl.value;
  if (!node || !props.hasMore) return;
  if (node.scrollTop + node.clientHeight >= node.scrollHeight - 48) emit('need-more');
}

function clearSelection() {
  selectedIndex.value = null;
}

defineExpose({
  resetSelection,
  clearSelection,
  pickFromLibrary: libraryPick,
  applyExternalItem: applyEditorItem,
  trashCursor,
});
</script>

<style scoped>
.item-workbench {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.workbench-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel-soft);
}

.workbench-mode {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.workbench-hover-hint {
  margin-left: auto;
  color: var(--muted);
  font-size: 10px;
  line-height: 1.35;
}

.workbench-trash-inline {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  align-self: flex-end;
  width: var(--slot-size, 48px);
  height: var(--slot-size, 48px);
  padding: 0;
  border: 0;
  border-radius: 0;
  background: #8b8b8b url('/ui/slot.png') center / 100% 100% no-repeat;
  cursor: pointer;
}

.workbench-trash-inline img {
  width: 72%;
  height: 72%;
  image-rendering: pixelated;
}

.workbench-trash-inline:hover { box-shadow: inset 0 0 0 2px #fff; }
.workbench-trash-inline.armed { box-shadow: inset 0 0 0 2px #d9534f; }

.workbench-trash {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  padding: 0;
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
  cursor: pointer;
}

.workbench-trash-icon {
  width: 32px;
  height: 32px;
  image-rendering: pixelated;
  pointer-events: none;
}

.workbench-trash.armed {
  border-color: #d9534f;
  background: rgba(217, 83, 79, .12);
  color: #ff8a85;
}

.mode-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--muted);
}

.mode-dot.live {
  background: #3fb950;
}

.mode-dot.preset {
  background: #d29922;
}

.mode-dot.simulate {
  background: #8b949e;
}

.workbench-body {
  position: relative;
  display: block;
  min-width: 0;
}

.workbench-body > .workbench-grids {
  width: 100%;
}

.workbench-grids {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  padding: 8px;
  border: 2px solid #373737;
  border-radius: 4px;
  background: #c6c6c6;
  overflow: auto;
}

.workbench-grids-lazy {
  max-height: 58vh;
}

.workbench-row {
  display: flex;
  flex-wrap: nowrap;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}

.workbench-row-grid {
  display: grid;
  align-items: start;
  width: max-content;
}

.workbench-row-grid .workbench-section {
  min-width: 0;
}

/* 玩家背包 GUI：装备/副手在上，主背包居中，快捷栏在下并留间隔。 */
.workbench-row-gear {
  align-items: flex-end;
  gap: 18px;
  padding-left: 4px;
}

.workbench-doll {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  min-width: 176px;
}

.workbench-row-gear .workbench-section:last-child {
  margin-bottom: 2px;
}

.workbench-row-main {
  margin-top: 6px;
}

.workbench-row-hotbar,
.workbench-row-gap {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 2px solid #8b8b8b;
}

.workbench-section-title {
  margin-bottom: 4px;
  color: #373737;
  font-size: 11px;
  font-weight: 700;
  text-shadow: 0 1px 0 #e8e8e8;
}

.workbench-panel-head h4 {
  margin: 0;
}

.workbench-floating-cursor {
  position: fixed;
  z-index: 99998;
  pointer-events: none;
  filter: drop-shadow(0 6px 8px rgba(0, 0, 0, .45));
  transform: translate(0, 0) scale(1.08);
  transform-origin: top left;
}

.workbench-web-picker {
  padding: 9px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
}

.workbench-web-picker-head {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 6px;
}

.workbench-web-list {
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-height: 220px;
  overflow: auto;
}

.workbench-web-row {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  padding: 4px 6px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.workbench-web-row:hover {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.workbench-web-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}

.ghost.tiny {
  padding: 2px 7px;
  font-size: 11px;
}


</style>


