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
      <p v-if="error" class="container-message error">{{ error }}</p>
      <p v-if="message" class="container-message ok">{{ message }}</p>

      <div class="pool-head" :class="{ 'pool-head-embedded': embedded }">
        <div>
          <h3 v-if="!embedded">{{ title }}</h3>
          <h4 v-else>{{ title }}</h4>
          <p class="muted small">{{ description }}</p>
        </div>
        <span class="spacer"></span>
        <span class="muted small">
          共 {{ nonEmpty }} 件物品 · {{ slots.length }} 格<template v-if="kind === 'fund'">
            · 实物总值 {{ formatValue(fundValue) }} VP</template>
        </span>
        <button
          v-if="kind === 'fund'"
          class="ghost"
          type="button"
          :disabled="loading || saving"
          @click="expandSlots"
        >＋ 扩充 9 格</button>
        <button class="ghost" type="button" :disabled="loading" @click="load">重新读取</button>
      </div>

      <div v-if="kind === 'fund'" class="fund-search">
        <input
          v-model="itemQuery"
          type="search"
          placeholder="搜索仓库物品（中文 / 英文 / ID）"
          aria-label="搜索仓库物品"
        />
        <select v-model="itemSort" aria-label="仓库物品排序">
          <option value="slot">按槽位</option>
          <option value="name">按名称</option>
          <option value="count">按数量</option>
          <option value="value">按价值</option>
        </select>
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

      <div class="pool-actions">
        <span class="muted small">
          改动即时写入仓库；Web 只和自己交互，不需要预设/模拟，也不需要二次确认。
        </span>
        <span v-if="saving" class="muted small">写入中…</span>
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
          :web-deposit="kind !== 'web'"
          :web-withdraw="kind !== 'web'"
          @change="onWorkbenchChange"
        />
      </div>
    </template>
  </component>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue';
import TabShell from '../components/TabShell.vue';
import ItemWorkbench from '../components/ItemWorkbench.vue';
import { useLiveRefresh } from '../composables/useLiveRefresh.js';

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
const cursorPending = ref(false);
const itemQuery = ref('');
const itemSort = ref('slot');
const focusedSlot = ref(null);
let liveTimer = null;

const title = computed(() => props.kind === 'fund' ? '基金仓库' : 'Web 背包');
const description = computed(() => props.kind === 'fund'
  ? '服务器基金的实物仓库，和容器/玩家背包共用同一套槽位与组件编辑；改动即时写入，槽位可按需扩充，没有容量上限。'
  : '幽灵管理员的 54 格 Web 仓库：装备栏、副手、主背包、快捷栏与仓库，共用容器工作台、调试方块页签和垃圾桶。');
const readEndpoint = computed(() => props.kind === 'fund'
  ? '/api/lottery/fund' : '/api/inventory/web');
const writeEndpoint = computed(() => props.kind === 'fund'
  ? '/api/lottery/fund/write' : '/api/inventory/web/write');
const nonEmpty = computed(() => (slots.value || []).filter((value) => value).length);

const layout = computed(() => {
  if (props.kind === 'fund') {
    // 每 9 格一行、每 54 格一块；扩容后自动生成新块，不截断已有物品。
    const rows = [];
    for (let start = 0; start < slots.value.length; start += 54) {
      rows.push({
        label: `基金仓库 ${start}–${Math.min(slots.value.length, start + 54) - 1}`,
        start,
        size: Math.min(54, slots.value.length - start),
        columns: 9,
        zone: 'plain',
      });
    }
    return rows;
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

const filteredItems = computed(() => {
  const query = itemQuery.value.trim().toLowerCase();
  let rows = (items.value || []).filter((row) => {
    if (!query) return true;
    return [row.zh, row.name, row.id].some((value) =>
      String(value || '').toLowerCase().includes(query));
  });
  rows = rows.slice();
  if (itemSort.value === 'name') {
    rows.sort((a, b) => String(a.zh || a.name || a.id || '')
      .localeCompare(String(b.zh || b.name || b.id || ''), 'zh-CN'));
  } else if (itemSort.value === 'count') {
    rows.sort((a, b) => (Number(b.count) || 0) - (Number(a.count) || 0));
  } else if (itemSort.value === 'value') {
    rows.sort((a, b) => (Number(b.value) || 0) - (Number(a.value) || 0));
  } else {
    rows.sort((a, b) => (Number(a.slot) || 0) - (Number(b.slot) || 0));
  }
  return rows;
});

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
    const incoming = (snapshot.slots || []).slice();
    const minimum = props.kind === 'fund' ? (snapshot.minSize || 54) : 54;
    while (incoming.length < minimum) incoming.push('');
    if (sameSlots(incoming, baselineSlots.value)) return false;
    applyLoadedData(incoming, snapshot.items || [], snapshot.value);
    message.value = '检测到仓库内容变化，已同步最新内容。';
    return true;
  },
});

// 仓库与 Web 背包同源：所有改动即时落库，因此没有实时/预设/模拟分段。

onMounted(() => {
  if (token.value) connect();
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
    const incoming = (result.data.slots || []).slice();
    const minimum = props.kind === 'fund' ? (result.data.minSize || 54) : 54;
    while (incoming.length < minimum) incoming.push('');
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
  cursorPending.value = false;
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

async function expandSlots() {
  if (saving.value) return;
  const next = slots.value.slice();
  for (let i = 0; i < 9; i++) next.push('');
  slots.value = next;
  baselineSlots.value = next.slice();
  focusedSlot.value = null;
  await save();
  message.value = `仓库已扩充到 ${slots.value.length} 格。`;
}

async function focusSlot(slot) {
  focusedSlot.value = slot;
  await nextTick();
  const node = document.querySelector(`.workbench-section .slot[aria-label^="槽位 ${slot} "]`);
  node?.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function formatValue(value) {
  const number = Number(value) || 0;
  return number >= 1000 ? (Math.round(number / 100) / 10) + 'k' : String(number);
}

function onWorkbenchChange(payload) {
  cursorPending.value = !!payload.pending;
  slots.value = payload.slots;
  items.value = payload.items;
  dirty.value = !sameSlots(payload.slots, baselineSlots.value);
  if (!dirty.value) {
    liveTimer && clearTimeout(liveTimer);
    return;
  }
  if (payload.pending) {
    message.value = '光标上还有物品，放下后才会写入仓库。';
    return;
  }
  liveTimer && clearTimeout(liveTimer);
  liveTimer = setTimeout(() => save(), 450);
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
    const incoming = (result.data.slots || slots.value).slice();
    const minimum = props.kind === 'fund' ? (result.data.minSize || 54) : 54;
    while (incoming.length < minimum) incoming.push('');
    slots.value = incoming;
    baselineSlots.value = incoming.slice();
    items.value = result.data.items || items.value;
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

.pool-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.inventory-connect {
  display: flex;
  gap: 10px;
  max-width: 520px;
  margin-top: 10px;
}

</style>
