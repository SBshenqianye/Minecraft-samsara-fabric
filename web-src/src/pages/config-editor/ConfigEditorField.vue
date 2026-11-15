<template>
  <div
    class="entry"
    :class="{ restart: field.restart, 'entry-wide': field.secret || kind === 'json' }"
    :data-path="field.path"
  >
    <div class="entry-key">
      <span class="entry-path">{{ field.label || field.path }}</span>
      <span v-if="field.restart" class="badge restart">需重启</span>
      <span v-if="field.secret" class="badge">敏感</span>
      <span v-if="field.readOnly" class="badge">只读</span>
    </div>
    <div class="entry-desc">{{ field.description || '' }}</div>
    <div class="entry-control">
      <div v-if="kind === 'readonly'" class="entry-readonly">{{ readonlyText(value) }}</div>
      <ChatTextEditor
        v-else-if="kind === 'chat'"
        :model-value="value == null ? '' : String(value)"
        :rows="3"
        @change="(next) => store.setField(field, next)"
      />
      <SegmentedButtons
        v-else-if="kind === 'bool'"
        :value="(value === true || String(value) === 'true') ? 'true' : 'false'"
        :choices="BOOL_CHOICES"
        @change="(next) => store.setField(field, next === 'true')"
      />
      <select
        v-else-if="kind === 'enum'"
        :value="String(value == null ? '' : value)"
        @change="store.setField(field, $event.target.value)"
      >
        <option v-for="choice in choices" :key="choice.value" :value="choice.value">
          {{ choice.label || choice.value }}
        </option>
      </select>
      <input
        v-else-if="kind === 'int' || kind === 'number'"
        type="number"
        :value="value == null ? '' : String(value)"
        :min="field.min"
        :max="field.max"
        :step="field.step != null ? field.step : kind === 'int' ? 1 : 'any'"
        @input="store.setField(field, $event.target.value)"
      />
      <textarea
        v-else-if="kind === 'json' || kind === 'multiline'"
        :rows="kind === 'json' ? 8 : 4"
        spellcheck="false"
        :value="kind === 'json' ? pretty(value) : (value == null ? '' : String(value))"
        @input="store.setField(field, $event.target.value)"
      ></textarea>
      <input
        v-else-if="kind === 'password'"
        type="password"
        autocomplete="new-password"
        :placeholder="field.placeholder || '留空表示不修改'"
        :value="''"
        @input="store.setField(field, $event.target.value)"
      />
      <input
        v-else-if="kind === 'url'"
        type="url"
        :value="value == null ? '' : String(value)"
        @input="store.setField(field, $event.target.value)"
      />
      <input
        v-else
        type="text"
        :value="value == null ? '' : String(value)"
        @input="store.setField(field, $event.target.value)"
      />
      <div class="entry-error" :data-path="field.path">{{ store.fieldErrors.get(field.path) || '' }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import ChatTextEditor from '../../components/ChatTextEditor.vue';
import SegmentedButtons from '../../components/SegmentedButtons.vue';
import { fieldKind, pretty, readonlyText } from './model.js';

const props = defineProps({
  store: { type: Object, required: true },
  field: { type: Object, required: true },
});

const BOOL_CHOICES = [{ value: 'true', label: '启用' }, { value: 'false', label: '关闭' }];
const kind = computed(() => fieldKind(props.store.current.value, props.field));
const value = computed(() => props.store.currentValue(props.field));
const choices = computed(() => (props.field.choices || []).map((choice) => (choice && typeof choice === 'object'
  ? { value: String(choice.value), label: choice.label == null ? String(choice.value) : String(choice.label) }
  : { value: String(choice), label: String(choice) })));
</script>
