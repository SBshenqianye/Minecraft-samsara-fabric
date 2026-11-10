<template>
  <TabShell name="pool-inventory" :title="title" :description="description">
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

      <div class="pool-head">
        <div>
          <h3>{{ title }}</h3>
          <p class="muted small">{{ description }}</p>
        </div>
        <span class="spacer"></span>
        <span class="muted small">共 {{ nonEmpty }} 件物品</span>
        <button class="ghost" type="button" :disabled="loading" @click="load">重新读取</button>
      </div>

      <div class="pool-actions">
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
        <button
          v-if="mode !== 'simulate'"
          class="primary"
          type="button"
          :disabled="!dirty || saving"
          @click="confirmSave"
        >
          {{ saving ? '写入中…' : '保存修改' }}{{ dirty ? ' *' : '' }}
        </button>
        <button class="ghost" type="button" :disabled="loading || saving" @click="load">重置</button>
        <span class="muted small">{{ modeHint }}</span>
      </div>

      <div class="panel">
        <ItemWorkbench
          :slots="slots"
          :items="items"
          :layout="layout"
          :schema="schema"
          :mode="mode"
          item-endpoint="/api/inventory/item"
          :revision="revision"
          :disabled="saving"
          :web-deposit="kind !== 'web'"
          :web-withdraw="kind !== 'web'"
          @change="onWorkbenchChange"
        />
      </div>
    </template>
  </TabShell>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import TabShell from '../components/TabShell.vue';
import ItemWorkbench from '../components/ItemWorkbench.vue';

const props = defineProps({
  kind: { type: String, default: 'web' },
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
const mode = ref('simulate');
const schema = ref({});
let liveTimer = null;

const title = computed(() => props.kind === 'fund' ? '基金仓库' : 'Web 背包');
const description = computed(() => props.kind === 'fund'
  ? '服务器基金的实物仓库，和容器/玩家背包共用同一套槽位、组件编辑与写入模式；支持垃圾桶。'
  : '幽灵管理员的 54 格 Web 仓库：装备栏、副手、主背包、快捷栏与仓库，共用容器工作台、调试方块页签和垃圾桶。');
const readEndpoint = computed(() => props.kind === 'fund'
  ? '/api/lottery/fund' : '/api/inventory/web');
const writeEndpoint = computed(() => props.kind === 'fund'
  ? '/api/lottery/fund/write' : '/api/inventory/web/write');
const nonEmpty = computed(() => (slots.value || []).filter((value) => value).length);

const layout = computed(() => props.kind === 'web'
  ? [
      { label: '装备 0–3', start: 0, size: 4, columns: 4 },
      { label: '副手 4', start: 4, size: 1, columns: 1 },
      { label: '主背包 5–31', start: 5, size: 27, columns: 9 },
      { label: '快捷栏 32–40', start: 32, size: 9, columns: 9 },
      { label: '仓库 41–53', start: 41, size: 13, columns: 9 },
    ]
  : [{ label: '基金仓库 0–53', start: 0, size: 54, columns: 9 }]);

const modeOptions = [
  { id: 'live', label: '实时', hint: '每次操作立即写入仓库。' },
  { id: 'preset', label: '预设', hint: '本地编辑，点保存一次性写入。' },
  { id: 'simulate', label: '模拟', hint: '只在本地演练，不会写入。' },
];
const modeHint = computed(() => modeOptions.find((option) => option.id === mode.value)?.hint || '');

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
    slots.value = (result.data.slots || []).slice();
    while (slots.value.length < 54) slots.value.push('');
    baselineSlots.value = slots.value.slice();
    items.value = result.data.items || [];
    revision.value = Date.now();
    dirty.value = false;
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
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
  slots.value = payload.slots;
  items.value = payload.items;
  dirty.value = !sameSlots(payload.slots, baselineSlots.value);
  if (!dirty.value) {
    liveTimer && clearTimeout(liveTimer);
    return;
  }
  if (mode.value !== 'live' || payload.pending) return;
  liveTimer && clearTimeout(liveTimer);
  liveTimer = setTimeout(() => save(true), 450);
}

async function setMode(next) {
  if (next === mode.value) return;
  if (next === 'live' && dirty.value) {
    try {
      await ElMessageBox.confirm('切换到实时模式会立即把当前修改写入仓库。继续？', '切换到实时模式',
        { confirmButtonText: '切换并写入', cancelButtonText: '取消', type: 'warning' });
    } catch {
      return;
    }
  }
  mode.value = next;
  if (next === 'live' && dirty.value) await save(true);
}

async function confirmSave() {
  try {
    await ElMessageBox.confirm('将当前仓库内容一次性写入服务器。', '确认写入仓库',
      { confirmButtonText: '确认写入', cancelButtonText: '取消', type: 'warning' });
  } catch {
    return;
  }
  await save(false);
}

async function save(silent) {
  if (saving.value) return;
  saving.value = true;
  error.value = '';
  try {
    const result = await api(writeEndpoint.value, {
      method: 'POST',
      body: JSON.stringify({ slots: slots.value }),
    });
    if (result.status !== 200 || !result.data.ok) throw new Error(result.data.error || '写入失败');
    slots.value = (result.data.slots || slots.value).slice();
    while (slots.value.length < 54) slots.value.push('');
    baselineSlots.value = slots.value.slice();
    items.value = result.data.items || items.value;
    dirty.value = false;
    message.value = '已写入' + title.value + '。';
    if (!silent) ElMessage.success(message.value);
  } catch (e) {
    error.value = e.message || String(e);
    if (!silent) ElMessage.error(error.value);
  } finally {
    saving.value = false;
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

.pool-head h3 {
  margin: 0 0 3px;
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
</style>
