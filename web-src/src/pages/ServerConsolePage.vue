<template>
  <TabShell name="server-console">
    <div id="server-console-panel" class="server-panel">
      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>RCON 控制台</h2>
            <p class="panel-sub">
              在服务器线程执行原版 / 模组命令，可直接回显。权限等同控制台；stop 会关闭服务器。
            </p>
          </div>
          <div class="panel-tools">
            <button id="server-console-clear" class="ghost" type="button" @click="clearOutput">清空输出</button>
          </div>
        </div>

        <div class="console-quick-filter">
          <input
            id="server-console-filter"
            v-model="filterText"
            type="search"
            autocomplete="off"
            placeholder="筛选快捷命令，例如 save / 玩家 / 调试"
          />
          <button id="server-console-copy" class="ghost" type="button" @click="copyOutput">复制输出</button>
        </div>

        <div id="server-console-quick" class="console-quick">
          <div
            v-for="group in visibleGroups"
            :key="group.label"
            class="console-quick-group"
            :class="{ 'danger-zone': group.danger }"
          >
            <span class="console-quick-label">{{ group.label }}</span>
            <button
              v-for="item in group.items"
              :key="item.command"
              type="button"
              class="ghost"
              :class="{ danger: item.danger }"
              :data-console-command="item.command"
              :title="item.title"
              @click="run(item.command)"
            >{{ item.label }}</button>
          </div>
        </div>

        <pre
          id="server-console-output"
          ref="outputEl"
          class="server-console-output"
          role="log"
          aria-live="polite"
        >{{ outputLines.join('\n') }}</pre>

        <div class="server-console-input">
          <input
            id="server-console-command"
            ref="inputEl"
            v-model="command"
            type="text"
            autocomplete="off"
            placeholder="输入命令，不需要前缀 /；Tab 补全，↑↓ 选择历史"
            @keydown="onKeydown"
          />
          <button id="server-console-send" class="primary" type="button" :disabled="running" @click="submit">
            {{ running ? '执行中…' : '执行' }}
          </button>
        </div>

        <div id="server-console-complete" class="console-complete" :hidden="!complete.items.length">
          <div class="console-complete-list">
            <button
              v-for="(item, index) in complete.items"
              :key="index + ':' + item.text"
              type="button"
              class="console-complete-item"
              :class="{ active: index === complete.selected }"
              :data-index="index"
              @mousedown.prevent
              @click="applyComplete(item)"
            >
              <span class="console-complete-text">{{ item.text }}</span>
              <span v-if="item.tooltip" class="console-complete-tip">{{ item.tooltip }}</span>
            </button>
          </div>
          <div class="console-complete-footer">
            <span class="console-complete-preview">{{ completePreviewText }}</span>
            Tab 补全 · ↑↓ 选择 · Esc 关闭
          </div>
        </div>

        <div id="server-console-message" class="message" :class="messageKind">{{ message }}</div>
      </section>
    </div>
  </TabShell>
</template>

<script setup>
import { computed, nextTick, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import TabShell from '../components/TabShell.vue';
import { useApi } from '../composables/useApi.js';
import {
  addHistory,
  completePreview,
  filterQuickGroups,
  historyValue,
  isDangerous,
  QUICK_GROUPS,
  responseSummary,
} from './server-console/model.js';

defineOptions({ name: 'ServerConsolePage' });

const { request } = useApi();
const command = ref('');
const outputLines = ref([]);
const message = ref('');
const messageKind = ref('');
const running = ref(false);
const filterText = ref('');
const history = ref([]);
const historyIndex = ref(-1);
const inputEl = ref(null);
const outputEl = ref(null);
const complete = reactive({ items: [], selected: 0, range: null });

const visibleGroups = computed(() => filterQuickGroups(QUICK_GROUPS, filterText.value));
const completePreviewText = computed(() => {
  const item = complete.items[complete.selected];
  return item ? '将补全为：' + completePreview(command.value, complete.range, item) : '';
});

function setMessage(text, kind = '') {
  message.value = text || '';
  messageKind.value = kind;
}

function append(text) {
  outputLines.value = [...outputLines.value, String(text == null ? '' : text)];
  nextTick(() => {
    if (outputEl.value) outputEl.value.scrollTop = outputEl.value.scrollHeight;
  });
}

function closeComplete() {
  complete.items = [];
  complete.selected = 0;
  complete.range = null;
}

function applyComplete(item) {
  if (!item || !complete.range) return;
  const start = complete.range.start;
  const end = complete.range.end;
  const next = command.value.slice(0, start) + item.text + command.value.slice(end);
  const cursor = start + item.text.length;
  command.value = next;
  closeComplete();
  nextTick(() => {
    inputEl.value?.focus();
    inputEl.value?.setSelectionRange(cursor, cursor);
  });
}

function selectComplete(index) {
  if (!complete.items.length) return;
  const count = complete.items.length;
  complete.selected = (index + count) % count;
}

async function requestComplete() {
  const value = command.value;
  const cursor = inputEl.value?.selectionStart ?? value.length;
  try {
    const result = await request('/api/server/console/complete', {
      method: 'POST',
      body: JSON.stringify({ command: value, cursor }),
    });
    const data = result.data || {};
    if (!result.ok || data.ok === false) {
      closeComplete();
      return;
    }
    const suggestions = Array.isArray(data.suggestions) ? data.suggestions : [];
    if (suggestions.length === 1) {
      complete.range = data.range || null;
      applyComplete(suggestions[0]);
      return;
    }
    if (!suggestions.length || !data.range) {
      closeComplete();
      return;
    }
    complete.items = suggestions;
    complete.selected = 0;
    complete.range = data.range;
  } catch (error) {
    closeComplete();
    setMessage(error.message || '补全失败', 'error');
  }
}

async function run(rawCommand) {
  const raw = String(rawCommand || '').trim();
  if (!raw || running.value) return;
  if (isDangerous(raw)) {
    try {
      await ElMessageBox.confirm(
        '执行危险命令？\n' + raw + '\n\n这可能不可逆或直接关闭服务器。',
        '危险命令',
        { confirmButtonText: '执行', cancelButtonText: '取消', type: 'warning' },
      );
    } catch {
      return;
    }
  }
  running.value = true;
  setMessage('执行中…');
  append('> ' + raw);
  try {
    const result = await request('/api/server/console', {
      method: 'POST',
      body: JSON.stringify({ command: raw }),
    });
    const data = result.data || {};
    const lines = Array.isArray(data.output) ? data.output : [data.error || '无输出'];
    for (const line of lines) append(line);
    setMessage(responseSummary(data) + (result.ok && data.ok !== false ? '' : ' · 失败'),
      result.ok && data.ok !== false ? 'ok' : 'error');
    history.value = addHistory(history.value, raw);
    historyIndex.value = -1;
    command.value = '';
    closeComplete();
    nextTick(() => inputEl.value?.focus());
  } catch (error) {
    append('错误：' + (error.message || '执行失败'));
    setMessage(error.message || '执行失败', 'error');
  } finally {
    running.value = false;
  }
}

function onKeydown(event) {
  if (event.key === 'Tab') {
    event.preventDefault();
    if (complete.items.length) applyComplete(complete.items[complete.selected]);
    else requestComplete();
    return;
  }
  if (event.key === 'Enter') {
    event.preventDefault();
    closeComplete();
    submit();
    return;
  }
  if (event.key === 'Escape' && complete.items.length) {
    event.preventDefault();
    closeComplete();
    return;
  }
  if (event.key === 'ArrowUp' && complete.items.length) {
    event.preventDefault();
    selectComplete(complete.selected - 1);
    return;
  }
  if (event.key === 'ArrowDown' && complete.items.length) {
    event.preventDefault();
    selectComplete(complete.selected + 1);
    return;
  }
  if (event.key === 'ArrowUp' && history.value.length) {
    event.preventDefault();
    historyIndex.value = Math.min(history.value.length - 1, historyIndex.value + 1);
    command.value = historyValue(history.value, historyIndex.value);
    return;
  }
  if (event.key === 'ArrowDown' && history.value.length) {
    event.preventDefault();
    historyIndex.value = Math.max(-1, historyIndex.value - 1);
    command.value = historyValue(history.value, historyIndex.value);
  }
}

function submit() {
  // 兼容直接设置 DOM input.value 的调用方（旧 E2E / 浏览器自动填充）。
  run(inputEl.value?.value || command.value);
}

function clearOutput() {
  outputLines.value = [];
  setMessage('');
}

async function copyOutput() {
  const text = outputLines.value.join('\n');
  try {
    await navigator.clipboard.writeText(text);
    setMessage('已复制输出', 'ok');
  } catch {
    const area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    document.execCommand('copy');
    area.remove();
    ElMessage.success('已复制输出');
  }
}
</script>

<style scoped>
#server-console-panel { min-width: 0; }
.console-quick-filter { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.console-quick-filter input {
  flex: 1;
  min-width: 180px;
  padding: 7px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  color: var(--fg);
}
.server-console-output {
  min-height: 220px;
  max-height: 52vh;
  overflow: auto;
  margin: 10px 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #0b1117;
  color: #d8e2ec;
  font: 12px/1.55 var(--mono);
  white-space: pre-wrap;
}
.server-console-input { display: flex; gap: 8px; }
.server-console-input input {
  flex: 1;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  color: var(--fg);
  font-family: var(--mono);
}
.console-complete {
  position: relative;
  margin-top: 6px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  overflow: hidden;
}
.console-complete-list { max-height: 220px; overflow: auto; }
.console-complete-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 6px 10px;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: var(--fg-soft);
  text-align: left;
}
.console-complete-item.active,
.console-complete-item:hover { background: var(--accent-soft); color: var(--fg); }
.console-complete-text { font-family: var(--mono); }
.console-complete-tip { color: var(--muted); font-size: 11px; }
.console-complete-footer {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 6px 10px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 11px;
}
.console-complete-preview { flex: 1; font-family: var(--mono); }
.message.ok { color: var(--ok); }
.message.error { color: var(--danger); }
</style>
