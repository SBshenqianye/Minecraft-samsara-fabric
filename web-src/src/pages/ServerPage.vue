<template>
  <div id="panel-server">
    <LegacyPanel panel-id="server-config-panel" />
    <LegacyPanel panel-id="server-backup-panel" />
    <LegacyPanel panel-id="server-console-panel" />
    <LegacyPanel panel-id="server-audit-panel" />
  </div>
</template>

<script setup>
import LegacyPanel from '../components/LegacyPanel.vue';
</script>
