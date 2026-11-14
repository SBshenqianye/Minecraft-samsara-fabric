<template>
  <TabShell name="lottery">
    <LegacyPanel panel-id="panel-lottery" />
    <section v-if="isFundPage" class="panel fund-inventory-embed">
      <PoolInventoryPage kind="fund" embedded />
    </section>
  </TabShell>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import TabShell from '../components/TabShell.vue';
import LegacyPanel from '../components/LegacyPanel.vue';
import PoolInventoryPage from './PoolInventoryPage.vue';
import { useLegacyPage } from '../composables/useLegacyPage.js';

defineOptions({ name: 'LotteryPage' });

useLegacyPage({
  panelId: 'panel-lottery',
  load: () => import('../../../mod/src/main/resources/web/lottery.js'),
  activate: () => window.samsaraLottery?.activate?.(),
});

const route = useRoute();
const isFundPage = computed(() => route.meta.subpage === 'fund');
</script>

<style scoped>
.fund-inventory-embed {
  margin-top: 16px;
}
</style>
