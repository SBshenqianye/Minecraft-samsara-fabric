<template>
  <div id="server-config-panel" class="server-panel panel-stack" :hidden="hidden">
    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>服务器配置</h2>
          <p class="panel-sub">6 个服务器文件在同一页显示，可折叠、可搜索；写入前自动备份到 samsara/server-config-backups。server.properties/eula 需重启生效，其余保存后立即重载。</p>
        </div>
        <div class="panel-tools">
          <label class="search-box">
            <span aria-hidden="true">⌕</span>
            <input
              id="server-config-search"
              v-model="search"
              type="search"
              placeholder="搜索配置项：键名 / 说明"
            />
          </label>
          <button id="server-reload" class="ghost" type="button" :disabled="loading" @click="reload">重新读取</button>
          <button id="server-save" class="primary" type="button" :disabled="saving" @click="save">
            {{ saveLabel }}
          </button>
        </div>
      </div>
      <div id="server-config-message" class="message" :class="messageKind" role="status">{{ message }}</div>
      <div id="server-config-files" class="config-files">
        <details
          v-for="file in visibleFiles"
          :id="fileElementId(file)"
          :key="file.id"
          class="file-block"
          :class="fileCardClass(file)"
          :open="isOpen(fileElementId(file), true)"
          @toggle="onToggle(fileElementId(file), $event)"
        >
          <summary class="file-summary">
            <span class="file-title">
              <span v-if="FILE_META[file.id]" class="server-file-icon">{{ FILE_META[file.id].icon }}</span>
              {{ file.name }}
            </span>
            <span class="file-path">{{ displayPath(file, model?.serverRoot) }}</span>
            <span class="count-pill file-count">{{ fileCount(file) }}</span>
            <span v-if="FILE_META[file.id]" class="server-file-badge">{{ FILE_META[file.id].badge }}</span>
          </summary>
          <div class="file-body">
            <p v-if="FILE_META[file.id]" class="server-file-note">{{ FILE_META[file.id].note }}</p>

            <!-- properties / eula：按分组渲染可编辑条目 -->
            <template v-if="viewFor(file).kind === 'lines'">
              <p v-if="file.kind === 'properties'" class="server-file-note">
                以 # 或 ! 开头的行是注释，不会显示为可编辑字段；这里只列出 key=value。
              </p>
              <details
                v-for="group in viewFor(file).grouping.groups"
                :id="'server-group-' + file.id + '-' + encodeURIComponent(group.name)"
                :key="group.name"
                class="group-block"
                :open="isOpen('server-group-' + file.id + '-' + encodeURIComponent(group.name), true)"
                @toggle="onToggle('server-group-' + file.id + '-' + encodeURIComponent(group.name), $event)"
              >
                <summary class="group-title">
                  <span>{{ group.name }}</span>
                  <span class="count-pill group-count">{{ group.items.length }}</span>
                </summary>
                <div
                  v-for="{ token, index } in group.items"
                  :id="entryId(file.id, token.key, index)"
                  :key="token.key + ':' + index"
                  class="entry"
                  :class="{ 'entry-wide': specOf(file, token.key).chat }"
                >
                  <div>
                    <div class="entry-key">
                      {{ token.key }}
                      <template v-if="badgesFor(file, token.key).length">
                        <span
                          v-for="badge in badgesFor(file, token.key)"
                          :key="badge.text + badge.kind"
                          class="badge"
                          :class="badge.kind"
                        >{{ badge.text }}</span>
                      </template>
                    </div>
                    <div v-if="specOf(file, token.key).desc" class="entry-desc">
                      {{ specOf(file, token.key).desc }}
                    </div>
                  </div>
                  <div class="entry-control">
                    <ChatTextEditor
                      v-if="specOf(file, token.key).chat"
                      :model-value="token.value"
                      :rows="3"
                      :server-list="file.id === 'server-properties' && token.key === 'motd'"
                      :online="model?.onlinePlayers ?? 0"
                      :max-players="model?.maxPlayers ?? 20"
                      :version="model?.version || 'Minecraft 26.3'"
                      @change="(next) => setProperty(file, token, next)"
                    />
                    <SegmentedButtons
                      v-else-if="specOf(file, token.key).type === 'bool'"
                      :value="token.value === 'true' ? 'true' : 'false'"
                      :choices="BOOL_CHOICES"
                      @change="(next) => setProperty(file, token, next)"
                    />
                    <SegmentedButtons
                      v-else-if="specOf(file, token.key).type === 'enum'"
                      :value="token.value"
                      :choices="specOf(file, token.key).choices"
                      @change="(next) => setProperty(file, token, next)"
                    />
                    <textarea
                      v-else-if="specOf(file, token.key).type === 'multiline'"
                      class="entry-textarea"
                      :rows="4"
                      spellcheck="false"
                      :value="token.value"
                      @input="(event) => setProperty(file, token, event.target.value)"
                    ></textarea>
                    <input
                      v-else
                      :type="inputType(specOf(file, token.key))"
                      :value="token.value"
                      :min="specOf(file, token.key).min"
                      :max="specOf(file, token.key).max"
                      :step="specOf(file, token.key).type === 'int' ? 1 : undefined"
                      :autocomplete="specOf(file, token.key).type === 'password' ? 'new-password' : undefined"
                      :placeholder="inputPlaceholder(specOf(file, token.key))"
                      @input="(event) => setProperty(file, token, event.target.value)"
                    />
                  </div>
                  <div class="entry-actions">
                    <button
                      v-if="file.kind === 'properties'"
                      class="ghost"
                      type="button"
                      :title="resetTitle(token)"
                      @click="resetProperty(file, token)"
                    >恢复默认</button>
                  </div>
                </div>
              </details>
            </template>

            <!-- JSON 名单文件 -->
            <div v-else-if="viewFor(file).kind === 'json'" class="server-json-list">
              <details
                v-for="{ item, index } in viewFor(file).json.items"
                :id="jsonItemId(file.id, index)"
                :key="jsonItemId(file.id, index)"
                class="file-block server-json-item"
                :open="isOpen(jsonItemId(file.id, index), true)"
                @toggle="onToggle(jsonItemId(file.id, index), $event)"
              >
                <summary class="file-summary server-json-head">
                  <div>
                    <h4 class="file-title">{{ jsonTitle(item, index) }}</h4>
                    <p class="file-path">{{ item.uuid || item.ip || '' }}</p>
                  </div>
                  <div class="server-json-item-actions">
                    <button
                      v-if="viewFor(file).json.spec.player"
                      class="ghost"
                      type="button"
                      @click="pickPlayer(file, item)"
                    >选择玩家</button>
                    <button class="ghost danger" type="button" @click="removeJsonItem(file, index)">删除</button>
                  </div>
                </summary>
                <div class="file-body">
                  <div class="struct-list">
                    <div v-for="key in Object.keys(item)" :key="key" class="struct-row">
                      <div class="server-field-meta">
                        <span class="server-field-label">{{ key }}</span>
                        <small>{{ jsonFieldSpec(file, key, item).desc || '' }}</small>
                      </div>
                      <div class="server-field-control">
                        <SegmentedButtons
                          v-if="jsonFieldSpec(file, key, item).type === 'bool'"
                          :value="item[key] === true ? 'true' : 'false'"
                          :choices="BOOL_CHOICES"
                          @change="(next) => setJsonField(file, item, key, next === 'true')"
                        />
                        <SegmentedButtons
                          v-else-if="jsonFieldSpec(file, key, item).type === 'enum'"
                          :value="String(item[key] ?? '')"
                          :choices="jsonFieldSpec(file, key, item).choices"
                          @change="(next) => setJsonField(file, item, key, next)"
                        />
                        <input
                          v-else
                          :type="jsonFieldSpec(file, key, item).type === 'int' ? 'number' : 'text'"
                          :value="item[key]"
                          :style="jsonFieldSpec(file, key, item).mono ? 'font-family: var(--mono)' : undefined"
                          @input="(event) => setJsonField(file, item, key, event.target.value)"
                        />
                        <button
                          v-if="jsonFieldSpec(file, key, item).quick === 'now'"
                          class="ghost"
                          type="button"
                          @click="setJsonField(file, item, key, stampNow())"
                        >现在</button>
                        <button
                          v-if="jsonFieldSpec(file, key, item).quick === 'forever'"
                          class="ghost"
                          type="button"
                          @click="setJsonField(file, item, key, 'forever')"
                        >永久</button>
                      </div>
                    </div>
                  </div>
                </div>
              </details>
              <p v-if="!viewFor(file).json.items.length" class="muted small">这个文件还是空数组。</p>
              <div class="server-json-actions">
                <button class="ghost" type="button" @click="addJsonItem(file)">{{ viewFor(file).json.spec.addLabel }}</button>
                <button
                  v-if="viewFor(file).json.spec.player"
                  class="ghost"
                  type="button"
                  @click="pickPlayer(file, null)"
                >从玩家选择</button>
              </div>
            </div>

            <!-- 无法按结构化解析时回退到源码编辑 -->
            <template v-else>
              <p v-if="viewFor(file).draft.parseError" class="message warning">
                JSON 解析失败，已切换为源码编辑；保存前请先修正格式。
              </p>
              <textarea
                class="server-config-editor"
                spellcheck="false"
                :value="viewFor(file).draft.text || ''"
                @input="(event) => setTextDraft(file, event.target.value)"
              ></textarea>
            </template>
          </div>
        </details>
        <p v-if="!files.length" class="muted small">没有可编辑的服务器配置文件。</p>
      </div>
    </section>

    <PlayerPickerDialog ref="pickerRef" @pick="onPlayerPicked" />
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { useApi } from '../../composables/useApi.js';
import ChatTextEditor from './ChatTextEditor.vue';
import PlayerPickerDialog from './PlayerPickerDialog.vue';
import SegmentedButtons from './SegmentedButtons.vue';
import {
  FILE_META,
  JSON_SPECS,
  PROPERTY_DEFAULTS,
  autoFillBan,
  buildContent,
  defaultJsonItem,
  displayPath,
  entryId,
  filterJsonItems,
  groupProperties,
  isDraftDirty,
  jsonItemId,
  parseDraft,
  specFor,
  stampNow,
} from './model.js';

const props = defineProps({
  hidden: { type: Boolean, default: false },
  model: { type: Object, default: null },
  loading: { type: Boolean, default: false },
});
const emit = defineEmits(['reload', 'saved', 'dirty']);

const { request } = useApi();
const BOOL_CHOICES = [{ value: 'true', label: '开' }, { value: 'false', label: '关' }];

const files = computed(() => (props.model?.configFiles || []));
const drafts = reactive(new Map());
const openStates = reactive(new Map());
const search = ref('');
const message = ref('');
const messageKind = ref('');
const saving = ref(false);
const pickerRef = ref(null);
let pickTarget = null;

watch(files, (next) => {
  drafts.clear();
  for (const file of next || []) {
    drafts.set(file.id, parseDraft(file));
    if (!openStates.has('server-file-' + file.id)) openStates.set('server-file-' + file.id, true);
  }
}, { immediate: true });

/** 每个文件只算一次分组/搜索，避免模板里反复遍历上百个属性。 */
const views = computed(() => {
  const query = search.value.trim().toLowerCase();
  const out = new Map();
  for (const file of files.value) {
    const draft = drafts.get(file.id) || parseDraft(file);
    if (draft.kind === 'lines') {
      out.set(file.id, { draft, kind: 'lines', grouping: groupProperties(file.id, draft.tokens, query) });
    } else if (draft.kind === 'json') {
      out.set(file.id, { draft, kind: 'json', json: filterJsonItems(file.id, draft.data, query) });
    } else {
      out.set(file.id, { draft, kind: 'text' });
    }
  }
  return out;
});

const EMPTY_VIEW = { draft: { kind: 'text', text: '' }, kind: 'text', grouping: { count: 0, groups: [] } };

function viewFor(file) {
  return views.value.get(file.id) || EMPTY_VIEW;
}

/** 搜索时只保留有匹配条目的文件；空搜索显示全部 6 个文件。 */
const visibleFiles = computed(() => {
  const query = search.value.trim();
  if (!query) return files.value;
  return files.value.filter((file) => {
    const view = views.value.get(file.id);
    if (!view) return true;
    if (view.kind === 'lines') return view.grouping.count > 0;
    if (view.kind === 'json') return view.json.items.length > 0;
    return true;
  });
});

const dirtyCount = computed(() => {
  let count = 0;
  for (const file of files.value) {
    const draft = drafts.get(file.id);
    if (draft && isDraftDirty(file, draft)) count++;
  }
  return count;
});
const saveLabel = computed(() => (dirtyCount.value > 0
  ? '保存并重载（' + dirtyCount.value + '）' : '保存并重载'));

watch(dirtyCount, (count) => emit('dirty', count), { immediate: true });

function fileCount(file) {
  const view = viewFor(file);
  if (view.kind === 'lines') return view.grouping.count;
  if (view.kind === 'json') return view.draft.data.length;
  return 1;
}

function fileElementId(file) {
  return 'server-file-' + file.id;
}

function fileCardClass(file) {
  const meta = FILE_META[file.id];
  return meta ? 'server-file-card ' + meta.className : '';
}

function isOpen(id, fallback) {
  return openStates.has(id) ? openStates.get(id) : fallback;
}

function onToggle(id, event) {
  const target = event.target;
  if (target && target.id) openStates.set(target.id, target.open);
}

function specOf(file, key) {
  return specFor(file.id, key);
}

function badgesFor(file, key) {
  const badges = [];
  if (file.restartRequired) badges.push({ text: '需重启', kind: 'restart' });
  if (specOf(file, key).type === 'password') badges.push({ text: '敏感', kind: '' });
  return badges;
}

function inputType(spec) {
  if (spec.type === 'int' || spec.type === 'number') return 'number';
  if (spec.type === 'password') return 'password';
  if (spec.type === 'url') return 'url';
  return 'text';
}

function inputPlaceholder(spec) {
  if (spec.type === 'password') return spec.placeholder || '敏感值';
  if (spec.type === 'url') return spec.placeholder || 'https://…';
  return spec.placeholder || '空';
}

function resetTitle(token) {
  return Object.prototype.hasOwnProperty.call(PROPERTY_DEFAULTS, token.key)
    ? '恢复为 Minecraft 原版默认值：' + (PROPERTY_DEFAULTS[token.key] || '（空）')
    : '恢复为本次加载时的值；保留配置项，不会删除这一条';
}

function bump(file) {
  // Map 的值替换一次，确保保存计数、按钮状态与分组视图同步刷新。
  drafts.set(file.id, { ...drafts.get(file.id) });
}

function setProperty(file, token, next) {
  token.value = String(next == null ? '' : next);
  bump(file);
}

function resetProperty(file, token) {
  const fallback = token.originalValue != null ? token.originalValue : '';
  token.value = Object.prototype.hasOwnProperty.call(PROPERTY_DEFAULTS, token.key)
    ? PROPERTY_DEFAULTS[token.key] : fallback;
  bump(file);
}

function setTextDraft(file, text) {
  drafts.get(file.id).text = text;
  bump(file);
}

function jsonTitle(item, index) {
  return item.name || item.ip || item.uuid || ('条目 ' + (index + 1));
}

function jsonFieldSpec(file, key, item) {
  const spec = JSON_SPECS[file.id] || { fields: {} };
  return (spec.fields && spec.fields[key]) || {
    desc: '自定义字段',
    type: typeof item[key] === 'boolean' ? 'bool'
      : typeof item[key] === 'number' ? 'int' : 'text',
  };
}

function setJsonField(file, item, key, value) {
  const spec = jsonFieldSpec(file, key, item);
  if (spec.type === 'bool') item[key] = Boolean(value);
  else if (spec.type === 'enum') item[key] = spec.numeric ? Number(value) : value;
  else if (spec.type === 'int' || spec.type === 'number') item[key] = value === '' ? 0 : Number(value);
  else item[key] = value;
  bump(file);
}

function removeJsonItem(file, index) {
  drafts.get(file.id).data.splice(index, 1);
  bump(file);
}

function scrollToId(id) {
  document.getElementById(id)?.scrollIntoView({ block: 'start' });
}

function addJsonItem(file) {
  const draft = drafts.get(file.id);
  draft.data.push(defaultJsonItem(file.id));
  search.value = '';
  bump(file);
  scrollToId(jsonItemId(file.id, draft.data.length - 1));
}

function pickPlayer(file, item) {
  pickTarget = { file, item };
  pickerRef.value?.open(item ? '选择玩家' : ('选择玩家' + (file.id === 'whitelist' ? '加入白名单' : '')));
}

function onPlayerPicked(player) {
  if (!pickTarget) return;
  const { file, item } = pickTarget;
  const draft = drafts.get(file.id);
  const target = item || defaultJsonItem(file.id);
  target.uuid = player.uuid;
  target.name = player.name;
  autoFillBan(file.id, target);
  if (!item) draft.data.push(target);
  search.value = '';
  bump(file);
  pickTarget = null;
  if (!item) scrollToId(jsonItemId(file.id, draft.data.length - 1));
}

async function save() {
  const dirtyFiles = files.value.filter((file) => {
    const draft = drafts.get(file.id);
    return draft && isDraftDirty(file, draft);
  });
  if (!dirtyFiles.length) {
    message.value = '没有需要保存的修改。';
    messageKind.value = 'ok';
    return;
  }
  saving.value = true;
  message.value = '保存中…';
  messageKind.value = '';
  let saved = 0;
  let restart = false;
  let live = false;
  try {
    for (const file of dirtyFiles) {
      const result = await request('/api/server/config', {
        method: 'POST',
        body: JSON.stringify({ id: file.id, content: buildContent(drafts.get(file.id)) }),
      });
      if (!result.ok || result.data?.ok === false) {
        throw new Error(result.data?.error || '保存失败');
      }
      const data = result.data || {};
      saved++;
      restart = restart || Boolean(data.restartRequired);
      live = live || Boolean(data.applied);
    }
    message.value = '已保存 ' + saved + ' 个文件'
      + (restart ? '；部分需要重启服务器生效' : (live ? '；已立即生效' : ''));
    messageKind.value = restart ? 'warning' : 'ok';
    emit('saved', { saved });
    emit('reload');
  } catch (error) {
    message.value = error.message || '保存失败';
    messageKind.value = 'error';
  } finally {
    saving.value = false;
  }
}

function reload() {
  message.value = '';
  messageKind.value = '';
  emit('reload');
}
</script>
