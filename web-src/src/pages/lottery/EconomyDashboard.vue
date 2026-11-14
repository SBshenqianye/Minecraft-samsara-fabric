<template>
  <div class="economy-dash-grid">
    <article class="dash-card">
      <div class="dash-card-head">
        <h3>服务器基金</h3>
        <span>FUND</span>
      </div>
      <strong class="dash-value">{{ vp(dash.fund.value) }}</strong>
      <p class="dash-sub">基金物品 {{ dash.fund.itemCount }} 件 · 与奖池/滚存共同构成实物资产</p>
      <div class="dash-bar"><span :style="{ width: dash.fund.width }"></span></div>
      <div class="dash-icons">
        <ItemIcon v-for="(item, index) in dash.fund.items" :key="index" :item="item" size="sm" />
        <span v-if="!dash.fund.items.length" class="dash-empty">暂无基金物品</span>
      </div>
    </article>

    <article class="dash-card">
      <div class="dash-card-head">
        <h3>死人乐透</h3>
        <span>LOTTERY</span>
      </div>
      <strong class="dash-value">{{ vp(dash.lottery.pot) }}</strong>
      <p class="dash-sub">{{ dash.lottery.sub }}</p>
      <div class="dash-donut" :style="{ background: dash.lottery.donut }">
        <strong>{{ vp(dash.lottery.pot) }}</strong>
      </div>
      <div class="dash-legend">
        <span>● 奖池 {{ vp(dash.lottery.pot) }}</span>
        <span>● 滚存 {{ vp(dash.lottery.rollover) }}</span>
      </div>
    </article>

    <article class="dash-card">
      <div class="dash-card-head">
        <h3>保险</h3>
        <span>INSURANCE</span>
      </div>
      <strong class="dash-value">{{ dash.insurance.active }}</strong>
      <p class="dash-sub">{{ dash.insurance.sub }}</p>
      <div class="dash-bar dash-bar-stack">
        <span
          v-for="item in dash.insurance.legend"
          :key="item.status"
          :style="{ width: item.width, background: item.color }"
        ></span>
      </div>
      <div class="dash-legend">
        <span v-for="item in dash.insurance.legend" :key="item.status">
          <i :style="{ background: item.color }"></i>{{ item.status }} {{ item.count }}
        </span>
        <span v-if="!dash.insurance.total" class="dash-empty">暂无保单</span>
      </div>
    </article>

    <article class="dash-card dash-card-wide">
      <div class="dash-card-head">
        <h3>历史开奖走势</h3>
        <span>HISTORY</span>
      </div>
      <p v-if="!dash.history.length" class="dash-empty">暂无历史开奖。</p>
      <div v-else class="dash-history">
        <div v-for="row in dash.history" :key="row.day" class="dash-history-col">
          <div class="dash-history-bar" :class="{ 'has-winner': row.winner }" :style="{ height: row.height }"></div>
          <span>{{ row.day }}</span>
        </div>
      </div>
    </article>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import ItemIcon from '../../components/ItemIcon.vue';
import { dashboardModel, vp } from './model.js';

const props = defineProps({
  model: { type: Object, default: () => ({}) },
});

const dash = computed(() => dashboardModel(props.model));
</script>
