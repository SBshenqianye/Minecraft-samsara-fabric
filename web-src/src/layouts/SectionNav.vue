<template>
  <nav v-if="sections.length" class="section-tabs" aria-label="二级页面">
    <button
      v-for="section in sections"
      :key="section.id"
      class="section-tab"
      :class="{ active: activeSection === section.id }"
      type="button"
      :data-section="section.id"
      @click="$emit('select-section', section)"
    >
      {{ section.label }}
    </button>
  </nav>

  <nav v-if="subnavItems.length" class="section-subnav" aria-label="三级页面">
    <div class="subnav-title">{{ sectionLabel }}</div>
    <button
      v-for="item in subnavItems"
      :key="item.id"
      class="subnav-item"
      :class="{ active: item.id === activeSubpage }"
      type="button"
      :data-subnav="item.id"
      @click="$emit('navigate', item.target)"
    >
      {{ item.label }}
    </button>
  </nav>
</template>

<script setup>
defineProps({
  sections: { type: Array, default: () => [] },
  activeSection: { type: String, default: '' },
  subnavItems: { type: Array, default: () => [] },
  activeSubpage: { type: String, default: '' },
  sectionLabel: { type: String, default: '' },
});

defineEmits(['select-section', 'navigate']);
</script>
