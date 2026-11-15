<template>
  <section class="panel" id="world-stack-files-panel">
    <div class="panel-head">
      <div>
        <h2>文件位置</h2>
        <p class="panel-sub">动态列出本模组创建/使用的服务器级目录。备份不随存档移动；缓存目录可安全删除；<code>world-stack-backup</code> 只保留最近 N 份（由 <code>backupKeep</code> 控制），<code>world-stack-archive</code> 是用户数据不会自动删除。点击行或 ⧉ 可复制绝对路径。</p>
      </div>
    </div>
    <div id="world-stack-files" class="world-stack-files">
      <div
        v-for="node in store.files.value"
        :key="node.path"
        class="world-stack-file"
        :class="{ missing: !node.exists }"
        :style="{ paddingLeft: (Math.max(0, node.depth) * 14 + 4) + 'px' }"
        :title="node.path"
        @click="copy(node.path)"
      >
        <span class="world-stack-file-icon">{{ node.exists ? (node.kind === 'dir' ? '▸' : '·') : '×' }}</span>
        <span class="world-stack-file-label">{{ node.label }}</span>
        <span class="world-stack-file-note">{{ node.note }}</span>
        <span class="world-stack-file-size">{{ node.size }}</span>
        <button class="world-stack-file-copy" type="button" title="复制路径" @click.stop="copy(node.path)">⧉</button>
      </div>
      <p v-if="!store.files.value.length" class="muted small">暂无文件位置信息。</p>
    </div>
  </section>
</template>

<script setup>
const props = defineProps({
  store: { type: Object, required: true },
});

function copy(path) {
  props.store.copyPath(path).then((ok) => {
    if (ok) props.store.setMessage('已复制路径：' + path, 'ok');
    else props.store.setMessage('复制失败：浏览器不允许访问剪贴板', 'error');
  });
}
</script>
