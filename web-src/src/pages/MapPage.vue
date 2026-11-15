<template>
  <TabShell name="map">
    <div id="panel-map" class="panel-stack">
      <MapPanel />
    </div>
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import MapPanel from './map/MapPanel.vue';
import { clearPageNav } from '../composables/usePageNav.js';
import { onBeforeUnmount } from 'vue';

defineOptions({ name: 'MapPage' });

onBeforeUnmount(clearPageNav);
</script>
