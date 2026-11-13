<template>
  <div class="framework-dialog-host">
    <el-dialog
      v-for="dialog in dialogs"
      :key="dialog.id"
      v-model="dialog.visible"
      :title="dialog.title"
      :width="dialog.width"
      append-to-body
      :z-index="1600"
      class="samsara-framework-dialog"
      @opened="mount(dialog)"
    >
      <div
        :ref="(el) => setHost(dialog, el)"
        class="framework-dialog-body"
      ></div>
    </el-dialog>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue';

const dialogs = ref([]);
const hosts = new Map();

function setHost(dialog, el) {
  if (!el) {
    hosts.delete(dialog.id);
    return;
  }
  hosts.set(dialog.id, el);
  if (dialog.content && dialog.content.parentElement !== el) el.append(dialog.content);
}

async function mount(dialog) {
  await nextTick();
  const el = hosts.get(dialog.id);
  if (el && dialog.content && dialog.content.parentElement !== el) el.append(dialog.content);
}

async function open(id, options = {}) {
  const key = String(id || '');
  if (!key) return;
  let dialog = dialogs.value.find((item) => item.id === key);
  if (!dialog) {
    dialog = reactive({
      id: key,
      title: options.title || '',
      width: options.width || 'min(980px, 94vw)',
      visible: false,
      content: null,
    });
    dialogs.value.push(dialog);
  }
  dialog.title = options.title || dialog.title || '对话框';
  dialog.width = options.width || dialog.width;
  if (options.content) dialog.content = options.content;
  await nextTick();
  dialog.visible = true;
  await nextTick();
  mount(dialog);
}

function close(id) {
  const dialog = dialogs.value.find((item) => item.id === id);
  if (dialog) dialog.visible = false;
}

function isOpen(id) {
  const dialog = dialogs.value.find((item) => item.id === id);
  return Boolean(dialog && dialog.visible);
}

function closeAll() {
  for (const dialog of dialogs.value) dialog.visible = false;
}

onMounted(() => {
  window.SamsaraDialog = { open, close, isOpen, closeAll };
});

onBeforeUnmount(() => {
  if (window.SamsaraDialog) window.SamsaraDialog = null;
  hosts.clear();
});
</script>

<style>
.samsara-framework-dialog .el-dialog__body {
  max-height: 86vh;
  overflow: auto;
}
.samsara-framework-dialog .framework-dialog-body > .dialog-body > h3 {
  display: none;
}
.samsara-framework-dialog .identity-data-content {
  max-height: none;
}
</style>
