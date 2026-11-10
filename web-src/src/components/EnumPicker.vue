<template>
  <span ref="root" class="enum-picker" :class="{ open }">
    <button type="button" class="enum-trigger" :title="currentLabel" @click="toggle">
      <span class="enum-value">{{ currentLabel }}</span>
      <span class="enum-caret">▾</span>
    </button>
    <div v-if="open" class="enum-popover">
      <input
        ref="searchInput"
        v-model="search"
        class="enum-search"
        type="text"
        placeholder="搜索名称或 ID"
        @keyup.enter="chooseFirst"
      />
      <div class="enum-list">
        <button
          v-for="option in filtered"
          :key="String(option.id)"
          type="button"
          class="enum-option"
          :class="{ active: String(option.id) === String(modelValue) }"
          @click="choose(option)"
        >
          <span class="enum-option-name">{{ option.name || option.id }}</span>
          <span class="enum-option-id">{{ option.id }}</span>
        </button>
        <p v-if="!filtered.length" class="enum-empty">没有匹配项</p>
      </div>
    </div>
  </span>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';

const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  options: { type: Array, default: () => [] },
  placeholder: { type: String, default: '请选择' },
  allowEmpty: { type: Boolean, default: false },
  emptyLabel: { type: String, default: '无' },
});
const emit = defineEmits(['update:modelValue']);

const root = ref(null);
const searchInput = ref(null);
const open = ref(false);
const search = ref('');

const current = computed(() =>
  props.options.find((option) => String(option.id) === String(props.modelValue)) || null);

const currentLabel = computed(() => {
  if (current.value) return current.value.name || current.value.id;
  if (props.modelValue === '' || props.modelValue === null || props.modelValue === undefined) {
    return props.allowEmpty ? props.emptyLabel : props.placeholder;
  }
  return String(props.modelValue);
});

const filtered = computed(() => {
  const query = search.value.trim().toLowerCase();
  const rows = props.allowEmpty
    ? [{ id: '', name: props.emptyLabel }, ...props.options]
    : props.options;
  if (!query) return rows.slice(0, 300);
  return rows.filter((option) =>
    String(option.name || '').toLowerCase().includes(query)
      || String(option.id || '').toLowerCase().includes(query)).slice(0, 300);
});

watch(open, async (value) => {
  if (!value) return;
  search.value = '';
  await nextTick();
  searchInput.value?.focus();
});

function toggle() {
  open.value = !open.value;
}

function choose(option) {
  emit('update:modelValue', option.id);
  open.value = false;
}

function chooseFirst() {
  if (filtered.value.length) choose(filtered.value[0]);
}

function onDocumentClick(event) {
  if (!open.value) return;
  if (root.value && !root.value.contains(event.target)) open.value = false;
}

document.addEventListener('pointerdown', onDocumentClick, true);
onBeforeUnmount(() => document.removeEventListener('pointerdown', onDocumentClick, true));
</script>

<style scoped>
.enum-picker {
  position: relative;
  display: inline-flex;
  min-width: 150px;
  max-width: 100%;
}

.enum-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 5px 8px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--panel-soft);
  color: var(--fg);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.enum-trigger:hover,
.enum-picker.open .enum-trigger {
  border-color: var(--accent);
}

.enum-value {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.enum-caret {
  color: var(--muted);
  font-size: 10px;
}

.enum-popover {
  position: absolute;
  z-index: 40;
  top: calc(100% + 4px);
  left: 0;
  width: max(260px, 100%);
  padding: 8px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: var(--panel);
  box-shadow: var(--shadow);
}

.enum-search {
  width: 100%;
  margin-bottom: 6px;
  padding: 5px 7px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel-soft);
  color: var(--fg);
  font-size: 12px;
}

.enum-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 260px;
  overflow: auto;
}

.enum-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 5px 7px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.enum-option:hover,
.enum-option.active {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.enum-option-name {
  font-size: 12px;
}

.enum-option-id {
  color: var(--muted);
  font-size: 10px;
  white-space: nowrap;
}

.enum-empty {
  margin: 6px;
  color: var(--muted);
  font-size: 12px;
}
</style>
