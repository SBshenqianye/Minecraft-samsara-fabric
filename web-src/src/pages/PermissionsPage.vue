<template>
  <TabShell name="permissions">
    <section v-if="!connected" class="panel">
      <h3>连接管理端</h3>
      <p class="muted small">需要管理端 token；token 只保存在当前标签页的 sessionStorage。</p>
      <div class="perm-connect">
        <el-input v-model="tokenInput" type="password" placeholder="Bearer token" show-password />
        <el-button type="primary" :loading="connecting" @click="connect">连接</el-button>
      </div>
      <p v-if="authError" class="container-message error">{{ authError }}</p>
    </section>

    <section v-else class="panel permissions-panel">
      <div class="perm-toolbar">
        <el-input
          id="perm-search"
          v-model="query"
          clearable
          placeholder="搜索权限路径"
          style="max-width: 300px"
        />
        <el-button id="perm-expand-all" size="small" @click="expandAll">全部展开</el-button>
        <el-button id="perm-collapse-all" size="small" @click="collapseAll">全部折叠</el-button>
        <el-button id="perm-refresh" size="small" :loading="loading" @click="reload">重新读取</el-button>
        <span class="spacer"></span>
        <span class="muted small">{{ stats.total }} 路径</span>
      </div>

      <p id="perm-message" class="message" :class="messageKind">{{ message }}</p>
      <p v-if="model.warnings.length" class="message warning">{{ model.warnings.join('；') }}</p>

      <div id="perm-stats" class="stat-row">
        <div v-for="row in statRows" :key="row.label" class="stat">
          <span>{{ row.label }}</span>
          <strong>{{ row.value }}</strong>
        </div>
      </div>

      <div class="perm-layout">
        <div id="perm-tree" class="perm-tree">
          <PermissionNode
            v-for="node in visibleRoots"
            :key="node.path"
            :node="node"
            :depth="0"
            :query="query"
            :collapsed="collapsed"
            :busy="busy"
            @toggle="toggle"
            @save="saveNode"
          />
          <div v-if="!visibleRoots.length" class="nav-empty">没有匹配的权限路径</div>
        </div>

        <aside class="owner-panel">
          <h3>Owner 名单</h3>
          <p class="muted small">
            这些玩家始终拥有 owner 权限；节点修改会即时保存，owner 名单改完点“保存 owner 名单”生效。
          </p>
          <div id="perm-owners" class="owner-list">
            <div v-for="(owner, index) in model.owners" :key="index" class="owner-row">
              <el-input v-model="model.owners[index]" />
              <el-button size="small" @click="removeOwnerAt(index)">移除</el-button>
            </div>
            <div class="owner-row">
              <el-input v-model="ownerInput" placeholder="新增 owner" @keyup.enter="addOwnerName" />
              <el-button size="small" @click="addOwnerName">添加</el-button>
            </div>
          </div>
          <div id="perm-owner-message" class="message small" :class="ownerMessageKind">
            {{ ownerMessage }}
          </div>
          <el-button type="primary" size="small" :loading="savingOwners" @click="saveOwners">
            保存 owner 名单
          </el-button>
        </aside>
      </div>
    </section>
  </TabShell>
</template>

<script setup>
import { computed, reactive, ref } from 'vue';
import TabShell from '../components/TabShell.vue';
import PermissionNode from './permissions/PermissionNode.vue';
import {
  addOwner,
  collapsePaths,
  collectStats,
  emptyModel,
  nodeMatches,
  normalizeModel,
  removeOwner,
} from './permissions/model.js';
import { useAuth } from '../composables/useAuth.js';

defineOptions({ name: 'PermissionsPage' });

const model = reactive(emptyModel());
const query = ref('');
const collapsed = ref(new Set());
const loading = ref(false);
const busy = ref(false);
const savingOwners = ref(false);
const error = ref('');
const message = ref('');
const messageKind = ref('');
const ownerInput = ref('');
const ownerMessage = ref('');
const ownerMessageKind = ref('');

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

const stats = computed(() => collectStats(model.tree));
const statRows = computed(() => [
  { label: '命令路径', value: stats.value.total },
  { label: '关闭权限', value: stats.value.deny },
  { label: '停用节点', value: stats.value.disabled },
  { label: '自定义规则', value: stats.value.custom },
  { label: '叶子', value: stats.value.leaves },
]);
const visibleRoots = computed(() => model.tree.filter((node) => nodeMatches(node, query.value)));

function setMessage(text, kind = '') {
  message.value = text || '';
  messageKind.value = kind;
}

function setOwnerMessage(text, kind = '') {
  ownerMessage.value = text || '';
  ownerMessageKind.value = kind;
}

function applyModel(data) {
  const next = normalizeModel(data);
  model.path = next.path;
  model.tree = next.tree;
  model.owners = next.owners;
  model.warnings = next.warnings;
}

async function load() {
  loading.value = true;
  error.value = '';
  setMessage('');
  try {
    const result = await api('/api/permissions');
    if (!result.ok) throw new Error(result.data?.error || '读取权限树失败');
    applyModel(result.data);
    setMessage('已读取权限树', 'ok');
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

async function reload() {
  loading.value = true;
  setMessage('');
  try {
    const result = await api('/api/permissions/reload', { method: 'POST' });
    const data = result.data || {};
    if (!result.ok || data.ok === false) throw new Error(data.error || '重新读取失败');
    applyModel(data.permissions || data);
    setMessage(data.message || '权限配置已重载', 'ok');
  } catch (e) {
    setMessage(e.message || String(e), 'error');
  } finally {
    loading.value = false;
  }
}

function toggle(path) {
  const next = new Set(collapsed.value);
  if (next.has(path)) next.delete(path);
  else next.add(path);
  collapsed.value = next;
}

function expandAll() {
  collapsed.value = new Set();
}

function collapseAll() {
  collapsed.value = new Set(collapsePaths(model.tree));
}

async function saveNode(change) {
  if (busy.value || !change?.path) return;
  busy.value = true;
  setMessage('');
  try {
    const node = { path: change.path };
    if (change.level !== undefined) node.level = change.level;
    if (change.disabled !== undefined) node.disabled = change.disabled;
    const result = await api('/api/permissions', {
      method: 'PUT',
      body: JSON.stringify({ nodes: [node] }),
    });
    const data = result.data || {};
    if (!result.ok || data.ok === false) throw new Error(data.error || '保存权限失败');
    applyModel(data.permissions || data);
    setMessage(data.message || '权限已保存并热重载', 'ok');
  } catch (e) {
    setMessage(e.message || String(e), 'error');
  } finally {
    busy.value = false;
  }
}

function addOwnerName() {
  const next = addOwner(model.owners, ownerInput.value);
  if (next === model.owners) {
    if (ownerInput.value.trim() && model.owners.includes(ownerInput.value.trim())) {
      setOwnerMessage('该 owner 已存在', 'error');
    }
    return;
  }
  model.owners = next;
  ownerInput.value = '';
  setOwnerMessage('owner 名单有修改，点“保存 owner 名单”后生效', 'ok');
}

function removeOwnerAt(index) {
  model.owners = removeOwner(model.owners, index);
  setOwnerMessage('owner 名单有修改，点“保存 owner 名单”后生效', 'ok');
}

async function saveOwners() {
  savingOwners.value = true;
  setOwnerMessage('');
  try {
    const result = await api('/api/permissions', {
      method: 'PUT',
      body: JSON.stringify({ owners: model.owners }),
    });
    const data = result.data || {};
    if (!result.ok || data.ok === false) throw new Error(data.error || '保存 owner 失败');
    applyModel(data.permissions || data);
    setOwnerMessage(data.message || 'owner 名单已保存并热重载', 'ok');
  } catch (e) {
    setOwnerMessage(e.message || String(e), 'error');
  } finally {
    savingOwners.value = false;
  }
}
</script>

<style scoped>
.perm-connect {
  display: flex;
  gap: 8px;
  align-items: center;
  max-width: 560px;
}
.permissions-panel { display: flex; flex-direction: column; gap: 10px; }
.perm-toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.spacer { flex: 1; }
.perm-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 14px;
  align-items: start;
}
.perm-tree {
  max-height: 74vh;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--panel);
}
.owner-panel {
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--panel-soft);
  padding: 12px;
}
.owner-row { display: flex; gap: 6px; margin-bottom: 6px; }
.owner-list { margin: 8px 0; }
.message.warning { color: var(--amber); }
.message.ok { color: var(--ok); }
.message.error { color: var(--danger); }
@media (max-width: 980px) {
  .perm-layout { grid-template-columns: 1fr; }
}
</style>
