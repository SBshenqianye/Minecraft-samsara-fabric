<template>
  <TabShell name="inventory" title="玩家背包" description="地图选玩家">
    <section v-if="!connected" class="panel">
      <h3>连接管理端</h3>
      <p class="muted small">需要管理端 token；token 只保存在当前标签页的 sessionStorage。</p>
      <div class="inventory-connect">
        <el-input v-model="tokenInput" type="password" placeholder="Bearer token" show-password />
        <el-button type="primary" :loading="connecting" @click="connect">连接</el-button>
      </div>
      <p v-if="error" class="container-message error">{{ error }}</p>
    </section>

    <template v-else>
      <p v-if="error" class="container-message error">{{ error }}</p>
      <p v-if="message" class="container-message ok">{{ message }}</p>

      <div class="inventory-layout" :class="{ embedded: isEmbedded }">
        <aside class="panel inventory-side">
          <template v-if="source === 'identity'">
            <h3>身份 data UUID</h3>
            <p class="muted small">从身份组详情进入时自动填入；也可以手动输入 data UUID。</p>
            <el-input v-model="identityUuid" placeholder="data UUID" clearable />
            <el-button type="primary" size="small" :loading="loading" @click="loadIdentity">读取身份背包</el-button>
            <el-button size="small" @click="switchSource('player')">返回账号背包</el-button>
          </template>
          <template v-else>
            <h3>玩家</h3>
            <el-input v-model="query" placeholder="搜索名字 / UUID" clearable />
            <el-select v-model="scope" class="inventory-scope" @change="reload">
              <el-option label="背包（主背包 + 盔甲 + 副手）" value="inventory" />
              <el-option label="末影箱" value="ender" />
              <el-option label="盔甲（只读分区）" value="armor" />
            </el-select>
            <div class="inventory-player-list">
              <button
                v-for="player in filteredPlayers"
                :key="player.uuid"
                class="inventory-player"
                :class="{ active: selectedPlayer && selectedPlayer.uuid === player.uuid, online: player.online }"
                type="button"
                @click="selectPlayer(player)"
              >
                <span class="inventory-player-name">{{ player.name || player.uuid }}</span>
                <span class="inventory-player-meta" :class="{ 'is-online': player.online }">
                  {{ player.online ? '当前在线' : '当前不在线' }}
                  <template v-if="player.dimension"> · {{ shortDimension(player.dimension) }}</template>
                  <template v-if="player.lastSeen"> · {{ player.lastSeen }}</template>
                </span>
              </button>
              <p v-if="!filteredPlayers.length" class="muted small">没有匹配的已知玩家。</p>
            </div>
          </template>
        </aside>

        <section class="panel inventory-main">
          <div v-if="source === 'player'" class="inventory-map-block">
            <div class="inventory-map-head">
              <h3>地图选玩家</h3>
              <span class="muted small">在线玩家实时位置；离线玩家保留最后退出位置。</span>
              <span class="muted small" v-if="playersUpdatedAt">· 状态 {{ playersUpdatedAt }}</span>
              <span class="spacer"></span>
              <select v-model="mapDimension">
                <option v-for="dimension in mapDimensions" :key="dimension.id" :value="dimension.id">
                  {{ dimension.name }}（{{ dimension.id }}）
                </option>
              </select>
            </div>
            <div class="inventory-map">
              <MapPicker
                :markers="playerMarkers"
                :dimension="mapDimension"
                :selected-id="selectedPlayer?.uuid || ''"
                :height="520"
                @select="selectMarker"
              />
            </div>
          </div>

          <div v-if="!data" class="muted small">
            {{ source === 'identity' ? '输入 data UUID 后读取身份背包。' : '从地图或左侧列表选择玩家。' }}
          </div>
          <template v-else>
            <div class="inventory-head">
              <div>
                <h3>{{ data.player.name || data.player.uuid }}</h3>
                <p class="muted small">
                  <template v-if="source === 'identity'">身份数据 {{ data.data }} · </template>
                  {{ data.player.uuid }} · {{ scopeLabel }} · revision {{ revision }}
                </p>
              </div>
              <div class="inventory-badges">
                <el-tag :type="data.online ? 'success' : 'info'" size="small">
                  {{ data.online ? '在线 · 直接写入' : (source === 'identity' ? '身份数据 · 直接写入文件' : '离线 · 安全队列') }}
                </el-tag>
                <el-tag v-if="data.pendingWrite" type="warning" size="small">待写入队列</el-tag>
                <el-tag v-if="dirty" type="danger" size="small">有未提交修改</el-tag>
              </div>
            </div>

            <div class="inventory-actions">
              <div class="mode-switch" role="group" aria-label="写入模式">
                <button
                  v-for="option in modeOptions"
                  :key="option.id"
                  type="button"
                  :class="{ active: mode === option.id }"
                  :title="option.hint"
                  @click="setMode(option.id)"
                >{{ option.label }}</button>
              </div>
              <el-button size="small" :disabled="saving" @click="refresh">重置 / 重新读取</el-button>
              <el-button
                v-if="mode !== 'simulate'"
                size="small"
                type="primary"
                :disabled="!dirty || saving"
                :loading="saving"
                @click="confirmSave"
              >
                {{ mode === 'live' ? '立即写入' : '保存修改' }}{{ dirty ? ' *' : '' }}
              </el-button>
            </div>

            <p v-if="!data.online && source !== 'identity'" class="muted small">
              离线写入不会直接改运行中的 .dat，而是在玩家下次登录或停服前应用。
            </p>

            <ItemWorkbench
              :slots="slots"
              :items="data.items || []"
              :layout="layout"
              :schema="schema"
              :mode="mode"
              item-endpoint="/api/inventory/item"
              :revision="revision"
              :disabled="saving"
              @change="onWorkbenchChange"
            />

            <p v-if="data.pendingWrite" class="muted small">
              当前显示安全队列中的内容；写入会更新队列，登录或停服前应用。
            </p>
          </template>
        </section>
      </div>
    </template>
  </TabShell>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import TabShell from '../components/TabShell.vue';
import ItemWorkbench from '../components/ItemWorkbench.vue';
import MapPicker from '../components/MapPicker.vue';
import { useLiveRefresh } from '../composables/useLiveRefresh.js';
import { itemCursor } from '../composables/itemCursor.js';

const route = useRoute();
const token = ref(sessionStorage.getItem('samsara-token') || '');
const tokenInput = ref(token.value);
const connected = ref(false);
const connecting = ref(false);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const message = ref('');
const query = ref('');
const scope = ref('inventory');
const players = ref([]);
const selectedPlayer = ref(null);
const data = ref(null);
const slots = ref([]);
const baselineSlots = ref([]);
const revision = ref(0);
const dirty = ref(false);
const mode = ref('simulate');
const schema = ref({});
const source = ref('player');
const identityUuid = ref('');
const isEmbedded = computed(() => String(route.query.embed || '') === '1');
const mapDimension = ref('minecraft:overworld');
const playersUpdatedAt = ref('');
let liveTimer = null;
let playersTimer = null;

const modeOptions = [
  { id: 'live', label: '实时', hint: '每次操作立即写入；离线玩家/未加载目标会排队。' },
  { id: 'preset', label: '预设', hint: '本地编辑，点保存一次性提交；重置会丢弃未提交修改。' },
  { id: 'simulate', label: '模拟', hint: '只在本地演练，不会写入服务器。' },
];

const currentModeHint = computed(() =>
  modeOptions.find((option) => option.id === mode.value)?.hint || '');

const scopeLabel = computed(() => {
  if (scope.value === 'ender') return '末影箱';
  if (scope.value === 'armor') return '盔甲';
  return '背包';
});

const filteredPlayers = computed(() => {
  const value = query.value.trim().toLowerCase();
  if (!value) return players.value;
  return players.value.filter((player) =>
    String(player.name || '').toLowerCase().includes(value)
      || String(player.uuid || '').toLowerCase().includes(value));
});

const cursorPending = computed(() => !!itemCursor.item);

const liveState = useLiveRefresh({
  mode: () => mode.value,
  key: () => `${source.value}:${selectedPlayer.value?.uuid || identityUuid.value}:${scope.value}`,
  canRefresh: () => connected.value && !!data.value && !dirty.value
    && !saving.value && !loading.value && !cursorPending.value,
  fetchSnapshot: async (signal) => {
    const params = source.value === 'identity'
      ? new URLSearchParams({ source: 'identity', data: identityUuid.value, scope: scope.value })
      : new URLSearchParams({ player: selectedPlayer.value?.uuid || '', scope: scope.value });
    const result = await api('/api/inventory?' + params.toString(), { signal });
    return result.status === 200 && result.data.ok ? result.data : null;
  },
  applySnapshot: (snapshot) => {
    // 双检：请求在途期间用户一旦开始编辑就丢弃这次服务端快照。
    if (dirty.value || saving.value || cursorPending.value || !data.value) return false;
    const incoming = (snapshot.slots || []).slice();
    if (sameSlots(incoming, baselineSlots.value)) return false;
    applyInventoryData(snapshot);
    message.value = '检测到游戏内背包变化，已同步最新内容。';
    return true;
  },
});

const mapDimensions = computed(() => {
  const seen = new Map();
  const defaults = [
    { id: 'minecraft:overworld', name: '主世界' },
    { id: 'minecraft:the_nether', name: '下界' },
    { id: 'minecraft:the_end', name: '末地' },
  ];
  for (const dimension of defaults) seen.set(dimension.id, dimension);
  for (const player of players.value) {
    if (player.dimension && !seen.has(player.dimension)) {
      seen.set(player.dimension, { id: player.dimension, name: shortDimension(player.dimension) });
    }
  }
  return [...seen.values()];
});

const playerMarkers = computed(() => players.value
  .filter((player) => player.dimension && Number.isFinite(Number(player.x)))
  .map((player) => ({
    id: player.uuid,
    x: Number(player.x),
    z: Number(player.z),
    dimension: player.dimension,
    label: player.name || player.uuid.slice(0, 8),
    sub: `${player.online ? '在线' : '离线'} · ${Math.round(player.x)}, ${Math.round(player.y)}, ${Math.round(player.z)}`,
    kind: 'player',
    online: player.online === true,
    uuid: player.uuid,
    name: player.name || '',
  })));

const layout = computed(() => {
  if (scope.value === 'inventory') {
    // 对齐游戏内背包 GUI：装备/副手在上，主背包 9–35 居中，快捷栏 0–8 在下并留间隔。
    return [
      { label: '盔甲 36–39', start: 36, size: 4, columns: 1, row: 'gear', zone: 'gear' },
      { label: '副手 40', start: 40, size: 1, columns: 1, row: 'gear', zone: 'gear' },
      { label: '主背包 9–35', start: 9, size: 27, columns: 9, row: 'main', zone: 'main' },
      { label: '快捷栏 0–8', start: 0, size: 9, columns: 9, row: 'hotbar', zone: 'hotbar', gap: true },
    ];
  }
  if (scope.value === 'armor') {
    return [{ label: '盔甲 36–39', start: 0, size: 4, columns: 1 }];
  }
  return [{ label: '末影箱 0–26', start: 0, size: 27, columns: 9 }];
});

onMounted(() => {
  // 身份组背包已迁到全局大窗口，这里只处理真实玩家/离线安全队列。
  source.value = 'player';
  identityUuid.value = '';
  if (token.value) connect();
  playersTimer = window.setInterval(refreshPlayersQuietly, 4000);
});

onBeforeUnmount(() => {
  window.clearInterval(playersTimer);
  liveTimer && clearTimeout(liveTimer);
});

async function refreshPlayersQuietly() {
  if (!connected.value || source.value !== 'player') return;
  try {
    const result = await api('/api/server/players');
    if (result.status !== 200) return;
    const next = result.data.players || [];
    players.value = next;
    playersUpdatedAt.value = new Date().toLocaleTimeString();
    if (selectedPlayer.value) {
      const match = next.find((player) => player.uuid === selectedPlayer.value.uuid);
      if (match) {
        const wasOnline = selectedPlayer.value.online === true;
        selectedPlayer.value = match;
        if (data.value) {
          data.value = Object.assign({}, data.value, { online: match.online });
        }
        if (wasOnline !== match.online) {
          message.value = match.online
            ? '玩家已上线，可直接实时写入。'
            : '玩家已下线，后续写入会进入安全队列。';
        }
      }
    }
  } catch {
    // 轮询失败不打断当前编辑。
  }
}

function shortDimension(dimension) {
  const value = String(dimension || '');
  if (value.includes('nether')) return '下界';
  if (value.endsWith('the_end') || value.includes('end')) return '末地';
  if (value.includes('overworld')) return '主世界';
  return value.replace('minecraft:', '');
}

async function api(path, options = {}) {
  const headers = Object.assign({ Authorization: 'Bearer ' + token.value }, options.headers || {});
  if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, Object.assign({}, options, { headers }));
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
    const result = await api('/api/status');
    if (result.status !== 200) throw new Error(result.data.error || ('连接失败：' + result.status));
    sessionStorage.setItem('samsara-token', value);
    connected.value = true;
    await loadSchema();
    if (source.value === 'identity') {
      if (identityUuid.value) await loadIdentity();
    } else {
      await loadPlayers();
    }
  } catch (e) {
    error.value = e.message || String(e);
    connected.value = false;
  } finally {
    connecting.value = false;
  }
}

async function loadSchema() {
  try {
    const result = await api('/api/items/schema');
    if (result.status === 200 && result.data.ok) schema.value = result.data.schema || {};
  } catch {
    // schema 失败时仍可做数量编辑
  }
}

async function loadPlayers() {
  loading.value = true;
  try {
    const result = await api('/api/server/players');
    if (result.status !== 200) throw new Error(result.data.error || '读取玩家列表失败');
    players.value = result.data.players || [];
    selectedPlayer.value = players.value.find((player) => player.online)
      || players.value[0]
      || null;
    if (selectedPlayer.value?.dimension) mapDimension.value = selectedPlayer.value.dimension;
    if (selectedPlayer.value) await reload();
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

async function selectPlayer(player) {
  selectedPlayer.value = player;
  if (player.dimension) mapDimension.value = player.dimension;
  await reload();
}

function selectMarker(id) {
  const player = players.value.find((candidate) => candidate.uuid === id);
  if (player) selectPlayer(player);
}

async function switchSource(next) {
  if (next === 'identity') {
    source.value = 'identity';
    data.value = null;
    if (identityUuid.value) await loadIdentity();
  } else {
    source.value = 'player';
    data.value = null;
    if (!players.value.length) await loadPlayers();
    else if (selectedPlayer.value) await reload();
  }
}

async function loadIdentity() {
  const uuid = identityUuid.value.trim();
  if (!uuid) {
    error.value = '请输入 data UUID';
    return;
  }
  loading.value = true;
  error.value = '';
  message.value = '';
  try {
    const params = new URLSearchParams({ source: 'identity', data: uuid, scope: scope.value });
    const result = await api('/api/inventory?' + params.toString());
    if (result.status !== 200 || !result.data.ok) {
      throw new Error(result.data.error || '读取身份背包失败');
    }
    applyInventoryData(result.data);
  } catch (e) {
    data.value = null;
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

async function reload() {
  if (!selectedPlayer.value) return;
  await loadInventory();
}

async function refresh() {
  if (source.value === 'identity') await loadIdentity();
  else await loadInventory();
}

async function loadInventory() {
  if (!selectedPlayer.value) return;
  loading.value = true;
  error.value = '';
  message.value = '';
  try {
    const params = new URLSearchParams({
      player: selectedPlayer.value.uuid,
      scope: scope.value,
    });
    const result = await api('/api/inventory?' + params.toString());
    if (result.status !== 200 || !result.data.ok) {
      throw new Error(result.data.error || '读取背包失败');
    }
    applyInventoryData(result.data);
    if (result.data.pendingWrite) {
      message.value = '该玩家有待写入队列；当前显示队列中的内容，登录或停服前应用。';
    }
  } catch (e) {
    data.value = null;
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

function applyInventoryData(payload) {
  data.value = payload;
  slots.value = (payload.slots || []).slice();
  baselineSlots.value = slots.value.slice();
  revision.value = payload.revision;
  dirty.value = false;
  message.value = '';
}

function sameSlots(a, b) {
  const left = a || [];
  const right = b || [];
  if (left.length !== right.length) return false;
  for (let i = 0; i < left.length; i++) {
    if ((left[i] || '') !== (right[i] || '')) return false;
  }
  return true;
}

function onWorkbenchChange(payload) {
  if (!data.value) return;
  slots.value = payload.slots;
  data.value = Object.assign({}, data.value, { items: payload.items });
  dirty.value = !sameSlots(payload.slots, baselineSlots.value);
  if (!dirty.value) {
    liveTimer && clearTimeout(liveTimer);
    return;
  }
  if (mode.value !== 'live' || payload.pending) {
    if (mode.value === 'live' && payload.pending) {
      message.value = '光标上还有物品，放下后才会实时写入。';
    }
    return;
  }
  liveTimer && clearTimeout(liveTimer);
  liveTimer = setTimeout(() => save(true), 450);
}

async function setMode(next) {
  if (next === mode.value) return;
  if (next === 'live' && dirty.value) {
    try {
      await ElMessageBox.confirm(
        '切换到实时模式会立即把当前修改写入服务器。继续？',
        '切换到实时模式',
        { confirmButtonText: '切换并写入', cancelButtonText: '取消', type: 'warning' },
      );
    } catch {
      return;
    }
  }
  mode.value = next;
  if (next === 'live' && dirty.value) await save(true);
}

async function confirmSave() {
  if (!data.value) return;
  const label = data.value.online
    ? '直接写入在线玩家'
    : (source.value === 'identity' ? '直接写入身份数据文件（写入前自动备份）' : '写入离线安全队列');
  try {
    await ElMessageBox.confirm(
      `将${label}：${data.value.player.name || data.value.player.uuid} · ${scopeLabel.value}；`
        + `当前 revision ${revision.value}。写入前会保存 Web 审计快照。`,
      '确认写入玩家背包',
      { confirmButtonText: '确认写入', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  await save(false);
}

async function save(silent) {
  if (saving.value || !data.value) return;
  saving.value = true;
  error.value = '';
  try {
    const body = {
      scope: scope.value,
      revision: revision.value,
      slots: slots.value,
    };
    if (source.value === 'identity') {
      body.source = 'identity';
      body.data = identityUuid.value.trim();
    } else {
      body.player = data.value.player.uuid;
    }
    const result = await api('/api/inventory/write', {
      method: 'POST',
      body: JSON.stringify(body),
    });
    if (result.status === 409) {
      await refresh();
      throw new Error(result.data.error
        ? result.data.error + '（已重新读取）'
        : 'revision 冲突，已重新读取');
    }
    if (result.status !== 200 || !result.data.ok) {
      throw new Error(result.data.error || '写入失败');
    }
    const queued = result.data.queued === true;
    data.value = Object.assign({}, data.value, result.data);
    slots.value = (result.data.slots || slots.value).slice();
    baselineSlots.value = slots.value.slice();
    revision.value = result.data.revision;
    dirty.value = false;
    if (source.value === 'identity') {
      message.value = result.data.online
        ? '已写入在线玩家（同身份）；退出时会同步到身份数据。'
        : '已写入身份数据文件，并已自动备份写入前版本。';
    } else if (queued) {
      message.value = '玩家不在线，已写入安全队列；下次登录或停服前应用。';
    } else {
      message.value = '已写入在线玩家背包与盔甲/副手。';
    }
    if (!silent) ElMessage.success(message.value);
  } catch (e) {
    error.value = e.message || String(e);
    if (!silent) ElMessage.error(error.value);
  } finally {
    saving.value = false;
    liveState.invalidate();
  }
}
</script>

<style scoped>
.inventory-connect {
  display: flex;
  gap: 10px;
  max-width: 520px;
  margin-top: 10px;
}

.inventory-layout {
  display: grid;
  grid-template-columns: minmax(220px, 280px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.inventory-layout.embedded {
  grid-template-columns: minmax(0, 1fr);
}

.inventory-layout.embedded .inventory-side {
  display: none;
}

.inventory-side {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.inventory-scope {
  width: 100%;
}

.inventory-player-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 420px;
  overflow: auto;
}

.inventory-player {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  width: 100%;
  padding: 7px 9px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.inventory-player:hover {
  background: var(--panel-soft);
  border-color: var(--line);
}

.inventory-player.active {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.inventory-player.online {
  border-color: color-mix(in srgb, #4ade80 42%, var(--line));
}

.inventory-player.online.active {
  border-color: var(--accent);
}

.inventory-player-name {
  font-weight: 600;
}

.inventory-player-meta {
  color: var(--muted);
  font-size: 11px;
}

.inventory-player-meta.is-online {
  color: #3fbf72;
  font-weight: 600;
}

.inventory-map-block {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-bottom: 14px;
}

.inventory-map-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.inventory-map-head h3 {
  margin: 0;
}

.inventory-map-head select {
  padding: 4px 7px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel-soft);
  color: var(--fg);
  font-size: 12px;
}

.inventory-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.inventory-head h3 {
  margin: 0 0 3px;
}

.inventory-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.inventory-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.mode-switch {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--panel-soft);
}

.mode-switch button {
  padding: 4px 10px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  cursor: pointer;
}

.mode-switch button.active {
  background: var(--accent);
  color: #fff;
}

@media (max-width: 900px) {
  .inventory-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .inventory-player-list {
    max-height: 220px;
  }
}
.inventory-map {
  margin-top: 12px;
}
</style>
