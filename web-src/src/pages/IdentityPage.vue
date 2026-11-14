<template>
  <TabShell name="identity">
    <LegacyPanel panel-id="panel-identity" />
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import LegacyPanel from '../components/LegacyPanel.vue';

defineOptions({ name: 'IdentityPage' });
</script>
