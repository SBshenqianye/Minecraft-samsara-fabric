<template>
  <div
    ref="bar"
    class="page-scrollbar"
    :class="{ visible }"
    @click="onTrackClick"
  >
    <div
      ref="thumb"
      class="page-scrollbar-thumb"
      @pointerdown="onPointerDown"
    ></div>
  </div>
</template>

<script setup>
import { usePageScrollbar } from '../composables/usePageScrollbar.js';

const { bar, thumb, visible, update, onPointerDown, onTrackClick } = usePageScrollbar();

defineExpose({ update });
</script>
