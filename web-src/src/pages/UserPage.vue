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
        <main ref="host" class="content user-content" data-user-view="1">
<section id="user-section-status" class="panel">
  <div class="panel-head">
    <div>
      <h2>服务器状态</h2>
      <p class="panel-sub">公开只读信息；不包含配置、文件路径、备份、权限或 token。</p>
    </div>
  </div>
  <div id="user-status" class="status-chips">
    <div v-for="[label, value] in statusRows" :key="label" class="status-chip">
      <span>{{ label }}</span><strong>{{ value }}</strong>
    </div>
  </div>
  <div id="user-message" class="message" :class="messageKind" role="status">{{ message }}</div>
</section>

<section id="user-section-worlds" class="panel">
  <div class="panel-head">
    <div>
      <h2>世界栈</h2>
      <p class="panel-sub">每个栈层的种子互相独立；这里只显示公开的栈层顺序、种子和在线人数。</p>
    </div>
  </div>
  <div id="user-worlds" class="world-stack-list">
    <div v-for="stack in worlds" :key="stack.id" class="world-stack-card" :class="{ top: stack.top }">
      <div class="world-stack-card-head">
        <div class="world-stack-card-title">
          <strong>{{ stack.label || stack.id || 'world' }}</strong>
          <span class="muted small">栈序 #{{ (stack.index || 0) + 1 }}{{ stack.top ? ' · 当前栈顶' : '' }}</span>
        </div>
      </div>
      <div class="world-stack-card-body">
        <div class="world-stack-card-main">
          <div class="stat-row">
            <div class="stat"><span>种子</span><strong>{{ stack.seed ?? '—' }}</strong></div>
            <div class="stat"><span>在线</span><strong>{{ stack.online ?? 0 }} 人</strong></div>
          </div>
        </div>
        <WorldStackIcon :stack="stack" :admin="false" />
      </div>
    </div>
    <div v-if="!worlds.length" class="map-overview-empty">还没有可显示的世界栈。</div>
  </div>
</section>

<section id="user-section-lottery" class="panel">
  <div class="panel-head">
    <div>
      <h2>死人乐透</h2>
      <p class="panel-sub">每日猜死亡人数；公开端只显示奖池、票数与上期脱敏结果，不显示实时死亡数。</p>
    </div>
  </div>
  <div id="user-lottery" class="status-chips">
    <div v-for="[label, value] in lotteryChips" :key="label" class="status-chip">
      <span>{{ label }}</span><strong>{{ value }}</strong>
    </div>
  </div>
  <h3 class="lottery-pool-head">待开奖奖池</h3>
  <div id="user-pool" class="lottery-items">
    <div v-for="(item, index) in activeItems" :key="'a' + index" class="lottery-item-card">
      <ItemIcon :item="item" size="md" :show-count="false" />
      <div class="lottery-item-body">
        <span class="lottery-item-name">{{ itemTitle(item) }}</span>
        <span class="lottery-item-meta">{{ (item.count || 0) + ' 个 · ' + vp(item.value) }}</span>
      </div>
    </div>
    <div v-if="!activeItems.length" class="lottery-empty">待开奖奖池为空。</div>
  </div>
  <h3 class="lottery-pool-head">下注轮已入池</h3>
  <div id="user-betting-pool" class="lottery-items">
    <div v-for="(item, index) in bettingItems" :key="'b' + index" class="lottery-item-card">
      <ItemIcon :item="item" size="md" :show-count="false" />
      <div class="lottery-item-body">
        <span class="lottery-item-name">{{ itemTitle(item) }}</span>
        <span class="lottery-item-meta">{{ (item.count || 0) + ' 个 · ' + vp(item.value) }}</span>
      </div>
    </div>
    <div v-if="!bettingItems.length" class="lottery-empty">下注轮还没有人投入物品。</div>
  </div>
  <div id="user-fund" class="lottery-items">
    <div v-for="(item, index) in fundItems" :key="'f' + index" class="lottery-item-card">
      <ItemIcon :item="item" size="md" :show-count="false" />
      <div class="lottery-item-body">
        <span class="lottery-item-name">{{ itemTitle(item) }}</span>
        <span class="lottery-item-meta">{{ (item.count || 0) + ' 个 · ' + vp(item.value) }}</span>
      </div>
    </div>
  </div>
</section>

<section id="user-section-values" class="panel">
  <div class="panel-head">
    <div>
      <h2>物品价值表</h2>
      <p class="panel-sub">公开价格表；支持中文、全拼、首字母、英文和 ID 搜索。</p>
    </div>
    <span id="user-values-count" class="lottery-pill">—</span>
  </div>
  <UserValueTable />
</section>

<MapPanel user-view api-base="/api/user/map" section-id="user-section-map" />
        </main>
      </div>
    </div>

    <button v-if="showTop" class="user-top-button" type="button" @click="scrollTop">
      回到顶部
    </button>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { useApi } from '../composables/useApi.js';
import ItemIcon from '../components/ItemIcon.vue';
import MapPanel from './map/MapPanel.vue';
import UserValueTable from './user/UserValueTable.vue';
import WorldStackIcon from './world-stack/WorldStackIcon.vue';
import { itemTitle, vp } from './lottery/model.js';

const host = ref(null);
const activeSection = ref('user-section-status');
const showTop = ref(false);
const status = ref(null);
const worldsData = ref([]);
const lottery = ref(null);
const message = ref('');
const messageKind = ref('');
const sections = [
  { id: 'user-section-status', label: '服务器状态' },
  { id: 'user-section-worlds', label: '世界栈' },
  { id: 'user-section-lottery', label: '死人乐透' },
  { id: 'user-section-values', label: '物品价值表' },
  { id: 'user-section-map', label: '地图' },
];

const { request } = useApi();
let timer = 0;
let ticking = false;

const statusRows = computed(() => {
  const value = status.value || {};
  return [
    ['在线玩家', String(value.online ?? 0) + ' / ' + String(value.maxPlayers ?? 0)],
    ['服务器版本', value.version || '未知'],
    ['MOTD', value.motd || '—'],
    ['运行 tick', String(value.tickCount ?? 0)],
  ];
});
const worlds = computed(() => (Array.isArray(worldsData.value) ? worldsData.value : []));
const lotteryChips = computed(() => {
  const data = lottery.value;
  if (!data || !data.enabled) return [['状态', '未开启']];
  const round = data.round || {};
  const active = data.active || {};
  const rows = [
    ['待开奖轮', active.day || '—'],
    ['本轮种子', String(active.seedValue ?? 0) + ' VP'],
    ['待开奖奖池', String(active.pot ?? 0) + ' VP'],
    ['待开奖票数', String(active.tickets ?? 0) + ' 张 / ' + String(active.players ?? 0) + ' 人'],
    ['开奖', active.drawTime || '—'],
    ['可下注轮', round.day || '—'],
    ['下注轮已入池', String(round.ownPot ?? round.pot ?? 0) + ' VP'],
    ['已售', String(round.tickets ?? 0) + ' 张'],
    ['截止', round.cutoff || '—'],
    ['滚存', String(data.rollover ?? 0) + ' VP'],
  ];
  if (data.fund) {
    rows.push(['服务器基金', String(data.fund.value ?? 0) + ' VP · ' + String(data.fund.count ?? 0) + ' 项']);
  }
  if (data.gold) {
    rows.push(
      ['国际金价', data.gold.enabled ? Number(data.gold.price || 0).toFixed(2) + ' USD/oz' : '未启用'],
      ['金块估值', vp(data.gold.blockValue)],
    );
  }
  if (data.insurance && data.insurance.enabled) {
    rows.push(['保险', '保单 ' + (data.insurance.total ?? 0)
      + ' · 生效 ' + (data.insurance.active ?? 0)
      + ' · 待审核 ' + (data.insurance.pending ?? 0)]);
  }
  const last = data.last;
  if (last) {
    rows.push(
      ['上期死亡', String(last.deathCount ?? 0) + ' 个'],
      ['上期中奖', String(last.winnerCount ?? 0) + ' 人'],
      ['上期奖池', String(last.pot ?? 0) + ' VP' + (last.rollover ? ' · 滚存' : '')],
    );
    if (Array.isArray(last.winners) && last.winners.length) {
      rows.push(['上期中奖者', last.winners.join('、')]);
    }
  } else {
    rows.push(['上期', '暂无记录']);
  }
  return rows;
});
const activeItems = computed(() => (Array.isArray(lottery.value?.active?.items)
  ? lottery.value.active.items : []));
const bettingItems = computed(() => (Array.isArray(lottery.value?.round?.items)
  ? lottery.value.round.items : []));
const fundItems = computed(() => (Array.isArray(lottery.value?.fund?.items)
  ? lottery.value.fund.items : []));

async function loadSummary() {
  try {
    const [statusResult, worldsResult, lotteryResult] = await Promise.all([
      request('/api/user/status'),
      request('/api/user/worlds'),
      request('/api/user/lottery'),
    ]);
    if (!statusResult.ok || !worldsResult.ok || !lotteryResult.ok) {
      throw new Error('HTTP ' + (statusResult.status || worldsResult.status || lotteryResult.status));
    }
    status.value = statusResult.data;
    worldsData.value = Array.isArray(worldsResult.data?.stacks) ? worldsResult.data.stacks : [];
    lottery.value = lotteryResult.data;
    message.value = '';
    messageKind.value = '';
  } catch (error) {
    message.value = '公开信息读取失败：' + (error.message || '未知错误');
    messageKind.value = 'error';
  }
}

onMounted(async () => {
  document.title = 'samsara · 服务器公开信息';
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', measure, { passive: true });
  await loadSummary();
  timer = window.setInterval(loadSummary, 5000);
  await nextTick();
  measure();
  updateActive();
});

onUnmounted(() => {
  window.clearInterval(timer);
  window.removeEventListener('scroll', onScroll);
  window.removeEventListener('resize', measure);
});

function measure() {
  const header = document.querySelector('.user-topbar');
  const tabs = document.querySelector('.user-nav');
  const root = document.documentElement;
  root.style.setProperty('--header-h', Math.round(header?.getBoundingClientRect().height || 0) + 'px');
  root.style.setProperty('--ia-section-h', Math.round(tabs?.getBoundingClientRect().height || 0) + 'px');
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
