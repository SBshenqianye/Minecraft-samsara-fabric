<template>
  <div class="admin-layout ia-layout">
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

        <div class="ia-header-context" aria-live="polite">
          <template v-for="(crumb, index) in crumbs" :key="crumb + index">
            <span v-if="index" class="ia-context-sep">/</span>
            <span :class="index === crumbs.length - 1 ? 'ia-context-page' : 'ia-context-workspace'">
              {{ crumb }}
            </span>
          </template>
        </div>

        <div class="connection">
          <span id="connection-state" class="conn-state">未连接</span>
          <a class="user-view-link" href="/user" target="_blank" rel="noopener"
              title="公开只读信息，不需要 token">用户视图</a>
        </div>
      </div>

      <div id="mods-actions" class="tab-actions ia-actions">
        <div class="tab-actions-title">
          <strong id="action-title">配置</strong>
          <span id="action-hint" class="muted small"></span>
        </div>
        <div class="tab-actions-buttons">
          <button id="action-default" class="ghost" type="button" hidden>恢复默认</button>
          <button id="reload" class="ghost" type="button">重新读取</button>
          <button id="save" class="primary" type="button">保存并重载</button>
        </div>
      </div>

      <div id="server-actions" class="tab-actions ia-actions" hidden>
        <div class="tab-actions-title">
          <strong id="server-action-title">服务器配置</strong>
          <span id="server-action-hint" class="muted small"></span>
        </div>
        <div class="tab-actions-buttons">
          <button id="server-reload" class="ghost" type="button">重新读取</button>
          <button id="server-save" class="primary" type="button">保存并重载</button>
          <button id="server-backup-create" class="primary" type="button" hidden>创建备份</button>
        </div>
      </div>

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
    <div class="ia-body">
      <nav
        class="workspace-rail"
        :class="{ collapsed: !railPinned && !railPeek, peek: railPeek, pinned: railPinned }"
        aria-label="顶级工作区"
        @mouseenter="railPeek = !railPinned"
        @mouseleave="railPeek = false"
      >
        <div class="rail-head">
          <span class="rail-title">工作区</span>
          <button
            class="rail-toggle"
            type="button"
            :aria-pressed="railPinned ? 'true' : 'false'"
            :title="railPinned ? '收起工作区导航' : '固定工作区导航'"
            @click="toggleRail"
          >{{ railPinned ? '«' : '»' }}</button>
        </div>
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
          <span class="rail-label">{{ mod.label }}</span>
          <span class="rail-hint">{{ mod.hint }}</span>
        </button>
        <p v-if="!mods.length" class="rail-empty">连接后显示有配置的模组</p>
      </nav>

      <div class="ia-main">
        <div class="ia-crumbs" aria-label="面包屑">
          <template v-for="(crumb, index) in crumbs" :key="'crumb-' + crumb + index">
            <span v-if="index" class="ia-crumb-sep">/</span>
            <span :class="index === crumbs.length - 1 ? 'ia-crumb-title' : 'ia-crumb-part'">
              {{ crumb }}
            </span>
          </template>
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

        <main class="app-shell" :class="{ 'has-subnav': subnavItems.length > 0 }">
          <div class="ia-side">
            <nav v-if="subnavItems.length" class="section-subnav" aria-label="三级页面">
              <div class="subnav-title">{{ sectionLabel }}</div>
              <button
                v-for="item in subnavItems"
                :key="item.id"
                class="subnav-item"
                :class="{ active: item.id === activeSubpage }"
                type="button"
                :data-subnav="item.id"
                @click="navigate(item.target)"
              >
                {{ item.label }}
              </button>
            </nav>
            <aside id="global-nav" class="side-nav" aria-label="页内导航"></aside>
          </div>
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
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { SECTION_NAV } from '../router.js';
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
const overlayHost = ref(null);
const loadError = ref('');
const mods = ref([]);
const railPinned = ref(readRailPinned());
const railPeek = ref(false);

const baseWorkspaces = [
  { id: 'vanilla', label: '本体', hint: 'Minecraft / Vanilla' },
  { id: 'samsara', label: '周目管理', hint: 'samsara' },
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
    { id: 'config', label: '服务器配置', target: '/vanilla/config' },
    { id: 'backup', label: '存档备份', target: '/vanilla/backup' },
    { id: 'console', label: 'RCON', target: '/vanilla/console' },
    { id: 'audit', label: 'Web 审计', target: '/vanilla/audit' },
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
  return route.meta.section || (workspace.value === 'vanilla' ? 'config' : 'economy');
});
const sections = computed(() => {
  if (workspace.value === 'mods') return [];
  return SECTIONS[workspace.value] || [];
});
const sectionLabel = computed(() => {
  if (workspace.value === 'mods') return modDisplayName.value;
  return sections.value.find((item) => item.id === activeSection.value)?.label
    || String(route.meta.title || '');
});
const pageLabel = computed(() => {
  if (workspace.value === 'mods') return String(route.meta.title || '配置文件');
  const title = String(route.meta.title || '');
  return title && title !== sectionLabel.value ? title : '';
});
const workspaceShort = computed(() => {
  if (workspace.value === 'vanilla') return '本体';
  if (workspace.value === 'mods') return '其他模组';
  return 'samsara';
});
const crumbs = computed(() => {
  const list = [workspaceShort.value];
  if (workspace.value === 'mods') {
    list.push(modDisplayName.value || activeModId.value || '其他模组');
    list.push(pageLabel.value || '配置文件');
  } else {
    if (sectionLabel.value) list.push(sectionLabel.value);
    if (pageLabel.value) list.push(pageLabel.value);
  }
  return list;
});
const subnavItems = computed(() => {
  if (workspace.value !== 'samsara') return [];
  return SECTION_NAV[activeSection.value] || [];
});
const activeSubpage = computed(() => {
  const current = subnavItems.value.find((item) => item.target === route.path);
  if (current) return current.id;
  return String(route.meta.page || '');
});
const modDisplayName = computed(() => {
  const mod = mods.value.find((item) => item.id === activeModId.value);
  return mod ? mod.label : (activeModId.value || '其他模组');
});
const workspaceLabel = computed(() => workspaceShort.value);
const pageTitle = computed(() => pageLabel.value || sectionLabel.value);

let started = false;
let lastApplied = '';
let navTimer = 0;
let originalSetActiveTab = null;
let modsRetry = 0;
let legacyNavGuard = 0;
let railTimer = 0;

function readRailPinned() {
  try {
    return localStorage.getItem('samsara.ia.rail') === 'pinned';
  } catch {
    return false;
  }
}

function storeRailPinned() {
  try {
    localStorage.setItem('samsara.ia.rail', railPinned.value ? 'pinned' : 'collapsed');
  } catch {
    // localStorage 不可用时只保留本次状态
  }
}

function toggleRail() {
  railPinned.value = !railPinned.value;
  railPeek.value = false;
  storeRailPinned();
}

function maybePeekRail() {
  if (railPinned.value) return;
  try {
    const today = new Date().toISOString().slice(0, 10);
    if (localStorage.getItem('samsara.ia.rail.day') === today) return;
    localStorage.setItem('samsara.ia.rail.day', today);
  } catch {
    // 忽略
  }
  window.clearTimeout(railTimer);
  railTimer = window.setTimeout(() => { railPeek.value = true; }, 350);
  railTimer = window.setTimeout(() => { railPeek.value = false; }, 3000);
}

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

function hasCjk(text) {
  return /[\u3400-\u9fff]/.test(String(text || ''));
}

async function loadMods() {
  const ui = window.SamsaraUI;
  if (!ui?.api) return;
  try {
    const data = await ui.api('/api/config/pages');
    const names = data.modNames || {};
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
      .map(([id, pages]) => {
        const name = String(names[id] || names[id.toLowerCase()] || '').trim();
        const chinese = hasCjk(name) ? name : '';
        return {
          id,
          pages,
          label: chinese || id,
          hint: chinese ? id : pages.length + ' 个配置文件',
        };
      })
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
    for (const node of Array.from(doc.body.childNodes)) {
      if (node.nodeType === Node.ELEMENT_NODE
          && (node.tagName === 'HEADER' || node.tagName === 'MAIN'
            || node.tagName === 'SCRIPT')) {
        continue;
      }
      if (node.nodeType === Node.ELEMENT_NODE && node.id === 'app-skeleton') continue;
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
    measureChrome();
    maybePeekRail();
    window.setTimeout(loadMods, 400);
    window.addEventListener('resize', measureChrome, { passive: true });
    window.addEventListener('scroll', scheduleMeasure, { passive: true });
  } catch (error) {
    loadError.value = '管理端加载失败：' + (error?.message || error);
  }
});

onUnmounted(() => {
  window.removeEventListener('resize', measureChrome);
  window.removeEventListener('scroll', scheduleMeasure);
  window.clearTimeout(railTimer);
  window.clearTimeout(measureTimer);
});

watch(() => route.path, async () => {
  if (!started) return;
  try {
    await nextTick();
    await waitForCurrentPanel();
    await applyRoute();
    measureChrome();
  } catch (error) {
    loadError.value = '页面切换失败：' + (error?.message || error);
  }
});

let measureTimer = 0;
function scheduleMeasure() {
  if (measureTimer) return;
  measureTimer = window.setTimeout(() => {
    measureTimer = 0;
    measureChrome();
  }, 120);
}

function measureChrome() {
  const header = document.querySelector('.ia-header');
  const section = document.querySelector('.section-tabs');
  const headerH = header ? Math.round(header.getBoundingClientRect().height) : 128;
  const sectionH = section ? Math.round(section.getBoundingClientRect().height) : 56;
  const root = document.documentElement;
  root.style.setProperty('--header-h', headerH + 'px');
  root.style.setProperty('--ia-section-h', sectionH + 'px');
  root.style.setProperty('--ia-nav-top', (headerH + sectionH + 12) + 'px');
}

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

function guarded(fn) {
  legacyNavGuard++;
  try {
    return fn();
  } finally {
    legacyNavGuard = Math.max(0, legacyNavGuard - 1);
  }
}

async function applyRoute(force = false) {
  if (!window.SamsaraUI) return;
  const key = [workspace.value, page.value, route.meta.serverPanel || ''].join(':');
  if (force || key !== lastApplied) {
    lastApplied = key;
    if (workspace.value === 'vanilla') {
      guarded(() => window.SamsaraServer?.showPanel?.(route.meta.serverPanel || 'server-config-panel'));
    } else if (workspace.value === 'mods') {
      guarded(() => window.SamsaraServer?.leaveServer?.());
      window.SamsaraConfigEditor?.setSourceFilter?.('third-party');
      window.SamsaraConfigEditor?.setNavEnabled?.(false);
      hideLegacyChrome();
    } else {
      guarded(() => window.SamsaraServer?.leaveServer?.());
      if (route.meta.panel === 'config-editor') {
        window.SamsaraConfigEditor?.setSourceFilter?.('samsara');
        window.SamsaraConfigEditor?.setNavEnabled?.(true);
      }
      if (route.meta.panel) {
        guarded(() => applyLegacyPanel(route.meta.panel));
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
  const actions = document.getElementById('mods-actions');
  if (actions) actions.hidden = false;
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
    // 内部路由应用（applyRoute / leaveServer / showPanel）由 guarded() 包住，
    // 这里的导航必须被抑制，否则会从 /mods/* 或天网页跳回经济业务。
    if (legacyNavGuard > 0) return;
    const target = LEGACY_ROUTES[name];
    if (target) navigate(target);
  };
  bridge.__samsaraIaBridge = true;
  window.SamsaraUI.setActiveTab = bridge;
}
</script>
