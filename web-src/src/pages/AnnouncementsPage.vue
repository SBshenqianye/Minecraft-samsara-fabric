<template>
  <TabShell name="announcements">
    <LegacyPanel panel-id="panel-announcements" />
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import LegacyPanel from '../components/LegacyPanel.vue';

defineOptions({ name: 'AnnouncementsPage' });
</script>
