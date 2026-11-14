<template>
  <div class="chat-text-editor">
    <div class="chat-text-toolbar">
      <button
        class="chat-text-toggle"
        type="button"
        :aria-expanded="paletteOpen ? 'true' : 'false'"
        @click="paletteOpen = !paletteOpen"
      >颜色 4×4</button>
      <div class="chat-text-formats">
        <button
          v-for="[code, label, style] in CHAT_FORMATS"
          :key="code"
          class="chat-text-format"
          type="button"
          :title="'§' + code + ' ' + label"
          :style="style"
          @click="insert(code)"
        >{{ label }}</button>
      </div>
    </div>

    <div
      v-show="paletteOpen"
      class="claim-color-grid chat-text-color-grid"
      role="group"
      :aria-label="paletteLabel"
    >
      <button
        v-for="[code, label, hex] in CHAT_COLORS"
        :key="code"
        class="claim-color-cell chat-text-color-cell"
        type="button"
        :title="'§' + code + ' ' + label"
        :aria-label="'插入颜色 §' + code + ' ' + label"
        @click="insert(code)"
      >
        <span class="claim-color-swatch" :style="{ background: hex }"></span>
        <span class="claim-color-name">{{ label }}</span>
        <span class="claim-color-meta">§{{ code }} · {{ code.toUpperCase() }}</span>
      </button>
    </div>

    <textarea
      ref="inputEl"
      v-model="text"
      class="chat-text-input"
      :rows="rows"
      spellcheck="false"
      :placeholder="placeholder"
      @input="emitChange"
    ></textarea>

    <div class="chat-text-preview-head">
      <span class="chat-text-preview-label">预览</span>
    </div>

    <div v-if="serverList" class="chat-text-preview server-list-preview">
      <div class="server-list-card">
        <div class="server-list-icon">
          <img :src="iconUrl" alt="" draggable="false" />
        </div>
        <div class="server-list-body">
          <div class="server-list-title">
            <div class="server-list-line server-list-line-first">
              <div class="chat-text-rendered">
                <span
                  v-for="(segment, index) in firstLine"
                  :key="'f' + index"
                  :style="segment.style"
                >{{ segment.text }}</span>
              </div>
            </div>
            <div class="server-list-line server-list-line-second">
              <div class="chat-text-rendered">
                <span
                  v-for="(segment, index) in secondLine"
                  :key="'s' + index"
                  :style="segment.style"
                >{{ segment.text }}</span>
              </div>
            </div>
          </div>
        </div>
        <div class="server-list-right">
          <div class="server-list-status">
            <span class="server-list-signal">
              <i v-for="n in 5" :key="n" :style="{ height: (3 + (n - 1) * 3) + 'px' }"></i>
            </span>
            <span class="server-list-players">{{ onlineText }}</span>
          </div>
          <div class="server-list-version">{{ version }}</div>
        </div>
      </div>
    </div>
    <div v-else class="chat-text-preview">
      <div class="chat-text-rendered">
        <span
          v-for="(segment, index) in segments"
          :key="index"
          :style="segment.style"
        >{{ segment.text }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import {
  CHAT_COLORS,
  CHAT_FORMATS,
  parseChatSegments,
  splitMotdLines,
} from './model.js';

const props = defineProps({
  modelValue: { type: String, default: '' },
  rows: { type: Number, default: 3 },
  placeholder: { type: String, default: '支持 § 颜色/格式代码，例如 §e[公告] §f欢迎' },
  serverList: { type: Boolean, default: false },
  online: { type: [Number, String], default: 0 },
  maxPlayers: { type: [Number, String], default: 20 },
  version: { type: String, default: 'Minecraft 26.3' },
  paletteLabel: { type: String, default: 'MOTD 颜色' },
});
const emit = defineEmits(['update:modelValue', 'change']);

const inputEl = ref(null);
const paletteOpen = ref(false);
const iconUrl = '/items/grass_block.png';
const text = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
});

const segments = computed(() => parseChatSegments(props.modelValue));
const lines = computed(() => splitMotdLines(props.modelValue));
const firstLine = computed(() => parseChatSegments(lines.value[0]));
const secondLine = computed(() => parseChatSegments(lines.value[1] || ''));
const onlineText = computed(() => (props.online == null ? '0' : String(props.online))
  + '/' + (props.maxPlayers == null ? '20' : String(props.maxPlayers)));

function insert(code) {
  const input = inputEl.value;
  const value = input ? input.value : (props.modelValue || '');
  const start = input?.selectionStart == null ? value.length : input.selectionStart;
  const end = input?.selectionEnd == null ? start : input.selectionEnd;
  const next = value.slice(0, start) + '§' + code + value.slice(end);
  // 同步写回 DOM，避免受控 textarea 等下一次 Vue 刷新才看到插入结果。
  if (input) {
    input.value = next;
    const pos = start + 2;
    input.focus();
    input.setSelectionRange(pos, pos);
  }
  emit('update:modelValue', next);
  emit('change', next);
}

function emitChange() {
  emit('change', inputEl.value ? inputEl.value.value : props.modelValue);
}
</script>
