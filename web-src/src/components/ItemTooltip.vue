<template>
  <Teleport to="body">
    <div
      v-if="itemTooltip.visible && itemTooltip.item"
      class="mc-item-tooltip"
      :class="'rarity-' + rarity"
      :style="{ left: itemTooltip.x + 'px', top: itemTooltip.y + 'px' }"
    >
      <div class="mc-tooltip-name">{{ item.customName || item.name || item.id }}</div>
      <div class="mc-tooltip-id">{{ item.id }}</div>

      <div v-if="lore.length" class="mc-tooltip-lore">
        <div v-for="(line, index) in lore" :key="'lore-' + index" class="mc-tooltip-lore-line">
          {{ line }}
        </div>
      </div>

      <div v-if="enchantments.length" class="mc-tooltip-ench">
        <div v-for="row in enchantments" :key="'ench-' + row.id" class="mc-tooltip-line">
          <span>{{ row.name || row.id }}</span>
          <span class="mc-tooltip-level">{{ roman(row.level) }}</span>
        </div>
      </div>

      <div v-if="potionEffects.length" class="mc-tooltip-potion">
        <div v-for="row in potionEffects" :key="'effect-' + row.id" class="mc-tooltip-line">
          <span>{{ row.name || row.id }}</span>
          <span class="mc-tooltip-muted">{{ effectTime(row) }}</span>
        </div>
      </div>

      <div v-if="attributeGroups.length" class="mc-tooltip-attrs">
        <div v-for="group in attributeGroups" :key="'attr-' + group.slot" class="mc-tooltip-attr-group">
          <div class="mc-tooltip-slots">{{ group.label }}</div>
          <div v-for="(row, index) in group.rows" :key="'attr-line-' + index" class="mc-tooltip-line">
            <span :class="row.positive ? 'mc-tooltip-positive' : 'mc-tooltip-negative'">
              {{ row.text }}
            </span>
            <span class="mc-tooltip-muted">{{ row.name }}</span>
          </div>
        </div>
      </div>

      <div v-if="durability" class="mc-tooltip-durability">{{ durability }}</div>
      <div v-if="item.componentCount > 0" class="mc-tooltip-components">
        {{ item.componentCount }} 个组件
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue';
import { itemTooltip } from './itemTooltip.js';

const item = computed(() => itemTooltip.item || {});

const rarity = computed(() => {
  const value = String(item.value.rarity || 'common').toLowerCase();
  return ['common', 'uncommon', 'rare', 'epic'].includes(value) ? value : 'common';
});

const lore = computed(() => Array.isArray(item.value.lore) ? item.value.lore : []);

const enchantments = computed(() => {
  const rows = Array.isArray(item.value.enchantments) ? item.value.enchantments : [];
  return rows.slice().sort((a, b) => String(a.name || a.id).localeCompare(String(b.name || b.id)));
});

const potionEffects = computed(() => {
  const potion = item.value.potion || {};
  return Array.isArray(potion.effects) ? potion.effects : [];
});

const attributeGroups = computed(() => {
  const rows = Array.isArray(item.value.attributes) ? item.value.attributes : [];
  const groups = new Map();
  for (const row of rows) {
    const slot = String(row.slot || 'any');
    if (!groups.has(slot)) groups.set(slot, { slot, label: slotLabel(slot), rows: [] });
    const amount = Number(row.amount || 0);
    const operation = String(row.operation || 'add_value');
    const positive = amount >= 0;
    const text = operation === 'add_value'
      ? formatAmount(amount) + ' ' + (row.name || row.id)
      : formatPercent(amount) + ' ' + (row.name || row.id);
    groups.get(slot).rows.push({ name: operationLabel(operation), text, positive });
  }
  return [...groups.values()];
});

const durability = computed(() => {
  const max = Number(item.value.maxDamage || 0);
  if (max <= 0) return '';
  const damage = Number(item.value.damage || 0);
  const left = Math.max(0, max - damage);
  return '耐久度: ' + left + ' / ' + max;
});

function slotLabel(slot) {
  return ({
    mainhand: '在主手时：',
    offhand: '在副手时：',
    hand: '在主手或副手时：',
    armor: '穿戴时：',
    any: '手持时：',
  })[slot] || '属性：';
}

function operationLabel(operation) {
  return ({
    add_value: '固定值',
    add_multiplied_base: '基础倍率',
    add_multiplied_total: '总倍率',
  })[operation] || '';
}

function formatAmount(value) {
  const rounded = Math.round(value * 100) / 100;
  return (rounded >= 0 ? '+' : '') + rounded;
}

function formatPercent(value) {
  const rounded = Math.round(value * 10000) / 100;
  return (rounded >= 0 ? '+' : '') + rounded + '%';
}

function effectTime(row) {
  const duration = Number(row.duration || 0);
  const amplifier = Number(row.amplifier || 0);
  const seconds = Math.max(0, Math.round(duration / 20));
  const time = seconds >= 60
    ? Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0')
    : seconds + 's';
  return time + (amplifier > 0 ? ' · ' + roman(amplifier + 1) : '');
}

function roman(value) {
  const number = Math.max(1, Math.min(255, Number(value) || 1));
  if (number > 10) return String(number);
  return ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'][number];
}
</script>

<style scoped>
.mc-item-tooltip {
  position: fixed;
  z-index: 99999;
  width: 306px;
  max-height: 420px;
  overflow: hidden;
  padding: 10px 12px 9px;
  border: 2px solid #2b0a4a;
  border-radius: 4px;
  background:
    linear-gradient(180deg, rgba(24, 8, 32, .97), rgba(12, 4, 18, .98)),
    repeating-linear-gradient(0deg, rgba(255, 255, 255, .015) 0 1px, transparent 1px 3px);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, .08) inset,
    0 10px 28px rgba(0, 0, 0, .55);
  color: #d8d8d8;
  font: 13px/1.42 'Minecraft', 'Microsoft YaHei', system-ui, sans-serif;
  text-shadow: 1.5px 1.5px 0 rgba(0, 0, 0, .9);
  pointer-events: none;
}

.mc-tooltip-name {
  margin-bottom: 1px;
  font-size: 14px;
  font-weight: 700;
}

.rarity-common .mc-tooltip-name { color: #ffffff; }
.rarity-uncommon .mc-tooltip-name { color: #ffff55; }
.rarity-rare .mc-tooltip-name { color: #55ffff; }
.rarity-epic .mc-tooltip-name { color: #ff55ff; }

.mc-tooltip-id {
  color: #7b86d9;
  font-size: 12px;
  word-break: break-all;
}

.mc-tooltip-lore {
  margin-top: 5px;
  color: #b78bff;
}

.mc-tooltip-ench,
.mc-tooltip-potion,
.mc-tooltip-attrs {
  margin-top: 5px;
}

.mc-tooltip-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  color: #b8b8b8;
}

.mc-tooltip-line > span:first-child {
  min-width: 0;
}

.mc-tooltip-level {
  color: #b8b8b8;
  white-space: nowrap;
}

.mc-tooltip-muted {
  color: #8a8a8a;
  font-size: 12px;
  white-space: nowrap;
}

.mc-tooltip-attr-group + .mc-tooltip-attr-group {
  margin-top: 4px;
}

.mc-tooltip-slots {
  margin-top: 4px;
  color: #a8a8a8;
}

.mc-tooltip-positive {
  color: #55ff55;
}

.mc-tooltip-negative {
  color: #ff5555;
}

.mc-tooltip-durability {
  margin-top: 6px;
  color: #d8d8d8;
}

.mc-tooltip-components {
  margin-top: 4px;
  color: #777777;
  font-size: 11px;
}
</style>
