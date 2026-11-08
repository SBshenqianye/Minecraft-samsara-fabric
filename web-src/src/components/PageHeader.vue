<template>
  <header class="page-banner" :class="{ compact }">
    <div class="page-banner-main">
      <div class="page-banner-text">
        <h2 v-if="title" class="page-banner-title">{{ title }}</h2>
        <p v-if="description" class="page-banner-desc">{{ description }}</p>
      </div>
      <div v-if="badges.length" class="page-banner-badges">
        <span v-for="badge in badges" :key="badge" class="page-banner-badge">{{ badge }}</span>
      </div>
    </div>
    <div v-if="$slots.actions" class="page-banner-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  badges: { type: Array, default: () => [] },
  compact: { type: Boolean, default: false },
});
</script>
