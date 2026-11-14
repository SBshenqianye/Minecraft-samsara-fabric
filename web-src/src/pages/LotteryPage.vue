<template>
<TabShell name="lottery">
<div id="panel-lottery" class="panel-stack" :class="'lottery-tab-' + subpage">
  <section class="panel" id="lottery-dashboard" :hidden="subpage !== 'overview'">
    <div class="panel-head">
      <div>
        <h2>经济业务概览</h2>
        <p class="panel-sub">服务器基金 · 死人乐透 · 保险的实时汇总；点击二级 Tab 查看明细。</p>
      </div>
      <div class="panel-tools">
        <button id="lottery-dashboard-refresh" class="ghost" type="button" :disabled="loading" @click="load">刷新概览</button>
      </div>
    </div>
    <div id="lottery-dashboard-body" class="economy-dashboard">
      <EconomyDashboard v-if="model" :model="model" />
    </div>
  </section>

  <section class="panel" id="lottery-overview" :hidden="subpage !== 'lottery'">
    <div class="panel-head">
      <div>
        <h2>经济业务</h2>
        <p class="panel-sub">死人乐透 · 服务器基金 · 物品/宠物保险。全部使用实物结算，不建立虚拟余额。</p>
      </div>
      <div class="panel-tools">
        <button id="lottery-refresh" class="ghost" type="button" :disabled="loading" @click="load">刷新</button>
        <button id="lottery-draw" class="ghost danger" type="button" :disabled="busy" @click="draw">手动开奖</button>
        <button id="lottery-cancel" class="ghost danger" type="button" :disabled="busy" @click="cancel">取消并退款</button>
      </div>
    </div>
    <div id="lottery-message" class="message" :class="messageKind" role="status">{{ message }}</div>
    <div id="lottery-stats" class="stat-row">
      <div v-for="[label, value] in stats" :key="label" class="stat">
        <span>{{ label }}</span><strong>{{ value }}</strong>
      </div>
    </div>
  </section>

  <section class="panel" id="lottery-round-panel" :hidden="subpage !== 'lottery'">
    <div class="panel-head">
      <div>
        <h2>当前轮次</h2>
        <p class="panel-sub">下注物品立即进入对应轮次奖池；待开奖轮和可下注轮分开显示。截止前盲押，开奖后按票平分奖池，5% 进服务器基金，无人中滚存到下一期。</p>
      </div>
      <span id="lottery-round-badge" class="lottery-pill" :class="round.tone">{{ round.label }}</span>
    </div>
    <div id="lottery-round" class="stat-row">
      <div v-for="[label, value] in roundRows" :key="label" class="stat">
        <span>{{ label }}</span><strong>{{ value }}</strong>
      </div>
    </div>
    <div id="lottery-bets" class="lottery-list">
      <template v-for="section in betList" :key="section.title">
        <div class="lottery-section-title">{{ section.title }}</div>
        <div v-for="(bet, index) in section.rows" :key="index" class="lottery-row">
          <div class="lottery-row-main">
            <span class="lottery-row-title">{{ bet.title }}</span>
            <span class="lottery-row-meta">{{ bet.meta }}</span>
          </div>
        </div>
        <div v-if="!section.rows.length" class="lottery-empty">{{ section.empty }}</div>
      </template>
    </div>
    <h3 class="lottery-pool-head">待开奖奖池物品</h3>
    <div id="lottery-pool" class="lottery-items">
      <div v-for="(item, index) in activePotItems" :key="'a' + index" class="lottery-item-card">
        <ItemIcon :item="item" size="md" :show-count="false" />
        <div class="lottery-item-body">
          <span class="lottery-item-name">{{ itemTitle(item) }}</span>
          <span class="lottery-item-meta">{{ (item.count || 0) + ' 个 · ' + vp(item.value) }}</span>
        </div>
      </div>
      <div v-if="!activePotItems.length" class="lottery-empty">待开奖奖池为空。</div>
    </div>
    <h3 class="lottery-pool-head">下注轮已入池</h3>
    <div id="lottery-betting-pool" class="lottery-items">
      <div v-for="(item, index) in bettingPotItems" :key="'b' + index" class="lottery-item-card">
        <ItemIcon :item="item" size="md" :show-count="false" />
        <div class="lottery-item-body">
          <span class="lottery-item-name">{{ itemTitle(item) }}</span>
          <span class="lottery-item-meta">{{ (item.count || 0) + ' 个 · ' + vp(item.value) }}</span>
        </div>
      </div>
      <div v-if="!bettingPotItems.length" class="lottery-empty">下注轮还没有人投入物品。</div>
    </div>
  </section>

  <ValueTable ref="valueTableRef" :hidden="subpage !== 'fund'" @error="onError" />

  <section class="panel" id="lottery-claims-panel" :hidden="subpage !== 'lottery'">
    <div class="panel-head">
      <div>
        <h2>待领取</h2>
        <p class="panel-sub">背包满或离线时保留的退款/中奖物品；玩家上线后执行 <code>/samsara lottery collect</code> 领取，背包满则继续保留。</p>
      </div>
      <span id="lottery-claims-count" class="lottery-pill">{{ claimRows.length }} 笔</span>
    </div>
    <div id="lottery-claims" class="lottery-list">
      <div v-for="(claim, index) in claimRows" :key="index" class="lottery-claim">
        <div class="lottery-row-main">
          <span class="lottery-row-title">{{ claim.owner }}</span>
          <span class="lottery-row-meta">{{ claim.meta }}</span>
        </div>
        <div class="lottery-items">
          <div v-for="(item, itemIndex) in claim.items" :key="itemIndex" class="lottery-item-card">
            <ItemIcon :item="item" size="md" :show-count="false" />
            <div class="lottery-item-body">
              <span class="lottery-item-name">{{ itemTitle(item) }}</span>
              <span class="lottery-item-meta">{{ (item.count || 0) + ' 个 · ' + vp(item.value) }}</span>
            </div>
          </div>
          <div v-if="!claim.items.length" class="lottery-empty">没有物品。</div>
        </div>
      </div>
      <div v-if="!claimRows.length" class="lottery-empty">没有待领取物品。</div>
    </div>
  </section>

  <section class="panel" id="lottery-insurance-panel" :hidden="subpage !== 'insurance'">
    <div class="panel-head">
      <div>
        <h2>保险</h2>
        <p class="panel-sub">物品单件投保，保费 = 价值 × 2；生物死亡后 3 秒在主人身边复活。</p>
      </div>
      <span id="lottery-insurance-summary" class="lottery-pill">
        {{ insurance.total }} 单{{ insurance.pending ? ' · ' + insurance.pending + ' 待审核' : '' }}
      </span>
    </div>
    <div id="lottery-insurance" class="lottery-list">
      <div v-for="row in insurance.rows" :key="row.id" class="lottery-row">
        <div class="lottery-row-main">
          <span class="lottery-row-title">{{ row.id }}</span>
          <span class="lottery-row-meta">{{ row.meta }}</span>
        </div>
        <span class="lottery-pill" :class="row.tone">{{ row.status }}</span>
      </div>
      <div v-if="!insurance.rows.length" class="lottery-empty">还没有保单。</div>
      <div v-if="insurance.more" class="lottery-empty">{{ insurance.more }}</div>
    </div>
  </section>

  <section class="panel" id="lottery-history-panel" :hidden="subpage !== 'lottery'">
    <div class="panel-head">
      <div>
        <h2>历史开奖</h2>
        <p class="panel-sub">最近 10 期；滚存表示当期无人中奖，奖池进入下一期。</p>
      </div>
    </div>
    <div id="lottery-history" class="lottery-list">
      <div v-for="(row, index) in history" :key="index" class="lottery-row">
        <div class="lottery-row-main">
          <span class="lottery-row-title">{{ row.title }}</span>
          <span class="lottery-row-meta">{{ row.meta }}</span>
        </div>
        <span v-if="row.rollover" class="lottery-pill warn">滚存</span>
      </div>
      <div v-if="!history.length" class="lottery-empty">还没有已结算的期次。</div>
    </div>
  </section>
</div>
    <section v-if="subpage === 'fund'" class="panel fund-inventory-embed">
      <PoolInventoryPage kind="fund" embedded />
    </section>
</TabShell>
</template>

<script setup>
import { computed, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { useRoute } from 'vue-router';
import TabShell from '../components/TabShell.vue';
import ItemIcon from '../components/ItemIcon.vue';
import PoolInventoryPage from './PoolInventoryPage.vue';
import EconomyDashboard from './lottery/EconomyDashboard.vue';
import ValueTable from './lottery/ValueTable.vue';
import { useApi } from '../composables/useApi.js';
import { clearPageNav, setPageNav } from '../composables/usePageNav.js';
import {
  betSections,
  claims,
  historyRows,
  insuranceRows,
  itemTitle,
  roundState,
  roundStats,
  statsRows,
  vp,
} from './lottery/model.js';

defineOptions({ name: 'LotteryPage' });

const route = useRoute();
const { request } = useApi();
const model = ref(null);
const loading = ref(false);
const busy = ref(false);
const message = ref('');
const messageKind = ref('');
const valueTableRef = ref(null);

const subpage = computed(() => String(route.meta.subpage || 'overview'));
const stats = computed(() => statsRows(model.value || {}));
const round = computed(() => roundState(model.value || {}));
const roundRows = computed(() => roundStats(model.value || {}));
const betList = computed(() => betSections(model.value || {}));
const claimRows = computed(() => claims(model.value || {}));
const insurance = computed(() => insuranceRows(model.value || {}));
const history = computed(() => historyRows(model.value || {}));
const activePotItems = computed(() => (Array.isArray(model.value?.activePotItems)
  ? model.value.activePotItems : []));
const bettingPotItems = computed(() => (Array.isArray(model.value?.bettingPotItems)
  ? model.value.bettingPotItems : []));

function setMessage(text, kind = '') {
  message.value = text || '';
  messageKind.value = kind;
}

function onError(text) {
  setMessage(text, 'error');
}

async function load() {
  loading.value = true;
  try {
    const result = await request('/api/lottery');
    if (!result.ok) throw new Error(result.data?.error || '读取失败');
    model.value = result.data || {};
    setMessage('已刷新 · ' + new Date().toLocaleTimeString());
  } catch (error) {
    setMessage('读取失败：' + (error.message || '未知错误'), 'error');
  } finally {
    loading.value = false;
  }
}

async function action(path, confirmText) {
  try {
    await ElMessageBox.confirm(confirmText, '确认操作', {
      confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning',
    });
  } catch {
    return;
  }
  busy.value = true;
  try {
    const result = await request(path, { method: 'POST', body: '{}' });
    if (!result.ok || result.data?.ok === false) {
      throw new Error(result.data?.error || '操作失败');
    }
    setMessage(result.data?.message || '完成');
    await load();
  } catch (error) {
    setMessage('操作失败：' + (error.message || '未知错误'), 'error');
  } finally {
    busy.value = false;
  }
}

function draw() {
  return action('/api/lottery/draw', '确认手动开奖当前统计日？');
}

function cancel() {
  return action('/api/lottery/cancel', '确认取消当前统计日并全额退款？');
}

const NAV = {
  overview: [{ label: '概览', target: 'lottery-dashboard' }],
  fund: [{ label: '物品价值表', target: 'lottery-values-panel' }],
  lottery: [
    { label: '状态', target: 'lottery-overview' },
    { label: '当前轮次', target: 'lottery-round-panel' },
    { label: '待领取', target: 'lottery-claims-panel' },
    { label: '历史开奖', target: 'lottery-history-panel' },
  ],
  insurance: [{ label: '保险', target: 'lottery-insurance-panel' }],
};

function syncNav() {
  setPageNav({ title: '经济业务', items: NAV[subpage.value] || NAV.overview });
}

watch(subpage, syncNav, { immediate: true });

function onConnected() {
  load();
  valueTableRef.value?.ensure();
}

onActivated(syncNav);

onMounted(() => {
  window.addEventListener('samsara:connected', onConnected);
  syncNav();
  load();
  valueTableRef.value?.ensure();
});

onBeforeUnmount(() => {
  window.removeEventListener('samsara:connected', onConnected);
  clearPageNav();
});
</script>

<style scoped>
.fund-inventory-embed {
  margin-top: 16px;
}
</style>
