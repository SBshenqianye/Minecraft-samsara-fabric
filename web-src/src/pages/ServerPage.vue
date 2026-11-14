<template>
  <div id="panel-server">
    <LegacyPanel panel-id="server-config-panel" />
    <LegacyPanel panel-id="server-backup-panel" />
    <LegacyPanel panel-id="server-audit-panel" />
  </div>
</template>

<script setup>
import { useRoute } from 'vue-router';
import LegacyPanel from '../components/LegacyPanel.vue';
import { useLegacyPage } from '../composables/useLegacyPage.js';

defineOptions({ name: 'ServerPage' });

const route = useRoute();

useLegacyPage({
  panelIds: ['server-config-panel', 'server-backup-panel', 'server-audit-panel'],
  load: async () => {
    await import('../../../mod/src/main/resources/web/world-stack.js');
    await import('../../../mod/src/main/resources/web/server.js');
  },
  activate: () => window.SamsaraServer?.showPanel?.(route.meta.serverPanel || 'server-config-panel'),
});
</script>
