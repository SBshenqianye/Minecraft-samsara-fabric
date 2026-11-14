<template>
  <div class="pool-panel" id="identity-pool-panel">
    <div class="panel-head compact">
      <div>
        <h3>未进组 / 数据 UUID 库</h3>
        <p class="panel-sub">账号和 data 分开显示：正版账号、离线账号、data 各自独立。把账号拖进卡片的“正版账号/离线账号”区域，把 data UUID 拖进“数据”区域。正版账号/离线账号只能在一个组；data 可以复制到多个组。</p>
      </div>
      <div class="panel-tools">
        <label class="inline-field">
          <span>名称 / UUID</span>
          <input
            id="identity-resolve-input"
            v-model="resolveInput"
            type="text"
            placeholder="输入玩家名或 UUID"
            autocomplete="off"
            @keydown.enter.prevent="resolve"
          />
        </label>
        <button id="identity-resolve-button" class="ghost" type="button" @click="resolve">解析</button>
        <button id="identity-generate" class="ghost" type="button" @click="identity.generate(true)">生成 data UUID</button>
      </div>
    </div>
    <div v-if="resolveResult" id="identity-resolve-result" class="resolve-result">
      <span
        v-for="row in resolveRows"
        :key="row.uuid"
        class="uuid-chip"
      >
        <span class="uuid-name">{{ row.label + ' · ' + (row.name || shortUuid(row.uuid)) }}</span>
        <span class="uuid-code">{{ shortUuid(row.uuid) }}</span>
      </span>
      <span v-if="resolveResult.premiumNote" class="note">{{ resolveResult.premiumNote }}</span>
    </div>
    <div class="pool-filter-bar">
      <label class="inline-field">
        <span>筛选</span>
        <input id="identity-pool-search" v-model="filter.text" type="search" placeholder="名称 / UUID" autocomplete="off" />
      </label>
      <label class="check-line">
        <input id="identity-hide-probe" v-model="filter.hideProbe" type="checkbox" />
        <span>隐藏疑似 probe / 测试 ID</span>
      </label>
      <label class="check-line">
        <input id="identity-hide-unknown" v-model="filter.hideUnknown" type="checkbox" />
        <span>隐藏占位名（玩家-xxxxxxxx）</span>
      </label>
      <span id="identity-pool-count" class="muted small">{{ pool.poolText }}</span>
    </div>
    <div v-if="pool.suggestions.length" id="identity-suggestions" class="identity-suggestions">
      <p class="muted small">ID 相似度推荐（只是建议，不会自动保存）：同一名字的正版账号/离线账号入口可以合成一个组；同类型多个账号只能选一个入口。</p>
      <div v-for="(cluster, index) in pool.suggestions" :key="index" class="identity-suggestion">
        <div class="grow">
          <strong>{{ cluster.title }}</strong>
          <div class="muted small">{{ cluster.detail }}</div>
        </div>
        <button class="ghost" type="button" @click="identity.createDraftGroup(cluster)">
          {{ cluster.premium && cluster.offline ? '建组（正版账号 + 离线账号）' : '建组（取第一个）' }}
        </button>
      </div>
    </div>
    <div v-else id="identity-suggestions" class="identity-suggestions" hidden></div>

    <details class="pool-section" id="identity-premium-section" open>
      <summary>正版账号 UUID（未进组） <span id="identity-premium-count" class="muted small">{{ pool.counts.premium }}</span></summary>
      <div id="identity-premium-pool" class="chip-pool">
        <IdentityChip
          v-for="item in pool.premium"
          :key="item.uuid"
          :uuid="item.uuid"
          role="premium"
          :name="item.name"
          :display-name="item.name || shortUuid(item.uuid)"
          :tag="item.tag"
          :badge="item.badge"
          :online-label="item.online"
          :actions="poolActions"
          @dragstart="(payload) => identity.startDrag({ ...payload, type: item.type })"
          @dragend="identity.endDrag"
          @action="(action) => onPoolAction(action, item, 'premium')"
        />
        <span v-if="!pool.premium.length" class="pool-empty">没有未进组的正版账号 UUID。</span>
      </div>
    </details>
    <details class="pool-section" id="identity-offline-section" open>
      <summary>离线账号 UUID（未进组） <span id="identity-offline-count" class="muted small">{{ pool.counts.offline }}</span></summary>
      <div id="identity-offline-pool" class="chip-pool">
        <IdentityChip
          v-for="item in pool.offline"
          :key="item.uuid"
          :uuid="item.uuid"
          role="offline"
          :name="item.name"
          :display-name="item.name || shortUuid(item.uuid)"
          :tag="item.tag"
          :badge="item.badge"
          :online-label="item.online"
          :actions="poolActions"
          @dragstart="(payload) => identity.startDrag({ ...payload, type: item.type })"
          @dragend="identity.endDrag"
          @action="(action) => onPoolAction(action, item, 'offline')"
        />
        <span v-if="!pool.offline.length" class="pool-empty">没有未进组的离线账号 UUID。</span>
      </div>
    </details>
    <details class="pool-section" id="identity-data-section">
      <summary>数据 UUID（data） <span id="identity-data-count" class="muted small">{{ pool.counts.data }}</span></summary>
      <div id="identity-data-pool" class="chip-pool">
        <IdentityChip
          v-for="item in pool.data"
          :key="item.uuid"
          :uuid="item.uuid"
          role="data"
          :name="item.name"
          :display-name="item.name || '空白 data'"
          :tag="item.tag"
          :badge="item.badge"
          :online-label="item.online"
          :locked="Boolean(item.label)"
          :actions="dataActions(item)"
          @dragstart="(payload) => identity.startDrag({ ...payload, type: item.type || 'data' })"
          @dragend="identity.endDrag"
          @action="(action) => onDataAction(action, item)"
        />
        <span v-if="!pool.data.length" class="pool-empty">没有闲置 data UUID；点“生成 data UUID”创建一把空白钥匙。</span>
      </div>
    </details>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import IdentityChip from './IdentityChip.vue';
import { shortUuid } from './model.js';

const props = defineProps({
  identity: { type: Object, required: true },
});

const filter = props.identity.poolFilter;
const pool = computed(() => props.identity.pool.value);
const resolveInput = ref('');
const resolveResult = ref(null);

const poolActions = [
  { key: 'assign', label: '→组', title: '放入身份组…（不需要拖动）' },
  { key: 'copy', label: '复制' },
];

const resolveRows = computed(() => {
  const result = resolveResult.value;
  if (!result) return [];
  const rows = [];
  if (result.premium) rows.push({ uuid: result.premium, label: '正版账号', name: result.name || '' });
  if (result.offline) rows.push({ uuid: result.offline, label: '离线账号', name: result.name || '' });
  for (const [uuid, name] of Object.entries(result.names || {})) {
    if (uuid === result.premium || uuid === result.offline) continue;
    rows.push({ uuid, label: '账号', name });
  }
  return rows;
});

function dataActions(item) {
  const actions = [
    { key: 'assign', label: '→组', title: '放入身份组…（不需要拖动）' },
    { key: 'copy', label: '复制' },
  ];
  if (item.canLabel) actions.push({ key: 'label', label: '命名', title: '给这个 data UUID 起名/备注' });
  if (item.canBackups) actions.push({ key: 'backups', label: '备份记录', title: '查看备份位置与时间' });
  if (item.canRemove) actions.push({ key: 'remove', label: '×', title: '从池子移除这个空白 data' });
  return actions;
}

function onPoolAction(action, item, role) {
  if (action === 'assign') props.identity.openAssign(item.uuid, item.name, role, item.type);
  if (action === 'copy') props.identity.copyUuid(item.uuid);
}

function onDataAction(action, item) {
  if (action === 'assign') props.identity.openAssign(item.uuid, item.name, 'data', item.type || 'data');
  if (action === 'copy') props.identity.copyUuid(item.uuid);
  if (action === 'label') props.identity.editLabel(item.uuid);
  if (action === 'backups') props.identity.openDetail(item.uuid);
  if (action === 'remove') {
    props.identity.setMessage('已从池子移除空白 data：' + item.uuid, 'ok');
  }
}

async function resolve() {
  const value = resolveInput.value.trim();
  if (!value) {
    props.identity.setMessage('请输入玩家名或 UUID。', 'error');
    return;
  }
  resolveResult.value = await props.identity.resolveToolbarValue(value);
}
</script>
