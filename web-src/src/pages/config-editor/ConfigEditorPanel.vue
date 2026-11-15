<template>
  <div>
    <template v-if="page">
      <div class="config-editor-page-head">
        <h3>{{ store.pageTitle(page) }}</h3>
        <span class="config-editor-badge">{{ page.format || 'unknown' }}</span>
        <span class="config-editor-badge">
          {{ page.source === 'server' ? '服务器本体' : page.source === 'samsara' ? 'samsara' : '第三方模组' }}
        </span>
        <span v-if="page.requiresConfirmation" class="config-editor-badge">需预览确认</span>
        <span v-if="!page.writable" class="config-editor-badge">只读</span>
        <div class="config-editor-actions">
          <button v-if="page.customWidget" class="ghost" type="button" @click="openCustom">打开专用编辑器</button>
          <button v-if="page.writable" class="primary" type="button" @click="store.preview()">预览并保存</button>
          <button class="ghost" type="button" @click="store.reloadPage()">刷新</button>
        </div>
      </div>
      <p class="muted small">
        {{ (page.path || '') + (page.revision ? ' · revision ' + page.revision.slice(0, 12) : '') }}
      </p>
      <div v-if="page.readOnlyReason" class="config-editor-readonly">{{ page.readOnlyReason }}</div>
      <div v-if="!page.customWidget && page.writable && groups.length === 0" class="muted small">
        这个文件没有可编辑字段。
      </div>
      <details
        v-for="group in (page.customWidget || !page.writable ? [] : groups)"
        :key="group.id"
        class="config-editor-group"
        open
      >
        <summary>{{ group.id }} · {{ group.fields.length }}</summary>
        <div class="group-body">
          <ConfigEditorField
            v-for="field in group.fields"
            :key="field.path"
            :store="stores"
            :field="field"
          />
        </div>
      </details>
    </template>
    <p v-else class="muted">请选择左侧配置页面。</p>

    <dialog id="config-editor-preview-dialog" ref="dialogRef" class="app-dialog">
      <form method="dialog" class="dialog-body">
        <h3>预览变更</h3>
        <p id="config-editor-preview-sub" class="dialog-sub">{{ stores.previewInfo.sub }}</p>
        <div id="config-editor-preview-errors" class="message error" :hidden="!stores.previewInfo.error">
          {{ stores.previewInfo.error }}
        </div>
        <pre id="config-editor-preview-diff" class="config-editor-diff">{{ stores.previewInfo.diff }}</pre>
        <div class="dialog-actions">
          <span class="spacer"></span>
          <button type="button" id="config-editor-preview-cancel" class="ghost" @click="store.cancelPreview()">取消</button>
          <button type="button" id="config-editor-preview-confirm" class="primary" @click="store.confirmSave()">确认保存</button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ROUTE_ALIASES } from '../../navigation.js';
import ConfigEditorField from './ConfigEditorField.vue';

const props = defineProps({
  store: { type: Object, required: true },
});

const stores = props.store;
const router = useRouter();
const dialogRef = ref(null);
const page = computed(() => props.store.current.value);
const groups = computed(() => props.store.groups.value);

watch(() => props.store.previewOpen.value, async (open) => {
  const dialog = dialogRef.value;
  if (!dialog) return;
  if (open && !dialog.open) {
    if (typeof dialog.showModal === 'function') dialog.showModal();
  } else if (!open && dialog.open) {
    dialog.close();
  }
});

function openCustom() {
  const alias = ROUTE_ALIASES[page.value?.customWidget];
  if (alias) router.push(alias);
}
</script>
