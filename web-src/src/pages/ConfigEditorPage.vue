<template>
<TabShell name="config-editor">
<div id="panel-config-editor" class="panel-stack">
  <section class="panel">
    <div class="panel-head">
      <div>
        <h2>配置编辑器</h2>
        <p class="panel-sub">PageSpec / FormSpec 驱动：samsara YAML、服务器本体与第三方 YAML/JSON/TOML/properties。第三方与服务器配置需先预览确认，写入前自动备份。</p>
      </div>
      <div class="panel-tools">
        <label class="search-box">
          <span aria-hidden="true">⌕</span>
          <input id="config-editor-search" v-model="store.filter.value" type="search" placeholder="搜索页面 / 字段 / 分组" />
        </label>
        <span id="config-editor-summary" class="muted small">{{ store.summary.value }}</span>
      </div>
    </div>
    <div id="config-editor-message" class="message" :class="store.messageKind.value" role="status">{{ store.message.value }}</div>
    <div class="config-editor-layout">
      <div id="config-editor-body" class="config-editor-body">
        <ConfigEditorPanel :store="store" />
      </div>
    </div>
  </section>
</div>
</TabShell>
</template>

<script setup>
import { onActivated, onDeactivated, onBeforeUnmount, onMounted, watch } from 'vue';
import TabShell from '../components/TabShell.vue';
import ConfigEditorPanel from './config-editor/ConfigEditorPanel.vue';
import { clearPageNav, setPageNav } from '../composables/usePageNav.js';
import { useConfigEditor } from './config-editor/useConfigEditor.js';

defineOptions({ name: 'ConfigEditorPage' });

const store = useConfigEditor();
store.setSourceFilter('samsara');

function syncNav() {
  setPageNav({
    title: '配置编辑器',
    items: store.navItems.value.map((group) => ({
      ...group,
      id: 'group:' + group.label,
      target: '',
      onClick: () => {
        const first = group.children?.[0];
        if (first) store.openPage(first.target);
      },
      children: (group.children || []).map((page) => ({
        ...page,
        id: page.target,
        target: '',
        onClick: () => store.openPage(page.target),
      })),
    })),
  });
}

function activate() {
  if (!store.loaded.value) store.load();
  syncNav();
}

function onConnected() {
  store.load();
}

watch(() => store.navItems.value, syncNav, { deep: true });
onActivated(activate);
onDeactivated(clearPageNav);

onMounted(() => {
  window.addEventListener('samsara:connected', onConnected);
  activate();
});

onBeforeUnmount(() => {
  window.removeEventListener('samsara:connected', onConnected);
  clearPageNav();
});
</script>
