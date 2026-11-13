<template>
  <Teleport to="body">
    <div
      v-if="itemTooltip.visible && itemTooltip.item"
      ref="tipEl"
      popover="manual"
      class="mc-item-tooltip"
      :class="'rarity-' + rarity"
      :style="{ left: itemTooltip.x + 'px', top: itemTooltip.y + 'px' }"
    >
      <div class="mc-tooltip-name" :class="{ renamed: !!item.customName }">
        {{ displayName }}
      </div>
      <div v-if="item.customName && baseName && baseName !== displayName" class="mc-tooltip-basename">
        {{ baseName }}
      </div>

      <div v-if="lore.length" class="mc-tooltip-lore">
        <div v-for="(line, index) in lore" :key="'lore-' + index" class="mc-tooltip-lore-line">
          {{ line }}
        </div>
      </div>

      <div v-if="enchantments.length" class="mc-tooltip-ench">
        <div v-for="row in enchantments" :key="'ench-' + row.id" class="mc-tooltip-line">
          <span>{{ row.label }}</span>
          <span class="mc-tooltip-level">{{ roman(row.level) }}</span>
        </div>
      </div>

      <div v-if="potionEffects.length" class="mc-tooltip-potion">
        <div v-for="row in potionEffects" :key="'effect-' + row.id" class="mc-tooltip-line">
          <span>{{ row.label }}</span>
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
            <span class="mc-tooltip-muted">{{ row.operation }}</span>
          </div>
        </div>
      </div>

      <div v-if="item.unbreakable" class="mc-tooltip-unbreakable">不可破坏</div>

      <div v-if="durability" class="mc-tooltip-durability">
        <span>耐久度：{{ durability.left }} / {{ durability.max }}</span>
        <span class="mc-tooltip-durability-bar">
          <span
            class="mc-tooltip-durability-fill"
            :style="{ width: durability.percent + '%', background: durability.color }"
          ></span>
        </span>
      </div>

      <div class="mc-tooltip-footer">
        <span class="mc-tooltip-id">{{ item.id }}</span>
        <span v-if="rarityLabel" class="mc-tooltip-rarity">{{ rarityLabel }}</span>
      </div>
      <div v-if="item.componentCount > 0" class="mc-tooltip-components">
        {{ item.componentCount }} 个数据组件
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue';
import { itemTooltip } from './itemTooltip.js';
import { zhAttribute, zhEffect, zhEnchantment, zhRarity } from './itemZh.js';

const tipEl = ref(null);
const item = computed(() => itemTooltip.item || {});

// Web 背包在 top layer；tooltip 也放进 top layer，否则会被 popover 遮住。
watch(() => itemTooltip.visible && !!itemTooltip.item, async (visible) => {
  await nextTick();
  const el = tipEl.value;
  if (!el || typeof el.showPopover !== 'function') return;
  try {
    if (visible && !el.matches(':popover-open')) el.showPopover();
    else if (!visible && el.matches(':popover-open')) el.hidePopover();
  } catch {
    // 浏览器不支持 popover 时退回普通 fixed 层。
  }
}, { flush: 'post' });

const rarity = computed(() => {
  const value = String(item.value.rarity || 'common').toLowerCase();
  return ['common', 'uncommon', 'rare', 'epic'].includes(value) ? value : 'common';
});

const rarityLabel = computed(() => zhRarity(rarity.value));

const displayName = computed(() =>
  item.value.customName || item.value.zh || item.value.name || item.value.id || '');
const baseName = computed(() => item.value.zh || item.value.name || item.value.id || '');

const lore = computed(() => Array.isArray(item.value.lore) ? item.value.lore : []);

const enchantments = computed(() => {
  const rows = Array.isArray(item.value.enchantments) ? item.value.enchantments : [];
  return rows
    .map((row) => ({ ...row, label: zhEnchantment(row.id, row.name) }))
    .sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'));
});

const potionEffects = computed(() => {
  const potion = item.value.potion || {};
  const rows = Array.isArray(potion.effects) ? potion.effects : [];
  return rows.map((row) => ({ ...row, label: zhEffect(row.id, row.name) }));
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
    const name = zhAttribute(row.id, row.name);
    const text = operation === 'add_value'
      ? formatAmount(amount) + ' ' + name
      : formatPercent(amount) + ' ' + name;
    groups.get(slot).rows.push({ operation: operationLabel(operation), text, positive });
  }
  return [...groups.values()];
});

const durability = computed(() => {
  const max = Number(item.value.maxDamage || 0);
  if (max <= 0) return null;
  const damage = Number(item.value.damage || 0);
  const left = Math.max(0, max - damage);
  const percent = Math.max(0, Math.min(100, Math.round(left / max * 100)));
  const hue = Math.round(percent * 1.2);
  return { left, max, percent, color: `hsl(${hue} 85% 48%)` };
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
    : seconds + ' 秒';
  return time + (amplifier > 0 ? ' · ' + roman(amplifier + 1) + ' 级' : '');
}

function roman(value) {
  const number = Math.max(1, Math.min(255, Number(value) || 1));
  if (number > 10) return String(number);
  return ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'][number];
}
</script>

<style scoped>
.mc-item-tooltip[popover] {
  inset: auto;
  margin: 0;
  padding: 8px 10px 8px;
  border: 0;
  color: inherit;
}

.mc-item-tooltip {
  position: fixed;
  z-index: 99999;
  width: 306px;
  max-height: 460px;
  overflow: hidden;
  padding: 8px 10px 8px;
  border: 1px solid #2b0a4a;
  border-radius: 2px;
  background:
    linear-gradient(180deg, rgba(16, 0, 16, .96), rgba(12, 2, 16, .98)),
    repeating-linear-gradient(0deg, rgba(255, 255, 255, .015) 0 1px, transparent 1px 3px);
  box-shadow:
    0 0 0 1px #190033 inset,
    0 3px 0 1px rgba(30, 6, 50, .9),
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

.mc-tooltip-name.renamed {
  font-style: italic;
}

.rarity-common .mc-tooltip-name { color: #ffffff; }
.rarity-uncommon .mc-tooltip-name { color: #ffff55; }
.rarity-rare .mc-tooltip-name { color: #55ffff; }
.rarity-epic .mc-tooltip-name { color: #ff55ff; }

.mc-tooltip-basename {
  color: #9a9a9a;
  font-size: 11px;
}

.mc-tooltip-lore {
  margin-top: 5px;
  color: #b78bff;
  font-style: italic;
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

.mc-tooltip-unbreakable {
  margin-top: 5px;
  color: #7ec6ff;
}

.mc-tooltip-durability {
  margin-top: 6px;
  color: #d8d8d8;
}

.mc-tooltip-durability-bar {
  display: block;
  height: 4px;
  margin-top: 3px;
  border: 1px solid #000;
  background: #1a1a1a;
}

.mc-tooltip-durability-fill {
  display: block;
  height: 100%;
}

.mc-tooltip-footer {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-top: 7px;
  padding-top: 5px;
  border-top: 1px solid rgba(255, 255, 255, .08);
}

.mc-tooltip-id {
  color: #777777;
  font-size: 11px;
  word-break: break-all;
}

.mc-tooltip-rarity {
  color: #8a8a8a;
  font-size: 11px;
  white-space: nowrap;
}

.mc-tooltip-components {
  margin-top: 2px;
  color: #6a6a6a;
  font-size: 11px;
}
</style>
