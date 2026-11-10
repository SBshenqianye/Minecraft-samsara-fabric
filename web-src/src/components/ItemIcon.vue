<template>
  <span
    class="item-icon-view"
    :class="['size-' + size, { 'has-glint': glint }]"
    :title="tooltip"
  >
    <img
      v-if="!failed"
      :src="src"
      :alt="label"
      @error="failed = true"
    />
    <span v-else class="fallback">{{ (label || '?').slice(0, 1) }}</span>
    <span v-if="tint" class="tint" :style="{ background: tint }"></span>
    <span v-if="glint" class="glint" aria-hidden="true"></span>
    <span v-if="showCount && count > 1" class="count">{{ count }}</span>
  </span>
</template>

<script setup>
import { computed, ref, watch } from 'vue';

const props = defineProps({
  item: { type: Object, default: () => ({}) },
  size: { type: String, default: 'md' },
  showCount: { type: Boolean, default: true },
});

const failed = ref(false);

const label = computed(() => props.item.name || props.item.zh || props.item.id || '?');
const count = computed(() => Number(props.item.count || 1));

const icon = computed(() => {
  const explicit = String(props.item.icon || '').trim();
  const raw = explicit || String(props.item.id || '');
  return (raw.includes(':') ? raw.slice(raw.indexOf(':') + 1) : raw) || 'stone';
});

const src = computed(() => '/items/' + icon.value + '.png');

const components = computed(() => String(props.item.components || ''));

const glint = computed(() => {
  if (typeof props.item.glint === 'boolean') return props.item.glint;
  if (props.item.glint === true) return true;
  // 只匹配“非空”附魔表；空的 enchantments={} 不应该发光。
  const text = components.value;
  if (/enchantment_glint_override\s*[=:]\s*true/i.test(text)) return true;
  if (/(?:^|[^a-z_])(stored_)?enchantments\s*[=:]\s*\{[^}]/i.test(text)) return true;
  return false;
});

const tint = computed(() => {
  const raw = props.item.tint;
  if (typeof raw === 'number' && Number.isFinite(raw)) {
    return '#' + (raw & 0xFFFFFF).toString(16).padStart(6, '0');
  }
  if (raw) return String(raw);
  if (props.item.color) {
    const value = Number(props.item.color);
    if (Number.isFinite(value)) {
      return '#' + (value & 0xFFFFFF).toString(16).padStart(6, '0');
    }
  }
  const dyed = components.value.match(/dyed_color?\s*[=:]\s*(?:[^,}]*?rgb\s*[=:]\s*)?(-?\d+)/i);
  const potion = components.value.match(/custom_color\s*[=:]\s*(-?\d+)/i);
  const match = dyed || potion;
  if (!match) return '';
  const value = Number(match[1]);
  if (!Number.isFinite(value)) return '';
  return '#' + (value & 0xFFFFFF).toString(16).padStart(6, '0');
});

const tooltip = computed(() => {
  const parts = [label.value, props.item.id || '', '数量 ' + count.value];
  if (components.value) parts.push(components.value);
  return parts.filter(Boolean).join('\n');
});

watch(() => props.item, () => {
  failed.value = false;
}, { deep: true });
</script>

<style scoped>
.item-icon-view {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
}

.item-icon-view img {
  width: 32px;
  height: 32px;
  image-rendering: pixelated;
  object-fit: contain;
}

.size-sm {
  width: 30px;
  height: 30px;
}

.size-sm img {
  width: 24px;
  height: 24px;
}

.fallback {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--line);
  border-radius: 6px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 700;
}

.tint {
  position: absolute;
  inset: 4px;
  border-radius: 4px;
  mix-blend-mode: multiply;
  opacity: .55;
  pointer-events: none;
}

.glint {
  position: absolute;
  inset: 3px;
  border-radius: 4px;
  background: linear-gradient(120deg,
    transparent 28%, rgba(255, 255, 255, .75) 50%, transparent 72%);
  background-size: 220% 100%;
  mix-blend-mode: screen;
  pointer-events: none;
  animation: item-glint 2.6s linear infinite;
}

.size-sm .glint {
  inset: 2px;
}

@keyframes item-glint {
  from {
    background-position: 120% 0;
  }
  to {
    background-position: -120% 0;
  }
}

.count {
  position: absolute;
  right: 0;
  bottom: 0;
  min-width: 14px;
  padding: 0 3px;
  border-radius: 5px;
  background: color-mix(in srgb, var(--panel) 82%, transparent);
  color: var(--fg);
  font-size: 10px;
  font-weight: 700;
  line-height: 14px;
  text-align: center;
}
</style>
