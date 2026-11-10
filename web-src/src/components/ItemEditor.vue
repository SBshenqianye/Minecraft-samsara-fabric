<template>
  <div v-if="item" class="item-editor">
    <header class="item-editor-head">
      <div class="item-editor-title">
        <ItemIcon :item="item" size="sm" :show-count="false" />
        <div>
          <strong>{{ item.name || item.id }}</strong>
          <p class="muted small">{{ item.id }} · 槽位内数量 {{ item.count }}</p>
        </div>
      </div>
      <div class="item-editor-actions">
        <span v-if="applying" class="muted small">应用中…</span>
        <span v-else-if="dirty" class="muted small">待应用</span>
        <button class="ghost" type="button" @click="applyNow(true)">立即应用</button>
      </div>
    </header>

    <p v-if="editorError" class="container-message error">{{ editorError }}</p>

    <section class="editor-section">
      <h4>名称与描述</h4>
      <label class="field">
        <span>自定义名称</span>
        <input v-model="form.customName" type="text" placeholder="留空使用物品默认名称" @input="scheduleApply" />
      </label>
      <label class="field">
        <span>Lore（每行一条）</span>
        <textarea v-model="form.loreText" rows="3" placeholder="第一行&#10;第二行" @input="scheduleApply"></textarea>
      </label>
      <div class="field-row">
        <label class="field">
          <span>稀有度</span>
          <EnumPicker v-model="form.rarity" :options="schema.rarities || []" allow-empty empty-label="默认"
              @update:model-value="scheduleApply" />
        </label>
        <label class="field">
          <span>发光覆盖</span>
          <select v-model="form.glintOverride" @change="scheduleApply">
            <option value="default">跟随物品（默认）</option>
            <option value="true">强制发光</option>
            <option value="false">强制不发光</option>
          </select>
        </label>
      </div>
    </section>

    <section class="editor-section">
      <h4>耐久</h4>
      <div class="field-row">
        <label class="field check">
          <input v-model="form.unbreakable" type="checkbox" @change="scheduleApply" />
          <span>无法破坏</span>
        </label>
        <label class="field">
          <span>当前损伤</span>
          <input v-model.number="form.damage" type="number" min="0" @input="scheduleApply" />
        </label>
        <label class="field">
          <span>最大耐久</span>
          <input v-model.number="form.maxDamage" type="number" min="0" @input="scheduleApply" />
        </label>
      </div>
    </section>

    <section class="editor-section">
      <h4>染色与模型</h4>
      <div class="field-row">
        <label class="field">
          <span>染色</span>
          <span class="color-field">
            <input v-model="form.dye" type="color" @input="scheduleApply" />
            <input v-model="form.dye" type="text" placeholder="#RRGGBB / 留空" @input="scheduleApply" />
            <button class="ghost tiny" type="button" @click="clearDye">清除</button>
          </span>
        </label>
        <label class="field">
          <span>CustomModelData（浮点，逗号分隔）</span>
          <input v-model="form.modelFloats" type="text" placeholder="如 1, 2.5" @input="scheduleApply" />
        </label>
        <label class="field">
          <span>CustomModelData 布尔标志（逗号分隔）</span>
          <input v-model="form.modelFlags" type="text" placeholder="true, false" @input="scheduleApply" />
        </label>
        <label class="field">
          <span>CustomModelData 字符串（逗号分隔）</span>
          <input v-model="form.modelStrings" type="text" placeholder="如 sword, glowing" @input="scheduleApply" />
        </label>
        <label class="field">
          <span>CustomModelData 颜色（整数，逗号分隔）</span>
          <input v-model="form.modelColors" type="text" placeholder="如 16711680, 65280" @input="scheduleApply" />
        </label>
      </div>
    </section>

    <section class="editor-section">
      <h4>附魔</h4>
      <div class="list-editor">
        <div v-for="(row, index) in form.enchantments" :key="'e' + index" class="list-row">
          <EnumPicker v-model="row.id" :options="schema.enchantments || []" @update:model-value="scheduleApply" />
          <input v-model.number="row.level" class="num" type="number" min="1" @input="scheduleApply" />
          <button class="ghost tiny" type="button" @click="removeRow(form.enchantments, index)">移除</button>
        </div>
        <button class="ghost tiny" type="button" @click="addEnchantment('enchantments')">+ 添加附魔</button>
      </div>
      <h4 class="sub">存储附魔（附魔书）</h4>
      <div class="list-editor">
        <div v-for="(row, index) in form.storedEnchantments" :key="'s' + index" class="list-row">
          <EnumPicker v-model="row.id" :options="schema.enchantments || []" @update:model-value="scheduleApply" />
          <input v-model.number="row.level" class="num" type="number" min="1" @input="scheduleApply" />
          <button class="ghost tiny" type="button" @click="removeRow(form.storedEnchantments, index)">移除</button>
        </div>
        <button class="ghost tiny" type="button" @click="addEnchantment('storedEnchantments')">+ 添加存储附魔</button>
      </div>
    </section>

    <section class="editor-section">
      <h4>药水</h4>
      <div class="field-row">
        <label class="field">
          <span>药水类型</span>
          <EnumPicker v-model="form.potion.id" :options="schema.potions || []" allow-empty empty-label="无"
              @update:model-value="scheduleApply" />
        </label>
        <label class="field">
          <span>自定义颜色</span>
          <span class="color-field">
            <input v-model="form.potion.customColor" type="color" @input="scheduleApply" />
            <button class="ghost tiny" type="button" @click="form.potion.customColor = ''; scheduleApply()">清除</button>
          </span>
        </label>
      </div>
      <div class="list-editor">
        <div v-for="(row, index) in form.potion.effects" :key="'p' + index" class="list-row effect-row">
          <EnumPicker v-model="row.id" :options="schema.effects || []" @update:model-value="scheduleApply" />
          <label class="mini">时长 <input v-model.number="row.duration" class="num" type="number" min="1" @input="scheduleApply" /></label>
          <label class="mini">等级 <input v-model.number="row.amplifier" class="num" type="number" min="0" @input="scheduleApply" /></label>
          <label class="mini check"><input v-model="row.ambient" type="checkbox" @change="scheduleApply" /> 环境</label>
          <label class="mini check"><input v-model="row.visible" type="checkbox" @change="scheduleApply" /> 可见</label>
          <button class="ghost tiny" type="button" @click="removeRow(form.potion.effects, index)">移除</button>
        </div>
        <button class="ghost tiny" type="button" @click="addEffect">+ 添加药水效果</button>
      </div>
    </section>

    <section class="editor-section">
      <h4>属性修饰</h4>
      <div class="list-editor">
        <div v-for="(row, index) in form.attributes" :key="'a' + index" class="list-row attr-row">
          <EnumPicker v-model="row.id" :options="schema.attributes || []" @update:model-value="scheduleApply" />
          <input v-model.number="row.amount" class="num" type="number" step="0.01" @input="scheduleApply" />
          <EnumPicker v-model="row.operation" :options="schema.operations || []" @update:model-value="scheduleApply" />
          <EnumPicker v-model="row.slot" :options="schema.slots || []" @update:model-value="scheduleApply" />
          <button class="ghost tiny" type="button" @click="removeRow(form.attributes, index)">移除</button>
        </div>
        <button class="ghost tiny" type="button" @click="addAttribute">+ 添加属性</button>
      </div>
    </section>

    <details class="editor-raw">
      <summary>原始组件 / 编码</summary>
      <pre>{{ item.components }}</pre>
      <pre>{{ item.encoded }}</pre>
    </details>
  </div>
</template>

<script setup>
import { reactive, ref, watch } from 'vue';
import EnumPicker from './EnumPicker.vue';
import ItemIcon from './ItemIcon.vue';

const props = defineProps({
  item: { type: Object, default: null },
  schema: { type: Object, default: () => ({}) },
});
const emit = defineEmits(['update']);

const form = reactive(emptyForm());
const applying = ref(false);
const dirty = ref(false);
const editorError = ref('');
let timer = null;
let lastEncoded = '';

watch(() => props.item, (value) => {
  if (!value || value.encoded === lastEncoded) return;
  sync(value);
}, { immediate: true, deep: false });

function emptyForm() {
  return {
    customName: '',
    loreText: '',
    rarity: '',
    glintOverride: 'default',
    unbreakable: false,
    damage: 0,
    maxDamage: 0,
    dye: '',
    modelFloats: '',
    modelFlags: '',
    modelStrings: '',
    modelColors: '',
    enchantments: [],
    storedEnchantments: [],
    potion: { id: '', customColor: '', effects: [] },
    attributes: [],
  };
}

function sync(item) {
  Object.assign(form, emptyForm());
  form.customName = item.customName || '';
  form.loreText = (item.lore || []).join('\n');
  form.rarity = item.rarity || '';
  form.glintOverride = item.glintOverride === true ? 'true' : item.glintOverride === false ? 'false' : 'default';
  form.unbreakable = item.unbreakable === true;
  form.damage = Number(item.damage || 0);
  form.maxDamage = Number(item.maxDamage || 0);
  form.dye = typeof item.dye === 'number' ? intToHex(item.dye) : '';
  form.modelFloats = Array.isArray(item.customModelData?.floats)
    ? item.customModelData.floats.join(', ') : '';
  form.modelFlags = Array.isArray(item.customModelData?.flags)
    ? item.customModelData.flags.map((value) => value ? 'true' : 'false').join(', ') : '';
  form.modelStrings = Array.isArray(item.customModelData?.strings)
    ? item.customModelData.strings.join(', ') : '';
  form.modelColors = Array.isArray(item.customModelData?.colors)
    ? item.customModelData.colors.join(', ') : '';
  form.enchantments = (item.enchantments || []).map((row) => ({ id: row.id, level: row.level }));
  form.storedEnchantments = (item.storedEnchantments || []).map((row) => ({ id: row.id, level: row.level }));
  const potion = item.potion || {};
  form.potion.id = potion.id || '';
  form.potion.customColor = typeof potion.customColor === 'number' ? intToHex(potion.customColor) : '';
  form.potion.effects = (potion.effects || []).map((row) => ({
    id: row.id,
    duration: row.duration,
    amplifier: row.amplifier,
    ambient: row.ambient === true,
    visible: row.visible !== false,
  }));
  form.attributes = (item.attributes || []).map((row) => ({
    id: row.id,
    amount: row.amount,
    operation: row.operation,
    slot: row.slot,
    modifierId: row.modifierId || '',
  }));
  dirty.value = false;
  editorError.value = '';
}

function intToHex(value) {
  return '#' + (Number(value) & 0xFFFFFF).toString(16).padStart(6, '0');
}

function hexToInt(value) {
  const text = String(value || '').trim();
  if (!/^#[0-9a-f]{6}$/i.test(text)) return null;
  return parseInt(text.slice(1), 16);
}

function scheduleApply() {
  dirty.value = true;
  clearTimeout(timer);
  timer = setTimeout(() => applyNow(false), 500);
}

function removeRow(list, index) {
  list.splice(index, 1);
  scheduleApply();
}

function addEnchantment(key) {
  form[key].push({ id: '', level: 1 });
  scheduleApply();
}

function addEffect() {
  form.potion.effects.push({ id: '', duration: 200, amplifier: 0, ambient: false, visible: true });
  scheduleApply();
}

function addAttribute() {
  form.attributes.push({ id: '', amount: 0, operation: 'add_value', slot: 'any' });
  scheduleApply();
}

function clearDye() {
  form.dye = '';
  scheduleApply();
}

function buildPatch() {
  const lore = form.loreText.split('\n').map((line) => line.trimEnd());
  while (lore.length && !lore[lore.length - 1]) lore.pop();
  const patch = {
    customName: form.customName,
    lore,
    rarity: form.rarity || '',
    glintOverride: form.glintOverride === 'default' ? null : form.glintOverride === 'true',
    unbreakable: form.unbreakable === true,
    damage: Number(form.damage) || 0,
    maxDamage: Number(form.maxDamage) || 0,
    dye: hexToInt(form.dye),
    enchantments: form.enchantments.filter((row) => row.id).map((row) => ({
      id: row.id, level: Number(row.level) || 1,
    })),
    storedEnchantments: form.storedEnchantments.filter((row) => row.id).map((row) => ({
      id: row.id, level: Number(row.level) || 1,
    })),
    attributes: form.attributes.filter((row) => row.id).map((row) => ({
      id: row.id,
      amount: Number(row.amount) || 0,
      operation: row.operation || 'add_value',
      slot: row.slot || 'any',
      modifierId: row.modifierId || '',
    })),
  };
  const modelFloats = form.modelFloats.split(',').map((part) => Number(part.trim()))
    .filter((value) => Number.isFinite(value));
  const modelFlags = form.modelFlags.split(',').map((part) => part.trim().toLowerCase())
    .filter((part) => part === 'true' || part === 'false').map((part) => part === 'true');
  const modelStrings = form.modelStrings.split(',').map((part) => part.trim())
    .filter(Boolean);
  const modelColors = form.modelColors.split(',').map((part) => Number(part.trim()))
    .filter((value) => Number.isFinite(value));
  patch.customModelData = (modelFloats.length || modelFlags.length || modelStrings.length
    || modelColors.length)
    ? { floats: modelFloats, flags: modelFlags, strings: modelStrings, colors: modelColors }
    : null;
  const effects = form.potion.effects.filter((row) => row.id).map((row) => ({
    id: row.id,
    duration: Number(row.duration) || 200,
    amplifier: Number(row.amplifier) || 0,
    ambient: row.ambient === true,
    visible: row.visible !== false,
  }));
  const customColor = hexToInt(form.potion.customColor);
  if (form.potion.id || effects.length || customColor !== null) {
    patch.potion = { id: form.potion.id, customColor, effects };
  } else {
    patch.potion = null;
  }
  return patch;
}

async function applyNow(force) {
  if (!props.item || !props.item.encoded) return;
  clearTimeout(timer);
  if (!force && !dirty.value) return;
  applying.value = true;
  editorError.value = '';
  try {
    const token = sessionStorage.getItem('samsara-token') || '';
    const response = await fetch('/api/items/build', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        encoded: props.item.encoded,
        count: props.item.count,
        patch: buildPatch(),
      }),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || !body.ok) throw new Error(body.error || '应用物品组件失败');
    if (Array.isArray(body.errors) && body.errors.length) {
      editorError.value = body.errors.join('；');
    }
    lastEncoded = body.item.encoded;
    dirty.value = false;
    emit('update', body.item);
  } catch (e) {
    editorError.value = e.message || String(e);
  } finally {
    applying.value = false;
  }
}
</script>

<style scoped>
.item-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: min(70vh, 720px);
  overflow: auto;
  padding-right: 4px;
}

.item-editor-head {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  background: var(--panel);
}

.item-editor-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.item-editor-title p {
  margin: 0;
}

.item-editor-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.editor-section {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel-soft);
}

.editor-section h4 {
  margin: 0 0 8px;
  font-size: 12px;
}

.editor-section h4.sub {
  margin-top: 10px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  font-size: 11px;
  color: var(--muted);
}

.field input:not([type='checkbox']):not([type='color']),
.field select,
.field textarea {
  width: 100%;
  padding: 5px 7px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel);
  color: var(--fg);
  font-size: 12px;
}

.field textarea {
  resize: vertical;
}

.field.check {
  flex-direction: row;
  align-items: center;
  gap: 6px;
  color: var(--fg);
}

.field-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.field-row > .field {
  flex: 1 1 160px;
}

.color-field {
  display: flex;
  align-items: center;
  gap: 6px;
}

.color-field input[type='color'] {
  width: 36px;
  height: 30px;
  padding: 1px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel);
}

.color-field input[type='text'] {
  flex: 1;
}

.list-editor {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.list-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
}

.list-row .num {
  width: 74px;
  padding: 5px 6px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel);
  color: var(--fg);
}

.effect-row .mini,
.attr-row .mini {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--muted);
  font-size: 11px;
}

.mini.check {
  color: var(--fg);
}

.control.tiny,
.ghost.tiny {
  padding: 2px 7px;
  font-size: 11px;
}

.editor-raw pre {
  max-height: 160px;
  overflow: auto;
  padding: 8px;
  border-radius: 8px;
  background: var(--panel-soft);
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
