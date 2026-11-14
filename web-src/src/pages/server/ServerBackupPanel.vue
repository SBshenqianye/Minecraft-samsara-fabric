<template>
  <div id="server-backup-panel" class="server-panel panel-stack" :hidden="hidden">
    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>存档备份</h2>
          <p class="panel-sub">备份 world + 服务器配置 + config/samsara。列表里的“时间线头节点”只是最新一份备份，不是实时世界；当前世界始终在运行中变化，不会出现在备份列表里。每份备份都可以回滚；创建与恢复都在停服后、下次启动前执行，恢复前会自动保留当前 world，失败回滚。</p>
        </div>
        <div class="panel-tools">
          <button id="server-backup-reload" class="ghost" type="button" :disabled="loading" @click="reload">重新读取</button>
          <button id="server-backup-create" class="primary" type="button" :disabled="busy" @click="previewCreate">创建备份</button>
        </div>
      </div>
      <div id="server-backup-message" class="message" :class="messageKind" role="status">{{ message }}</div>
      <div id="server-backup-stats" class="stat-row">
        <div v-for="[label, value] in stats" :key="label" class="stat">
          <span>{{ label }}</span>
          <strong>{{ value }}</strong>
        </div>
      </div>
      <div id="server-backup-pending" class="server-backup-pending" :hidden="!pending">
        <template v-if="pending">
          <strong>{{ pending.mode === 'restore' ? '已排队恢复：' : '已排队创建备份：' }}</strong>
          <span>{{ (pending.id || '') + ' · 请停服并重新启动，任务会在世界打开前执行。' }}</span>
        </template>
      </div>
      <div id="server-backup-preview" class="server-backup-preview" :hidden="!preview">
        <template v-if="preview">
          <div class="world-stack-preview-title">{{ preview.title }}</div>
          <dl class="world-stack-rows">
            <template v-for="[key, value] in preview.rows" :key="key">
              <dt>{{ key }}</dt>
              <dd>{{ value }}</dd>
            </template>
          </dl>
          <div v-if="preview.warnings?.length" class="world-stack-warning">
            <ul class="world-stack-warning-list">
              <li v-for="(line, index) in preview.warnings" :key="'w' + index">{{ line }}</li>
            </ul>
          </div>
          <div v-if="preview.steps?.length" class="world-stack-steps">
            <strong>将执行</strong>
            <ol>
              <li v-for="(step, index) in preview.steps" :key="'s' + index">{{ step }}</li>
            </ol>
          </div>
          <div v-if="preview.errors?.length" class="world-stack-error">
            <ul class="world-stack-error-list">
              <li v-for="(line, index) in preview.errors" :key="'e' + index">{{ line }}</li>
            </ul>
          </div>
          <template v-if="preview.ok">
            <label class="check-line world-stack-confirm">
              <input v-model="preview.confirmed" type="checkbox" />
              <span>{{ preview.confirmText }}</span>
            </label>
            <label v-if="preview.mode === 'create'" class="world-stack-note-field">
              <span>备份说明（可选，显示在时间线上，方便以后辨认）</span>
              <input v-model="preview.note" type="text" maxlength="120"
                  placeholder="例如：更新模组前、开新地图前、修复前" />
            </label>
            <button
              class="primary"
              :class="{ danger: preview.mode === 'restore' }"
              type="button"
              :disabled="!preview.confirmed || busy"
              @click="confirmPreview"
            >{{ preview.mode === 'restore' ? '确认排队恢复（需重启）' : '确认创建（需重启）' }}</button>
          </template>
        </template>
      </div>

      <div id="server-backup-timeline" class="backup-timeline">
        <div
          v-for="node in nodes"
          :key="node.id"
          class="timeline-node"
          :class="{ current: node.current, branch: node.depth > 0, 'branch-start': isBranchStart(node) }"
          :style="{ '--depth': String(node.depth || 0) }"
        >
          <span class="timeline-rail" aria-hidden="true"></span>
          <div class="timeline-card">
            <div class="timeline-card-head">
              <div class="timeline-card-title">
                <strong>{{ formatTime(node.createdAt) }}</strong>
                <span class="muted small mono">{{ node.id }}</span>
              </div>
              <div class="timeline-badges">
                <span v-if="node.current" class="world-stack-badge ok">时间线头节点</span>
                <span v-if="node.restoredAt" class="world-stack-badge done">
                  恢复过 {{ formatTime(node.restoredAt) }}
                </span>
                <span v-if="node.branchId && node.branchId !== 'main'" class="world-stack-badge">
                  分支 {{ node.branchId.replace(/^br-/, '') }}
                </span>
              </div>
            </div>
            <p class="muted small">{{ timelineNodeMeta(node) }}</p>
            <p v-if="node.error" class="message error">警告：{{ node.error }}</p>
            <div class="timeline-actions">
              <button class="primary" type="button" @click="previewRestore(node.id)">回滚到这里</button>
              <button class="ghost" type="button" @click="download(node)">下载</button>
              <button
                class="ghost danger"
                type="button"
                :disabled="node.deletable === false"
                :title="node.deletable === false
                  ? (node.current ? '当前时间线节点不能删除' : '还有后续分支，先删除子节点') : ''"
                @click="removeBackup(node.id)"
              >删除</button>
            </div>
          </div>
        </div>
        <p v-if="!nodes.length" class="muted small">
          还没有本体备份。点右上角“创建备份”，停服重启后生成时间线起点。
        </p>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>任务与回滚</h2>
          <p class="panel-sub">创建/恢复任务报告；恢复前的当前世界会保留在 samsara/server-backups/pre-restore-&lt;job&gt;/world。</p>
        </div>
      </div>
      <div id="server-job-list" class="server-job-list">
        <div v-for="(job, index) in jobs" :key="job.id || index" class="server-job" :class="job.status">
          <div class="server-job-head">
            <strong>{{ (job.type || 'task') + ' · ' + (job.id || '') }}</strong>
            <span class="world-stack-badge" :class="job.status">{{ (job.status || '') + ' · ' + (job.stage || '') }}</span>
          </div>
          <p v-if="job.message" class="muted small">{{ job.message }}</p>
          <div v-if="job.log && job.log.length" class="server-job-log">{{ job.log.join('\n') }}</div>
        </div>
        <p v-if="!jobs.length" class="muted small">还没有任务记录。</p>
      </div>
    </section>

    <section class="panel" id="world-stack-files-panel">
      <div class="panel-head">
        <div>
          <h2>文件位置</h2>
          <p class="panel-sub">动态列出本模组创建/使用的服务器级目录。备份不随存档移动；缓存目录可安全删除；<code>world-stack-backup</code> 只保留最近 N 份（由 <code>backupKeep</code> 控制），<code>world-stack-archive</code> 是用户数据不会自动删除。点击行或 ⧉ 可复制绝对路径。</p>
        </div>
      </div>
      <div id="world-stack-files" class="world-stack-files">
        <div
          v-for="(node, index) in fileRows"
          :key="node.path || index"
          class="world-stack-file"
          :class="{ missing: !node.exists }"
          :style="{ paddingLeft: (Math.max(0, node.depth) * 14 + 4) + 'px' }"
          :title="node.path"
          @click="copyPath(node.path)"
        >
          <span class="world-stack-file-icon">{{ node.exists ? (node.kind === 'dir' ? '▸' : '·') : '×' }}</span>
          <span class="world-stack-file-label">{{ node.label }}</span>
          <span class="world-stack-file-note">{{ node.note || '' }}</span>
          <span class="world-stack-file-size">{{ node.bytes >= 0 ? formatBytes(node.bytes) : '' }}</span>
          <button class="world-stack-file-copy" type="button" title="复制路径"
              @click.stop="copyPath(node.path)">⧉</button>
        </div>
        <p v-if="!fileRows.length" class="muted small">暂无文件位置信息。</p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useApi } from '../../composables/useApi.js';
import {
  backupStats,
  formatBytes,
  formatTime,
  timelineNodeMeta,
} from './model.js';

const props = defineProps({
  hidden: { type: Boolean, default: false },
  model: { type: Object, default: null },
  loading: { type: Boolean, default: false },
});
const emit = defineEmits(['reload']);

const { request, download: downloadFile } = useApi();
const message = ref('');
const messageKind = ref('');
const busy = ref(false);
const preview = ref(null);
const fileRows = ref([]);
let filesLoaded = false;

const stats = computed(() => backupStats(props.model || {}));
const nodes = computed(() => props.model?.timeline?.nodes || []);
const jobs = computed(() => props.model?.jobs || []);
const pending = computed(() => props.model?.pendingRestore || props.model?.pendingBackup || null);

function setMessage(text, kind = '') {
  message.value = text || '';
  messageKind.value = kind;
}

function reload() {
  setMessage('');
  preview.value = null;
  emit('reload');
  loadFiles(true);
}

async function loadFiles(force = false) {
  if (filesLoaded && !force) return;
  try {
    const result = await request('/api/world-stack');
    const files = result.ok && Array.isArray(result.data?.files) ? result.data.files : [];
    fileRows.value = files;
    filesLoaded = true;
  } catch {
    fileRows.value = [];
  }
}

watch(() => props.hidden, (hidden) => {
  if (!hidden) loadFiles();
}, { immediate: true });

// token 在页面挂载后才连接时补一次读取。
function onConnected() {
  loadFiles(true);
}

onMounted(() => window.addEventListener('samsara:connected', onConnected));
onBeforeUnmount(() => window.removeEventListener('samsara:connected', onConnected));

function isBranchStart(node) {
  const list = nodes.value;
  const index = list.indexOf(node);
  if (index <= 0) return false;
  return Boolean(node.branchId) && node.branchId !== (list[index - 1].branchId || '');
}

function toRows(previewData) {
  return [
    ['world', previewData.worldName || '—'],
    ['world 大小', formatBytes(previewData.worldBytes)],
    ['可用空间', formatBytes(previewData.freeBytes)],
    ['需要空间', formatBytes(previewData.neededBytes)],
    ['保留', previewData.keep === 0 ? '不自动清理' : '最新 ' + previewData.keep + ' 份 + 祖先'],
    ['执行时机', '停服后、下次启动前；源 world 不会被删除'],
  ];
}

function restoreRows(previewData) {
  return [
    ['备份大小', formatBytes(previewData.backupBytes)],
    ['备份 world', previewData.worldName || '—'],
    ['当前 world', formatBytes(previewData.worldBytes)],
    ['可用空间', formatBytes(previewData.freeBytes)],
    ['执行时机', '停服后、下次启动前'],
  ];
}

async function previewCreate() {
  busy.value = true;
  setMessage('读取创建预览…');
  try {
    const result = await request('/api/server/backups/preview', {
      method: 'POST',
      body: JSON.stringify({}),
    });
    if (result.ok && result.data?.ok) {
      showCreatePreview(result.data);
      setMessage('');
      return;
    }
    // 空间/目录不满足时后端以 400 返回结构化预览，仍然要展示原因。
    if (result.data && Object.prototype.hasOwnProperty.call(result.data, 'worldBytes')) {
      showCreatePreview(result.data);
      setMessage('');
      return;
    }
    setMessage(result.data?.error || '创建备份预览失败', 'error');
  } catch (error) {
    if (error.data && Object.prototype.hasOwnProperty.call(error.data, 'worldBytes')) {
      showCreatePreview(error.data);
      setMessage('');
    } else {
      setMessage(error.message || '创建备份预览失败', 'error');
    }
  } finally {
    busy.value = false;
  }
}

function showCreatePreview(data) {
  const ok = Boolean(data?.ok);
  preview.value = {
    mode: 'create',
    ok,
    title: ok ? '创建备份预览：可以执行' : '创建备份预览：需要修正',
    rows: toRows(data || {}),
    steps: Array.isArray(data?.steps) ? data.steps : [],
    warnings: [],
    errors: ok ? [] : [data?.error || '预览未通过'],
    confirmed: false,
    note: '',
    confirmText: '我确认：停服并重新启动后打包 world、服务器配置与 config/samsara；源 world 保持不变',
  };
  scrollPreview();
}

async function previewRestore(id) {
  busy.value = true;
  setMessage('读取恢复预览…');
  try {
    const result = await request('/api/server/backups/preview-restore', {
      method: 'POST',
      body: JSON.stringify({ id }),
    });
    showRestorePreview(id, result.data?.preview || {});
    setMessage('');
  } catch (error) {
    if (error.data?.preview) {
      showRestorePreview(id, error.data.preview);
      setMessage('');
    } else {
      setMessage(error.message || '恢复预览失败', 'error');
    }
  } finally {
    busy.value = false;
  }
}

function showRestorePreview(id, data) {
  const ok = Boolean(data?.ok);
  preview.value = {
    mode: 'restore',
    id,
    ok,
    title: (ok ? '恢复预览：' : '恢复预览：需要修正 ') + id,
    rows: restoreRows(data || {}),
    steps: Array.isArray(data?.steps) ? data.steps : [],
    warnings: Array.isArray(data?.warnings) ? data.warnings : [],
    errors: ok ? [] : (Array.isArray(data?.errors) && data.errors.length
      ? data.errors : ['恢复预览未通过']),
    confirmed: false,
    note: '',
    confirmText: '我确认：恢复会替换当前 world；恢复前的世界保留在 pre-restore-<job>/world',
  };
  scrollPreview();
}

function scrollPreview() {
  requestAnimationFrame(() => {
    document.getElementById('server-backup-preview')?.scrollIntoView({ block: 'start' });
  });
}

async function confirmPreview() {
  const data = preview.value;
  if (!data?.confirmed) {
    setMessage('请先勾选确认项', 'warning');
    return;
  }
  busy.value = true;
  setMessage(data.mode === 'restore' ? '正在排队…' : '正在排队…');
  try {
    if (data.mode === 'restore') {
      const result = await request('/api/server/backups/restore', {
        method: 'POST',
        body: JSON.stringify({ id: data.id, confirm: true }),
      });
      if (!result.ok || result.data?.ok === false) {
        throw new Error(result.data?.error || '排队恢复失败');
      }
      setMessage(result.data?.message || '已排队恢复，请停服并重新启动。', 'ok');
    } else {
      const result = await request('/api/server/backups', {
        method: 'POST',
        body: JSON.stringify({ confirm: true, note: String(data.note || '').trim() }),
      });
      if (!result.ok || result.data?.ok === false) {
        throw new Error(result.data?.error || '创建备份失败');
      }
      setMessage(result.data?.message || '已排队，请停服并重新启动。', 'ok');
    }
    preview.value = null;
    emit('reload');
  } catch (error) {
    setMessage(error.message || '操作失败', 'error');
  } finally {
    busy.value = false;
  }
}

async function removeBackup(id) {
  if (!window.confirm('删除备份 ' + id + '？源 world 不会受影响。')) return;
  try {
    const result = await request('/api/server/backups/delete', {
      method: 'POST',
      body: JSON.stringify({ id, confirm: true }),
    });
    if (!result.ok || result.data?.ok === false) {
      throw new Error(result.data?.error || '删除失败');
    }
    setMessage(result.data?.message || '已删除', 'ok');
    emit('reload');
  } catch (error) {
    setMessage(error.message || '删除失败', 'error');
  }
}

async function download(node) {
  if (Number(node.zipBytes) > 256 * 1024 * 1024
      && !window.confirm('备份超过 256MB，浏览器下载会占用大量内存。仍要下载？')) {
    return;
  }
  try {
    await downloadFile('/api/server/backups/download?id=' + encodeURIComponent(node.id), node.id + '.zip');
  } catch (error) {
    setMessage(error.message || '下载失败', 'error');
  }
}

async function copyPath(path) {
  try {
    await navigator.clipboard.writeText(String(path || ''));
    setMessage('已复制路径：' + path, 'ok');
  } catch {
    setMessage('复制失败：浏览器不允许访问剪贴板', 'error');
  }
}
</script>
