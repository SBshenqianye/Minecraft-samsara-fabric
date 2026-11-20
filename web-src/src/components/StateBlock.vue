<template>
  <div v-if="loading" class="state-block" data-state="loading">
    <el-skeleton :rows="rows" animated />
  </div>
  <el-alert
    v-else-if="error"
    class="state-block"
    data-state="error"
    type="error"
    :closable="false"
    show-icon
    :title="error"
  >
    <template v-if="$slots.error" #default><slot name="error" /></template>
  </el-alert>
  <div v-else-if="empty" class="state-block" data-state="empty">
    <el-empty :description="emptyText" :image-size="64" />
    <div v-if="$slots.actions" class="state-block-actions"><slot name="actions" /></div>
  </div>
  <slot v-else />
</template>

<script setup>
// 区块级状态：加载/错误/空态。嵌在页面布局内部使用，不替换整页内容，
// 关键：出错时保留同页其他已加载区块（与 PageState 的整页语义区分）。
defineProps({
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  empty: { type: Boolean, default: false },
  emptyText: { type: String, default: '没有数据' },
  rows: { type: Number, default: 3 },
});
</script>

<style scoped>
.state-block {
  margin: 4px 0;
}
.state-block-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
  margin-top: 8px;
}
</style>
