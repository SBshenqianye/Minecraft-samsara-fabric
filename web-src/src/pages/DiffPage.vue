<template>
  <TabShell name="diff" title="Diff 回看" description="只读回看区块差异；复用现有 Diff API，不重写回放算法。">
    <section v-if="!connected" class="panel">
      <h3>连接管理端</h3>
      <p class="muted small">需要管理端 token；token 只保存在当前标签页的 sessionStorage。</p>
      <div class="diff-connect">
        <el-input v-model="tokenInput" type="password" placeholder="Bearer token" show-password />
        <el-button type="primary" :loading="connecting" @click="connect">连接</el-button>
      </div>
      <p v-if="error" class="container-message error">{{ error }}</p>
    </section>

    <template v-else>
      <p v-if="error" class="container-message error">{{ error }}</p>
      <p v-if="message" class="container-message ok">{{ message }}</p>

      <section class="panel">
        <h3>区块差异</h3>
        <p class="muted small">
          选择栈层/维度/区块后先读取摘要；执行回看需要一名在线玩家作为观看者。
          也可以到 <router-link to="/samsara/world/map">世界地图</router-link> 点选区块。
        </p>
        <div class="diff-grid">
          <label>
            栈层
            <el-select v-model="stack" style="width: 100%">
              <el-option v-for="item in stacks" :key="item.id" :label="item.label" :value="item.id" />
            </el-select>
          </label>
          <label>
            维度
            <el-select v-model="dimension" style="width: 100%">
              <el-option v-for="item in dimensions" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </label>
          <label>
            区块 X
            <el-input-number v-model="cx" :min="-1875000" :max="1875000" style="width: 100%" />
          </label>
          <label>
            区块 Z
            <el-input-number v-model="cz" :min="-1875000" :max="1875000" style="width: 100%" />
          </label>
          <label>
            起（可选）
            <el-input v-model="from" placeholder="2026-11-07T12:00 或 -30m" />
          </label>
          <label>
            止（可选）
            <el-input v-model="to" placeholder="2026-11-07T14:00" />
          </label>
        </div>
        <div class="diff-actions">
          <el-button size="small" :loading="summaryLoading" @click="loadSummary">读取摘要</el-button>
          <el-button size="small" @click="loadStatus" :loading="statusLoading">刷新回看状态</el-button>
        </div>

        <div v-if="summary" class="diff-summary">
          <h4>摘要</h4>
          <div class="diff-summary-grid">
            <span>方块改动</span><strong>{{ summary.changedBlocks ?? summary.editCount ?? '—' }}</strong>
            <span>候选方块</span><strong>{{ summary.candidateBlocks ?? '—' }}</strong>
            <span>最早</span><strong>{{ formatTime(summary.earliest) }}</strong>
            <span>最晚</span><strong>{{ formatTime(summary.latest) }}</strong>
            <span>保护区</span><strong>{{ summary.protected ? '是' : '否' }}</strong>
            <span>倒流运行中</span><strong>{{ summary.rewindRunning ? '是' : '否' }}</strong>
          </div>
          <p v-if="summary.error" class="container-message error">{{ summary.error }}</p>
        </div>
      </section>

      <section class="panel">
        <h3>启动只读回看</h3>
        <div class="diff-grid">
          <label>
            观看者（在线玩家）
            <el-input v-model="viewer" placeholder="玩家名" />
          </label>
          <label>
            倍速
            <el-input-number v-model="speed" :min="0.1" :max="64" :step="0.5" style="width: 100%" />
          </label>
        </div>
        <div class="diff-actions">
          <el-button size="small" type="primary" :loading="starting"
              :disabled="!viewer.trim()" @click="startView">启动单区块回看</el-button>
          <el-button size="small" :loading="starting" :disabled="!viewer.trim()" @click="stopView">
            停止回看
          </el-button>
        </div>
        <p class="muted small">只读：零世界写入、不删区块、不写 diff；停止后自动恢复观看者。</p>
      </section>

      <section class="panel">
        <h3>回看状态</h3>
        <pre class="diff-status">{{ statusText }}</pre>
      </section>
    </template>
  </TabShell>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import TabShell from '../components/TabShell.vue';
import { useAuth } from '../composables/useAuth.js';

const {
  tokenInput,
  connected,
  connecting,
  error,
  connect,
  api,
} = useAuth({
  autoConnect: true,
  onConnected: async () => {
    await loadStacks();
    await loadStatus();
  },
});
const message = ref('');
const stacks = ref([]);
const stack = ref('');
const dimension = ref('minecraft:overworld');
const cx = ref(0);
const cz = ref(0);
const from = ref('');
const to = ref('');
const viewer = ref('');
const speed = ref(4);
const summary = ref(null);
const summaryLoading = ref(false);
const statusLoading = ref(false);
const starting = ref(false);
const viewStatus = ref(null);

const dimensions = computed(() => {
  const current = stacks.value.find((item) => item.id === stack.value);
  const list = current?.dimensions || [];
  if (!list.length) return [{ id: 'minecraft:overworld', name: '主世界' }];
  return list.map((item) => ({ id: item.id, name: item.name || item.id }));
});

const statusText = computed(() => {
  if (!viewStatus.value) return '尚未读取。';
  return JSON.stringify(viewStatus.value, null, 2);
});

watch(stack, () => {
  const list = dimensions.value;
  if (!list.some((item) => item.id === dimension.value)) {
    dimension.value = list[0]?.id || 'minecraft:overworld';
  }
});

async function loadStacks() {
  const result = await api('/api/map');
  if (result.status !== 200) throw new Error(result.data.error || '读取世界栈失败');
  stacks.value = (result.data.stacks || []).map((item) => Object.assign({}, item, {
    label: item.top ? (item.label || item.id) + '（栈顶）' : (item.label || item.id),
  }));
  if (!stack.value && stacks.value.length) stack.value = stacks.value[0].id;
  const list = dimensions.value;
  if (!list.some((item) => item.id === dimension.value)) {
    dimension.value = list[0]?.id || 'minecraft:overworld';
  }
}

async function loadSummary() {
  summaryLoading.value = true;
  error.value = '';
  try {
    const params = new URLSearchParams({
      stack: stack.value,
      dim: dimension.value,
      cx: String(cx.value),
      cz: String(cz.value),
      load: '1',
    });
    if (to.value.trim()) params.set('to', to.value.trim());
    const result = await api('/api/diff/chunk?' + params.toString());
    if (result.status !== 200) throw new Error(result.data.error || '读取摘要失败');
    summary.value = result.data;
  } catch (e) {
    summary.value = null;
    error.value = e.message || String(e);
  } finally {
    summaryLoading.value = false;
  }
}

async function loadStatus() {
  statusLoading.value = true;
  try {
    const result = await api('/api/diff/view/status');
    if (result.status !== 200) throw new Error(result.data.error || '读取回看状态失败');
    viewStatus.value = result.data;
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    statusLoading.value = false;
  }
}

async function startView() {
  starting.value = true;
  error.value = '';
  message.value = '';
  try {
    const result = await api('/api/diff/view', {
      method: 'POST',
      body: JSON.stringify({
        player: viewer.value.trim(),
        stack: stack.value,
        dim: dimension.value,
        cx: cx.value,
        cz: cz.value,
        from: from.value.trim(),
        to: to.value.trim(),
        speed: speed.value,
      }),
    });
    if (result.status !== 200 || result.data.ok === false) {
      throw new Error(result.data.error || '启动回看失败');
    }
    message.value = result.data.message || '已启动只读回看';
    ElMessage.success('已启动只读回看');
    await loadStatus();
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    starting.value = false;
  }
}

async function stopView() {
  starting.value = true;
  error.value = '';
  try {
    const result = await api('/api/diff/view', {
      method: 'POST',
      body: JSON.stringify({ action: 'stop', player: viewer.value.trim() }),
    });
    if (result.status !== 200 || result.data.ok === false) {
      throw new Error(result.data.error || '停止回看失败');
    }
    message.value = result.data.message || '已停止回看';
    ElMessage.success('已停止回看');
    await loadStatus();
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    starting.value = false;
  }
}

function formatTime(value) {
  if (!value) return '—';
  const date = new Date(Number(value));
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString();
}
</script>

<style scoped>
.diff-connect {
  display: flex;
  gap: 10px;
  max-width: 520px;
  margin-top: 10px;
}

.diff-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
  margin: 12px 0;
}

.diff-grid label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: var(--muted);
  font-size: 12px;
}

.diff-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.diff-summary {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.diff-summary h4 {
  margin: 0 0 8px;
}

.diff-summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 6px 14px;
  font-size: 13px;
}

.diff-summary-grid span {
  color: var(--muted);
}

.diff-status {
  max-height: 320px;
  overflow: auto;
  margin: 0;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-soft);
  font-size: 11px;
  white-space: pre-wrap;
}
</style>
