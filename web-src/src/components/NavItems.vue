<template>
  <template v-for="item in items" :key="item.target + ':' + item.label">
    <button
      type="button"
      class="nav-item"
      :class="{ active: active === item.target }"
      :data-target="item.target || ''"
      @click="emit('select', item)"
    >
      <span>{{ item.label }}</span>
      <span v-if="item.count != null" class="nav-count">{{ item.count }}</span>
    </button>
    <div v-if="item.children && item.children.length" class="nav-children">
      <NavItems :items="item.children" :active="active" @select="(child) => emit('select', child)" />
    </div>
  </template>
</template>

<script setup>
defineOptions({ name: 'NavItems' });

defineProps({
  items: { type: Array, default: () => [] },
  active: { type: String, default: '' },
});
const emit = defineEmits(['select']);
</script>
