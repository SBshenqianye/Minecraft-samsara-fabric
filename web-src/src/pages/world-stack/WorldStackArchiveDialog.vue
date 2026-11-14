<template>
  <dialog
    ref="dialogEl"
    id="world-stack-archive-dialog"
    class="app-dialog world-stack-dialog"
    @close="close"
  >
    <div class="dialog-body">
      <div class="dialog-head">
        <div>
          <h3>归档 / 删除栈层</h3>
          <p class="dialog-sub">危险操作：先预览，确认后才排队；重启时把维度与元数据复制到 stack-archive 备份，再直接从栈清单删除；world 不能归档。</p>
        </div>
        <button class="ghost dialog-close" type="button" title="关闭" aria-label="关闭" @click="close">×</button>
      </div>
      <div v-if="error" id="world-stack-archive-error" class="world-stack-error" role="alert">{{ error }}</div>
      <div v-if="preview" id="world-stack-archive-preview" class="world-stack-preview">
        <div class="world-stack-preview-title">
          {{ preview.ok ? '归档预览：' + preview.targetId : '归档预览：需要修正' }}
        </div>
        <dl v-if="preview.source" class="world-stack-rows">
          <template v-for="row in previewRows" :key="row.key">
            <dt>{{ row.key }}</dt>
            <dd>{{ row.value }}</dd>
          </template>
        </dl>
        <div v-if="preview.steps?.length" class="world-stack-steps">
          <strong>将执行</strong>
          <ol><li v-for="step in preview.steps" :key="step">{{ step }}</li></ol>
        </div>
        <div v-if="preview.warnings?.length" class="world-stack-warning">
          <ul class="world-stack-warning-list">
            <li v-for="warning in preview.warnings" :key="warning">{{ warning }}</li>
          </ul>
        </div>
        <div v-if="preview.errors?.length" class="world-stack-error">
          <ul class="world-stack-error-list">
            <li v-for="item in preview.errors" :key="item">{{ item }}</li>
          </ul>
        </div>
        <template v-if="preview.ok && !preview.errors?.length">
          <label class="check-line world-stack-confirm">
            <input v-model="confirmed" type="checkbox" />
            <span>我确认：重启后把该栈层移到 stack-archive 备份并从栈清单移除，world 仍为栈顶</span>
          </label>
          <button
            type="button"
            class="danger"
            :disabled="!confirmed || busy"
            @click="runArchive"
          >{{ busy ? '排队中…' : '确认归档（需重启）' }}</button>
        </template>
      </div>
      <p v-else-if="loading" class="muted small">正在读取归档预览…</p>
    </div>
  </dialog>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue';
import { useApi } from '../../composables/useApi.js';
import { fmtBytes } from './model.js';

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  stackId: { type: String, default: '' },
});
const emit = defineEmits(['update:modelValue', 'archived']);

const dialogEl = ref(null);
const preview = ref(null);
const error = ref('');
const loading = ref(false);
const busy = ref(false);
const confirmed = ref(false);
const { json } = useApi();

const previewRows = computed(() => {
  const item = preview.value;
  if (!item?.source) return [];
  return [
    { key: '栈层', value: item.targetId },
    { key: '数据大小', value: fmtBytes(item.source.sizeBytes) },
    {
      key: '备份位置',
      value: item.backupPath || '服务器级 samsara/world-stack-archive/'
        + item.targetId + '-<job>',
    },
    { key: '执行时机', value: '服务器重启时；先备份再移除；备份不跟随存档' },
  ];
});

watch(() => props.modelValue, (open) => {
  if (open) {
    openDialog();
    loadPreview();
  } else {
    dialogEl.value?.close?.();
  }
});

watch(() => props.stackId, () => {
  if (props.modelValue) loadPreview();
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

async function loadPreview() {
  if (!props.stackId) return;
  loading.value = true;
  error.value = '';
  preview.value = null;
  confirmed.value = false;
  try {
    preview.value = await json('/api/world-stack/archive/preview', {
      method: 'POST',
      body: JSON.stringify({ id: props.stackId }),
    }, '归档预览失败');
  } catch (err) {
    error.value = err.message || String(err);
  } finally {
    loading.value = false;
  }
}

async function runArchive() {
  if (!confirmed.value || !preview.value || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    const data = await json('/api/world-stack/archive', {
      method: 'POST',
      body: JSON.stringify({ id: preview.value.targetId }),
    }, '归档排队失败');
    emit('archived', data);
    emit('update:modelValue', false);
  } catch (err) {
    error.value = err.message || String(err);
  } finally {
    busy.value = false;
  }
}
</script>
