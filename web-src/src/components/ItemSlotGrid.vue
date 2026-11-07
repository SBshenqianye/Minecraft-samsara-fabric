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
      <template v-if="itemAt(index)">
        <img
          v-if="!failed.has(index)"
          :src="iconUrl(itemAt(index))"
          :alt="itemAt(index).name"
          @error="fail(index)"
        />
        <span v-else class="fallback">{{ (itemAt(index).name || '?').slice(0, 1) }}</span>
        <span class="count">{{ itemAt(index).count }}</span>
      </template>
      <span v-else class="empty-index">{{ index }}</span>
    </button>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue';

const props = defineProps({
  slots: { type: Array, default: () => [] },
  items: { type: Array, default: () => [] },
  selected: { type: Number, default: null },
  columns: { type: Number, default: 9 },
  iconBase: { type: String, default: '/items/' },
});

defineEmits(['select']);

const failed = ref(new Set());

const itemMap = computed(() => {
  const map = new Map();
  for (const item of props.items) map.set(item.slot, item);
  return map;
});

watch(() => props.items, () => {
  failed.value = new Set();
});

function itemAt(index) {
  return itemMap.value.get(index) || null;
}

function iconUrl(item) {
  return props.iconBase + (item.icon || 'stone') + '.png';
}

function fail(index) {
  const next = new Set(failed.value);
  next.add(index);
  failed.value = next;
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
