<template>
  <div v-if="loading" class="page-state" data-state="loading">
    <el-skeleton :rows="rows" animated />
  </div>
  <div v-else-if="error" class="page-state" data-state="error">
    <el-alert type="error" :closable="false" show-icon :title="error">
      <template v-if="$slots.error" #default><slot name="error" /></template>
    </el-alert>
    <div v-if="$slots.actions" class="page-state-actions"><slot name="actions" /></div>
  </div>
  <div v-else-if="empty" class="page-state" data-state="empty">
    <el-empty :description="emptyText" :image-size="72" />
    <div v-if="$slots.actions" class="page-state-actions"><slot name="actions" /></div>
  </div>
  <slot v-else />
</template>

<script setup>
// 统一加载/错误/空态：页面只声明状态，不再各写一套骨架屏与提示。
defineProps({
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  empty: { type: Boolean, default: false },
  emptyText: { type: String, default: '暂无数据' },
  rows: { type: Number, default: 4 },
});
</script>

<style scoped>
.page-state {
  padding: 12px 0;
}
.page-state-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
  margin-top: 8px;
}
</style>
