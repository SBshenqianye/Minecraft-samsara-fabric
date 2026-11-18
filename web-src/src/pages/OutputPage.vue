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
              <input id="output-search" v-model="filter" type="search" placeholder="搜索消息 / 分组 / 说明" />
            </label>
            <span class="inline-toggle">
              <span class="muted small">自动续行前缀</span>
              <span id="output-auto-continue">
                <SegmentedButtons
                  :value="store.autoContinue.value ? 'true' : 'false'"
                  :choices="AUTO_CHOICES"
                  @change="(next) => store.setAutoContinue(next === 'true')"
                />
              </span>
            </span>
            <button class="ghost" type="button" :disabled="store.saving.value" @click="store.save()">保存并重载</button>
            <button class="ghost" type="button" :disabled="store.loading.value" @click="store.reload()">重新读取</button>
            <button class="ghost" type="button" @click="store.restoreDefault()">恢复默认</button>
          </div>
        </div>

        <div id="output-message" class="message" :class="store.messageKind.value" role="status">{{ store.message.value }}</div>

        <div class="output-grid">
          <OutputEditor :store="store" />
          <section class="output-preview-wrap">
            <div class="column-title">渲染预览</div>
            <div id="output-preview" class="output-preview">
              <p v-if="store.previewHint.value" class="muted small">{{ store.previewHint.value }}</p>
              <template v-else>
                <span
                  v-for="(fragment, index) in store.fragments.value"
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
import { computed, onActivated, onDeactivated, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import TabShell from '../components/TabShell.vue';
import SegmentedButtons from '../components/SegmentedButtons.vue';
import OutputEditor from './output/OutputEditor.vue';
import { clearPageNav, setPageNav } from '../composables/usePageNav.js';
import { useUnsavedChanges } from '../composables/useUnsavedChanges.js';
import { useOutput } from './output/useOutput.js';

defineOptions({ name: 'OutputPage' });

const AUTO_CHOICES = [{ value: 'true', label: '开' }, { value: 'false', label: '关' }];
const store = useOutput();
useUnsavedChanges(() => store.dirty.value);
const filter = ref('');

const filteredMessages = computed(() => {
  const needle = filter.value.trim().toLowerCase();
  return (store.model.value?.messages || []).filter((message) => !needle
    || (message.key + ' ' + message.group + ' ' + message.description)
      .toLowerCase().includes(needle));
});

function syncNav() {
  const groups = new Map();
  for (const message of filteredMessages.value) {
    if (!groups.has(message.group)) groups.set(message.group, []);
    groups.get(message.group).push(message);
  }
  const items = [...groups.entries()].map(([group, messages]) => ({
    label: group,
    count: messages.length,
    target: 'out-msg-' + encodeURIComponent(messages[0].key),
    onClick: () => store.select(messages[0].key),
    children: messages.map((message) => ({
      label: message.key,
      target: 'out-msg-' + encodeURIComponent(message.key),
      onClick: () => store.select(message.key),
    })),
  }));
  setPageNav({
    title: '输出消息',
    count: filter.value
      ? filteredMessages.value.length + ' / ' + (store.model.value?.messages || []).length
      : (store.model.value?.messages || []).length,
    items,
  });
}

function activate() {
  if (!store.model.value) store.load();
  else store.renderPreview();
  syncNav();
}

function onConnected() {
  store.load();
}

watch([() => store.model.value, filter, () => store.key.value], syncNav);
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
