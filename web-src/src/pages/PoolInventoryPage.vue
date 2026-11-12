<template>
  <component :is="embedded ? 'div' : TabShell" v-bind="embedded ? {} : { name: 'pool-inventory', title, description }">
    <section v-if="!connected" class="panel">
      <h3>连接管理端</h3>
      <p class="muted small">需要管理端 token；token 只保存在当前标签页的 sessionStorage。</p>
      <div class="inventory-connect">
        <el-input v-model="tokenInput" type="password" placeholder="Bearer token" show-password />
        <el-button type="primary" :loading="connecting" @click="connect">连接</el-button>
      </div>
      <p v-if="error" class="container-message error">{{ error }}</p>
    </section>

    <template v-else>
      <div class="pool-head" :class="{ 'pool-head-embedded': embedded }">
        <div>
          <h3 v-if="!embedded">{{ title }}</h3>
          <h4 v-else>{{ title }}</h4>
          <p v-if="description" class="muted small">{{ description }}</p>
        </div>
        <span class="spacer"></span>
        <span class="muted small">
          共 {{ nonEmpty }} 件物品 · {{ slots.length }} 格<template v-if="kind === 'fund'">
            · 实物总值 {{ formatValue(fundValue) }} VP</template>
        </span>
        <button
          class="icon-button"
          type="button"
          :disabled="loading"
          title="重新读取"
          aria-label="重新读取"
          @click="load"
        >↻</button>
      </div>

      <div v-if="kind === 'fund'" class="fund-search">
        <input
          v-model="itemQuery"
          type="search"
          placeholder="搜索仓库物品（中文 / 英文 / ID）"
          aria-label="搜索仓库物品"
        />
        <div class="fund-sort" role="group" aria-label="排列顺序">
          <button
            v-for="option in sortOptions"
            :key="option.id"
            type="button"
            class="fund-sort-segment"
            :class="{ active: itemSort === option.id }"
            :title="option.hint"
            @click="setSort(option.id)"
          >{{ option.label }}</button>
          <span class="fund-sort-divider"></span>
          <button
            type="button"
            class="fund-sort-dir"
            :class="{ desc: sortDesc }"
            :title="sortDesc ? '当前逆序，点击改为顺序' : '当前顺序，点击改为逆序'"
            @click="toggleSortDirection"
          >{{ sortDesc ? '逆序 ↓' : '顺序 ↑' }}</button>
        </div>
        <span class="fund-sort-badge">{{ sortLabel }}</span>
        <span class="muted small">{{ filteredItems.length }} / {{ items.length }} 条</span>
      </div>
      <div v-if="kind === 'fund' && filteredItems.length" class="fund-item-list">
        <button
          v-for="row in filteredItems"
          :key="row.slot"
          type="button"
          class="fund-item-row"
          :class="{ active: focusedSlot === row.slot }"
          @click="focusSlot(row.slot)"
        >
          <span class="fund-item-slot">{{ row.slot }}</span>
          <span class="fund-item-name">{{ row.zh || row.name || row.id }}</span>
          <span class="muted small">×{{ row.count }}</span>
          <span v-if="row.value" class="fund-item-value">{{ formatValue(row.value) }}</span>
        </button>
      </div>

      <!-- 固定高度状态行：写入中/已写入/错误都占同一行，仓库网格不会上下浮动。 -->
      <div class="pool-status" role="status" aria-live="polite">
        <p v-if="error" class="container-message error pool-status-line">{{ error }}</p>
        <p v-else-if="saving" class="muted small pool-status-line pool-status-saving">写入中…</p>
        <p v-else-if="message" class="container-message ok pool-status-line">{{ message }}</p>
      </div>

      <div class="panel">
        <ItemWorkbench
          :slots="slots"
          :items="items"
          :layout="layout"
          :schema="schema"
          mode="live"
          item-endpoint="/api/inventory/item"
          :revision="revision"
          :disabled="saving"
          :trash="kind === 'web'"
          :editable="false"
          :unlimited-stack="kind === 'fund'"
          :auto-stack="kind === 'fund'"
          :has-more="hasMore"
          @change="onWorkbenchChange"
          @need-more="loadMoreSlots"
        />
      </div>
    </template>
  </component>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import TabShell from '../components/TabShell.vue';
import ItemWorkbench from '../components/ItemWorkbench.vue';
import { useLiveRefresh } from '../composables/useLiveRefresh.js';
import { itemCursor } from '../composables/itemCursor.js';
import { filterItems } from '../composables/itemSearch.js';

const SLOT_PX = 48;
const SLOT_GAP = 2;
const PAGE_ROWS = 6;

function computeColumns() {
  // 大致按「窗口宽度 / 格子宽度」算列数，再乘固定 6 行得到最低格子数。
  const available = Math.max(360, window.innerWidth - 360);
  return Math.max(3, Math.min(24, Math.floor((available + SLOT_GAP) / (SLOT_PX + SLOT_GAP))));
}

const props = defineProps({
  kind: { type: String, default: 'web' },
  embedded: { type: Boolean, default: false },
});

const token = ref(sessionStorage.getItem('samsara-token') || '');
const tokenInput = ref(token.value);
const connected = ref(false);
const connecting = ref(false);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const message = ref('');
const slots = ref([]);
const baselineSlots = ref([]);
const items = ref([]);
const revision = ref(0);
const dirty = ref(false);
const fundValue = ref(0);
const schema = ref({});
const cursorPending = computed(() => !!itemCursor.item);
const itemQuery = ref('');
const itemSort = ref('added');
const sortDesc = ref(false);
const columns = ref(computeColumns());
const visiblePages = ref(1);
const sortOptions = [
  { id: 'added', label: '添加顺序', hint: '按放入仓库的先后（槽位顺序）' },
  { id: 'name', label: '名称', hint: '按物品中文 / 英文名称' },
  { id: 'count', label: '数量', hint: '按堆叠数量' },
  { id: 'nbt', label: 'NBT', hint: '按数据组件数目' },
  { id: 'rarity', label: '稀有度', hint: '按原版稀有度' },
  { id: 'value', label: '价值', hint: '按 VP 价值' },
];
const pageSlots = computed(() => columns.value * PAGE_ROWS);
const focusedSlot = ref(null);
let liveTimer = null;

const title = computed(() => props.kind === 'fund' ? '基金仓库' : 'Web 背包');
const description = computed(() => props.kind === 'fund'
  ? ''
  : '幽灵管理员的 54 格 Web 仓库：装备栏、副手、主背包、快捷栏与仓库。');
const readEndpoint = computed(() => props.kind === 'fund'
  ? '/api/lottery/fund' : '/api/inventory/web');
const writeEndpoint = computed(() => props.kind === 'fund'
  ? '/api/lottery/fund/write' : '/api/inventory/web/write');
const nonEmpty = computed(() => (slots.value || []).filter((value) => value).length);

const layout = computed(() => {
  if (props.kind === 'fund') {
    // 列数随窗口宽度自适应；最低显示 固定 6 行 × 当前列数，滚到底再懒加载。
    const size = Math.min(slots.value.length, visiblePages.value * pageSlots.value);
    return [{
      label: '基金仓库 0–' + Math.max(0, size - 1),
      start: 0,
      size,
      columns: columns.value,
      zone: 'plain',
    }];
  }
  // Web 背包与玩家背包同一套相对布局。
  return [
    { label: '装备 0–3', start: 0, size: 4, columns: 1, row: 'gear', zone: 'gear' },
    { label: '副手 4', start: 4, size: 1, columns: 1, row: 'gear', zone: 'gear' },
    { label: '主背包 5–31', start: 5, size: 27, columns: 9, row: 'main', zone: 'main' },
    { label: '快捷栏 32–40', start: 32, size: 9, columns: 9, row: 'hotbar', zone: 'hotbar', gap: true },
    { label: '仓库 41–53', start: 41, size: 13, columns: 9, row: 'storage', zone: 'storage', gap: true },
  ];
});

const filteredItems = computed(() => filterItems(items.value || [], itemQuery.value)
  .slice()
  .sort((a, b) => (Number(a.slot) || 0) - (Number(b.slot) || 0)));

const liveState = useLiveRefresh({
  mode: () => 'live',
  key: () => `${props.kind}:${readEndpoint.value}`,
  canRefresh: () => connected.value && !dirty.value && !saving.value
    && !loading.value && !cursorPending.value,
  fetchSnapshot: async (signal) => {
    const result = await api(readEndpoint.value, { signal });
    return result.status === 200 && result.data.ok ? result.data : null;
  },
  applySnapshot: (snapshot) => {
    if (dirty.value || saving.value || cursorPending.value) return false;
    const incoming = normalize(incomingSlots(snapshot.slots));
    if (sameSlots(incoming, baselineSlots.value)) return false;
    applyLoadedData(incoming, snapshot.items || [], snapshot.value);
    message.value = '检测到仓库内容变化，已同步最新内容。';
    return true;
  },
});

// 仓库与 Web 背包同源：所有改动即时落库，因此没有实时/预设/模拟分段。

function onResize() {
  columns.value = computeColumns();
  visiblePages.value = Math.max(1, visiblePages.value);
}

onMounted(() => {
  if (token.value) connect();
  window.addEventListener('resize', onResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize);
});

async function api(path, options = {}) {
  const headers = Object.assign({ Authorization: 'Bearer ' + token.value }, options.headers || {});
  if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, Object.assign({}, options, { headers }));
  const text = await response.text();
  let body = {};
  try { body = text ? JSON.parse(text) : {}; } catch { body = { error: text }; }
  if (response.status === 401) {
    connected.value = false;
    throw new Error('token 无效或已失效');
  }
  return { status: response.status, data: body };
}

async function connect() {
  connecting.value = true;
  error.value = '';
  try {
    const value = (tokenInput.value || '').trim();
    if (!value) throw new Error('请输入 token');
    token.value = value;
    const result = await api('/api/status');
    if (result.status !== 200) throw new Error(result.data.error || ('连接失败：' + result.status));
    sessionStorage.setItem('samsara-token', value);
    connected.value = true;
    await loadSchema();
    await load();
  } catch (e) {
    error.value = e.message || String(e);
    connected.value = false;
  } finally {
    connecting.value = false;
  }
}

async function loadSchema() {
  try {
    const result = await api('/api/items/schema');
    if (result.status === 200 && result.data.ok) schema.value = result.data.schema || {};
  } catch {
    // schema 不可用时仍可做数量/拖拽编辑
  }
}

async function load() {
  loading.value = true;
  error.value = '';
  message.value = '';
  try {
    const result = await api(readEndpoint.value);
    if (result.status !== 200 || !result.data.ok) throw new Error(result.data.error || '读取仓库失败');
    const incoming = normalize(incomingSlots(result.data.slots));
    applyLoadedData(incoming, result.data.items || [], result.data.value);
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

function applyLoadedData(incoming, incomingItems, value) {
  slots.value = incoming;
  baselineSlots.value = incoming.slice();
  items.value = incomingItems;
  fundValue.value = Number(value || 0);
  revision.value = Date.now();
  dirty.value = false;
  let visible = 1;
  while (visible * pageSlots.value < incoming.length
      && incoming.slice((visible - 1) * pageSlots.value, visible * pageSlots.value)
        .every(Boolean)) {
    visible += 1;
  }
  visiblePages.value = visible;
}

function incomingSlots(list) {
  const source = (list || []).slice();
  const minimum = props.kind === 'fund' ? pageSlots.value : 54;
  while (source.length < minimum) source.push('');
  return source;
}

/** 基金仓库至少 54 格，最后一件物品之后永远留一格空位；多余的整块空槽会被裁掉。 */
function normalize(list) {
  if (props.kind !== 'fund') return list;
  const out = list.slice();
  let last = -1;
  for (let i = out.length - 1; i >= 0; i--) {
    if (out[i]) { last = i; break; }
  }
  const needed = Math.max(pageSlots.value,
    Math.ceil((last + 2) / pageSlots.value) * pageSlots.value);
  while (out.length < needed) out.push('');
  if (out.length > needed) out.length = needed;
  return out;
}

function sectionCountFor(list) {
  if (props.kind !== 'fund') return 1;
  const out = list || [];
  let last = -1;
  for (let i = out.length - 1; i >= 0; i--) {
    if (out[i]) { last = i; break; }
  }
  return Math.max(1, Math.ceil((last + 2) / pageSlots.value));
}

const hasMore = computed(() => props.kind === 'fund'
  && visiblePages.value * pageSlots.value < slots.value.length);

function loadMoreSlots() {
  if (!hasMore.value) return;
  visiblePages.value += 1;
}

function sameSlots(a, b) {
  const left = a || [];
  const right = b || [];
  if (left.length !== right.length) return false;
  for (let i = 0; i < left.length; i++) {
    if ((left[i] || '') !== (right[i] || '')) return false;
  }
  return true;
}

async function focusSlot(slot) {
  focusedSlot.value = slot;
  visiblePages.value = Math.max(visiblePages.value,
    Math.ceil((slot + 2) / pageSlots.value));
  await nextTick();
  const node = document.querySelector(`.workbench-section .slot[aria-label^="槽位 ${slot} "]`);
  node?.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function formatValue(value) {
  const number = Number(value) || 0;
  return number >= 1000 ? (Math.round(number / 100) / 10) + 'k' : String(number);
}

function onWorkbenchChange(payload) {
  const previousLength = slots.value.length;
  const next = normalize(payload.slots);
  slots.value = next;
  items.value = payload.items;
  // 只在仓库因为“填满最后可见格”而增长时自动露出下一块；
  // 大仓库重新打开时仍从 0–53 开始，滚到底再懒加载。
  if (next.length > previousLength) {
    visiblePages.value = Math.max(visiblePages.value,
      Math.ceil(next.length / pageSlots.value));
  }
  dirty.value = !sameSlots(next, baselineSlots.value);
  if (!dirty.value) {
    liveTimer && clearTimeout(liveTimer);
    return;
  }
  liveTimer && clearTimeout(liveTimer);
  liveTimer = setTimeout(() => save(), 450);
}

const RARITY_ORDER = { common: 1, uncommon: 2, rare: 3, epic: 4 };

const sortLabel = computed(() =>
  sortOptions.find((option) => option.id === itemSort.value)?.label || '添加顺序');

function setSort(next) {
  if (next === itemSort.value) return;
  itemSort.value = next;
  applySort();
}

function sortKey(row) {
  switch (itemSort.value) {
    case 'name': return String(row.zh || row.name || row.id || '');
    case 'count': return Number(row.count) || 0;
    case 'nbt': return Number(row.componentCount) || 0;
    case 'rarity': return RARITY_ORDER[row.rarity] || 0;
    case 'value': return Number(row.value) || 0;
    default: return Number(row.slot) || 0;
  }
}

function applySort() {
  const rows = (items.value || []).slice();
  const direction = sortDesc.value ? -1 : 1;
  rows.sort((a, b) => {
    const left = sortKey(a);
    const right = sortKey(b);
    const result = typeof left === 'string'
      ? left.localeCompare(right, 'zh-CN') : left - right;
    if (result !== 0) return result * direction;
    return String(a.zh || a.name || a.id || '')
      .localeCompare(String(b.zh || b.name || b.id || ''), 'zh-CN') * direction;
  });
  const next = normalize(new Array(Math.max(pageSlots.value, rows.length + 1)).fill(''));
  rows.forEach((row, index) => { next[index] = row.encoded; });
  slots.value = next;
  items.value = rows.map((row, index) => Object.assign({}, row, { slot: index }));
  visiblePages.value = sectionCountFor(next);
  dirty.value = true;
  save();
}

function toggleSortDirection() {
  sortDesc.value = !sortDesc.value;
  applySort();
}

async function save() {
  if (saving.value) return;
  saving.value = true;
  error.value = '';
  try {
    const result = await api(writeEndpoint.value, {
      method: 'POST',
      body: JSON.stringify({ slots: slots.value }),
    });
    if (result.status !== 200 || !result.data.ok) throw new Error(result.data.error || '写入失败');
    const incoming = normalize(incomingSlots(result.data.slots || slots.value));
    slots.value = incoming;
    baselineSlots.value = incoming.slice();
    items.value = result.data.items || items.value;
    visiblePages.value = Math.min(visiblePages.value,
      Math.max(1, Math.ceil(incoming.length / pageSlots.value)));
    fundValue.value = Number(result.data.value || 0);
    dirty.value = false;
    message.value = '已写入' + title.value + '。';
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    saving.value = false;
    liveState.invalidate();
  }
}
</script>

<style scoped>
.pool-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 10px;
}

.pool-status {
  display: flex;
  align-items: center;
  min-height: 22px;
  margin: 2px 0 6px;
}

/* 状态行里的 .container-message 去掉自带内外边距，保证高度恒定。 */
.pool-status-line {
  margin: 0;
  padding: 0;
  background: transparent;
  font-size: 12px;
  line-height: 1.4;
}

.pool-status-line.error { color: #b91c1c; }
.pool-status-line.ok { color: #047857; }
.pool-status-saving { margin: 0; }

.pool-head h3,
.pool-head h4 {
  margin: 0 0 3px;
}

.pool-head-embedded {
  margin-top: 4px;
}

.fund-search {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.fund-search input[type='search'] {
  flex: 1 1 220px;
  min-width: 160px;
  padding: 5px 9px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-soft);
  color: var(--fg);
}

.fund-item-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 132px;
  overflow: auto;
  margin-bottom: 12px;
}

.fund-item-row {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-soft);
  color: var(--fg);
  font-size: 12px;
  cursor: pointer;
}

.fund-item-row.active {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.fund-item-slot {
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.fund-item-value {
  color: var(--amber);
  font-variant-numeric: tabular-nums;
}

.icon-button {
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-soft);
  color: var(--muted);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}

.icon-button:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--fg);
}

.inventory-connect {
  display: flex;
  gap: 10px;
  max-width: 520px;
  margin-top: 10px;
}


.fund-sort {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: stretch;
  gap: 0;
  border: 2px solid #373737;
  border-radius: 4px;
  background: #8b8b8b;
  overflow: hidden;
}

.fund-sort-segment {
  padding: 4px 9px;
  border: 0;
  border-right: 1px solid #555;
  background: #c6c6c6;
  color: #2b2b2b;
  font-size: 11px;
  cursor: pointer;
}

.fund-sort-segment:hover { background: #e0e0e0; }

.fund-sort-segment.active {
  background: #4a7f3f;
  color: #fff;
  box-shadow: inset 0 0 0 2px #2f5a26;
}

.fund-sort-divider { width: 4px; background: #555; }

.fund-sort-dir {
  padding: 4px 9px;
  border: 0;
  background: #c6c6c6;
  color: #2b2b2b;
  font-size: 11px;
  cursor: pointer;
}

.fund-sort-dir.desc { background: #b06a3c; color: #fff; }

.fund-sort-badge {
  padding: 3px 9px;
  border: 1px solid var(--amber, #f0c674);
  border-radius: 999px;
  color: var(--amber, #f0c674);
  font-size: 11px;
  font-weight: 700;
}
</style>
