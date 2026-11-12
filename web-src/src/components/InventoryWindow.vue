<template>
  <div class="inventory-window" :class="'inventory-window-' + kind">
    <div class="iw-bar">
      <span class="mode-dot live"></span>
      <strong>实时</strong>
      <span
        class="mode-flag"
        title="每次操作立即写入；未加载区块或离线玩家会排队，加载/登录后自动应用。"
        aria-label="写入说明"
      >!</span>
      <span class="muted small">{{ filledCount }} 件 · revision {{ revisionText }}</span>
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
      <button
        v-if="mode === 'preset'"
        class="primary tiny"
        type="button"
        :disabled="!dirty || saving"
        @click="confirmSave"
      >保存修改{{ dirty ? ' *' : '' }}</button>
      <span v-if="saving" class="muted small">写入中…</span>
      <button
        class="icon-button"
        type="button"
        :disabled="loading"
        title="重新读取"
        aria-label="重新读取"
        @click="load"
      >↻</button>
    </div>

    <p v-if="error" class="container-message error">{{ error }}</p>
    <p v-else-if="message" class="container-message ok">{{ message }}</p>
    <p v-if="loading" class="muted small">读取中…</p>

    <ItemWorkbench
      v-if="ready"
      :slots="slots"
      :items="items"
      :layout="layout"
      :schema="schema"
      mode="live"
      :item-endpoint="itemEndpoint"
      :revision="revision"
      :disabled="saving"
      :trash="false"
      :doll="kind === 'player'"
      @change="onWorkbenchChange"
    >
      <template #doll>
        <PlayerModel3D
          v-if="kind === 'player'"
          :uuid="target?.uuid || ''"
          :name="target?.name || ''"
          :online="target?.online === true"
          :armor="armorMap"
        />
      </template>
    </ItemWorkbench>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import ItemWorkbench from './ItemWorkbench.vue';
import PlayerModel3D from './PlayerModel3D.vue';
import { useLiveRefresh } from '../composables/useLiveRefresh.js';
import { itemCursor } from '../composables/itemCursor.js';

const props = defineProps({
  kind: { type: String, default: 'container' },
  target: { type: Object, default: () => ({}) },
});

const INV_SIZE = 41;
const ENDER_SIZE = 27;

const token = ref(sessionStorage.getItem('samsara-token') || '');
const mode = ref('simulate');
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const message = ref('');
const slots = ref([]);
const baselineSlots = ref([]);
const items = ref([]);
const revision = ref(0);
const invRevision = ref(0);
const enderRevision = ref(0);
const dirty = ref(false);
const schema = ref({});
let liveTimer = null;
let saveQueued = false;

const modeOptions = [
  { id: 'live', label: '实时', hint: '每次操作立即写入；未加载区块或离线玩家会排队。' },
  { id: 'preset', label: '预设', hint: '本地编辑，点保存一次性提交。' },
  { id: 'simulate', label: '模拟', hint: '只在本地演练，不会写入服务器。' },
];

const tokenKey = computed(() => props.kind === 'container'
  ? 'container:' + [props.target?.dimension, props.target?.x, props.target?.y, props.target?.z].join(',')
  : 'player:' + (props.target?.uuid || ''));
const cursorPending = computed(() => !!itemCursor.item);
const ready = computed(() => token.value && !loading.value && !error.value);
const itemEndpoint = computed(() => props.kind === 'container'
  ? '/api/containers/item' : '/api/inventory/item');
const filledCount = computed(() => (slots.value || []).filter((value) => value).length);
const revisionText = computed(() => props.kind === 'container'
  ? String(revision.value)
  : invRevision.value + ' / ' + enderRevision.value);
const armorMap = computed(() => {
  const map = {};
  for (const row of items.value || []) {
    const slot = Number(row.slot);
    if (slot === 39) map.helmet = row;
    else if (slot === 38) map.chestplate = row;
    else if (slot === 37) map.leggings = row;
    else if (slot === 36) map.boots = row;
  }
  return map;
});
const containerSize = computed(() => Number(props.target?.size) || 0);

const layout = computed(() => {
  if (props.kind === 'container') {
    const size = Math.max(1, containerSize.value || slots.value.length || 1);
    return [{
      label: '容器槽位 0–' + (size - 1),
      start: 0,
      size,
      columns: size <= 3 ? size : 9,
    }];
  }
  return [
    { label: '盔甲 36–39', start: 36, size: 4, columns: 1, row: 'gear', zone: 'gear' },
    { label: '副手 40', start: 40, size: 1, columns: 1, row: 'gear', zone: 'gear' },
    { label: '主背包 9–35', start: 9, size: 27, columns: 9, row: 'main', zone: 'main' },
    { label: '快捷栏 0–8', start: 0, size: 9, columns: 9, row: 'hotbar', zone: 'hotbar', gap: true },
    { label: '末影箱 0–26', start: INV_SIZE, size: ENDER_SIZE, columns: 9, row: 'ender', zone: 'ender', gap: true },
  ];
});

const liveState = useLiveRefresh({
  mode: () => 'live',
  key: () => tokenKey.value,
  canRefresh: () => !!token.value && !!slots.value.length && !dirty.value
    && !saving.value && !loading.value && !cursorPending.value,
  fetchSnapshot: async (signal) => {
    if (props.kind === 'container') {
      const result = await api(readPath(), { signal });
      return result.status === 200 && result.data.ok ? result.data : null;
    }
    const [inv, end] = await Promise.all([
      api(inventoryPath('inventory'), { signal }),
      api(inventoryPath('ender'), { signal }),
    ]);
    if (inv.status !== 200 || !inv.data.ok || end.status !== 200 || !end.data.ok) return null;
    return { inv: inv.data, end: end.data };
  },
  applySnapshot: (snapshot) => {
    if (dirty.value || saving.value || cursorPending.value) return false;
    const incoming = props.kind === 'container'
      ? (snapshot.container?.slots || [])
      : combineSlots(snapshot.inv?.slots, snapshot.end?.slots);
    if (sameSlots(incoming, baselineSlots.value)) return false;
    if (props.kind === 'container') applyContainerData(snapshot);
    else applyPlayerData(snapshot.inv, snapshot.end);
    if (!message.value) message.value = '检测到外部改动，已同步最新内容。';
    return true;
  },
});

onMounted(async () => {
  await loadSchema();
  await load();
});

onBeforeUnmount(() => {
  window.clearTimeout(liveTimer);
});

watch(() => props.kind === 'player'
  ? String(props.target?.uuid || '')
  : [props.target?.dimension, props.target?.x, props.target?.y, props.target?.z].join(','), () => {
  load();
});

function readPath() {
  const row = props.target || {};
  const params = new URLSearchParams({
    dimension: String(row.dimension || ''),
    x: String(row.x ?? 0),
    y: String(row.y ?? 0),
    z: String(row.z ?? 0),
  });
  return '/api/containers/read?' + params.toString();
}

function inventoryPath(scope) {
  const params = new URLSearchParams({
    player: String(props.target?.uuid || ''),
    scope,
  });
  return '/api/inventory?' + params.toString();
}

async function api(path, options = {}) {
  const headers = Object.assign({ Authorization: 'Bearer ' + token.value }, options.headers || {});
  if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, Object.assign({}, options, { headers }));
  const text = await response.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch { data = { error: text }; }
  if (response.status === 401) throw new Error('token 无效或已失效');
  return { status: response.status, data };
}

async function loadSchema() {
  if (Object.keys(schema.value).length) return;
  try {
    const result = await api('/api/items/schema');
    if (result.status === 200 && result.data.ok) schema.value = result.data.schema || {};
  } catch {
    // 组件编辑降级为数量/拖拽。
  }
}

async function load() {
  loading.value = true;
  error.value = '';
  message.value = '';
  try {
    if (props.kind === 'container') {
      const result = await api(readPath());
      if (result.status !== 200 || !result.data.ok) {
        throw new Error(result.data.error || '读取容器失败');
      }
      applyContainerData(result.data);
    } else {
      const [inv, end] = await Promise.all([
        api(inventoryPath('inventory')),
        api(inventoryPath('ender')),
      ]);
      if (inv.status !== 200 || !inv.data.ok) {
        throw new Error(inv.data.error || '读取玩家背包失败');
      }
      if (end.status !== 200 || !end.data.ok) {
        throw new Error(end.data.error || '读取末影箱失败');
      }
      applyPlayerData(inv.data, end.data);
      if (inv.data.pendingWrite || end.data.pendingWrite) {
        message.value = '玩家不在线，当前显示安全队列中的内容。';
      }
    }
  } catch (e) {
    error.value = e.message || String(e);
    slots.value = [];
    items.value = [];
  } finally {
    loading.value = false;
  }
}

function applyContainerData(payload) {
  const row = payload.container || {};
  const incoming = (row.slots || []).slice();
  slots.value = incoming;
  baselineSlots.value = incoming.slice();
  items.value = (payload.items || []).slice();
  revision.value = Number(row.revision || payload.revision || 0);
  dirty.value = false;
}

function combineSlots(invSlots, endSlots) {
  const inv = (invSlots || []).slice(0, INV_SIZE);
  const end = (endSlots || []).slice(0, ENDER_SIZE);
  while (inv.length < INV_SIZE) inv.push('');
  while (end.length < ENDER_SIZE) end.push('');
  return inv.concat(end);
}

function applyPlayerData(inv, end) {
  const incoming = combineSlots(inv?.slots, end?.slots);
  slots.value = incoming;
  baselineSlots.value = incoming.slice();
  const invItems = (inv?.items || []).map((row) => Object.assign({}, row));
  const endItems = (end?.items || []).map((row) =>
    Object.assign({}, row, { slot: Number(row.slot) + INV_SIZE }));
  items.value = invItems.concat(endItems);
  invRevision.value = Number(inv?.revision || 0);
  enderRevision.value = Number(end?.revision || 0);
  revision.value = invRevision.value;
  dirty.value = false;
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
    window.clearTimeout(liveTimer);
    return;
  }
  if (mode.value !== 'live') return;
  window.clearTimeout(liveTimer);
  liveTimer = window.setTimeout(() => save(true), 420);
}

async function setMode(next) {
  if (next === mode.value) return;
  if (next === 'live' && dirty.value) {
    try {
      await ElMessageBox.confirm(
        '切换到实时模式会立即把当前修改写入目标；未加载目标会排队。继续？',
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
  try {
    await ElMessageBox.confirm(
      '将把当前修改写入目标；写入前会保存 Web 审计快照。',
      '确认写入',
      { confirmButtonText: '确认写入', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  await save(false);
}

async function writePlayerScope(scope, scopeSlots, scopeRevision) {
  return api('/api/inventory/write', {
    method: 'POST',
    body: JSON.stringify({
      player: props.target?.uuid,
      scope,
      revision: scopeRevision,
      slots: scopeSlots,
    }),
  });
}

async function save(silent) {
  if (saving.value) {
    saveQueued = true;
    return;
  }
  if (!slots.value.length) return;
  saving.value = true;
  saveQueued = false;
  error.value = '';
  try {
    if (props.kind === 'container') {
      const row = props.target || {};
      const result = await api('/api/containers/write', {
        method: 'POST',
        body: JSON.stringify({
          dimension: row.dimension,
          x: row.x,
          y: row.y,
          z: row.z,
          revision: revision.value,
          slots: slots.value,
        }),
      });
      if (result.status === 409) {
        await load();
        throw new Error('容器已被其他操作修改，已重新读取。');
      }
      if ((result.status !== 200 && result.status !== 202) || !result.data.ok) {
        throw new Error(result.data.error || '写入失败');
      }
      const incoming = (result.data.container?.slots || slots.value).slice();
      slots.value = incoming;
      baselineSlots.value = incoming.slice();
      items.value = result.data.items || items.value;
      revision.value = Number(result.data.container?.revision || revision.value);
      message.value = result.data.queued ? '目标区块未加载，已排队。' : '已写入容器。';
    } else {
      const invSlots = slots.value.slice(0, INV_SIZE);
      const endSlots = slots.value.slice(INV_SIZE, INV_SIZE + ENDER_SIZE);
      const baseline = baselineSlots.value;
      const changedInv = !sameSlots(invSlots, baseline.slice(0, INV_SIZE));
      const changedEnd = !sameSlots(endSlots, baseline.slice(INV_SIZE, INV_SIZE + ENDER_SIZE));
      if (!changedInv && !changedEnd) {
        dirty.value = false;
        return;
      }
      const queued = [];
      if (changedInv) {
        const result = await writePlayerScope('inventory', invSlots, invRevision.value);
        if (result.status === 409) {
          await load();
          throw new Error('玩家背包已被其他操作修改，已重新读取。');
        }
        if ((result.status !== 200 && result.status !== 202) || !result.data.ok) {
          throw new Error(result.data.error || '写入玩家背包失败');
        }
        invRevision.value = Number(result.data.revision || invRevision.value);
        if (result.data.queued) queued.push('背包');
      }
      if (changedEnd) {
        const result = await writePlayerScope('ender', endSlots, enderRevision.value);
        if (result.status === 409) {
          await load();
          throw new Error('末影箱已被其他操作修改，已重新读取。');
        }
        if ((result.status !== 200 && result.status !== 202) || !result.data.ok) {
          throw new Error(result.data.error || '写入末影箱失败');
        }
        enderRevision.value = Number(result.data.revision || enderRevision.value);
        if (result.data.queued) queued.push('末影箱');
      }
      baselineSlots.value = slots.value.slice();
      message.value = queued.length
        ? '玩家不在线，已写入安全队列（' + queued.join('、') + '）。'
        : '已写入玩家背包与末影箱。';
    }
    dirty.value = false;
    if (!silent) ElMessage.success(message.value);
  } catch (e) {
    error.value = e.message || String(e);
    if (!silent) ElMessage.error(error.value);
  } finally {
    saving.value = false;
    liveState.invalidate();
    if (saveQueued) {
      saveQueued = false;
      save(silent);
    }
  }
}
</script>

<style scoped>
.inventory-window {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.iw-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  padding-bottom: 4px;
}

.mode-switch {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-soft);
}

.mode-switch button {
  padding: 3px 9px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
  cursor: pointer;
}

.mode-switch button.active {
  background: var(--accent);
  color: #fff;
}

.primary.tiny {
  padding: 3px 9px;
  border: 1px solid var(--accent);
  border-radius: 7px;
  background: var(--accent);
  color: #fff;
  font-size: 11px;
  cursor: pointer;
}

.primary.tiny:disabled {
  opacity: .45;
  cursor: default;
}

.mode-flag {
  display: inline-grid;
  place-items: center;
  width: 14px;
  height: 14px;
  border: 1px solid var(--line-strong);
  border-radius: 50%;
  color: var(--muted);
  font-size: 9px;
  font-weight: 700;
  cursor: help;
}

.icon-button {
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--panel-soft);
  color: var(--muted);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
</style>
