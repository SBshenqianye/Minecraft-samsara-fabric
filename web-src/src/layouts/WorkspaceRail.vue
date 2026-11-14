<template>
  <nav
    ref="railEl"
    class="workspace-rail"
    :class="{ collapsed: !open, peek: open && !pinned, pinned }"
    aria-label="顶级工作区"
    @mouseenter="onRailEnter"
  >
    <div class="rail-head">
      <span class="rail-title">工作区</span>
      <button
        class="rail-toggle"
        type="button"
        :aria-pressed="open ? 'true' : 'false'"
        :title="open ? '收起工作区导航' : '展开工作区导航'"
        @click="toggle"
      >{{ open ? '«' : '»' }}</button>
    </div>
    <button
      v-for="workspace in workspaces"
      :key="workspace.id"
      class="rail-item"
      :class="{ active: activeWorkspace === workspace.id }"
      type="button"
      :data-workspace="workspace.id"
      @click="$emit('select-workspace', workspace.id)"
    >
      <span class="rail-label">{{ workspace.label }}</span>
      <span class="rail-hint">{{ workspace.hint }}</span>
    </button>

    <div class="rail-title rail-title-mods">其他模组</div>
    <button
      v-for="mod in mods"
      :key="mod.id"
      class="rail-item rail-item-mod"
      :class="{ active: activeWorkspace === 'mods' && activeModId === mod.id }"
      type="button"
      :data-mod-id="mod.id"
      @click="$emit('select-mod', mod)"
    >
      <span class="rail-label">{{ mod.label }}</span>
      <span class="rail-hint">{{ mod.hint }}</span>
    </button>
    <p v-if="!mods.length" class="rail-empty">连接后显示有配置的模组</p>
  </nav>

  <button
    v-show="!open && !closing"
    ref="railHandle"
    class="rail-handle"
    type="button"
    aria-label="展开工作区导航"
    @mouseenter="onRailEnter"
    @click="onRailEnter"
  >工作区 ›</button>
</template>

<script setup>
import { useWorkspaceRail } from '../composables/useWorkspaceRail.js';

defineProps({
  workspaces: { type: Array, default: () => [] },
  mods: { type: Array, default: () => [] },
  activeWorkspace: { type: String, default: '' },
  activeModId: { type: String, default: '' },
});

defineEmits(['select-workspace', 'select-mod']);

const {
  railEl,
  railHandle,
  open,
  pinned,
  closing,
  toggle,
  onRailEnter,
  collapse,
  maybePeek,
} = useWorkspaceRail();

defineExpose({ collapse, maybePeek });
</script>
