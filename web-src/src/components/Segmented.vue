<template>
  <el-segmented
    class="samsara-segmented"
    :model-value="modelValue"
    :options="normalized"
    :size="size"
    :block="block"
    :aria-label="ariaLabel"
    @update:model-value="onUpdate"
  />
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  modelValue: { type: [String, Number, Boolean], default: '' },
  options: { type: Array, default: () => [] },
  size: { type: String, default: 'small' },
  block: { type: Boolean, default: false },
  ariaLabel: { type: String, default: '' },
});
const emit = defineEmits(['update:modelValue']);

// 同时接受字符串数组和 { value, label, hint } 数组，方便各页面复用。
const normalized = computed(() => props.options.map((option) => {
  if (option && typeof option === 'object') {
    return {
      value: option.value ?? option.id,
      label: option.label ?? String(option.value ?? option.id),
      disabled: option.disabled === true,
    };
  }
  return { value: option, label: String(option) };
}));

function onUpdate(value) {
  emit('update:modelValue', value);
}
</script>

<style scoped>
.samsara-segmented {
  --el-segmented-bg-color: var(--panel-soft, #1e242c);
  --el-segmented-item-selected-bg-color: var(--accent, #58a6ff);
  --el-segmented-item-selected-color: #fff;
  --el-segmented-item-hover-bg-color: color-mix(in srgb, var(--accent, #58a6ff) 16%, transparent);
  --el-segmented-item-active-bg-color: color-mix(in srgb, var(--accent, #58a6ff) 22%, transparent);
  --el-segmented-color: var(--muted, #9aa7b5);
  --el-segmented-item-selected-disabled-bg-color: var(--accent, #58a6ff);
  padding: 2px;
  border: 1px solid var(--line, #2c3542);
  border-radius: 8px;
  background: var(--panel-soft, #1e242c);
}

.samsara-segmented :deep(.el-segmented__item) {
  padding: 0 10px;
  border-radius: 6px;
  font-size: 11px;
  line-height: 22px;
  white-space: nowrap;
}

.samsara-segmented :deep(.el-segmented__item-selected) {
  box-shadow: 0 1px 4px rgba(0, 0, 0, .28);
}
</style>
