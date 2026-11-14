<template>
  <div class="world-stack-card-visual">
    <div class="world-stack-icon-frame">
      <img
        v-show="currentSrc"
        class="world-stack-icon-image"
        :src="currentSrc"
        :alt="(stack.label || stack.id || '世界栈') + ' 图标'"
        loading="lazy"
        decoding="async"
        referrerpolicy="no-referrer"
        @error="advance"
      />
      <div v-show="!currentSrc" class="world-stack-icon-placeholder">暂无图标</div>
      <span class="world-stack-icon-badge" :data-source="source">{{ label }}</span>
    </div>
    <div v-if="admin" class="world-stack-icon-tools">
      <button type="button" class="ghost" @click="$emit('upload', stack)">上传图片</button>
      <button
        v-if="stack.icon && stack.icon.custom"
        type="button"
        class="ghost danger"
        @click="$emit('delete', stack)"
      >恢复默认</button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import { stackIconLabel, stackMapTileUrl } from './model.js';

const props = defineProps({
  stack: { type: Object, required: true },
  admin: { type: Boolean, default: true },
});
defineEmits(['upload', 'delete']);

const candidates = computed(() => {
  const list = [];
  if (props.stack.icon?.url) {
    list.push({ url: props.stack.icon.url, source: props.stack.icon.source || 'upload' });
  }
  const mapUrl = stackMapTileUrl(props.stack);
  if (mapUrl) list.push({ url: mapUrl, source: 'map' });
  return list;
});

const index = ref(0);
const currentSrc = computed(() => candidates.value[index.value]?.url || '');
const source = computed(() => candidates.value[index.value]?.source || 'placeholder');
const label = computed(() => stackIconLabel(source.value));

watch(candidates, () => { index.value = 0; });

function advance() {
  if (index.value < candidates.value.length) index.value += 1;
}
</script>
