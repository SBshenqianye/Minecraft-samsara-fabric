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

      <!-- 本体页的按钮属于页面自己（ServerPage / ServerConsolePage），hero 只负责标题与说明。 -->
      <div id="server-actions" class="page-hero-main" :hidden="!serverMode">
        <div class="page-hero-text">
          <h1 id="server-action-title" class="page-hero-title">{{ serverTitle }}</h1>
          <p id="server-action-hint" class="page-hero-hint muted small">{{ serverHint }}</p>
        </div>
      </div>
    </section>

    <!-- 旧脚本仍按 id / data-panel 查找这些 Tab；视觉上由 ia.css 隐藏。点击直接走路由。 -->
    <nav class="ia-legacy-tabs" aria-hidden="true">
      <button class="top-tab" :class="{ active: !serverMode }" type="button"
          data-section="mods" @click="goWorkspace('mods')">samsara</button>
      <button class="top-tab" :class="{ active: serverMode }" type="button"
          data-section="server" @click="goWorkspace('server')">本体</button>
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
      <button v-for="tab in SERVER_TABS" :key="tab.panel" class="tab" type="button"
          :class="{ active: activeServerPanel === tab.panel }"
          :data-server-panel="tab.panel" @click="goServerPanel(tab.panel)">{{ tab.label }}</button>
    </nav>
  </header>
</template>

<script setup>
import { useRouter } from 'vue-router';

defineProps({
  crumbs: { type: Array, default: () => [] },
  workspaceShort: { type: String, default: 'samsara' },
  serverMode: { type: Boolean, default: false },
  heroTextOnly: { type: Boolean, default: false },
  serverTitle: { type: String, default: '服务器配置' },
  serverHint: { type: String, default: '' },
  activeServerPanel: { type: String, default: '' },
});

const router = useRouter();

const SERVER_TABS = [
  { panel: 'server-config-panel', label: '服务器配置', path: '/vanilla/config' },
  { panel: 'server-backup-panel', label: '存档备份', path: '/vanilla/backup' },
  { panel: 'server-console-panel', label: 'RCON 控制台', path: '/vanilla/console' },
  { panel: 'server-audit-panel', label: 'Web 审计', path: '/samsara/system/audit' },
];

function goWorkspace(section) {
  const target = section === 'server' ? '/vanilla/config' : '/samsara/economy';
  if (router.currentRoute.value.path !== target) router.push(target);
}

function goServerPanel(panel) {
  const tab = SERVER_TABS.find((item) => item.panel === panel);
  if (tab && router.currentRoute.value.path !== tab.path) router.push(tab.path);
}

defineExpose({ SERVER_TABS });
</script>
