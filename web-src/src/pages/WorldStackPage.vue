<template>
  <TabShell name="world-stack">
    <LegacyPanel panel-id="panel-world-stack" />
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import LegacyPanel from '../components/LegacyPanel.vue';
import { useLegacyPage } from '../composables/useLegacyPage.js';

defineOptions({ name: 'WorldStackPage' });

useLegacyPage({
  panelId: 'panel-world-stack',
  load: () => import('../../../mod/src/main/resources/web/world-stack.js'),
  activate: () => window.SamsaraWorldStack?.activate?.(),
});
</script>
