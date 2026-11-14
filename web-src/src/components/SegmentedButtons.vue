<template>
  <div class="segmented" role="group">
    <button
      v-for="choice in normalized"
      :key="choice.value"
      class="segmented-btn"
      :class="{ active: String(value) === choice.value }"
      type="button"
      :data-value="choice.value"
      :aria-pressed="String(value) === choice.value ? 'true' : 'false'"
      @click="pick(choice.value)"
    >{{ choice.label }}</button>
  </div>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  value: { type: [String, Number, Boolean], default: '' },
  choices: { type: Array, default: () => [] },
});
const emit = defineEmits(['change']);

const normalized = computed(() => props.choices.map((choice) => (choice && typeof choice === 'object'
  ? { value: String(choice.value), label: choice.label == null ? String(choice.value) : String(choice.label) }
  : { value: String(choice), label: String(choice) })));

function pick(value) {
  if (String(props.value) === value) return;
  emit('change', value);
}
</script>
