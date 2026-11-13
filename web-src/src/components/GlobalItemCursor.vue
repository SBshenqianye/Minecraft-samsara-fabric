<template>
  <Teleport to="body">
    <div
      v-if="itemCursor.item"
      ref="cursorEl"
      popover="manual"
      class="global-item-cursor workbench-floating-cursor"
      :style="{ left: pointerX + 'px', top: pointerY + 'px' }"
      aria-hidden="true"
    >
      <ItemIcon :item="itemCursor.item" size="lg" :show-count="true" />
    </div>
  </Teleport>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import ItemIcon from './ItemIcon.vue';
import { itemCursor } from '../composables/itemCursor.js';

const pointerX = ref(12);
const pointerY = ref(12);
const cursorEl = ref(null);

// Web 背包在 top layer；跟随光标的物品也必须进入 top layer，否则会被背包遮住。
// tooltip 显示后同样在 top layer，需要把光标重新提到 tooltip 之上。
watch(() => [!!itemCursor.item, itemCursor.raise || 0], async ([visible, raise]) => {
  await nextTick();
  const el = cursorEl.value;
  if (!el || typeof el.showPopover !== 'function') return;
  try {
    if (visible) {
      if (raise) {
        if (el.matches(':popover-open')) el.hidePopover();
        el.showPopover();
      } else if (!el.matches(':popover-open')) {
        el.showPopover();
      }
    } else if (el.matches(':popover-open')) {
      el.hidePopover();
    }
  } catch {
    // ignore
  }
}, { flush: 'post' });

function onPointerMove(event) {
  pointerX.value = event.clientX + 12;
  pointerY.value = event.clientY + 12;
}

onMounted(() => {
  window.addEventListener('pointermove', onPointerMove, { passive: true });
});

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onPointerMove);
});
</script>

<style scoped>
.global-item-cursor {
  position: fixed;
  z-index: 100000;
  pointer-events: none;
  filter: drop-shadow(0 6px 8px rgba(0, 0, 0, .45));
  transform: scale(1.08);
  transform-origin: top left;
}

.global-item-cursor[popover] {
  inset: auto;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
}
</style>
