<template>
<TabShell name="map">
<div id="panel-map" class="panel-stack">
  <section class="panel map-panel">
    <div class="panel-head">
      <div>
        <h2>地图</h2>
        <p class="panel-sub">按 v0.24 世界栈扫描各栈层的 region 文件生成地表底图，并叠加该层的圈地保护区块、旗帜与出生点历史。只读展示，不加载区块、不写世界数据；地形图缓存可安全删除。</p>
        <p class="panel-sub">栈顶是 <code>world</code>；旧栈层按从栈底往上数（第一层、第二层…），目录在 dimensions/samsara/stack_<span class="muted">n</span>；出生点标记在末地不显示，下界按主世界坐标 /8 推算。点“世界栈总览”按栈层显示主世界/下界/末地与在线玩家；玩家标记每 2 秒刷新，点击可复制 TP 命令。</p>
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
          <input id="map-show-claims" type="checkbox" checked>
          <span>显示圈地</span>
        </label>
        <label class="check-line">
          <input id="map-show-chunks" type="checkbox" checked>
          <span>区块网格</span>
        </label>
        <label class="check-line" title="区块操作模式默认开启；点击区块会先打开预览/确认，不会直接写世界。">
          <input id="map-chunk-mode" type="checkbox" checked>
          <span>区块操作</span>
        </label>
        <label class="check-line">
          <input id="map-show-spawns" type="checkbox" checked>
          <span>显示出生点</span>
        </label>
        <label class="check-line">
          <input id="map-shading" type="checkbox" checked>
          <span>高度明暗</span>
        </label>
        <label class="check-line">
          <input id="map-show-players" type="checkbox" checked>
          <span>显示玩家</span>
        </label>
        <label class="check-line">
          <input id="map-show-entities" type="checkbox" checked>
          <span>实体</span>
        </label>
        <label class="check-line">
          <input id="map-show-trails" type="checkbox" checked>
          <span>轨迹</span>
        </label>
        <label class="check-line">
          <input id="map-show-rare-names" type="checkbox" checked>
          <span>稀有名字</span>
        </label>
        <label class="check-line">
          <input id="map-show-separation" type="checkbox" checked>
          <span>排斥半径</span>
        </label>
        <button id="map-fit" class="ghost" type="button">定位全部圈地</button>
        <button id="map-zoom-out" class="ghost map-zoom-button" type="button" title="缩小">−</button>
        <span id="map-zoom-label" class="map-zoom-label">4 格/像素</span>
        <button id="map-zoom-in" class="ghost map-zoom-button" type="button" title="放大">＋</button>
        <button id="map-overview-toggle" class="ghost map-overview-toggle" type="button" aria-pressed="false">世界栈总览</button>
        <button id="map-reload" class="ghost" type="button">重新读取</button>
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
</div>
</TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import { useLegacyPage } from '../composables/useLegacyPage.js';

defineOptions({ name: 'MapPage' });

useLegacyPage({
  panelId: 'panel-map',
  load: () => import('../legacy-core/map.js'),
  activate: () => window.SamsaraMap?.activate?.(),
});
</script>
