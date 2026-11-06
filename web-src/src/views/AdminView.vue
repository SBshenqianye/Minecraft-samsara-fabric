<template>
  <div ref="host" class="legacy-host"></div>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { mountLegacyAdmin } from '../legacy/admin.js';

const host = ref(null);

onMounted(async () => {
  try {
    await mountLegacyAdmin(host.value);
  } catch (error) {
    const box = document.createElement('div');
    box.className = 'container-message error';
    box.textContent = '管理端加载失败：' + (error?.message || error);
    host.value?.appendChild(box);
  }
});
</script>
