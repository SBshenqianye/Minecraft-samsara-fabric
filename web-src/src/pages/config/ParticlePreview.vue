<template>
  <canvas ref="canvasEl" class="particle-canvas" :title="'粒子效果预览：' + name"></canvas>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { attachParticles } from './particles.js';

const props = defineProps({
  name: { type: String, default: '' },
  theme: { type: String, default: 'dark' },
});

const canvasEl = ref(null);

function attach() {
  if (!canvasEl.value || !props.name) return;
  attachParticles(canvasEl.value, props.name, { background: props.theme });
}

onMounted(() => requestAnimationFrame(attach));
watch(() => [props.name, props.theme], () => requestAnimationFrame(attach));
onBeforeUnmount(() => {
  if (canvasEl.value?.__samsaraRaf) cancelAnimationFrame(canvasEl.value.__samsaraRaf);
});
</script>
