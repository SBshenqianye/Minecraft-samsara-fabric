<template>
  <div class="container-page" :class="{ embedded }">
    <PageHeader
      v-if="embedded"
      title="容器"
      description="维度/坐标/半径/类型搜索，读取与编辑容器槽位；写入前保存审计快照并校验 revision。"
    />
    <header v-if="!embedded">
      <h1>容器</h1>
      <span class="muted small">浏览/编辑箱子、桶、潜影盒等容器；写入前会保存审计快照</span>
      <span class="spacer"></span>
      <a class="user-view-link" href="/">返回管理端</a>
      <button v-if="connected" class="ghost" type="button" @click="logout">断开</button>
    </header>
    <section v-if="embedded" class="container-context">
      <span class="muted small">天网 · 容器 · 浏览/编辑箱子、桶、潜影盒；写入前会保存审计快照。</span>
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
        <h2>搜索容器</h2>
        <div class="container-filters">
          <label>
            维度
            <select v-model="filters.dimension">
              <option v-for="dimension in dimensions" :key="dimension.id" :value="dimension.id">
                {{ dimension.name }}（{{ dimension.id }}）
              </option>
            </select>
          </label>
          <label>
            X
            <input v-model.number="filters.x" type="number" />
          </label>
          <label>
            Z
            <input v-model.number="filters.z" type="number" />
          </label>
          <label>
            半径（格）
            <input v-model.number="filters.radius" type="number" min="0" max="512" />
          </label>
          <label>
            类型
            <input v-model="filters.type" type="text" placeholder="minecraft:chest" />
          </label>
          <button class="primary" type="button" :disabled="loading" @click="search">搜索</button>
          <button class="ghost" type="button" :disabled="scanning" @click="scan">刷新索引</button>
        </div>
        <p class="muted small">只列出索引中的容器；未加载区块显示缓存内容，已加载区块读取实时容器。</p>
        <div v-if="containers.length" class="container-list">
          <button
            v-for="row in containers"
            :key="row.dimension + ':' + row.x + ',' + row.y + ',' + row.z"
            class="container-row"
            :class="{ active: selected && samePos(selected.container, row) }"
            type="button"
            @click="openContainer(row)"
          >
            <span>
              <strong>{{ row.type }}</strong>
              <span class="meta"> · {{ row.dimension }} · {{ row.x }}, {{ row.y }}, {{ row.z }}</span>
            </span>
            <span class="meta">{{ row.nonEmpty }} / {{ row.size }} 格</span>
            <span class="meta">rev {{ row.revision }} · {{ row.source === 'loaded' ? '实时' : '缓存' }}</span>
          </button>
        </div>
        <p v-else class="muted small">{{ loading ? '读取中…' : '没有找到容器；可调整坐标/半径或点“刷新索引”。' }}</p>
      </section>

      <section v-if="selected" class="container-card">
        <div class="container-filters">
          <div>
            <h2>{{ selected.container.type }}</h2>
            <p class="muted small">
              {{ selected.container.dimension }} · {{ selected.container.x }}, {{ selected.container.y }}, {{ selected.container.z }}
              · revision {{ revision }} · {{ selected.container.source === 'loaded' ? '实时' : '缓存' }}
            </p>
          </div>
          <span class="spacer"></span>
          <button class="ghost" type="button" @click="reloadContainer">重新读取</button>
          <button class="primary" type="button" :disabled="!dirty || saving" @click="confirmOpen = true">
            保存{{ dirty ? ' *' : '' }}
          </button>
        </div>

        <ItemSlotGrid
          :slots="slots"
          :items="items"
          :selected="selectedSlot"
          @select="selectSlot"
        />

        <div class="slot-editor">
          <template v-if="selectedSlot !== null">
            <template v-if="itemAt(selectedSlot)">
              <label>
                槽位 {{ selectedSlot }} · {{ itemAt(selectedSlot).name }}
                <input v-model.number="editCount" type="number" min="1" :max="itemAt(selectedSlot).maxStack || 64" />
              </label>
              <button class="primary" type="button" :disabled="busy" @click="updateCount">更新数量</button>
              <button class="ghost" type="button" :disabled="busy" @click="removeSlot">移除</button>
              <span class="muted small">{{ itemAt(selectedSlot).id }}</span>
            </template>
            <template v-else>
              <label>
                物品搜索
                <input v-model="searchQuery" type="text" placeholder="中文/英文/ID，如 diamond、钻石" @keyup.enter="searchItems" />
              </label>
              <button class="ghost" type="button" :disabled="searchLoading" @click="searchItems">搜索物品</button>
              <label v-if="newItem">
                数量
                <input v-model.number="newItemCount" type="number" min="1" max="99" />
              </label>
              <button v-if="newItem" class="primary" type="button" :disabled="busy" @click="addItem">
                添加 {{ newItem.zh || newItem.name }}
              </button>
            </template>
          </template>
          <span v-else class="muted small">点击一个槽位查看/编辑；空槽位可以添加物品。</span>
        </div>

        <div v-if="searchResults.length && selectedSlot !== null && !itemAt(selectedSlot)" class="container-list" style="margin-top: 10px">
          <button
            v-for="item in searchResults"
            :key="item.id"
            class="container-row"
            :class="{ active: newItem && newItem.id === item.id }"
            type="button"
            @click="chooseItem(item)"
          >
            <span><strong>{{ item.zh || item.name }}</strong> <span class="meta">{{ item.id }}</span></span>
            <span class="meta">{{ item.value }} VP</span>
            <span class="meta">选择</span>
          </button>
        </div>

        <details v-if="itemAt(selectedSlot)" style="margin-top: 12px">
          <summary class="muted small">组件 / NBT 信息</summary>
          <p class="container-detail">{{ itemAt(selectedSlot).components }}</p>
          <p class="container-detail">{{ itemAt(selectedSlot).encoded }}</p>
        </details>
      </section>
    </template>

    <div v-if="confirmOpen" class="modal-backdrop" @click.self="confirmOpen = false">
      <div class="modal-card">
        <h3>确认保存容器</h3>
        <p class="muted small">
          将写入 {{ selected?.container?.dimension }} {{ selected?.container?.x }}, {{ selected?.container?.y }}, {{ selected?.container?.z }}；
          当前 revision {{ revision }}，非空槽位 {{ slots.filter((slot) => slot).length }} 个。
        </p>
        <p class="muted small">写入前会保存编辑前快照到 Web 审计；revision 冲突会拒绝保存。</p>
        <div class="modal-actions">
          <button class="ghost" type="button" @click="confirmOpen = false">取消</button>
          <button class="primary" type="button" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '确认保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import ItemSlotGrid from '../components/ItemSlotGrid.vue';
import PageHeader from '../components/PageHeader.vue';

const props = defineProps({
  embedded: { type: Boolean, default: false },
});
const embedded = computed(() => props.embedded);
const token = ref(sessionStorage.getItem('samsara-token') || '');
const tokenInput = ref(token.value);
const connected = ref(false);
const connecting = ref(false);
const loading = ref(false);
const scanning = ref(false);
const saving = ref(false);
const busy = ref(false);
const error = ref('');
const message = ref('');
const messageKind = ref('');
const dimensions = ref([
  { id: 'minecraft:overworld', name: '主世界' },
  { id: 'minecraft:the_nether', name: '下界' },
  { id: 'minecraft:the_end', name: '末地' },
]);
const filters = reactive({ dimension: 'minecraft:overworld', x: 0, z: 0, radius: 64, type: '' });
const containers = ref([]);
const selected = ref(null);
const slots = ref([]);
const items = ref([]);
const revision = ref(0);
const selectedSlot = ref(null);
const editCount = ref(1);
const searchQuery = ref('');
const searchResults = ref([]);
const searchLoading = ref(false);
const newItem = ref(null);
const newItemCount = ref(1);
const confirmOpen = ref(false);
const dirty = ref(false);

const itemMap = computed(() => {
  const map = new Map();
  for (const item of items.value) map.set(item.slot, item);
  return map;
});

onMounted(() => {
  if (token.value) connect();
});

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
    await loadDimensions();
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
    if (list.length) dimensions.value = list;
  } catch {
    // 保留默认三维度
  }
}

async function search() {
  loading.value = true;
  error.value = '';
  try {
    const params = new URLSearchParams();
    if (filters.dimension) params.set('dimension', filters.dimension);
    if (filters.x !== null && filters.x !== '' && filters.z !== null && filters.z !== '') {
      params.set('x', String(filters.x));
      params.set('z', String(filters.z));
    }
    params.set('radius', String(filters.radius || 0));
    if (filters.type) params.set('type', filters.type);
    const result = await api('/api/containers?' + params.toString());
    if (result.status !== 200) throw new Error(result.data.error || '读取容器列表失败');
    containers.value = result.data.containers || [];
    if (!containers.value.length) {
      message.value = '没有找到容器；可点“刷新索引”后重试。';
      messageKind.value = '';
    }
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

async function scan() {
  scanning.value = true;
  error.value = '';
  try {
    const result = await api('/api/containers/scan', {
      method: 'POST',
      body: JSON.stringify({
        dimension: filters.dimension,
        x: filters.x,
        z: filters.z,
        radius: filters.radius,
      }),
    });
    if (result.status === 429) throw new Error('刷新过于频繁，请稍后再试');
    if (result.status !== 200 || !result.data.ok) throw new Error(result.data.error || '刷新索引失败');
    message.value = '已排队刷新 ' + result.data.queuedChunks + ' 个已加载区块，正在读取索引…';
    messageKind.value = 'ok';
    await new Promise((resolve) => setTimeout(resolve, 1200));
    await search();
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    scanning.value = false;
  }
}

async function openContainer(row) {
  error.value = '';
  message.value = '';
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
    selectedSlot.value = null;
    dirty.value = false;
  } catch (e) {
    error.value = e.message || String(e);
  }
}

async function reloadContainer() {
  if (!selected.value) return;
  await openContainer(selected.value.container);
}

function samePos(a, b) {
  return a && b && a.dimension === b.dimension && a.x === b.x && a.y === b.y && a.z === b.z;
}

function itemAt(index) {
  return itemMap.value.get(index) || null;
}

function selectSlot(index) {
  selectedSlot.value = index;
  const item = itemAt(index);
  editCount.value = item ? item.count : 1;
  searchQuery.value = '';
  searchResults.value = [];
  newItem.value = null;
  newItemCount.value = 1;
}

function chooseItem(item) {
  newItem.value = item;
  newItemCount.value = 1;
}

async function searchItems() {
  const query = (searchQuery.value || '').trim();
  if (!query) {
    searchResults.value = [];
    return;
  }
  searchLoading.value = true;
  error.value = '';
  try {
    const result = await api('/api/lottery/values?q=' + encodeURIComponent(query) + '&limit=20');
    if (result.status !== 200) throw new Error(result.data.error || '物品搜索失败');
    searchResults.value = (result.data.values || []).filter((item) => item.acceptable !== false);
    if (!searchResults.value.length) {
      message.value = '没有匹配的物品。';
      messageKind.value = '';
    }
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    searchLoading.value = false;
  }
}

async function updateCount() {
  const index = selectedSlot.value;
  const item = itemAt(index);
  if (!item) return;
  const count = Number(editCount.value);
  if (!Number.isFinite(count) || count < 1) {
    error.value = '数量至少为 1';
    return;
  }
  busy.value = true;
  error.value = '';
  try {
    const result = await api('/api/containers/item', {
      method: 'POST',
      body: JSON.stringify({ encoded: slots.value[index], count }),
    });
    if (result.status !== 200 || !result.data.ok) throw new Error(result.data.error || '更新数量失败');
    slots.value[index] = result.data.encoded;
    items.value = items.value.map((row) => row.slot === index
      ? Object.assign({}, row, {
        count: result.data.count,
        encoded: result.data.encoded,
        components: result.data.components,
      })
      : row);
    dirty.value = true;
    message.value = '已更新槽位 ' + index + ' 的数量；保存后写入容器。';
    messageKind.value = 'ok';
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    busy.value = false;
  }
}

function removeSlot() {
  const index = selectedSlot.value;
  if (index === null) return;
  slots.value[index] = '';
  items.value = items.value.filter((row) => row.slot !== index);
  dirty.value = true;
  message.value = '已移除槽位 ' + index + '；保存后写入容器。';
  messageKind.value = 'ok';
}

async function addItem() {
  if (!newItem.value) return;
  let index = selectedSlot.value;
  if (index === null || slots.value[index]) {
    index = slots.value.findIndex((slot) => !slot);
  }
  if (index < 0) {
    error.value = '容器已满，没有空槽位';
    return;
  }
  const count = Number(newItemCount.value);
  if (!Number.isFinite(count) || count < 1) {
    error.value = '数量至少为 1';
    return;
  }
  busy.value = true;
  error.value = '';
  try {
    const result = await api('/api/containers/item', {
      method: 'POST',
      body: JSON.stringify({ id: newItem.value.id, count }),
    });
    if (result.status !== 200 || !result.data.ok) throw new Error(result.data.error || '添加物品失败');
    slots.value[index] = result.data.encoded;
    items.value = items.value.filter((row) => row.slot !== index);
    items.value.push({
      slot: index,
      id: result.data.id,
      icon: (result.data.id || '').split(':').pop(),
      name: result.data.name,
      count: result.data.count,
      maxStack: 64,
      encoded: result.data.encoded,
      components: result.data.components,
    });
    selectedSlot.value = index;
    newItem.value = null;
    searchResults.value = [];
    dirty.value = true;
    message.value = '已添加 ' + result.data.name + ' 到槽位 ' + index + '；保存后写入容器。';
    messageKind.value = 'ok';
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    busy.value = false;
  }
}

async function save() {
  if (!selected.value) return;
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
      throw new Error('保存冲突：容器已被其他操作修改（当前 revision ' + revision.value + '），已重新读取。');
    }
    if (result.status !== 200 || !result.data.ok) throw new Error(result.data.error || '保存失败');
    confirmOpen.value = false;
    dirty.value = false;
    revision.value = result.data.container.revision;
    await reloadContainer();
    await search();
    message.value = '已保存容器 ' + container.dimension + ' ' + container.x + ',' + container.y + ',' + container.z;
    messageKind.value = 'ok';
  } catch (e) {
    error.value = e.message || String(e);
    confirmOpen.value = false;
  } finally {
    saving.value = false;
  }
}
</script>
