<template>
  <div ref="host" class="legacy-host"></div>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { mountLegacyUser } from '../legacy/user.js';

const host = ref(null);

onMounted(async () => {
  try {
    await mountLegacyUser(host.value);
  } catch (error) {
    const box = document.createElement('div');
    box.className = 'container-message error';
    box.textContent = '用户端加载失败：' + (error?.message || error);
    host.value?.appendChild(box);
  }
});
</script>
