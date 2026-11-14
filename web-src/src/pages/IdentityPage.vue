<template>
  <TabShell name="identity">
    <LegacyPanel panel-id="panel-identity" />
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import LegacyPanel from '../components/LegacyPanel.vue';
import { useLegacyPage } from '../composables/useLegacyPage.js';

defineOptions({ name: 'IdentityPage' });

useLegacyPage({
  panelId: 'panel-identity',
  load: () => import('../../../mod/src/main/resources/web/identity.js'),
  activate: () => window.SamsaraIdentity?.activate?.(),
});
</script>
