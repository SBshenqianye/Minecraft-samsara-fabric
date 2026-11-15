<template>
  <section :id="sectionId || undefined" class="panel map-panel">
    <div class="panel-head">
      <div>
        <h2>地图</h2>
        <p class="panel-sub">按世界栈读取各栈层的地表底图，并叠加圈地保护区块、旗帜与出生点历史。只读展示，不加载区块、不写世界数据；地形图缓存可安全删除。</p>
        <p class="panel-sub">栈顶是 <code>world</code>；旧栈层按从栈底往上数。点“世界栈总览”查看各栈层缩略图与在线玩家；玩家标记定时刷新。</p>
      </div>
      <div class="panel-tools">
        <label class="inline-field">
          <span>栈层</span>
          <select id="map-stack"></select>
        </label>
        <label class="inline-field">
          <span>维度</span>
          <select id="map-dimension"></select>
        </label>
        <label class="check-line">
          <input id="map-show-claims" type="checkbox" :checked="options.claims"
              @change="setOption('claims', $event.target.checked)">
          <span>显示圈地</span>
        </label>
        <label class="check-line">
          <input id="map-show-chunks" type="checkbox" :checked="options.chunks"
              @change="setOption('chunks', $event.target.checked)">
          <span>区块网格</span>
        </label>
        <label v-if="!userView" class="check-line" title="区块操作模式默认开启；点击区块会先打开预览/确认，不会直接写世界。">
          <input id="map-chunk-mode" type="checkbox" :checked="options.chunkMode"
              @change="setOption('chunkMode', $event.target.checked)">
          <span>区块操作</span>
        </label>
        <label class="check-line">
          <input id="map-show-spawns" type="checkbox" :checked="options.spawns"
              @change="setOption('spawns', $event.target.checked)">
          <span>显示出生点</span>
        </label>
        <label class="check-line">
          <input id="map-shading" type="checkbox" :checked="options.shading"
              @change="setOption('shading', $event.target.checked)">
          <span>高度明暗</span>
        </label>
        <label class="check-line">
          <input id="map-show-players" type="checkbox" :checked="options.players"
              @change="setOption('players', $event.target.checked)">
          <span>显示玩家</span>
        </label>
        <label class="check-line">
          <input id="map-show-entities" type="checkbox" :checked="options.entities"
              @change="setOption('entities', $event.target.checked)">
          <span>实体</span>
        </label>
        <label class="check-line">
          <input id="map-show-trails" type="checkbox" :checked="options.trails"
              @change="setOption('trails', $event.target.checked)">
          <span>轨迹</span>
        </label>
        <label class="check-line">
          <input id="map-show-rare-names" type="checkbox" :checked="options.rareNames"
              @change="setOption('rareNames', $event.target.checked)">
          <span>稀有名字</span>
        </label>
        <label class="check-line">
          <input id="map-show-separation" type="checkbox" :checked="options.separation"
              @change="setOption('separation', $event.target.checked)">
          <span>排斥半径</span>
        </label>
        <button id="map-fit" class="ghost" type="button" @click="fit">定位全部圈地</button>
        <button id="map-zoom-out" class="ghost map-zoom-button" type="button" title="缩小" @click="zoomOut">−</button>
        <span id="map-zoom-label" class="map-zoom-label">4 格/像素</span>
        <button id="map-zoom-in" class="ghost map-zoom-button" type="button" title="放大" @click="zoomIn">＋</button>
        <button id="map-overview-toggle" class="ghost map-overview-toggle" type="button"
            aria-pressed="false" @click="toggleOverview">世界栈总览</button>
        <button id="map-reload" class="ghost" type="button" @click="reload">重新读取</button>
      </div>
    </div>
    <div id="map-message" class="message" role="status"></div>
    <div id="map-stats" class="stat-row map-stats"></div>
    <div id="map-stage-anchor" class="map-stage">
      <canvas id="map-canvas" class="map-canvas" aria-label="地形与圈地地图"></canvas>
      <canvas id="map-players" class="map-players" aria-hidden="true"></canvas>
      <canvas id="map-live" class="map-live" aria-hidden="true"></canvas>
      <div id="map-tooltip" class="map-tooltip" hidden></div>
      <div id="map-dim-anchors" class="map-dim-anchors" aria-hidden="true"></div>
      <div id="map-selection" class="map-selection map-hud"></div>
    </div>
    <div id="map-stack-overview" class="map-overview" hidden>
      <div class="map-overview-head">
        <div>
          <strong>世界栈总览</strong>
          <p class="muted small">每个栈：主世界在上、下界在下，中间留虚空/高空缝隙；末地在右侧。栈与栈之间也是虚空/高空缝隙。点击面板进入详细地图。</p>
        </div>
        <span id="map-overview-status" class="map-overview-status">在线玩家读取中…</span>
      </div>
      <div id="map-overview-stacks" class="map-overview-stacks"></div>
    </div>
    <div class="map-activity-wrap">
      <div class="map-activity-head">
        <strong>在线玩家与最近方块操作</strong>
        <span id="map-live-status" class="muted small">读取中…</span>
      </div>
      <p class="muted small" style="margin:0 0 8px">只显示当前已加载区块内的在线玩家、实体位置和最近方块操作，不是全服聊天/命令日志。</p>
      <div id="map-activity" class="map-activity"></div>
    </div>
    <div class="map-footer">
      <div class="map-legend">
        <span><i class="map-swatch claim"></i>半透明青色 = 保护区块</span>
        <span><i class="map-swatch banner"></i>圆点 = 旗帜（颜色取图案色）</span>
        <span><i class="map-swatch player"></i>蓝色圆点 = 在线玩家</span>
        <span><i class="map-swatch spawn"></i>菱形 = 出生点历史（独立颜色）</span>
        <span><i class="map-swatch trail"></i>细线 = 玩家/实体短轨迹</span>
        <span><i class="map-swatch separation"></i>虚线圆 = 出生点排斥半径</span>
        <span><i class="map-swatch chunk"></i><span id="map-chunk-grid-legend">区块网格 关</span></span>
        <span>拖动平移 · 滚轮缩放 · 点击旗帜/出生点看详情</span>
      </div>
    </div>
  </section>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useApi } from '../../composables/useApi.js';
import { getToken } from '../../composables/useApi.js';
import { setPageNav } from '../../composables/usePageNav.js';
import { createMapKernel } from './kernel.js';

const props = defineProps({
  userView: { type: Boolean, default: false },
  apiBase: { type: String, default: '/api/map' },
  sectionId: { type: String, default: '' },
});

const { request, authHeaders, loadBinary } = useApi();
const options = reactive({
  claims: true,
  chunks: false,
  chunkMode: true,
  spawns: true,
  shading: true,
  players: true,
  entities: true,
  trails: true,
  rareNames: true,
  separation: false,
});
const mapApi = ref(null);

function kernelApi(path, opts) {
  return request(path, opts).then((result) => {
    if (!result.ok) {
      const error = new Error(result.data?.error || ('HTTP ' + result.status));
      error.status = result.status;
      error.body = result.data;
      throw error;
    }
    return result.data;
  });
}

function authMessage(error, fallback) {
  if (error?.status === 401) return 'token 无效或已失效';
  return easeMessage(error, fallback);
}

function easeMessage(error, fallback) {
  return fallback + (error?.message ? '：' + error.message : '');
}

function buildNav(title, items) {
  if (props.userView) return;
  setPageNav({
    title,
    items: (items || []).map((item) => ({ ...item, children: item.children || [] })),
  });
}

function scrollToTarget(id) {
  document.getElementById(id)?.scrollIntoView({ block: 'start' });
}

function syncOptions() {
  const next = mapApi.value?.getOptions?.();
  if (next) Object.assign(options, next);
}

function setOption(name, value) {
  options[name] = value;
  mapApi.value?.setOption?.(name, value);
}

function reload() {
  mapApi.value?.reload?.();
}

function fit() {
  mapApi.value?.fitView?.();
}

function zoomIn() {
  mapApi.value?.zoomIn?.();
}

function zoomOut() {
  mapApi.value?.zoomOut?.();
}

function toggleOverview() {
  mapApi.value?.toggleOverview?.();
}

function createKernel() {
  mapApi.value = createMapKernel({
    api: kernelApi,
    authMessage: props.userView ? easeMessage : authMessage,
    authHeaders: props.userView ? () => ({}) : authHeaders,
    loadBinary,
    apiBase: props.apiBase,
    hasToken: props.userView ? () => true : () => Boolean(getToken()),
    isActive: () => true,
    userView: props.userView,
    vueControls: true,
    buildNav,
    scrollToTarget,
    setCount: () => {},
    markStale: () => {},
  });
  syncOptions();
  mapApi.value.activate?.();
}

watch(() => props.apiBase, () => {
  mapApi.value = null;
  createKernel();
});

onMounted(createKernel);
onBeforeUnmount(() => {
  mapApi.value = null;
});
</script>
