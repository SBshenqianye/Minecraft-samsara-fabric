<template>
  <TabShell name="track" title="轨迹回看" description="查看最近可回看的玩家与时段；真正的回放仍在游戏内执行。">
    <section v-if="!connected" class="panel">
      <h3>连接管理端</h3>
      <p class="muted small">需要管理端 token；token 只保存在当前标签页的 sessionStorage。</p>
      <div class="track-connect">
        <el-input v-model="tokenInput" type="password" placeholder="Bearer token" show-password />
        <el-button type="primary" :loading="connecting" @click="connect">连接</el-button>
      </div>
      <p v-if="error" class="container-message error">{{ error }}</p>
    </section>

    <template v-else>
      <p v-if="error" class="container-message error">{{ error }}</p>
      <section class="panel">
        <div class="track-toolbar">
          <div>
            <h3>最近轨迹</h3>
            <p class="muted small">
              当前周目 #{{ cycleId }} · 只读列表 · 不删除、不改写轨迹
            </p>
          </div>
          <el-button size="small" @click="load" :loading="loading">刷新</el-button>
        </div>
        <div class="track-filters">
          <el-select v-model="days" style="width: 150px" @change="load">
            <el-option label="最近 1 天" :value="1" />
            <el-option label="最近 3 天" :value="3" />
            <el-option label="最近 7 天" :value="7" />
            <el-option label="最近 30 天" :value="30" />
          </el-select>
          <el-input v-model="playerFilter" placeholder="玩家名 / UUID（可选）" clearable
              style="max-width: 240px" @keyup.enter="load" />
          <el-button size="small" type="primary" :loading="loading" @click="load">查询</el-button>
        </div>
        <p class="muted small">
          要按区块查看方块差异，请到
          <router-link to="/samsara/sky/diff">Diff 回看</router-link>
          或
          <router-link to="/samsara/world/map">世界地图</router-link>。
        </p>

        <div v-if="tracks.length" class="track-list">
          <article v-for="track in tracks" :key="trackKey(track)" class="track-row">
            <div class="track-main">
              <strong>{{ track.name || track.uuid }}</strong>
              <span class="muted small">
                周目 {{ track.cycleId }} · {{ track.firstAt }} → {{ track.lastAt }}
              </span>
              <span v-if="track.samples" class="muted small">{{ track.samples }} 点</span>
              <el-tag v-if="track.truncated" type="warning" size="small">已截断</el-tag>
            </div>
            <div class="track-actions">
              <code class="track-command">{{ track.command }}</code>
              <el-button size="small" @click="copyCommand(track)">复制命令</el-button>
            </div>
          </article>
        </div>
        <p v-else class="muted small">
          {{ loading ? '读取中…' : '所选范围内没有可回看的轨迹。' }}
        </p>
      </section>
    </template>
  </TabShell>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import TabShell from '../components/TabShell.vue';

const token = ref(sessionStorage.getItem('samsara-token') || '');
const tokenInput = ref(token.value);
const connected = ref(false);
const connecting = ref(false);
const loading = ref(false);
const error = ref('');
const days = ref(7);
const playerFilter = ref('');
const cycleId = ref(0);
const tracks = ref([]);

onMounted(() => {
  if (token.value) connect();
});

async function api(path) {
  const response = await fetch(path, {
    headers: { Authorization: 'Bearer ' + token.value },
  });
  const text = await response.text();
  let body = {};
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    body = { error: text };
  }
  if (response.status === 401) {
    connected.value = false;
    throw new Error('token 无效或已失效');
  }
  return { status: response.status, data: body };
}

async function connect() {
  connecting.value = true;
  error.value = '';
  try {
    const value = (tokenInput.value || '').trim();
    if (!value) throw new Error('请输入 token');
    token.value = value;
    const status = await api('/api/status');
    if (status.status !== 200) throw new Error(status.data.error || '连接失败');
    sessionStorage.setItem('samsara-token', value);
    connected.value = true;
    await load();
  } catch (e) {
    error.value = e.message || String(e);
    connected.value = false;
  } finally {
    connecting.value = false;
  }
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const params = new URLSearchParams({ days: String(days.value), limit: '200' });
    if (playerFilter.value.trim()) params.set('player', playerFilter.value.trim());
    const result = await api('/api/track?' + params.toString());
    if (result.status !== 200 || !result.data.ok) {
      throw new Error(result.data.error || '读取轨迹列表失败');
    }
    cycleId.value = result.data.cycleId;
    tracks.value = result.data.tracks || [];
  } catch (e) {
    tracks.value = [];
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

function trackKey(track) {
  return [track.uuid, track.cycleId, track.firstMs, track.lastMs].join(':');
}

async function copyCommand(track) {
  try {
    await navigator.clipboard.writeText(track.command);
    ElMessage.success('已复制回看命令');
  } catch {
    try {
      const area = document.createElement('textarea');
      area.value = track.command;
      area.setAttribute('readonly', 'readonly');
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      document.execCommand('copy');
      area.remove();
      ElMessage.success('已复制回看命令');
    } catch {
      ElMessage.error('复制失败，请手动选择命令');
    }
  }
}
</script>

<style scoped>
.track-connect {
  display: flex;
  gap: 10px;
  max-width: 520px;
  margin-top: 10px;
}

.track-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.track-toolbar h3 {
  margin: 0 0 3px;
}

.track-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.track-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.track-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
}

.track-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.track-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.track-command {
  max-width: min(520px, 60vw);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--muted);
}
</style>
