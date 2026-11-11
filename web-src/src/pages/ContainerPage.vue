<template>
  <div class="container-page" :class="{ embedded }">
    <header v-if="!embedded">
      <h1>天网</h1>
      <span class="muted small">地图选箱 / 按物品找箱</span>
      <span class="spacer"></span>
      <a class="user-view-link" href="/">返回管理端</a>
      <button v-if="connected" class="ghost" type="button" @click="logout">断开</button>
    </header>
    <section v-if="embedded" class="container-context">
      <span class="muted small">天网 · 容器 / 玩家</span>
      <span class="spacer"></span>
      <button v-if="connected" class="ghost" type="button" @click="logout">断开</button>
    </section>

    <section v-if="!connected" class="container-card">
      <h2>连接管理端</h2>
      <p class="muted small">输入 <code>config/samsara/web.yml</code> 里的 token；只保存在当前标签页的 sessionStorage。</p>
      <div class="container-filters">
        <label>
          token
          <input v-model="tokenInput" type="password" autocomplete="off" placeholder="Bearer token" @keyup.enter="connect" />
        </label>
        <button class="primary" type="button" :disabled="connecting" @click="connect">
          {{ connecting ? '连接中…' : '连接' }}
        </button>
      </div>
      <p v-if="error" class="container-message error">{{ error }}</p>
    </section>

    <template v-else>
      <p v-if="message" class="container-message" :class="messageKind">{{ message }}</p>
      <p v-if="error" class="container-message error">{{ error }}</p>

      <section class="container-card">
        <div class="container-filters">
          <label>
            维度
            <select v-model="filters.dimension" :disabled="allDimensions" @change="search">
              <option v-for="dimension in dimensions" :key="dimension.id" :value="dimension.id">
                {{ dimension.name }}（{{ dimension.id }}）
              </option>
            </select>
          </label>
          <label class="dimension-toggle" title="同时搜索所有维度">
            <input v-model="allDimensions" type="checkbox" @change="toggleAllDimensions" />
            全维度
          </label>
          <label class="grow">
            按物品找箱
            <input v-model="filters.q" type="text" placeholder="物品 ID / 名称，如 diamond、钻石"
              @input="queueSearch" @keyup.enter="search" />
          </label>
          <button
            class="icon-button"
            type="button"
            :disabled="scanning"
            title="刷新索引（重新扫描已加载区块）"
            aria-label="刷新索引"
            @click="scan"
          >↻</button>
        </div>

        <div class="container-map">
          <MapPicker
            :markers="markers"
            :dimension="filters.dimension || dimensions[0]?.id || 'minecraft:overworld'"
            :selected-id="selectedMarkerId"
            :height="520"
            @select="selectMarker"
          />
        </div>

        <div class="container-result-head">
          <span class="muted small">
            索引 {{ indexSize }} 个容器 · 当前 {{ containers.length }} 个
          </span>
          <span class="spacer"></span>
          <span class="muted small">{{ loading ? '读取中…' : '' }}</span>
        </div>
        <div v-if="containers.length" class="container-list compact list-narrow">
          <button
            v-for="row in containers"
            :key="rowKey(row)"
            class="container-row"
            :class="{ active: selected && samePos(selected.container, row) }"
            type="button"
            @click="openContainer(row)"
          >
            <span>
              <strong>{{ typeLabel(row.type) }}</strong>
              <span class="meta"> · {{ row.dimension }} · {{ row.x }}, {{ row.y }}, {{ row.z }}</span>
            </span>
            <span class="meta">{{ row.nonEmpty }} / {{ row.size }} 格</span>
            <span class="meta">{{ row.source === 'loaded' ? '实时' : row.source === 'pending' ? '待写入' : '缓存' }}</span>
          </button>
        </div>
        <p v-else class="muted small">{{ loading ? '读取中…' : '没有找到容器；可输入物品名找箱，或点“刷新索引”。' }}</p>
      </section>

    </template>

    <Teleport to="body">
      <FloatingWindow
        v-for="win in visibleWindows"
        :key="win.key"
        :title="win.title"
        :subtitle="win.subtitle"
        :width="win.width"
        :height="win.height"
        :x="win.x"
        :y="win.y"
        :z="win.pinned ? 900 + win.z : win.z"
        :pinned="win.pinned"
        :active="activeWindow === win.key"
        @focus="focusWindow(win)"
        @pin="toggleWindowPin(win)"
        @minimize="minimizeWindow(win)"
        @close="closeWindow(win)"
      >
        <InventoryWindow :kind="win.kind" :target="win.target" />
      </FloatingWindow>
    </Teleport>

    <GlobalItemCursor v-if="!embedded" />

    <Teleport to="body">
      <div v-if="minimizedWindows.length" class="window-dock" aria-label="最小化的窗口">
        <span class="window-dock-title">窗口</span>
        <button
          v-for="win in minimizedWindows"
          :key="win.key"
          type="button"
          class="window-dock-tab"
          :title="win.subtitle"
          @click="restoreWindow(win)"
        >{{ win.title }}</button>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import FloatingWindow from '../components/FloatingWindow.vue';
import GlobalItemCursor from '../components/GlobalItemCursor.vue';
import InventoryWindow from '../components/InventoryWindow.vue';
import MapPicker from '../components/MapPicker.vue';
import { useLiveRefresh } from '../composables/useLiveRefresh.js';
import { itemCursor } from '../composables/itemCursor.js';

const props = defineProps({ embedded: { type: Boolean, default: false } });
const embedded = computed(() => props.embedded);

const token = ref(sessionStorage.getItem('samsara-token') || '');
const tokenInput = ref(token.value);
const connected = ref(false);
const connecting = ref(false);
const loading = ref(false);
const scanning = ref(false);
const saving = ref(false);
const error = ref('');
const message = ref('');
const messageKind = ref('');
const dimensions = ref([
  { id: 'minecraft:overworld', name: '主世界' },
  { id: 'minecraft:the_nether', name: '下界' },
  { id: 'minecraft:the_end', name: '末地' },
]);
const filters = reactive({ dimension: 'minecraft:overworld', q: '' });
const windows = ref([]);
const players = ref([]);
const selectedPlayerId = ref('');
let playersTimer = null;
const activeWindow = ref('');
let windowZ = 90;
let windowCascade = 0;
const allDimensions = ref(false);
const lastDimension = ref('minecraft:overworld');
let searchTimer = null;
const containers = ref([]);
const indexSize = ref(0);
const selected = ref(null);
const slots = ref([]);
const baselineSlots = ref([]);
const items = ref([]);
const revision = ref(0);
const dirty = ref(false);
const mode = ref('simulate');
const schema = ref({});
const cursorPending = computed(() => !!itemCursor.item);
let liveTimer = null;

const liveState = useLiveRefresh({
  mode: () => 'simulate',
  key: () => selected.value
    ? `${selected.value.container?.dimension}:${selected.value.container?.x},${selected.value.container?.y},${selected.value.container?.z}`
    : '',
  canRefresh: () => connected.value && !!selected.value && !dirty.value
    && !saving.value && !loading.value && !cursorPending.value,
  fetchSnapshot: async (signal) => {
    const row = selected.value?.container;
    if (!row) return null;
    const params = new URLSearchParams({
      dimension: row.dimension, x: String(row.x), y: String(row.y), z: String(row.z),
    });
    const result = await api('/api/containers/read?' + params.toString(), { signal });
    return result.status === 200 && result.data.ok ? result.data : null;
  },
  applySnapshot: (snapshot) => {
    if (dirty.value || saving.value || cursorPending.value || !selected.value) return false;
    const incoming = (snapshot.container?.slots || []).slice();
    if (sameSlots(incoming, baselineSlots.value)) return false;
    selected.value = snapshot;
    slots.value = incoming;
    baselineSlots.value = incoming.slice();
    items.value = (snapshot.items || []).slice();
    revision.value = snapshot.container?.revision || revision.value;
    message.value = '检测到容器内物品变化，已同步最新内容。';
    messageKind.value = 'ok';
    return true;
  },
});

const modeOptions = [
  { id: 'live', label: '实时', hint: '每次操作立即写入；未加载区块会排队，加载后应用。' },
  { id: 'preset', label: '预设', hint: '本地编辑，点保存一次性提交。' },
  { id: 'simulate', label: '模拟', hint: '只在本地演练，不会写入服务器。' },
];

const currentModeHint = computed(() =>
  modeOptions.find((option) => option.id === mode.value)?.hint || '');

const layout = computed(() => [{
  label: `容器槽位 0–${Math.max(0, (selected.value?.container?.size || slots.value.length) - 1)}`,
  start: 0,
  size: selected.value?.container?.size || slots.value.length,
  columns: (selected.value?.container?.size || 9) <= 3
    ? Math.max(1, selected.value?.container?.size || 1) : 9,
}]);

const markers = computed(() => {
  const rows = containers.value
    .filter((row) => !filters.dimension || row.dimension === filters.dimension)
    .slice(0, 300)
    .map((row) => ({
      id: rowKey(row),
      x: row.x,
      z: row.z,
      dimension: row.dimension,
      label: typeLabel(row.type),
      sub: row.x + ', ' + row.y + ', ' + row.z + ' · ' + row.nonEmpty + '/' + row.size,
      kind: 'container',
      icon: row.type,
    }));
  const people = players.value
    .filter((player) => player.dimension && Number.isFinite(Number(player.x)))
    .filter((player) => !filters.dimension || player.dimension === filters.dimension)
    .map((player) => ({
      id: 'player:' + player.uuid,
      uuid: player.uuid,
      x: Number(player.x),
      z: Number(player.z),
      dimension: player.dimension,
      label: player.name || String(player.uuid).slice(0, 8),
      sub: (player.online ? '在线' : '离线') + ' · '
        + Math.round(player.x) + ', ' + Math.round(player.y) + ', ' + Math.round(player.z),
      kind: 'player',
      online: player.online !== false,
    }));
  return rows.concat(people);
});

const selectedMarkerId = computed(() =>
  selectedPlayerId.value || (selected.value ? rowKey(selected.value.container) : ''));

onMounted(() => {
  if (token.value) connect();
});

onBeforeUnmount(() => {
  window.clearTimeout(searchTimer);
  if (playersTimer) window.clearInterval(playersTimer);
});

async function loadPlayers() {
  try {
    const result = await api('/api/server/players');
    if (result.status === 200 && result.data.ok) {
      players.value = result.data.players || [];
    }
  } catch {
    // 玩家位置不可用时只显示容器。
  }
}


function typeLabel(type) {
  const key = String(type || '');
  const map = {
    'minecraft:chest': '箱子',
    'minecraft:trapped_chest': '陷阱箱',
    'minecraft:barrel': '木桶',
    'minecraft:shulker_box': '潜影盒',
    'minecraft:hopper': '漏斗',
    'minecraft:dispenser': '发射器',
    'minecraft:dropper': '投掷器',
    'minecraft:furnace': '熔炉',
    'minecraft:blast_furnace': '高炉',
    'minecraft:smoker': '烟熏炉',
    'minecraft:brewing_stand': '酿造台',
    'minecraft:ender_chest': '末影箱',
  };
  return map[key] || key.replace('minecraft:', '');
}

function rowKey(row) {
  return row.dimension + ':' + row.x + ',' + row.y + ',' + row.z;
}

async function api(path, options = {}) {
  const headers = Object.assign({ Authorization: 'Bearer ' + token.value }, options.headers || {});
  if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, Object.assign({}, options, { headers }));
  const text = await response.text();
  let data = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = { error: text };
  }
  if (response.status === 401) {
    connected.value = false;
    throw new Error('token 无效或已失效');
  }
  return { status: response.status, data };
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
    await Promise.all([loadDimensions(), loadSchema(), loadPlayers()]);
    await search();
    if (!playersTimer) playersTimer = window.setInterval(loadPlayers, 4000);
  } catch (e) {
    error.value = e.message || String(e);
    connected.value = false;
  } finally {
    connecting.value = false;
  }
}

function logout() {
  sessionStorage.removeItem('samsara-token');
  token.value = '';
  tokenInput.value = '';
  connected.value = false;
  selected.value = null;
  containers.value = [];
  slots.value = [];
  items.value = [];
}

async function loadDimensions() {
  try {
    const result = await api('/api/map');
    if (result.status !== 200) return;
    const list = [];
    const seen = new Set();
    for (const stack of result.data.stacks || []) {
      for (const dimension of stack.dimensions || []) {
        if (!dimension.id || seen.has(dimension.id)) continue;
        seen.add(dimension.id);
        list.push({ id: dimension.id, name: dimension.name || dimension.id });
      }
    }
    if (list.length) {
      dimensions.value = list;
      if (!seen.has(filters.dimension)) filters.dimension = list[0].id;
      if (allDimensions.value) filters.dimension = '';
    }
  } catch {
    // 保留默认三维度
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

async function search() {
  loading.value = true;
  error.value = '';
  try {
    const params = new URLSearchParams();
    if (filters.dimension) params.set('dimension', filters.dimension);
    const q = (filters.q || '').trim();
    if (q) params.set('q', q);
    const result = await api('/api/containers?' + params.toString());
    if (result.status !== 200 || !result.data.ok) {
      throw new Error(result.data.error || '读取容器列表失败');
    }
    containers.value = result.data.containers || [];
    indexSize.value = result.data.indexSize || 0;
    if (!containers.value.length) {
      message.value = q ? '没有容器包含匹配该物品。' : '没有找到容器；可点“刷新索引”。';
      messageKind.value = '';
    } else if (!selected.value) {
      // 首次进入自动打开离 E2E 常用测试箱/出生点最近的容器，避免用户还要多点一次。
      const preferred = containers.value.find((row) => row.x === 8 && row.z === 8)
        || containers.value[0];
      await openContainer(preferred);
    }
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

function queueSearch() {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(search, 320);
}

function toggleAllDimensions() {
  if (allDimensions.value) {
    lastDimension.value = filters.dimension || lastDimension.value;
    filters.dimension = '';
  } else {
    filters.dimension = lastDimension.value || dimensions.value[0]?.id || 'minecraft:overworld';
  }
  search();
}

async function searchAllDimensions() {
  if (filters.dimension) {
    filters.dimension = '';
  } else {
    filters.dimension = dimensions.value[0]?.id || 'minecraft:overworld';
  }
  await search();
}

async function scan() {
  scanning.value = true;
  error.value = '';
  try {
    const result = await api('/api/containers/scan', {
      method: 'POST',
      body: JSON.stringify({
        dimension: filters.dimension,
        x: null,
        z: null,
        radius: 0,
      }),
    });
    if (result.status === 429) throw new Error('刷新过于频繁，请稍后再试');
    if (result.status !== 200 || !result.data.ok) throw new Error(result.data.error || '刷新索引失败');
    message.value = '已刷新已加载区块；正在重新读取索引…';
    messageKind.value = 'ok';
    await new Promise((resolve) => setTimeout(resolve, 800));
    await search();
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    scanning.value = false;
  }
}

function selectMarker(id) {
  if (String(id).startsWith('player:')) {
    const player = players.value.find((row) => 'player:' + row.uuid === id);
    if (player) openPlayerWindow(player);
    return;
  }
  const row = containers.value.find((candidate) => rowKey(candidate) === id);
  if (row) openContainer(row);
}

function openPlayerWindow(player) {
  selectedPlayerId.value = 'player:' + player.uuid;
  const key = selectedPlayerId.value;
  const existing = windows.value.find((win) => win.key === key);
  if (existing) {
    existing.minimized = false;
    focusWindow(existing);
    return;
  }
  windowCascade = (windowCascade + 1) % 7;
  windows.value.push({
    key,
    kind: 'player',
    target: { uuid: player.uuid, name: player.name, online: player.online },
    title: player.name || String(player.uuid).slice(0, 8),
    subtitle: (player.online ? '在线' : '离线') + ' · ' + player.uuid,
    width: 760,
    height: 680,
    x: 180 + windowCascade * 28,
    y: 88 + windowCascade * 22,
    z: ++windowZ,
    pinned: false,
    minimized: false,
  });
  activeWindow.value = key;
}

function openContainer(row) {
  selectedPlayerId.value = '';
  selected.value = { container: row };
  openWindow(row);
}

function openWindow(row) {
  const key = rowKey(row);
  const existing = windows.value.find((win) => win.key === key);
  if (existing) {
    existing.minimized = false;
    focusWindow(existing);
    return;
  }
  windowCascade = (windowCascade + 1) % 7;
  windows.value.push({
    key,
    kind: 'container',
    target: Object.assign({}, row),
    title: typeLabel(row.type),
    subtitle: row.dimension + ' · ' + row.x + ', ' + row.y + ', ' + row.z,
    width: 820,
    height: 640,
    x: 120 + windowCascade * 28,
    y: 96 + windowCascade * 22,
    z: ++windowZ,
    pinned: false,
    minimized: false,
  });
  activeWindow.value = key;
}

const visibleWindows = computed(() => windows.value.filter((win) => !win.minimized));
const minimizedWindows = computed(() => windows.value.filter((win) => win.minimized));

function focusWindow(win) {
  windowZ += 1;
  win.z = windowZ;
  activeWindow.value = win.key;
}

function toggleWindowPin(win) {
  win.pinned = !win.pinned;
  if (win.pinned) focusWindow(win);
}

function minimizeWindow(win) {
  win.minimized = true;
  if (activeWindow.value === win.key) activeWindow.value = '';
}

function restoreWindow(win) {
  win.minimized = false;
  focusWindow(win);
}

function closeWindow(win) {
  windows.value = windows.value.filter((row) => row.key !== win.key);
  if (activeWindow.value === win.key) activeWindow.value = '';
  if (selected.value && rowKey(selected.value.container) === win.key) selected.value = null;
  if (selectedPlayerId.value === win.key) selectedPlayerId.value = '';
}

async function reloadContainer() {
  if (!selected.value) return;
  await openContainer(selected.value.container);
  message.value = '已重置为服务器当前内容。';
  messageKind.value = '';
}

function samePos(a, b) {
  return a && b && a.dimension === b.dimension && a.x === b.x && a.y === b.y && a.z === b.z;
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

function onWorkbenchChange(payload) {
  if (!selected.value) return;
  slots.value = payload.slots;
  items.value = payload.items;
  dirty.value = !sameSlots(payload.slots, baselineSlots.value);
  if (!dirty.value) {
    liveTimer && clearTimeout(liveTimer);
    return;
  }
  if (mode.value !== 'live' || payload.pending) {
    if (mode.value === 'live' && payload.pending) {
      message.value = '光标上还有物品，放下后才会实时写入。';
      messageKind.value = '';
    }
    return;
  }
  liveTimer && clearTimeout(liveTimer);
  liveTimer = setTimeout(() => save(true), 450);
}

async function setMode(next) {
  if (next === mode.value) return;
  if (next === 'live' && dirty.value) {
    try {
      await ElMessageBox.confirm(
        '切换到实时模式会立即把当前修改写入容器；未加载区块会排队。继续？',
        '切换到实时模式',
        { confirmButtonText: '切换并写入', cancelButtonText: '取消', type: 'warning' },
      );
    } catch {
      return;
    }
  }
  mode.value = next;
  if (next === 'live' && dirty.value) await save(true);
}

async function confirmSave() {
  if (!selected.value) return;
  try {
    await ElMessageBox.confirm(
      `将写入 ${selected.value.container.dimension} ${selected.value.container.x},`
        + `${selected.value.container.y},${selected.value.container.z}；revision ${revision.value}。`,
      '确认保存容器',
      { confirmButtonText: '确认写入', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  await save(false);
}

async function save(silent) {
  if (saving.value || !selected.value) return;
  saving.value = true;
  error.value = '';
  try {
    const container = selected.value.container;
    const result = await api('/api/containers/write', {
      method: 'POST',
      body: JSON.stringify({
        dimension: container.dimension,
        x: container.x,
        y: container.y,
        z: container.z,
        revision: revision.value,
        slots: slots.value,
      }),
    });
    if (result.status === 409) {
      revision.value = result.data.currentRevision || revision.value;
      await reloadContainer();
      throw new Error('保存冲突：容器已被其他操作修改，已重新读取。');
    }
    if ((result.status !== 200 && result.status !== 202) || !result.data.ok) {
      throw new Error(result.data.error || '保存失败');
    }
    dirty.value = false;
    if (result.data.queued) {
      // 未加载区块的写入只是排队，缓存内容还是旧的；不要把界面回滚到旧快照。
      if (result.data.container) {
        selected.value = Object.assign({}, selected.value, {
          container: Object.assign({}, selected.value.container, { source: 'pending' }),
        });
      }
      message.value = '目标区块未加载，已排队；区块加载后自动写入。';
      messageKind.value = '';
      if (!silent) ElMessage.success('已加入待写入队列');
    } else {
      if (result.data.container) {
        selected.value = Object.assign({}, selected.value, { container: result.data.container });
        revision.value = result.data.container.revision;
        slots.value = (result.data.container.slots || slots.value).slice();
        selected.value.items = result.data.items || selected.value.items;
      }
      message.value = '已写入容器。';
      messageKind.value = 'ok';
      if (!silent) ElMessage.success('已写入容器');
    }
    baselineSlots.value = slots.value.slice();
    await search();
  } catch (e) {
    error.value = e.message || String(e);
    if (!silent) ElMessage.error(error.value);
  } finally {
    saving.value = false;
    liveState.invalidate();
  }
}
</script>

<style scoped>
.container-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.container-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 9px;
}

.container-filters .grow {
  flex: 1 1 220px;
}

.container-result-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 9px 0 5px;
}

.container-list.compact {
  max-height: 280px;
  overflow: auto;
}

.mode-switch {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--panel-soft);
}

.mode-switch button {
  padding: 4px 10px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  cursor: pointer;
}

.mode-switch button.active {
  background: var(--accent);
  color: #fff;
}

.mode-note {
  margin: 0 0 8px;
  color: var(--muted);
  font-size: 11px;
}

.container-map {
  margin-top: 12px;
}

.list-narrow {
  max-width: 760px;
}

.dimension-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--muted);
  font-size: 12px;
  white-space: nowrap;
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


.window-dock {
  position: fixed;
  right: 0;
  top: 30%;
  z-index: 880;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 3px;
  padding: 6px 5px;
  border: 1px solid var(--line);
  border-right: 0;
  border-radius: 9px 0 0 9px;
  background: var(--panel);
  box-shadow: -8px 0 24px rgba(0, 0, 0, .35);
}

.window-dock-title {
  color: var(--muted);
  font-size: 9px;
  text-align: center;
}

.window-dock-tab {
  max-width: 108px;
  padding: 4px 7px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel-soft);
  color: var(--fg);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.window-dock-tab:hover {
  border-color: var(--accent);
}

</style>
