<template>
  <div class="identity-inline-editor">
    <p v-if="error" class="container-message error">{{ error }}</p>
    <p v-if="message" class="container-message ok">{{ message }}</p>
    <div class="identity-inline-toolbar">
      <div class="mode-switch identity-scope-switch">
        <button type="button" :class="{ active: scope === 'inventory' }" @click="setScope('inventory')">背包</button>
        <button type="button" :class="{ active: scope === 'ender' }" @click="setScope('ender')">末影箱</button>
      </div>
      <button class="ghost tiny" type="button" :disabled="saving || loading" title="重新读取" aria-label="重新读取" @click="load">↻</button>
      <button class="primary tiny" type="button" :disabled="!dirty || saving" @click="save">保存修改</button>
      <span class="muted small">{{ filled }} / {{ slots.length }} 格</span>
      <span class="spacer"></span>
      <span class="muted small">data {{ dataUuid }} · revision {{ revision }}</span>
    </div>
    <div v-if="slots.length" class="identity-inline-body">
      <ItemWorkbench
        :slots="slots"
        :items="items"
        :layout="layout"
        :schema="schema"
        mode="simulate"
        item-endpoint="/api/inventory/item"
        :revision="revision"
        :editable="true"
        :trash="false"
        :show-toolbar="false"
        @change="onChange"
      />
    </div>
    <p v-else-if="loading" class="muted small">读取中…</p>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import ItemWorkbench from '../components/ItemWorkbench.vue';

const dataUuid = ref('');
const scope = ref('inventory');
const slots = ref([]);
const items = ref([]);
const schema = ref({});
const revision = ref(0);
const baseline = ref([]);
const dirty = ref(false);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const message = ref('');
let token = '';

const filled = computed(() => (slots.value || []).filter((slot) => slot).length);
const layout = computed(() => (scope.value === 'ender'
  ? [{ label: '末影箱 0–26', start: 0, size: 27, columns: 9 }]
  : [
    { label: '盔甲 36–39', start: 36, size: 4, columns: 1, row: 'gear', zone: 'gear' },
    { label: '副手 40', start: 40, size: 1, columns: 1, row: 'gear', zone: 'gear' },
    { label: '主背包 9–35', start: 9, size: 27, columns: 9, row: 'main', zone: 'main' },
    { label: '快捷栏 0–8', start: 0, size: 9, columns: 9, row: 'hotbar', zone: 'hotbar', gap: true },
  ]));

async function api(path, options = {}) {
  const response = await fetch(path, Object.assign({}, options, {
    headers: Object.assign({
      Authorization: 'Bearer ' + token,
      'Content-Type': 'application/json',
    }, options.headers || {}),
  }));
  let data = {};
  try { data = await response.json(); } catch { data = {}; }
  return { status: response.status, data };
}

async function loadSchema() {
  if (Object.keys(schema.value).length) return;
  const result = await api('/api/items/schema');
  if (result.status === 200 && result.data.ok) schema.value = result.data.schema || {};
}

async function load() {
  if (!dataUuid.value) return;
  loading.value = true;
  error.value = '';
  message.value = '';
  try {
    const params = new URLSearchParams({ source: 'identity', data: dataUuid.value, scope: scope.value });
    const result = await api('/api/inventory?' + params.toString());
    if (result.status !== 200 || !result.data.ok) {
      throw new Error(result.data.error || '读取身份背包失败');
    }
    slots.value = (result.data.slots || []).slice();
    baseline.value = slots.value.slice();
    items.value = (result.data.items || []).slice();
    revision.value = result.data.revision || 0;
    dirty.value = false;
    await loadSchema();
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
  return left.every((value, index) => (value || '') === (right[index] || ''));
}

function onChange(payload) {
  slots.value = payload.slots;
  items.value = payload.items;
  dirty.value = !sameSlots(slots.value, baseline.value);
}

async function setScope(next) {
  if (next === scope.value) return;
  scope.value = next;
  slots.value = [];
  items.value = [];
  dirty.value = false;
  await load();
}

async function save() {
  if (saving.value || !dirty.value) return;
  saving.value = true;
  error.value = '';
  try {
    const result = await api('/api/inventory/write', {
      method: 'POST',
      body: JSON.stringify({
        source: 'identity',
        data: dataUuid.value,
        scope: scope.value,
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
    baseline.value = slots.value.slice();
    items.value = result.data.items || items.value;
    revision.value = result.data.revision || revision.value;
    dirty.value = false;
    message.value = '已写入身份数据文件，并自动备份写入前版本。';
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    saving.value = false;
  }
}

onMounted(() => {
  token = sessionStorage.getItem('samsara-token') || '';
  const params = new URLSearchParams(location.search);
  dataUuid.value = params.get('data') || '';
  scope.value = params.get('scope') === 'ender' ? 'ender' : 'inventory';
  load();
});
</script>

<style scoped>
.identity-inline-editor { color: var(--fg); }
.identity-inline-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.identity-inline-toolbar .spacer { flex: 1 1 auto; }
.identity-inline-body { max-height: 62vh; overflow: auto; }
</style>
