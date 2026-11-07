<template>
  <TabShell name="track" title="轨迹回看" description="查看最近轨迹，并跳转游戏内回看；第一版只读入口。">
    <section class="panel">
      <h3>轨迹回看</h3>
      <p class="muted small">
        轨迹回放仍在服务器内执行。这里列出最近可回看的玩家与时段；点击“复制回看命令”后到游戏内执行。
      </p>
      <p class="muted small">后端只读接口将在后续步骤接入；当前页面占位。</p>
    </section>
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
</script>
