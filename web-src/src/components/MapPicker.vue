<template>
  <div class="map-picker" :style="{ height: height + 'px' }">
    <div ref="viewport" class="map-viewport" @pointerdown="startPan" @wheel.prevent="onWheel">
      <div class="map-layer">
        <template v-for="tile in tiles" :key="tile.key">
          <img
            v-if="tile.src"
            class="map-tile"
            :src="tile.src"
            :style="{ left: tile.left + 'px', top: tile.top + 'px', width: tile.size + 'px', height: tile.size + 'px' }"
            alt=""
            draggable="false"
          />
        </template>
        <svg
          v-if="trailsVisible && trailPaths.length"
          class="map-trails"
          :viewBox="'0 0 ' + viewW + ' ' + viewH"
          preserveAspectRatio="none"
        >
          <polyline
            v-for="line in trailPaths"
            :key="line.id"
            :points="line.points"
            :stroke="line.color"
          />
        </svg>
        <button
          v-for="cluster in visibleClusters"
          :key="cluster.id"
          type="button"
          class="map-marker"
          :class="{ active: cluster.active, offline: cluster.offline, player: cluster.player, clustered: cluster.markers.length > 1 }"
          :style="{ left: cluster.left + 'px', top: cluster.top + 'px' }"
          :title="cluster.title"
          @pointerdown.stop
          @click.stop="onMarkerClick(cluster)"
        >
          <template v-if="cluster.markers.length > 1">
            <span class="cluster-count">{{ cluster.markers.length }}</span>
            <span class="marker-label">{{ cluster.label }}</span>
          </template>
          <template v-else>
            <span v-if="cluster.marker.kind === 'player'" class="marker-head" :style="avatarStyle(cluster.marker)">
              <span v-if="!avatarUrls[cluster.marker.id]" class="marker-initial">{{ initialOf(cluster.marker) }}</span>
            </span>
            <ItemIcon
              v-else
              class="marker-item"
              :item="{ id: cluster.marker.icon || 'chest', name: cluster.marker.label, count: 1 }"
              size="sm"
              :show-count="false"
            />
            <span class="marker-label">{{ cluster.marker.label }}</span>
          </template>
        </button>
      </div>
      <div
        v-if="clusterPopup"
        class="cluster-popup"
        :style="{ left: clusterPopup.left + 'px', top: clusterPopup.top + 'px' }"
        @pointerdown.stop
        @click.stop
        @wheel.stop
      >
        <div class="cluster-popup-head">
          <strong>{{ clusterPopup.markers.length }} 个目标</strong>
          <span class="spacer"></span>
          <button
            class="ghost tiny"
            type="button"
            :class="{ on: clusterPinned }"
            :title="clusterPinned ? '取消保持' : '保持网格（不自动关闭）'"
            @click="clusterPinned = !clusterPinned"
          >{{ clusterPinned ? '保持中' : '保持' }}</button>
          <button class="ghost tiny" type="button" title="关闭" @click="closeClusterPopup">×</button>
        </div>
        <div
          class="cluster-grid"
          :style="{ gridTemplateColumns: 'repeat(' + clusterPopup.columns + ', minmax(0, 1fr))' }"
        >
          <button
            v-for="marker in clusterPopup.markers"
            :key="marker.id"
            type="button"
            class="cluster-cell"
            :class="{ active: marker.id === selectedId }"
            :title="marker.label + (marker.sub ? ' · ' + marker.sub : '')"
            @click="chooseClusterMarker(marker)"
          >
            <ItemIcon
              v-if="marker.kind !== 'player'"
              :item="{ id: marker.icon || 'chest', name: marker.label }"
              size="sm"
              :show-count="false"
            />
            <span v-else class="marker-head" :style="avatarStyle(marker)">
              <span v-if="!avatarUrls[marker.id]" class="marker-initial">{{ initialOf(marker) }}</span>
            </span>
            <span class="cluster-cell-text">
              <span class="cluster-cell-label">{{ marker.label }}</span>
              <span class="muted small">{{ marker.sub }}</span>
            </span>
          </button>
        </div>
      </div>
      <div class="map-controls" @pointerdown.stop @click.stop>
        <button type="button" class="ghost tiny" title="放大（滚轮向上）" @click.stop="zoom(-1)">＋</button>
        <button type="button" class="ghost tiny" title="缩小（滚轮向下）" @click.stop="zoom(1)">－</button>
        <button type="button" class="ghost tiny" title="回到标记" @click.stop="centerOnMarkers">◎</button>
        <button
          type="button"
          class="ghost tiny"
          :class="{ on: trailsVisible }"
          title="显示/隐藏玩家轨迹"
          @click.stop="trailsVisible = !trailsVisible"
        >轨迹</button>
      </div>
      <div class="map-foot">
        <span>{{ dimensionLabel }} · {{ scale }} 格/像素 · 拖动平移</span>
        <span v-if="trailPlayers.length" class="muted">玩家轨迹 {{ trailPlayers.length }} 人</span>
        <span v-else-if="!tiles.length" class="muted">该区域没有地形缓存</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import ItemIcon from './ItemIcon.vue';

const props = defineProps({
  markers: { type: Array, default: () => [] },
  dimension: { type: String, default: 'minecraft:overworld' },
  selectedId: { type: String, default: '' },
  height: { type: Number, default: 440 },
  showTrails: { type: Boolean, default: true },
});
const emit = defineEmits(['select']);

// 与世界地图 map.js 同一套缩放档位与「格/像素」口径；交互实现彼此独立。
const SCALES = [0.0625, 0.125, 0.25, 0.5, 1, 2, 4, 8, 16];
const MIN_SCALE = 0.0625;
const MAX_SCALE = 16;
const TRAIL_COLORS = ['#60a5fa', '#f0c674', '#7ad7a8', '#c792ea', '#f38ba8', '#89ddff'];

const viewport = ref(null);
const model = ref(null);
const stackId = ref('');
const shortDim = ref('overworld');
const centerX = ref(0);
const centerZ = ref(0);
const scale = ref(4);
const viewW = ref(600);
const viewH = ref(440);
const tiles = ref([]);
const tileCache = new Map();
let tilesRequest = 0;
const trailPlayers = ref([]);
const trailsVisible = ref(props.showTrails);
const avatarUrls = reactive({});
const avatarLoading = new Set();
const avatarCache = new Map();
const dragging = ref(false);
const centeredOnMarkers = ref(false);
let dragStart = null;
let trailTimer = null;
let observer = null;

const dimensionLabel = computed(() => {
  const row = findDimension();
  return row?.name || props.dimension;
});

function projection() {
  const left = centerX.value - viewW.value * scale.value / 2;
  const top = centerZ.value - viewH.value * scale.value / 2;
  return { left, top, ppb: 1 / scale.value };
}

const visibleMarkers = computed(() => {
  const { left, top, ppb } = projection();
  return props.markers
    .filter((marker) => !marker.dimension || marker.dimension === props.dimension)
    .map((marker) => ({
      ...marker,
      kind: marker.kind || 'container',
      // 取整像素，避免 transform: translate(-50%,-50%) 下的亚像素抖动。
      left: Math.round((Number(marker.x) - left) * ppb),
      top: Math.round((Number(marker.z) - top) * ppb),
    }))
    .filter((marker) => marker.left > -80 && marker.left < viewW.value + 80
      && marker.top > -40 && marker.top < viewH.value + 40);
});

const CLUSTER_DISTANCE = 52;
const clusterPopup = ref(null);
const clusterPinned = ref(false);

const visibleClusters = computed(() => {
  const source = visibleMarkers.value;
  const clusters = [];
  for (const marker of source) {
    let target = null;
    for (const cluster of clusters) {
      const dx = cluster.left - marker.left;
      const dy = cluster.top - marker.top;
      if (dx * dx + dy * dy <= CLUSTER_DISTANCE * CLUSTER_DISTANCE) {
        target = cluster;
        break;
      }
    }
    if (!target) {
      target = { left: marker.left, top: marker.top, markers: [] };
      clusters.push(target);
    }
    target.markers.push(marker);
    target.left = target.markers.reduce((sum, row) => sum + row.left, 0) / target.markers.length;
    target.top = target.markers.reduce((sum, row) => sum + row.top, 0) / target.markers.length;
  }
  return clusters.map((cluster) => {
    const rows = cluster.markers.slice().sort((a, b) =>
      (Number(a.z) - Number(b.z)) || (Number(a.x) - Number(b.x)));
    const first = rows[0];
    const everyPlayer = rows.every((row) => row.kind === 'player');
    const everyOffline = rows.every((row) => row.online === false);
    return {
      id: rows.map((row) => row.id).sort().join('|'),
      left: Math.round(cluster.left),
      top: Math.round(cluster.top),
      markers: rows,
      marker: first,
      active: rows.some((row) => row.id === props.selectedId),
      offline: everyOffline,
      player: everyPlayer,
      label: rows.length > 1
        ? (everyPlayer ? '玩家' : '容器')
        : first.label,
      title: rows.length > 1
        ? rows.length + ' 个目标，点击展开'
        : first.label + (first.sub ? ' · ' + first.sub : ''),
    };
  });
});

function onMarkerClick(cluster) {
  if (cluster.markers.length === 1) {
    emit('select', cluster.markers[0].id);
    return;
  }
  const columns = Math.min(3, Math.max(2, Math.ceil(Math.sqrt(cluster.markers.length))));
  const width = 380;
  const height = Math.min(380, 56 + Math.ceil(cluster.markers.length / columns) * 54);
  clusterPopup.value = {
    markers: cluster.markers,
    columns,
    left: Math.max(8, Math.min(cluster.left + 14, viewW.value - width - 8)),
    top: Math.max(8, Math.min(cluster.top + 14, viewH.value - height - 8)),
  };
}

function chooseClusterMarker(marker) {
  emit('select', marker.id);
  if (!clusterPinned.value) closeClusterPopup();
}

function closeClusterPopup() {
  clusterPopup.value = null;
  clusterPinned.value = false;
}

const trailPaths = computed(() => {
  if (!trailsVisible.value) return [];
  const { left, top, ppb } = projection();
  return trailPlayers.value
    .filter((player) => !player.dimensionId || player.dimensionId === props.dimension)
    .map((player, index) => {
      const points = (player.trail || [])
        .map((point) => Math.round((Number(point.x) - left) * ppb)
          + ',' + Math.round((Number(point.z) - top) * ppb))
        .join(' ');
      return {
        id: String(player.uuid || player.name || index),
        points,
        color: TRAIL_COLORS[index % TRAIL_COLORS.length],
      };
    })
    .filter((line) => line.points.split(' ').length > 1);
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

async function loadTrails() {
  try {
    const token = sessionStorage.getItem('samsara-token') || '';
    const response = await fetch('/api/map/player-trails?limit=400', {
      headers: { Authorization: 'Bearer ' + token },
    });
    if (!response.ok) return;
    const data = await response.json();
    if (Array.isArray(data.players)) trailPlayers.value = data.players;
  } catch {
    // 轨迹不可用时不打断地图
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
    centeredOnMarkers.value = true;
  } else {
    const bounds = findDimension()?.bounds;
    if (bounds && Number.isFinite(bounds.minX)) {
      centerX.value = (bounds.minX + bounds.maxX) / 2;
      centerZ.value = (bounds.minZ + bounds.maxZ) / 2;
    }
  }
  rebuildTiles();
}

// 瓦片先由 new Image() 预加载成功才进入 DOM；失败瓦片永不渲染 <img>，
// 因此缩放/平移时不会出现浏览器破图标志（世界地图用 canvas 也是同样效果）。
function rebuildTiles() {
  if (!stackId.value) {
    tiles.value = [];
    return;
  }
  const request = ++tilesRequest;
  const tileScale = Math.min(8, Math.max(1, scale.value));
  const { left, top, ppb } = projection();
  const size = 512 / scale.value;
  const minRx = Math.floor(left / 512);
  const maxRx = Math.floor((left + viewW.value * scale.value) / 512);
  const minRz = Math.floor(top / 512);
  const maxRz = Math.floor((top + viewH.value * scale.value) / 512);
  const out = [];
  for (let rx = minRx; rx <= maxRx; rx++) {
    for (let rz = minRz; rz <= maxRz; rz++) {
      const key = [stackId.value, props.dimension, rx, rz, tileScale, 1].join('|');
      const url = '/api/user/map/tile?stack=' + encodeURIComponent(stackId.value)
        + '&dim=' + encodeURIComponent(props.dimension)
        + '&rx=' + rx + '&rz=' + rz + '&scale=' + tileScale + '&shade=1';
      const cached = tileCache.get(key);
      out.push({
        key,
        src: cached && cached.ok ? cached.url : '',
        left: (rx * 512 - left) * ppb,
        top: (rz * 512 - top) * ppb,
        size,
      });
      if (cached && (cached.ok || Date.now() - cached.at < 60_000)) continue;
      const image = new Image();
      image.onload = () => {
        tileCache.set(key, { ok: true, url, at: Date.now() });
        if (request !== tilesRequest) return;
        const current = tiles.value.find((row) => row.key === key);
        if (current) current.src = url;
      };
      image.onerror = () => {
        tileCache.set(key, { ok: false, url, at: Date.now() });
      };
      image.src = url;
    }
  }
  tiles.value = out;
}

function setScale(next, px, py) {
  const clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, next));
  if (clamped === scale.value) return;
  const ax = px == null ? viewW.value / 2 : px;
  const ay = py == null ? viewH.value / 2 : py;
  const worldX = centerX.value + (ax - viewW.value / 2) * scale.value;
  const worldZ = centerZ.value + (ay - viewH.value / 2) * scale.value;
  scale.value = clamped;
  centerX.value = worldX - (ax - viewW.value / 2) * clamped;
  centerZ.value = worldZ - (ay - viewH.value / 2) * clamped;
  rebuildTiles();
}

function zoom(direction) {
  let index = SCALES.indexOf(scale.value);
  if (index < 0) index = SCALES.findIndex((value) => value >= scale.value);
  const next = Math.max(0, Math.min(SCALES.length - 1, index + direction));
  setScale(SCALES[next]);
}

function onWheel(event) {
  const rect = viewport.value?.getBoundingClientRect();
  const px = rect ? event.clientX - rect.left : viewW.value / 2;
  const py = rect ? event.clientY - rect.top : viewH.value / 2;
  const raw = event.deltaY < 0 ? scale.value / 2 : scale.value * 2;
  const snapped = SCALES.reduce((best, value) =>
    Math.abs(value - raw) < Math.abs(best - raw) ? value : best, SCALES[0]);
  setScale(snapped, px, py);
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

function initialOf(marker) {
  const name = String(marker.label || marker.name || '?').trim();
  return /^[A-Za-z0-9\u4e00-\u9fff]/.test(name) ? name.charAt(0).toUpperCase() : '?';
}

function avatarStyle(marker) {
  const url = avatarUrls[marker.id];
  if (!url) return {};
  return {
    backgroundImage: `url('${url}')`,
    backgroundSize: '100% 100%',
  };
}

function loadAvatar(marker) {
  if (!marker || marker.kind !== 'player' || avatarUrls[marker.id] || avatarLoading.has(marker.id)) return;
  const cached = avatarCache.get(marker.id);
  if (cached) {
    avatarUrls[marker.id] = cached;
    return;
  }
  const params = new URLSearchParams();
  if (marker.uuid) params.set('uuid', String(marker.uuid));
  if (marker.label) params.set('name', String(marker.label));
  const query = params.toString();
  if (!query) return;
  avatarLoading.add(marker.id);
  const image = new Image();
  image.crossOrigin = 'anonymous';
  image.referrerPolicy = 'no-referrer';
  image.decoding = 'async';
  image.onload = () => {
    avatarLoading.delete(marker.id);
    try {
      const canvas = document.createElement('canvas');
      canvas.width = 64;
      canvas.height = 64;
      const ctx = canvas.getContext('2d');
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(image, 8, 8, 8, 8, 0, 0, 64, 64);
      try { ctx.drawImage(image, 40, 8, 8, 8, 0, 0, 64, 64); } catch { /* 旧皮肤没有帽子层 */ }
      const data = canvas.toDataURL('image/png');
      avatarCache.set(marker.id, data);
      avatarUrls[marker.id] = data;
    } catch {
      // tainted canvas 或解码失败时保留首字母占位。
    }
  };
  image.onerror = () => {
    avatarLoading.delete(marker.id);
  };
  image.src = '/api/user/map/avatar?' + query;
}

function prepareMarkers() {
  for (const marker of props.markers) {
    if ((marker.kind || 'container') === 'player') loadAvatar(marker);
  }
}

onMounted(() => {
  loadModel();
  prepareMarkers();
  if (props.showTrails) {
    loadTrails();
    trailTimer = window.setInterval(loadTrails, 2000);
  }
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
  trailTimer && window.clearInterval(trailTimer);
  tilesRequest++;
  window.removeEventListener('pointermove', onPan);
});

watch(() => props.dimension, () => {
  closeClusterPopup();
  applyStack();
}, { immediate: false });

watch(() => props.markers, () => {
  // 玩家状态每 4 秒刷新；这里不能重建瓦片，否则整张地图会随标记更新闪烁。
  prepareMarkers();
  if (!centeredOnMarkers.value && props.markers.length) centerOnMarkers();
}, { deep: false });
</script>

<style scoped>
.map-picker {
  position: relative;
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 12px;
  overflow: hidden;
  background:
    linear-gradient(rgba(255, 255, 255, .025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, .025) 1px, transparent 1px),
    #10151c;
  background-size: 32px 32px, 32px 32px, auto;
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

.map-trails {
  position: absolute;
  inset: 0;
  z-index: 3;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.map-trails polyline {
  fill: none;
  stroke-width: 1.6;
  stroke-linejoin: round;
  stroke-linecap: round;
  stroke-opacity: .8;
}

.map-marker {
  position: absolute;
  z-index: 4;
  display: flex;
  align-items: center;
  gap: 5px;
  transform: translate(-50%, -50%);
  padding: 3px 7px 3px 4px;
  border: 1px solid #f0c674;
  border-radius: 999px;
  background: rgba(18, 22, 30, .9);
  color: #ffe9b0;
  font-size: 10px;
  white-space: nowrap;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0, 0, 0, .35);
  /* 只过渡颜色，不重复布局；位置取整后点击选中不再出现像素级抖动 */
  transition: border-color .2s ease, box-shadow .2s ease, background-color .2s ease, color .2s ease;
}

.map-marker.player {
  border-color: #4ade80;
  color: #d6ffe9;
}

.map-marker.active {
  border-color: #58a6ff;
  background: rgba(20, 50, 90, .94);
  color: #d8ecff;
  box-shadow: 0 0 0 2px rgba(88, 166, 255, .35), 0 2px 8px rgba(0, 0, 0, .35);
}

.map-marker:active {
  transform: translate(-50%, -50%) scale(.97);
}

.map-marker.offline {
  border-color: #6e7681;
  color: #b7bec9;
}

.marker-head {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  overflow: hidden;
  border-radius: 4px;
  background-color: #263241;
  background-repeat: no-repeat;
  image-rendering: pixelated;
  transition: box-shadow .18s ease;
}

.map-marker.player:not(.offline) .marker-head {
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, .2), 0 0 0 1.5px #4ade80;
}

.map-marker.offline .marker-head {
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, .12), 0 0 0 1px #6e7681;
}

.marker-initial {
  color: #f5f8ff;
  font-size: 10px;
  font-weight: 700;
}

.marker-item {
  width: 18px;
  height: 18px;
}

.marker-label {
  max-width: 96px;
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

.map-controls button.on {
  border-color: #4ade80;
  color: #b7f7cf;
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

.cluster-count {
  display: grid;
  place-items: center;
  min-width: 22px;
  height: 22px;
  padding: 0 4px;
  border-radius: 999px;
  background: #f0c674;
  color: #2b210f;
  font-size: 11px;
  font-weight: 800;
}

.map-marker.clustered {
  border-color: #ffd47a;
  background: rgba(46, 36, 18, .94);
}

.cluster-popup {
  position: absolute;
  z-index: 8;
  width: 380px;
  max-height: 380px;
  overflow: auto;
  padding: 8px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: rgba(16, 21, 28, .97);
  box-shadow: 0 10px 28px rgba(0, 0, 0, .5);
}

.cluster-popup-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-size: 11px;
}

.cluster-grid {
  display: grid;
  gap: 4px;
}

.cluster-cell {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  padding: 3px 5px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: rgba(255, 255, 255, .03);
  color: var(--fg);
  text-align: left;
  cursor: pointer;
}

.cluster-cell:hover,
.cluster-cell.active {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.cluster-cell-text {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.cluster-cell-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--fg);
}

.cluster-cell-text .muted {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

</style>
