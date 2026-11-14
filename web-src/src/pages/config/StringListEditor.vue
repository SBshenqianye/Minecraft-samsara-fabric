<template>
  <div class="struct-list">
    <div v-for="(row, index) in rows" :key="index" class="struct-row">
      <input
        class="struct-input"
        :value="row"
        @input="(event) => updateRow(index, event.target.value)"
      />
      <button class="struct-btn ghost" type="button" title="删除" @click="removeRow(index)">×</button>
    </div>
    <button class="struct-btn ghost" type="button" title="添加" @click="addRow">+ 添加一项</button>
  </div>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
});
const emit = defineEmits(['update:modelValue']);

const rows = computed(() => (Array.isArray(props.modelValue) ? props.modelValue : []));

function emitRows(next) {
  emit('update:modelValue', next.map((value) => String(value ?? '').trim()).filter(Boolean));
}

function updateRow(index, value) {
  const next = rows.value.slice();
  next[index] = value;
  emitRows(next);
}

function removeRow(index) {
  const next = rows.value.slice();
  next.splice(index, 1);
  emitRows(next);
}

function addRow() {
  emit('update:modelValue', [...rows.value, '']);
}
</script>
