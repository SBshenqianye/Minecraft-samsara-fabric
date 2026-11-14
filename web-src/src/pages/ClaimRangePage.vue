<template>
  <TabShell name="claim-range">
    <div id="panel-claim-range" class="panel-stack">
      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>圈地范围</h2>
            <p class="panel-sub">按染料颜色设置单方向延伸距离，或使用固定距离；保存后已有圈地会立即按新范围重算。</p>
          </div>
          <div class="panel-tools">
            <button id="claim-range-reload" class="ghost" type="button" :disabled="config.loading.value" @click="config.reload">重新读取</button>
            <button id="claim-range-save" class="primary" type="button" :disabled="config.saving.value" @click="config.save('claim-range')">保存并重载</button>
          </div>
        </div>
        <div id="claim-range-message" class="message" :class="config.messageKind.value" role="status">{{ config.message.value }}</div>
        <div id="claim-range-files" class="config-files">
          <ConfigFileBlock
            v-if="claimFile"
            :file="mainFile"
            :config="configStore"
            plain
          />
          <div v-else-if="config.model.value" class="nav-empty">没有找到 claim-range.yml 配置</div>
          <div id="claim-pattern-host" ref="patternHost"></div>
          <div id="claim-preview-host" ref="previewHost"></div>
        </div>
      </section>
    </div>
  </TabShell>
</template>

<script setup>
import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import TabShell from '../components/TabShell.vue';
import ConfigFileBlock from './config/ConfigFileBlock.vue';
import { createClaimKernel } from './config/claim-kernel.js';
import { createButtonGroup } from './config/button-group.js';
import { useConfig } from './config/useConfig.js';
import { clearPageNav, setPageNav } from '../composables/usePageNav.js';
import { entryElementId, entryVisible, groupElementId } from './config/model.js';

defineOptions({ name: 'ClaimRangePage' });

window.__samsaraClaimVuePage = true;

const config = useConfig({ includeClaim: true });
const configStore = config;
const patternHost = ref(null);
const previewHost = ref(null);
let previewApi = null;
let unsubscribe = null;

const claimFile = computed(() => config.claimFile.value);
const patternEntries = computed(() => (claimFile.value?.entries || [])
  .filter((entry) => String(entry.path || '').startsWith('patternDirections.')));
const mainFile = computed(() => {
  const file = claimFile.value;
  if (!file) return null;
  return {
    ...file,
    entries: (file.entries || []).filter((entry) =>
      !String(entry.path || '').startsWith('patternDirections.')),
  };
});

function mountKernel() {
  const file = claimFile.value;
  if (!file || !patternHost.value || !previewHost.value) return;
  previewApi = null;
  const kernel = createClaimKernel({
    getValue: (entry) => config.currentValue(file, entry),
    setValue: (entry, value) => config.setValue(file, entry, value),
    buttonGroup: createButtonGroup,
    onReady: (api) => { previewApi = api; },
  });
  const pattern = kernel.patternDirectionsEditor(file, patternEntries.value, (patternId) => {
    previewApi?.selectPattern?.(patternId);
    document.getElementById('claim-range-preview')?.scrollIntoView({ block: 'start' });
  });
  patternHost.value.replaceChildren(pattern);
  const preview = kernel.claimRangePreview(file);
  preview.id = 'claim-range-preview';
  previewHost.value.replaceChildren(preview);
}

function syncNav() {
  const file = claimFile.value;
  if (!file) {
    setPageNav({ title: '圈地范围', items: [] });
    return;
  }
  const entries = (mainFile.value?.entries || []);
  const groups = new Map();
  for (const entry of entries) {
    const name = entry.group || '其他';
    if (!groups.has(name)) groups.set(name, []);
    groups.get(name).push(entry);
  }
  const items = [...groups.entries()].map(([name, rows]) => ({
    label: name,
    count: rows.length,
    target: groupElementId(file.id, name),
    children: rows.map((entry) => ({
      label: entry.path,
      target: entryElementId(file.id, entry.path),
      hidden: !entryVisible(file, entry, config.edits),
    })),
  }));
  items.push(
    { label: '图案单位方向', target: 'claim-pattern-directions' },
    { label: '保护范围调试', target: 'claim-range-preview' },
    { label: '旗帜合成器', target: 'claim-banner-composer' },
  );
  setPageNav({ title: '圈地范围', items });
}

watch(claimFile, async () => {
  syncNav();
  await nextTick();
  mountKernel();
});

watch(
  () => config.edits.size,
  () => {
    previewApi?.redraw?.();
    syncNav();
  },
);

function onConnected() {
  config.load();
}

onActivated(syncNav);

onMounted(() => {
  window.addEventListener('samsara:connected', onConnected);
  config.load();
  nextTick(mountKernel);
  unsubscribe = config.onChange(() => {
    previewApi?.redraw?.();
    syncNav();
  });
});

onBeforeUnmount(() => {
  window.removeEventListener('samsara:connected', onConnected);
  window.__samsaraClaimVuePage = false;
  unsubscribe?.();
  clearPageNav();
});
</script>
