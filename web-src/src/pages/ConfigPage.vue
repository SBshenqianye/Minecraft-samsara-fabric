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
            :hidden="!warnings.length"
          >{{ warnings.length ? '最近警告：' + warnings.join('；') : '' }}</span>
        </div>
        <div id="status-chips" class="status-chips">
          <div v-for="[label, value] in chips" :key="label" class="status-chip">
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
              <input id="search" type="search" placeholder="搜索配置：键名 / 说明 / 分组" />
            </label>
            <!-- 配置渲染仍由 app.js 负责，摘要文本由内核写入。 -->
            <span id="summary" class="muted small"></span>
          </div>
        </div>
        <div id="message" class="message" role="status"></div>
        <div id="files" class="config-files"></div>
      </section>
    </div>
  </TabShell>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import TabShell from '../components/TabShell.vue';
import { useApi } from '../composables/useApi.js';

defineOptions({ name: 'ConfigPage' });

// 运行状态面板由 Vue 渲染，内核 renderStatus() 不再写这两块 DOM。
window.__samsaraConfigVueStatus = true;

const { request } = useApi();
const status = ref(null);

const chips = computed(() => {
  const value = status.value;
  if (!value) return [];
  return [
    ['Web 版本', value.webVersion],
    ['模组版本', value.version],
    ['Minecraft', value.minecraft],
    ['配置根', value.configRoot],
    ['世界数据', value.worldDataRoot],
    ['身份数据', value.identityRoot],
  ].map(([label, text]) => [label, text == null || text === '' ? '—' : String(text)]);
});
const warnings = computed(() => (status.value?.warnings || []));

async function loadStatus() {
  try {
    const result = await request('/api/status');
    if (result.ok) status.value = result.data || null;
  } catch {
    // 状态读取失败不影响配置编辑。
  }
}

function onConnected() {
  loadStatus();
}

onMounted(() => {
  window.addEventListener('samsara:connected', onConnected);
  loadStatus();
});

onBeforeUnmount(() => {
  window.removeEventListener('samsara:connected', onConnected);
  window.__samsaraConfigVueStatus = false;
});
</script>
