<template>
  <div class="int-list-wrap">
    <div class="int-list-tools">
      <span class="int-list-hint">图案颜色 0（白）→ 15（黑）；数字为单方向延伸区块数（旗面颜色只影响外观）</span>
      <label class="int-list-bulk">
        <span>统一设为</span>
        <input v-model.number="bulk" type="number" :min="min" :max="max" step="1" title="把这个值应用到全部 16 种颜色" />
      </label>
      <button class="ghost" type="button" @click="applyAll">应用到全部</button>
    </div>
    <div class="int-list-grid">
      <label v-for="(dye, index) in palette" :key="dye.id" class="int-list-tile" :title="dye.name + '（颜色 ' + index + '）'">
        <span class="int-list-swatch" :style="{ background: dye.hex }"></span>
        <input
          type="number"
          :min="min"
          :max="max"
          step="1"
          :value="values[index] ?? min"
          :aria-label="dye.name + ' 保护距离（区块）'"
          @input="(event) => updateValue(index, event.target.value)"
        />
      </label>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import { DYE_PALETTE } from './palette.js';

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  min: { type: Number, default: 1 },
  max: { type: Number, default: 32 },
  defaults: { type: Array, default: () => [] },
});
const emit = defineEmits(['update:modelValue']);

const palette = DYE_PALETTE;
const min = computed(() => props.min);
const max = computed(() => props.max);
const values = computed(() => props.modelValue);
const bulk = ref(props.modelValue?.[0] ?? props.defaults?.[0] ?? 1);

function clamp(value) {
  const parsed = Math.round(Number(value));
  if (!Number.isFinite(parsed)) return min.value;
  return Math.max(min.value, Math.min(max.value, parsed));
}

function updateValue(index, raw) {
  const next = palette.map((_, i) => (i === index ? clamp(raw) : clamp(values.value[i])));
  emit('update:modelValue', next);
}

function applyAll() {
  const value = clamp(bulk.value);
  emit('update:modelValue', palette.map(() => value));
}
</script>
