<template>
<TabShell name="world-stack">
<div id="panel-world-stack" class="panel-stack">
  <section class="panel" id="world-stack-overview">
    <div class="panel-head">
      <div>
        <h2>世界栈</h2>
        <p class="panel-sub">导入服务器本地 world 目录；导入/归档在服务器重启时执行，源目录只读保持不变。排序只改逻辑顺序，不重命名 stack_<span class="muted">n</span> 目录。</p>
      </div>
      <div class="panel-tools">
        <button id="world-stack-timeline-open" class="ghost" type="button" @click="timelineOpen = true">操作时间线</button>
        <button id="world-stack-refresh" class="ghost" type="button" :disabled="store.loading.value" @click="store.load()">重新读取</button>
      </div>
    </div>
    <div id="world-stack-message" class="message" :class="store.messageKind.value" role="status">{{ store.message.value }}</div>
    <WorldStackStats :store="store" />
  </section>

  <section class="panel" id="world-stack-layers">
    <div class="panel-head">
      <div>
        <h2>栈层</h2>
        <p class="panel-sub">world 固定是栈顶；旧栈层是 world 内部的自定义维度，按从栈底往上数（stack_0 是最下层）。卡片显示存档名、存档创建/最后游玩时间、版本、种子、出生点、维度和数据大小。</p>
      </div>
    </div>
    <WorldStackCards
      :stacks="store.stacks.value"
      @archive="archiveStack"
      @upload="store.chooseIcon"
      @delete="store.deleteIcon"
    />
  </section>

  <WorldStackOrder :store="store" />
  <WorldStackImport :store="store" />
  <WorldStackJobs :store="store" />
  <WorldStackFiles :store="store" />

  <WorldStackArchiveDialog
    v-model="archiveOpen"
    :stack-id="archiveStackId"
    @archived="onArchived"
  />
  <WorldStackTimelineDialog v-model="timelineOpen" />
  <input
    ref="iconInput"
    id="world-stack-icon-input"
    type="file"
    accept="image/png,image/jpeg,image/webp"
    hidden
    @change="onIconFile"
  />
</div>
</TabShell>
</template>

<script setup>
import { onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import TabShell from '../components/TabShell.vue';
import WorldStackArchiveDialog from './world-stack/WorldStackArchiveDialog.vue';
import WorldStackCards from './world-stack/WorldStackCards.vue';
import WorldStackFiles from './world-stack/WorldStackFiles.vue';
import WorldStackImport from './world-stack/WorldStackImport.vue';
import WorldStackJobs from './world-stack/WorldStackJobs.vue';
import WorldStackOrder from './world-stack/WorldStackOrder.vue';
import WorldStackStats from './world-stack/WorldStackStats.vue';
import WorldStackTimelineDialog from './world-stack/WorldStackTimelineDialog.vue';
import { clearPageNav, setPageNav } from '../composables/usePageNav.js';
import { useWorldStack } from './world-stack/useWorldStack.js';

defineOptions({ name: 'WorldStackPage' });

const store = useWorldStack();
const archiveOpen = ref(false);
const archiveStackId = ref('');
const timelineOpen = ref(false);
const iconInput = ref(null);

function syncNav() {
  setPageNav({ title: '世界栈导航', items: store.navItems.value });
}

function archiveStack(stack) {
  archiveStackId.value = stack.id;
  archiveOpen.value = true;
}

async function onArchived(data) {
  store.setMessage('已排队：' + data.jobId + '。重启服务器后归档；备份会保留。', 'ok');
  await store.load();
}

function onIconFile(event) {
  const file = event.target.files?.[0] || null;
  if (file) store.handleIconFile(file);
}

function onConnected() {
  store.load();
}

watch(() => store.model.value, syncNav);
onActivated(syncNav);

onMounted(() => {
  window.addEventListener('samsara:connected', onConnected);
  store.setIconInput(iconInput.value);
  syncNav();
  store.load();
});

onBeforeUnmount(() => {
  window.removeEventListener('samsara:connected', onConnected);
  clearPageNav();
});
</script>
