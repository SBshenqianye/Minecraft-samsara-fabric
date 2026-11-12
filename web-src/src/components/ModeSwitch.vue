<template>
  <div class="mode-switch" role="group" aria-label="写入模式">
    <button
      v-for="option in WRITE_MODES"
      :key="option.id"
      type="button"
      :class="{ active: modelValue === option.id }"
      :title="option.hint"
      :data-mode="option.id"
      @click="$emit('update:modelValue', option.id)"
    >{{ option.label }}</button>
  </div>
</template>

<script setup>
import { WRITE_MODES } from '../composables/writeModes.js';

defineProps({ modelValue: { type: String, default: 'simulate' } });
defineEmits(['update:modelValue']);
</script>

<style scoped>
.mode-switch {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-soft);
}

.mode-switch button {
  padding: 3px 9px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
  cursor: pointer;
}

.mode-switch button.active {
  background: var(--accent);
  color: #fff;
}
</style>
