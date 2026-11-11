<template>
  <section class="creative-library" aria-label="创造模式物品库">
    <div class="creative-tabs" role="tablist" aria-label="物品分类">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        type="button"
        role="tab"
        :aria-selected="category === tab.id ? 'true' : 'false'"
        :class="{ active: category === tab.id }"
        :title="tab.label"
        @click="category = tab.id"
      >{{ tab.label }}</button>
    </div>

    <div class="creative-toolbar">
      <input
        v-model="query"
        type="search"
        placeholder="搜索中文 / 英文 / ID / 拼音"
        aria-label="搜索物品"
      />
      <select v-model="sort" aria-label="物品排序">
        <option value="name">按名称</option>
        <option value="value-desc">价值从高到低</option>
        <option value="value-asc">价值从低到高</option>
        <option value="id">按 ID</option>
      </select>
      <span class="muted small">{{ filtered.length }} / {{ all.length }}</span>
    </div>

    <div ref="scrollBox" class="creative-grid" @scroll="onScroll">
      <button
        v-for="row in visibleRows"
        :key="row.id"
        type="button"
        class="creative-slot"
        :title="(row.zh || row.name || row.id) + '\n' + row.id"
        @click="pick(row)"
        @mouseenter="hover(row, $event)"
        @mousemove="move($event)"
        @mouseleave="leave"
      >
        <ItemIcon :item="row" size="sm" :show-count="false" />
      </button>
      <p v-if="loading" class="muted small creative-empty">加载物品库…</p>
      <p v-else-if="!filtered.length" class="muted small creative-empty">
        {{ query ? '没有匹配的物品。' : '这个分类没有物品。' }}
      </p>
    </div>

    <p v-if="error" class="container-message error">{{ error }}</p>
    <p class="muted small creative-hint">点击物品放到光标；按住 Shift 点击可连续取用。物品无限，不消耗。</p>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import ItemIcon from './ItemIcon.vue';
import { hideItemTooltip, itemTooltip, moveItemTooltip, showItemTooltip } from './itemTooltip.js';

const emit = defineEmits(['pick']);

const PAGE = 200;
const tabs = [
  { id: 'all', label: '全部' },
  { id: 'building', label: '建筑' },
  { id: 'decoration', label: '装饰' },
  { id: 'redstone', label: '红石' },
  { id: 'transport', label: '交通' },
  { id: 'food', label: '食物' },
  { id: 'tools', label: '工具' },
  { id: 'combat', label: '战斗' },
  { id: 'brewing', label: '酿造' },
  { id: 'misc', label: '杂项' },
  { id: 'debug', label: '调试方块' },
];

// 调试方块原本挂在各页面工作台的物品库里；现在只在 Web 背包物品库保留一份。
const DEBUG_ITEMS = [
  { id: 'minecraft:command_block', name: '命令方块' },
  { id: 'minecraft:chain_command_block', name: '连锁命令方块' },
  { id: 'minecraft:repeating_command_block', name: '循环命令方块' },
  { id: 'minecraft:structure_block', name: '结构方块' },
  { id: 'minecraft:jigsaw', name: '拼图方块' },
  { id: 'minecraft:barrier', name: '屏障' },
  { id: 'minecraft:light', name: '光源方块' },
  { id: 'minecraft:structure_void', name: '结构空位' },
  { id: 'minecraft:debug_stick', name: '调试棒' },
  { id: 'minecraft:spawner', name: '刷怪笼' },
  { id: 'minecraft:bedrock', name: '基岩' },
  { id: 'minecraft:end_portal_frame', name: '末地传送门框架' },
  { id: 'minecraft:command_block_minecart', name: '命令方块矿车' },
  { id: 'minecraft:knowledge_book', name: '知识之书' },
].map((row) => Object.assign({}, row, { zh: row.name, _tab: 'debug' }));

const all = ref([]);
const loading = ref(false);
const error = ref('');
const category = ref('all');
const query = ref('');
const sort = ref('name');
const visibleCount = ref(PAGE);
const scrollBox = ref(null);
let cachePromise = null;

const KEYWORDS = {
  building: ['planks', 'log', 'wood', 'stone', 'cobble', 'brick', 'concrete', 'terracotta',
    'wool', 'glass', 'sand', 'dirt', 'gravel', 'quartz', 'purpur', 'prismarine', 'deepslate',
    'blackstone', 'basalt', 'tuff', 'calcite', 'mud', 'leaves', 'sapling', 'stem', 'hyphae',
    'sandstone', 'granite', 'diorite', 'andesite', 'netherrack', 'end_stone', 'obsidian',
    'concrete_powder', 'glowstone', 'sea_lantern', 'shroomlight', 'moss', 'vine'],
  decoration: ['flower', 'pot', 'painting', 'banner', 'carpet', 'torch', 'lantern', 'candle',
    'sign', 'chain', 'item_frame', 'head', 'bed', 'skull', 'ladder', 'scaffolding', 'coral',
    'candle', 'chiseled', 'bookshelf', 'campfire', 'bell', 'armor_stand', 'flower_pot'],
  redstone: ['redstone', 'piston', 'repeater', 'comparator', 'observer', 'dispenser',
    'dropper', 'hopper', 'rail', 'lever', 'button', 'pressure_plate', 'daylight_detector',
    'tripwire', 'target', 'note_block', 'jukebox', 'lamp', 'copper_bulb', 'sculk_sensor',
    'crafter', 'lectern'],
  transport: ['minecart', 'boat', 'raft', 'saddle', 'elytra', 'rail'],
  food: ['apple', 'bread', 'beef', 'porkchop', 'chicken', 'mutton', 'cod', 'salmon', 'cookie',
    'cake', 'stew', 'soup', 'carrot', 'potato', 'beetroot', 'melon', 'berries', 'honey',
    'kelp', 'mushroom_stew', 'rabbit_stew', 'pumpkin_pie', 'dried_kelp', 'sweet_berries'],
  tools: ['pickaxe', 'axe', 'shovel', 'hoe', 'shears', 'flint_and_steel', 'fishing_rod',
    'brush', 'sponge', 'bucket', 'compass', 'clock', 'map', 'lead', 'name_tag', 'trident'],
  combat: ['sword', 'helmet', 'chestplate', 'leggings', 'boots', 'shield', 'bow', 'crossbow',
    'arrow', 'totem', 'mace', 'wind_charge'],
  brewing: ['potion', 'glass_bottle', 'brewing_stand', 'blaze_powder', 'nether_wart',
    'ghast_tear', 'spider_eye', 'glistering_melon', 'golden_carrot', 'fermented_spider_eye',
    'dragon_breath', 'magma_cream', 'rabbit_foot', 'phantom_membrane', 'sugar', 'glowstone_dust'],
};

function classify(id) {
  const path = String(id || '').replace(/^minecraft:/, '').toLowerCase();
  for (const key of ['combat', 'brewing', 'transport', 'redstone', 'food', 'tools', 'building', 'decoration']) {
    if (KEYWORDS[key].some((word) => path.includes(word))) return key;
  }
  return 'misc';
}

const filtered = computed(() => {
  const text = query.value.trim().toLowerCase();
  let rows = category.value === 'debug' ? DEBUG_ITEMS : all.value;
  if (category.value !== 'all' && category.value !== 'debug') {
    rows = rows.filter((row) => row._tab === category.value);
  }
  if (text) {
    rows = rows.filter((row) => [row.zh, row.name, row.id, row.py, row.initials]
      .some((value) => String(value || '').toLowerCase().includes(text)));
  }
  const sorted = rows.slice();
  if (sort.value === 'value-desc') sorted.sort((a, b) => (b.value || 0) - (a.value || 0));
  else if (sort.value === 'value-asc') sorted.sort((a, b) => (a.value || 0) - (b.value || 0));
  else if (sort.value === 'id') sorted.sort((a, b) => String(a.id).localeCompare(String(b.id)));
  else sorted.sort((a, b) => String(a.zh || a.name || a.id)
    .localeCompare(String(b.zh || b.name || b.id), 'zh-CN'));
  return sorted;
});

const visibleRows = computed(() => filtered.value.slice(0, visibleCount.value));

watch([category, query, sort], () => {
  visibleCount.value = PAGE;
  if (scrollBox.value) scrollBox.value.scrollTop = 0;
  visibleCount.value = PAGE;
});

async function api(path) {
  const token = sessionStorage.getItem('samsara-token') || '';
  const response = await fetch(path, { headers: { Authorization: 'Bearer ' + token } });
  const text = await response.text();
  let data = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = { error: text };
  }
  if (!response.ok || data.ok === false) {
    throw new Error(data.error || ('加载失败：' + response.status));
  }
  return data;
}

async function ensureAll() {
  if (all.value.length) return all.value;
  if (cachePromise) return cachePromise;
  cachePromise = (async () => {
    const rows = [];
    let offset = 0;
    let total = Infinity;
    while (rows.length < total && offset < 4000) {
      const data = await api(`/api/lottery/values?q=&sort=value-desc&offset=${offset}&limit=${PAGE}`);
      total = Number(data.total || 0);
      const page = data.values || [];
      rows.push(...page);
      offset += PAGE;
      if (!page.length) break;
    }
    all.value = rows.map((row) => Object.assign({}, row, { _tab: classify(row.id) }));
    return all.value;
  })();
  return cachePromise;
}

onMounted(async () => {
  loading.value = true;
  try {
    await ensureAll();
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
});

function onScroll(event) {
  const node = event.target;
  if (node.scrollTop + node.clientHeight >= node.scrollHeight - 120
      && visibleCount.value < filtered.value.length) {
    visibleCount.value += PAGE;
  }
}

function pick(row) {
  emit('pick', row);
}

function hover(row, event) {
  showItemTooltip(row, event);
}

function move(event) {
  moveItemTooltip(event);
}

function leave() {
  hideItemTooltip();
}

defineExpose({ reload: ensureAll });
</script>

<style scoped>
.creative-library {
  display: flex;
  flex-direction: column;
  min-height: 0;
  gap: 8px;
}

.creative-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--panel-soft);
}

.creative-tabs button {
  padding: 3px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
  cursor: pointer;
}

.creative-tabs button.active {
  background: var(--accent);
  color: #fff;
}

.creative-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.creative-toolbar input[type='search'] {
  flex: 1 1 160px;
  min-width: 120px;
  padding: 5px 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-soft);
  color: var(--fg);
}

.creative-grid {
  display: grid;
  grid-template-columns: repeat(9, 32px);
  gap: 3px;
  align-content: start;
  min-height: 200px;
  max-height: 54vh;
  padding: 6px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #1c1c1c;
}

.creative-slot {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid #3a3a3a;
  border-radius: 4px;
  background: #2b2b2b;
  cursor: pointer;
}

.creative-slot:hover {
  border-color: #f0f0f0;
  background: #3d3d3d;
}

.creative-empty {
  grid-column: 1 / -1;
  margin: 6px 0;
  color: #b9b9b9;
}

.creative-hint {
  margin: 0;
}
</style>
