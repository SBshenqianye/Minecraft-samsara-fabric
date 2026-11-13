<template>
  <div ref="root" class="dimension-picker" :class="{ disabled }">
    <button
      type="button"
      class="dimension-picker-trigger"
      :disabled="disabled"
      :aria-expanded="open ? 'true' : 'false'"
      @click.stop="toggle"
    >
      <span class="dimension-picker-current">{{ currentLabel }}</span>
      <span class="dimension-picker-caret">▾</span>
    </button>
    <div v-if="open" class="dimension-picker-pop" @click.stop>
      <div v-for="group in groups" :key="group.id" class="dimension-picker-row">
        <span class="dimension-picker-stack">{{ group.label }}</span>
        <span class="dimension-picker-values">
          <button
            v-for="dimension in group.dimensions"
            :key="dimension.id"
            type="button"
            class="dimension-picker-option"
            :class="{ active: dimension.id === modelValue }"
            @click="choose(dimension.id)"
          >
            {{ dimension.name }}
          </button>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

const props = defineProps({
  modelValue: { type: String, default: '' },
  groups: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false },
});
const emit = defineEmits(['update:modelValue']);

const root = ref(null);
const open = ref(false);

const currentLabel = computed(() => {
  for (const group of props.groups) {
    const found = (group.dimensions || []).find((dimension) => dimension.id === props.modelValue);
    if (found) return found.name;
  }
  return props.modelValue || '选择维度';
});

function toggle() {
  if (!props.disabled) open.value = !open.value;
}

function choose(id) {
  emit('update:modelValue', id);
  open.value = false;
}

function onDocumentPointer(event) {
  if (open.value && root.value && !root.value.contains(event.target)) open.value = false;
}

onMounted(() => document.addEventListener('pointerdown', onDocumentPointer, true));
onBeforeUnmount(() => document.removeEventListener('pointerdown', onDocumentPointer, true));
</script>

<style scoped>
.dimension-picker {
  position: relative;
  flex: 0 0 auto;
}

.dimension-picker.disabled {
  opacity: .55;
}

.dimension-picker-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 92px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--panel-soft);
  color: var(--fg);
  font-size: 12px;
  cursor: pointer;
}

.dimension-picker-trigger:disabled {
  cursor: default;
}

.dimension-picker-caret {
  margin-left: auto;
  color: var(--muted);
  font-size: 10px;
}

.dimension-picker-pop {
  position: absolute;
  z-index: 80;
  top: calc(100% + 4px);
  left: 0;
  min-width: 280px;
  padding: 6px;
  border: 1px solid var(--line-strong);
  border-radius: 9px;
  background: var(--panel);
  box-shadow: 0 12px 32px rgba(0, 0, 0, .38);
}

.dimension-picker-row {
  display: grid;
  grid-template-columns: minmax(80px, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 4px 5px;
  border-radius: 6px;
}

.dimension-picker-row:hover {
  background: var(--panel-soft);
}

.dimension-picker-stack {
  overflow: hidden;
  color: var(--muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dimension-picker-values {
  display: flex;
  gap: 3px;
}

.dimension-picker-option {
  padding: 3px 8px;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  color: var(--fg-soft);
  font-size: 11px;
  cursor: pointer;
  white-space: nowrap;
}

.dimension-picker-option:hover {
  border-color: var(--line-strong);
  background: var(--panel-soft);
}

.dimension-picker-option.active {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}
</style>
