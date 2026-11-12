<template>
  <div class="slot-grid" :class="{ 'has-cursor': hasCursor }" :style="{ '--slot-columns': columns }">
    <button
      v-for="(encoded, index) in slots"
      :key="index"
      class="slot"
      :class="{ selected: selected === index, filled: !!itemAt(index) }"
      type="button"
      :aria-label="ariaFor(index)"
      :title="titleFor(index)"
      :draggable="!!itemAt(index)"
      @click.prevent="$emit('slot-click', { index, button: 0, shift: $event.shiftKey })"
      @contextmenu.prevent="$emit('slot-click', { index, button: 2, shift: $event.shiftKey })"
      @dragstart="$emit('drag-start', { index, event: $event })"
      @dragend="$emit('drag-end', { index })"
      @dragover.prevent
      @drop.prevent="$emit('drop-slot', { index, event: $event })"
      @mouseenter="$emit('slot-hover', { index, event: $event, item: itemAt(index) })"
      @mousemove="$emit('slot-hover', { index, event: $event, item: itemAt(index) })"
      @mouseleave="$emit('slot-leave', { index })"
    >
      <ItemIcon
        v-if="itemAt(index)"
        :item="itemAt(index)"
      />
      <img
        v-else-if="hintAt(index)"
        class="slot-hint"
        :src="hintAt(index).icon"
        :alt="hintAt(index).label"
        :title="hintAt(index).label"
        draggable="false"
      />
      <span v-else class="empty-index">{{ start + index }}</span>
      <span
        v-if="editable && itemAt(index)"
        class="slot-edit"
        role="button"
        tabindex="0"
        title="编辑组件 / NBT（不会拿起物品）"
        @click.stop="$emit('slot-edit', { index })"
        @keydown.enter.stop="$emit('slot-edit', { index })"
      >✎</span>
    </button>
  </div>
</template>
<script setup>
import { computed } from 'vue';
import ItemIcon from './ItemIcon.vue';

const props = defineProps({
  slots: { type: Array, default: () => [] },
  items: { type: Array, default: () => [] },
  selected: { type: Number, default: null },
  start: { type: Number, default: 0 },
  columns: { type: Number, default: 9 },
  iconBase: { type: String, default: '/items/' },
  hasCursor: { type: Boolean, default: false },
  editable: { type: Boolean, default: true },
  hints: { type: Array, default: () => [] },
});

defineEmits(['select', 'slot-click', 'slot-edit', 'drop-slot', 'drag-start',
  'drag-end', 'slot-hover', 'slot-leave']);

const itemMap = computed(() => {
  const map = new Map();
  for (const item of props.items) map.set(item.slot, item);
  return map;
});

function itemAt(index) {
  return itemMap.value.get(index) || null;
}

function hintAt(index) {
  return props.hints[index] || null;
}

function titleFor(index) {
  const item = itemAt(index);
  if (!item) {
    const hint = hintAt(index);
    return (hint ? hint.label + '槽（可放任意物品）' : '空槽位 ' + (props.start + index))
      + '\n点击拿起 / 右键分半，悬停显示完整属性';
  }
  return (item.zh || item.name || item.id) + ' ×' + item.count + '\n点击拿起 / 右键分半，悬停显示完整属性';
}

function ariaFor(index) {
  const item = itemAt(index);
  if (item) return '槽位 ' + (props.start + index) + ' ' + (item.zh || item.name || item.id) + ' ×' + item.count;
  const hint = hintAt(index);
  return hint ? hint.label + '槽位 ' + (props.start + index) + '（可放任意物品）'
    : '空槽位 ' + (props.start + index);
}
</script>
