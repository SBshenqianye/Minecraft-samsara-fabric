<template>
  <div id="server-audit-panel" class="server-panel panel-stack" :hidden="hidden">
    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>Web 审计（全部管理端写请求）</h2>
          <p class="panel-sub">覆盖 samsara 配置、其他模组配置、服务器本体文件与 RCON/命令类写请求：每次管理端写操作前自动快照可回滚的小文件（<code>config/samsara/</code>、服务器配置文件、栈层图标与 pending 队列），并记录发起者。可直接回滚配置类修改；世界数据请使用世界栈时间线或本体备份。</p>
        </div>
        <div class="panel-tools">
          <button id="server-audit-refresh" class="ghost" type="button" :disabled="loading" @click="load(true)">刷新审计</button>
        </div>
      </div>
      <div id="server-audit-message" class="message" :class="messageKind" role="status">{{ message }}</div>
      <div id="server-audit-list" class="server-audit-list">
        <div v-for="entry in entries" :key="entry.id" class="server-audit-item">
          <div class="server-audit-main">
            <strong>{{ auditEntryText(entry).title }}</strong>
            <span>{{ auditEntryText(entry).meta }}</span>
          </div>
          <div class="server-audit-actions">
            <button class="ghost" type="button" @click="preview(entry)">预览</button>
            <button v-if="entry.rollbackable" class="ghost danger" type="button" @click="rollback(entry)">回滚</button>
          </div>
        </div>
        <button
          v-if="hasMore"
          class="ghost server-audit-more"
          type="button"
          :disabled="loading"
          @click="load(false)"
        >{{ loading ? '加载中…' : '加载更早的审计记录' }}</button>
        <p v-if="!entries.length && !loading" class="muted small">
          还没有审计记录。保存配置、权限、身份组、输出模板或服务器配置后会出现。
        </p>
      </div>
    </section>

    <dialog v-if="diff" ref="diffRef" id="server-audit-diff-wrap" class="framework-dialog server-audit-diff-wrap"
        @close="diff = null">
      <div class="dialog-head">
        <h3 id="server-audit-diff-title">差异 · {{ diff.id }} · {{ diff.changedCount }} 个文件</h3>
        <button id="server-audit-diff-close" class="ghost" type="button" @click="closeDiff">关闭</button>
      </div>
      <div id="server-audit-changes" class="server-audit-changes">
        <button
          v-for="(change, index) in changedChanges"
          :key="change.path"
          class="server-audit-change"
          :class="change.status"
          type="button"
          title="跳到该文件差异"
          @click="jumpTo(index)"
        >{{ diffSign(change.status) + change.path }}</button>
        <span v-if="!changedChanges.length" class="muted small">
          当前文件与这次快照完全一致（没有差异）。
        </span>
      </div>
      <pre id="server-audit-diff" class="server-audit-diff" :hidden="diff.diffs.length > 0">{{ diff.text || '（没有可显示的文本差异）' }}</pre>
      <div v-if="diff.diffs.length" id="server-audit-diffs" class="server-audit-diffs">
        <details
          v-for="(item, index) in diff.diffs"
          :id="'audit-diff-' + index"
          :key="item.path + index"
          class="server-audit-file"
          :open="index === 0"
        >
          <summary>{{ diffSign(item.status) + item.path }}</summary>
          <pre v-if="diffLines(item.diff).length">
            <span
              v-for="(line, lineIndex) in diffLines(item.diff)"
              :key="lineIndex"
              :class="diffLineClass(line)"
            >{{ line }}</span>
          </pre>
          <div v-else class="server-audit-file-empty">没有可显示的文本差异。</div>
        </details>
      </div>
    </dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useApi } from '../../composables/useApi.js';
import {
  auditEntryText,
  diffLineClass,
  diffSign,
  formatBytes,
} from './model.js';

const props = defineProps({
  hidden: { type: Boolean, default: false },
});
const emit = defineEmits(['reloaded']);

const { request } = useApi();
const entries = ref([]);
const offset = ref(0);
const hasMore = ref(false);
const total = ref(0);
const loading = ref(false);
const message = ref('');
const messageKind = ref('');
const diff = ref(null);
const diffRef = ref(null);

const changedChanges = computed(() => (diff.value?.changes || [])
  .filter((item) => item.status !== 'unchanged'));

function setMessage(text, kind = '') {
  message.value = text || '';
  messageKind.value = kind;
}

async function load(reset = true) {
  if (loading.value) return;
  loading.value = true;
  if (reset) {
    offset.value = 0;
    entries.value = [];
  }
  try {
    const result = await request('/api/audit?offset=' + offset.value + '&limit=30');
    if (!result.ok) throw new Error(result.data?.error || '读取审计失败');
    const page = Array.isArray(result.data?.entries) ? result.data.entries : [];
    entries.value = reset ? page : entries.value.concat(page);
    offset.value += page.length;
    total.value = Number(result.data?.total || 0);
    hasMore.value = result.data?.total != null
      ? offset.value < total.value : page.length >= 30;
    setMessage('已加载 ' + entries.value.length
      + (total.value ? ' / ' + total.value : '')
      + ' 条审计记录' + (hasMore.value ? '；可继续加载更早记录。' : '；写请求前自动快照。'), 'ok');
  } catch (error) {
    setMessage(error.message || '读取审计失败', 'error');
  } finally {
    loading.value = false;
  }
}

async function preview(entry) {
  try {
    const result = await request('/api/audit/preview', {
      method: 'POST',
      body: JSON.stringify({ id: entry.id }),
    });
    if (!result.ok || result.data?.ok === false) {
      setMessage(result.data?.error || '预览失败', 'error');
      return;
    }
    const data = result.data || {};
    const previewEntry = data.entry || entry;
    const changes = Array.isArray(data.changes) ? data.changes : [];
    const changedCount = Number(data.changedCount || 0);
    setMessage('快照 ' + previewEntry.id + '：' + (previewEntry.fileCount || 0) + ' 文件 / '
      + formatBytes(previewEntry.bytes) + '；差异 ' + changedCount + ' 个文件；'
      + (previewEntry.note || ''), 'ok');
    diff.value = {
      id: previewEntry.id,
      changedCount,
      changes,
      diffs: Array.isArray(data.diffs) ? data.diffs : [],
      text: data.diff || '',
    };
    await nextTick();
    const dialog = diffRef.value;
    if (dialog && !dialog.open && typeof dialog.showModal === 'function') dialog.showModal();
  } catch (error) {
    setMessage(error.message || '预览失败', 'error');
  }
}

function diffLines(text) {
  return String(text || '').split('\n').filter((line) => line !== '');
}

function jumpTo(index) {
  const target = document.getElementById('audit-diff-' + index);
  if (!target) return;
  target.open = true;
  target.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

function closeDiff() {
  const dialog = diffRef.value;
  if (dialog?.open) dialog.close();
  diff.value = null;
}

async function rollback(entry) {
  if (!window.confirm('回滚到这次 Web 修改之前？\n' + entry.action
      + '\n时间 ' + new Date(Number(entry.createdAt || 0)).toLocaleString()
      + '\n\n会先保存当前状态，再恢复快照文件。')) {
    return;
  }
  try {
    const result = await request('/api/audit/rollback', {
      method: 'POST',
      body: JSON.stringify({ id: entry.id, confirm: true }),
    });
    if (!result.ok || result.data?.ok === false) {
      setMessage(result.data?.error || '回滚失败', 'error');
      return;
    }
    setMessage(result.data?.message || '已回滚', 'ok');
    emit('reloaded');
    await load(true);
  } catch (error) {
    setMessage(error.message || '回滚失败', 'error');
  }
}

watch(() => props.hidden, (hidden) => {
  if (!hidden && !entries.value.length && !loading.value) load(true);
}, { immediate: true });

// token 在页面挂载后才连接时补一次读取。
function onConnected() {
  if (!props.hidden) load(true);
}

onMounted(() => window.addEventListener('samsara:connected', onConnected));
onBeforeUnmount(() => window.removeEventListener('samsara:connected', onConnected));

defineExpose({ load });
</script>

<style scoped>
.server-audit-diff-wrap { width: min(920px, 94vw); max-height: 86vh; border: 1px solid var(--line); border-radius: 14px; background: var(--panel); color: var(--fg); padding: 14px; }
.server-audit-diff-wrap::backdrop { background: rgba(3, 6, 10, .62); }
.server-audit-changes { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0; }
.server-audit-changes .server-audit-change { font-family: var(--mono); font-size: 11px; }
.server-audit-diff { max-height: 52vh; overflow: auto; margin: 8px 0; padding: 8px 10px; border-radius: 8px; background: var(--panel-soft); }
.server-audit-diffs { max-height: 56vh; overflow: auto; }
.server-audit-file pre { display: flex; flex-direction: column; margin: 6px 0; padding: 8px 10px; border-radius: 8px; background: var(--panel-soft); font: 12px/1.5 var(--mono); }
</style>
