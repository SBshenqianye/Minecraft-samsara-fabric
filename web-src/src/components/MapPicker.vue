<template>
  <div class="map-picker" :style="{ height: height + 'px' }">
    <div ref="viewport" class="map-viewport" @pointerdown="startPan" @wheel.prevent="onWheel">
      <div class="map-layer">
        <img
          v-for="tile in tiles"
          :key="tile.key"
          class="map-tile"
          :src="tile.src"
          :style="{ left: tile.left + 'px', top: tile.top + 'px', width: tile.size + 'px', height: tile.size + 'px' }"
          alt=""
          draggable="false"
          @load="tile.loaded = true"
        />
        <button
          v-for="marker in visibleMarkers"
          :key="marker.id"
          type="button"
          class="map-marker"
          :class="{ active: marker.id === selectedId, offline: marker.online === false }"
          :style="{ left: marker.left + 'px', top: marker.top + 'px' }"
          :title="marker.label + (marker.sub ? ' · ' + marker.sub : '')"
          @pointerdown.stop
          @click.stop="$emit('select', marker.id)"
        >
          <span class="marker-dot">{{ marker.kind === 'player' ? '人' : '箱' }}</span>
          <span class="marker-label">{{ marker.label }}</span>
        </button>
      </div>
      <div class="map-controls">
        <button type="button" class="ghost tiny" title="放大" @click.stop="zoom(1)">＋</button>
        <button type="button" class="ghost tiny" title="缩小" @click.stop="zoom(-1)">－</button>
        <button type="button" class="ghost tiny" title="回到标记" @click.stop="centerOnMarkers">◎</button>
      </div>
      <div class="map-foot">
        <span>{{ dimensionLabel }} · 缩放 1/{{ scale }} · 拖动平移</span>
        <span v-if="!tiles.length" class="muted">该区域没有地形缓存</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

const props = defineProps({
  markers: { type: Array, default: () => [] },
  dimension: { type: String, default: 'minecraft:overworld' },
  selectedId: { type: String, default: '' },
  height: { type: Number, default: 380 },
});
defineEmits(['select']);

const viewport = ref(null);
const model = ref(null);
const stackId = ref('');
const shortDim = ref('overworld');
const centerX = ref(0);
const centerZ = ref(0);
const scale = ref(2);
const viewW = ref(600);
const viewH = ref(380);
const tiles = ref([]);
const dragging = ref(false);
let dragStart = null;

const dimensionLabel = computed(() => {
  const row = findDimension();
  return row?.name || props.dimension;
});

const visibleMarkers = computed(() => {
  const ppb = 1 / scale.value;
  const left = centerX.value - viewW.value * scale.value / 2;
  const top = centerZ.value - viewH.value * scale.value / 2;
  return props.markers
    .filter((marker) => !marker.dimension || marker.dimension === props.dimension)
    .map((marker) => ({
      ...marker,
      kind: marker.kind || 'container',
      left: (Number(marker.x) - left) * ppb,
      top: (Number(marker.z) - top) * ppb,
    }))
    .filter((marker) => marker.left > -80 && marker.left < viewW.value + 80
      && marker.top > -40 && marker.top < viewH.value + 40);
});

function findDimension() {
  for (const stack of model.value?.stacks || []) {
    for (const dimension of stack.dimensions || []) {
      if (dimension.id === props.dimension) {
        return { ...dimension, stackId: stack.id };
      }
    }
  }
  return null;
}

async function loadModel() {
  try {
    const token = sessionStorage.getItem('samsara-token') || '';
    const response = await fetch('/api/map', { headers: { Authorization: 'Bearer ' + token } });
    if (!response.ok) return;
    model.value = await response.json();
    applyStack();
  } catch {
    // ignore
  }
}

function applyStack() {
  const found = findDimension();
  const stack = found
    ? (model.value.stacks || []).find((row) => row.id === found.stackId)
    : (model.value?.stacks || [])[0];
  stackId.value = stack?.id || model.value?.topStackId || '';
  const dimension = stack?.dimensions?.find((row) => row.id === props.dimension);
  shortDim.value = dimension?.dim || shortName(props.dimension);
  centerOnMarkers();
  rebuildTiles();
}

function shortName(dimension) {
  if (dimension.includes('the_nether') || dimension.endsWith('nether')) return 'the_nether';
  if (dimension.includes('the_end') || dimension.endsWith('end')) return 'the_end';
  return 'overworld';
}

function centerOnMarkers() {
  const rows = props.markers.filter((marker) =>
    !marker.dimension || marker.dimension === props.dimension);
  if (rows.length) {
    centerX.value = rows.reduce((sum, row) => sum + Number(row.x), 0) / rows.length;
    centerZ.value = rows.reduce((sum, row) => sum + Number(row.z), 0) / rows.length;
  } else {
    const bounds = findDimension()?.bounds;
    if (bounds && Number.isFinite(bounds.minX)) {
      centerX.value = (bounds.minX + bounds.maxX) / 2;
      centerZ.value = (bounds.minZ + bounds.maxZ) / 2;
    }
  }
  rebuildTiles();
}

function rebuildTiles() {
  if (!stackId.value) {
    tiles.value = [];
    return;
  }
  const ppb = 1 / scale.value;
  const size = 512 / scale.value;
  const left = centerX.value - viewW.value * scale.value / 2;
  const top = centerZ.value - viewH.value * scale.value / 2;
  const minRx = Math.floor(left / 512);
  const maxRx = Math.floor((left + viewW.value * scale.value) / 512);
  const minRz = Math.floor(top / 512);
  const maxRz = Math.floor((top + viewH.value * scale.value) / 512);
  const out = [];
  for (let rx = minRx; rx <= maxRx; rx++) {
    for (let rz = minRz; rz <= maxRz; rz++) {
      out.push({
        key: rx + ',' + rz + ',' + scale.value,
        src: '/api/user/map/tile?stack=' + encodeURIComponent(stackId.value)
          + '&dim=' + encodeURIComponent(props.dimension)
          + '&rx=' + rx + '&rz=' + rz + '&scale=' + scale.value + '&shade=1',
        left: (rx * 512 - left) * ppb,
        top: (rz * 512 - top) * ppb,
        size,
        loaded: false,
      });
    }
  }
  tiles.value = out;
}

function zoom(direction) {
  const values = [1, 2, 4, 8];
  const index = values.indexOf(scale.value);
  const next = Math.max(0, Math.min(values.length - 1, index + direction));
  if (values[next] === scale.value) return;
  scale.value = values[next];
  rebuildTiles();
}

function onWheel(event) {
  zoom(event.deltaY < 0 ? 1 : -1);
}

function startPan(event) {
  if (event.button !== 0) return;
  dragging.value = true;
  dragStart = { x: event.clientX, y: event.clientY, cx: centerX.value, cz: centerZ.value };
  viewport.value?.setPointerCapture?.(event.pointerId);
  window.addEventListener('pointermove', onPan);
  window.addEventListener('pointerup', endPan, { once: true });
}

function onPan(event) {
  if (!dragging.value || !dragStart) return;
  const ppb = 1 / scale.value;
  centerX.value = dragStart.cx - (event.clientX - dragStart.x) / ppb;
  centerZ.value = dragStart.cz - (event.clientY - dragStart.y) / ppb;
  rebuildTiles();
}

function endPan() {
  dragging.value = false;
  dragStart = null;
  window.removeEventListener('pointermove', onPan);
}

let observer = null;
onMounted(() => {
  loadModel();
  if (viewport.value) {
    viewW.value = viewport.value.clientWidth || 600;
    viewH.value = viewport.value.clientHeight || props.height;
    observer = new ResizeObserver(() => {
      if (!viewport.value) return;
      viewW.value = viewport.value.clientWidth;
      viewH.value = viewport.value.clientHeight;
      rebuildTiles();
    });
    observer.observe(viewport.value);
  }
});

onBeforeUnmount(() => {
  observer?.disconnect();
  window.removeEventListener('pointermove', onPan);
});

watch(() => props.dimension, () => {
  applyStack();
}, { immediate: false });

watch(() => props.markers, () => {
  rebuildTiles();
});
</script>

<style scoped>
.map-picker {
  position: relative;
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 12px;
  overflow: hidden;
  background: #10151c;
  touch-action: none;
}

.map-viewport {
  position: absolute;
  inset: 0;
  cursor: grab;
}

.map-viewport:active {
  cursor: grabbing;
}

.map-layer {
  position: absolute;
  inset: 0;
}

.map-tile {
  position: absolute;
  image-rendering: pixelated;
  user-select: none;
}

.map-marker {
  position: absolute;
  z-index: 4;
  display: flex;
  align-items: center;
  gap: 4px;
  transform: translate(-50%, -50%);
  padding: 3px 6px;
  border: 1px solid #f0c674;
  border-radius: 999px;
  background: rgba(18, 22, 30, .9);
  color: #ffe9b0;
  font-size: 10px;
  white-space: nowrap;
  cursor: pointer;
}

.map-marker.active {
  border-color: #58a6ff;
  background: rgba(20, 50, 90, .94);
  color: #d8ecff;
  box-shadow: 0 0 0 2px rgba(88, 166, 255, .35);
}

.map-marker.offline {
  border-color: #6e7681;
  color: #b7bec9;
}

.marker-dot {
  display: grid;
  place-items: center;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: #f0c674;
  color: #1b1b1b;
  font-size: 9px;
  font-weight: 700;
}

.map-marker.active .marker-dot {
  background: #58a6ff;
}

.map-marker.offline .marker-dot {
  background: #6e7681;
  color: #fff;
}

.marker-label {
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.map-controls {
  position: absolute;
  z-index: 6;
  right: 8px;
  top: 8px;
  display: flex;
  gap: 4px;
}

.map-controls button {
  width: 26px;
  height: 26px;
  padding: 0;
  border-color: rgba(255, 255, 255, .2);
  background: rgba(18, 22, 30, .85);
  color: #dbe4f0;
}

.map-foot {
  position: absolute;
  z-index: 6;
  left: 8px;
  bottom: 6px;
  display: flex;
  gap: 10px;
  padding: 3px 7px;
  border-radius: 6px;
  background: rgba(18, 22, 30, .72);
  color: #c7d2df;
  font-size: 10px;
  pointer-events: none;
}
</style>
