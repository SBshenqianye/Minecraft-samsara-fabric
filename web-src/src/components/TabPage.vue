<template>
  <div class="tab-page" :data-page="name">
    <div v-if="title || $slots.actions" class="tab-page-head">
      <div>
        <h2 v-if="title">{{ title }}</h2>
        <p v-if="description" class="muted small">{{ description }}</p>
      </div>
      <div v-if="$slots.actions" class="tab-page-actions">
        <slot name="actions" />
      </div>
    </div>
    <slot />
  </div>
</template>

<script setup>
defineProps({
  name: { type: String, required: true },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
});
</script>
