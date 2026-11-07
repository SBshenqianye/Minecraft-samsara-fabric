<template>
  <ul class="folder-tree" :class="{ 'folder-tree-root': depth === 0 }">
    <li v-for="node in nodes" :key="node.type + ':' + node.path">
      <template v-if="node.type === 'folder'">
        <button
          class="folder-node"
          :class="{ active: activeFolder === node.path }"
          type="button"
          :style="{ paddingLeft: (8 + depth * 14) + 'px' }"
          @click="$emit('folder', node.path)"
        >
          <span class="folder-icon" aria-hidden="true">▸</span>
          <span>{{ node.name }}</span>
        </button>
        <FolderTree
          v-if="node.children && node.children.length"
          :nodes="node.children"
          :active-folder="activeFolder"
          :active-file="activeFile"
          :depth="depth + 1"
          @folder="$emit('folder', $event)"
          @file="$emit('file', $event)"
        />
      </template>
      <button
        v-else
        class="file-node"
        :class="{ active: activeFile === node.id }"
        type="button"
        :style="{ paddingLeft: (8 + depth * 14) + 'px' }"
        @click="$emit('file', node)"
      >
        <span class="file-icon" aria-hidden="true">·</span>
        <span>{{ node.name }}</span>
      </button>
    </li>
  </ul>
</template>

<script setup>
defineOptions({ name: 'FolderTree' });

defineProps({
  nodes: { type: Array, default: () => [] },
  activeFolder: { type: String, default: '' },
  activeFile: { type: String, default: '' },
  depth: { type: Number, default: 0 },
});

defineEmits(['folder', 'file']);
</script>
