<template>
  <TabShell name="config">
    <div id="panel-config" class="panel-stack">
      <section class="panel status-panel">
        <div class="panel-head">
          <div>
            <h2>运行状态</h2>
            <p class="panel-sub">只读信息；配置文件保存在服务器本机。</p>
          </div>
          <span id="status-warning" class="status-warning" hidden></span>
        </div>
        <div id="status-chips" class="status-chips"></div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>配置项</h2>
            <p class="panel-sub">所有输入都会写入对应 YAML；留空时仍按默认值兜底。</p>
          </div>
          <div class="panel-tools">
            <label class="search-box">
              <span aria-hidden="true">⌕</span>
              <input id="search" type="search" placeholder="搜索配置：键名 / 说明 / 分组" />
            </label>
            <span id="summary" class="muted small"></span>
          </div>
        </div>
        <div id="message" class="message" role="status"></div>
        <div id="files" class="config-files"></div>
      </section>
    </div>
  </TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';

defineOptions({ name: 'ConfigPage' });
</script>
