<template>
  <TabShell name="output">
    <LegacyPanel panel-id="panel-output" />
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import LegacyPanel from '../components/LegacyPanel.vue';

defineOptions({ name: 'OutputPage' });
</script>
