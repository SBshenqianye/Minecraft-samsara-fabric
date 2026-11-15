<template>
  <div id="world-stack-stats" class="stat-row">
    <span v-for="[label, value] in store.stats.value" :key="label" class="map-stat">
      <span>{{ label }}</span>
      <strong>{{ value }}</strong>
    </span>
  </div>
</template>

<script setup>
defineProps({
  store: { type: Object, required: true },
});
</script>
