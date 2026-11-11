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

        <div id="mods-actions" class="page-hero-main">
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

        <div id="server-actions" class="page-hero-main" hidden>
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

    <nav
      ref="railEl"
      class="workspace-rail"
      :class="{ collapsed: !railOpen, peek: railOpen && !railPinned, pinned: railPinned }"
      aria-label="顶级工作区"
      @mouseenter="onRailEnter"
    >
      <div class="rail-head">
        <span class="rail-title">工作区</span>
        <button
          class="rail-toggle"
          type="button"
          :aria-pressed="railOpen ? 'true' : 'false'"
          :title="railOpen ? '收起工作区导航' : '展开工作区导航'"
          @click="toggleRail"
        >{{ railOpen ? '«' : '»' }}</button>
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

    <button
      v-show="!railOpen && !railClosing"
      ref="railHandle"
      class="rail-handle"
      type="button"
      aria-label="展开工作区导航"
      @mouseenter="onRailEnter"
      @click="onRailEnter"
    >工作区 ›</button>

    <div class="ia-body">
      <div class="ia-main">
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

        <main class="app-shell">
          <div class="ia-side">
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

    <div
      ref="pageScrollbar"
      class="page-scrollbar"
      :class="{ visible: scrollbarVisible }"
      @click="onScrollbarTrackClick"
    >
      <div
        ref="pageScrollbarThumb"
        class="page-scrollbar-thumb"
        @pointerdown="onScrollbarPointerDown"
      ></div>
    </div>

    <div ref="overlayHost" class="ia-overlay-host"></div>
    <IdentityInventoryDialog v-if="workspace === 'samsara'" />
    <GlobalInventoryDrawer v-if="workspace === 'samsara'" />
    <GlobalItemCursor />
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
import PoolInventoryPage from '../pages/PoolInventoryPage.vue';
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
import IdentityInventoryDialog from '../components/IdentityInventoryDialog.vue';
import GlobalInventoryDrawer from '../components/GlobalInventoryDrawer.vue';
import GlobalItemCursor from '../components/GlobalItemCursor.vue';

const route = useRoute();
const router = useRouter();
const overlayHost = ref(null);
const pageScrollbar = ref(null);
const pageScrollbarThumb = ref(null);
const scrollbarVisible = ref(false);
const loadError = ref('');
const mods = ref([]);
const railPinned = ref(readRailPinned());
const railOpen = ref(false);
const railClosing = ref(false);
const railEl = ref(null);
const railHandle = ref(null);

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
    { id: 'system', label: '系统', target: '/samsara/system/audit' },
  ],
  vanilla: [
    { id: 'config', label: '服务器配置', target: '/vanilla/config' },
    { id: 'backup', label: '存档备份', target: '/vanilla/backup' },
    { id: 'console', label: 'RCON', target: '/vanilla/console' },
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
    const section = sectionLabel.value;
    const current = pageLabel.value;
    // “RCON / RCON 控制台”这类父子同名面包屑合并成一项。
    if (section && current
        && (current.startsWith(section + ' ') || section.startsWith(current + ' '))) {
      list.push(current);
    } else {
      if (section) list.push(section);
      if (current) list.push(current);
    }
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
let railCloseTimer = 0;
let scrollbarHideTimer = 0;
let scrollbarDragging = false;
let scrollbarStartY = 0;
let scrollbarStartScroll = 0;
let scrollbarTrackH = 0;
let scrollbarThumbH = 0;
let scrollbarObserver = null;
let lastPointerX = -1;
let lastPointerY = -1;
let railIgnoreOutsideUntil = 0;

function readRailPinned() {
  // 工作区导航每次进入都默认收起；展开只用于本次悬停/点击。
  return false;
}

function storeRailPinned() {
  try {
    localStorage.setItem('samsara.ia.rail', railPinned.value ? 'pinned' : 'collapsed');
  } catch {
    // localStorage 不可用时只保留本次状态
  }
}

function rectContains(rect, x, y, pad = 8) {
  return Boolean(rect
    && x >= rect.left - pad && x <= rect.right + pad
    && y >= rect.top - pad && y <= rect.bottom + pad);
}

function pointerInsideRail(x, y) {
  const rail = railEl.value?.getBoundingClientRect();
  const zone = rail ? {
    left: 0,
    right: Math.max(rail.right, 210),
    top: rail.top,
    bottom: rail.bottom,
  } : null;
  return rectContains(zone, x, y)
    || rectContains(railHandle.value?.getBoundingClientRect(), x, y);
}

function openRail(pinned = false) {
  window.clearTimeout(railCloseTimer);
  window.clearTimeout(railTimer);
  railClosing.value = false;
  railOpen.value = true;
  // 展开动画期间不要因为指针还在左侧滑动区就判定为“离开”。
  railIgnoreOutsideUntil = performance.now() + 300;
  if (pinned) railPinned.value = true;
}

function collapseRail() {
  window.clearTimeout(railTimer);
  if (!railOpen.value && !railClosing.value) return;
  // 点击页面后焦点可能还在导航按钮上；先移走焦点，避免 :focus-visible 把导航重新撑开。
  const active = document.activeElement;
  if (active && railEl.value && railEl.value.contains(active) && typeof active.blur === 'function') {
    active.blur();
  }
  railOpen.value = false;
  railPinned.value = false;
  storeRailPinned();
  // 等收回动画结束再显示“工作区”把手，避免把手和导航同时出现。
  railClosing.value = true;
  window.clearTimeout(railCloseTimer);
  railCloseTimer = window.setTimeout(() => {
    railClosing.value = false;
  }, 240);
}

function toggleRail() {
  if (railOpen.value) {
    collapseRail();
    return;
  }
  openRail(true);
  storeRailPinned();
}

function maybePeekRail() {
  if (railOpen.value) return;
  try {
    const today = new Date().toISOString().slice(0, 10);
    if (localStorage.getItem('samsara.ia.rail.day') === today) return;
    localStorage.setItem('samsara.ia.rail.day', today);
  } catch {
    // 忽略
  }
  window.clearTimeout(railTimer);
  railTimer = window.setTimeout(() => {
    openRail(false);
    railTimer = window.setTimeout(() => {
      if (!pointerInsideRail(lastPointerX, lastPointerY)) collapseRail();
    }, 3000);
  }, 350);
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
    const stored = localStorage.getItem(lastKey(key));
    return stored && stored !== 'undefined' ? stored : fallback;
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

function animateContent() {
  const content = document.querySelector('.content');
  if (!content) return;
  content.classList.remove('page-enter');
  // 强制回流，保证连续切页时动画可以重新触发。
  void content.offsetWidth;
  content.classList.add('page-enter');
}

function updatePageScrollbar() {
  const bar = pageScrollbar.value;
  const thumb = pageScrollbarThumb.value;
  if (!bar || !thumb) return;
  const doc = document.documentElement;
  const viewH = window.innerHeight || doc.clientHeight;
  const scrollH = doc.scrollHeight;
  if (scrollH <= viewH + 1) {
    scrollbarVisible.value = false;
    return;
  }
  const trackH = Math.max(1, bar.clientHeight);
  const thumbH = Math.max(36, Math.round(trackH * viewH / scrollH));
  const maxTop = Math.max(1, trackH - thumbH);
  const ratio = Math.min(1, Math.max(0, window.scrollY / Math.max(1, scrollH - viewH)));
  thumb.style.height = thumbH + 'px';
  thumb.style.transform = 'translateY(' + Math.round(ratio * maxTop) + 'px)';
  scrollbarTrackH = trackH;
  scrollbarThumbH = thumbH;
}

function showPageScrollbar() {
  scrollbarVisible.value = true;
  window.clearTimeout(scrollbarHideTimer);
  scrollbarHideTimer = window.setTimeout(() => {
    if (!scrollbarDragging) scrollbarVisible.value = false;
  }, 1200);
}

function onPageScroll() {
  updatePageScrollbar();
  showPageScrollbar();
}

function onScrollbarPointerDown(event) {
  if (!pageScrollbar.value || !pageScrollbarThumb.value) return;
  scrollbarDragging = true;
  scrollbarStartY = event.clientY;
  scrollbarStartScroll = window.scrollY;
  updatePageScrollbar();
  pageScrollbarThumb.value.setPointerCapture?.(event.pointerId);
  event.preventDefault();
}

function onScrollbarPointerMove(event) {
  if (!scrollbarDragging) return;
  const doc = document.documentElement;
  const maxScroll = Math.max(1, doc.scrollHeight - window.innerHeight);
  const maxTop = Math.max(1, scrollbarTrackH - scrollbarThumbH);
  const delta = (event.clientY - scrollbarStartY) / maxTop * maxScroll;
  window.scrollTo(0, Math.max(0, Math.min(maxScroll, scrollbarStartScroll + delta)));
  updatePageScrollbar();
  showPageScrollbar();
}

function onScrollbarPointerUp() {
  if (!scrollbarDragging) return;
  scrollbarDragging = false;
  showPageScrollbar();
}

function onScrollbarTrackClick(event) {
  if (event.target === pageScrollbarThumb.value) return;
  const bar = pageScrollbar.value;
  if (!bar) return;
  const trackH = Math.max(1, bar.clientHeight);
  const ratio = Math.min(1, Math.max(0, (event.clientY - bar.getBoundingClientRect().top) / trackH));
  window.scrollTo(0,
      ratio * Math.max(0, document.documentElement.scrollHeight - window.innerHeight));
  showPageScrollbar();
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
    // 旧脚本初始化时会按 URL hash 回落到默认 panel；等它跑完后检查 hero 是否被改错，
    // 只有确实错位时才重新应用路由，避免打断 RCON 这类刚执行完的命令提示。
    window.setTimeout(() => {
      if (started && !heroMatchesRoute()) applyRoute(true).catch(() => {});
    }, 450);
    maybePeekRail();
    window.setTimeout(loadMods, 400);
    window.addEventListener('resize', measureChrome, { passive: true });
    window.addEventListener('scroll', scheduleMeasure, { passive: true });
    document.addEventListener('pointermove', onGlobalPointerMove, { passive: true });
    window.addEventListener('blur', onWindowBlur);
    window.addEventListener('scroll', onPageScroll, { passive: true });
    window.addEventListener('resize', updatePageScrollbar);
    window.addEventListener('pointermove', onScrollbarPointerMove);
    window.addEventListener('pointerup', onScrollbarPointerUp);
    if (window.ResizeObserver) {
      scrollbarObserver = new ResizeObserver(updatePageScrollbar);
      scrollbarObserver.observe(document.body);
    }
    nextTick(updatePageScrollbar);
  } catch (error) {
    loadError.value = '管理端加载失败：' + (error?.message || error);
  }
});

onUnmounted(() => {
  window.removeEventListener('resize', measureChrome);
  window.removeEventListener('scroll', scheduleMeasure);
  document.removeEventListener('pointermove', onGlobalPointerMove);
  window.removeEventListener('blur', onWindowBlur);
  window.removeEventListener('scroll', onPageScroll);
  window.removeEventListener('resize', updatePageScrollbar);
  window.removeEventListener('pointermove', onScrollbarPointerMove);
  window.removeEventListener('pointerup', onScrollbarPointerUp);
  scrollbarObserver?.disconnect();
  window.clearTimeout(railTimer);
  window.clearTimeout(railCloseTimer);
  window.clearTimeout(measureTimer);
  window.clearTimeout(navAlignTimer);
  window.clearTimeout(scrollbarHideTimer);
});

watch(() => route.path, async () => {
  if (!started) return;
  // 先同步 hero，再等旧 panel 激活；避免标题/按钮晚一步更新造成闪动。
  syncHeroFromRoute();
  try {
    await nextTick();
    await waitForCurrentPanel();
    await applyRoute();
    // 切页不重置工作区导航；只有鼠标离开/窗口缩放才收回。
    measureChrome(false);
    animateContent();
    nextTick(updatePageScrollbar);
  } catch (error) {
    loadError.value = '页面切换失败：' + (error?.message || error);
  }
});

let measureTimer = 0;
let navAlignTimer = 0;

function onGlobalPointerMove(event) {
  lastPointerX = event.clientX;
  lastPointerY = event.clientY;
  if (!railOpen.value) return;
  if (performance.now() < railIgnoreOutsideUntil) return;
  if (pointerInsideRail(lastPointerX, lastPointerY)) {
    window.clearTimeout(railCloseTimer);
    return;
  }
  // 用一个短延迟做二次确认，避免鼠标从把手移到导航、或动画途中误判。
  window.clearTimeout(railCloseTimer);
  railCloseTimer = window.setTimeout(() => {
    if (railOpen.value && !pointerInsideRail(lastPointerX, lastPointerY)) {
      collapseRail();
    }
  }, 140);
}

function onRailEnter() {
  openRail(false);
}

function onWindowBlur() {
  window.clearTimeout(railCloseTimer);
  collapseRail();
}

function scheduleMeasure() {
  if (measureTimer) return;
  measureTimer = window.setTimeout(() => {
    measureTimer = 0;
    measureChrome(false);
  }, 120);
}

function measureChrome(resetRail = true) {
  const header = document.querySelector('.ia-header');
  const section = document.querySelector('.section-tabs');
  const headerH = header ? Math.round(header.getBoundingClientRect().height) : 128;
  const sectionH = section ? Math.round(section.getBoundingClientRect().height) : 56;
  const root = document.documentElement;
  root.style.setProperty('--header-h', headerH + 'px');
  root.style.setProperty('--ia-section-h', sectionH + 'px');
  // 浏览器窗口拖动/缩放时，收起状态可能停在“悬停展开”：
  // 统一在尺寸变化后收回，避免变成无法收回的浮层。
  if (resetRail) collapseRail();
  alignSideNav(headerH, sectionH);
}

/**
 * 左侧页面导航与工作区导航固定在二级栏下方，不再跟随内容卡片标题滚动，
 * 避免滚动时导航上下跳动。
 */
function alignSideNav(headerH, sectionH) {
  const root = document.documentElement;
  root.style.setProperty('--ia-nav-top', Math.round(headerH + sectionH + 12) + 'px');
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
  if (page.value === 'server') return route.meta.serverPanel || 'server-config-panel';
  if (workspace.value === 'mods') return 'panel-config-editor';
  const panel = route.meta.panel;
  return panel ? 'panel-' + panel : '';
}

function initialPanelId() {
  if (route.meta.server) return route.meta.serverPanel || 'server-config-panel';
  if (page.value === 'server') return route.meta.serverPanel || 'server-config-panel';
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

const HERO_HINTS = {
  track: '查看最近可回看的玩家与时段；真正的回放仍在游戏内执行。',
  diff: '只读回看区块差异；复用现有 Diff API，不重写回放算法。',
  containers: '维度/坐标/半径/类型搜索，读取与编辑容器槽位；写入前保存审计快照并校验 revision。',
  inventory: '在线玩家可读可写；离线玩家只读，写入进入安全队列，登录或停服前应用。',
  'mod-config': '第三方模组配置；写入前预览并自动备份，保存后可热重载。',
};

const SERVER_HERO = {
  'server-config-panel': {
    title: '服务器配置',
    hint: '保存会写入所有已修改文件并热重载；properties/eula 需重启',
  },
  'server-backup-panel': {
    title: '存档备份',
    hint: '时间线可回滚到任意节点；恢复后下一次备份从该点分出新分支',
  },
  'server-console-panel': {
    title: 'RCON 控制台',
    hint: '在服务器线程执行命令；stop 会关闭服务器',
  },
  'server-audit-panel': {
    title: 'Web 审计（全部管理端写请求）',
    hint: '覆盖 samsara、其他模组、服务器配置与 RCON；写前快照，配置类可回滚',
  },
};

/** 路由变化时同步更新 hero，避免等内容 panel 激活后才更新造成闪动。 */
function syncHeroFromRoute() {
  const serverMode = workspace.value === 'vanilla' || page.value === 'server';
  if (serverMode) {
    showHero('server');
    const panel = route.meta.serverPanel || 'server-config-panel';
    const meta = SERVER_HERO[panel];
    if (meta) {
      const title = document.getElementById('server-action-title');
      const hint = document.getElementById('server-action-hint');
      if (title) title.textContent = meta.title;
      if (hint) hint.textContent = meta.hint;
    }
    return;
  }
  if (workspace.value === 'mods') {
    showHero('page', String(route.meta.title || '配置文件'), HERO_HINTS['mod-config']);
    return;
  }
  const panel = route.meta.panel;
  if (panel) {
    const actions = window.SamsaraTabActions?.[panel];
    if (actions) {
      showHero('legacy');
      const title = document.getElementById('action-title');
      const hint = document.getElementById('action-hint');
      if (title) title.textContent = actions.title || '';
      if (hint) hint.textContent = actions.hint || '';
      const save = document.getElementById('save');
      if (save) {
        save.textContent = actions.save || '保存并重载';
        save.hidden = !actions.save;
      }
      const def = document.getElementById('action-default');
      if (def) def.hidden = panel !== 'output';
    } else {
      showHero('page', String(route.meta.title || ''), '');
    }
    return;
  }
  showHero('page', String(route.meta.title || ''),
      HERO_HINTS[String(route.meta.page || '')] || '');
}

/**
 * 统一顶部 hero 横幅：旧页面由 TAB_ACTIONS 写标题/说明并显示保存按钮；
 * Vue 页面由路由 meta + HERO_HINTS 写标题/说明，页面自己的按钮留在内容区。
 */
/** 旧脚本初始化后是否把 hero 覆盖成了别的页面（常见于深链接刷新回落到高级配置）。 */
function heroMatchesRoute() {
  const serverMode = workspace.value === 'vanilla' || page.value === 'server';
  const node = document.getElementById(serverMode ? 'server-action-title' : 'action-title');
  const current = String(node?.textContent || '').trim();
  const wanted = String(route.meta.title || '').trim();
  if (!wanted) return true;
  return current === wanted || current.includes(wanted) || wanted.includes(current);
}

function showHero(mode, title, hint) {
  const mods = document.getElementById('mods-actions');
  const server = document.getElementById('server-actions');
  if (!mods || !server) return;
  const serverMode = mode === 'server';
  mods.hidden = serverMode;
  server.hidden = !serverMode;
  if (serverMode) return;
  if (mode === 'page') {
    mods.classList.add('hero-text-only');
    const titleNode = document.getElementById('action-title');
    const hintNode = document.getElementById('action-hint');
    if (titleNode) titleNode.textContent = title || '';
    if (hintNode) hintNode.textContent = hint || '';
  } else {
    mods.classList.remove('hero-text-only');
  }
}

async function applyRoute(force = false) {
  if (!window.SamsaraUI) return;
  const key = [workspace.value, page.value, route.meta.serverPanel || '',
    route.meta.subpage || ''].join(':');
  if (force || key !== lastApplied) {
    lastApplied = key;
    if (workspace.value === 'vanilla' || page.value === 'server') {
      guarded(() => window.SamsaraServer?.showPanel?.(route.meta.serverPanel || 'server-config-panel'));
      showHero('server');
      if (page.value === 'server' && workspace.value === 'samsara') {
        // 审计页属于 samsara 工作区，不要显示“本体”的旧一级分栏样式。
        document.body.classList.remove('server-section');
      }
    } else if (workspace.value === 'mods') {
      guarded(() => window.SamsaraServer?.leaveServer?.());
      window.SamsaraConfigEditor?.setSourceFilter?.('third-party');
      window.SamsaraConfigEditor?.setNavEnabled?.(false);
      hideLegacyChrome();
      showHero('page', String(route.meta.title || '配置文件'), HERO_HINTS['mod-config']);
    } else {
      guarded(() => window.SamsaraServer?.leaveServer?.());
      if (route.meta.panel === 'config-editor') {
        window.SamsaraConfigEditor?.setSourceFilter?.('samsara');
        window.SamsaraConfigEditor?.setNavEnabled?.(true);
      }
      if (route.meta.panel) {
        guarded(() => applyLegacyPanel(route.meta.panel));
        showHero('legacy');
        if (route.meta.panel === 'lottery') {
          window.samsaraLottery?.setTab?.(String(route.meta.subpage || 'overview'));
        }
      } else {
        hideLegacyChrome();
        showHero('page', String(route.meta.title || ''),
            HERO_HINTS[String(route.meta.page || '')] || '');
      }
    }
  }
  if (route.path) {
    rememberTarget(workspace.value, activeSection.value, route.path);
    if (workspace.value === 'mods' && activeModId.value) {
      rememberTarget('mods.' + activeModId.value, 'files', route.path);
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
