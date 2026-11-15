<template>
  <section class="panel" id="world-stack-jobs-panel">
    <div class="panel-head">
      <div>
        <h2>任务与结果</h2>
        <p class="panel-sub">导入/归档是重启时执行的任务；这里显示排队、完成、失败与回滚说明。</p>
      </div>
    </div>
    <div id="world-stack-jobs" class="world-stack-jobs">
      <article v-for="job in store.jobs.value" :key="job.id + job.type + job.stage" class="world-stack-job" :class="job.status">
        <div class="world-stack-job-head">
          <strong>{{ job.type + ' · ' + job.id }}</strong>
          <span class="world-stack-badge" :class="job.status">{{ job.status }}</span>
        </div>
        <p class="muted small">{{ job.stage }}{{ job.progress ? ' · ' + job.progress + '%' : '' }}{{ job.message ? ' · ' + job.message : '' }}</p>
        <details v-if="job.log.length">
          <summary>日志（{{ job.log.length }} 行）</summary>
          <pre class="world-stack-log">{{ job.log.join('\n') }}</pre>
        </details>
      </article>
      <p v-if="!store.jobs.value.length" class="muted small">暂无导入/归档任务。排序是立即生效的，不会在这里生成任务。</p>
    </div>
  </section>
</template>

<script setup>
defineProps({
  store: { type: Object, required: true },
});
</script>
