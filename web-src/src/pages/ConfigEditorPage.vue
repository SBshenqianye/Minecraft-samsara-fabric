<template>
<TabShell name="config-editor">
<div id="panel-config-editor" class="panel-stack">
  <section class="panel">
    <div class="panel-head">
      <div>
        <h2>配置编辑器</h2>
        <p class="panel-sub">PageSpec / FormSpec 驱动：samsara YAML、服务器本体与第三方 YAML/JSON/TOML/properties。第三方与服务器配置需先预览确认，写入前自动备份。</p>
      </div>
      <div class="panel-tools">
        <label class="search-box">
          <span aria-hidden="true">⌕</span>
          <input id="config-editor-search" type="search" placeholder="搜索页面 / 字段 / 分组">
        </label>
        <span id="config-editor-summary" class="muted small"></span>
      </div>
    </div>
    <div id="config-editor-message" class="message" role="status"></div>
    <div class="config-editor-layout">
      <div id="config-editor-body" class="config-editor-body"></div>
    </div>
  </section>
</div>
</TabShell>
</template>

<script setup>
import TabShell from '../components/TabShell.vue';
import { useLegacyPage } from '../composables/useLegacyPage.js';

defineOptions({ name: 'ConfigEditorPage' });

useLegacyPage({
  panelId: 'panel-config-editor',
  load: () => import('../../../mod/src/main/resources/web/config-editor.js'),
  afterLoad: () => window.SamsaraConfigEditor?.setSourceFilter?.('samsara'),
  activate: () => window.SamsaraConfigEditor?.activate?.(),
});
</script>
