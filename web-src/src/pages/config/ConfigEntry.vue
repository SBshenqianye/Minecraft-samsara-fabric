<template>
  <div
    :id="rowId"
    class="entry"
    :class="{ restart: entry.restart, 'entry-wide': wide }"
    :data-visible-when="entry.visibleWhen || undefined"
    :hidden="!visible"
  >
    <div>
      <div class="entry-key">
        {{ entry.path }}
        <span
          v-if="missing && !entry.sensitive"
          class="badge default"
          title="文件里没有这一项；保存时会按默认值写入"
        >默认值</span>
        <span v-if="entry.restart" class="badge restart">需重启</span>
      </div>
      <div v-if="entry.description" class="entry-desc">{{ entry.description }}</div>
    </div>
    <div class="entry-control">
      <ConfigEntryControl :file="file" :entry="entry" :config="config" />
      <div class="entry-error" :data-path="entry.path">{{ config.entryErrors.get(entry.path) || '' }}</div>
    </div>
    <div class="entry-actions">
      <button class="ghost" type="button" title="把这个字段恢复为 schema 默认值" @click="config.resetEntry(file, entry)">恢复默认</button>
      <button
        v-if="file.id === 'permissions' && entry.path === 'entries'"
        class="ghost"
        type="button"
        @click="openPermissions"
      >打开权限树</button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import ConfigEntryControl from './ConfigEntryControl.vue';
import { entryElementId, entryVisible, isMissingValue, isWideEntry } from './model.js';

const props = defineProps({
  file: { type: Object, required: true },
  entry: { type: Object, required: true },
  config: { type: Object, required: true },
});

const router = useRouter();
const rowId = computed(() => entryElementId(props.file.id, props.entry.path));
const wide = computed(() => isWideEntry(props.entry));
const missing = computed(() => isMissingValue(props.entry));
const visible = computed(() => entryVisible(props.file, props.entry, props.config.edits));

function openPermissions() {
  router.push('/samsara/config/permissions');
}
</script>
