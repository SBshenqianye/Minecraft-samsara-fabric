<template>
  <div class="global-inventory">
    <button
      v-show="!open"
      class="global-inventory-tab"
      type="button"
      :class="{ active: open }"
      :aria-expanded="open ? 'true' : 'false'"
      title="Web 背包 + 创造物品库（E）"
      @click="openWindow"
    >
      <span>背包</span>
      <span class="global-inventory-tab-key">E</span>
    </button>

    <FloatingWindow
      v-if="open"
      class="global-inventory-panel"
      title="Web 背包"
      subtitle="54 格 · 创造物品库（E）"
      :width="initialWidth"
      :height="initialHeight"
      :x="initialX"
      :y="72"
      :z="1200"
      :pinned="pinned"
      :active="true"
      :can-minimize="false"
      @close="close"
      @dismiss="close"
      @minimize="minimize"
      @pin="pinned = !pinned"
    >
      <div class="global-inventory-status">
        <span v-if="saving" class="muted small">写入中…</span>
        <span v-else-if="dirty" class="muted small">待写入…</span>
      </div>
      <p v-if="error" class="container-message error">{{ error }}</p>
      <p v-if="message" class="container-message ok">{{ message }}</p>
      <div v-if="!connected" class="global-inventory-connect">
        <p class="muted small">需要管理端 token；请先在任意管理页连接。</p>
      </div>
      <div v-else class="global-inventory-body" :class="{ 'has-editor': !!editorItem }">
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
            :editable="true"
            :trash="false"
            :show-toolbar="false"
            @change="onChange"
            @select="onWorkbenchSelect"
          />
          <button
            class="creative-trash"
            type="button"
            title="删除光标上的物品（原版创造模式垃圾桶位置）"
            aria-label="垃圾桶"
            @click="workbench?.trashCursor()"
          >
            <img :src="trashIconSrc" alt="" draggable="false" />
          </button>
        </div>
        <CreativeLibrary class="global-inventory-library" @pick="onPick" />
        <div v-if="editorItem" class="global-inventory-editor">
          <div class="global-inventory-editor-head">
            <strong>组件编辑</strong>
            <span class="spacer"></span>
            <button class="ghost tiny" type="button" title="关闭编辑" @click="closeEditor">×</button>
          </div>
          <ItemEditor
            :item="editorItem"
            :schema="schema"
            @update="applyEditorItem"
          />
        </div>
      </div>
    </FloatingWindow>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue';
import FloatingWindow from './FloatingWindow.vue';
import ItemWorkbench from './ItemWorkbench.vue';
import CreativeLibrary from './CreativeLibrary.vue';
import ItemEditor from './ItemEditor.vue';

const open = ref(false);
const pinned = ref(false);
const trashIconSrc = '/ui/trashcan.png';
const initialWidth = Math.min(1120, Math.max(880, window.innerWidth - 40));
const initialHeight = Math.min(780, Math.max(620, window.innerHeight - 110));
const initialX = Math.max(20, window.innerWidth - initialWidth - 20);
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
const editorItem = ref(null);

function onWorkbenchSelect(item) {
  editorItem.value = item || null;
}

function closeEditor() {
  editorItem.value = null;
  workbench.value?.clearSelection();
}

function applyEditorItem(item) {
  workbench.value?.applyExternalItem(item);
}
let token = '';
let liveTimer = null;

// 参考原版创造模式物品栏：9x5 物品格 + 最下方 9 格快捷栏，共 54 格。
// 垃圾桶放在快捷栏右侧（与创造模式「销毁物品」槽位置一致）。
const layout = [
  { label: '物品 0–44', start: 0, size: 45, columns: 9, row: 'creative', zone: 'creative' },
  { label: '快捷栏 45–53', start: 45, size: 9, columns: 9, row: 'hotbar', zone: 'hotbar', gap: true },
];
onMounted(() => {
  token = sessionStorage.getItem('samsara-token') || '';
  connected.value = !!token;
  window.addEventListener('keydown', onKeydown);
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown);
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
  toggleWindow();
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

function openWindow() {
  open.value = true;
  // 默认未保持：点击窗口外部即收起；点 ⌖ 才保持窗口一直存在。
  pinned.value = false;
  if (!slots.value.length && !loading.value && !error.value) bootstrap();
}

function minimize() {
  open.value = false;
}

function close() {
  pinned.value = false;
  open.value = false;
  window.clearTimeout(liveTimer);
}

function toggleWindow() {
  if (open.value) {
    open.value = false;
    return;
  }
  openWindow();
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

defineExpose({ open: () => openWindow() });
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

.global-inventory-status {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
}

.global-inventory-body {
  display: grid;
  flex: 1;
  min-height: 0;
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

/* Web 背包窗口内不再出现滚动条：缩小槽位，保持整窗可见。 */
.global-inventory-panel :deep(.slot-grid) {
  --slot-size: 40px;
  gap: 4px;
}

.global-inventory-panel :deep(.workbench-grids) {
  padding: 8px;
}

.global-inventory-panel :deep(.workbench-row) {
  gap: 10px;
}

.global-inventory-panel :deep(.workbench-row-gap) {
  margin-top: 10px;
  padding-top: 8px;
}

/* 让窗口内容区成为纵向 flex，背包表格吃掉剩余高度，避免出现外层滚动条 */
.global-inventory-panel :deep(.fw-body) {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 专用编辑面板：独立一列，不遮挡背包 */
.global-inventory-body.has-editor {
  grid-template-columns: minmax(0, 1.15fr) minmax(230px, .8fr) minmax(280px, .95fr);
}

.global-inventory-editor {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  max-height: 100%;
  padding: 8px;
  border: 2px solid #373737;
  border-radius: 4px;
  background: #c6c6c6;
  overflow: auto;
}

.global-inventory-editor-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  color: #2b2b2b;
  font-size: 12px;
}

.global-inventory-editor-head .spacer { flex: 1; }

.global-inventory-editor :deep(.item-editor) { color: #2b2b2b; }

/* 原版创造模式垃圾桶：快捷栏右侧 */
.global-inventory-slots { position: relative; }

.creative-trash {
  position: absolute;
  right: 4px;
  bottom: 4px;
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 2px solid #373737;
  border-radius: 2px;
  background: #8b8b8b url('/ui/slot.png') center / 100% 100% no-repeat;
  cursor: pointer;
}

.creative-trash img {
  width: 28px;
  height: 28px;
  image-rendering: pixelated;
}

.creative-trash:hover { box-shadow: inset 0 0 0 2px #ffffff; }

/* 创造物品库也用原版 GUI 灰底与页签样式（「全部」在第一位） */
.global-inventory-panel :deep(.creative-library) {
  gap: 6px;
  padding: 6px;
  border: 2px solid #373737;
  border-radius: 4px;
  background: #c6c6c6;
}

.global-inventory-panel :deep(.creative-tabs) {
  border: 0;
  border-radius: 0;
  background: #8b8b8b;
  padding: 2px;
  gap: 2px;
}

.global-inventory-panel :deep(.creative-tabs button) {
  border: 1px solid #555;
  border-radius: 2px;
  background: #c6c6c6;
  color: #2b2b2b;
}

.global-inventory-panel :deep(.creative-tabs button.active) {
  background: #ffffff;
  color: #2b2b2b;
  border-color: #ffffff;
}

.global-inventory-panel :deep(.creative-toolbar) { color: #2b2b2b; }

.global-inventory-panel :deep(.creative-grid) {
  border: 2px solid #373737;
  border-radius: 0;
  background: #8b8b8b;
}
</style>
