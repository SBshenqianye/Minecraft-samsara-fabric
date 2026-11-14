<template>
  <TabShell name="config-editor">
    <LegacyPanel panel-id="panel-config-editor" />
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import LegacyPanel from '../components/LegacyPanel.vue';
import { useLegacyPage } from '../composables/useLegacyPage.js';

defineOptions({ name: 'ConfigEditorPage' });

useLegacyPage({
  panelId: 'panel-config-editor',
  load: () => import('../../../mod/src/main/resources/web/config-editor.js'),
  afterLoad: () => window.SamsaraConfigEditor?.setSourceFilter?.('samsara'),
  activate: () => window.SamsaraConfigEditor?.activate?.(),
});
</script>
