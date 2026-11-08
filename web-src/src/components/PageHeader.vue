<template>
  <div class="page-header-block" :class="{ compact }">
    <header class="page-banner">
      <div class="page-banner-main">
        <div class="page-banner-text">
          <h2 v-if="title" class="page-banner-title">{{ title }}</h2>
          <p v-if="description" class="page-banner-desc">{{ description }}</p>
        </div>
        <div v-if="badges.length" class="page-banner-badges">
          <span v-for="badge in badges" :key="badge" class="page-banner-badge">{{ badge }}</span>
        </div>
      </div>
    </header>
    <div v-if="$slots.actions" class="page-toolbar">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  badges: { type: Array, default: () => [] },
  compact: { type: Boolean, default: false },
});
</script>
