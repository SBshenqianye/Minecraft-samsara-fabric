<template>
  <section class="panel-stack" data-page="storage-layout">
    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>存储布局</h2>
          <p class="panel-sub">只读视图：每类数据在哪个路径、多大、最近写入、是否随周目与是否进本体备份。不显示 token，也不会修改任何文件。</p>
        </div>
        <div class="panel-tools">
          <button id="storage-layout-reload" class="ghost" type="button" :disabled="loading" @click="load">
            {{ loading ? '读取中…' : '重新读取' }}
          </button>
        </div>
      </div>
      <div id="storage-layout-message" class="message" :class="messageKind" role="status">{{ message }}</div>
      <PageState :loading="loading" :error="error" :empty="!loading && !error && !rows.length"
          empty-text="没有可显示的存储目录。" @retry="load">
        <div id="storage-layout-rows" class="storage-layout-rows">
          <article v-for="row in rows" :key="row.id" class="storage-row">
            <div class="storage-row-head">
              <strong>{{ row.label }}</strong>
              <span class="muted small mono">{{ row.id }}</span>
              <span v-if="row.followsWorld" class="world-stack-badge">随周目</span>
              <span class="world-stack-badge" :class="{ ok: row.backup !== '不备份' }">{{ row.backup }}</span>
              <span v-if="!row.exists" class="world-stack-badge warn">不存在</span>
            </div>
            <p class="mono small storage-path" :title="row.path">{{ row.path }}</p>
            <div class="storage-stats">
              <span>{{ row.truncated ? '≥ ' : '' }}{{ formatBytes(row.bytes) }}</span>
              <span>{{ row.truncated ? '≥ ' : '' }}{{ row.files }} 个文件</span>
              <span>最近写入 {{ formatTime(row.updatedAt) }}</span>
            </div>
            <p class="muted small">{{ row.note }}</p>
          </article>
        </div>
      </PageState>
    </section>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import PageState from '../components/PageState.vue';
import { useApi } from '../composables/useApi.js';

defineOptions({ name: 'StorageLayoutPage' });

const { request } = useApi();
const loading = ref(false);
const error = ref('');
const message = ref('');
const messageKind = ref('');
const rows = ref([]);

function formatBytes(bytes) {
  const value = Number(bytes) || 0;
  if (value < 1024) return value + ' B';
  const kb = value / 1024;
  if (kb < 1024) return kb.toFixed(1) + ' KB';
  const mb = kb / 1024;
  if (mb < 1024) return mb.toFixed(1) + ' MB';
  return (mb / 1024).toFixed(2) + ' GB';
}

function formatTime(ms) {
  const value = Number(ms) || 0;
  if (!value) return '—';
  const date = new Date(value);
  return date.toLocaleString('zh-CN', { hour12: false });
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const result = await request('/api/storage');
    if (!result.ok || result.data?.ok === false) {
      throw new Error(result.data?.error || '读取存储布局失败');
    }
    rows.value = Array.isArray(result.data.rows) ? result.data.rows : [];
    message.value = '已读取 ' + rows.value.length + ' 个目录';
    messageKind.value = 'ok';
  } catch (e) {
    error.value = e.message || '读取存储布局失败';
    message.value = error.value;
    messageKind.value = 'error';
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.storage-layout-rows {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.storage-row {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--panel-soft, var(--panel));
  min-width: 0;
}

.storage-row-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.storage-path {
  margin: 6px 0;
  overflow-wrap: anywhere;
}

.storage-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: var(--text-sm);
  color: var(--fg-soft);
}
</style>
