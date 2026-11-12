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
      subtitle="68 格虚拟玩家 · 创造物品库（E）"
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
      <div v-else class="global-inventory-body">
        <div class="global-inventory-main">
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
              :editable="true"
              :trash="false"
              :show-toolbar="false"
              @change="onChange"
              @select="onWorkbenchSelect"
            />
            <div class="global-inventory-trash-row">
              <button
                class="creative-trash"
                type="button"
                title="删除光标上的物品"
                aria-label="垃圾桶"
                @click="workbench?.trashCursor()"
              >
                <img :src="trashIconSrc" alt="" draggable="false" />
              </button>
            </div>
          </div>
          <CreativeLibrary class="global-inventory-library" @pick="onPick" />
        </div>
        <div v-if="editorItem" class="global-inventory-editor">
          <div class="global-inventory-editor-head">
            <strong>组件编辑</strong>
            <span class="muted small">选中的槽位物品</span>
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
const WEB_SLOTS = 68; // 41 格玩家背包 + 27 格末影箱
const trashIconSrc = '/ui/trashcan.png';
const initialWidth = Math.min(1120, Math.max(880, window.innerWidth - 40));
const initialHeight = Math.min(940, Math.max(680, window.innerHeight - 60));
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
let localVersion = 0;
let flushPromise = null;

// 与玩家背包同一套相对布局：装备 4 格 + 副手 1 格 + 主背包 27 格
// + 快捷栏 9 格 + 末影箱 27 格，共 68 格，方便以后把 Web 背包当成虚拟玩家。
const ARMOR_HINTS = [
  { icon: '/ui/slot_helmet.png', label: '头盔' },
  { icon: '/ui/slot_chestplate.png', label: '胸甲' },
  { icon: '/ui/slot_leggings.png', label: '护腿' },
  { icon: '/ui/slot_boots.png', label: '靴子' },
];
const layout = [
  { label: '装备 0–3', start: 0, size: 4, columns: 1, row: 'gear', zone: 'gear', hints: ARMOR_HINTS },
  { label: '副手 4', start: 4, size: 1, columns: 1, row: 'gear', zone: 'gear',
    hints: [{ icon: '/ui/slot_shield.png', label: '副手' }] },
  { label: '主背包 5–31', start: 5, size: 27, columns: 9, row: 'main', zone: 'main' },
  { label: '快捷栏 32–40', start: 32, size: 9, columns: 9, row: 'hotbar', zone: 'hotbar', gap: true },
  { label: '末影箱 41–67（27 格）', start: 41, size: 27, columns: 9, row: 'storage', zone: 'storage', gap: true },
];
onMounted(() => {
  token = sessionStorage.getItem('samsara-token') || '';
  connected.value = !!token;
  window.addEventListener('keydown', onKeydown);
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown);
  window.clearTimeout(liveTimer);
  if (dirty.value && !saving.value) save();
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
  liveTimer = null;
  // 关闭会销毁浮窗内的组件，未落库的改动立即补写，避免丢改动。
  if (dirty.value && !saving.value) save();
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
    while (incoming.length < WEB_SLOTS) incoming.push('');
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
  localVersion += 1;
  dirty.value = !sameSlots(payload.slots, baselineSlots.value);
  // Web 背包只和自己交互，没有“和玩家抢东西”的风险，所以一律实时写入。
  window.clearTimeout(liveTimer);
  if (!dirty.value) return;
  // 120ms 足以合并连续点击，又不会让“写入中”明显拖慢操作。
  liveTimer = window.setTimeout(() => {
    liveTimer = null;
    save();
  }, 120);
}

function onPick(row) {
  workbench.value?.pickFromLibrary(row);
}

/**
 * 写入队列：保存期间允许继续操作。每次发送都记录版本号，返回时若本地又有改动，
 * 不拿旧服务器快照覆盖本地（旧实现会因此把刚放进去的物品“吞”掉），熔断式继续写最新状态。
 */
async function save() {
  if (flushPromise) return flushPromise;
  if (!dirty.value) return;
  saving.value = true;
  error.value = '';
  flushPromise = (async () => {
    while (dirty.value) {
      const version = localVersion;
      const snapshot = slots.value.slice();
      const result = await api('/api/inventory/web/write', {
        method: 'POST',
        body: JSON.stringify({ slots: snapshot }),
      });
      if (result.status !== 200 || !result.data.ok) {
        throw new Error(result.data.error || '写入失败');
      }
      const incoming = (result.data.slots || snapshot).slice();
      while (incoming.length < WEB_SLOTS) incoming.push('');
      if (version === localVersion) {
        slots.value = incoming;
        baselineSlots.value = incoming.slice();
        items.value = result.data.items || items.value;
      } else {
        // 本次快照已经落库；本地在请求期间又变了，保留本地并继续写下一版。
        baselineSlots.value = snapshot;
      }
      dirty.value = !sameSlots(slots.value, baselineSlots.value);
      message.value = '已写入 Web 背包。';
    }
  })();
  try {
    await flushPromise;
  } catch (e) {
    error.value = e.message || String(e);
    // 网络/服务端临时失败时保留 dirty，稍后自动重试一次。
    if (!liveTimer) liveTimer = window.setTimeout(() => save(), 1200);
  } finally {
    saving.value = false;
    flushPromise = null;
  }
  return flushPromise;
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
  align-items: center;
  gap: 8px;
  min-height: 18px;
  margin-bottom: 6px;
}

/* 生存物品栏在左、创造物品库在右，两者等高；组件编辑独占下方整行。 */
.global-inventory-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  overflow: auto;
}

.global-inventory-main {
  display: grid;
  flex: 0 0 auto;
  grid-template-columns: minmax(380px, 1.15fr) minmax(320px, 1fr);
  align-items: stretch;
  gap: 10px;
  min-height: 360px;
}

.global-inventory-slots {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.global-inventory-library {
  min-width: 0;
  min-height: 0;
}

.global-inventory-trash-row {
  display: flex;
  justify-content: flex-end;
  padding-top: 6px;
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
  .global-inventory-main {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* 缩小槽位并让格子区按内容收窄，腾出下半屏给创造物品库。 */
.global-inventory-panel :deep(.slot-grid) {
  --slot-size: 32px;
  gap: 3px;
}

.global-inventory-panel :deep(.workbench-grids) {
  width: max-content;
  min-width: 380px;
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

/* 专用编辑面板：放在生存背包和创造物品库下方，占满整行。 */
.global-inventory-editor {
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  width: 100%;
  min-width: 0;
  padding: 8px;
  border: 2px solid #373737;
  border-radius: 4px;
  background: #c6c6c6;
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

.global-inventory-editor :deep(.item-editor) {
  display: block;
  columns: 240px;
  column-gap: 8px;
  max-height: none;
  overflow: visible;
  padding-right: 0;
  color: #2b2b2b;
}

.global-inventory-editor :deep(.item-editor-head),
.global-inventory-editor :deep(.container-message),
.global-inventory-editor :deep(.editor-raw) {
  column-span: all;
}

.global-inventory-editor :deep(.editor-section) {
  break-inside: avoid;
  margin: 0 0 8px;
  padding: 8px;
  border-radius: 8px;
}

.global-inventory-editor :deep(.editor-section h4) {
  margin-bottom: 6px;
}

.global-inventory-editor :deep(.field-row) {
  gap: 6px;
}

.global-inventory-editor :deep(.field-row > .field) {
  flex: 1 1 120px;
}

.global-inventory-editor :deep(.list-editor) {
  gap: 4px;
}

.global-inventory-editor :deep(.list-row) {
  gap: 4px;
}

.global-inventory-editor :deep(.item-editor-head) {
  position: static;
  padding: 2px 0 8px;
  background: transparent;
}

/* 垃圾桶单独一行，不再压在物品栏格子上 */
.creative-trash {
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
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
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
  flex: 1 1 auto;
  border: 2px solid #373737;
  border-radius: 0;
  background: #8b8b8b;
  min-height: 220px;
  max-height: none;
}
</style>

