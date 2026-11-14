<template>
  <TabShell name="config">
    <div id="panel-config" class="panel-stack">
      <section class="panel status-panel">
        <div class="panel-head">
          <div>
            <h2>运行状态</h2>
            <p class="panel-sub">只读信息；配置文件保存在服务器本机。</p>
          </div>
          <span
            id="status-warning"
            class="status-warning"
            :hidden="!config.warnings.value.length"
          >{{ config.warnings.value.length ? '最近警告：' + config.warnings.value.join('；') : '' }}</span>
        </div>
        <div id="status-chips" class="status-chips">
          <div v-for="[label, value] in config.chips.value" :key="label" class="status-chip">
            <span>{{ label }}</span>
            <strong :title="value">{{ value }}</strong>
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>配置项</h2>
            <p class="panel-sub">所有输入都会写入对应 YAML；留空时仍按默认值兜底。</p>
          </div>
          <div class="panel-tools">
            <label class="search-box">
              <span aria-hidden="true">⌕</span>
              <input
                id="search"
                v-model="config.search.value"
                type="search"
                placeholder="搜索配置：键名 / 说明 / 分组"
              />
            </label>
            <span id="summary" class="muted small">{{ config.summary.value }}</span>
            <button id="config-reload" class="ghost" type="button" :disabled="config.loading.value" @click="config.reload">重新读取</button>
            <button id="config-save" class="primary" type="button" :disabled="config.saving.value" @click="config.save()">保存并重载</button>
          </div>
        </div>
        <div id="message" class="message" :class="config.messageKind.value" role="status">{{ config.message.value }}</div>
        <div id="files" class="config-files">
          <ConfigFileBlock
            v-for="file in visibleFiles"
            :key="file.id"
            :file="file"
            :config="configStore"
            :query="config.search.value"
          />
          <p v-if="!visibleFiles.length && config.model.value" class="muted small">没有匹配的配置项。</p>
        </div>
      </section>
    </div>
  </TabShell>
</template>

<script setup>
import { computed, onActivated, onBeforeUnmount, onMounted, watch } from 'vue';
import TabShell from '../components/TabShell.vue';
import ConfigFileBlock from './config/ConfigFileBlock.vue';
import { useConfig } from './config/useConfig.js';
import { clearPageNav, setPageNav } from '../composables/usePageNav.js';
import {
  entryElementId,
  entryVisible,
  fileElementId,
  fileTitle,
  fileView,
  groupElementId,
} from './config/model.js';

defineOptions({ name: 'ConfigPage' });

// 配置渲染由 Vue 接管；app.js 只保留页面壳与连接流程。
window.__samsaraConfigVuePage = true;

const config = useConfig();
const configStore = config;

const visibleFiles = computed(() => config.configFiles.value
  .filter((file) => fileView(file, config.edits, config.search.value).count > 0));

function syncNav() {
  const items = visibleFiles.value.map((file) => {
    const view = fileView(file, config.edits, config.search.value);
    return {
      label: fileTitle(file),
      count: view.count,
      target: fileElementId(file.id),
      children: view.groups.map((group) => ({
        label: group.name,
        count: group.entries.length,
        target: groupElementId(file.id, group.name),
        children: group.entries.map((entry) => ({
          label: entry.path,
          target: entryElementId(file.id, entry.path),
          hidden: !entryVisible(file, entry, config.edits),
        })),
      })),
    };
  });
  setPageNav({ title: '配置导航', count: config.total.value, items });
}

watch(
  () => [visibleFiles.value, config.edits.size, config.search.value],
  syncNav,
  { deep: true, immediate: true },
);

let unsubscribe = null;

function onConnected() {
  config.load();
  config.loadStatus();
}

onActivated(syncNav);

onMounted(() => {
  window.addEventListener('samsara:connected', onConnected);
  syncNav();
  config.load();
  config.loadStatus();
  unsubscribe = config.onChange(syncNav);
});

onBeforeUnmount(() => {
  window.removeEventListener('samsara:connected', onConnected);
  unsubscribe?.();
  window.__samsaraConfigVuePage = false;
  clearPageNav();
});
</script>
