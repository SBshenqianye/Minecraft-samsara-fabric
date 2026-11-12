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
            :key="'skin-' + face.name"
            class="pm3d-face pm3d-skin"
            :class="'face-' + face.name"
            :style="faceStyle(part, face)"
          ></div>
          <template v-for="(layer, li) in armorLayers(part.name)" :key="'armor-' + li">
            <div
              v-for="face in part.faces"
              :key="'a' + li + '-' + face.name"
              class="pm3d-face pm3d-armor"
              :class="'face-' + face.name"
              :style="armorFaceStyle(part, face, layer)"
            ></div>
          </template>
        </div>
      </div>
      <div class="pm3d-shadow"></div>
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
  transform: 'rotateX(' + rotX.value + 'deg) rotateY(' + rotY.value + 'deg) scale(1.4)',
}));

// 皮肤 64x64 箱型 UV；26.3 盔甲贴图 (64x32) 沿用同布局，
// 左臂/左腿镜像右臂/右腿（v 不超过 32）。
const UV = {
  head: { front: [8, 8], right: [0, 8], left: [16, 8], back: [24, 8], top: [8, 0], bottom: [16, 0] },
  body: { front: [20, 20], right: [16, 20], left: [28, 20], back: [32, 20], top: [20, 16], bottom: [28, 16] },
  'arm-right': { front: [44, 20], right: [40, 20], left: [48, 20], back: [52, 20], top: [44, 16], bottom: [48, 16] },
  'arm-left': { front: [44, 20], right: [40, 20], left: [48, 20], back: [52, 20], top: [44, 16], bottom: [48, 16] },
  'leg-right': { front: [4, 20], right: [0, 20], left: [8, 20], back: [12, 20], top: [4, 16], bottom: [8, 16] },
  'leg-left': { front: [4, 20], right: [0, 20], left: [8, 20], back: [12, 20], top: [4, 16], bottom: [8, 16] },
};

function makePart(name, w, h, d, x, y) {
  const uv = UV[name];
  return {
    name, w, h, d, x, y,
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
  makePart('head', 8, 8, 8, 0, -12),
  makePart('body', 8, 12, 4, 0, -2),
  makePart('arm-right', 4, 12, 4, -6, -2),
  makePart('arm-left', 4, 12, 4, 6, -2),
  makePart('leg-right', 4, 12, 4, -2, 10),
  makePart('leg-left', 4, 12, 4, 2, 10),
];

function partStyle(part) {
  return {
    width: part.w * U + 'px',
    height: part.h * U + 'px',
    marginLeft: -(part.w * U / 2) + 'px',
    marginTop: -(part.h * U / 2) + 'px',
    transform: 'translate3d(' + (part.x * U) + 'px,' + (part.y * U) + 'px,0)'
      + ' rotateX(var(--rx, 0deg)) rotateZ(var(--rz, 0deg))',
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

// 26.3 的 layer_1 / layer_2 改名为 equipment/humanoid*，UV 不变。
const MATERIAL = [
  ['turtle', 'turtle_scute'], ['netherite', 'netherite'], ['diamond', 'diamond'],
  ['golden', 'gold'], ['gold', 'gold'], ['chainmail', 'chainmail'],
  ['leather', 'leather'], ['copper', 'copper'], ['iron', 'iron'],
];

function materialOf(item) {
  const id = String(item?.id || '').replace('minecraft:', '');
  for (const [prefix, name] of MATERIAL) {
    if (id.startsWith(prefix)) return name;
  }
  return 'iron';
}

function armorTex(item, prefix) {
  return '/armor/' + prefix + '_' + materialOf(item) + '.png';
}

function armorLayers(name) {
  const a = props.armor || {};
  const out = [];
  if (name === 'head' && a.helmet) out.push({ tex: armorTex(a.helmet, 'humanoid'), z: 2 });
  if ((name === 'body' || name.startsWith('arm-')) && a.chestplate) {
    out.push({ tex: armorTex(a.chestplate, 'humanoid'), z: 2 });
  }
  if (name.startsWith('leg-')) {
    if (a.leggings) out.push({ tex: armorTex(a.leggings, 'humanoid_leggings'), z: 2 });
    if (a.boots) out.push({ tex: armorTex(a.boots, 'humanoid'), z: 3 });
  }
  return out;
}

function armorFaceStyle(part, face, layer) {
  const [ux, uy] = UV[part.name][face.name];
  return {
    width: face.fw * U + 'px',
    height: face.fh * U + 'px',
    marginLeft: -(face.fw * U / 2) + 'px',
    marginTop: -(face.fh * U / 2) + 'px',
    backgroundImage: "url('" + layer.tex + "')",
    backgroundSize: 64 * U + 'px ' + 32 * U + 'px',
    backgroundPosition: -(ux * U) + 'px ' + -(uy * U) + 'px',
    transform: face.transform + ' translateZ(' + layer.z + 'px)',
  };
}

function startDrag(event) {
  if (event.button !== 0) return;
  drag = { x: event.clientX, y: event.clientY, rx: rotX.value, ry: rotY.value };
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
  window.removeEventListener('pointermove', onDrag);
}

function resetView() {
  rotX.value = -6;
  rotY.value = 24;
}

watch(() => [props.uuid, props.name], resetView);
onBeforeUnmount(() => window.removeEventListener('pointermove', onDrag));
</script>

<style scoped>
.pm3d {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  width: 176px;
  user-select: none;
}

.pm3d.offline { opacity: .94; }

.pm3d-stage {
  position: relative;
  display: grid;
  place-items: center;
  width: 176px;
  height: 230px;
  perspective: 720px;
  cursor: grab;
  touch-action: none;
  background: linear-gradient(#c6c6c6, #8b8b8b);
  border: 2px solid #373737;
  border-radius: 4px;
  overflow: hidden;
}

.pm3d-stage:active { cursor: grabbing; }

.pm3d-shadow {
  position: absolute;
  bottom: 12px;
  left: 50%;
  width: 96px;
  height: 16px;
  transform: translateX(-50%);
  border-radius: 50%;
  background: radial-gradient(ellipse, rgba(0, 0, 0, .45), transparent 70%);
  pointer-events: none;
}

.pm3d-model {
  position: relative;
  z-index: 1;
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

.pm3d-skin { background-color: #a8a8a8; }
.pm3d-armor { opacity: .94; }

/* 原版模型有方向光：侧面压暗、顶面提亮，避免六个面糊成一片。 */
.face-right, .face-left { filter: brightness(.78); }
.face-back { filter: brightness(.86); }
.face-top { filter: brightness(1.14); }
.face-bottom { filter: brightness(.66); }

.pm3d-face { box-shadow: inset 0 0 0 1px rgba(0, 0, 0, .18); }

.part-arm-left, .part-arm-right, .part-leg-left, .part-leg-right {
  transform-origin: 50% 0;
}

.pose-wave .part-arm-left { --rz: -145deg; }
.pose-wave .part-arm-right { --rz: 8deg; }
.pose-run .part-arm-left { --rx: 38deg; }
.pose-run .part-arm-right { --rx: -38deg; }
.pose-run .part-leg-left { --rx: -28deg; }
.pose-run .part-leg-right { --rx: 28deg; }
.pose-sneak .part-leg-left, .pose-sneak .part-leg-right { --rx: -32deg; }
.pose-sneak .part-body { --rx: 12deg; }

.pm3d-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 3px;
  width: 176px;
}

.pm3d-tools .spacer { flex: 1; }

.pm3d-tools button {
  padding: 2px 6px;
  border: 1px solid #555;
  border-radius: 3px;
  background: #c6c6c6;
  color: #2b2b2b;
  font-size: 10px;
  cursor: pointer;
}

.pm3d-tools button.active {
  background: #4a7f3f;
  border-color: #2f5a26;
  color: #fff;
}
</style>
