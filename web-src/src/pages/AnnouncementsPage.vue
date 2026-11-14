<template>
  <TabShell name="announcements">
    <section v-if="!connected" class="panel">
      <h3>连接管理端</h3>
      <p class="muted small">需要管理端 token；token 只保存在当前标签页的 sessionStorage。</p>
      <div class="announcements-connect">
        <el-input v-model="tokenInput" type="password" placeholder="Bearer token" show-password />
        <el-button type="primary" :loading="connecting" @click="connect">连接</el-button>
      </div>
      <p v-if="authError" class="container-message error">{{ authError }}</p>
    </section>

    <section v-else class="panel announcements-panel">
      <div class="announcements-toolbar">
        <span id="announcements-summary" class="muted small">{{ summary }}</span>
        <span class="spacer"></span>
        <el-button id="announcements-add" size="small" @click="add">+ 新建</el-button>
        <el-button id="announcements-refresh" size="small" :loading="loading" @click="reload">
          刷新
        </el-button>
      </div>
      <p id="announcements-message" class="message" :class="messageKind">{{ message }}</p>

      <div class="announcements-layout">
        <aside class="announcements-side">
          <div id="announcements-list" class="announcements-list">
            <button
              v-for="(item, index) in list"
              :key="index + ':' + (item.id || item.name)"
              type="button"
              class="announcement-item"
              :class="{ active: index === selectedIndex }"
              @click="selectedIndex = index"
            >
              <span class="announcement-name">{{ item.name || item.id || '未命名' }}</span>
              <span class="announcement-badges">
                <span class="config-editor-badge">{{ item.trigger || 'manual' }}</span>
                <span v-if="!item.enabled" class="config-editor-badge">停用</span>
                <span v-if="item.bossbar" class="config-editor-badge">BossBar</span>
              </span>
            </button>
            <p v-if="!list.length" class="muted small">还没有公告，点“+ 新建”。</p>
          </div>
        </aside>

        <div id="announcements-editor" class="announcements-editor">
          <template v-if="selected">
            <div class="config-editor-page-head">
              <h3>{{ selected.name || '未命名公告' }}</h3>
              <span class="config-editor-badge">{{ selected.trigger || 'manual' }}</span>
              <span v-if="selected.enabled" class="config-editor-badge">启用</span>
              <div class="config-editor-actions">
                <el-button type="primary" size="small" :loading="saving" @click="save">保存</el-button>
                <el-button size="small" :loading="previewing" @click="previewAnnouncement">预览</el-button>
                <el-button size="small" :loading="sending" @click="send">发送</el-button>
                <el-button size="small" @click="duplicate">复制</el-button>
                <el-button size="small" type="danger" plain @click="remove">删除</el-button>
              </div>
            </div>

            <div class="announcement-form">
              <details class="config-editor-group" open>
                <summary>基础</summary>
                <div class="group-body">
                  <div class="entry announcement-row">
                    <div class="entry-key">名称</div>
                    <div class="entry-control"><el-input v-model="selected.name" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">ID</div>
                    <div class="entry-control"><el-input :model-value="selected.id" readonly /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">启用</div>
                    <div class="entry-control"><el-switch v-model="selected.enabled" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">触发</div>
                    <div class="entry-control">
                      <el-select v-model="selected.trigger" style="width: 100%">
                        <el-option v-for="value in model.triggers" :key="value" :label="value" :value="value" />
                      </el-select>
                    </div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">间隔（秒）</div>
                    <div class="entry-control">
                      <el-input-number v-model="selected.intervalSeconds" :min="10" :max="86400" style="width: 100%" />
                    </div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">计划时间</div>
                    <div class="entry-control">
                      <el-input v-model="selected.startAt" placeholder="yyyy-MM-dd HH:mm" />
                    </div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">优先级</div>
                    <div class="entry-control">
                      <el-input-number v-model="selected.priority" :min="-100" :max="100" style="width: 100%" />
                    </div>
                  </div>
                </div>
              </details>

              <details class="config-editor-group" open>
                <summary>目标</summary>
                <div class="group-body">
                  <div class="entry announcement-row">
                    <div class="entry-key">范围</div>
                    <div class="entry-control">
                      <el-select v-model="selected.target" style="width: 100%">
                        <el-option v-for="value in model.targets" :key="value" :label="value" :value="value" />
                      </el-select>
                    </div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">目标值</div>
                    <div class="entry-control">
                      <el-input v-model="selected.targetValue" placeholder="权限路径 / 维度 / 玩家名，逗号分隔" />
                    </div>
                  </div>
                </div>
              </details>

              <details class="config-editor-group" open>
                <summary>展示</summary>
                <div class="group-body">
                  <div class="entry announcement-row">
                    <div class="entry-key">聊天</div>
                    <div class="entry-control"><el-switch v-model="selected.chat" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">聊天文本</div>
                    <div class="entry-control"><el-input v-model="selected.chatText" type="textarea" :rows="4" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">ActionBar</div>
                    <div class="entry-control"><el-switch v-model="selected.actionbar" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">ActionBar 文本</div>
                    <div class="entry-control"><el-input v-model="selected.actionbarText" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">Title</div>
                    <div class="entry-control"><el-switch v-model="selected.title" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">标题</div>
                    <div class="entry-control"><el-input v-model="selected.titleText" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">副标题</div>
                    <div class="entry-control"><el-input v-model="selected.subtitleText" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">淡入/停留/淡出（tick）</div>
                    <div class="entry-control inline-numbers">
                      <el-input-number v-model="selected.fadeInTicks" :min="0" controls-position="right" />
                      <el-input-number v-model="selected.stayTicks" :min="0" controls-position="right" />
                      <el-input-number v-model="selected.fadeOutTicks" :min="0" controls-position="right" />
                    </div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">BossBar</div>
                    <div class="entry-control"><el-switch v-model="selected.bossbar" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">BossBar 文本</div>
                    <div class="entry-control"><el-input v-model="selected.bossbarText" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">BossBar 颜色</div>
                    <div class="entry-control">
                      <el-select v-model="selected.bossbarColor" style="width: 100%">
                        <el-option v-for="value in model.colors" :key="value" :label="value" :value="value" />
                      </el-select>
                    </div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">BossBar 样式</div>
                    <div class="entry-control">
                      <el-select v-model="selected.bossbarOverlay" style="width: 100%">
                        <el-option v-for="value in model.overlays" :key="value" :label="value" :value="value" />
                      </el-select>
                    </div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">BossBar 时长（秒）</div>
                    <div class="entry-control">
                      <el-input-number v-model="selected.durationSeconds" :min="1" :max="3600" style="width: 100%" />
                    </div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">声音</div>
                    <div class="entry-control"><el-switch v-model="selected.sound" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">声音 ID</div>
                    <div class="entry-control"><el-input v-model="selected.soundId" /></div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">音量 / 音高</div>
                    <div class="entry-control inline-numbers">
                      <el-input-number v-model="selected.soundVolume" :min="0" :max="2" :step="0.1" controls-position="right" />
                      <el-input-number v-model="selected.soundPitch" :min="0.5" :max="2" :step="0.1" controls-position="right" />
                    </div>
                  </div>
                </div>
              </details>

              <details class="config-editor-group" open>
                <summary>交互</summary>
                <div class="group-body">
                  <div class="entry announcement-row">
                    <div class="entry-key">点击动作</div>
                    <div class="entry-control">
                      <el-select v-model="selected.clickAction" style="width: 100%">
                        <el-option v-for="value in model.clickActions" :key="value" :label="value" :value="value" />
                      </el-select>
                    </div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">点击值</div>
                    <div class="entry-control">
                      <el-input v-model="selected.clickValue" placeholder="命令 / URL / 文本" />
                    </div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">点击权限</div>
                    <div class="entry-control">
                      <el-input v-model="selected.clickPermission" placeholder="run 事件留空要求 OP" />
                    </div>
                  </div>
                  <div class="entry announcement-row">
                    <div class="entry-key">悬浮文本</div>
                    <div class="entry-control"><el-input v-model="selected.hoverText" /></div>
                  </div>
                </div>
              </details>
            </div>
          </template>
          <p v-else class="muted">请选择或新建一条公告。</p>

          <div id="announcement-preview" class="announcement-preview">
            <div class="announcement-preview-title">{{ previewTitle }}</div>
            <div v-if="preview" class="announcement-preview-body">
              <template v-if="(preview.chat || []).length">
                <div class="preview-label">聊天</div>
                <div v-for="(line, index) in preview.chat" :key="'chat-' + index" class="preview-chat-line">
                  {{ line }}
                </div>
              </template>
              <div v-if="preview.actionbar" class="preview-actionbar">ActionBar：{{ preview.actionbar }}</div>
              <div v-if="preview.title" class="preview-title">Title：{{ preview.title }}</div>
              <div v-if="preview.subtitle" class="preview-subtitle">Subtitle：{{ preview.subtitle }}</div>
              <div v-if="preview.bossbar" class="preview-bossbar">BossBar：{{ preview.bossbar }}</div>
              <div v-if="preview.sound" class="preview-sound">声音：{{ preview.sound }}</div>
            </div>
            <div v-else class="announcement-preview-body muted small">
              点“预览”查看变量替换后的效果。
            </div>
          </div>
        </div>
      </div>
    </section>
  </TabShell>
</template>

<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import TabShell from '../components/TabShell.vue';
import { useAuth } from '../composables/useAuth.js';
import {
  createAnnouncement,
  duplicateAnnouncement,
  emptyModel,
  normalizeModel,
  previewChannels,
  summaryText,
} from './announcements/model.js';

defineOptions({ name: 'AnnouncementsPage' });

const model = reactive(emptyModel());
const list = ref([]);
const selectedIndex = ref(0);
const loading = ref(false);
const saving = ref(false);
const previewing = ref(false);
const sending = ref(false);
const applying = ref(false);
const dirty = ref(false);
const error = ref('');
const message = ref('');
const messageKind = ref('');
const preview = ref(null);

const {
  tokenInput,
  connected,
  connecting,
  error: authError,
  connect,
  api,
} = useAuth({
  autoConnect: true,
  onConnected: load,
});

const selected = computed(() => list.value[selectedIndex.value] || null);
const summary = computed(() => summaryText(list.value.length, dirty.value));
const previewTitle = computed(() => {
  if (!preview.value) return '预览';
  const channels = previewChannels(preview.value);
  return '预览 · ' + (preview.value.player || '') + ' · ' + (channels.join(' / ') || '无展示');
});

watch(list, () => {
  if (applying.value) return;
  dirty.value = true;
}, { deep: true });

function setMessage(text, kind = '') {
  message.value = text || '';
  messageKind.value = kind;
}

function applyModel(data) {
  applying.value = true;
  const next = normalizeModel(data);
  model.triggers = next.triggers;
  model.targets = next.targets;
  model.colors = next.colors;
  model.overlays = next.overlays;
  model.clickActions = next.clickActions;
  model.history = next.history;
  list.value = next.announcements;
  if (selectedIndex.value >= list.value.length) {
    selectedIndex.value = Math.max(0, list.value.length - 1);
  }
  nextTick(() => {
    applying.value = false;
    dirty.value = false;
  });
}

async function load() {
  loading.value = true;
  error.value = '';
  setMessage('');
  try {
    const result = await api('/api/announcements');
    if (!result.ok) throw new Error(result.data?.error || '读取公告失败');
    applyModel(result.data);
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

async function reload() {
  await load();
  if (!error.value) setMessage('已重新读取公告。', 'ok');
}

async function save() {
  saving.value = true;
  error.value = '';
  setMessage('');
  try {
    const result = await api('/api/announcements', {
      method: 'PUT',
      body: JSON.stringify({ announcements: list.value }),
    });
    const data = result.data || {};
    if (!result.ok || data.ok === false) {
      throw new Error((data.errors || []).join('；') || data.error || '保存失败');
    }
    applyModel(data);
    setMessage(data.message || '公告已保存', 'ok');
  } catch (e) {
    setMessage(e.message || String(e), 'error');
  } finally {
    saving.value = false;
  }
}

async function previewAnnouncement() {
  if (!selected.value) return;
  previewing.value = true;
  setMessage('');
  try {
    const result = await api('/api/announcements/preview', {
      method: 'POST',
      body: JSON.stringify({ announcement: selected.value }),
    });
    const data = result.data || {};
    if (!result.ok || data.ok === false) {
      throw new Error((data.errors || []).join('；') || data.error || '预览失败');
    }
    preview.value = data;
    setMessage('预览已更新', 'ok');
  } catch (e) {
    setMessage(e.message || String(e), 'error');
  } finally {
    previewing.value = false;
  }
}

async function send() {
  if (!selected.value) return;
  sending.value = true;
  setMessage('');
  try {
    const first = await api('/api/announcements/send', {
      method: 'POST',
      body: JSON.stringify({ announcement: selected.value, dryRun: true }),
    });
    const firstData = first.data || {};
    if (!first.ok || firstData.ok === false) {
      throw new Error((firstData.errors || []).join('；') || firstData.error || '发送失败');
    }
    if (firstData.preview) preview.value = firstData.preview;
    await ElMessageBox.confirm(
      (firstData.message || '预览通过') + '\n\n确认推送给 ' + (firstData.recipients || 0) + ' 名玩家？',
      '发送公告',
      { confirmButtonText: '发送', cancelButtonText: '取消', type: 'warning' },
    );
    const result = await api('/api/announcements/send', {
      method: 'POST',
      body: JSON.stringify({ announcement: selected.value, dryRun: false, confirm: true }),
    });
    const data = result.data || {};
    setMessage(data.message || (result.ok ? '已发送' : '发送失败'), result.ok ? 'ok' : 'error');
  } catch (e) {
    if (e === 'cancel' || e?.message === 'cancel') return;
    setMessage(e.message || String(e), 'error');
  } finally {
    sending.value = false;
  }
}

function add() {
  list.value.push(createAnnouncement());
  selectedIndex.value = list.value.length - 1;
  dirty.value = true;
}

function duplicate() {
  if (!selected.value) return;
  list.value.splice(selectedIndex.value + 1, 0, duplicateAnnouncement(selected.value));
  selectedIndex.value += 1;
  dirty.value = true;
}

async function remove() {
  if (!selected.value) return;
  try {
    await ElMessageBox.confirm(
      '删除公告「' + (selected.value.name || selected.value.id) + '」？',
      '删除公告',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  list.value.splice(selectedIndex.value, 1);
  selectedIndex.value = Math.max(0, selectedIndex.value - 1);
  dirty.value = true;
}
</script>

<style scoped>
.announcements-connect {
  display: flex;
  gap: 8px;
  align-items: center;
  max-width: 560px;
}
.announcements-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.announcements-toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
}
.spacer { flex: 1; }
.announcements-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}
.announcements-side {
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--panel-soft);
  padding: 8px;
}
.announcements-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 70vh;
  overflow: auto;
}
.announcement-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 9px 10px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: var(--fg-soft);
  text-align: left;
}
.announcement-item:hover { background: var(--panel); }
.announcement-item.active {
  background: var(--panel);
  border-color: var(--accent);
  color: var(--fg);
}
.announcement-name { font-weight: 700; }
.announcement-badges { display: flex; flex-wrap: wrap; gap: 4px; }
.announcement-editor { min-width: 0; }
.announcement-form { display: flex; flex-direction: column; gap: 10px; }
.announcement-form .config-editor-group { margin: 0; }
.announcement-preview {
  margin-top: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--panel-soft);
  padding: 12px 14px;
}
.announcement-preview-title { font-weight: 700; margin-bottom: 8px; }
.announcement-preview-body { display: flex; flex-direction: column; gap: 4px; }
.preview-label { color: var(--muted); font-size: 12px; }
.preview-chat-line {
  padding: 4px 8px;
  border-radius: 6px;
  background: var(--panel);
}
.inline-numbers { display: flex; gap: 8px; }
.inline-numbers :deep(.el-input-number) { flex: 1; min-width: 0; }
.message { margin: 0; min-height: 20px; }
.message.ok { color: var(--ok); }
.message.error { color: var(--danger); }
@media (max-width: 980px) {
  .announcements-layout { grid-template-columns: 1fr; }
  .announcements-list { max-height: 220px; }
}
</style>
