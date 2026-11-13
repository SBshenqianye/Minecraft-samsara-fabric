<template>
  <section class="creative-library" aria-label="创造模式物品库">
    <div class="creative-tabs creative-tabs-top" role="tablist" aria-label="物品分类">
      <button
        v-for="tab in tabs.slice(0, 6)"
        :key="tab.id"
        type="button"
        role="tab"
        :aria-selected="category === tab.id ? 'true' : 'false'"
        :aria-label="tab.label"
        :class="{ active: category === tab.id }"
        :title="tab.label"
        @click="category = tab.id"
      >
        <img :src="'/items/' + tab.icon + '.png'" alt="" loading="lazy" />
      </button>
    </div>

    <div class="creative-toolbar">
      <input
        v-model="query"
        type="search"
        placeholder="搜索中文 / 英文 / ID / 拼音"
        aria-label="搜索物品"
      />
      <Segmented v-model="sort" :options="sortOptions" aria-label="物品排序" />
      <span class="muted small">{{ filtered.length }} / {{ all.length }}</span>
    </div>

    <div ref="scrollBox" class="creative-grid" @scroll="onScroll">
      <button
        v-for="row in visibleRows"
        :key="row.id"
        type="button"
        class="creative-slot"
        :title="(row.zh || row.name || row.id) + '\n' + row.id"
        @click="pick(row, $event)"
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

    <div class="creative-tabs creative-tabs-bottom" role="tablist" aria-label="更多物品分类">
      <button
        v-for="tab in tabs.slice(6)"
        :key="tab.id"
        type="button"
        role="tab"
        :aria-selected="category === tab.id ? 'true' : 'false'"
        :aria-label="tab.label"
        :class="{ active: category === tab.id }"
        :title="tab.label"
        @click="category = tab.id"
      >
        <img :src="'/items/' + tab.icon + '.png'" alt="" loading="lazy" />
      </button>
    </div>

    <p v-if="error" class="container-message error">{{ error }}</p>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, toRefs, watch } from 'vue';
import ItemIcon from './ItemIcon.vue';
import Segmented from './Segmented.vue';
import { filterItems } from '../composables/itemSearch.js';
import { hideItemTooltip, itemTooltip, moveItemTooltip, showItemTooltip } from './itemTooltip.js';

const emit = defineEmits(['pick']);

const PAGE = 200;
const sortOptions = [
  { value: 'name', label: '名称' },
  { value: 'value-desc', label: '价值 ↓' },
  { value: 'value-asc', label: '价值 ↑' },
  { value: 'id', label: 'ID' },
];
const tabs = [
  { id: 'building', label: '建筑方块', icon: 'bricks' },
  { id: 'colored', label: '染色方块', icon: 'red_wool' },
  { id: 'nature', label: '自然方块', icon: 'grass_block' },
  { id: 'functional', label: '功能方块', icon: 'crafting_table' },
  { id: 'redstone', label: '红石', icon: 'redstone' },
  { id: 'search', label: '搜索物品', icon: 'compass' },
  { id: 'tools', label: '工具与实用物品', icon: 'iron_pickaxe' },
  { id: 'combat', label: '战斗用品', icon: 'iron_sword' },
  { id: 'food', label: '食物与饮品', icon: 'apple' },
  { id: 'ingredients', label: '原材料', icon: 'iron_ingot' },
  { id: 'spawn_egg', label: '刷怪蛋', icon: 'cow_spawn_egg' },
  { id: 'debug', label: '调试方块', icon: 'command_block' },
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
// 搜索/分类/排序挂到全局：Web 背包浮窗关闭再打开时组件会重建，但条件不重置。
const state = globalThis.__samsaraCreativeLibrary
  || (globalThis.__samsaraCreativeLibrary = reactive({
    query: '', category: 'search', sort: 'name',
  }));
const { query, category, sort } = toRefs(state);
const visibleCount = ref(PAGE);
const scrollBox = ref(null);
let cachePromise = null;

const KEYWORDS = {
  colored: ['wool', 'concrete', 'terracotta', 'stained_glass', 'candle', 'carpet',
    'glazed_terracotta', 'banner', 'shulker_box', 'bed'],
  building: ['planks', 'log', 'wood', 'stone', 'cobble', 'brick', 'deepslate',
    'blackstone', 'basalt', 'tuff', 'calcite', 'quartz', 'purpur', 'prismarine',
    'sandstone', 'granite', 'diorite', 'andesite', 'netherrack', 'end_stone',
    'obsidian', 'glass', 'concrete', 'terracotta'],
  nature: ['leaves', 'sapling', 'flower', 'grass', 'dirt', 'sand', 'gravel', 'clay',
    'mud', 'moss', 'vine', 'mushroom', 'coral', 'kelp', 'seagrass', 'cactus',
    'bamboo', 'sugar_cane', 'pumpkin', 'melon', 'cocoa', 'stem', 'hyphae',
    'shroomlight', 'glow_lichen'],
  functional: ['crafting_table', 'furnace', 'chest', 'barrel', 'hopper', 'beacon',
    'anvil', 'enchanting_table', 'brewing_stand', 'cauldron', 'composter',
    'smithing_table', 'loom', 'stonecutter', 'cartography_table', 'fletching_table',
    'grindstone', 'lectern', 'jukebox', 'bell', 'respawn_anchor', 'lodestone',
    'conduit', 'campfire', 'torch', 'lantern', 'chain', 'scaffolding', 'ladder',
    'crafting', 'door', 'trapdoor', 'fence', 'stairs', 'slab', 'wall'],
  redstone: ['redstone', 'piston', 'repeater', 'comparator', 'observer', 'dispenser',
    'dropper', 'hopper', 'rail', 'lever', 'button', 'pressure_plate', 'daylight_detector',
    'tripwire', 'target', 'note_block', 'jukebox', 'lamp', 'copper_bulb', 'sculk_sensor',
    'crafter', 'lectern', 'tnt'],
  tools: ['pickaxe', 'axe', 'shovel', 'hoe', 'shears', 'flint_and_steel', 'fishing_rod',
    'brush', 'sponge', 'bucket', 'compass', 'clock', 'map', 'lead', 'name_tag', 'trident',
    'elytra', 'minecart', 'boat', 'raft', 'saddle'],
  combat: ['sword', 'helmet', 'chestplate', 'leggings', 'boots', 'shield', 'bow', 'crossbow',
    'arrow', 'totem', 'mace', 'wind_charge'],
  food: ['apple', 'bread', 'beef', 'porkchop', 'chicken', 'mutton', 'cod', 'salmon', 'cookie',
    'cake', 'stew', 'soup', 'carrot', 'potato', 'beetroot', 'melon_slice', 'berries', 'honey',
    'kelp', 'pumpkin_pie', 'sweet_berries', 'golden_apple', 'golden_carrot'],
  ingredients: ['ingot', 'nugget', 'diamond', 'emerald', 'coal', 'charcoal', 'stick',
    'string', 'feather', 'leather', 'bone', 'gunpowder', 'blaze_rod', 'blaze_powder',
    'ender_pearl', 'ender_eye', 'nether_star', 'nether_wart', 'glowstone_dust',
    'redstone', 'quartz', 'amethyst_shard', 'copper_ingot', 'raw_', 'scrap', 'shell'],
};

function classify(id) {
  const path = String(id || '').replace(/^minecraft:/, '').toLowerCase();
  if (path.endsWith('_spawn_egg')) return 'spawn_egg';
  if (path.includes('tipped_arrow')) return 'combat';
  if (path.includes('potion') || path.endsWith('_arrow')) return path.includes('arrow') ? 'combat' : 'food';
  if (path.includes('enchanted_book')) return 'ingredients';
  if (path.endsWith('_ingot') || path.endsWith('_nugget') || path.startsWith('raw_')
      || path.endsWith('_ore') || path.includes('_ore_')) return 'ingredients';
  if (path.includes('copper')) return 'building';
  const order = ['colored', 'combat', 'food', 'tools', 'redstone', 'functional',
    'nature', 'building', 'ingredients'];
  for (const key of order) {
    if (KEYWORDS[key].some((word) => path.includes(word))) return key;
  }
  return categoryFallback(path);
}

function categoryFallback(path) {
  if (path.includes('dye') || path.includes('potion')) return 'ingredients';
  return 'functional';
}

const filtered = computed(() => {
  const text = query.value.trim().toLowerCase();
  let rows = category.value === 'debug' ? DEBUG_ITEMS : all.value;
  if (category.value !== 'all' && category.value !== 'search' && category.value !== 'debug') {
    rows = rows.filter((row) => row._tab === category.value);
  }
  if (text) rows = filterItems(rows, text);
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

function pick(row, event) {
  emit('pick', Object.assign({}, row, { shift: event?.shiftKey === true }));
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
  grid-template-columns: repeat(auto-fill, minmax(32px, 1fr));
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
  width: 100%;
  min-height: 32px;
  aspect-ratio: 1;
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
