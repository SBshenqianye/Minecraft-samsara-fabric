<template>
  <header class="app-header ia-header">
    <div class="ia-header-main">
      <div class="brand ia-brand">
        <div class="brand-mark" aria-hidden="true">
          <span></span><span></span><span></span>
        </div>
        <div class="brand-text">
          <h1>samsara</h1>
          <p>Web 控制台 <span id="app-version" class="version-pill">v—</span></p>
        </div>
      </div>

      <div class="connection">
        <span id="connection-state" class="conn-state">未连接</span>
        <a class="user-view-link" href="/user" target="_blank" rel="noopener"
            title="公开只读信息，不需要 token">用户视图</a>
      </div>
    </div>

    <section class="page-hero" aria-label="页面横幅">
      <div class="page-hero-art" aria-hidden="true"></div>
      <div class="page-hero-context">
        <div class="ia-crumbs" aria-label="面包屑">
          <template v-for="(crumb, index) in crumbs" :key="'crumb-' + crumb + index">
            <span v-if="index" class="ia-crumb-sep">/</span>
            <span :class="index === crumbs.length - 1 ? 'ia-crumb-title' : 'ia-crumb-part'">
              {{ crumb }}
            </span>
          </template>
        </div>
        <span class="page-hero-tag">{{ workspaceShort }} · 控制台</span>
      </div>

      <div id="mods-actions" class="page-hero-main" :class="{ 'hero-text-only': heroTextOnly }"
          :hidden="serverMode">
        <div class="page-hero-text">
          <h1 id="action-title" class="page-hero-title">配置</h1>
          <p id="action-hint" class="page-hero-hint muted small"></p>
        </div>
        <div class="tab-actions-buttons page-hero-buttons">
          <button id="action-default" class="ghost" type="button" hidden>恢复默认</button>
          <button id="reload" class="ghost" type="button">重新读取</button>
          <button id="save" class="primary" type="button">保存并重载</button>
        </div>
      </div>

      <div id="server-actions" class="page-hero-main" :hidden="!serverMode">
        <div class="page-hero-text">
          <h1 id="server-action-title" class="page-hero-title">服务器配置</h1>
          <p id="server-action-hint" class="page-hero-hint muted small"></p>
        </div>
        <div class="tab-actions-buttons page-hero-buttons">
          <button id="server-reload" class="ghost" type="button">重新读取</button>
          <button id="server-save" class="primary" type="button">保存并重载</button>
          <button id="server-backup-create" class="primary" type="button" hidden>创建备份</button>
        </div>
      </div>
    </section>

    <!-- 旧脚本仍按 id / data-panel 查找这些 Tab；视觉上由 ia.css 隐藏。 -->
    <nav class="ia-legacy-tabs" aria-hidden="true">
      <button class="top-tab active" type="button" data-section="mods">samsara</button>
      <button class="top-tab" type="button" data-section="server">本体</button>
    </nav>

    <nav id="mods-tabs" class="ia-legacy-tabs" aria-hidden="true">
      <button class="tab" type="button" data-panel="panel-config-editor">配置编辑器</button>
      <button class="tab active" type="button" data-panel="panel-config">高级配置</button>
      <button class="tab" type="button" data-panel="panel-claim-range">圈地范围</button>
      <button class="tab" type="button" data-panel="panel-output">输出格式</button>
      <button class="tab" type="button" data-panel="panel-permissions">权限</button>
      <button class="tab" type="button" data-panel="panel-map">地图</button>
      <button class="tab" type="button" data-panel="panel-announcements">公告</button>
      <button class="tab" type="button" data-panel="panel-identity">身份组</button>
      <button class="tab" type="button" data-panel="panel-world-stack">世界栈</button>
      <button class="tab" type="button" data-panel="panel-lottery">经济业务</button>
    </nav>

    <nav id="server-tabs" class="ia-legacy-tabs" aria-hidden="true">
      <button class="tab active" type="button" data-server-panel="server-config-panel">服务器配置</button>
      <button class="tab" type="button" data-server-panel="server-backup-panel">存档备份</button>
      <button class="tab" type="button" data-server-panel="server-console-panel">RCON 控制台</button>
      <button class="tab" type="button" data-server-panel="server-audit-panel">Web 审计</button>
    </nav>
  </header>
</template>

<script setup>
defineProps({
  crumbs: { type: Array, default: () => [] },
  workspaceShort: { type: String, default: 'samsara' },
  serverMode: { type: Boolean, default: false },
  heroTextOnly: { type: Boolean, default: false },
});
</script>
