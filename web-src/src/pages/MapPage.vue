<template>
  <TabShell name="map">
    <LegacyPanel panel-id="panel-map" />
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import LegacyPanel from '../components/LegacyPanel.vue';

defineOptions({ name: 'MapPage' });
</script>
