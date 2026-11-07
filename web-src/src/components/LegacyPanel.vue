<template>
  <div ref="host" class="legacy-panel-host"></div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue';
import { importLegacyElement } from '../legacy/document.js';

const props = defineProps({
  panelId: { type: String, required: true },
});

const host = ref(null);
let panel = null;

onMounted(async () => {
  panel = await importLegacyElement(props.panelId);
  host.value?.appendChild(panel);
});

onUnmounted(() => {
  if (panel) {
    panel.remove();
    panel = null;
  }
});
</script>
