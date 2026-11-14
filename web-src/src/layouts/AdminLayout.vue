<template>
  <div class="admin-layout ia-layout">
    <AppHeader
      :crumbs="crumbs"
      :workspace-short="workspaceShort"
      :server-mode="serverMode"
      :hero-text-only="heroTextOnly"
      :server-title="serverHero.title"
      :server-hint="serverHero.hint"
      :active-server-panel="actingServerPanel"
    />

    <WorkspaceRail
      ref="railRef"
      :workspaces="WORKSPACES"
      :mods="mods"
      :active-workspace="activeWorkspace"
      :active-mod-id="activeModId"
      @select-workspace="goWorkspace"
      @select-mod="goMod"
    />

    <div class="ia-body">
      <div class="ia-main">
        <SectionNav
          :sections="sections"
          :active-section="activeSection"
          :subnav-items="subnavItems"
          :active-subpage="activeSubpage"
          :section-label="sectionLabel"
          @select-section="goSection"
          @navigate="navigate"
        />

        <main class="app-shell" :class="{ 'no-nav': !route.meta.panel && route.meta.page !== 'server' }">
          <div class="ia-side">
            <PageNav />
            <aside id="global-nav" class="side-nav" aria-label="页内导航"></aside>
          </div>
          <div class="content">
            <!-- 路由是页面选择的唯一来源；需要保留状态的页面由 KEEP_ALIVE_PAGES 精确控制。 -->
            <router-view v-slot="{ Component }">
              <keep-alive :include="KEEP_ALIVE_PAGES">
                <component :is="Component" />
              </keep-alive>
            </router-view>
          </div>
        </main>
      </div>
    </div>

    <PageScrollbar ref="scrollbarRef" />

    <div ref="overlayHost" class="ia-overlay-host"><AdminOverlays /></div>
    <IdentityInventoryDialog v-if="workspace === 'samsara'" />
    <GlobalInventoryDrawer v-if="workspace === 'samsara'" />
    <FrameworkDialogHost />
    <GlobalItemCursor />
    <div v-if="loadError" class="container-message error" style="margin: 20px">
      {{ loadError }}
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { KEEP_ALIVE_PAGES, LEGACY_ALIASES, WORKSPACES } from '../navigation.js';
import { waitForElement } from '../legacy/document.js';
import { loadLegacyAdminScripts } from '../legacy/admin.js';
import { useApi } from '../composables/useApi.js';
import { useAdminNavigation } from '../composables/useAdminNavigation.js';
import { useMods } from '../composables/useMods.js';
import AppHeader from './AppHeader.vue';
import AdminOverlays from './AdminOverlays.vue';
import WorkspaceRail from './WorkspaceRail.vue';
import SectionNav from './SectionNav.vue';
import PageScrollbar from './PageScrollbar.vue';
import PageNav from '../components/PageNav.vue';
import { pageNavState } from '../composables/usePageNav.js';
import IdentityInventoryDialog from '../components/IdentityInventoryDialog.vue';
import GlobalInventoryDrawer from '../components/GlobalInventoryDrawer.vue';
import FrameworkDialogHost from '../components/FrameworkDialogHost.vue';
import GlobalItemCursor from '../components/GlobalItemCursor.vue';

const route = useRoute();
const router = useRouter();
const { request } = useApi();
const overlayHost = ref(null);
const railRef = ref(null);
const scrollbarRef = ref(null);
const loadError = ref('');
const heroTextOnly = ref(false);

const { mods, loadMods } = useMods();
const {
  workspace,
  page,
  activeWorkspace,
  activeModId,
  activeSection,
  sections,
  sectionLabel,
  workspaceShort,
  crumbs,
  subnavItems,
  activeSubpage,
} = useAdminNavigation(route, mods);

const serverMode = computed(() => workspace.value === 'vanilla' || page.value === 'server');
const actingServerPanel = computed(() => route.meta.serverPanel || 'server-config-panel');
const serverHero = computed(() => SERVER_HERO[actingServerPanel.value]
  || { title: String(route.meta.title || '服务器配置'), hint: '' });

let started = false;
let lastApplied = '';
let navTimer = 0;
let originalSetActiveTab = null;
let legacyNavGuard = 0;

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

function animateContent() {
  const content = document.querySelector('.content');
  if (!content) return;
  content.classList.remove('page-enter');
  // 强制回流，保证连续切页时动画可以重新触发。
  void content.offsetWidth;
  content.classList.add('page-enter');
}

async function refreshWebVersion() {
  try {
    const result = await request('/api/status');
    if (!result.ok) return;
    const node = document.getElementById('app-version');
    if (node && result.data.webVersion) node.textContent = 'v' + result.data.webVersion;
  } catch {
    // 状态不可用时保留 v—。
  }
}

onMounted(async () => {
  try {
    await waitForInitialPanel();
    // 旧脚本对 #search 等页面元素已做判空；页面懒挂载时不再等 4 秒。
    await loadLegacyAdminScripts();
    originalSetActiveTab = window.SamsaraUI?.setActiveTab || null;
    installLegacyTabBridge();
    // 连接成功后补一次版本号：首次挂载时还拿不到 token，1.2 秒的兜底可能刚好错过连接完成。
    window.SamsaraUI?.onConnected?.(() => {
      loadMods();
      refreshWebVersion();
      // 原生 Vue 页面没有旧页面钩子，用 DOM 事件通知它们 token 已可用。
      window.dispatchEvent(new CustomEvent('samsara:connected'));
    });
    await nextTick();
    await applyRoute(true);
    started = true;
    measureChrome();
    // 旧脚本初始化时会按 URL hash 回落到默认 panel；等它跑完后检查 hero 是否被改错，
    // 只有确实错位时才重新应用路由，避免打断 RCON 这类刚执行完的命令提示。
    window.setTimeout(() => {
      if (started && !heroMatchesRoute()) applyRoute(true).catch(() => {});
    }, 450);
    railRef.value?.maybePeek();
    refreshWebVersion();
    window.setTimeout(refreshWebVersion, 1200);
    window.setTimeout(loadMods, 400);
    window.addEventListener('resize', measureChrome, { passive: true });
    window.addEventListener('scroll', scheduleMeasure, { passive: true });
    nextTick(() => scrollbarRef.value?.update());
  } catch (error) {
    loadError.value = '管理端加载失败：' + (error?.message || error);
  }
});

onUnmounted(() => {
  window.removeEventListener('resize', measureChrome);
  window.removeEventListener('scroll', scheduleMeasure);
  window.clearTimeout(measureTimer);
  window.clearTimeout(navAlignTimer);
});

watch(() => pageNavState.title, (title) => {
  if (!title) return;
  // Vue 页面自带页内导航时，清掉旧脚本留下的 #global-nav，避免两套导航同时出现。
  const nav = document.getElementById('global-nav');
  if (nav) {
    nav.replaceChildren();
    nav.hidden = true;
  }
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
    nextTick(() => scrollbarRef.value?.update());
  } catch (error) {
    loadError.value = '页面切换失败：' + (error?.message || error);
  }
});

let measureTimer = 0;
let navAlignTimer = 0;

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
  if (resetRail) railRef.value?.collapse();
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
  announcements: '编辑、预览并发送全服公告；支持聊天 / ActionBar / Title / BossBar 与定时、周期触发。',
  permissions: '按命令路径调整权限等级；关闭会级联到后代，owner 名单单独保存并热重载。',
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
function syncServerHero() {
  // 标题与说明由 AppHeader 按路由 meta 绑定，这里只保证 hero 模式正确。
  heroTextOnly.value = false;
}

function syncHeroFromRoute() {
  if (serverMode.value) {
    showHero('server');
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
  const node = document.getElementById(serverMode.value ? 'server-action-title' : 'action-title');
  const current = String(node?.textContent || '').trim();
  const wanted = String(route.meta.title || '').trim();
  if (!wanted) return true;
  return current === wanted || current.includes(wanted) || wanted.includes(current);
}

function showHero(mode, title, hint) {
  // server / page 的显隐由 AppHeader 的 serverMode / heroTextOnly 响应式控制；
  // 旧页面（legacy）的标题与按钮仍由 app.js 的 TAB_ACTIONS 写入 DOM。
  if (mode === 'server') {
    heroTextOnly.value = false;
    syncServerHero();
    return;
  }
  heroTextOnly.value = mode === 'page';
  if (mode === 'page') {
    const titleNode = document.getElementById('action-title');
    const hintNode = document.getElementById('action-hint');
    if (titleNode) titleNode.textContent = title || '';
    if (hintNode) hintNode.textContent = hint || '';
  }
}

async function applyRoute(force = false) {
  if (!window.SamsaraUI) return;
  const key = [workspace.value, page.value, route.meta.serverPanel || '',
    route.meta.subpage || ''].join(':');
  if (force || key !== lastApplied) {
    lastApplied = key;
    if (workspace.value === 'vanilla' || page.value === 'server') {
      // 本体页与 Web 审计都是原生 Vue 页面：面板只由路由 meta.serverPanel 决定。
      document.body.classList.remove('server-section');
      document.querySelector('main.app-shell')?.classList.remove('no-nav');
      syncServerHero();
      if (page.value === 'server' && workspace.value === 'samsara') {
        // 审计页属于 samsara 工作区，不应该套用本体的一级分栏。
        document.body.classList.remove('server-section');
      }
    } else if (workspace.value === 'mods') {
      guarded(() => window.SamsaraServer?.leaveServer?.());
      window.SamsaraConfigEditor?.setSourceFilter?.('third-party');      window.SamsaraConfigEditor?.setNavEnabled?.(false);
      hideLegacyChrome();
      showHero('page', String(route.meta.title || '配置文件'), HERO_HINTS['mod-config']);
    } else {
      guarded(() => window.SamsaraServer?.leaveServer?.());
      if (route.meta.panel === 'config-editor') {
        window.SamsaraConfigEditor?.setSourceFilter?.('samsara');
        window.SamsaraConfigEditor?.setNavEnabled?.(true);
      }
      if (route.meta.panel) {
        document.querySelector('main.app-shell')?.classList.remove('no-nav');
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
  // 旧面板激活会 clearNav 并给 app-shell 加 no-nav；Vue 页面自带页内导航时要恢复。
  if (pageNavState.title) {
    document.querySelector('main.app-shell')?.classList.remove('no-nav');
  }
}

function applyLegacyPanel(name) {
  if (!name) return;
  const fn = originalSetActiveTab || window.SamsaraUI?.setActiveTab;
  if (typeof fn === 'function') fn(name);
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
    // 内部路由应用（applyRoute / leaveServer / showPanel）由 guarded() 包住：
    // 只切旧面板，不再反向导航，否则会从 /mods/* 或天网页跳回经济业务。
    if (legacyNavGuard > 0) {
      originalSetActiveTab(name);
      return;
    }
    const target = LEGACY_ALIASES[name];
    if (target && router.currentRoute.value.path !== target) {
      // 旧 tab 主动切换时先交给路由：等新页面组件与 panel 挂载后，applyRoute 再调用
      // originalSetActiveTab。否则旧脚本会在 DOM 还不存在时先跑一遍激活/加载，
      // 世界地图、世界栈、公告这类缓存了元素引用的页面会拿到 null 而渲染空白。
      navigate(target);
      return;
    }
    originalSetActiveTab(name);
  };
  bridge.__samsaraIaBridge = true;
  window.SamsaraUI.setActiveTab = bridge;
}
</script>
