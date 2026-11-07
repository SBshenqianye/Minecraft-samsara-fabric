<template>
  <TabShell name="inventory" title="玩家背包" description="在线玩家可读可写；离线玩家只读，写入进入安全队列，登录或停服前应用。">
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

      <div class="inventory-layout">
        <aside class="panel inventory-side">
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
              :class="{ active: selectedPlayer && selectedPlayer.uuid === player.uuid }"
              type="button"
              @click="selectPlayer(player)"
            >
              <span class="inventory-player-name">{{ player.name || player.uuid }}</span>
              <span class="inventory-player-meta">
                {{ player.online ? '当前在线' : '当前不在线' }}
                <template v-if="player.lastSeen"> · {{ player.lastSeen }}</template>
              </span>
            </button>
            <p v-if="!filteredPlayers.length" class="muted small">没有匹配的已知玩家。</p>
          </div>
        </aside>

        <section class="panel inventory-main">
          <div v-if="!data" class="muted small">从左侧选择玩家后读取背包。</div>
          <template v-else>
            <div class="inventory-head">
              <div>
                <h3>{{ data.player.name || data.player.uuid }}</h3>
                <p class="muted small">
                  {{ data.player.uuid }} · {{ scopeLabel }} · revision {{ revision }}
                </p>
              </div>
              <div class="inventory-badges">
                <el-tag :type="data.online ? 'success' : 'info'" size="small">
                  {{ data.online ? '当前在线 · 直接写入' : '离线 · 安全队列写入' }}
                </el-tag>
                <el-tag v-if="data.pendingWrite" type="warning" size="small">待写入队列</el-tag>
                <el-tag v-if="dirty" type="danger" size="small">有未保存修改</el-tag>
              </div>
            </div>

            <div class="inventory-actions">
              <el-button size="small" @click="refresh">重新读取</el-button>
              <el-button size="small" type="primary" :disabled="!dirty || saving" :loading="saving"
                  @click="confirmSave">
                保存{{ dirty ? ' *' : '' }}
              </el-button>
              <span v-if="!data.online" class="muted small">
                离线写入不会直接改运行中的 .dat，而是在玩家下次登录或停服前应用。
              </span>
            </div>

            <div v-if="scope === 'inventory'" class="inventory-sections">
              <div class="inventory-section">
                <div class="inventory-section-title">主背包 0–35</div>
                <ItemSlotGrid
                  :slots="slots.slice(0, 36)"
                  :items="itemsFor(0, 36)"
                  :selected="selectedSlot !== null && selectedSlot < 36 ? selectedSlot : null"
                  @select="selectSlot"
                />
              </div>
              <div class="inventory-section">
                <div class="inventory-section-title">盔甲 36–39</div>
                <ItemSlotGrid
                  :slots="slots.slice(36, 40)"
                  :items="itemsFor(36, 40)"
                  :selected="selectedSlot !== null && selectedSlot >= 36 && selectedSlot < 40 ? selectedSlot - 36 : null"
                  :columns="4"
                  @select="(index) => selectSlot(index + 36)"
                />
              </div>
              <div class="inventory-section">
                <div class="inventory-section-title">副手 40</div>
                <ItemSlotGrid
                  :slots="slots.slice(40, 41)"
                  :items="itemsFor(40, 41)"
                  :selected="selectedSlot === 40 ? 0 : null"
                  :columns="1"
                  @select="(index) => selectSlot(index + 40)"
                />
              </div>
            </div>

            <ItemSlotGrid
              v-else
              :slots="slots"
              :items="data.items || []"
              :selected="selectedSlot"
              :columns="scope === 'armor' ? 4 : 9"
              @select="selectSlot"
            />

            <div class="inventory-editor">
              <template v-if="selectedSlot !== null">
                <template v-if="itemAt(selectedSlot)">
                  <label>
                    槽位 {{ selectedSlot }} · {{ itemAt(selectedSlot).name }}
                    <el-input-number v-model="editCount" :min="1"
                        :max="itemAt(selectedSlot).maxStack || 64" />
                  </label>
                  <el-button type="primary" size="small" :loading="busy" @click="updateCount">
                    更新数量
                  </el-button>
                  <el-button size="small" :loading="busy" @click="removeSlot">移除</el-button>
                  <span class="muted small">{{ itemAt(selectedSlot).id }}</span>
                </template>
                <template v-else>
                  <el-input v-model="searchQuery" placeholder="中文/英文/ID，如 diamond、钻石"
                      style="max-width: 280px" @keyup.enter="searchItems" />
                  <el-button size="small" :loading="searchLoading" @click="searchItems">搜索物品</el-button>
                  <el-input-number v-if="newItem" v-model="newItemCount" :min="1" :max="newItem.maxStack || 99" />
                  <el-button v-if="newItem" type="primary" size="small" :loading="busy" @click="addItem">
                    添加 {{ newItem.zh || newItem.name }}
                  </el-button>
                </template>
              </template>
              <span v-else class="muted small">点击槽位编辑；离线写入会进入安全队列。</span>
            </div>

            <div v-if="searchResults.length && selectedSlot !== null && !itemAt(selectedSlot)"
                class="inventory-search-results">
              <button
                v-for="item in searchResults"
                :key="item.id"
                class="inventory-search-row"
                :class="{ active: newItem && newItem.id === item.id }"
                type="button"
                @click="chooseItem(item)"
              >
                <span><strong>{{ item.zh || item.name }}</strong> <span class="muted small">{{ item.id }}</span></span>
                <span class="muted small">{{ item.value }} VP</span>
              </button>
            </div>

            <details v-if="itemAt(selectedSlot)" class="inventory-detail">
              <summary class="muted small">组件 / NBT 信息</summary>
              <p class="container-detail">{{ itemAt(selectedSlot).components }}</p>
              <p class="container-detail">{{ itemAt(selectedSlot).encoded }}</p>
            </details>
          </template>
        </section>
      </div>
    </template>
  </TabShell>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import TabShell from '../components/TabShell.vue';
import ItemSlotGrid from '../components/ItemSlotGrid.vue';

const token = ref(sessionStorage.getItem('samsara-token') || '');
const tokenInput = ref(token.value);
const connected = ref(false);
const connecting = ref(false);
const saving = ref(false);
const busy = ref(false);
const error = ref('');
const message = ref('');
const query = ref('');
const scope = ref('inventory');
const players = ref([]);
const selectedPlayer = ref(null);
const data = ref(null);
const slots = ref([]);
const revision = ref(0);
const selectedSlot = ref(null);
const editCount = ref(1);
const searchQuery = ref('');
const searchResults = ref([]);
const searchLoading = ref(false);
const newItem = ref(null);
const newItemCount = ref(1);
const dirty = ref(false);

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

onMounted(() => {
  if (token.value) connect();
});

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
    await loadPlayers();
  } catch (e) {
    error.value = e.message || String(e);
    connected.value = false;
  } finally {
    connecting.value = false;
  }
}

async function loadPlayers() {
  try {
    const result = await api('/api/server/players');
    if (result.status !== 200) throw new Error(result.data.error || '读取玩家列表失败');
    players.value = result.data.players || [];
    selectedPlayer.value = players.value.find((player) => player.online)
      || players.value[0]
      || null;
    if (selectedPlayer.value) await reload();
  } catch (e) {
    error.value = e.message || String(e);
  }
}

async function selectPlayer(player) {
  selectedPlayer.value = player;
  await reload();
}

async function reload() {
  if (!selectedPlayer.value) return;
  await loadInventory();
}

async function refresh() {
  await loadInventory();
}

async function loadInventory() {
  if (!selectedPlayer.value) return;
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
    data.value = result.data;
    slots.value = (result.data.slots || []).slice();
    revision.value = result.data.revision;
    selectedSlot.value = null;
    dirty.value = false;
    searchResults.value = [];
    newItem.value = null;
    if (result.data.pendingWrite) {
      message.value = '该玩家有待写入队列；当前显示队列中的内容，登录或停服前应用。';
    }
  } catch (e) {
    data.value = null;
    error.value = e.message || String(e);
  }
}

function itemAt(index) {
  if (!data.value) return null;
  return (data.value.items || []).find((item) => item.slot === index) || null;
}

function itemsFor(start, end) {
  return (data.value?.items || [])
    .filter((item) => item.slot >= start && item.slot < end)
    .map((item) => Object.assign({}, item, { slot: item.slot - start }));
}

function selectSlot(index) {
  selectedSlot.value = index;
  const item = itemAt(index);
  editCount.value = item ? item.count : 1;
  searchQuery.value = '';
  searchResults.value = [];
  newItem.value = null;
  newItemCount.value = 1;
}

function chooseItem(item) {
  newItem.value = item;
  newItemCount.value = 1;
}

async function searchItems() {
  const text = searchQuery.value.trim();
  if (!text) {
    searchResults.value = [];
    return;
  }
  searchLoading.value = true;
  error.value = '';
  try {
    const result = await api('/api/lottery/values?q=' + encodeURIComponent(text) + '&limit=20');
    if (result.status !== 200) throw new Error(result.data.error || '物品搜索失败');
    searchResults.value = (result.data.values || []).filter((item) => item.acceptable !== false);
    if (!searchResults.value.length) {
      message.value = '没有匹配的物品。';
    }
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    searchLoading.value = false;
  }
}

async function updateCount() {
  const index = selectedSlot.value;
  const item = itemAt(index);
  if (!item) return;
  const count = Number(editCount.value);
  if (!Number.isFinite(count) || count < 1) {
    error.value = '数量至少为 1';
    return;
  }
  busy.value = true;
  try {
    const result = await api('/api/inventory/item', {
      method: 'POST',
      body: JSON.stringify({ encoded: slots.value[index], count }),
    });
    if (result.status !== 200 || !result.data.ok) {
      throw new Error(result.data.error || '更新数量失败');
    }
    slots.value[index] = result.data.encoded;
    data.value.items = data.value.items.map((row) => row.slot === index
      ? Object.assign({}, row, {
        count: result.data.count,
        encoded: result.data.encoded,
        components: result.data.components,
      })
      : row);
    dirty.value = true;
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    busy.value = false;
  }
}

function removeSlot() {
  const index = selectedSlot.value;
  if (index === null) return;
  slots.value[index] = '';
  data.value.items = data.value.items.filter((row) => row.slot !== index);
  dirty.value = true;
}

async function addItem() {
  if (!newItem.value) return;
  let index = selectedSlot.value;
  if (index === null || slots.value[index]) {
    index = slots.value.findIndex((slot) => !slot);
  }
  if (index < 0) {
    error.value = scope.value === 'ender' ? '末影箱已满' : '背包已满，没有空槽位';
    return;
  }
  const count = Number(newItemCount.value);
  if (!Number.isFinite(count) || count < 1) {
    error.value = '数量至少为 1';
    return;
  }
  busy.value = true;
  try {
    const result = await api('/api/inventory/item', {
      method: 'POST',
      body: JSON.stringify({ id: newItem.value.id, count }),
    });
    if (result.status !== 200 || !result.data.ok) {
      throw new Error(result.data.error || '添加物品失败');
    }
    slots.value[index] = result.data.encoded;
    data.value.items = data.value.items
      .filter((row) => row.slot !== index)
      .concat([{
        slot: index,
        id: result.data.id,
        icon: (result.data.id || '').split(':').pop(),
        name: result.data.name,
        count: result.data.count,
        maxStack: 64,
        encoded: result.data.encoded,
        components: result.data.components,
      }]);
    selectedSlot.value = index;
    newItem.value = null;
    searchResults.value = [];
    dirty.value = true;
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    busy.value = false;
  }
}

async function confirmSave() {
  if (!data.value) return;
  const label = data.value.online ? '直接写入在线玩家' : '写入离线安全队列';
  try {
    await ElMessageBox.confirm(
      `将${label}：${data.value.player.name || data.value.player.uuid} · ${scopeLabel.value}；` +
      `当前 revision ${revision.value}。写入前会保存 Web 审计快照。`,
      '确认写入玩家背包',
      { confirmButtonText: '确认写入', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  await save();
}

async function save() {
  saving.value = true;
  error.value = '';
  try {
    const result = await api('/api/inventory/write', {
      method: 'POST',
      body: JSON.stringify({
        player: data.value.player.uuid,
        scope: scope.value,
        revision: revision.value,
        slots: slots.value,
      }),
    });
    if (result.status === 409) {
      await loadInventory();
      throw new Error(result.data.error
        ? result.data.error + '（当前 revision ' + revision.value + '，已重新读取）'
        : 'revision 冲突，已重新读取');
    }
    if (result.status !== 200 || !result.data.ok) {
      throw new Error(result.data.error || '写入失败');
    }
    data.value = Object.assign({}, data.value, result.data);
    slots.value = (result.data.slots || slots.value).slice();
    revision.value = result.data.revision;
    dirty.value = false;
    selectedSlot.value = null;
    if (result.data.queued) {
      message.value = '玩家不在线，已写入安全队列；下次登录或停服前应用。';
      ElMessage.success('已写入离线安全队列');
    } else {
      message.value = '已写入在线玩家背包与盔甲/副手。';
      ElMessage.success('已写入在线玩家背包');
    }
    await loadInventory();
  } catch (e) {
    error.value = e.message || String(e);
    ElMessage.error(error.value);
  } finally {
    saving.value = false;
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
  max-height: 460px;
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

.inventory-player-name {
  font-weight: 600;
}

.inventory-player-meta {
  color: var(--muted);
  font-size: 11px;
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

.inventory-sections {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.inventory-section-title {
  margin-bottom: 6px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
}

.inventory-editor {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.inventory-search-results {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 240px;
  overflow: auto;
  margin-top: 10px;
}

.inventory-search-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 9px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.inventory-search-row.active {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.inventory-detail {
  margin-top: 12px;
}

@media (max-width: 900px) {
  .inventory-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .inventory-player-list {
    max-height: 220px;
  }
}
</style>
