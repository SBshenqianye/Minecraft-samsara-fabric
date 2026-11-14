<template>
  <section class="panel" id="lottery-values-panel" :hidden="hidden">
    <div class="panel-head">
      <div>
        <h2>物品价值表</h2>
        <p class="panel-sub">铁锭 = 1 VP 的锚点体系；只列出可收物品，未知物品为 0 不显示。支持中文、全拼、首字母和英文 ID 搜索。</p>
      </div>
      <span id="lottery-values-count" class="lottery-pill">{{ countText }}</span>
    </div>
    <div class="lottery-values-toolbar">
      <label class="search-box">
        <span aria-hidden="true">⌕</span>
        <input
          id="lottery-values-search"
          v-model="query"
          type="search"
          placeholder="搜索中文 / 拼音 / 首字母 / 英文 / ID"
          @input="scheduleLoad"
        />
      </label>
      <label class="inline-field">
        <span>分类</span>
        <select id="lottery-values-category" v-model="category" @change="reload">
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
        <select id="lottery-values-sort" v-model="sort" @change="reload">
          <option value="value-desc">价值从高到低</option>
          <option value="value-asc">价值从低到高</option>
          <option value="name">名称</option>
        </select>
      </label>
    </div>
    <div id="lottery-values-scroll" ref="scrollEl" class="lottery-value-scroll" :hidden="!rows.length">
      <div class="lottery-value-head">
        <span>物品</span><span>分类</span><span class="num">单价</span>
      </div>
      <div id="lottery-values" class="lottery-value-body">
        <div
          v-for="row in rows"
          :key="row.raw.id || row.name"
          class="lottery-value-row"
          :class="{ zero: row.zero }"
          :title="row.name + ' · ' + row.value"
        >
          <div class="lottery-value-item">
            <ItemIcon :item="row.raw" size="sm" :show-count="false" />
            <span class="lottery-value-name">{{ row.name }}</span>
            <span class="lottery-value-id">{{ row.id }}</span>
            <span v-if="row.goldLinked" class="lottery-value-gold">金价</span>
          </div>
          <span class="lottery-value-cat">{{ row.category }}</span>
          <strong class="num">{{ row.value }}</strong>
        </div>
        <button
          v-if="rows.length < total"
          class="ghost lottery-more"
          type="button"
          @click="load(false)"
        >加载更多（{{ rows.length }} / {{ total }}）</button>
      </div>
    </div>
    <div id="lottery-values-empty" class="lottery-empty" :hidden="rows.length > 0">没有匹配的物品。</div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue';
import ItemIcon from '../../components/ItemIcon.vue';
import { useApi } from '../../composables/useApi.js';
import { valueRows } from './model.js';

const props = defineProps({
  hidden: { type: Boolean, default: false },
});
const emit = defineEmits(['error']);

const { request } = useApi();
const query = ref('');
const category = ref('');
const sort = ref('value-desc');
const values = ref([]);
const total = ref(0);
const loading = ref(false);
const scrollEl = ref(null);
let timer = 0;
let loaded = false;
let seq = 0;

const rows = computed(() => valueRows(values.value));
const countText = computed(() => values.value.length + ' / ' + total.value + ' 项');

function scheduleLoad() {
  window.clearTimeout(timer);
  timer = window.setTimeout(reload, 240);
}

async function load(reset) {
  if (loading.value && !reset) return;
  if (reset) {
    values.value = [];
    total.value = 0;
  }
  const current = ++seq;
  loading.value = true;
  try {
    const params = new URLSearchParams();
    if (query.value.trim()) params.set('q', query.value.trim());
    if (category.value) params.set('category', category.value);
    params.set('sort', sort.value);
    params.set('offset', String(reset ? 0 : values.value.length));
    params.set('limit', '100');
    const result = await request('/api/lottery/values?' + params.toString());
    if (current !== seq) return;
    if (!result.ok) throw new Error(result.data?.error || '价值表读取失败');
    const page = Array.isArray(result.data?.values) ? result.data.values : [];
    values.value = reset ? page : values.value.concat(page);
    total.value = Number(result.data?.total || values.value.length);
    loaded = true;
  } catch (error) {
    if (current === seq) emit('error', '价值表读取失败：' + (error.message || '未知错误'));
  } finally {
    if (current === seq) loading.value = false;
  }
}

function reload() {
  return load(true);
}

function ensure() {
  if (!loaded) return load(true);
  return Promise.resolve();
}

onBeforeUnmount(() => window.clearTimeout(timer));

defineExpose({ ensure, reload });
</script>
