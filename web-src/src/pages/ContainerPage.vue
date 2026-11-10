<template>
  <div class="container-page" :class="{ embedded }">
    <header v-if="!embedded">
      <h1>容器</h1>
      <span class="muted small">地图选箱 / 按物品找箱；点击拾取、右键分半，实时或预设写入。</span>
      <span class="spacer"></span>
      <a class="user-view-link" href="/">返回管理端</a>
      <button v-if="connected" class="ghost" type="button" @click="logout">断开</button>
    </header>
    <section v-if="embedded" class="container-context">
      <span class="muted small">天网 · 容器 · 地图选箱 / 按物品找箱；写入前会保存审计快照。</span>
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
            <select v-model="filters.dimension" @change="search">
              <option v-for="dimension in dimensions" :key="dimension.id" :value="dimension.id">
                {{ dimension.name }}（{{ dimension.id }}）
              </option>
            </select>
          </label>
          <label class="grow">
            按物品找箱
            <input v-model="filters.q" type="text" placeholder="物品 ID / 名称，如 diamond、钻石" @keyup.enter="search" />
          </label>
          <button class="primary" type="button" :disabled="loading" @click="search">搜索</button>
          <button class="ghost" type="button" :disabled="scanning" @click="scan">刷新索引</button>
          <button class="ghost" type="button" :disabled="loading" @click="searchAllDimensions">
            {{ filters.dimension ? '全维度搜索' : '按当前维度' }}
          </button>
        </div>

        <MapPicker
          :markers="markers"
          :dimension="filters.dimension || dimensions[0]?.id || 'minecraft:overworld'"
          :selected-id="selectedMarkerId"
          :height="360"
          @select="selectMarker"
        />

        <div class="container-result-head">
          <span class="muted small">
            索引 {{ indexSize }} 个容器；当前结果 {{ containers.length }} 个。未加载区块显示缓存，已加载区块实时。
          </span>
          <span class="spacer"></span>
          <span class="muted small">{{ loading ? '读取中…' : '' }}</span>
        </div>
        <div v-if="containers.length" class="container-list compact">
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

      <section v-if="selected" class="container-card">
        <div class="container-filters">
          <div>
            <h2>{{ typeLabel(selected.container.type) }}</h2>
            <p class="muted small">
              {{ selected.container.dimension }} · {{ selected.container.x }}, {{ selected.container.y }}, {{ selected.container.z }}
              · revision {{ revision }} · {{ selected.container.source === 'loaded' ? '实时容器' : selected.container.source === 'pending' ? '排队写入中' : '磁盘缓存' }}
            </p>
          </div>
          <span class="spacer"></span>
          <div class="mode-switch" role="group" aria-label="写入模式">
            <button
              v-for="option in modeOptions"
              :key="option.id"
              type="button"
              :class="{ active: mode === option.id }"
              :title="option.hint"
              @click="setMode(option.id)"
            >{{ option.label }}</button>
          </div>
          <button class="ghost" type="button" :disabled="saving" @click="reloadContainer">重置</button>
          <button
            v-if="mode !== 'simulate'"
            class="primary"
            type="button"
            :disabled="!dirty || saving"
            @click="confirmSave"
          >
            {{ saving ? '写入中…' : (mode === 'live' ? '立即写入' : '保存修改') }}{{ dirty ? ' *' : '' }}
          </button>
        </div>

        <p class="mode-note">{{ currentModeHint }}</p>

        <ItemWorkbench
          :slots="slots"
          :items="items"
          :layout="layout"
          :schema="schema"
          :mode="mode"
          item-endpoint="/api/containers/item"
          :revision="revision"
          :disabled="saving"
          @change="onWorkbenchChange"
        />
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import ItemWorkbench from '../components/ItemWorkbench.vue';
import MapPicker from '../components/MapPicker.vue';

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
const containers = ref([]);
const indexSize = ref(0);
const selected = ref(null);
const slots = ref([]);
const items = ref([]);
const revision = ref(0);
const dirty = ref(false);
const mode = ref('simulate');
const schema = ref({});
let liveTimer = null;

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

const markers = computed(() => containers.value
  .filter((row) => !filters.dimension || row.dimension === filters.dimension)
  .slice(0, 300)
  .map((row) => ({
    id: rowKey(row),
    x: row.x,
    z: row.z,
    dimension: row.dimension,
    label: typeLabel(row.type),
    sub: `${row.x}, ${row.y}, ${row.z} · ${row.nonEmpty}/${row.size}`,
    kind: 'container',
  })));

const selectedMarkerId = computed(() =>
  selected.value ? rowKey(selected.value.container) : '');

onMounted(() => {
  if (token.value) connect();
});

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
    await Promise.all([loadDimensions(), loadSchema()]);
    await search();
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
  const row = containers.value.find((candidate) => rowKey(candidate) === id);
  if (row) openContainer(row);
}

async function openContainer(row) {
  error.value = '';
  message.value = '';
  liveTimer && clearTimeout(liveTimer);
  try {
    const params = new URLSearchParams({
      dimension: row.dimension,
      x: String(row.x),
      y: String(row.y),
      z: String(row.z),
    });
    const result = await api('/api/containers/read?' + params.toString());
    if (result.status !== 200 || !result.data.ok) throw new Error(result.data.error || '读取容器失败');
    selected.value = result.data;
    slots.value = (result.data.container.slots || []).slice();
    items.value = (result.data.items || []).slice();
    revision.value = result.data.container.revision;
    dirty.value = false;
  } catch (e) {
    error.value = e.message || String(e);
  }
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

function onWorkbenchChange(payload) {
  if (!selected.value) return;
  slots.value = payload.slots;
  items.value = payload.items;
  dirty.value = true;
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
    await search();
  } catch (e) {
    error.value = e.message || String(e);
    if (!silent) ElMessage.error(error.value);
  } finally {
    saving.value = false;
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
</style>
