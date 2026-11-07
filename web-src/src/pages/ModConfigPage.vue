<template>
  <TabShell name="mod-config" :title="title" description="按模组配置路径递归浏览；文件页复用 PageSpec/FormSpec 编辑器。">
    <section class="panel">
      <h3>{{ title }}</h3>
      <p class="muted small">其他模组配置树将在后续步骤接入。</p>
    </section>
  </TabShell>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import TabShell from '../components/TabShell.vue';

const route = useRoute();
const title = computed(() => route.params.modId ? String(route.params.modId) : '其他模组');
</script>
