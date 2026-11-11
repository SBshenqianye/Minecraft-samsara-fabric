<template>
  <div class="global-inventory">
    <button
      v-show="!open"
      class="global-inventory-tab"
      type="button"
      :class="{ active: open, pinned }"
      :aria-expanded="open ? 'true' : 'false'"
      title="Web 背包 + 创造物品库（E）"
      @mouseenter="onEnter"
      @click="togglePin"
    >
      <span>背包</span>
      <span class="global-inventory-tab-key">E</span>
    </button>

    <transition name="inventory-slide">
      <section
        v-if="open"
        class="global-inventory-panel"
        aria-label="Web 背包与创造物品库"
        @mouseenter="onEnter"
        @mouseleave="onLeave"
      >
        <header class="global-inventory-head">
          <div>
            <h3>Web 背包</h3>
            <p class="muted small">幽灵管理员的 54 格仓库；每次改动即时写入，和创造物品库一起随时存取。</p>
          </div>
          <span class="spacer"></span>
          <span v-if="saving" class="muted small">写入中…</span>
          <span v-else-if="dirty" class="muted small">待写入…</span>
          <button class="ghost" type="button" title="收起" @click="close">×</button>
        </header>

        <p v-if="error" class="container-message error">{{ error }}</p>
        <p v-if="message" class="container-message ok">{{ message }}</p>

        <div v-if="!connected" class="global-inventory-connect">
          <p class="muted small">需要管理端 token；请先在任意管理页连接。</p>
        </div>

        <div v-else class="global-inventory-body">
          <div class="global-inventory-slots">
            <ItemWorkbench
              ref="workbench"
              :slots="slots"
              :items="items"
              :layout="layout"
              :schema="schema"
              mode="live"
              item-endpoint="/api/inventory/item"
              :revision="revision"
              :disabled="saving"
              :show-library="false"
              :web-deposit="false"
              :web-withdraw="false"
              @change="onChange"
            />
          </div>
          <CreativeLibrary class="global-inventory-library" @pick="onPick" />
        </div>
      </section>
    </transition>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue';
import ItemWorkbench from './ItemWorkbench.vue';
import CreativeLibrary from './CreativeLibrary.vue';

const open = ref(false);
const pinned = ref(false);
const loading = ref(false);
const saving = ref(false);
const connected = ref(false);
const error = ref('');
const message = ref('');
const slots = ref([]);
const baselineSlots = ref([]);
const items = ref([]);
const revision = ref(0);
const dirty = ref(false);
const schema = ref({});
const workbench = ref(null);
let token = '';
let closeTimer = null;
let liveTimer = null;

const layout = [
  { label: '装备 0–3', start: 0, size: 4, columns: 1, row: 'gear', zone: 'gear' },
  { label: '副手 4', start: 4, size: 1, columns: 1, row: 'gear', zone: 'gear' },
  { label: '主背包 5–31', start: 5, size: 27, columns: 9, row: 'main', zone: 'main' },
  { label: '快捷栏 32–40', start: 32, size: 9, columns: 9, row: 'hotbar', zone: 'hotbar', gap: true },
  { label: '仓库 41–53', start: 41, size: 13, columns: 9, row: 'storage', zone: 'storage', gap: true },
];
onMounted(() => {
  token = sessionStorage.getItem('samsara-token') || '';
  connected.value = !!token;
  window.addEventListener('keydown', onKeydown);
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown);
  window.clearTimeout(closeTimer);
  window.clearTimeout(liveTimer);
});

function onKeydown(event) {
  if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
  if (event.key.toLowerCase() !== 'e') return;
  const target = event.target;
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA'
      || target.tagName === 'SELECT' || target.isContentEditable)) {
    return;
  }
  // 大弹窗/确认框真正可见时不抢 E（Element Plus 关闭的弹层仍留在 DOM 里）。
  if (dialogBlocking()) return;
  event.preventDefault();
  togglePin();
}

function dialogBlocking() {
  if (document.querySelector('dialog[open]')) return true;
  return [...document.querySelectorAll('.el-overlay')].some((node) => {
    if (node.getAttribute('aria-hidden') === 'true') return false;
    if (typeof node.checkVisibility === 'function') {
      return node.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true });
    }
    const style = window.getComputedStyle(node);
    return style.display !== 'none' && style.visibility !== 'hidden';
  });
}

function onEnter() {
  window.clearTimeout(closeTimer);
  open.value = true;
  if (!slots.value.length && !loading.value && !error.value) bootstrap();
}

function onLeave() {
  if (pinned.value) return;
  window.clearTimeout(closeTimer);
  closeTimer = window.setTimeout(() => {
    if (!pinned.value) open.value = false;
  }, 260);
}

function togglePin() {
  window.clearTimeout(closeTimer);
  if (open.value && pinned.value) {
    pinned.value = false;
    open.value = false;
    return;
  }
  pinned.value = true;
  open.value = true;
  if (!slots.value.length && !loading.value) bootstrap();
}

function close() {
  pinned.value = false;
  open.value = false;
  window.clearTimeout(liveTimer);
}

async function bootstrap() {
  token = sessionStorage.getItem('samsara-token') || '';
  connected.value = !!token;
  if (!token) return;
  loadSchema();
  await load();
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
  if (response.status === 401) {
    connected.value = false;
    throw new Error('token 无效或已失效');
  }
  return { status: response.status, data };
}

async function loadSchema() {
  if (Object.keys(schema.value).length) return;
  try {
    const result = await api('/api/items/schema');
    if (result.status === 200 && result.data.ok) schema.value = result.data.schema || {};
  } catch {
    // 组件编辑降级。
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

async function load() {
  if (!token) return;
  loading.value = true;
  error.value = '';
  try {
    const result = await api('/api/inventory/web');
    if (result.status !== 200 || !result.data.ok) throw new Error(result.data.error || '读取 Web 背包失败');
    const incoming = (result.data.slots || []).slice();
    while (incoming.length < 54) incoming.push('');
    slots.value = incoming;
    baselineSlots.value = incoming.slice();
    items.value = result.data.items || [];
    revision.value = Date.now();
    dirty.value = false;
    connected.value = true;
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

function onChange(payload) {
  slots.value = payload.slots;
  items.value = payload.items;
  dirty.value = !sameSlots(payload.slots, baselineSlots.value);
  // Web 背包只和自己交互，没有“和玩家抢东西”的风险，所以一律实时写入。
  if (!dirty.value) {
    window.clearTimeout(liveTimer);
    return;
  }
  if (payload.pending) {
    message.value = '光标上还有物品，放下后才会写入。';
    return;
  }
  window.clearTimeout(liveTimer);
  liveTimer = window.setTimeout(() => save(), 450);
}

function onPick(row) {
  workbench.value?.pickFromLibrary(row);
}

async function save() {
  if (saving.value) return;
  saving.value = true;
  error.value = '';
  try {
    const result = await api('/api/inventory/web/write', {
      method: 'POST',
      body: JSON.stringify({ slots: slots.value }),
    });
    if (result.status !== 200 || !result.data.ok) throw new Error(result.data.error || '写入失败');
    const incoming = (result.data.slots || slots.value).slice();
    while (incoming.length < 54) incoming.push('');
    slots.value = incoming;
    baselineSlots.value = incoming.slice();
    items.value = result.data.items || items.value;
    dirty.value = false;
    message.value = '已写入 Web 背包。';
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    saving.value = false;
  }
}

defineExpose({ open: () => togglePin() });
</script>

<style scoped>
.global-inventory-tab {
  position: fixed;
  right: 0;
  top: 46%;
  z-index: 60;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 12px 6px;
  border: 1px solid var(--line);
  border-right: 0;
  border-radius: 10px 0 0 10px;
  background: var(--panel);
  color: var(--muted);
  font-size: 12px;
  writing-mode: vertical-rl;
  cursor: pointer;
  transition: background .15s ease, color .15s ease;
}

.global-inventory-tab:hover,
.global-inventory-tab.active {
  background: var(--accent);
  color: #fff;
}

.global-inventory-tab-key {
  padding: 2px 4px;
  border: 1px solid currentColor;
  border-radius: 4px;
  font-size: 10px;
  line-height: 1;
  writing-mode: horizontal-tb;
}

.global-inventory-panel {
  position: fixed;
  right: 0;
  top: 96px;
  bottom: 24px;
  z-index: 59;
  width: min(1080px, 92vw);
  padding: 14px;
  overflow: auto;
  border: 1px solid var(--line);
  border-right: 0;
  border-radius: 14px 0 0 14px;
  background: var(--panel);
  box-shadow: -12px 0 36px rgba(0, 0, 0, .35);
}

.global-inventory-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.global-inventory-head h3 {
  margin: 0 0 2px;
}

.global-inventory-head .spacer {
  flex: 1 1 auto;
}

.global-inventory-body {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(300px, 1fr);
  gap: 12px;
  align-items: start;
}

.global-inventory-slots {
  min-width: 0;
}

.global-inventory-library {
  min-width: 0;
}

.inventory-slide-enter-active,
.inventory-slide-leave-active {
  transition: transform .18s ease, opacity .18s ease;
}

.inventory-slide-enter-from,
.inventory-slide-leave-to {
  transform: translateX(24px);
  opacity: 0;
}

@media (max-width: 900px) {
  .global-inventory-body {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
