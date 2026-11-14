<template>
  <div class="user-page admin-layout ia-layout user-public">
    <header class="app-header ia-header user-topbar user-header">
      <div class="ia-header-main">
        <div class="brand ia-brand user-brand">
          <div class="brand-mark" aria-hidden="true">
            <span></span><span></span><span></span>
          </div>
          <div class="brand-text">
            <h1>samsara</h1>
            <p>服务器公开信息 <span class="version-pill">只读</span></p>
          </div>
        </div>
        <div class="user-top-actions">
          <el-tag size="small" effect="plain" type="success">公开只读</el-tag>
          <a class="user-view-link" href="/" title="进入管理端，需要 token">管理端</a>
        </div>
      </div>

      <section class="page-hero" aria-label="公开信息横幅">
        <div class="page-hero-art" aria-hidden="true"></div>
        <div class="page-hero-context">
          <div class="ia-crumbs" aria-label="面包屑">
            <span>samsara</span>
            <span class="ia-crumb-sep">/</span>
            <span class="ia-crumb-title">公开信息</span>
          </div>
          <span class="page-hero-tag">PUBLIC</span>
        </div>
        <div class="page-hero-main">
          <div class="page-hero-text">
            <h1 class="page-hero-title">服务器公开信息</h1>
            <p class="page-hero-hint">只读视图，不需要 token；数据由服务端公开白名单生成，不包含 UUID、路径、配置与写操作。</p>
          </div>
        </div>
      </section>
    </header>

    <div class="ia-body">
      <div class="ia-main">
        <nav class="section-tabs user-nav" aria-label="公开信息导航">
          <button
            v-for="item in sections"
            :key="item.id"
            class="section-tab user-nav-item"
            :class="{ active: activeSection === item.id }"
            type="button"
            @click="scrollTo(item.id)"
          >
            {{ item.label }}
          </button>
        </nav>
        <main ref="host" class="content user-content">
<section id="user-section-status" class="panel">
  <div class="panel-head">
    <div>
      <h2>服务器状态</h2>
      <p class="panel-sub">公开只读信息；不包含配置、文件路径、备份、权限或 token。</p>
    </div>
  </div>
  <div id="user-status" class="status-chips"></div>
  <div id="user-message" class="message" role="status"></div>
</section>

<section id="user-section-worlds" class="panel">
  <div class="panel-head">
    <div>
      <h2>世界栈</h2>
      <p class="panel-sub">每个栈层的种子互相独立；这里只显示公开的栈层顺序、种子和在线人数。</p>
    </div>
  </div>
  <div id="user-worlds" class="world-stack-list"></div>
</section>

<section id="user-section-lottery" class="panel">
  <div class="panel-head">
    <div>
      <h2>死人乐透</h2>
      <p class="panel-sub">每日猜死亡人数；公开端只显示奖池、票数与上期脱敏结果，不显示实时死亡数。</p>
    </div>
  </div>
  <div id="user-lottery" class="status-chips"></div>
  <h3 class="lottery-pool-head">待开奖奖池</h3>
  <div id="user-pool" class="lottery-items"></div>
  <h3 class="lottery-pool-head">下注轮已入池</h3>
  <div id="user-betting-pool" class="lottery-items"></div>
  <div id="user-fund" class="lottery-items"></div>
</section>

<section id="user-section-values" class="panel">
  <div class="panel-head">
    <div>
      <h2>物品价值表</h2>
      <p class="panel-sub">公开价格表；支持中文、全拼、首字母、英文和 ID 搜索。</p>
    </div>
    <span id="user-values-count" class="lottery-pill">—</span>
  </div>
  <div class="lottery-values-toolbar">
    <label class="search-box">
      <span aria-hidden="true">⌕</span>
      <input id="user-values-search" type="search" placeholder="搜索中文 / 拼音 / 首字母 / 英文 / ID">
    </label>
    <label class="inline-field">
      <span>分类</span>
      <select id="user-values-category">
        <option value="">全部</option>
        <option value="材料">材料</option>
        <option value="工具">工具</option>
        <option value="护甲">护甲</option>
        <option value="方块">方块</option>
        <option value="食物">食物</option>
        <option value="其他">其他</option>
      </select>
    </label>
    <label class="inline-field">
      <span>排序</span>
      <select id="user-values-sort">
        <option value="value-desc">价值从高到低</option>
        <option value="value-asc">价值从低到高</option>
        <option value="name">名称</option>
      </select>
    </label>
  </div>
  <div id="user-values-scroll" class="lottery-value-scroll">
    <div class="lottery-value-head">
      <span>物品</span><span>分类</span><span class="num">单价</span>
    </div>
    <div id="user-values" class="lottery-value-body"></div>
  </div>
  <div id="user-values-empty" class="lottery-empty" hidden>没有匹配的物品。</div>
</section>

<section id="user-section-map" class="panel map-panel">
  <div class="panel-head">
    <div>
      <h2>地图</h2>
      <p class="panel-sub">按世界栈读取各栈层的地表底图，并叠加圈地保护区块、旗帜与出生点历史；只读展示，不加载区块、不写世界数据。</p>
      <p class="panel-sub">栈顶是 <code>world</code>；旧栈层按从栈底往上数。点“世界栈总览”查看各栈层缩略图与在线玩家；玩家标记每 250 毫秒刷新。</p>
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
        <input id="map-show-chunks" type="checkbox">
        <span>区块网格</span>
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
        <input id="map-show-separation" type="checkbox">
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
        </main>
      </div>
    </div>

    <button v-if="showTop" class="user-top-button" type="button" @click="scrollTop">
      回到顶部
    </button>
  </div>
</template>

<script setup>
import { nextTick, onMounted, onUnmounted, ref } from 'vue';
import { mountLegacyUser } from '../legacy/user.js';

const host = ref(null);
const activeSection = ref('user-section-status');
const showTop = ref(false);
const sections = [
  { id: 'user-section-status', label: '服务器状态' },
  { id: 'user-section-worlds', label: '世界栈' },
  { id: 'user-section-lottery', label: '死人乐透' },
  { id: 'user-section-values', label: '物品价值表' },
  { id: 'user-section-map', label: '地图' },
];

let ticking = false;

onMounted(async () => {
  document.title = 'samsara · 服务器公开信息';
  try {
    await mountLegacyUser(host.value);
  } catch (error) {
    const box = document.createElement('div');
    box.className = 'container-message error';
    box.textContent = '用户端加载失败：' + (error?.message || error);
    host.value?.appendChild(box);
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', measure, { passive: true });
  await nextTick();
  measure();
  updateActive();
});

onUnmounted(() => {
  window.removeEventListener('scroll', onScroll);
  window.removeEventListener('resize', measure);
});

function measure() {
  const header = document.querySelector('.user-topbar');
  const tabs = document.querySelector('.user-nav');
  const root = document.documentElement;
  root.style.setProperty('--header-h',
      Math.round(header?.getBoundingClientRect().height || 0) + 'px');
  root.style.setProperty('--ia-section-h',
      Math.round(tabs?.getBoundingClientRect().height || 0) + 'px');
}

function onScroll() {
  if (ticking) return;
  ticking = true;
  window.requestAnimationFrame(() => {
    ticking = false;
    updateActive();
  });
}

function updateActive() {
  const root = getComputedStyle(document.documentElement);
  const offset = (parseInt(root.getPropertyValue('--header-h'), 10) || 0)
    + (parseInt(root.getPropertyValue('--ia-section-h'), 10) || 0) + 24;
  const y = window.scrollY + offset;
  let current = sections[0].id;
  for (const item of sections) {
    const node = document.getElementById(item.id);
    if (node && node.offsetTop <= y) current = item.id;
  }
  activeSection.value = current;
  showTop.value = window.scrollY > 700;
}

function scrollTo(id) {
  const node = document.getElementById(id);
  if (!node) return;
  const root = getComputedStyle(document.documentElement);
  const offset = (parseInt(root.getPropertyValue('--header-h'), 10) || 0)
    + (parseInt(root.getPropertyValue('--ia-section-h'), 10) || 0) + 12;
  const top = node.getBoundingClientRect().top + window.scrollY - offset;
  window.scrollTo({ top, behavior: 'smooth' });
  activeSection.value = id;
}

function scrollTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' });
}
</script>

<style scoped>
.user-page {
  width: 100%;
  padding-bottom: 72px;
}

.user-public .ia-main {
  margin-left: 0;
}

.user-brand {
  min-width: 0;
}

.user-brand .brand-text p {
  margin-top: 2px;
}

.user-top-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
}

.user-nav {
  flex-wrap: nowrap;
}

.user-content {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding-top: 12px;
}

.user-content :deep(section[id^="user-section-"]) {
  scroll-margin-top: 180px;
}

.user-top-button {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 25;
  padding: 8px 14px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  background: var(--panel);
  color: var(--fg-soft);
  box-shadow: var(--shadow);
  cursor: pointer;
}

.user-top-button:hover {
  border-color: var(--accent);
  color: var(--accent);
}

@media (max-width: 900px) {
  .user-page {
    padding-bottom: 60px;
  }

  .user-content {
    padding-top: 8px;
  }
}
</style>
