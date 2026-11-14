<template>
  <div class="particle-wrap" :data-particle-theme="theme">
    <SegmentedButtons
      v-if="kind === 'boolean'"
      :value="boolValue ? 'true' : 'false'"
      :choices="BOOL_CHOICES"
      @change="(next) => update(next === 'true')"
    />
    <SegmentedButtons
      v-else-if="kind === 'enum' && choices.length === 2"
      :value="String(enumValue)"
      :choices="choices"
      @change="update"
    />
    <select
      v-else-if="kind === 'enum'"
      :value="String(enumValue)"
      @change="update($event.target.value)"
    >
      <option v-for="choice in choices" :key="choice" :value="choice">{{ choice }}</option>
    </select>
    <div v-else-if="kind === 'number'" class="entry-number">
      <input
        type="number"
        :value="value == null ? fallback : value"
        :min="entry.min"
        :max="entry.max"
        :step="entry.type === 'int' ? 1 : 'any'"
        :placeholder="placeholder"
        :title="'默认值：' + (fallback === '' ? '空' : fallback)"
        @input="(event) => onNumberInput(event.target.value)"
        @change="(event) => onNumberChange(event.target.value)"
      />
    </div>
    <input
      v-else-if="kind === 'sensitive'"
      type="password"
      :value="''"
      :placeholder="entry.configured ? '已配置；留空表示不修改' : '尚未生成'"
      @input="(event) => onSensitive(event.target.value)"
    />
    <StringListEditor
      v-else-if="kind === 'string-list'"
      :model-value="Array.isArray(value) ? value : []"
      @update:model-value="update"
    />
    <IntListEditor
      v-else-if="kind === 'int-list'"
      :model-value="Array.isArray(value) ? value : []"
      :min="entry.min ?? 1"
      :max="entry.max ?? 32"
      :defaults="Array.isArray(entry.default) ? entry.default : []"
      @update:model-value="update"
    />
    <div v-else-if="kind === 'level-map'" class="data-body">
      <div class="data-hint">数据表默认折叠；展开后可逐行增删。命令路径 → 权限等级。</div>
      <div class="struct-map">
        <div v-for="(row, index) in mapRows" :key="index" class="struct-row struct-kv">
          <input v-model="row.key" class="struct-input" placeholder="命令路径 / 键" @input="emitMap" />
          <select v-model="row.value" class="struct-input" @change="emitMap">
            <option value="user">user</option>
            <option value="op">op</option>
            <option value="owner">owner</option>
            <option value="config">config</option>
          </select>
          <button class="struct-btn ghost" type="button" title="删除" @click="removeMapRow(index)">×</button>
        </div>
        <button class="struct-btn ghost" type="button" title="添加" @click="addMapRow">+ 添加一行</button>
      </div>
    </div>
    <CurveField
      v-else-if="kind === 'curve'"
      :file="file"
      :entry="entry"
      :config="config"
    />
    <input
      v-else
      type="text"
      :value="value == null ? fallback : value"
      :placeholder="placeholder"
      :title="'默认值：' + (fallback === '' ? '空' : fallback)"
      @input="(event) => onTextInput(event.target.value)"
      @change="(event) => onTextChange(event.target.value)"
    />
    <ParticlePreview v-if="theme" :name="String(enumValue)" :theme="theme" />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import CurveField from './CurveField.vue';
import IntListEditor from './IntListEditor.vue';
import ParticlePreview from './ParticlePreview.vue';
import SegmentedButtons from '../../components/SegmentedButtons.vue';
import StringListEditor from './StringListEditor.vue';
import { controlKind, numericFallback, numberPlaceholder, particleTheme } from './model.js';

const props = defineProps({
  file: { type: Object, required: true },
  entry: { type: Object, required: true },
  config: { type: Object, required: true },
});

const BOOL_CHOICES = [{ value: 'true', label: '启用' }, { value: 'false', label: '关闭' }];
const kind = computed(() => controlKind(props.entry));
const value = computed(() => props.config.currentValue(props.file, props.entry));
const choices = computed(() => (props.entry.choices || []).map(String));
const boolValue = computed(() => value.value === true);
const enumValue = computed(() => (value.value == null ? props.entry.default : value.value));
const fallback = computed(() => numericFallback(props.entry));
const placeholder = computed(() => numberPlaceholder(props.entry));
const theme = computed(() => particleTheme(props.entry.path));

const mapRows = ref([]);
watch(() => value.value, (next) => {
  if (kind.value !== 'level-map') return;
  const map = next && typeof next === 'object' && !Array.isArray(next) ? next : {};
  mapRows.value = Object.entries(map).map(([key, level]) => ({ key, value: String(level) }));
}, { immediate: true });

function update(next) {
  props.config.setValue(props.file, props.entry, next);
}

function onNumberInput(raw) {
  if (raw === '') update(props.entry.default);
  else update(Number(raw));
}

function onNumberChange(raw) {
  if (raw === '' && fallback.value !== '') update(props.entry.default);
}

function onSensitive(raw) {
  if (raw) update(raw);
}

function onTextInput(raw) {
  update(raw);
}

function onTextChange(raw) {
  if (raw === '' && fallback.value !== '') update(props.entry.default);
}

function emitMap() {
  const out = {};
  for (const row of mapRows.value) {
    const key = String(row.key || '').trim();
    if (key) out[key] = row.value;
  }
  update(out);
}

function addMapRow() {
  mapRows.value = [...mapRows.value, { key: '', value: 'user' }];
  emitMap();
}

function removeMapRow(index) {
  mapRows.value = mapRows.value.filter((_, i) => i !== index);
  emitMap();
}
</script>
