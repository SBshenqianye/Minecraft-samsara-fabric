<template>
  <TabShell name="output">
    <div id="panel-output" class="panel-stack">
      <section class="panel output-panel">
        <div class="panel-head">
          <div>
            <h2>输出格式</h2>
            <p class="panel-sub">
              每个消息是一块可编辑画布；文字、变量标签、按钮标签都在同一个内编辑区。
            </p>
            <p class="panel-sub">
              变量按变量权限节点渲染；按钮按权限节点判断可见性。
            </p>
          </div>
          <div class="panel-tools">
            <label class="search-box">
              <span aria-hidden="true">⌕</span>
              <input
                id="output-search"
                type="search"
                placeholder="搜索消息 / 分组 / 说明"
                @input="(event) => kernelSearch(event.target.value)"
              />
            </label>
            <span class="inline-toggle">
              <span class="muted small">自动续行前缀</span>
              <span id="output-auto-continue">
                <SegmentedButtons
                  :value="autoContinue ? 'true' : 'false'"
                  :choices="AUTO_CHOICES"
                  @change="(next) => setAutoContinue(next === 'true')"
                />
              </span>
            </span>
          </div>
        </div>

        <div id="output-message" class="message" role="status"></div>

        <div class="output-grid">
          <section class="output-edit-wrap">
            <div class="column-title">字段改写</div>
            <div id="output-disabled" class="output-disabled" hidden></div>
            <div id="output-meta" class="output-meta">{{ metaText }}</div>
            <div
              id="output-editor"
              class="rich-editor"
              contenteditable="true"
              spellcheck="false"
              aria-label="输出消息编辑画布"
            ></div>
            <div id="output-errors" class="output-errors"></div>
            <div class="editor-hint">
              拖入按钮和变量是原子标签，不能改文字；按钮可以改标签文字。
              选择消息后会恢复原始模板；重置会恢复系统模板。
            </div>
            <h3 class="palette-title">变量 <span id="output-var-count" class="count-pill">0</span></h3>
            <div id="output-palette" class="palette"></div>
            <h3 class="palette-title">按钮 <span id="output-button-count" class="count-pill">0</span></h3>
            <div id="output-buttons" class="palette"></div>
          </section>

          <section class="output-preview-wrap">
            <div class="column-title">渲染预览</div>
            <div id="output-preview" class="output-preview">
              <p v-if="previewHint" class="muted small">{{ previewHint }}</p>
              <template v-else>
                <span
                  v-for="(fragment, index) in fragments"
                  :key="index"
                  :class="{ 'preview-button': fragment.clickable }"
                  :style="fragment.style"
                  :title="fragment.title"
                >{{ fragment.text }}</span>
              </template>
            </div>
            <p class="muted small">预览由服务端渲染；按钮只显示标签，不执行命令。</p>
          </section>
        </div>
      </section>
    </div>
  </TabShell>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import TabShell from '../components/TabShell.vue';
import SegmentedButtons from '../components/SegmentedButtons.vue';
import { useApi } from '../composables/useApi.js';
import { useLegacyPage } from '../composables/useLegacyPage.js';
import { messageSummary, previewFragments } from './output/model.js';

defineOptions({ name: 'OutputPage' });

const AUTO_CHOICES = [{ value: 'true', label: '开' }, { value: 'false', label: '关' }];
const { request } = useApi();
const selected = ref(null);
const autoContinue = ref(true);
const fragments = ref([]);
const previewHint = ref('');
let seq = 0;

// 外围信息栏与预览由 Vue 接管，内核保留 contenteditable 画布与标签逻辑。
window.__samsaraOutputVueShell = true;

const metaText = computed(() => messageSummary(selected.value));

function onSelected(event) {
  selected.value = event.detail || null;
}

function onAuto(event) {
  autoContinue.value = Boolean(event.detail?.enabled);
}

function onParts(event) {
  const { key, parts, errors } = event.detail || {};
  renderPreview(key, parts, errors || []);
}

async function renderPreview(key, parts, errors) {
  const current = ++seq;
  fragments.value = [];
  previewHint.value = '';
  if (!key) return;
  if (errors.length) {
    previewHint.value = '修正模板后才会生成预览：' + errors.join('；');
    return;
  }
  try {
    const result = await request('/api/output/preview', {
      method: 'POST',
      body: JSON.stringify({ key, parts }),
    });
    if (current !== seq) return;
    if (!result.ok || result.data?.ok === false) {
      throw new Error(result.data?.error || '预览失败');
    }
    fragments.value = previewFragments(result.data);
    if (!fragments.value.length) previewHint.value = '（预览为空）';
  } catch (error) {
    if (current !== seq) return;
    previewHint.value = error.message || '预览失败';
  }
}

function setAutoContinue(enabled) {
  autoContinue.value = enabled;
  window.SamsaraOutput?.setAutoContinue?.(enabled);
}

function kernelSearch(value) {
  // 内核监听 #output-search 的 input 事件刷新页内导航；这里只兜底触发一次。
  const input = document.getElementById('output-search');
  if (input && input.value !== value) input.value = value;
}

onMounted(() => {
  window.addEventListener('samsara:output-selected', onSelected);
  window.addEventListener('samsara:output-auto', onAuto);
  window.addEventListener('samsara:output-parts', onParts);
});

onBeforeUnmount(() => {
  window.removeEventListener('samsara:output-selected', onSelected);
  window.removeEventListener('samsara:output-auto', onAuto);
  window.removeEventListener('samsara:output-parts', onParts);
  window.__samsaraOutputVueShell = false;
});

useLegacyPage({
  panelId: 'output-editor',
  load: () => import('../legacy-core/output.js'),
  activate: () => window.SamsaraOutput?.activate?.(),
});
</script>
