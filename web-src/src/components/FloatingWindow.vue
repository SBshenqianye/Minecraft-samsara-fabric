<template>
  <section
    class="floating-window"
    :class="{ pinned, active }"
    :style="style"
    @pointerdown="$emit('focus')"
  >
    <header class="fw-head" @pointerdown="startDrag">
      <div class="fw-title">
        <strong>{{ title }}</strong>
        <span v-if="subtitle" class="fw-sub">{{ subtitle }}</span>
      </div>
      <span class="spacer"></span>
      <button
        type="button"
        class="fw-button"
        :class="{ on: pinned }"
        :title="pinned ? '取消固定' : '固定在最前'"
        :aria-label="pinned ? '取消固定' : '固定在最前'"
        @pointerdown.stop
        @click.stop="$emit('pin')"
      >⌖</button>
      <button
        type="button"
        class="fw-button"
        title="最小化到右侧"
        aria-label="最小化"
        @pointerdown.stop
        @click.stop="$emit('minimize')"
      >–</button>
      <button
        type="button"
        class="fw-button"
        title="关闭"
        aria-label="关闭"
        @pointerdown.stop
        @click.stop="$emit('close')"
      >×</button>
    </header>
    <div class="fw-body">
      <slot />
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';

const props = defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  width: { type: Number, default: 760 },
  height: { type: Number, default: 640 },
  x: { type: Number, default: 80 },
  y: { type: Number, default: 120 },
  z: { type: Number, default: 100 },
  pinned: { type: Boolean, default: false },
  active: { type: Boolean, default: false },
});
defineEmits(['close', 'minimize', 'pin', 'focus']);

const left = ref(props.x);
const top = ref(props.y);
let drag = null;

watch(() => props.x, (value) => { if (!drag) left.value = value; });
watch(() => props.y, (value) => { if (!drag) top.value = value; });

const style = computed(() => ({
  left: left.value + 'px',
  top: top.value + 'px',
  width: Math.max(420, props.width) + 'px',
  height: Math.max(280, props.height) + 'px',
  zIndex: props.z,
}));

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function startDrag(event) {
  if (event.button !== 0) return;
  if (event.target.closest('button')) return;
  drag = {
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    left: left.value,
    top: top.value,
  };
  window.addEventListener('pointermove', onDrag);
  window.addEventListener('pointerup', endDrag, { once: true });
}

function onDrag(event) {
  if (!drag) return;
  const maxLeft = Math.max(8, window.innerWidth - 260);
  const maxTop = Math.max(8, window.innerHeight - 120);
  left.value = clamp(drag.left + event.clientX - drag.startX, 8, maxLeft);
  top.value = clamp(drag.top + event.clientY - drag.startY, 8, maxTop);
}

function endDrag() {
  drag = null;
  window.removeEventListener('pointermove', onDrag);
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onDrag);
});
</script>

<style scoped>
.floating-window {
  position: fixed;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--line-strong);
  border-radius: 12px;
  background: var(--panel);
  box-shadow: 0 18px 48px rgba(0, 0, 0, .55);
}

.floating-window.active {
  border-color: color-mix(in srgb, var(--accent) 62%, var(--line-strong));
}

.floating-window.pinned {
  box-shadow: 0 18px 48px rgba(0, 0, 0, .55), 0 0 0 1px var(--accent);
}

.fw-head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 9px;
  border-bottom: 1px solid var(--line);
  background: var(--panel-soft);
  cursor: move;
  user-select: none;
  touch-action: none;
}

.fw-title {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 8px;
}

.fw-title strong {
  font-size: 12px;
}

.fw-sub {
  overflow: hidden;
  color: var(--muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fw-button {
  width: 24px;
  height: 22px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
}

.fw-button:hover {
  border-color: var(--line-strong);
  color: var(--fg);
}

.fw-button.on {
  border-color: var(--accent);
  color: var(--accent);
}

.fw-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 10px;
}
</style>
