<template>
  <div class="pm3d" :class="{ offline: !online }">
    <div class="pm3d-stage" @pointerdown="startDrag">
      <canvas ref="canvasEl" class="pm3d-model"></canvas>
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
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

const props = defineProps({
  uuid: { type: String, default: '' },
  name: { type: String, default: '' },
  online: { type: Boolean, default: false },
  armor: { type: Object, default: () => ({}) },
});

const VIEW_W = 176;
const VIEW_H = 230;
const U = 5;                 // 每个皮肤像素对应的 CSS 像素
const CX = VIEW_W / 2;
const CY = VIEW_H / 2 - 4;

const rotX = ref(-6);        // 俯仰
const rotY = ref(24);        // 偏航
const pose = ref('stand');
const canvasEl = ref(null);
const poses = [
  { id: 'stand', label: '站立' },
  { id: 'wave', label: '挥手' },
  { id: 'sneak', label: '潜行' },
  { id: 'run', label: '奔跑' },
];

const query = computed(() => {
  // 与玩家列表头像使用完全相同的 ID 查找，避免同一玩家列表有皮肤、纸盒人却没有。
  const id = props.name || props.uuid;
  return id ? 'id=' + encodeURIComponent(id) : '';
});
const skinSrc = computed(() => '/api/user/map/avatar?' + query.value);

/*
 * Minecraft 64x64 皮肤 UV（skinview3d / 原版 HumanoidModel 同一套布局）。
 * 每个部位 6 个面，矩形为 [u, v, 宽, 高]，单位为皮肤像素。
 */
const UV_BASE = {
  head: { front: [8, 8, 8, 8], back: [24, 8, 8, 8], right: [0, 8, 8, 8], left: [16, 8, 8, 8], top: [8, 0, 8, 8], bottom: [16, 0, 8, 8] },
  body: { front: [20, 20, 8, 12], back: [32, 20, 8, 12], right: [16, 20, 4, 12], left: [28, 20, 4, 12], top: [20, 16, 8, 4], bottom: [28, 16, 8, 4] },
  'arm-right': { front: [44, 20, 4, 12], back: [52, 20, 4, 12], right: [40, 20, 4, 12], left: [48, 20, 4, 12], top: [44, 16, 4, 4], bottom: [48, 16, 4, 4] },
  'arm-left': { front: [36, 52, 4, 12], back: [44, 52, 4, 12], right: [32, 52, 4, 12], left: [40, 52, 4, 12], top: [36, 48, 4, 4], bottom: [40, 48, 4, 4] },
  'leg-right': { front: [4, 20, 4, 12], back: [12, 20, 4, 12], right: [0, 20, 4, 12], left: [8, 20, 4, 12], top: [4, 16, 4, 4], bottom: [8, 16, 4, 4] },
  'leg-left': { front: [4, 52, 4, 12], back: [12, 52, 4, 12], right: [0, 52, 4, 12], left: [8, 52, 4, 12], top: [4, 48, 4, 4], bottom: [8, 48, 4, 4] },
};

// 第二层皮肤（帽子/外套/袖子/裤腿），64x64 专用；旧 64x32 皮肤没有这一层。
const UV_OVERLAY = {
  head: { front: [40, 8, 8, 8], back: [56, 8, 8, 8], right: [32, 8, 8, 8], left: [48, 8, 8, 8], top: [40, 0, 8, 8], bottom: [48, 0, 8, 8] },
  body: { front: [20, 36, 8, 12], back: [32, 36, 8, 12], right: [16, 36, 4, 12], left: [28, 36, 4, 12], top: [20, 32, 8, 4], bottom: [28, 32, 8, 4] },
  'arm-right': { front: [44, 36, 4, 12], back: [52, 36, 4, 12], right: [40, 36, 4, 12], left: [48, 36, 4, 12], top: [44, 32, 4, 4], bottom: [48, 32, 4, 4] },
  'arm-left': { front: [52, 52, 4, 12], back: [60, 52, 4, 12], right: [48, 52, 4, 12], left: [56, 52, 4, 12], top: [52, 48, 4, 4], bottom: [56, 48, 4, 4] },
  'leg-right': { front: [4, 36, 4, 12], back: [12, 36, 4, 12], right: [0, 36, 4, 12], left: [8, 36, 4, 12], top: [4, 32, 4, 4], bottom: [8, 32, 4, 4] },
  'leg-left': { front: [20, 52, 4, 12], back: [28, 52, 4, 12], right: [16, 52, 4, 12], left: [24, 52, 4, 12], top: [20, 48, 4, 4], bottom: [24, 48, 4, 4] },
};

// 盔甲贴图是 64x32，左臂/左腿镜像右臂/右腿；旧皮肤同理。
const MIRRORED = {
  ...UV_BASE,
  'arm-left': UV_BASE['arm-right'],
  'leg-left': UV_BASE['leg-right'],
};

const BOX = {
  head: { w: 8, h: 8, d: 8, x: 0, y: -12, z: 0 },
  body: { w: 8, h: 12, d: 4, x: 0, y: -2, z: 0 },
  'arm-right': { w: 4, h: 12, d: 4, x: -6, y: -2, z: 0 },
  'arm-left': { w: 4, h: 12, d: 4, x: 6, y: -2, z: 0 },
  'leg-right': { w: 4, h: 12, d: 4, x: -2, y: 10, z: 0 },
  'leg-left': { w: 4, h: 12, d: 4, x: 2, y: 10, z: 0 },
};
const PARTS = Object.keys(BOX);
const FACES = ['front', 'back', 'right', 'left', 'top', 'bottom'];
const SHADE = { front: 1, back: .86, right: .78, left: .78, top: 1.14, bottom: .66 };

const MATERIAL = [
  ['turtle', 'turtle_scute'], ['netherite', 'netherite'], ['diamond', 'diamond'],
  ['golden', 'gold'], ['gold', 'gold'], ['chainmail', 'chainmail'],
  ['leather', 'leather'], ['copper', 'copper'], ['iron', 'iron'],
];

let skinImage = null;
let skinLegacy = false;
let skinRequest = 0;
const skinVersion = ref(0);
const armorCache = new Map();
const armorVersion = ref(0);
let drag = null;

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

function armorLayersFor(part) {
  const a = props.armor || {};
  const out = [];
  if (part === 'head' && a.helmet) out.push(armorTex(a.helmet, 'humanoid'));
  if ((part === 'body' || part.startsWith('arm-')) && a.chestplate) {
    out.push(armorTex(a.chestplate, 'humanoid'));
  }
  if (part.startsWith('leg-')) {
    if (a.leggings) out.push(armorTex(a.leggings, 'humanoid_leggings'));
    if (a.boots) out.push(armorTex(a.boots, 'humanoid'));
  }
  return out;
}

function armorImage(url) {
  let image = armorCache.get(url);
  if (!image) {
    image = new Image();
    image.onload = () => { armorVersion.value += 1; };
    image.src = url;
    armorCache.set(url, image);
  }
  return image.complete && image.naturalWidth ? image : null;
}

/** 皮肤接口拿不到时画一个 Steve 风格的占位皮肤，保证纸盒人始终有皮肤。 */
function makeDefaultSkin() {
  const canvas = document.createElement('canvas');
  canvas.width = 64;
  canvas.height = 64;
  const g = canvas.getContext('2d');
  const put = (x, y, w, h, color) => { g.fillStyle = color; g.fillRect(x, y, w, h); };
  const skin = '#c68642', hair = '#3f2a15', shirt = '#00a0a0', pants = '#3b3b96', shoe = '#4a4a4a';
  put(8, 0, 16, 8, hair);
  put(0, 8, 8, 8, hair); put(8, 8, 8, 8, skin);
  put(16, 8, 8, 8, skin); put(24, 8, 8, 8, hair);
  put(9, 10, 2, 2, '#ffffff'); put(13, 10, 2, 2, '#ffffff');
  put(10, 11, 1, 1, '#2b3a67'); put(14, 11, 1, 1, '#2b3a67');
  put(11, 13, 3, 1, '#8a4b3b');
  put(20, 16, 16, 4, shirt);
  put(16, 20, 24, 12, shirt);
  put(40, 16, 16, 16, skin);
  put(0, 16, 16, 12, pants); put(0, 28, 16, 4, shoe);
  put(32, 48, 16, 16, skin);
  put(0, 48, 16, 12, pants); put(0, 60, 16, 4, shoe);
  return canvas;
}

function loadSkin() {
  const request = ++skinRequest;
  const image = new Image();
  image.decoding = 'async';
  image.onload = () => {
    if (request !== skinRequest) return;
    skinImage = image;
    skinLegacy = image.naturalHeight < 64;
    skinVersion.value += 1;
    draw();
  };
  image.onerror = () => {
    if (request !== skinRequest) return;
    skinImage = makeDefaultSkin();
    skinLegacy = false;
    skinVersion.value += 1;
    draw();
  };
  image.src = skinSrc.value;
}

function expandBox(box, amount) {
  return {
    w: box.w + amount * 2, h: box.h + amount * 2, d: box.d + amount * 2,
    x: box.x, y: box.y, z: box.z,
  };
}

function facePoints(box, face) {
  const hw = box.w / 2, hh = box.h / 2, hd = box.d / 2;
  switch (face) {
    case 'front': return [[-hw, -hh, hd], [hw, -hh, hd], [-hw, hh, hd]];
    case 'back': return [[hw, -hh, -hd], [-hw, -hh, -hd], [hw, hh, -hd]];
    case 'right': return [[hw, -hh, hd], [hw, -hh, -hd], [hw, hh, hd]];
    case 'left': return [[-hw, -hh, -hd], [-hw, -hh, hd], [-hw, hh, -hd]];
    case 'top': return [[-hw, -hh, -hd], [hw, -hh, -hd], [-hw, -hh, hd]];
    default: return [[-hw, hh, hd], [hw, hh, hd], [-hw, hh, -hd]];
  }
}

const SNEAK_BODY_RX = -28.6;
const SNEAK_BODY_OY = 1.2;

/** 潜行时身体绕髋部前倾后，脖子/肩膀的世界坐标；让头和手臂真正接在躯干上。 */
function sneakNeckOffset() {
  const angle = SNEAK_BODY_RX * Math.PI / 180;
  const hipY = BOX.body.y + BOX.body.h / 2;
  const topY = BOX.body.y - BOX.body.h / 2;
  const dy = topY - hipY;
  const neckY = hipY + dy * Math.cos(angle) + SNEAK_BODY_OY;
  const neckZ = dy * Math.sin(angle);
  return {
    y: neckY - (BOX.head.y + BOX.head.h / 2),
    z: neckZ,
  };
}

function partTransform(part) {
  const box = BOX[part];
  let rx = 0, rz = 0, oy = 0, oz = 0;
  if (pose.value === 'wave') {
    if (part === 'arm-left') rz = -145;
    if (part === 'arm-right') rz = 8;
  } else if (pose.value === 'run') {
    if (part === 'arm-left') rx = 38;
    if (part === 'arm-right') rx = -38;
    if (part === 'leg-left') rx = -28;
    if (part === 'leg-right') rx = 28;
  } else if (pose.value === 'sneak') {
    // 原版 HumanoidModel：身体绕髋部前倾，头和手臂跟随躯干顶部（脖子/肩膀），
    // 手臂相对世界略向后垂，避免旧实现的“头颈断裂、手臂肩膀断裂”。
    const neck = sneakNeckOffset();
    if (part === 'body') {
      rx = SNEAK_BODY_RX;
      oy = SNEAK_BODY_OY;
    }
    if (part === 'head') {
      rx = -6;
      oy = neck.y;
      oz = neck.z;
    }
    if (part.startsWith('arm-')) {
      rx = -20;
      oy = neck.y;
      oz = neck.z;
    }
  }
  // 身体/头的枢轴在底部（髋部/脖子），四肢的枢轴在顶部（肩/胯）。
  const pivot = (part === 'body' || part === 'head')
    ? [box.x, box.y + box.h / 2, box.z]
    : [box.x, box.y - box.h / 2, box.z];
  return {
    rx: rx * Math.PI / 180,
    rz: rz * Math.PI / 180,
    pivot,
    oy,
    oz,
  };
}

function applyPart(point, box, tf) {
  let [x, y, z] = point;
  x += box.x; y += box.y; z += box.z;
  if (tf.rx || tf.rz) {
    let px = x - tf.pivot[0], py = y - tf.pivot[1], pz = z - tf.pivot[2];
    if (tf.rz) {
      const c = Math.cos(tf.rz), s = Math.sin(tf.rz);
      [px, py] = [px * c - py * s, px * s + py * c];
    }
    if (tf.rx) {
      const c = Math.cos(tf.rx), s = Math.sin(tf.rx);
      [py, pz] = [py * c - pz * s, py * s + pz * c];
    }
    x = px + tf.pivot[0]; y = py + tf.pivot[1]; z = pz + tf.pivot[2];
  }
  return [x, y + (tf.oy || 0), z + (tf.oz || 0)];
}

function rotateView(p, yaw, pitch) {
  const cy = Math.cos(yaw), sy = Math.sin(yaw);
  const x = p[0] * cy + p[2] * sy;
  const z = -p[0] * sy + p[2] * cy;
  const cx = Math.cos(pitch), sx = Math.sin(pitch);
  return [x, p[1] * cx - z * sx, p[1] * sx + z * cx];
}

const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const cross = (a, b) => [
  a[1] * b[2] - a[2] * b[1],
  a[2] * b[0] - a[0] * b[2],
  a[0] * b[1] - a[1] * b[0],
];
const project = (p) => [CX + p[0] * U, CY + p[1] * U];

/** 正交投影下每个面是平行四边形，用仿射变换把皮肤像素映射到画布。 */
function drawFace(ctx, face) {
  const [ux, uy, uw, uh] = face.uv;
  if (!face.img || uw <= 0 || uh <= 0) return;
  const s0 = face.s0, s1 = face.s1, s2 = face.s2;
  let a = (s1[0] - s0[0]) / uw;
  let b = (s1[1] - s0[1]) / uw;
  const c = (s2[0] - s0[0]) / uh;
  const d = (s2[1] - s0[1]) / uh;
  const origin = face.flip ? s1 : s0;
  if (face.flip) {
    a = -a;
    b = -b;
  }
  const e = origin[0] - a * ux - c * uy;
  const f = origin[1] - b * ux - d * uy;
  const s3 = [s1[0] + s2[0] - s0[0], s1[1] + s2[1] - s0[1]];
  ctx.save();
  ctx.imageSmoothingEnabled = false;
  ctx.beginPath();
  ctx.moveTo(s0[0], s0[1]);
  ctx.lineTo(s1[0], s1[1]);
  ctx.lineTo(s3[0], s3[1]);
  ctx.lineTo(s2[0], s2[1]);
  ctx.closePath();
  ctx.clip();
  if (face.shade !== 1) ctx.filter = 'brightness(' + face.shade + ')';
  ctx.transform(a, b, c, d, e, f);
  ctx.drawImage(face.img, 0, 0);
  ctx.restore();
}

function draw() {
  const canvas = canvasEl.value;
  if (!canvas) return;
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const width = Math.round(VIEW_W * dpr);
  const height = Math.round(VIEW_H * dpr);
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, VIEW_W, VIEW_H);
  if (!skinImage) return;

  const yaw = rotY.value * Math.PI / 180;
  const pitch = rotX.value * Math.PI / 180;
  const faces = [];

  const addPart = (part, uvMap, expand, img, bias, mirror = false) => {
    const box = BOX[part];
    const geom = expand ? expandBox(box, expand) : box;
    const tf = partTransform(part);
    for (const face of FACES) {
      const points = facePoints(geom, face).map((p) => applyPart(p, box, tf));
      const rotated = points.map((p) => rotateView(p, yaw, pitch));
      const normal = cross(sub(rotated[1], rotated[0]), sub(rotated[2], rotated[0]));
      if (normal[2] <= 0.0001) continue;
      // 皮肤 UV 的 right/left 是“角色自身”的左右；几何 +X 面是角色左侧。
      // 普通四肢取反；旧 64x32 皮肤的左侧肢体是右侧的镜像，取同侧 UV 并水平翻转。
      const uv = mirror
        ? (face === 'right' ? uvMap.right : face === 'left' ? uvMap.left : uvMap[face])
        : (face === 'right' ? uvMap.left : face === 'left' ? uvMap.right : uvMap[face]);
      faces.push({
        img,
        uv,
        flip: mirror,
        s0: project(rotated[0]),
        s1: project(rotated[1]),
        s2: project(rotated[2]),
        depth: (rotated[0][2] + rotated[1][2] + rotated[2][2]) / 3 + bias,
        shade: SHADE[face],
      });
    }
  };

  const baseUV = skinLegacy ? MIRRORED : UV_BASE;
  for (const part of PARTS) {
    addPart(part, baseUV[part], 0, skinImage, 0, skinLegacy && part.endsWith('-left'));
  }
  // 第二层皮肤用略大的盒子，透明区域自然露出底层。
  if (!skinLegacy) {
    for (const part of PARTS) addPart(part, UV_OVERLAY[part], 0.5, skinImage, 0.02);
  }
  for (const part of PARTS) {
    for (const url of armorLayersFor(part)) {
      const img = armorImage(url);
      // 盔甲贴图是 64x32 单侧布局，左侧肢体要把右腿/右臂镜像过去。
      if (img) addPart(part, MIRRORED[part], 0.9, img, 0.04, part.endsWith('-left'));
    }
  }

  faces.sort((a, b) => a.depth - b.depth);
  for (const face of faces) drawFace(ctx, face);
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

onMounted(() => {
  loadSkin();
  draw();
});

watch([rotX, rotY, pose], draw);
watch(() => props.armor, draw, { deep: true });
watch([skinVersion, armorVersion], draw);
watch(() => [props.uuid, props.name], () => {
  resetView();
  loadSkin();
});

onBeforeUnmount(() => {
  skinRequest += 1;
  window.removeEventListener('pointermove', onDrag);
});
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
  border: 2px solid #373737;
  border-radius: 4px;
  background: linear-gradient(#c6c6c6, #8b8b8b);
  overflow: hidden;
  cursor: grab;
  touch-action: none;
}

.pm3d-stage:active { cursor: grabbing; }

.pm3d-model {
  position: relative;
  z-index: 1;
  display: block;
  width: 176px;
  height: 230px;
}

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
