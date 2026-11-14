<template>
  <span
    class="uuid-chip"
    :class="[role, 'role-' + (role || 'data'), { online }]"
    draggable="true"
    :title="uuid + ' · ' + title"
    @dragstart="onDragStart"
    @dragend="$emit('dragend')"
  >
    <span class="uuid-name" :data-name-for="uuid" :data-label-locked="locked ? '1' : undefined">
      {{ displayName }}
    </span>
    <span class="uuid-code">{{ short }}</span>
    <span v-if="tag" class="uuid-role">{{ tag }}</span>
    <span v-if="badge" class="uuid-role badge">{{ badge }}</span>
    <span v-if="onlineLabel" class="uuid-role">当前在线</span>
    <span class="chip-actions">
      <button
        v-for="action in actions"
        :key="action.key"
        type="button"
        class="chip-action"
        :title="action.title || ''"
        @click.stop="$emit('action', action.key)"
      >{{ action.label }}</button>
    </span>
  </span>
</template>

<script setup>
import { computed } from 'vue';
import { shortUuid } from './model.js';

const props = defineProps({
  uuid: { type: String, default: '' },
  role: { type: String, default: '' },
  name: { type: String, default: '' },
  displayName: { type: String, default: '' },
  tag: { type: String, default: '' },
  badge: { type: String, default: '' },
  online: { type: Boolean, default: false },
  onlineLabel: { type: Boolean, default: false },
  locked: { type: Boolean, default: false },
  title: { type: String, default: '' },
  actions: { type: Array, default: () => [] },
});
const emit = defineEmits(['action', 'dragstart', 'dragend']);

const short = computed(() => shortUuid(props.uuid));

function onDragStart(event) {
  event.dataTransfer.effectAllowed = 'copy';
  try { event.dataTransfer.setData('text/plain', props.uuid); } catch { /* ignore */ }
  emit('dragstart', { uuid: props.uuid, name: props.name, role: props.role || null });
}
</script>
