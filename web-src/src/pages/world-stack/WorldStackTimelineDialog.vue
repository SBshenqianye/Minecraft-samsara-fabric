<template>
  <dialog
    ref="dialogEl"
    id="world-stack-timeline-dialog"
    class="app-dialog world-stack-dialog"
    @close="close"
  >
    <div class="dialog-body">
      <div class="dialog-head">
        <div>
          <h3>操作时间线</h3>
          <p class="dialog-sub">按执行顺序排列；完成后才会生成可撤销的时间线节点。时间线线性、无法分支；末端节点才可撤销。撤销在下次启动前执行，先备份当前状态，失败回滚；源世界、归档与用户备份不会自动删除。</p>
        </div>
        <div class="dialog-head-actions">
          <button id="world-stack-snapshot-refresh" class="ghost" type="button" @click="loadSnapshots">刷新快照占用</button>
          <button class="ghost dialog-close" type="button" title="关闭" aria-label="关闭" @click="close">×</button>
        </div>
      </div>
      <div v-if="error" id="world-stack-timeline-error" class="world-stack-error" role="alert">{{ error }}</div>
      <div id="world-stack-timeline" class="backup-timeline">
        <template v-if="nodes.length">
          <p class="muted small">已完成操作（仅末端节点可撤销）</p>
          <div
            v-for="node in nodes"
            :key="node.id"
            class="timeline-node"
            :class="{ current: node.current, branch: node.depth > 0 }"
            :style="{ '--depth': String(node.depth || 0) }"
            :data-id="node.id || ''"
            :data-branch-id="node.branchId || ''"
            :data-operation="node.operation || ''"
            :data-restorable="String(Boolean(node.restorable))"
            :data-legacy="String(Boolean(node.legacy))"
            :data-depth="String(node.depth || 0)"
          >
            <span class="timeline-rail" aria-hidden="true"></span>
            <div class="timeline-card">
              <div class="timeline-card-head">
                <div class="timeline-card-title">
                  <strong>{{ operationName(node.operation) }} · {{ fmtTime(node.createdAt) }}</strong>
                  <span class="muted small mono">{{ node.id }}</span>
                </div>
                <div class="timeline-badges">
                  <span class="world-stack-badge">{{ operationName(node.operation) }}</span>
                  <span v-if="node.current" class="world-stack-badge ok">当前时间线</span>
                  <span v-if="node.restoredAt" class="world-stack-badge done">已撤销 {{ fmtTime(node.restoredAt) }}</span>
                  <span v-if="node.legacy" class="world-stack-badge warn">旧备份 · 只读</span>
                  <span v-else-if="node.restorable" class="world-stack-badge ok">可撤销</span>
                  <span v-else class="world-stack-badge">不可撤销</span>
                </div>
              </div>
              <p class="muted small">
                job={{ node.jobId || '—' }}
                <template v-if="node.affectedIds?.length"> · 层 {{ node.affectedIds.join(' / ') }}</template>
                <template v-if="node.parentId"> · 上一节点 {{ node.parentId }}</template>
                <template v-else> · 时间线起点</template>
                <template v-if="node.restorableReason"> · {{ node.restorableReason }}</template>
              </p>
              <div class="timeline-actions">
                <button
                  type="button"
                  class="danger"
                  :disabled="!node.restorable"
                  :title="node.restorable ? '停服后、下次启动前撤销；先预览并勾选确认' : (node.restorableReason || '该节点没有完整可逆快照')"
                  @click="previewRestore(node.id)"
                >撤销这次操作</button>
                <button type="button" class="ghost" @click="toggleJob(node.id)">查看 job</button>
                <button type="button" class="ghost" @click="toggleManifest(node.id)">查看 manifest 摘要</button>
                <button
                  type="button"
                  class="ghost"
                  :disabled="!node.restorable"
                  @click="exportNode(node)"
                >导出快照</button>
              </div>
              <pre v-if="openJobs.has(node.id)" class="world-stack-log">{{ jobText(node) }}</pre>
              <div v-if="openManifests.has(node.id)" class="world-stack-warning">
                <ul class="world-stack-warning-list">
                  <li v-for="line in manifestLines(node)" :key="line">{{ line }}</li>
                </ul>
              </div>
            </div>
          </div>
        </template>
        <p v-else class="muted small">
          还没有世界栈操作快照。导入/提升/归档完成后会在这里生成节点；旧备份没有 operation.json 时只读展示。
        </p>
      </div>
      <div v-if="preview" id="world-stack-timeline-preview" class="world-stack-preview">
        <div class="world-stack-preview-title">
          {{ preview.ok ? '撤销预览：可以执行' : '撤销预览：不可执行' }}
        </div>
        <dl class="world-stack-rows">
          <template v-for="row in previewRows" :key="row.key">
            <dt>{{ row.key }}</dt>
            <dd>{{ row.value }}</dd>
          </template>
        </dl>
        <div v-if="preview.beforeSummary?.length" class="world-stack-steps">
          <strong>将恢复到（操作前栈清单）</strong>
          <ul class="world-stack-warning-list">
            <li v-for="line in preview.beforeSummary" :key="line">{{ line }}</li>
          </ul>
        </div>
        <div v-if="preview.warnings?.length" class="world-stack-warning">
          <ul class="world-stack-warning-list">
            <li v-for="line in preview.warnings" :key="line">{{ line }}</li>
          </ul>
        </div>
        <div v-if="preview.steps?.length" class="world-stack-steps">
          <strong>将执行</strong>
          <ol><li v-for="step in preview.steps" :key="step">{{ step }}</li></ol>
        </div>
        <div v-if="preview.errors?.length" class="world-stack-error">
          <ul class="world-stack-error-list">
            <li v-for="line in preview.errors" :key="line">{{ line }}</li>
          </ul>
        </div>
        <template v-if="preview.ok && !preview.errors?.length">
          <label class="check-line world-stack-confirm">
            <input v-model="confirmed" type="checkbox" />
            <span>我确认：停服后、下次启动前撤销这次操作；当前栈状态先写入 pre-restore-&lt;job&gt;，失败自动回滚；源世界、归档与用户备份不会自动删除</span>
          </label>
          <button type="button" class="danger" :disabled="!confirmed || busy" @click="runRestore">
            {{ busy ? '排队中…' : '确认撤销（需重启）' }}
          </button>
        </template>
      </div>
      <div class="world-stack-snapshot-head">
        <h3>撤销前的安全快照</h3>
        <p class="muted small">执行撤销时保留的当前栈状态，不会被自动删除。确认不再需要回滚后可手动释放空间。</p>
      </div>
      <div id="world-stack-snapshots" class="world-stack-snapshots">
        <p class="muted small">
          安全快照：共 {{ snapshots.length }} 份，占用 {{ fmtBytes(totalBytes) }}。这些是撤销/恢复前的安全网，不会被 backupKeep 自动清理。
        </p>
        <div v-for="item in snapshots" :key="item.name" class="world-stack-snapshot">
          <div class="world-stack-snapshot-info">
            <strong class="mono">{{ item.name || '—' }}</strong>
            <span class="muted small">{{ snapshotMeta(item) }}</span>
          </div>
          <button type="button" class="ghost danger" @click="cleanupSnapshot(item)">删除快照</button>
        </div>
      </div>
    </div>
  </dialog>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { useApi } from '../../composables/useApi.js';
import { fmtBytes, fmtTime } from './model.js';

const props = defineProps({ modelValue: { type: Boolean, default: false } });
const emit = defineEmits(['update:modelValue']);

const dialogEl = ref(null);
const nodes = ref([]);
const snapshots = ref([]);
const totalBytes = ref(0);
const error = ref('');
const preview = ref(null);
const confirmed = ref(false);
const busy = ref(false);
const openJobs = ref(new Set());
const openManifests = ref(new Set());
const { json } = useApi();

const OPERATIONS = {
  import: '导入世界',
  archive: '归档栈层',
  promote: '提升栈顶',
  reorder: '调整排序',
  restore: '撤销操作',
  'restore-snapshot': '恢复快照',
};

function operationName(value) {
  return OPERATIONS[value] || value || '未知操作';
}

const previewRows = computed(() => {
  const item = preview.value;
  if (!item) return [];
  return [
    { key: '节点', value: item.id || '—' },
    { key: '操作', value: operationName(item.operation) },
    { key: 'job', value: item.jobId || '—' },
    { key: '受影响层', value: (item.affectedIds || []).join(' / ') || '—' },
    { key: '备份目录', value: item.backupDir || '—' },
    { key: '备份大小', value: fmtBytes(item.backupBytes) },
    { key: '当前受影响数据', value: fmtBytes(item.currentBytes) },
    { key: '预计需要', value: fmtBytes(item.neededBytes) + '（可用 ' + fmtBytes(item.freeBytes) + '）' },
    { key: '执行时机', value: '停服后、下次启动前；先写 pre-restore 快照' },
  ];
});

watch(() => props.modelValue, (open) => {
  if (open) {
    openDialog();
    loadAll();
  } else {
    dialogEl.value?.close?.();
  }
});

onMounted(() => {
  if (props.modelValue) {
    openDialog();
    loadAll();
  }
});

async function openDialog() {
  await nextTick();
  const dialog = dialogEl.value;
  if (!dialog || dialog.open) return;
  try { dialog.showModal(); } catch { dialog.setAttribute('open', ''); }
}

function close() {
  emit('update:modelValue', false);
}

async function loadAll() {
  error.value = '';
  preview.value = null;
  try {
    const data = await json('/api/world-stack/timeline', {}, '读取时间线失败');
    nodes.value = Array.isArray(data.nodes) ? data.nodes : [];
    await loadSnapshots();
  } catch (err) {
    error.value = err.message || String(err);
  }
}

async function loadSnapshots() {
  try {
    const data = await json('/api/world-stack/timeline/snapshots', {}, '读取快照失败');
    snapshots.value = Array.isArray(data.items) ? data.items : [];
    totalBytes.value = data.totalBytes || 0;
  } catch (err) {
    error.value = err.message || String(err);
  }
}

function toggleJob(id) {
  const next = new Set(openJobs.value);
  if (next.has(id)) next.delete(id); else next.add(id);
  openJobs.value = next;
}

function toggleManifest(id) {
  const next = new Set(openManifests.value);
  if (next.has(id)) next.delete(id); else next.add(id);
  openManifests.value = next;
}

function jobText(node) {
  return [
    'job=' + (node.jobId || '—'),
    'status=' + (node.status || '—'),
    'created=' + (node.createdAt || '—'),
    'operation=' + (node.operation || '—'),
  ].join('\n');
}

function manifestLines(node) {
  return [
    '节点：' + (node.id || '—'),
    '受影响层：' + ((node.affectedIds || []).join(' / ') || '—'),
    '可撤销：' + (node.restorable ? '是' : '否'),
    '说明：' + (node.restorableReason || '无'),
  ];
}

function snapshotMeta(item) {
  const parts = [fmtBytes(item.bytes), fmtTime(item.createdAt || item.modifiedAt)];
  if (item.operation) parts.push(operationName(item.operation));
  if (item.operationId) parts.push(item.operationId);
  return parts.join(' · ');
}

async function previewRestore(id) {
  error.value = '';
  confirmed.value = false;
  try {
    preview.value = await json('/api/world-stack/timeline/preview', {
      method: 'POST',
      body: JSON.stringify({ id }),
    }, '撤销预览失败');
  } catch (err) {
    error.value = err.message || String(err);
  }
}

async function runRestore() {
  if (!preview.value || !confirmed.value || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    const data = await json('/api/world-stack/timeline/restore', {
      method: 'POST',
      body: JSON.stringify({ id: preview.value.id, confirm: true }),
    }, '撤销排队失败');
    ElMessage.success('已排队撤销：' + (data.jobId || ''));
    emit('update:modelValue', false);
    await loadAll();
  } catch (err) {
    error.value = err.message || String(err);
  } finally {
    busy.value = false;
  }
}

async function exportNode(node) {
  if (!node.restorable) return;
  try {
    const data = await json('/api/world-stack/timeline/export?id=' + encodeURIComponent(node.id),
        {}, '导出快照失败');
    const blob = new Blob([data.content || JSON.stringify(data)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = data.fileName || node.id + '.json';
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (err) {
    error.value = err.message || String(err);
  }
}

async function cleanupSnapshot(item) {
  if (!item?.name) return;
  if (!window.confirm('删除安全快照 ' + item.name + '？此操作只删除撤销前保留的当前状态副本，'
    + '不会删除源世界、归档或操作备份。')) return;
  try {
    await json('/api/world-stack/timeline/cleanup', {
      method: 'POST',
      body: JSON.stringify({ name: item.name, confirm: true }),
    }, '删除快照失败');
    await loadSnapshots();
  } catch (err) {
    error.value = err.message || String(err);
  }
}
</script>
