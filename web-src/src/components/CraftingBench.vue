<template>
  <div class="crafting-bench">
    <div class="crafting-head">
      <strong>合成</strong>
      <span class="muted small">{{ recipeId ? recipeId.replace('minecraft:', '') : '3×3' }}</span>
    </div>
    <div class="crafting-body">
      <ItemSlotGrid
        :slots="slots"
        :items="items"
        :columns="3"
        :start="0"
        :has-cursor="!!cursor"
        :editable="false"
        @slot-click="onSlotClick"
        @slot-hover="onHover"
        @slot-leave="onLeave"
      />
      <span class="crafting-arrow" aria-hidden="true">→</span>
      <button
        class="crafting-result"
        :class="{ filled: !!result }"
        type="button"
        title="取出合成结果"
        aria-label="取出合成结果"
        @click="takeResult"
        @mouseenter="onResultHover"
        @mouseleave="onLeave"
      >
        <ItemIcon v-if="result" :item="result" size="sm" :show-count="true" />
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import ItemIcon from './ItemIcon.vue';
import ItemSlotGrid from './ItemSlotGrid.vue';
import { itemCursor } from '../composables/itemCursor.js';
import { hideItemTooltip, showItemTooltip } from './itemTooltip.js';

const grid = ref(Array.from({ length: 9 }, () => null));
const result = ref(null);
const recipeId = ref('');
const busy = ref(false);
let previewTimer = 0;
let token = '';

const cursor = computed(() => itemCursor.item);
const slots = computed(() => grid.value.map((item) => item?.encoded || ''));
const items = computed(() => grid.value
  .map((item, slot) => (item ? Object.assign({}, item, { slot }) : null))
  .filter(Boolean));

function api(path, options = {}) {
  const headers = Object.assign({ Authorization: 'Bearer ' + token }, options.headers || {});
  if (options.body) headers['Content-Type'] = 'application/json';
  return fetch(path, Object.assign({}, options, { headers })).then(async (response) => {
    const text = await response.text();
    let data = {};
    try { data = text ? JSON.parse(text) : {}; } catch { data = { error: text }; }
    if (!response.ok || data.ok === false) throw new Error(data.error || '请求失败');
    return data;
  });
}

async function withCount(item, count) {
  if (Number(item.count) === count) return item;
  const data = await api('/api/items/build', {
    method: 'POST',
    body: JSON.stringify({ encoded: item.encoded, count, unlimited: true }),
  });
  return data.item;
}

function schedulePreview() {
  window.clearTimeout(previewTimer);
  previewTimer = window.setTimeout(refreshPreview, 100);
}

async function refreshPreview() {
  if (busy.value) return;
  const encoded = slots.value;
  if (!encoded.some(Boolean)) {
    result.value = null;
    recipeId.value = '';
    return;
  }
  try {
    const data = await api('/api/items/craft', {
      method: 'POST',
      body: JSON.stringify({ slots: encoded }),
    });
    result.value = data.result || null;
    recipeId.value = data.recipe || '';
  } catch {
    result.value = null;
    recipeId.value = '';
  }
}

async function onSlotClick({ index, button }) {
  if (busy.value) return;
  const current = grid.value[index];
  const held = itemCursor.item;
  try {
    if (!held) {
      if (!current) return;
      itemCursor.item = current;
      itemCursor.sourceId = 'crafting';
      itemCursor.sourceIndex = index;
      grid.value[index] = null;
    } else if (!current) {
      const take = button === 2 ? 1 : held.count;
      grid.value[index] = await withCount(held, take);
      itemCursor.item = take >= held.count ? null : await withCount(held, held.count - take);
      if (!itemCursor.item) itemCursor.sourceIndex = null;
    } else if (current.signature === held.signature) {
      const max = Math.min(current.maxStack || 64, held.maxStack || 64);
      const space = max - current.count;
      if (space <= 0) return;
      const place = button === 2 ? 1 : Math.min(space, held.count);
      grid.value[index] = await withCount(current, current.count + place);
      itemCursor.item = place >= held.count ? null : await withCount(held, held.count - place);
      if (!itemCursor.item) itemCursor.sourceIndex = null;
    } else if (button !== 2) {
      grid.value[index] = held;
      itemCursor.item = current;
      itemCursor.sourceId = 'crafting';
      itemCursor.sourceIndex = index;
    }
  } catch (error) {
    ElMessage.error(error.message || String(error));
  } finally {
    grid.value = [...grid.value];
    schedulePreview();
  }
}

async function takeResult() {
  if (!result.value || busy.value) return;
  if (itemCursor.item && itemCursor.item.signature !== result.value.signature) {
    ElMessage.warning('先放下手中的物品，再取出合成结果');
    return;
  }
  busy.value = true;
  try {
    const data = await api('/api/items/craft', {
      method: 'POST',
      body: JSON.stringify({ slots: slots.value }),
    });
    const crafted = data.result;
    if (!crafted) return;
    if (itemCursor.item) {
      const space = (itemCursor.item.maxStack || 64) - itemCursor.item.count;
      if (space < crafted.count) {
        ElMessage.warning('手中物品数量已满');
        return;
      }
      itemCursor.item = await withCount(itemCursor.item, itemCursor.item.count + crafted.count);
    } else {
      itemCursor.item = crafted;
      itemCursor.sourceId = 'crafting';
      itemCursor.sourceIndex = null;
    }
    const next = grid.value.map((item) => {
      if (!item) return null;
      return item.count > 1 ? Object.assign({}, item, { count: item.count - 1 }) : null;
    });
    const remaining = Array.isArray(data.remaining) ? data.remaining : [];
    for (let i = 0; i < 9; i += 1) {
      if (remaining[i]) next[i] = Object.assign({}, remaining[i], { slot: i });
    }
    grid.value = next;
    schedulePreview();
  } catch (error) {
    ElMessage.error(error.message || String(error));
  } finally {
    busy.value = false;
  }
}

function onHover({ item, event }) {
  if (item) showItemTooltip(item, event);
  else hideItemTooltip();
}

function onResultHover(event) {
  if (result.value) showItemTooltip(result.value, event);
  else hideItemTooltip();
}

function onLeave() {
  hideItemTooltip();
}

watch(() => itemCursor.item, () => {
  // 光标内容变化时不需要重新请求配方；槽位变化由操作本身触发。
});

onBeforeUnmount(() => {
  window.clearTimeout(previewTimer);
  if (itemCursor.sourceId === 'crafting') {
    itemCursor.item = null;
    itemCursor.sourceId = null;
    itemCursor.sourceIndex = null;
  }
});

token = sessionStorage.getItem('samsara-token') || '';
</script>

<style scoped>
.crafting-bench {
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: max-content;
  padding: 6px;
  border: 2px solid #373737;
  background: #c6c6c6;
}
.crafting-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  color: #2b2b2b;
}
.crafting-head strong { font-size: 12px; }
.crafting-body {
  display: flex;
  align-items: center;
  gap: 8px;
}
.crafting-body :deep(.slot-grid) {
  margin-top: 0;
}
.crafting-arrow {
  color: #373737;
  font-size: 20px;
  line-height: 1;
}
.crafting-result {
  display: grid;
  place-items: center;
  width: var(--slot-size, 36px);
  height: var(--slot-size, 36px);
  padding: 0;
  border: 2px solid #373737;
  border-radius: 0;
  background: #8b8b8b url('/ui/slot.png') center / 100% 100% no-repeat;
}
.crafting-result.filled {
  background-color: #8b8b8b;
}
.crafting-result:hover {
  background-color: #c6c6c6;
  box-shadow: inset 0 0 0 2px #ffffff;
}
</style>
