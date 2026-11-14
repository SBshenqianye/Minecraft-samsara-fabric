<template>
  <div class="perm-node" :id="pathId(node.path)">
    <div
      class="perm-row"
      :class="{ disabled: node.disabledBy }"
      :style="{ paddingLeft: (8 + depth * 20) + 'px' }"
    >
      <button
        type="button"
        class="perm-expand ghost"
        :disabled="!children.length || !hasVisibleChildren"
        :title="expandTitle"
        @click="$emit('toggle', node.path)"
      >{{ expandText }}</button>

      <span class="perm-label" :title="node.path">{{ label }}</span>

      <span v-if="children.length" class="perm-badge">{{ children.length }} 子项</span>

      <PermLevelControl
        class="perm-levels"
        :model-value="node.level || 'inherit'"
        :options="LEVELS"
        :disabled="blocked || busy"
        :title="levelTitle"
        @update:model-value="onLevel"
      />

      <button
        type="button"
        class="perm-secondary"
        :class="{ active: node.disabled }"
        :disabled="blocked || busy"
        :title="disabledTitle"
        @click="$emit('save', { path: node.path, disabled: !node.disabled })"
      >{{ node.disabled ? '已停用' : '停用节点' }}</button>

      <span
        v-if="node.recovery"
        class="perm-badge warning"
        title="关闭后仍保留控制台恢复入口（避免 Web/配置被锁死）"
      >!</span>

      <span class="perm-badge">实际：{{ node.effectiveLevel }}</span>
      <span v-if="node.source === 'custom'" class="perm-badge custom">自定义</span>
      <span v-if="node.disabledBy" class="perm-badge danger-text">
        被 {{ node.disabledBy }} 停用
      </span>
    </div>

    <template v-if="children.length && (!isCollapsed || query)">
      <PermissionNode
        v-for="child in visibleChildren"
        :key="child.path"
        :node="child"
        :depth="depth + 1"
        :query="query"
        :collapsed="collapsed"
        :busy="busy"
        @toggle="$emit('toggle', $event)"
        @save="$emit('save', $event)"
      />
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import PermLevelControl from './PermLevelControl.vue';
import { LEVELS, nodeMatches, pathId } from './model.js';

defineOptions({ name: 'PermissionNode' });

const props = defineProps({
  node: { type: Object, required: true },
  depth: { type: Number, default: 0 },
  query: { type: String, default: '' },
  collapsed: { type: Object, default: () => new Set() },
  busy: { type: Boolean, default: false },
});

const emit = defineEmits(['toggle', 'save']);

const children = computed(() => props.node.children || []);
const hasVisibleChildren = computed(() => children.value.some((child) => nodeMatches(child, props.query)));
const visibleChildren = computed(() => children.value.filter((child) => nodeMatches(child, props.query)));
const isCollapsed = computed(() => props.collapsed.has(props.node.path));
const blocked = computed(() => Boolean(props.node.disabledBy && props.node.disabledBy !== props.node.path));
const label = computed(() => (props.node.path === '*' ? '/samsara（整棵树）' : props.node.label));
const expandText = computed(() => {
  if (!children.value.length || !hasVisibleChildren.value) return '·';
  return isCollapsed.value && !props.query ? '▸' : '▾';
});
const expandTitle = computed(() => {
  if (!children.value.length || !hasVisibleChildren.value) return '';
  return isCollapsed.value && !props.query ? '展开' : '折叠';
});
const levelTitle = computed(() => (blocked.value
  ? '被祖先 ' + props.node.disabledBy + ' 停用；先恢复祖先'
  : '本节点声明的等级；关闭 = DENY（高于 config/owner/控制台）'));
const disabledTitle = computed(() => (blocked.value
  ? '被祖先 ' + props.node.disabledBy + ' 停用；先恢复祖先'
  : '整节点停用并级联到后代；与“关闭权限”不同，停用不改变等级'));

function onLevel(value) {
  emit('save', { path: props.node.path, level: value });
}
</script>

<style scoped>
.perm-node { min-width: 0; }
.perm-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  padding: 5px 8px;
  border-bottom: 1px solid var(--line);
}
.perm-row.disabled { opacity: .62; }
.perm-expand {
  width: 24px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--muted);
}
.perm-label {
  min-width: 120px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.perm-badge {
  padding: 1px 7px;
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--muted);
  font-size: 11px;
  white-space: nowrap;
}
.perm-badge.warning { color: var(--amber); border-color: var(--amber); }
.perm-badge.custom { color: var(--accent); border-color: var(--accent); }
.perm-badge.danger-text { color: var(--danger); border-color: var(--danger); }
.perm-secondary {
  padding: 2px 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel);
  color: var(--fg-soft);
  font-size: 11px;
}
.perm-secondary.active { color: var(--danger); border-color: var(--danger); }
</style>
