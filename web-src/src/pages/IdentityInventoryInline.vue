<template>
  <div class="identity-inline-editor">
    <p v-if="error" class="container-message error">{{ error }}</p>
    <p v-if="message" class="container-message ok">{{ message }}</p>
    <div class="identity-inline-toolbar">
      <button class="ghost tiny" type="button" :disabled="saving || loading" title="重新读取" aria-label="重新读取" @click="load">↻</button>
      <button class="primary tiny" type="button" :disabled="(!dirtyInv && !dirtyEnd) || saving" @click="save">保存修改</button>
      <span class="muted small">背包 {{ filledInv }} / 41 · 末影箱 {{ filledEnd }} / 27</span>
      <span class="spacer"></span>
      <span class="muted small">data {{ dataUuid }}</span>
    </div>

    <div v-if="invSlots.length || endSlots.length" class="identity-inline-body">
      <section class="identity-inline-section">
        <h4>背包</h4>
        <ItemWorkbench
          :slots="invSlots"
          :items="invItems"
          :layout="invLayout"
          :schema="schema"
          mode="simulate"
          item-endpoint="/api/inventory/item"
          :revision="invRevision"
          :editable="true"
          :trash="false"
          :show-toolbar="false"
          @change="onInvChange"
        />
      </section>
      <section class="identity-inline-section">
        <h4>末影箱</h4>
        <ItemWorkbench
          :slots="endSlots"
          :items="endItems"
          :layout="endLayout"
          :schema="schema"
          mode="simulate"
          item-endpoint="/api/inventory/item"
          :revision="endRevision"
          :editable="true"
          :trash="false"
          :show-toolbar="false"
          @change="onEndChange"
        />
      </section>
    </div>
    <p v-else-if="loading" class="muted small">读取中…</p>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import ItemWorkbench from '../components/ItemWorkbench.vue';

const dataUuid = ref('');
const invSlots = ref([]);
const endSlots = ref([]);
const invItems = ref([]);
const endItems = ref([]);
const schema = ref({});
const invRevision = ref(0);
const endRevision = ref(0);
const invBaseline = ref([]);
const endBaseline = ref([]);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const message = ref('');
let token = '';

const filledInv = computed(() => invSlots.value.filter(Boolean).length);
const filledEnd = computed(() => endSlots.value.filter(Boolean).length);
const dirtyInv = computed(() => !sameSlots(invSlots.value, invBaseline.value));
const dirtyEnd = computed(() => !sameSlots(endSlots.value, endBaseline.value));

const invLayout = [
  { label: '装备 36–39', start: 36, size: 4, columns: 4, row: 'gear', zone: 'gear' },
  { label: '副手 40', start: 40, size: 1, columns: 1, row: 'gear', zone: 'gear' },
  { label: '主背包 9–35', start: 9, size: 27, columns: 9, row: 'main', zone: 'main' },
  { label: '快捷栏 0–8', start: 0, size: 9, columns: 9, row: 'hotbar', zone: 'hotbar', gap: true },
];
const endLayout = [{ label: '末影箱 0–26', start: 0, size: 27, columns: 9 }];

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

function sameSlots(a, b) {
  const left = a || [];
  const right = b || [];
  if (left.length !== right.length) return false;
  return left.every((value, index) => (value || '') === (right[index] || ''));
}

async function loadSchema() {
  if (Object.keys(schema.value).length) return;
  const result = await api('/api/items/schema');
  if (result.status === 200 && result.data.ok) schema.value = result.data.schema || {};
}

async function loadScope(scope) {
  const params = new URLSearchParams({ source: 'identity', data: dataUuid.value, scope });
  const result = await api('/api/inventory?' + params.toString());
  if (result.status !== 200 || !result.data.ok) {
    throw new Error(result.data.error || ('读取' + (scope === 'ender' ? '末影箱' : '背包') + '失败'));
  }
  return result.data;
}

async function load() {
  if (!dataUuid.value) return;
  loading.value = true;
  error.value = '';
  message.value = '';
  try {
    const [inv, end] = await Promise.all([loadScope('inventory'), loadScope('ender')]);
    invSlots.value = (inv.slots || []).slice();
    endSlots.value = (end.slots || []).slice();
    invBaseline.value = invSlots.value.slice();
    endBaseline.value = endSlots.value.slice();
    invItems.value = inv.items || [];
    endItems.value = end.items || [];
    invRevision.value = inv.revision || 0;
    endRevision.value = end.revision || 0;
    await loadSchema();
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

function onInvChange(payload) {
  invSlots.value = payload.slots;
  invItems.value = payload.items;
}

function onEndChange(payload) {
  endSlots.value = payload.slots;
  endItems.value = payload.items;
}

async function writeScope(scope, slots, revision) {
  const result = await api('/api/inventory/write', {
    method: 'POST',
    body: JSON.stringify({ source: 'identity', data: dataUuid.value, scope, revision, slots }),
  });
  if (result.status === 409) {
    await load();
    throw new Error(scope === 'ender' ? '末影箱 revision 冲突，已重新读取' : '背包 revision 冲突，已重新读取');
  }
  if (result.status !== 200 || !result.data.ok) {
    throw new Error(result.data.error || '写入失败');
  }
  return result.data;
}

async function save() {
  if (saving.value || (!dirtyInv.value && !dirtyEnd.value)) return;
  saving.value = true;
  error.value = '';
  try {
    if (dirtyInv.value) {
      const data = await writeScope('inventory', invSlots.value, invRevision.value);
      invSlots.value = (data.slots || invSlots.value).slice();
      invBaseline.value = invSlots.value.slice();
      invItems.value = data.items || invItems.value;
      invRevision.value = data.revision || invRevision.value;
    }
    if (dirtyEnd.value) {
      const data = await writeScope('ender', endSlots.value, endRevision.value);
      endSlots.value = (data.slots || endSlots.value).slice();
      endBaseline.value = endSlots.value.slice();
      endItems.value = data.items || endItems.value;
      endRevision.value = data.revision || endRevision.value;
    }
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
.identity-inline-body {
  max-height: 68vh;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.identity-inline-section h4 { margin: 0 0 6px; font-size: 13px; }
</style>
