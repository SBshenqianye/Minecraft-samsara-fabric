<template>
  <div class="item-workbench">
    <div class="workbench-toolbar">
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
      <span v-if="cursor" class="cursor-chip">
        光标：<ItemIcon :item="cursor" size="sm" :show-count="false" />
        <span>{{ cursor.name }} ×{{ cursor.count }}</span>
        <span v-if="!ownsCursor" class="muted small">（来自其他背包）</span>
        <button v-if="ownsCursor" class="ghost tiny" type="button" :disabled="busy" @click="returnCursor">放回</button>
      </span>
    </div>

    
    <p v-if="workbenchError" class="container-message error">{{ workbenchError }}</p>
    <p v-if="workbenchMessage" class="container-message ok">{{ workbenchMessage }}</p>


    <div class="workbench-body" :class="{ 'has-editor': !!selectedItem }">
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
          :class="['workbench-row-' + row.zone, { 'workbench-row-gap': row.gap }]"
        >
          <div v-if="doll && row.zone === 'gear'" class="workbench-doll">
            <slot name="doll" />
          </div>
          <section
            v-for="section in row.sections"
            :key="section.label + section.start"
            class="workbench-section"
          >
            <div v-if="section.label" class="workbench-section-title">{{ section.label }}</div>
            <ItemSlotGrid
              :slots="slotsFor(section)"
              :items="itemsFor(section)"
              :selected="selectedIndex !== null && selectedIndex >= section.start
                && selectedIndex < section.start + section.size ? selectedIndex - section.start : null"
              :columns="section.columns || 9"
              :has-cursor="!!cursor"
              @slot-click="onSectionClick(section, $event)"
              @slot-edit="onSectionEdit(section, $event)"
              @slot-hover="onSlotHover"
              @slot-leave="onSlotLeave"
              @drag-start="onSectionDragStart(section, $event)"
              @drag-end="onDragEnd"
              @drop-slot="onSectionDrop(section, $event)"
            />
          </section>
        </div>
        <p v-if="!layout.length" class="muted small">没有可显示的槽位。</p>
      </div>

      <aside v-if="selectedItem" class="workbench-side">
        <div class="workbench-panel workbench-editor-panel">
          <div class="workbench-panel-head">
            <h4>槽位 {{ selectedIndex }} · 组件编辑</h4>
            <span class="spacer"></span>
            <button class="ghost tiny" type="button" @click="removeSelected">清空槽位</button>
            <button class="ghost tiny" type="button" title="关闭编辑" @click="closeEditor">×</button>
          </div>
          <ItemEditor
            :item="selectedItem"
            :schema="schema"
            :unlimited-stack="unlimitedStack"
            @update="applyEditorItem"
          />
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import ItemEditor from './ItemEditor.vue';
import ItemIcon from './ItemIcon.vue';
import ItemSlotGrid from './ItemSlotGrid.vue';
import { hideItemTooltip, showItemTooltip } from './itemTooltip.js';
import { clearItemCursor, itemCursor, nextWorkbenchId } from '../composables/itemCursor.js';

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
  unlimitedStack: { type: Boolean, default: false },
  hasMore: { type: Boolean, default: false },
  doll: { type: Boolean, default: false },
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
const ownsCursor = computed(() => !!cursor.value && itemCursor.sourceId === sourceId);
const busy = ref(false);
const workbenchError = ref('');
const workbenchMessage = ref('');
// 动态绑定：避免 Vite 把 /ui/ 资源当成构建期 import（这些文件由后端静态目录提供）。
const trashIconSrc = '/ui/trashcan.png';
let dragSource = null;


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
    } else {
      rows.push({
        name,
        zone: section.zone || (name || 'plain'),
        gap: !!section.gap,
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

async function api(path, options) {
  const token = sessionStorage.getItem('samsara-token') || '';
  const headers = Object.assign({ Authorization: 'Bearer ' + token }, options.headers || {});
  if (options.body) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, Object.assign({}, options, { headers }));
  const data = await response.json().catch(() => ({}));
  if (response.status === 401) throw new Error('token 无效或已失效');
  if (!response.ok || data.ok === false) throw new Error(data.error || '物品操作失败');
  return data;
}

async function withCount(item, count) {
  if (!item) return null;
  if (count <= 0) return null;
  if (item.encoded && Number(item.count) === count) return item;
  const data = await api(props.itemEndpoint, {
    method: 'POST',
    body: JSON.stringify({ encoded: item.encoded, count, unlimited: props.unlimitedStack }),
  });
  return Object.assign({}, item, data, { count });
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
    } else if (!slotItem) {
      const place = button === 2 ? 1 : cursor.value.count;
      const placed = await withCount(cursor.value, place);
      setSlot(index, placed);
      cursor.value = place >= cursor.value.count
        ? null : await withCount(cursor.value, cursor.value.count - place);
      selectedIndex.value = index;
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
  } catch (e) {
    workbenchError.value = e.message || String(e);
  } finally {
    busy.value = false;
    if (changed) commit('interact');
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
    workbenchError.value = e.message || String(e);
  } finally {
    busy.value = false;
    if (changed) commit('interact');
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
    workbenchMessage.value = '垃圾桶：先拿起物品再点这里，或把物品拖进来。';
    return;
  }
  cursor.value = null;
  cursorOrigin.value = null;
  workbenchMessage.value = '已丢进垃圾桶。';
  commit('interact');
}

function trashDrop() {
  if (dragSource === null) return;
  const source = dragSource;
  dragSource = null;
  setSlot(source, null);
  workbenchMessage.value = '已丢进垃圾桶。';
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

async function returnCursor() {
  if (!cursor.value) return;
  busy.value = true;
  let changed = false;
  try {
    let target = cursorOrigin.value;
    if (target === null || localSlots.value[target]) {
      target = localSlots.value.findIndex((slot) => !slot);
    }
    if (target >= 0) {
      setSlot(target, cursor.value);
      cursor.value = null;
      cursorOrigin.value = null;
      selectedIndex.value = target;
      changed = true;
    } else {
      workbenchError.value = '没有空槽位可以放回；可先清空一个槽位。';
    }
  } finally {
    busy.value = false;
    if (changed) commit('interact');
  }
}

async function libraryPick(row) {
  if (props.disabled || busy.value) return;
  busy.value = true;
  workbenchError.value = '';
  try {
    const data = await api(props.itemEndpoint, {
      method: 'POST',
      body: JSON.stringify({ id: row.id, count: 1 }),
    });
    const item = Object.assign({}, data, { count: 1, signature: data.signature || data.id });
    if (cursor.value) {
      cursor.value = item;
      commit('interact');
    } else if (selectedIndex.value !== null && !localSlots.value[selectedIndex.value]) {
      setSlot(selectedIndex.value, item);
      commit('interact');
    } else {
      cursor.value = item;
      cursorOrigin.value = selectedIndex.value;
    }
  } catch (e) {
    workbenchError.value = e.message || String(e);
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

function closeEditor() {
  selectedIndex.value = null;
  hideItemTooltip();
}

function removeSelected() {
  if (selectedIndex.value === null) return;
  if (!localSlots.value[selectedIndex.value] && !cursor.value) return;
  if (!localSlots.value[selectedIndex.value] && cursor.value
      && cursorOrigin.value === selectedIndex.value) {
    cursor.value = null;
    cursorOrigin.value = null;
  } else {
    setSlot(selectedIndex.value, null);
  }
  commit('interact');
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

defineExpose({ resetSelection, pickFromLibrary: libraryPick });
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

.cursor-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 7px;
  border: 1px solid var(--accent);
  border-radius: 8px;
  background: var(--accent-soft);
  font-size: 12px;
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
  gap: 14px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: linear-gradient(160deg, var(--panel-soft), var(--panel));
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
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
}

.workbench-section-title {
  margin-bottom: 6px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
}

.workbench-side {
  position: absolute;
  top: 0;
  right: 0;
  z-index: 8;
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: min(380px, 94%);
  max-height: 100%;
  overflow: auto;
}

.workbench-panel {
  padding: 10px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: var(--panel);
  box-shadow: 0 14px 34px rgba(0, 0, 0, .5);
}

.workbench-panel h4 {
  margin: 0 0 7px;
  font-size: 12px;
}

.workbench-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.workbench-panel-head h4 {
  margin: 0;
}

.workbench-tip {
  margin: 0;
  padding: 8px;
  border: 1px dashed var(--line);
  border-radius: 8px;
}

.workbench-editor-panel {
  min-height: 220px;
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

@media (max-width: 1100px) {
  .workbench-side {
    width: min(340px, 96%);
  }
}
</style>
