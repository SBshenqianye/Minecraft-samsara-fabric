<template>
  <span
    class="item-icon-view"
    :class="['size-' + size, { 'has-glint': glint }]"
    :aria-label="label"
  >
    <img
      v-if="!failed"
      :src="src"
      :alt="label"
      @error="failed = true"
    />
    <span v-else class="fallback">{{ (label || '?').slice(0, 1) }}</span>
    <span v-if="tint" class="tint" :style="{ background: tint }"></span>
    <span
      v-if="glint"
      class="glint"
      :style="glintMaskStyle"
      aria-hidden="true"
    ></span>
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

const label = computed(() => props.item.zh || props.item.name || props.item.id || '?');
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

const glintMaskStyle = computed(() => {
  const url = `url('${src.value}')`;
  return {
    maskImage: url,
    WebkitMaskImage: url,
  };
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

// 只有图标地址真正变化时才重试；地图拖动会不断重建 marker 对象，
// 按对象深度监听会把失败的图标反复重置，表现为闪烁 / 破图。
watch(src, () => {
  failed.value = false;
});
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
  background-image: url('/ui/enchanted_glint_item.png');
  background-repeat: repeat;
  background-size: 24px 24px;
  mix-blend-mode: screen;
  opacity: .72;
  image-rendering: pixelated;
  pointer-events: none;
  mask-repeat: no-repeat;
  mask-size: contain;
  mask-position: center;
  animation: item-glint 1.9s linear infinite;
}

.size-sm .glint {
  inset: 2px;
  background-size: 20px 20px;
}

@keyframes item-glint {
  from {
    background-position: 0 0;
  }
  to {
    background-position: 24px 24px;
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
