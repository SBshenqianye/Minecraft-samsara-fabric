<template>
  <div class="pm3d" :class="{ offline: !online }">
    <div class="pm3d-stage" @pointerdown="startDrag">
      <div class="pm3d-model" :class="'pose-' + pose" :style="modelStyle">
        <div
          v-for="part in parts"
          :key="part.name"
          class="pm3d-part"
          :class="'part-' + part.name"
          :style="partStyle(part)"
        >
          <div
            v-for="face in part.faces"
            :key="face.name"
            class="pm3d-face"
            :style="faceStyle(part, face)"
          ></div>
          <img
            v-if="part.armor && armorFor(part.armor)"
            class="pm3d-armor"
            :class="'armor-' + part.armor"
            :src="'/items/' + armorFor(part.armor).icon + '.png'"
            :alt="armorFor(part.armor).name || ''"
            draggable="false"
            @error="$event.target.style.display = 'none'"
          />
        </div>
      </div>
    </div>
    <div class="pm3d-tools">
      <button
        v-for="option in poses"
        :key="option.id"
        type="button"
        :class="{ active: pose === option.id }"
        @click="pose = option.id"
      >{{ option.label }}</button>
      <span class="spacer"></span>
      <button type="button" title="重置视角" @click="resetView">复位</button>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';

const props = defineProps({
  uuid: { type: String, default: '' },
  name: { type: String, default: '' },
  online: { type: Boolean, default: false },
  armor: { type: Object, default: () => ({}) },
});

const U = 4;
const rotX = ref(-6);
const rotY = ref(24);
const pose = ref('stand');
const dragging = ref(false);
let drag = null;

const poses = [
  { id: 'stand', label: '站立' },
  { id: 'wave', label: '挥手' },
  { id: 'sneak', label: '潜行' },
  { id: 'run', label: '奔跑' },
];

const query = computed(() => {
  const params = new URLSearchParams();
  if (props.uuid) params.set('uuid', props.uuid);
  if (props.name) params.set('name', props.name);
  return params.toString();
});
const skinSrc = computed(() => '/api/user/map/avatar?' + query.value);
const modelStyle = computed(() => ({
  '--skin': "url('" + skinSrc.value + "')",
  transform: 'rotateX(' + rotX.value + 'deg) rotateY(' + rotY.value + 'deg)',
}));

const SKIN = {
  head: {
    front: [8, 8, 8, 8], right: [0, 8, 8, 8], left: [16, 8, 8, 8],
    back: [24, 8, 8, 8], top: [8, 0, 8, 8], bottom: [16, 0, 8, 8],
  },
  body: {
    front: [20, 20, 8, 12], right: [16, 20, 4, 12], left: [28, 20, 4, 12],
    back: [32, 20, 8, 12], top: [20, 16, 8, 4], bottom: [28, 16, 8, 4],
  },
  armRight: {
    front: [44, 20, 4, 12], right: [40, 20, 4, 12], left: [48, 20, 4, 12],
    back: [52, 20, 4, 12], top: [44, 16, 4, 4], bottom: [48, 16, 4, 4],
  },
  armLeft: {
    front: [36, 52, 4, 12], right: [32, 52, 4, 12], left: [40, 52, 4, 12],
    back: [44, 52, 4, 12], top: [36, 48, 4, 4], bottom: [40, 48, 4, 4],
  },
  legRight: {
    front: [4, 20, 4, 12], right: [0, 20, 4, 12], left: [8, 20, 4, 12],
    back: [12, 20, 4, 12], top: [4, 16, 4, 4], bottom: [8, 16, 4, 4],
  },
  legLeft: {
    front: [20, 52, 4, 12], right: [16, 52, 4, 12], left: [24, 52, 4, 12],
    back: [28, 52, 4, 12], top: [20, 48, 4, 4], bottom: [24, 48, 4, 4],
  },
};

function makePart(name, w, h, d, x, y, armor, uv) {
  return {
    name, w, h, d, x, y, armor,
    faces: [
      { name: 'front', fw: w, fh: h, uv: uv.front, transform: 'translateZ(' + (d * U / 2) + 'px)' },
      { name: 'back', fw: w, fh: h, uv: uv.back, transform: 'rotateY(180deg) translateZ(' + (d * U / 2) + 'px)' },
      { name: 'right', fw: d, fh: h, uv: uv.right, transform: 'rotateY(90deg) translateZ(' + (w * U / 2) + 'px)' },
      { name: 'left', fw: d, fh: h, uv: uv.left, transform: 'rotateY(-90deg) translateZ(' + (w * U / 2) + 'px)' },
      { name: 'top', fw: w, fh: d, uv: uv.top, transform: 'rotateX(90deg) translateZ(' + (h * U / 2) + 'px)' },
      { name: 'bottom', fw: w, fh: d, uv: uv.bottom, transform: 'rotateX(-90deg) translateZ(' + (h * U / 2) + 'px)' },
    ],
  };
}

const parts = [
  makePart('head', 8, 8, 8, 0, -12, 'helmet', SKIN.head),
  makePart('body', 8, 12, 4, 0, -2, 'chestplate', SKIN.body),
  makePart('arm-right', 4, 12, 4, -6, -2, null, SKIN.armRight),
  makePart('arm-left', 4, 12, 4, 6, -2, null, SKIN.armLeft),
  makePart('leg-right', 4, 12, 4, -2, 10, 'leggings', SKIN.legRight),
  makePart('leg-left', 4, 12, 4, 2, 10, 'boots', SKIN.legLeft),
];

function partStyle(part) {
  return {
    width: part.w * U + 'px',
    height: part.h * U + 'px',
    marginLeft: -(part.w * U / 2) + 'px',
    marginTop: -(part.h * U / 2) + 'px',
    transform: 'translate3d(' + (part.x * U) + 'px,' + (part.y * U) + 'px,0) rotateX(var(--rx, 0deg)) rotateZ(var(--rz, 0deg))',
  };
}

function faceStyle(part, face) {
  const [ux, uy] = face.uv;
  return {
    width: face.fw * U + 'px',
    height: face.fh * U + 'px',
    marginLeft: -(face.fw * U / 2) + 'px',
    marginTop: -(face.fh * U / 2) + 'px',
    backgroundImage: 'var(--skin)',
    backgroundSize: 64 * U + 'px ' + 64 * U + 'px',
    backgroundPosition: -(ux * U) + 'px ' + -(uy * U) + 'px',
    transform: face.transform,
  };
}

function armorFor(kind) {
  return props.armor?.[kind] || null;
}

function startDrag(event) {
  if (event.button !== 0) return;
  drag = { x: event.clientX, y: event.clientY, rx: rotX.value, ry: rotY.value };
  dragging.value = true;
  window.addEventListener('pointermove', onDrag);
  window.addEventListener('pointerup', endDrag, { once: true });
}

function onDrag(event) {
  if (!drag) return;
  rotY.value = drag.ry + (event.clientX - drag.x) * 0.55;
  rotX.value = Math.max(-38, Math.min(38, drag.rx - (event.clientY - drag.y) * 0.35));
}

function endDrag() {
  drag = null;
  dragging.value = false;
  window.removeEventListener('pointermove', onDrag);
}

function resetView() {
  rotX.value = -6;
  rotY.value = 24;
}

watch(() => [props.uuid, props.name], () => {
  resetView();
});

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onDrag);
});
</script>

<style scoped>
.pm3d {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  width: 176px;
  user-select: none;
}

.pm3d.offline {
  opacity: .92;
}

.pm3d-stage {
  display: grid;
  place-items: center;
  width: 176px;
  height: 220px;
  perspective: 720px;
  cursor: grab;
  touch-action: none;
}

.pm3d-stage:active {
  cursor: grabbing;
}

.pm3d-model {
  position: relative;
  width: 0;
  height: 0;
  transform-style: preserve-3d;
  transition: transform .08s linear;
}

.pm3d-part {
  position: absolute;
  left: 50%;
  top: 50%;
  transform-style: preserve-3d;
}

.pm3d-face {
  position: absolute;
  left: 50%;
  top: 50%;
  background-repeat: no-repeat;
  image-rendering: pixelated;
  backface-visibility: hidden;
}

.part-arm-left,
.part-arm-right,
.part-leg-left,
.part-leg-right {
  transform-origin: 50% 0;
}

.pose-wave .part-arm-left {
  --rz: -145deg;
}

.pose-wave .part-arm-right {
  --rz: 8deg;
}

.pose-run .part-arm-left {
  --rx: 38deg;
}

.pose-run .part-arm-right {
  --rx: -38deg;
}

.pose-run .part-leg-left {
  --rx: -28deg;
}

.pose-run .part-leg-right {
  --rx: 28deg;
}

.pose-sneak .part-leg-left,
.pose-sneak .part-leg-right {
  --rx: -32deg;
}

.pm3d-armor {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 2;
  width: 16px;
  height: 16px;
  margin: -8px 0 0 -8px;
  border: 1px solid rgba(255, 255, 255, .55);
  border-radius: 3px;
  background: rgba(20, 20, 20, .72);
  image-rendering: pixelated;
  transform: translateZ(18px);
  pointer-events: none;
}

.pm3d-armor.armor-chestplate {
  width: 20px;
  height: 20px;
  margin: -10px 0 0 -10px;
  transform: translateZ(10px);
}

.pm3d-armor.armor-boots {
  transform: translateZ(10px) translateY(26px);
}

.pm3d-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 3px;
  width: 176px;
}

.pm3d-tools .spacer {
  flex: 1;
}

.pm3d-tools button {
  padding: 2px 6px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--panel-soft);
  color: var(--muted);
  font-size: 10px;
  cursor: pointer;
}

.pm3d-tools button.active {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}
</style>
