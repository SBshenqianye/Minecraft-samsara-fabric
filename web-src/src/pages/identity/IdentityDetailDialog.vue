<template>
  <div v-if="loading" class="muted">加载中…</div>
  <template v-else-if="error">
    <p class="message error">{{ error }}</p>
  </template>
  <template v-else-if="detail">
    <div class="identity-data-head">
      <code class="identity-data-uuid">{{ uuid }}</code>
      <button class="ghost" type="button" @click="copyUuid">复制 UUID</button>
      <button class="ghost" type="button" @click="identity.editLabel(uuid)">命名/备注</button>
    </div>
    <p class="muted small">
      名称：{{ labelName || '未命名' }} · 备注：{{ labelNote || '—' }}
    </p>

    <section class="identity-data-section identity-backup-section" :data-backup-for="uuid">
      <h4>备份记录</h4>
      <div class="identity-backup-list">
        <p v-if="backups.loading" class="muted small">加载中…</p>
        <template v-else-if="backups.rows.length">
          <p class="muted small">备份根目录：{{ backups.root || '—' }}</p>
          <div v-for="row in backups.rows" :key="row.id" class="identity-backup-row">
            <code>{{ row.id }}</code>
            <span class="muted small">{{ sizeText(row) }}</span>
            <button class="chip-action" type="button" @click="copyPath(row)">复制路径</button>
            <span class="muted small">{{ row.path }}</span>
          </div>
        </template>
        <p v-else class="muted small">
          还没有备份。点「备份数据」会在 {{ backups.root || ('identity/backups/data/' + uuid) }} 下生成一份。
        </p>
      </div>
    </section>

    <div class="status-chips">
      <div v-for="[label, value] in chips" :key="label" class="status-chip">
        <span>{{ label }}</span>
        <strong>{{ value }}</strong>
      </div>
    </div>

    <div class="identity-data-section identity-data-inventory">
      <iframe
        class="identity-inline-frame"
        title="身份背包编辑"
        :src="frameSrc"
      ></iframe>
    </div>

    <div class="identity-data-section identity-data-stats-section">
      <h4>统计</h4>
      <div class="identity-stat-tabs">
        <button
          v-for="tab in STAT_TABS"
          :key="tab.id"
          type="button"
          class="identity-stat-tab"
          :class="{ active: statTab === tab.id }"
          :data-tab="tab.id"
          @click="statTab = tab.id"
        >{{ tab.label }}</button>
      </div>
      <div class="identity-stat-body">
        <template v-if="statView.kind === 'rows'">
          <p v-if="!statView.rows.length" class="muted small">没有统计数据。</p>
          <div v-else class="identity-data-stats">
            <div v-for="row in statView.rows" :key="row.key" class="identity-data-stat">
              <span>{{ row.label }}</span>
              <strong>{{ row.value }}</strong>
            </div>
          </div>
        </template>
        <template v-else>
          <p v-if="!statView.sections.length" class="muted small">没有该分类的统计数据。</p>
          <div v-for="section in statView.sections" :key="section.key" class="identity-stat-list">
            <div class="identity-stat-list-title">{{ section.label }}</div>
            <div v-for="row in section.items" :key="row.id" class="identity-data-stat identity-stat-list-row">
              <ItemIcon :item="{ id: row.id, icon: row.iconId, name: row.name, zh: row.name, count: Number(row.count) }" size="sm" />
              <span class="identity-stat-name">{{ row.name }}</span>
              <strong>{{ row.count }}</strong>
            </div>
          </div>
        </template>
      </div>
    </div>

    <div class="identity-data-section identity-data-wide">
      <h4>成就 · {{ advDone }} / {{ advTotal }}{{ recipeNote }}</h4>
      <template v-if="advNodes.length">
        <div class="identity-adv-groups">
          <div class="identity-stat-tabs identity-adv-tabs">
            <button
              v-for="tab in ADV_TABS"
              :key="tab.id"
              type="button"
              class="identity-stat-tab"
              :class="{ active: advTab === tab.id }"
              :data-tab="tab.id"
              :disabled="!(advGroups[tab.id] || []).length"
              @click="advTab = tab.id"
            >{{ tab.label }}</button>
          </div>
          <div class="identity-adv-host">
            <div class="identity-adv-tree">
              <div class="identity-adv-bar">
                <span class="muted small">拖动平移 · 滚轮缩放 · 点击节点查看说明</span>
                <span class="identity-adv-spacer"></span>
                <button class="ghost tiny identity-adv-reset" type="button" title="重置视图" aria-label="重置视图" @click="resetView">↺</button>
              </div>
              <div
                ref="advViewport"
                class="identity-adv-viewport"
                :class="{ dragging: dragging }"
                @pointerdown="startDrag"
                @pointermove="moveDrag"
                @pointerup="stopDrag"
                @pointercancel="stopDrag"
                @wheel.prevent="onWheel"
              >
                <div class="identity-adv-canvas" :style="canvasStyle">
                  <svg class="identity-adv-lines" :width="layout.width" :height="layout.height">
                    <line
                      v-for="edge in layout.edges"
                      :key="edge.id"
                      :x1="edge.x1" :y1="edge.y1" :x2="edge.x2" :y2="edge.y2"
                      :class="edge.done ? 'done' : 'todo'"
                    />
                  </svg>
                  <button
                    v-for="point in layout.points"
                    :key="point.id"
                    type="button"
                    class="identity-adv-node"
                    :class="[point.done ? 'done' : 'todo', 'frame-' + (point.row.frame || 'task')]"
                    :style="{ left: (point.x - 22) + 'px', top: (point.y - 22) + 'px' }"
                    :title="(point.row.title || point.row.id) + ' · ' + (point.done ? '已完成' : '未完成')"
                    @pointerdown.stop
                    @click="selectedNode = point.row"
                  >
                    <img :src="'/items/' + (point.row.icon || 'stone') + '.png'" alt="" loading="lazy" />
                    <span class="identity-adv-label">{{ point.row.title || point.row.id }}</span>
                  </button>
                </div>
              </div>
              <div class="identity-adv-footer muted small">{{ advFooter }}</div>
            </div>
          </div>
        </div>
      </template>
      <template v-else-if="advIds.length">
        <div class="identity-data-tags">
          <span v-for="id in advIds" :key="id" class="identity-data-tag">{{ id }}</span>
        </div>
      </template>
      <p v-else class="muted small">没有已完成的成就记录。</p>
    </div>
  </template>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue';
import ItemIcon from '../../components/ItemIcon.vue';
import {
  ADV_TABS,
  STAT_TABS,
  advancementGroups,
  advancementLayout,
  statsForTab,
} from './model.js';

const props = defineProps({
  identity: { type: Object, required: true },
  uuid: { type: String, required: true },
});

const loading = ref(true);
const error = ref('');
const detail = ref(null);
const backups = reactive({ loading: true, root: '', rows: [] });
const statTab = ref('custom');
const advTab = ref('adventure');
const selectedNode = ref(null);
const advViewport = ref(null);
const dragging = ref(false);
const tx = ref(0);
const ty = ref(0);
const scale = ref(1);

const frameSrc = computed(() => '/identity-inventory-inline?'
  + new URLSearchParams({ data: props.uuid, scope: 'inventory' }).toString());
const labelName = computed(() => {
  const meta = props.identity.labels[props.uuid];
  if (meta?.name) return meta.name;
  const row = props.identity.dataList.value.find((item) => item.uuid === props.uuid);
  return row?.label || '';
});
const labelNote = computed(() => props.identity.labels[props.uuid]?.note || '');

const chips = computed(() => {
  const data = detail.value || {};
  return [
    ['数据文件', data.exists ? '存在' : '不存在'],
    ['大小', data.bytes ? (Number(data.bytes) / 1024).toFixed(1) + ' KiB' : '—'],
    ['生命', data.health == null ? '—' : String(data.health)],
    ['饥饿', data.food == null ? '—' : String(data.food)],
    ['经验', data.xpLevel == null ? '—' : data.xpLevel + ' 级 / ' + (data.xpTotal || 0)],
    ['维度', data.dimension || '—'],
    ['坐标', Array.isArray(data.pos) && data.pos.length >= 3
      ? data.pos.slice(0, 3).map((value) => Number(value).toFixed(1)).join(', ') : '—'],
  ];
});

const statView = computed(() => statsForTab(detail.value?.stats || {}, statTab.value));

const advNodes = computed(() => (Array.isArray(detail.value?.advancements?.nodes)
  ? detail.value.advancements.nodes : []));
const advIds = computed(() => (Array.isArray(detail.value?.advancements?.ids)
  ? detail.value.advancements.ids : []));
const advGroups = computed(() => advancementGroups(advNodes.value));
const activeAdvNodes = computed(() => advGroups.value[advTab.value] || []);
const layout = computed(() => advancementLayout(activeAdvNodes.value));
const advDone = computed(() => advNodes.value.filter((row) => row.done).length);
const advTotal = computed(() => (advNodes.value.length || detail.value?.advancements?.total || 0));
const recipeNote = computed(() => {
  const done = Number(detail.value?.advancements?.done || 0);
  if (!advNodes.value.length || done <= advDone.value) return '';
  return ' · 另有 ' + (done - advDone.value) + ' 个配方解锁';
});
const advFooter = computed(() => {
  if (selectedNode.value) {
    return (selectedNode.value.title || selectedNode.value.id) + ' · '
      + (selectedNode.value.done ? '已完成' : '未完成') + ' · '
      + (selectedNode.value.description || '');
  }
  return '共 ' + layout.value.total + ' 个成就节点，已获得 ' + layout.value.doneCount + ' 个。';
});
const canvasStyle = computed(() => ({
  width: layout.value.width + 'px',
  height: layout.value.height + 'px',
  transform: 'translate(' + tx.value + 'px, ' + ty.value + 'px) scale(' + scale.value + ')',
}));

function sizeText(row) {
  return ((Number(row.bytes) || 0) / 1024).toFixed(1) + ' KiB · '
    + ((row.files || []).length) + ' 个文件';
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    detail.value = await props.identity.fetchDetail(props.uuid);
  } catch (e) {
    error.value = e.message || '读取详情失败';
  } finally {
    loading.value = false;
  }
  backups.loading = true;
  try {
    const data = await props.identity.fetchBackups(props.uuid);
    backups.root = data.root;
    backups.rows = data.rows;
  } catch {
    backups.root = '';
    backups.rows = [];
  } finally {
    backups.loading = false;
  }
}

function copyUuid() {
  props.identity.copyUuid(props.uuid);
}

function copyPath(row) {
  props.identity.copyText(row.path);
  props.identity.setMessage('已复制备份路径', 'ok');
}

function resetView() {
  tx.value = 0;
  ty.value = 0;
  scale.value = 1;
}

let dragStart = null;

function startDrag(event) {
  if (event.button !== 0) return;
  event.preventDefault();
  dragging.value = true;
  dragStart = { x: event.clientX, y: event.clientY, tx: tx.value, ty: ty.value };
  try { event.currentTarget.setPointerCapture?.(event.pointerId); } catch { /* ignore */ }
}

function moveDrag(event) {
  if (!dragging.value || !dragStart) return;
  tx.value = dragStart.tx + event.clientX - dragStart.x;
  ty.value = dragStart.ty + event.clientY - dragStart.y;
}

function stopDrag() {
  dragging.value = false;
  dragStart = null;
}

function onWheel(event) {
  const next = Math.max(0.45, Math.min(2, scale.value * (event.deltaY < 0 ? 1.1 : 0.9)));
  scale.value = next;
}

watch(advTab, () => {
  selectedNode.value = null;
  resetView();
});
onMounted(load);
</script>
