<template>
  <div class="item-workbench">
    <div class="workbench-toolbar">
      <div class="workbench-mode">
        <span class="mode-dot" :class="mode"></span>
        <strong>{{ modeLabel }}</strong>
        <span class="muted small">{{ modeHint }}</span>
      </div>
      <div class="spacer"></div>
      <span class="workbench-hover-hint">悬停槽位查看完整 tooltip；点槽位右上角 ✎ 可直接编辑组件，不会拿起物品。</span>
      <span v-if="busy" class="muted small">处理中…</span>
      <button
        v-if="webDeposit"
        class="ghost tiny"
        type="button"
        :disabled="busy || !selectedItem"
        title="把当前选中的物品存入 Web 幽灵背包"
        @click="depositToWeb"
      >存入 Web 背包</button>
      <button
        v-if="webWithdraw"
        class="ghost tiny"
        type="button"
        :disabled="busy"
        title="从 Web 幽灵背包取出一件物品放到光标"
        @click="openWebPicker"
      >从 Web 背包取回</button>
      <button
        class="workbench-trash"
        :class="{ armed: !!cursor }"
        type="button"
        title="垃圾桶：拿起物品后点这里删除（或把物品拖进来）"
        @click="trashCursor"
        @dragover.prevent
        @drop.prevent="trashDrop"
      >🗑 垃圾桶</button>
      <span v-if="cursor" class="cursor-chip">
        光标：<ItemIcon :item="cursor" size="sm" :show-count="false" />
        <span>{{ cursor.name }} ×{{ cursor.count }}</span>
        <button class="ghost tiny" type="button" :disabled="busy" @click="returnCursor">放回</button>
      </span>
    </div>

    <Teleport to="body">
      <div
        v-if="cursor"
        class="workbench-floating-cursor"
        :style="{ left: pointerX + 'px', top: pointerY + 'px' }"
      >
        <ItemIcon :item="cursor" size="lg" :show-count="true" />
      </div>
    </Teleport>

    <p v-if="workbenchError" class="container-message error">{{ workbenchError }}</p>
    <p v-if="workbenchMessage" class="container-message ok">{{ workbenchMessage }}</p>

    <div v-if="webPickerOpen" class="workbench-web-picker">
      <div class="workbench-web-picker-head">
        <strong>Web 背包</strong>
        <span class="muted small">{{ webItems.length }} 件</span>
        <span class="spacer"></span>
        <button class="ghost tiny" type="button" @click="webPickerOpen = false">关闭</button>
      </div>
      <div v-if="webItems.length" class="workbench-web-list">
        <button
          v-for="row in webItems"
          :key="row.slot"
          type="button"
          class="workbench-web-row"
          @click="takeWebItem(row)"
        >
          <ItemIcon :item="row" size="sm" :show-count="true" />
          <span class="workbench-web-name">{{ row.name }} ×{{ row.count }}</span>
          <span class="muted small">槽位 {{ row.slot }}</span>
        </button>
      </div>
      <p v-else class="muted small">Web 背包是空的。</p>
    </div>

    <div class="workbench-body">
      <div class="workbench-grids">
        <div
          v-for="(row, rowIndex) in layoutRows"
          :key="'row-' + rowIndex"
          class="workbench-row"
          :class="['workbench-row-' + row.zone, { 'workbench-row-gap': row.gap }]"
        >
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

      <aside class="workbench-side">
        <div v-if="showLibrary" class="workbench-panel">
          <h4>物品库</h4>
          <div class="workbench-library-tabs">
            <button type="button" :class="{ active: !debugVisible }" @click="debugVisible = false">搜索物品</button>
            <button type="button" :class="{ active: debugVisible }" @click="debugVisible = true">调试方块</button>
          </div>
          <div v-if="!debugVisible" class="workbench-search">
            <input
              v-model="searchQuery"
              type="text"
              placeholder="中文 / 英文 / ID，如 diamond、钻石"
              @keyup.enter="searchItems"
            />
            <button class="ghost tiny" type="button" :disabled="searchLoading" @click="searchItems">搜索</button>
          </div>
          <div v-if="debugVisible" class="library-list">
            <button
              v-for="row in DEBUG_ITEMS"
              :key="row.id"
              type="button"
              class="library-row"
              @click="libraryPick(row)"
            >
              <span class="library-name">{{ row.name }}</span>
              <span class="library-id">{{ row.id }}</span>
            </button>
          </div>
          <div v-else-if="searchResults.length" class="library-list">
            <button
              v-for="row in searchResults"
              :key="row.id"
              type="button"
              class="library-row"
              @click="libraryPick(row)"
              @mouseenter="showItemTooltip(row, $event)"
              @mousemove="moveItemTooltip($event)"
              @mouseleave="hideItemTooltip"
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
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import ItemEditor from './ItemEditor.vue';
import ItemIcon from './ItemIcon.vue';
import ItemSlotGrid from './ItemSlotGrid.vue';
import { hideItemTooltip, itemTooltip, moveItemTooltip, showItemTooltip } from './itemTooltip.js';

const props = defineProps({
  slots: { type: Array, default: () => [] },
  items: { type: Array, default: () => [] },
  layout: { type: Array, default: () => [] },
  schema: { type: Object, default: () => ({}) },
  mode: { type: String, default: 'simulate' },
  itemEndpoint: { type: String, default: '/api/containers/item' },
  revision: { type: Number, default: 0 },
  disabled: { type: Boolean, default: false },
  webDeposit: { type: Boolean, default: true },
  webWithdraw: { type: Boolean, default: true },
  showLibrary: { type: Boolean, default: true },
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
const debugVisible = ref(false);
const pointerX = ref(12);
const pointerY = ref(12);
const workbenchMessage = ref('');
const webPickerOpen = ref(false);
const webItems = ref([]);
let dragSource = null;

const DEBUG_ITEMS = [
  { id: 'minecraft:command_block', name: '命令方块' },
  { id: 'minecraft:chain_command_block', name: '连锁命令方块' },
  { id: 'minecraft:repeating_command_block', name: '循环命令方块' },
  { id: 'minecraft:structure_block', name: '结构方块' },
  { id: 'minecraft:jigsaw', name: '拼图方块' },
  { id: 'minecraft:barrier', name: '屏障' },
  { id: 'minecraft:light', name: '光源方块' },
  { id: 'minecraft:structure_void', name: '结构空位' },
  { id: 'minecraft:debug_stick', name: '调试棒' },
  { id: 'minecraft:spawner', name: '刷怪笼' },
  { id: 'minecraft:bedrock', name: '基岩' },
  { id: 'minecraft:end_portal_frame', name: '末地传送门框架' },
  { id: 'minecraft:command_block_minecart', name: '命令方块矿车' },
  { id: 'minecraft:knowledge_book', name: '知识之书' },
];

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

function onPointerMove(event) {
  pointerX.value = event.clientX + 10;
  pointerY.value = event.clientY + 10;
  if (itemTooltip.visible) moveItemTooltip(event);
}

onMounted(() => {
  window.addEventListener('pointermove', onPointerMove, { passive: true });
});

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onPointerMove);
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
      const max = Math.min(slotItem.maxStack || 64, cursor.value.maxStack || 64);
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
      const space = Number(target.maxStack || 64) - Number(target.count);
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
  searchResults.value = [];
  webPickerOpen.value = false;
}

async function depositToWeb() {
  if (props.disabled || busy.value) return;
  const item = selectedItem.value || cursor.value;
  if (!item || !item.encoded) {
    workbenchError.value = '请先选中一个有物品的槽位（或拿起物品）。';
    workbenchMessage.value = '';
    return;
  }
  busy.value = true;
  workbenchError.value = '';
  workbenchMessage.value = '';
  try {
    await api('/api/inventory/web/deposit', {
      method: 'POST',
      body: JSON.stringify({ encoded: item.encoded }),
    });
    if (cursor.value && cursorOrigin.value === selectedIndex.value) {
      cursor.value = null;
      cursorOrigin.value = null;
    } else if (selectedIndex.value !== null && localSlots.value[selectedIndex.value]) {
      setSlot(selectedIndex.value, null);
    } else if (cursor.value) {
      cursor.value = null;
      cursorOrigin.value = null;
    }
    workbenchMessage.value = '已存入 Web 背包。';
    commit('interact');
  } catch (e) {
    workbenchError.value = e.message || String(e);
  } finally {
    busy.value = false;
  }
}

async function openWebPicker() {
  if (props.disabled || busy.value) return;
  busy.value = true;
  workbenchError.value = '';
  workbenchMessage.value = '';
  try {
    const data = await api('/api/inventory/web', {});
    webItems.value = Array.isArray(data.items) ? data.items : [];
    webPickerOpen.value = true;
  } catch (e) {
    workbenchError.value = e.message || String(e);
  } finally {
    busy.value = false;
  }
}

async function takeWebItem(row) {
  if (props.disabled || busy.value) return;
  busy.value = true;
  workbenchError.value = '';
  workbenchMessage.value = '';
  try {
    const data = await api('/api/inventory/web/withdraw', {
      method: 'POST',
      body: JSON.stringify({ index: row.slot, count: row.count }),
    });
    if (!data.item) throw new Error('未返回物品');
    cursor.value = data.item;
    cursorOrigin.value = null;
    selectedIndex.value = null;
    webPickerOpen.value = false;
    workbenchMessage.value = '已从 Web 背包取出 ' + (data.item.name || data.item.id) + '，点击槽位放下。';
    commit('interact');
  } catch (e) {
    workbenchError.value = e.message || String(e);
  } finally {
    busy.value = false;
  }
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
  padding: 3px 8px;
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
  cursor: pointer;
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

.workbench-library-tabs {
  display: flex;
  gap: 3px;
  margin-bottom: 7px;
  padding: 2px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-soft);
}

.workbench-library-tabs button {
  flex: 1;
  padding: 3px 6px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
  cursor: pointer;
}

.workbench-library-tabs button.active {
  background: var(--accent);
  color: #fff;
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
  .workbench-body {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
