<template>
  <section class="panel" id="world-stack-order-panel">
    <div class="panel-head">
      <div>
        <h2>栈位排序</h2>
        <p class="panel-sub">列表从上到下 = 栈顶 → 栈底（world 在最上面）。拖动或上下移动，保存前先看预览。只调整栈顶以下时立即生效；把旧栈层拖到栈顶会交换 world 与该层数据，需重启并在失败时回滚。</p>
      </div>
      <div class="panel-tools world-stack-order-actions">
        <span id="world-stack-order-preview" class="muted small">{{ view.preview }}</span>
        <button
          id="world-stack-order-save"
          class="primary"
          type="button"
          :disabled="!view.canSave || store.model.value?.restartRequired"
          @click="store.saveOrder()"
        >保存排序</button>
      </div>
    </div>
    <div id="world-stack-order" class="world-stack-order">
      <p v-if="!view.rows.length" class="muted small">当前没有栈层。</p>
      <div
        v-for="row in view.rows"
        :key="row.id"
        class="world-stack-order-item"
        :class="{ top: row.top, dragging: dragging === row.id, 'drop-target': dropTarget === row.id }"
        draggable="true"
        :data-id="row.id"
        @dragstart="onDragStart(row.id, $event)"
        @dragend="dragging = ''"
        @dragover.prevent="dropTarget = row.id"
        @dragleave="onDragLeave(row.id, $event)"
        @drop.prevent="onDrop(row.id)"
      >
        <span class="world-stack-drag" title="拖动排序">⋮⋮</span>
        <div class="world-stack-order-label">
          <strong>{{ row.label }}</strong>
          <span class="muted small">{{ row.meta }}</span>
        </div>
        <div class="world-stack-order-tools">
          <button class="ghost" type="button" title="向栈顶方向移动"
              :disabled="row.upDisabled" @click="store.moveBy(row.id, 1)">↑</button>
          <button class="ghost" type="button" title="向栈底方向移动"
              :disabled="row.downDisabled" @click="store.moveBy(row.id, -1)">↓</button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue';

const props = defineProps({
  store: { type: Object, required: true },
});
const dragging = ref('');
const dropTarget = ref('');
const view = computed(() => props.store.orderView.value);

function onDragStart(id, event) {
  dragging.value = id;
  try { event.dataTransfer.setData('text/plain', id); } catch { /* ignore */ }
}

function onDragLeave(id, event) {
  if (!event.currentTarget.contains(event.relatedTarget) && dropTarget.value === id) {
    dropTarget.value = '';
  }
}

function onDrop(id) {
  dropTarget.value = '';
  if (dragging.value && dragging.value !== id) props.store.moveTo(dragging.value, id);
  dragging.value = '';
}
</script>
