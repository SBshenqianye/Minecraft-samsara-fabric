<template>
  <div class="lottery-values-toolbar">
    <label class="search-box">
      <span aria-hidden="true">⌕</span>
      <input id="user-values-search" v-model="query" type="search" placeholder="搜索中文 / 拼音 / 首字母 / 英文 / ID" @input="schedule" />
    </label>
    <label class="inline-field">
      <span>分类</span>
      <select id="user-values-category" v-model="category" @change="load(true)">
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
      <select id="user-values-sort" v-model="sort" @change="load(true)">
        <option value="value-desc">价值从高到低</option>
        <option value="value-asc">价值从低到高</option>
        <option value="name">名称</option>
      </select>
    </label>
  </div>
  <div id="user-values-scroll" class="lottery-value-scroll" :hidden="!rows.length">
    <div class="lottery-value-head">
      <span>物品</span><span>分类</span><span class="num">单价</span>
    </div>
    <div id="user-values" class="lottery-value-body">
      <div v-for="row in rows" :key="row.raw.id || row.name" class="lottery-value-row" :class="{ zero: row.zero }">
        <div class="lottery-value-item">
          <ItemIcon :item="row.raw" size="sm" :show-count="false" />
          <span class="lottery-value-name">{{ row.name }}</span>
          <span class="lottery-value-id">{{ row.id }}</span>
          <span v-if="row.goldLinked" class="lottery-value-gold">金价</span>
        </div>
        <span class="lottery-value-cat">{{ row.category }}</span>
        <strong class="num">{{ row.value }}</strong>
      </div>
      <button v-if="rows.length < total" class="ghost lottery-more" type="button" @click="load(false)">
        加载更多（{{ rows.length }} / {{ total }}）
      </button>
    </div>
  </div>
  <div id="user-values-empty" class="lottery-empty" :hidden="rows.length > 0">没有匹配的物品。</div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import ItemIcon from '../../components/ItemIcon.vue';
import { useApi } from '../../composables/useApi.js';
import { valueRows } from '../lottery/model.js';

const props = defineProps({
  countId: { type: String, default: 'user-values-count' },
});

const { request } = useApi();
const query = ref('');
const category = ref('');
const sort = ref('value-desc');
const values = ref([]);
const total = ref(0);
let timer = 0;
let seq = 0;

const rows = computed(() => valueRows(values.value));

function schedule() {
  window.clearTimeout(timer);
  timer = window.setTimeout(() => load(true), 240);
}

async function load(reset) {
  if (reset) values.value = [];
  const current = ++seq;
  try {
    const params = new URLSearchParams();
    if (query.value.trim()) params.set('q', query.value.trim());
    if (category.value) params.set('category', category.value);
    params.set('sort', sort.value);
    params.set('offset', String(reset ? 0 : values.value.length));
    params.set('limit', '100');
    const result = await request('/api/user/lottery/values?' + params.toString());
    if (current !== seq || !result.ok) return;
    const page = Array.isArray(result.data?.values) ? result.data.values : [];
    values.value = reset ? page : values.value.concat(page);
    total.value = Number(result.data?.total || values.value.length);
    const count = document.getElementById(props.countId);
    if (count) count.textContent = values.value.length + ' / ' + total.value + ' 项';
  } catch {
    // 公开价值表失败时保留空态。
  }
}

onMounted(() => load(true));
onBeforeUnmount(() => window.clearTimeout(timer));
</script>
