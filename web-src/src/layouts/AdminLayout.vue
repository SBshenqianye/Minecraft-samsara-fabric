<template>
  <div class="admin-layout ia-layout">
    <div ref="headerHost" class="ia-header-host"></div>
    <div class="ia-body">
      <nav class="workspace-rail" aria-label="顶级工作区">
        <div class="rail-title">工作区</div>
        <button
          v-for="workspace in baseWorkspaces"
          :key="workspace.id"
          class="rail-item"
          :class="{ active: activeWorkspace === workspace.id }"
          type="button"
          :data-workspace="workspace.id"
          @click="goWorkspace(workspace.id)"
        >
          <span class="rail-label">{{ workspace.label }}</span>
          <span class="rail-hint">{{ workspace.hint }}</span>
        </button>

        <div class="rail-title rail-title-mods">其他模组</div>
        <button
          v-for="mod in mods"
          :key="mod.id"
          class="rail-item rail-item-mod"
          :class="{ active: activeWorkspace === 'mods' && activeModId === mod.id }"
          type="button"
          :data-mod-id="mod.id"
          @click="goMod(mod)"
        >
          <span class="rail-label">{{ mod.id }}</span>
          <span class="rail-hint">{{ mod.pages.length }} 个配置文件</span>
        </button>
        <p v-if="!mods.length" class="rail-empty">连接后显示有配置的模组</p>
      </nav>

      <div class="ia-main">
        <div class="ia-crumbs">
          <el-tag size="small" effect="plain" type="info">{{ workspaceLabel }}</el-tag>
          <span class="ia-crumb-sep">/</span>
          <span class="ia-crumb-title">{{ pageTitle }}</span>
        </div>

        <nav v-if="sections.length" class="section-tabs" aria-label="二级页面">
          <button
            v-for="section in sections"
            :key="section.id"
            class="section-tab"
            :class="{ active: activeSection === section.id }"
            type="button"
            :data-section="section.id"
            @click="goSection(section)"
          >
            {{ section.label }}
          </button>
        </nav>

        <main class="app-shell">
          <aside id="global-nav" class="side-nav" aria-label="页内导航"></aside>
          <div class="content">
            <LotteryPage v-show="page === 'lottery'" />
            <TrackPage v-if="page === 'track'" />
            <DiffPage v-if="page === 'diff'" />
            <ContainerPage v-if="page === 'containers'" embedded />
            <InventoryPage v-if="page === 'inventory'" />
            <ConfigEditorPage v-if="page === 'config-editor'" />
            <ConfigPage v-show="page === 'config'" />
            <ClaimRangePage v-show="page === 'claim-range'" />
            <OutputPage v-show="page === 'output'" />
            <PermissionsPage v-show="page === 'permissions'" />
            <MapPage v-show="page === 'map'" />
            <AnnouncementsPage v-show="page === 'announcements'" />
            <WorldStackPage v-show="page === 'world-stack'" />
            <IdentityPage v-show="page === 'identity'" />
            <ServerPage v-show="page === 'server'" />
            <ModConfigPage v-if="page === 'mod-config'" />
          </div>
        </main>
      </div>
    </div>

    <div ref="overlayHost" class="ia-overlay-host"></div>
    <div v-if="loadError" class="container-message error" style="margin: 20px">
      {{ loadError }}
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { loadLegacyDocument, waitForElement } from '../legacy/document.js';
import { loadLegacyAdminScripts } from '../legacy/admin.js';
import LotteryPage from '../pages/LotteryPage.vue';
import TrackPage from '../pages/TrackPage.vue';
import DiffPage from '../pages/DiffPage.vue';
import ContainerPage from '../pages/ContainerPage.vue';
import InventoryPage from '../pages/InventoryPage.vue';
import ConfigPage from '../pages/ConfigPage.vue';
import ConfigEditorPage from '../pages/ConfigEditorPage.vue';
import ClaimRangePage from '../pages/ClaimRangePage.vue';
import OutputPage from '../pages/OutputPage.vue';
import PermissionsPage from '../pages/PermissionsPage.vue';
import MapPage from '../pages/MapPage.vue';
import AnnouncementsPage from '../pages/AnnouncementsPage.vue';
import WorldStackPage from '../pages/WorldStackPage.vue';
import IdentityPage from '../pages/IdentityPage.vue';
import ServerPage from '../pages/ServerPage.vue';
import ModConfigPage from '../pages/ModConfigPage.vue';

const route = useRoute();
const router = useRouter();
const headerHost = ref(null);
const overlayHost = ref(null);
const loadError = ref('');
const mods = ref([]);

const baseWorkspaces = [
  { id: 'vanilla', label: '本体', hint: 'Minecraft / Vanilla' },
  { id: 'samsara', label: 'samsara', hint: '周目管理' },
];

const SECTIONS = {
  samsara: [
    { id: 'economy', label: '经济业务', target: '/samsara/economy' },
    { id: 'sky', label: '天网', target: '/samsara/sky/track' },
    { id: 'config', label: '配置', target: '/samsara/config/editor' },
    { id: 'world', label: '世界', target: '/samsara/world/map' },
    { id: 'community', label: '社区', target: '/samsara/community/announcements' },
  ],
  vanilla: [
    { id: 'server-config-panel', label: '服务器配置', target: '/vanilla/config' },
    { id: 'server-backup-panel', label: '存档备份', target: '/vanilla/backup' },
    { id: 'server-console-panel', label: 'RCON', target: '/vanilla/console' },
    { id: 'server-audit-panel', label: 'Web 审计', target: '/vanilla/audit' },
  ],
};

const LEGACY_ROUTES = {
  config: '/samsara/config/advanced',
  'config-editor': '/samsara/config/editor',
  'claim-range': '/samsara/config/claim-range',
  output: '/samsara/config/output',
  permissions: '/samsara/config/permissions',
  map: '/samsara/world/map',
  announcements: '/samsara/community/announcements',
  'world-stack': '/samsara/world/stack',
  lottery: '/samsara/economy',
  identity: '/samsara/community/identity',
};

const workspace = computed(() => route.meta.workspace || 'samsara');
const page = computed(() => route.meta.page || 'lottery');
const activeWorkspace = computed(() => workspace.value);
const activeModId = computed(() => String(route.params.modId || ''));
const activeSection = computed(() => {
  if (workspace.value === 'mods') return 'files';
  if (workspace.value === 'vanilla') return route.meta.serverPanel || 'server-config-panel';
  return route.meta.section || 'economy';
});
const sections = computed(() => {
  if (workspace.value === 'mods') return [];
  return SECTIONS[workspace.value] || [];
});
const workspaceLabel = computed(() => {
  if (workspace.value === 'mods') return activeModId.value || '其他模组';
  return workspace.value === 'vanilla' ? '本体' : 'samsara';
});
const pageTitle = computed(() => {
  if (workspace.value === 'mods') return page.value === 'mod-config' ? '配置树 / 文件' : '其他模组';
  return route.meta.title || sections.value.find((item) => item.id === activeSection.value)?.label || '';
});

let started = false;
let lastApplied = '';
let navTimer = 0;
let originalSetActiveTab = null;
let modsRetry = 0;

function navigate(path) {
  window.clearTimeout(navTimer);
  if (router.currentRoute.value.path === path) return;
  navTimer = window.setTimeout(() => {
    if (router.currentRoute.value.path !== path) router.push(path);
  }, 0);
}

function lastKey(key) {
  return 'samsara.ia.last.' + key;
}

function rememberTarget(workspaceId, sectionId, path) {
  try {
    localStorage.setItem(lastKey(workspaceId), path);
    if (sectionId) localStorage.setItem(lastKey(workspaceId + '.' + sectionId), path);
  } catch {
    // localStorage 不可用时忽略
  }
}

function rememberedTarget(key, fallback) {
  try {
    return localStorage.getItem(lastKey(key)) || fallback;
  } catch {
    return fallback;
  }
}

function goWorkspace(id) {
  if (id === 'samsara') {
    navigate(rememberedTarget('samsara', '/samsara/economy'));
  } else if (id === 'vanilla') {
    navigate(rememberedTarget('vanilla', '/vanilla/config'));
  }
}

function goSection(section) {
  navigate(rememberedTarget(workspace.value + '.' + section.id, section.target));
}

function goMod(mod) {
  const fallback = '/mods/' + encodeURIComponent(mod.id);
  navigate(rememberedTarget('mods.' + mod.id, fallback));
}

async function loadMods() {
  const ui = window.SamsaraUI;
  if (!ui?.api) return;
  try {
    const data = await ui.api('/api/config/pages');
    const groups = new Map();
    for (const pageSpec of data.pages || []) {
      if (pageSpec.source !== 'third-party') continue;
      const relative = String(pageSpec.id || '').startsWith('third-party:')
        ? String(pageSpec.id).substring('third-party:'.length)
        : String(pageSpec.fileName || '');
      const normalized = relative.replace(/\\/g, '/');
      const first = normalized.split('/')[0];
      if (!first) continue;
      if (!groups.has(first)) groups.set(first, []);
      groups.get(first).push(pageSpec);
    }
    mods.value = [...groups.entries()]
      .map(([id, pages]) => ({ id, pages }))
      .sort((a, b) => a.id.localeCompare(b.id));
    modsRetry = 0;
  } catch {
    // 未连接或接口不可用时保持空列表；连接完成后自动重试
    if (modsRetry++ < 20) {
      window.setTimeout(loadMods, 600);
    }
  }
}

onMounted(async () => {
  try {
    const doc = await loadLegacyDocument();
    const header = doc.querySelector('header.app-header');
    if (header) {
      const imported = document.importNode(header, true);
      imported.classList.add('ia-legacy-header');
      headerHost.value.appendChild(imported);
    }
    for (const node of Array.from(doc.body.childNodes)) {
      if (node === header) continue;
      if (node.nodeType === Node.ELEMENT_NODE
          && (node.tagName === 'MAIN' || node.tagName === 'SCRIPT')) {
        continue;
      }
      overlayHost.value.appendChild(document.importNode(node, true));
    }
    await waitForInitialPanel();
    try {
      await waitForElement('search', 4000);
    } catch {
      // 旧脚本要求 #search 存在；极端情况下继续，错误会显示在页面上
    }
    await loadLegacyAdminScripts();
    originalSetActiveTab = window.SamsaraUI?.setActiveTab || null;
    installLegacyTabBridge();
    window.SamsaraUI?.onConnected?.(loadMods);
    await nextTick();
    await applyRoute(true);
    started = true;
    window.setTimeout(loadMods, 400);
  } catch (error) {
    loadError.value = '管理端加载失败：' + (error?.message || error);
  }
});

watch(() => route.path, async () => {
  if (!started) return;
  try {
    await nextTick();
    await waitForCurrentPanel();
    await applyRoute();
  } catch (error) {
    loadError.value = '页面切换失败：' + (error?.message || error);
  }
});

async function waitForInitialPanel() {
  const id = initialPanelId();
  if (id) {
    try {
      await waitForElement(id, 5000);
    } catch {
      // 新页面可能没有旧 panel；继续加载脚本由页面自行处理
    }
  }
}

async function waitForCurrentPanel() {
  const id = currentPanelId();
  if (!id) return;
  try {
    await waitForElement(id);
  } catch {
    // 参考旧页面壳缺失时不阻塞路由
  }
}

function currentPanelId() {
  if (workspace.value === 'vanilla') return route.meta.serverPanel || 'server-config-panel';
  if (workspace.value === 'mods') return 'panel-config-editor';
  const panel = route.meta.panel;
  return panel ? 'panel-' + panel : '';
}

function initialPanelId() {
  if (route.meta.server) return route.meta.serverPanel || 'server-config-panel';
  if (workspace.value === 'vanilla') return route.meta.serverPanel || 'server-config-panel';
  if (workspace.value === 'mods') return 'panel-config-editor';
  const panel = route.meta.panel;
  return panel ? 'panel-' + panel : '';
}

async function applyRoute(force = false) {
  if (!window.SamsaraUI) return;
  const key = [workspace.value, page.value, route.meta.serverPanel || ''].join(':');
  if (force || key !== lastApplied) {
    lastApplied = key;
    if (workspace.value === 'vanilla') {
      window.SamsaraServer?.showPanel?.(route.meta.serverPanel || 'server-config-panel');
    } else if (workspace.value === 'mods') {
      window.SamsaraServer?.leaveServer?.();
      window.SamsaraConfigEditor?.setNavEnabled?.(false);
      hideLegacyChrome();
    } else {
      window.SamsaraServer?.leaveServer?.();
      if (route.meta.panel) {
        applyLegacyPanel(route.meta.panel);
      } else {
        hideLegacyChrome();
      }
    }
  }
  if (route.path) {
    rememberTarget(workspace.value, activeSection.value, route.path);
    if (workspace.value === 'mods' && activeModId.value) {
      rememberTarget('mods.' + activeModId.value, route.path);
    }
  }
}

function applyLegacyPanel(name) {
  if (!name) return;
  const fn = originalSetActiveTab || window.SamsaraUI?.setActiveTab;
  if (typeof fn === 'function') fn(name);
}

function hideLegacyChrome() {
  for (const id of ['mods-actions', 'server-actions']) {
    const node = document.getElementById(id);
    if (node) node.hidden = true;
  }
  const nav = document.getElementById('global-nav');
  if (nav) {
    nav.replaceChildren();
    nav.hidden = true;
  }
  const shell = document.querySelector('main.app-shell');
  if (shell) shell.classList.add('no-nav');
}

function installLegacyTabBridge() {
  if (!originalSetActiveTab || originalSetActiveTab.__samsaraIaBridge) return;
  const bridge = (name) => {
    originalSetActiveTab(name);
    const target = LEGACY_ROUTES[name];
    if (target) navigate(target);
  };
  bridge.__samsaraIaBridge = true;
  window.SamsaraUI.setActiveTab = bridge;
}
</script>
