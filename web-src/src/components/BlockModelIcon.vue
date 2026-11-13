<template>
  <canvas ref="canvas" class="block-model-icon" :width="size" :height="size" aria-hidden="true" />
</template>

<script setup>
import { onMounted, ref, watch } from 'vue';

const props = defineProps({
  kind: { type: String, default: 'chest' },
  axis: { type: String, default: 'x' },
  size: { type: Number, default: 96 },
});

const canvas = ref(null);
const cache = new Map();

function loadImage(src) {
  return new Promise((resolve) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => resolve(null);
    image.src = src;
  });
}

function faceRects(u, v, w, h, d) {
  return {
    right: [u, v + d, d, h],
    front: [u + d, v + d, w, h],
    left: [u + d + w, v + d, d, h],
    back: [u + d + w + d, v + d, w, h],
    top: [u + d, v, w, d],
    bottom: [u + d + w, v, w, d],
  };
}

function drawFace(ctx, image, rect, points) {
  const [sx, sy, sw, sh] = rect;
  if (sw <= 0 || sh <= 0) return;
  const p00 = points[0];
  const p10 = points[1];
  const p01 = points[3];
  ctx.save();
  ctx.setTransform(
    (p10.x - p00.x) / sw, (p10.y - p00.y) / sw,
    (p01.x - p00.x) / sh, (p01.y - p00.y) / sh,
    p00.x, p00.y,
  );
  ctx.drawImage(image, sx, sy, sw, sh, 0, 0, sw, sh);
  ctx.restore();
}

function render() {
  const el = canvas.value;
  if (!el) return;
  const key = props.kind + ':' + props.axis;
  if (cache.has(key)) {
    el.getContext('2d').drawImage(cache.get(key), 0, 0, el.width, el.height);
    return;
  }
  const textureName = props.kind.includes('trapped') ? 'trapped' : 'normal';
  const leftSrc = '/ui/chest_' + textureName + '_left.png';
  const rightSrc = '/ui/chest_' + textureName + '_right.png';
  Promise.all([loadImage(leftSrc), loadImage(rightSrc)]).then(([left, right]) => {
    const out = document.createElement('canvas');
    out.width = props.size;
    out.height = props.size;
    const ctx = out.getContext('2d');
    if (!left || !right) return;
    const W = props.size;
    const H = props.size;
    const angle = Math.PI / 6;
    const cos = Math.cos(angle);
    const sin = 0.5;
    const project = (x, y, z) => {
      if (props.axis === 'z') [x, z] = [z, x];
      return { x: (x - z) * cos, y: (x + z) * sin - y };
    };
    const boxes = [];
    const addBox = (image, x0, y0, z0, w, h, d, u, v) => {
      const rects = faceRects(u, v, w, h, d);
      boxes.push({ image, x0, y0, z0, w, h, d, rects });
    };
    addBox(left, -15, 0, -7, 15, 10, 14, 0, 19);
    addBox(left, -15, 10, -7, 15, 5, 14, 0, 0);
    addBox(right, 0, 0, -7, 15, 10, 14, 0, 19);
    addBox(right, 0, 10, -7, 15, 5, 14, 0, 0);

    const points = [];
    for (const box of boxes) {
      for (const x of [box.x0, box.x0 + box.w]) {
        for (const y of [box.y0, box.y0 + box.h]) {
          for (const z of [box.z0, box.z0 + box.d]) points.push(project(x, y, z));
        }
      }
    }
    const minX = Math.min(...points.map((p) => p.x));
    const maxX = Math.max(...points.map((p) => p.x));
    const minY = Math.min(...points.map((p) => p.y));
    const maxY = Math.max(...points.map((p) => p.y));
    const pad = 4;
    const scale = Math.min((W - pad * 2) / (maxX - minX), (H - pad * 2) / (maxY - minY));
    const ox = pad + (W - pad * 2 - (maxX - minX) * scale) / 2 - minX * scale;
    const oy = pad + (H - pad * 2 - (maxY - minY) * scale) / 2 - minY * scale;
    const map = (point) => ({ x: point.x * scale + ox, y: point.y * scale + oy });

    ctx.imageSmoothingEnabled = false;
    for (const box of boxes) {
      const { x0, y0, z0, w, h, d, rects, image } = box;
      const x1 = x0 + w;
      const y1 = y0 + h;
      const z1 = z0 + d;
      const front = [project(x0, y0, z1), project(x1, y0, z1), project(x1, y1, z1), project(x0, y1, z1)].map(map);
      const rightFace = [project(x1, y0, z0), project(x1, y0, z1), project(x1, y1, z1), project(x1, y1, z0)].map(map);
      const top = [project(x0, y1, z0), project(x1, y1, z0), project(x1, y1, z1), project(x0, y1, z1)].map(map);
      drawFace(ctx, image, rects.front, front);
      drawFace(ctx, image, rects.right, rightFace);
      drawFace(ctx, image, rects.top, top);
      // 锁扣：原版 ChestModel 的 lock 是独立小方块，单独贴在前脸中央。
      const lx0 = x0 + w / 2 - 2;
      const lx1 = lx0 + 4;
      const ly0 = y0 + 5;
      const ly1 = ly0 + 2.5;
      const lz = z1 + 0.05;
      const latch = [
        project(lx0, ly0, lz), project(lx1, ly0, lz),
        project(lx1, ly1, lz), project(lx0, ly1, lz),
      ].map(map);
      drawFace(ctx, image, [0, 0, 7, 4], latch);
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    cache.set(key, out);
    el.getContext('2d').drawImage(out, 0, 0);
  });
}

onMounted(render);
watch(() => [props.kind, props.axis, props.size], render);
</script>

<style scoped>
.block-model-icon {
  display: block;
  width: 100%;
  height: 100%;
  image-rendering: pixelated;
}
</style>
