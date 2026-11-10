<template>
  <div class="item-workbench">
    <div class="workbench-toolbar">
      <div class="workbench-mode">
        <span class="mode-dot" :class="mode"></span>
        <strong>{{ modeLabel }}</strong>
        <span class="muted small">{{ modeHint }}</span>
      </div>
      <div class="spacer"></div>
      <span v-if="busy" class="muted small">处理中…</span>
      <span v-if="cursor" class="cursor-chip">
        光标：<ItemIcon :item="cursor" size="sm" :show-count="false" />
        <span>{{ cursor.name }} ×{{ cursor.count }}</span>
        <button class="ghost tiny" type="button" :disabled="busy" @click="returnCursor">放回</button>
      </span>
    </div>

    <p v-if="workbenchError" class="container-message error">{{ workbenchError }}</p>

    <div class="workbench-body">
      <div class="workbench-grids">
        <section v-for="section in layout" :key="section.label + section.start" class="workbench-section">
          <div v-if="section.label" class="workbench-section-title">{{ section.label }}</div>
          <ItemSlotGrid
            :slots="slotsFor(section)"
            :items="itemsFor(section)"
            :selected="selectedIndex !== null && selectedIndex >= section.start
              && selectedIndex < section.start + section.size ? selectedIndex - section.start : null"
            :columns="section.columns || 9"
            :has-cursor="!!cursor"
            @slot-click="onSectionClick(section, $event)"
            @drag-start="onSectionDragStart(section, $event)"
            @drag-end="onDragEnd"
            @drop-slot="onSectionDrop(section, $event)"
          />
        </section>
        <p v-if="!layout.length" class="muted small">没有可显示的槽位。</p>
      </div>

      <aside class="workbench-side">
        <div class="workbench-panel">
          <h4>物品库</h4>
          <div class="workbench-search">
            <input
              v-model="searchQuery"
              type="text"
              placeholder="中文 / 英文 / ID，如 diamond、钻石"
              @keyup.enter="searchItems"
            />
            <button class="ghost tiny" type="button" :disabled="searchLoading" @click="searchItems">搜索</button>
          </div>
          <div v-if="searchResults.length" class="library-list">
            <button
              v-for="row in searchResults"
              :key="row.id"
              type="button"
              class="library-row"
              @click="libraryPick(row)"
            >
              <span class="library-name">{{ row.zh || row.name || row.id }}</span>
              <span class="library-id">{{ row.id }}</span>
            </button>
          </div>
          <p v-else class="muted small">{{ searchLoading ? '搜索中…' : '搜索后点击物品放到光标或当前空槽。' }}</p>
        </div>

        <div v-if="selectedItem" class="workbench-panel workbench-editor-panel">
          <div class="workbench-panel-head">
            <h4>槽位 {{ selectedIndex }} · 组件编辑</h4>
            <button class="ghost tiny" type="button" @click="removeSelected">清空槽位</button>
          </div>
          <ItemEditor
            :item="selectedItem"
            :schema="schema"
            @update="applyEditorItem"
          />
        </div>
        <p v-else class="muted small workbench-tip">
          左键拿起/放下整组，右键拿起一半/放下一个；相同物品会自动合并。选中槽位后可编辑名称、附魔、药水、属性等组件。
        </p>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import ItemEditor from './ItemEditor.vue';
import ItemIcon from './ItemIcon.vue';
import ItemSlotGrid from './ItemSlotGrid.vue';

const props = defineProps({
  slots: { type: Array, default: () => [] },
  items: { type: Array, default: () => [] },
  layout: { type: Array, default: () => [] },
  schema: { type: Object, default: () => ({}) },
  mode: { type: String, default: 'simulate' },
  itemEndpoint: { type: String, default: '/api/containers/item' },
  revision: { type: Number, default: 0 },
  disabled: { type: Boolean, default: false },
});
const emit = defineEmits(['change', 'select']);

const localSlots = ref([...(props.slots || [])]);
const localItems = ref([...(props.items || [])]);
const selectedIndex = ref(null);
const cursor = ref(null);
const cursorOrigin = ref(null);
const busy = ref(false);
const workbenchError = ref('');
const searchQuery = ref('');
const searchResults = ref([]);
const searchLoading = ref(false);
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
    cursor.value = null;
    cursorOrigin.value = null;
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
    pending: !!cursor.value,
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
    body: JSON.stringify({ encoded: item.encoded, count }),
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

function onSectionDragStart(section, payload) {
  return onDragStart(Object.assign({}, payload, { index: sectionIndex(section, payload) }));
}

function onSectionDrop(section, payload) {
  return onDrop(Object.assign({}, payload, { index: sectionIndex(section, payload) }));
}

async function onSlotClick({ index, button, shift }) {
  if (props.disabled || busy.value) return;
  selectedIndex.value = index;
  if (shift) {
    await quickMove(index);
    return;
  }
  busy.value = true;
  workbenchError.value = '';
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
    } else if (!slotItem) {
      const place = button === 2 ? 1 : cursor.value.count;
      const placed = await withCount(cursor.value, place);
      setSlot(index, placed);
      cursor.value = place >= cursor.value.count
        ? null : await withCount(cursor.value, cursor.value.count - place);
    } else if (sameSignature(slotItem, cursor.value)) {
      const max = Math.min(slotItem.maxStack || 64, cursor.value.maxStack || 64);
      const space = max - slotItem.count;
      if (space <= 0) return;
      const place = button === 2 ? 1 : Math.min(space, cursor.value.count);
      setSlot(index, await withCount(slotItem, slotItem.count + place));
      cursor.value = place >= cursor.value.count
        ? null : await withCount(cursor.value, cursor.value.count - place);
    } else if (button !== 2) {
      const previous = slotItem;
      setSlot(index, cursor.value);
      cursor.value = previous;
      cursorOrigin.value = index;
    }
  } catch (e) {
    workbenchError.value = e.message || String(e);
  } finally {
    busy.value = false;
    commit('interact');
  }
}

async function quickMove(index) {
  const item = itemAt(index);
  if (!item) return;
  busy.value = true;
  try {
    let target = localItems.value.find((row) =>
      row.slot !== index && sameSignature(row, item)
        && Number(row.count) < Number(row.maxStack || 64));
    if (target) {
      const space = Number(target.maxStack || 64) - Number(target.count);
      const move = Math.min(space, Number(item.count));
      setSlot(target.slot, await withCount(target, Number(target.count) + move));
      setSlot(index, move >= Number(item.count) ? null : await withCount(item, Number(item.count) - move));
    } else {
      const empty = localSlots.value.findIndex((slot, i) => i !== index && !slot);
      if (empty >= 0) {
        setSlot(empty, item);
        setSlot(index, null);
        selectedIndex.value = empty;
      }
    }
  } catch (e) {
    workbenchError.value = e.message || String(e);
  } finally {
    busy.value = false;
    commit('interact');
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
    } else {
      workbenchError.value = '没有空槽位可以放回；可先清空一个槽位。';
    }
  } finally {
    busy.value = false;
    commit('interact');
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

async function searchItems() {
  const query = searchQuery.value.trim();
  if (!query) {
    searchResults.value = [];
    return;
  }
  searchLoading.value = true;
  workbenchError.value = '';
  try {
    const data = await api('/api/lottery/values?q=' + encodeURIComponent(query) + '&limit=20', {});
    searchResults.value = (data.values || []).filter((row) => row.acceptable !== false);
  } catch (e) {
    workbenchError.value = e.message || String(e);
  } finally {
    searchLoading.value = false;
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

function removeSelected() {
  if (selectedIndex.value === null) return;
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
  searchResults.value = [];
}

defineExpose({ resetSelection });
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
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 420px);
  gap: 14px;
  align-items: start;
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

.workbench-section-title {
  margin-bottom: 6px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
}

.workbench-side {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.workbench-panel {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
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

.workbench-search {
  display: flex;
  gap: 6px;
}

.workbench-search input {
  flex: 1;
  min-width: 0;
  padding: 5px 7px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel-soft);
  color: var(--fg);
  font-size: 12px;
}

.library-list {
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-height: 240px;
  margin-top: 7px;
  overflow: auto;
}

.library-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 5px 7px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.library-row:hover {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.library-name {
  font-size: 12px;
}

.library-id {
  color: var(--muted);
  font-size: 10px;
  white-space: nowrap;
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

.ghost.tiny {
  padding: 2px 7px;
  font-size: 11px;
}

@media (max-width: 1100px) {
  .workbench-body {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
