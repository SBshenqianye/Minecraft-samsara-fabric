<template>
<TabShell name="lottery">
<div id="panel-lottery" class="panel-stack">
  <section class="panel" id="lottery-dashboard">
    <div class="panel-head">
      <div>
        <h2>经济业务概览</h2>
        <p class="panel-sub">服务器基金 · 死人乐透 · 保险的实时汇总；点击二级 Tab 查看明细。</p>
      </div>
      <div class="panel-tools">
        <button id="lottery-dashboard-refresh" class="ghost" type="button">刷新概览</button>
      </div>
    </div>
    <div id="lottery-dashboard-body" class="economy-dashboard"></div>
  </section>

  <section class="panel" id="lottery-overview">
    <div class="panel-head">
      <div>
        <h2>经济业务</h2>
        <p class="panel-sub">死人乐透 · 服务器基金 · 物品/宠物保险。全部使用实物结算，不建立虚拟余额。</p>
      </div>
      <div class="panel-tools">
        <button id="lottery-refresh" class="ghost" type="button">刷新</button>
        <button id="lottery-draw" class="ghost danger" type="button">手动开奖</button>
        <button id="lottery-cancel" class="ghost danger" type="button">取消并退款</button>
      </div>
    </div>
    <div id="lottery-message" class="message" role="status"></div>
    <div id="lottery-stats" class="stat-row"></div>
  </section>

  <section class="panel" id="lottery-round-panel">
    <div class="panel-head">
      <div>
        <h2>当前轮次</h2>
        <p class="panel-sub">下注物品立即进入对应轮次奖池；待开奖轮和可下注轮分开显示。截止前盲押，开奖后按票平分奖池，5% 进服务器基金，无人中滚存到下一期。</p>
      </div>
      <span id="lottery-round-badge" class="lottery-pill">—</span>
    </div>
    <div id="lottery-round" class="stat-row"></div>
    <div id="lottery-bets" class="lottery-list"></div>
    <h3 class="lottery-pool-head">待开奖奖池物品</h3>
    <div id="lottery-pool" class="lottery-items"></div>
    <h3 class="lottery-pool-head">下注轮已入池</h3>
    <div id="lottery-betting-pool" class="lottery-items"></div>
  </section>

  <section class="panel" id="lottery-values-panel">
    <div class="panel-head">
      <div>
        <h2>物品价值表</h2>
        <p class="panel-sub">铁锭 = 1 VP 的锚点体系；只列出可收物品，未知物品为 0 不显示。支持中文、全拼、首字母和英文 ID 搜索。</p>
      </div>
      <span id="lottery-values-count" class="lottery-pill">—</span>
    </div>
    <div class="lottery-values-toolbar">
      <label class="search-box">
        <span aria-hidden="true">⌕</span>
        <input id="lottery-values-search" type="search" placeholder="搜索中文 / 拼音 / 首字母 / 英文 / ID">
      </label>
      <label class="inline-field">
        <span>分类</span>
        <select id="lottery-values-category">
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
        <select id="lottery-values-sort">
          <option value="value-desc">价值从高到低</option>
          <option value="value-asc">价值从低到高</option>
          <option value="name">名称</option>
        </select>
      </label>
    </div>
    <div id="lottery-values-scroll" class="lottery-value-scroll">
      <div class="lottery-value-head">
        <span>物品</span><span>分类</span><span class="num">单价</span>
      </div>
      <div id="lottery-values" class="lottery-value-body"></div>
    </div>
    <div id="lottery-values-empty" class="lottery-empty" hidden>没有匹配的物品。</div>
  </section>

  <!-- 服务器基金已与基金仓库合并：实物列表改在本页下方的工作台里直接编辑。 -->

  <section class="panel" id="lottery-claims-panel">
    <div class="panel-head">
      <div>
        <h2>待领取</h2>
        <p class="panel-sub">背包满或离线时保留的退款/中奖物品；玩家上线后执行 <code>/samsara lottery collect</code> 领取，背包满则继续保留。</p>
      </div>
      <span id="lottery-claims-count" class="lottery-pill">0 笔</span>
    </div>
    <div id="lottery-claims" class="lottery-list"></div>
  </section>

  <section class="panel" id="lottery-insurance-panel">
    <div class="panel-head">
      <div>
        <h2>保险</h2>
        <p class="panel-sub">物品单件投保，保费 = 价值 × 2；生物死亡后 3 秒在主人身边复活。</p>
      </div>
      <span id="lottery-insurance-summary" class="lottery-pill">0 单</span>
    </div>
    <div id="lottery-insurance" class="lottery-list"></div>
  </section>

  <section class="panel" id="lottery-history-panel">
    <div class="panel-head">
      <div>
        <h2>历史开奖</h2>
        <p class="panel-sub">最近 10 期；滚存表示当期无人中奖，奖池进入下一期。</p>
      </div>
    </div>
    <div id="lottery-history" class="lottery-list"></div>
  </section>

</div>
    <section v-if="isFundPage" class="panel fund-inventory-embed">
      <PoolInventoryPage kind="fund" embedded />
    </section>
</TabShell>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import TabShell from '../components/TabShell.vue';
import PoolInventoryPage from './PoolInventoryPage.vue';
import { useLegacyPage } from '../composables/useLegacyPage.js';

defineOptions({ name: 'LotteryPage' });

useLegacyPage({
  panelId: 'panel-lottery',
  load: () => import('../../../mod/src/main/resources/web/lottery.js'),
  activate: () => window.samsaraLottery?.activate?.(),
});

const route = useRoute();
const isFundPage = computed(() => route.meta.subpage === 'fund');
</script>

<style scoped>
.fund-inventory-embed {
  margin-top: 16px;
}
</style>
