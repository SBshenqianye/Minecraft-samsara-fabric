<template>
  <TabShell name="permissions">
    <LegacyPanel panel-id="panel-permissions" />
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import LegacyPanel from '../components/LegacyPanel.vue';

defineOptions({ name: 'PermissionsPage' });
</script>
