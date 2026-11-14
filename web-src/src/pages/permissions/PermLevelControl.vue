<template>
  <div class="segmented perm-levels" :class="{ compact }">
    <button
      v-for="choice in options"
      :key="choice.value"
      type="button"
      class="segmented-btn"
      :class="{ active: modelValue === choice.value }"
      :data-value="choice.value"
      :disabled="disabled"
      @click="$emit('update:modelValue', choice.value)"
    >{{ choice.label }}</button>
  </div>
</template>

<script setup>
defineProps({
  modelValue: { type: String, default: 'inherit' },
  options: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false },
  compact: { type: Boolean, default: true },
});

defineEmits(['update:modelValue']);
</script>

<style scoped>
.perm-levels {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-soft);
}
.segmented-btn {
  padding: 2px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
  line-height: 20px;
}
.segmented-btn:hover:not(:disabled) { background: var(--panel); color: var(--fg); }
.segmented-btn.active {
  background: var(--accent);
  color: var(--accent-ink);
}
.segmented-btn:disabled { opacity: .45; cursor: not-allowed; }
</style>
