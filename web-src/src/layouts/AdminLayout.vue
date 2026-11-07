<template>
  <div class="admin-layout">
    <div ref="headerHost"></div>
    <main class="app-shell">
      <aside id="global-nav" class="side-nav" aria-label="全局导航"></aside>
      <div class="content">
        <ConfigPage v-show="tab === 'config'" />
        <ConfigEditorPage v-show="tab === 'config-editor'" />
        <ClaimRangePage v-show="tab === 'claim-range'" />
        <OutputPage v-show="tab === 'output'" />
        <PermissionsPage v-show="tab === 'permissions'" />
        <MapPage v-show="tab === 'map'" />
        <AnnouncementsPage v-show="tab === 'announcements'" />
        <WorldStackPage v-show="tab === 'world-stack'" />
        <LotteryPage v-show="tab === 'lottery'" />
        <IdentityPage v-show="tab === 'identity'" />
        <ServerPage v-show="isServer" />
      </div>
    </main>
    <div ref="overlayHost"></div>
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
import ConfigPage from '../pages/ConfigPage.vue';
import ConfigEditorPage from '../pages/ConfigEditorPage.vue';
import ClaimRangePage from '../pages/ClaimRangePage.vue';
import OutputPage from '../pages/OutputPage.vue';
import PermissionsPage from '../pages/PermissionsPage.vue';
import MapPage from '../pages/MapPage.vue';
import AnnouncementsPage from '../pages/AnnouncementsPage.vue';
import WorldStackPage from '../pages/WorldStackPage.vue';
import LotteryPage from '../pages/LotteryPage.vue';
import IdentityPage from '../pages/IdentityPage.vue';
import ServerPage from '../pages/ServerPage.vue';

const route = useRoute();
const router = useRouter();
const headerHost = ref(null);
const overlayHost = ref(null);
const loadError = ref('');

const tab = computed(() => route.meta.tab || 'config');
const isServer = computed(() => route.meta.server === true);

const MOD_ROUTES = {
  'panel-config': '/config',
  'panel-config-editor': '/config-editor',
  'panel-claim-range': '/claim-range',
  'panel-output': '/output',
  'panel-permissions': '/permissions',
  'panel-map': '/map',
  'panel-announcements': '/announcements',
  'panel-world-stack': '/world-stack',
  'panel-lottery': '/lottery',
  'panel-identity': '/identity',
};

const SERVER_ROUTES = {
  'server-config-panel': '/server/config',
  'server-backup-panel': '/server/backup',
  'server-console-panel': '/server/console',
  'server-audit-panel': '/server/audit',
};

let started = false;
let lastApplied = '';
let navTimer = 0;

function navigate(path) {
  window.clearTimeout(navTimer);
  if (router.currentRoute.value.path === path) return;
  navTimer = window.setTimeout(() => {
    if (router.currentRoute.value.path !== path) router.push(path);
  }, 0);
}

onMounted(async () => {
  try {
    const doc = await loadLegacyDocument();
    const header = doc.querySelector('header.app-header');
    if (header) {
      headerHost.value.appendChild(document.importNode(header, true));
    }
    for (const node of Array.from(doc.body.childNodes)) {
      if (node === header) continue;
      if (node.nodeType === Node.ELEMENT_NODE
          && (node.tagName === 'MAIN' || node.tagName === 'SCRIPT')) {
        continue;
      }
      overlayHost.value.appendChild(document.importNode(node, true));
    }
    installTabInterceptor();
    await waitForCurrentPanel();
    await loadLegacyAdminScripts();
    wrapSetActiveTab();
    await nextTick();
    await applyRoute(true);
    started = true;
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

async function waitForCurrentPanel() {
  if (isServer.value) {
    await waitForElement('server-config-panel');
  } else {
    await waitForElement('panel-' + tab.value);
  }
}

async function applyRoute(force = false) {
  if (!window.SamsaraUI) return;
  const key = isServer.value ? 'server:' + (route.meta.serverPanel || '') : 'tab:' + tab.value;
  if (!force && key === lastApplied) return;
  lastApplied = key;
  if (isServer.value) {
    window.SamsaraServer?.showPanel?.(route.meta.serverPanel || 'server-config-panel');
  } else {
    window.SamsaraServer?.leaveServer?.();
    window.SamsaraUI.setActiveTab(tab.value);
  }
}

function wrapSetActiveTab() {
  const original = window.SamsaraUI?.setActiveTab;
  if (!original || original.__samsaraRouteWrapped) return;
  const wrapped = (name) => {
    original(name);
    navigate('/' + name);
  };
  wrapped.__samsaraRouteWrapped = true;
  window.SamsaraUI.setActiveTab = wrapped;
}

function installTabInterceptor() {
  headerHost.value.addEventListener('click', (event) => {
    const modTab = event.target.closest?.('.tab[data-panel]');
    if (modTab) {
      event.preventDefault();
      event.stopImmediatePropagation();
      const path = MOD_ROUTES[modTab.dataset.panel];
      if (path) navigate(path);
      return;
    }
    const serverTab = event.target.closest?.('.tab[data-server-panel]');
    if (serverTab) {
      event.preventDefault();
      event.stopImmediatePropagation();
      const path = SERVER_ROUTES[serverTab.dataset.serverPanel];
      if (path) navigate(path);
      return;
    }
    const topTab = event.target.closest?.('.top-tab[data-section]');
    if (topTab) {
      event.preventDefault();
      event.stopImmediatePropagation();
      navigate(topTab.dataset.section === 'server' ? '/server/config' : '/config');
    }
  }, true);
}
</script>
