<template>
  <div class="server-page panel-stack">
    <el-alert
      v-if="loadError"
      id="server-load-error"
      class="server-load-error"
      type="error"
      :closable="false"
      show-icon
      :title="loadError"
    />
    <ServerConfigPanel
      :hidden="activePanel !== 'server-config-panel'"
      :model="model"
      :loading="loading"
      @reload="loadModel"
      @saved="onSaved"
      @dirty="dirtyFiles = $event"
    />
    <ServerBackupPanel
      :hidden="activePanel !== 'server-backup-panel'"
      :model="model"
      :loading="loading"
      @reload="loadModel"
    />
    <ServerAuditPanel
      :hidden="activePanel !== 'server-audit-panel'"
      @reloaded="loadModel"
    />
  </div>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useApi } from '../composables/useApi.js';
import { clearPageNav, setPageNav } from '../composables/usePageNav.js';
import ServerAuditPanel from './server/ServerAuditPanel.vue';
import ServerBackupPanel from './server/ServerBackupPanel.vue';
import ServerConfigPanel from './server/ServerConfigPanel.vue';
import {
  groupProperties,
  parseDraft,
} from './server/model.js';

defineOptions({ name: 'ServerPage' });

const route = useRoute();
const { request } = useApi();
const model = ref(null);
const loading = ref(false);
const dirtyFiles = ref(0);
const loadError = ref('');

const activePanel = computed(() => route.meta.serverPanel || 'server-config-panel');

async function loadModel() {
  loading.value = true;
  loadError.value = '';
  try {
    const result = await request('/api/server');
    if (!result.ok) {
      throw new Error(result.status === 401 || result.status === 403
        ? 'token 失效或没有权限，请重新连接'
        : (result.data?.error || '读取本体配置失败'));
    }
    model.value = result.data || null;
  } catch (e) {
    // 保留上一次数据，但必须让服主看到“读取失败”，否则空态会被当成“没有配置/没有备份”。
    loadError.value = '读取服务器信息失败：' + (e.message || String(e));
  } finally {
    loading.value = false;
  }
}

function onSaved() {
  dirtyFiles.value = 0;
}

/** 页内导航由数据实时生成；不依赖旧内核的 buildNav。 */
function syncPageNav() {
  if (activePanel.value === 'server-backup-panel') {
    setPageNav({
      title: '存档备份',
      count: (model.value?.timeline?.nodes || []).length,
      items: [
        { label: '备份时间线', target: 'server-backup-timeline' },
        { label: '任务与回滚', target: 'server-job-list' },
        { label: '文件位置', target: 'world-stack-files-panel' },
      ],
    });
    return;
  }
  if (activePanel.value === 'server-audit-panel') {
    setPageNav({
      title: 'Web 审计',
      items: [{ label: '审计记录', target: 'server-audit-list' }],
    });
    return;
  }
  const files = model.value?.configFiles || [];
  setPageNav({
    title: '服务器配置',
    count: files.length,
    items: files.map((file) => {
      const draft = parseDraft(file);
      const children = draft.kind === 'lines'
        ? groupProperties(file.id, draft.tokens, '').groups.map((group) => ({
          label: group.name,
          count: group.items.length,
          target: 'server-group-' + file.id + '-' + encodeURIComponent(group.name),
        }))
        : [];
      return {
        label: file.name,
        count: draft.kind === 'lines' ? groupProperties(file.id, draft.tokens, '').count
          : (draft.kind === 'json' ? draft.data.length : 1),
        target: 'server-file-' + file.id,
        children,
      };
    }),
  });
}

watch([activePanel, model], syncPageNav, { immediate: true });
onActivated(syncPageNav);
onDeactivated(clearPageNav);

function onConnected() {
  loadModel();
}

onMounted(() => {
  window.addEventListener('samsara:connected', onConnected);
  loadModel();
});

onBeforeUnmount(() => {
  window.removeEventListener('samsara:connected', onConnected);
  clearPageNav();
});
</script>
