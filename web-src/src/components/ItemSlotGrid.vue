<template>
  <div class="slot-grid" :style="{ '--slot-columns': columns }">
    <button
      v-for="(encoded, index) in slots"
      :key="index"
      class="slot"
      :class="{ selected: selected === index }"
      type="button"
      :title="titleFor(index)"
      :aria-label="ariaFor(index)"
      @click="$emit('select', index)"
    >
      <ItemIcon
        v-if="itemAt(index)"
        :item="itemAt(index)"
        :count="itemAt(index).count"
      />
      <span v-else class="empty-index">{{ index }}</span>
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
  columns: { type: Number, default: 9 },
  iconBase: { type: String, default: '/items/' },
});

defineEmits(['select']);

const itemMap = computed(() => {
  const map = new Map();
  for (const item of props.items) map.set(item.slot, item);
  return map;
});

function itemAt(index) {
  return itemMap.value.get(index) || null;
}

function titleFor(index) {
  const item = itemAt(index);
  if (!item) return '空槽位 ' + index;
  const parts = [item.name || item.id, item.id, '数量 ' + item.count];
  if (item.components) parts.push(String(item.components));
  return parts.join('\n');
}

function ariaFor(index) {
  const item = itemAt(index);
  return item ? '槽位 ' + index + ' ' + (item.name || item.id) + ' ×' + item.count : '空槽位 ' + index;
}
</script>
