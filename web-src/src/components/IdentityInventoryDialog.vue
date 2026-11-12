<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="min(1200px, 94vw)"
    top="4vh"
    append-to-body
    destroy-on-close
    :lock-scroll="false"
    class="identity-inventory-dialog"
    @closed="reset"
  >
    <p v-if="error" class="container-message error">{{ error }}</p>
    <p v-if="message" class="container-message ok">{{ message }}</p>

    <div class="identity-inventory-toolbar">
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
      <el-button size="small" :disabled="saving || loading" @click="load">重新读取</el-button>
      <el-button
        v-if="mode === 'preset'"
        size="small"
        type="primary"
        :disabled="!dirty || saving"
        :loading="saving"
        @click="save"
      >保存修改{{ dirty ? ' *' : '' }}</el-button>
      <span class="muted small">{{ modeHint }}</span>
      <span class="spacer"></span>
      <span class="muted small">data {{ dataUuid }} · revision {{ revision }}</span>
    </div>

    <div v-if="slots.length" class="identity-inventory-body">
      <ItemWorkbench
        :slots="slots"
        :items="items"
        :layout="layout"
        :schema="schema"
        :mode="mode"
        item-endpoint="/api/inventory/item"
        :revision="revision"
        :disabled="saving"
        :trash="false"
        @change="onChange"
      />
    </div>
    <p v-else class="muted small">{{ loading ? '加载中…' : '没有可编辑的槽位。' }}</p>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import ItemWorkbench from './ItemWorkbench.vue';
import { itemCursor } from '../composables/itemCursor.js';

const visible = ref(false);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const message = ref('');
const dataUuid = ref('');
const groupName = ref('');
const slots = ref([]);
const baselineSlots = ref([]);
const items = ref([]);
const revision = ref(0);
const dirty = ref(false);
const cursorPending = computed(() => !!itemCursor.item);
const mode = ref('simulate');
const schema = ref({});
let token = '';
let liveTimer = null;

const title = computed(() => (groupName.value ? `编辑身份组背包 · ${groupName.value}` : '编辑身份组背包'));
const modeOptions = [
  { id: 'live', label: '实时', hint: '每次操作立即写入身份数据文件；写入前自动备份。' },
  { id: 'preset', label: '预设', hint: '本地编辑，点保存一次性写入。' },
  { id: 'simulate', label: '模拟', hint: '只在本地演练，不会写入。' },
];
const modeHint = computed(() => modeOptions.find((option) => option.id === mode.value)?.hint || '');

// 与玩家背包同源的 41 格布局：装备/副手在上、主背包居中、快捷栏在下。
const layout = [
  { label: '盔甲 36–39', start: 36, size: 4, columns: 1, row: 'gear', zone: 'gear' },
  { label: '副手 40', start: 40, size: 1, columns: 1, row: 'gear', zone: 'gear' },
  { label: '主背包 9–35', start: 9, size: 27, columns: 9, row: 'main', zone: 'main' },
  { label: '快捷栏 0–8', start: 0, size: 9, columns: 9, row: 'hotbar', zone: 'hotbar', gap: true },
];

function onOpen(event) {
  const detail = event.detail || {};
  if (!detail.data) return;
  dataUuid.value = String(detail.data);
  groupName.value = String(detail.name || '');
  token = sessionStorage.getItem('samsara-token') || '';
  slots.value = [];
  items.value = [];
  dirty.value = false;
  error.value = '';
  message.value = '';
  mode.value = 'simulate';
  visible.value = true;
  loadSchema();
  load();
}

onMounted(() => window.addEventListener('samsara:identity-inventory', onOpen));
onBeforeUnmount(() => {
  window.removeEventListener('samsara:identity-inventory', onOpen);
  liveTimer && window.clearTimeout(liveTimer);
});

function reset() {
  liveTimer && window.clearTimeout(liveTimer);
  slots.value = [];
  items.value = [];
}

async function api(path, options = {}) {
  const headers = Object.assign({ Authorization: 'Bearer ' + token }, options.headers || {});
  if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, Object.assign({}, options, { headers }));
  const text = await response.text();
  let data = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = { error: text };
  }
  return { status: response.status, data };
}

async function loadSchema() {
  if (Object.keys(schema.value).length) return;
  try {
    const result = await api('/api/items/schema');
    if (result.status === 200 && result.data.ok) schema.value = result.data.schema || {};
  } catch {
    // 组件编辑降级为基础字段。
  }
}

async function load() {
  if (!dataUuid.value) return;
  loading.value = true;
  error.value = '';
  try {
    const params = new URLSearchParams({ source: 'identity', data: dataUuid.value, scope: 'inventory' });
    const result = await api('/api/inventory?' + params.toString());
    if (result.status !== 200 || !result.data.ok) {
      throw new Error(result.data.error || '读取身份背包失败');
    }
    slots.value = (result.data.slots || []).slice();
    baselineSlots.value = slots.value.slice();
    items.value = (result.data.items || []).slice();
    revision.value = result.data.revision || 0;
    dirty.value = false;
      message.value = result.data.online
      ? '该身份当前有账号在线：写入会直接写到在线玩家，退出时同步到身份数据。'
      : '写入前会自动备份身份数据文件。';
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

function onChange(payload) {
  slots.value = payload.slots;
  items.value = payload.items;
  dirty.value = !sameSlots(payload.slots, baselineSlots.value);
  if (!dirty.value) {
    liveTimer && window.clearTimeout(liveTimer);
    return;
  }
  if (mode.value !== 'live' || payload.pending) return;
  liveTimer && window.clearTimeout(liveTimer);
  liveTimer = window.setTimeout(() => save(true), 450);
}

async function setMode(next) {
  if (next === mode.value) return;
  if (next === 'live' && dirty.value) {
    try {
      await ElMessageBox.confirm('切换到实时模式会立即把当前修改写入身份数据。继续？',
        '切换到实时模式', { confirmButtonText: '切换并写入', cancelButtonText: '取消', type: 'warning' });
    } catch {
      return;
    }
  }
  mode.value = next;
  if (next === 'live' && dirty.value) await save(true);
}

async function save(silent) {
  if (saving.value || !dirty.value) return;
  if (!silent) {
    try {
      await ElMessageBox.confirm(
        `将写入 ${dataUuid.value} 的身份背包；写入前自动备份当前数据文件。`,
        '确认写入身份组背包',
        { confirmButtonText: '确认写入', cancelButtonText: '取消', type: 'warning' },
      );
    } catch {
      return;
    }
  }
  saving.value = true;
  error.value = '';
  try {
    const result = await api('/api/inventory/write', {
      method: 'POST',
      body: JSON.stringify({
        source: 'identity',
        data: dataUuid.value,
        scope: 'inventory',
        revision: revision.value,
        slots: slots.value,
      }),
    });
    if (result.status === 409) {
      await load();
      throw new Error('revision 冲突，已重新读取');
    }
    if (result.status !== 200 || !result.data.ok) {
      throw new Error(result.data.error || '写入失败');
    }
    slots.value = (result.data.slots || slots.value).slice();
    baselineSlots.value = slots.value.slice();
    items.value = result.data.items || items.value;
    revision.value = result.data.revision || revision.value;
    dirty.value = false;
    message.value = '已写入身份数据文件，并已自动备份写入前版本。';
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
.identity-inventory-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.identity-inventory-toolbar .spacer {
  flex: 1 1 auto;
}

.identity-inventory-body {
  max-height: 72vh;
  overflow: auto;
}
</style>
