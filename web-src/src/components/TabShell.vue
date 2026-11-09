<template>
  <div class="tab-shell" :data-page="name">
    <div v-if="$slots.actions" class="page-toolbar">
      <slot name="actions" />
    </div>
    <slot />
  </div>
</template>

<script setup>
// 页面标题/说明统一由 AdminLayout 的顶部 hero 横幅展示；
// TabShell 只保留页面内容与可选的页内工具栏，避免内容区再出现第二条横幅。
defineProps({
  name: { type: String, required: true },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  badges: { type: Array, default: () => [] },
  compact: { type: Boolean, default: false },
});
</script>
