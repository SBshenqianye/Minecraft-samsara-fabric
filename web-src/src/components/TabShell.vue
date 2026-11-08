<template>
  <div class="tab-shell" :data-page="name">
    <PageHeader
      v-if="resolvedTitle || description || $slots.actions"
      :title="resolvedTitle"
      :description="description"
      :badges="badges"
      :compact="compact"
    >
      <template v-if="$slots.actions" #actions>
        <slot name="actions" />
      </template>
    </PageHeader>
    <slot />
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import PageHeader from './PageHeader.vue';

const props = defineProps({
  name: { type: String, required: true },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  badges: { type: Array, default: () => [] },
  compact: { type: Boolean, default: false },
});

const route = useRoute();
const resolvedTitle = computed(() => props.title || String(route.meta.title || ''));
</script>
