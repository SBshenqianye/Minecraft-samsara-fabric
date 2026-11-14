<template>
  <component
    :is="plain ? 'div' : 'details'"
    :id="fileId"
    class="file-block"
    :class="{ 'plain-file': plain }"
    :open="plain ? undefined : true"
  >
    <summary v-if="!plain" class="file-summary">
      <span class="file-title">{{ title }}</span>
      <span class="file-path">{{ file.path }}</span>
      <span class="count-pill file-count">{{ view.count }}</span>
    </summary>
    <div class="file-body">
      <details
        v-for="group in view.groups"
        :id="groupId(group.name)"
        :key="group.name"
        class="group-block"
        open
      >
        <summary class="group-title">
          <span>{{ group.name }}</span>
          <span class="count-pill group-count">{{ group.entries.length }}</span>
        </summary>
        <ConfigEntry
          v-for="entry in group.entries"
          :key="entry.path"
          :file="file"
          :entry="entry"
          :config="config"
        />
      </details>
    </div>
  </component>
</template>

<script setup>
import { computed } from 'vue';
import ConfigEntry from './ConfigEntry.vue';
import { fileElementId, fileTitle, fileView, groupElementId } from './model.js';

const props = defineProps({
  file: { type: Object, required: true },
  config: { type: Object, required: true },
  plain: { type: Boolean, default: false },
  query: { type: String, default: '' },
});

const fileId = computed(() => fileElementId(props.file.id));
const title = computed(() => fileTitle(props.file));
const view = computed(() => fileView(props.file, props.config.edits, props.query));

function groupId(name) {
  return groupElementId(props.file.id, name);
}
</script>
