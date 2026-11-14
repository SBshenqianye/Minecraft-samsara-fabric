<template>
  <TabShell name="claim-range">
    <div id="panel-claim-range" class="panel-stack">
      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>圈地范围</h2>
            <p class="panel-sub">按染料颜色设置单方向延伸距离，或使用固定距离；保存后已有圈地会立即按新范围重算。</p>
          </div>
        </div>
        <div id="claim-range-message" class="message" role="status"></div>
        <div id="claim-range-files" class="config-files"></div>
      </section>
    </div>
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';

defineOptions({ name: 'ClaimRangePage' });
</script>
