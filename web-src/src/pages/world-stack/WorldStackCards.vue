<template>
  <div id="world-stack-list" class="world-stack-list" data-vue="1">
    <p v-if="!ordered.length" class="muted small">没有栈层数据；服务器启动后会自动创建 world 栈顶。</p>
    <article
      v-for="stack in ordered"
      :key="stack.id"
      class="world-stack-card"
      :class="{ top: stack.top }"
    >
      <div class="world-stack-card-head">
        <div class="world-stack-card-title">
          <strong>{{ stack.top ? 'world' : stack.id }}</strong>
          <span class="world-stack-badges">
            <span class="world-stack-badge">#{{ stack.index + 1 }}</span>
            <span v-if="stack.top" class="world-stack-badge top">栈顶</span>
            <span v-if="stack.loaded" class="world-stack-badge ok">已加载</span>
            <span class="world-stack-badge">{{ sourceName(stack.source) }}</span>
          </span>
        </div>
        <div class="panel-tools">
          <button
            v-if="!stack.top"
            type="button"
            class="ghost danger"
            @click="$emit('archive', stack)"
          >归档…</button>
        </div>
      </div>
      <div class="world-stack-card-body">
        <div class="world-stack-card-main">
          <dl class="world-stack-rows">
            <template v-for="row in rows(stack)" :key="row.key">
              <dt>{{ row.key }}</dt>
              <dd>{{ row.value }}</dd>
            </template>
          </dl>
        </div>
        <WorldStackIcon
          :stack="stack"
          admin
          @upload="$emit('upload', $event)"
          @delete="$emit('delete', $event)"
        />
      </div>
    </article>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import WorldStackIcon from './WorldStackIcon.vue';
import { fmtBytes, fmtTime, sourceName } from './model.js';

const props = defineProps({
  stacks: { type: Array, default: () => [] },
});
defineEmits(['archive', 'upload', 'delete']);

const ordered = computed(() => props.stacks.slice().sort((a, b) => b.index - a.index));

function rows(stack) {
  const dims = (stack.dimensions || [])
    .map((dim) => dim.id + ' ' + (dim.regions || 0))
    .join(' · ');
  const version = stack.versionName
    ? stack.versionName + (stack.snapshot ? ' · 快照' : '')
      + (stack.dataVersion ? ' · DataVersion ' + stack.dataVersion : '')
    : (stack.dataVersion ? 'DataVersion ' + stack.dataVersion : '—');
  return [
    ['世界名', stack.saveName || '—'],
    ['存档创建', fmtTime(stack.saveCreatedAt)],
    ['最后游玩', fmtTime(stack.lastPlayed)],
    ['存档版本', version],
    ['种子', String(stack.seed)],
    ['出生点', stack.spawnX + ', ' + stack.spawnY + ', ' + stack.spawnZ],
    ['出生历史', stack.spawnCycles + ' 轮'],
    ['维度 region', dims || '—'],
    ['数据大小', fmtBytes(stack.dataBytes)],
    ['来源', sourceName(stack.source) + (stack.importedFrom ? ' · ' + stack.importedFrom : '')],
    ['加入栈时间', fmtTime(stack.createdAt)],
    ['导入时间', stack.importedAt ? fmtTime(stack.importedAt) : '—'],
    ['元数据', stack.metaPath],
    ['维度', stack.dimensionsPath],
  ].map(([key, value]) => ({ key, value }));
}
</script>
