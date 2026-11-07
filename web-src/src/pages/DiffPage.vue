<template>
  <TabShell name="diff" title="Diff 回看" description="只读回看区块差异；复用现有 Diff API，不重写算法。">
    <section class="panel">
      <h3>Diff 回看</h3>
      <p class="muted small">选择世界、维度与区块后，可在世界地图中查看差异并启动只读回看。</p>
      <p class="muted small">详细控制将在后续步骤接入。</p>
    </section>
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
</script>
