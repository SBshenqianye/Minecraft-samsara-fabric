<template>
  <div ref="host" class="curve-host"></div>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { useApi } from '../../composables/useApi.js';
import { createCurveEditor } from './curve-kernel.js';
import { valueKey } from './model.js';

const props = defineProps({
  file: { type: Object, required: true },
  entry: { type: Object, required: true },
  config: { type: Object, required: true },
});

const { request } = useApi();
const host = ref(null);

onMounted(() => {
  if (!host.value) return;
  const editor = createCurveEditor({
    key: valueKey(props.file.id, props.entry.path),
    file: props.file,
    entry: props.entry,
    value: props.config.currentValue(props.file, props.entry),
    getValue: (entry) => props.config.currentValue(props.file, entry),
    setValue: (value) => props.config.setValue(props.file, props.entry, value),
    requestEstimate: async (query) => {
      const result = await request('/api/config/retention-estimate' + query);
      if (!result.ok) throw new Error(result.data?.error || '容量预估失败');
      return result.data;
    },
    onValidation: (valid) => props.config.setCurveInvalid(props.file, props.entry, valid),
  });
  host.value.replaceChildren(editor);
});
</script>
