<template>
  <TabShell name="map">
    <LegacyPanel panel-id="panel-map" />
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import LegacyPanel from '../components/LegacyPanel.vue';
import { useLegacyPage } from '../composables/useLegacyPage.js';

defineOptions({ name: 'MapPage' });

useLegacyPage({
  panelId: 'panel-map',
  load: () => import('../../../mod/src/main/resources/web/map.js'),
  activate: () => window.SamsaraMap?.activate?.(),
});
</script>
