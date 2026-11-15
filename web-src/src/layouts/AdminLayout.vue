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

        <main class="app-shell" :class="{ 'no-nav': !showPageNav }">
          <div class="ia-side">
            <PageNav />
            <aside id="global-nav" class="side-nav" hidden></aside>
          </div>
          <div class="content">
            <router-view v-slot="{ Component }">
              <Transition name="page-fade" mode="out-in">
                <keep-alive :include="KEEP_ALIVE_PAGES">
                  <component :is="Component" />
                </keep-alive>
              </Transition>
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
import { KEEP_ALIVE_PAGES, WORKSPACES } from '../navigation.js';
import { useApi } from '../composables/useApi.js';
import { useAdminNavigation } from '../composables/useAdminNavigation.js';
import { useMods } from '../composables/useMods.js';
import { clearPageNav, pageNavState } from '../composables/usePageNav.js';
import { autoConnect, session } from '../composables/useSession.js';
import AppHeader from './AppHeader.vue';
import AdminOverlays from './AdminOverlays.vue';
import WorkspaceRail from './WorkspaceRail.vue';
import SectionNav from './SectionNav.vue';
import PageScrollbar from './PageScrollbar.vue';
import PageNav from '../components/PageNav.vue';
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
const showPageNav = computed(() => Boolean(pageNavState.title));
const serverHero = computed(() => SERVER_HERO[actingServerPanel.value]
  || { title: String(route.meta.title || '服务器配置'), hint: '' });

let started = false;
let navTimer = 0;

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
  if (id === 'samsara') navigate(rememberedTarget('samsara', '/samsara/economy'));
  else if (id === 'vanilla') navigate(rememberedTarget('vanilla', '/vanilla/config'));
}

function goSection(section) {
  navigate(rememberedTarget(workspace.value + '.' + section.id, section.target));
}

function goMod(mod) {
  navigate(rememberedTarget('mods.' + mod.id, '/mods/' + encodeURIComponent(mod.id)));
}

function animateContent() {
  const content = document.querySelector('.content');
  if (!content) return;
  content.classList.remove('page-enter');
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

const HERO_HINTS = {
  announcements: '编辑、预览并发送全服公告；支持聊天 / ActionBar / Title / BossBar 与定时、周期触发。',
  permissions: '按命令路径调整权限等级；关闭会级联到后代，owner 名单单独保存并热重载。',
  track: '查看最近可回看的玩家与时段；真正的回放仍在游戏内执行。',
  diff: '只读回看区块差异；复用现有 Diff API，不重写回放算法。',
  containers: '维度/坐标/半径/类型搜索，读取与编辑容器槽位；写入前保存审计快照并校验 revision。',
  inventory: '在线玩家可读可写；离线玩家只读，写入进入安全队列，登录或停服前应用。',
  'mod-config': '第三方模组配置；写入前预览并自动备份，保存后可热重载。',
};

function syncHeroFromRoute() {
  if (serverMode.value) {
    showHero('server');
    return;
  }
  if (workspace.value === 'mods') {
    showHero('page', String(route.meta.title || '配置文件'), HERO_HINTS['mod-config']);
    return;
  }
  showHero('page', String(route.meta.title || ''),
    HERO_HINTS[String(route.meta.page || '')] || '');
}

function heroMatchesRoute() {
  const node = document.getElementById(serverMode.value ? 'server-action-title' : 'action-title');
  const current = String(node?.textContent || '').trim();
  const wanted = String(route.meta.title || '').trim();
  if (!wanted) return true;
  return current === wanted || current.includes(wanted) || wanted.includes(current);
}

function showHero(mode, title, hint) {
  if (mode === 'server') {
    heroTextOnly.value = false;
    return;
  }
  heroTextOnly.value = true;
  const titleNode = document.getElementById('action-title');
  const hintNode = document.getElementById('action-hint');
  if (titleNode) titleNode.textContent = title || '';
  if (hintNode) hintNode.textContent = hint || '';
}

async function applyRoute(force = false) {
  const key = [workspace.value, page.value, route.meta.serverPanel || '',
    route.meta.subpage || ''].join(':');
  if (!force && key === lastApplied) return;
  lastApplied = key;
  document.body.classList.remove('server-section');
  syncHeroFromRoute();
  if (route.path) {
    rememberTarget(workspace.value, activeSection.value, route.path);
    if (workspace.value === 'mods' && activeModId.value) {
      rememberTarget('mods.' + activeModId.value, 'files', route.path);
    }
  }
}

let lastApplied = '';

async function measureChrome(resetRail = true) {
  const header = document.querySelector('.ia-header');
  const section = document.querySelector('.section-tabs');
  const headerH = header ? Math.round(header.getBoundingClientRect().height) : 128;
  const sectionH = section ? Math.round(section.getBoundingClientRect().height) : 56;
  const root = document.documentElement;
  root.style.setProperty('--header-h', headerH + 'px');
  root.style.setProperty('--ia-section-h', sectionH + 'px');
  root.style.setProperty('--ia-nav-top', Math.round(headerH + sectionH + 12) + 'px');
  if (resetRail) railRef.value?.collapse();
}

let measureTimer = 0;

function scheduleMeasure() {
  if (measureTimer) return;
  measureTimer = window.setTimeout(() => {
    measureTimer = 0;
    measureChrome(false);
  }, 120);
}

function onNavigateEvent(event) {
  const path = event.detail?.path;
  if (path && typeof path === 'string') navigate(path);
}

onMounted(async () => {
  try {
    window.addEventListener('samsara:navigate', onNavigateEvent);
    window.addEventListener('resize', measureChrome, { passive: true });
    window.addEventListener('scroll', scheduleMeasure, { passive: true });
    const connected = await autoConnect();
    if (!connected && !session.token) {
      window.dispatchEvent(new CustomEvent('samsara:need-token'));
    }
    loadMods();
    refreshWebVersion();
    await nextTick();
    await applyRoute(true);
    started = true;
    measureChrome();
    window.setTimeout(() => {
      if (started && !heroMatchesRoute()) applyRoute(true).catch(() => {});
    }, 300);
    railRef.value?.maybePeek();
    nextTick(() => scrollbarRef.value?.update());
  } catch (error) {
    loadError.value = '管理端加载失败：' + (error?.message || error);
  }
});

onUnmounted(() => {
  window.removeEventListener('samsara:navigate', onNavigateEvent);
  window.removeEventListener('resize', measureChrome);
  window.removeEventListener('scroll', scheduleMeasure);
  window.clearTimeout(measureTimer);
});

watch(() => session.connected, (connected) => {
  if (!connected) return;
  loadMods();
  refreshWebVersion();
});

watch(() => route.path, () => {
  clearPageNav();
}, { flush: 'pre' });

watch(() => route.path, async () => {
  if (!started) return;
  syncHeroFromRoute();
  try {
    await nextTick();
    await applyRoute();
    measureChrome(false);
    animateContent();
    nextTick(() => scrollbarRef.value?.update());
  } catch (error) {
    loadError.value = '页面切换失败：' + (error?.message || error);
  }
});
</script>
