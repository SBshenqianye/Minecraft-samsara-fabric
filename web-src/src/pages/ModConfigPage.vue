<template>
  <TabShell name="mod-config" :title="modId || '其他模组'"
      description="按模组配置路径递归浏览；文件页复用 PageSpec/FormSpec 配置编辑器。">
    <p v-if="error" class="container-message error">{{ error }}</p>
    <p v-if="message" class="container-message ok">{{ message }}</p>

    <div class="mod-config-layout">
      <aside class="panel mod-tree">
        <h3>{{ modId || '其他模组' }}</h3>
        <p class="muted small">{{ pages.length }} 个配置文件</p>
        <FolderTree
          :nodes="tree"
          :active-folder="activeFolder"
          :active-file="currentPageId"
          @folder="openFolder"
          @file="openFile"
        />
      </aside>

      <section class="panel mod-editor">
        <nav v-if="currentFiles.length" class="mod-file-tabs" aria-label="当前文件夹文件">
          <button
            v-for="file in currentFiles"
            :key="file.id"
            class="mod-file-tab"
            :class="{ active: currentPageId === file.id }"
            type="button"
            @click="openFile(file)"
          >
            {{ file.name }}
          </button>
        </nav>
        <p v-if="!currentFiles.length" class="muted small">
          当前文件夹没有配置文件；从左侧文件夹树选择其他位置。
        </p>
        <div class="mod-editor-host">
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
        </div>
      </section>
    </div>
  </TabShell>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getToken, useApi } from '../composables/useApi.js';
import TabShell from '../components/TabShell.vue';
import FolderTree from '../components/FolderTree.vue';
import { useLegacyPage } from '../composables/useLegacyPage.js';

const route = useRoute();
const router = useRouter();
const { json } = useApi();
const error = ref('');
const message = ref('');
const pages = ref([]);
const loaded = ref(false);

useLegacyPage({
  panelId: 'panel-config-editor',
  load: () => import('../legacy-core/config-editor.js'),
  afterLoad: () => window.SamsaraConfigEditor?.setSourceFilter?.('third-party'),
  activate: () => window.SamsaraConfigEditor?.activate?.(),
});

const modId = computed(() => String(route.params.modId || ''));
const routeFile = computed(() => {
  const raw = route.params.file;
  if (Array.isArray(raw)) return raw.join('/');
  return String(raw || '').replace(/^\/+|\/+$/g, '');
});

const tree = computed(() => buildTree(pages.value));
const currentPage = computed(() => {
  if (!pages.value.length) return null;
  const wanted = routeFile.value;
  if (wanted) {
    const exact = pages.value.find((page) => pathWithin(page) === wanted);
    if (exact) return exact;
    const under = pages.value.find((page) => pathWithin(page).startsWith(wanted + '/'));
    if (under) return under;
  }
  return pages.value[0];
});
const currentPageId = computed(() => currentPage.value?.id || '');
const activeFolder = computed(() => {
  const page = currentPage.value;
  if (!page) return '';
  const path = pathWithin(page);
  const index = path.lastIndexOf('/');
  return index < 0 ? '' : path.substring(0, index);
});
const currentFiles = computed(() => {
  const folder = activeFolder.value;
  return pages.value
    .filter((page) => {
      const path = pathWithin(page);
      const index = path.lastIndexOf('/');
      const dir = index < 0 ? '' : path.substring(0, index);
      return dir === folder;
    })
    .map((page) => ({
      id: page.id,
      name: page.fileName.split('/').pop(),
      path: pathWithin(page),
      page,
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
});

onMounted(async () => {
  const ui = await waitFor(() => window.SamsaraUI, 20000);
  if (!ui) {
    error.value = '管理端脚本尚未加载完成，请刷新页面。';
    return;
  }
  ui.onConnected?.(() => reload());
  const token = getToken();
  if (token) {
    await waitFor(() => window.SamsaraUI.getToken && window.SamsaraUI.getToken(), 20000);
    await reload();
  }
});

watch(() => route.params.file, () => {
  if (loaded.value) openCurrent();
});

watch(() => route.params.modId, () => {
  if (loaded.value) reload();
});

async function reload() {
  error.value = '';
  message.value = '';
  const token = getToken();
  if (!token) {
    error.value = '需要先在管理端输入 token。';
    return;
  }
  try {
    const body = await json('/api/config/pages', {}, '读取配置页面失败');
    const all = body.pages || [];
    pages.value = all.filter((page) => page.source === 'third-party'
      && groupId(page) === modId.value);
    if (!pages.value.length) {
      error.value = '这个模组没有可编辑的配置文件。';
      loaded.value = true;
      return;
    }
    loaded.value = true;
    hideLegacyChrome();
    ensureRoute();
    await openCurrent();
  } catch (e) {
    error.value = e.message || String(e);
  }
}

function ensureRoute() {
  const wanted = routeFile.value;
  const has = wanted && pages.value.some((page) => {
    const path = pathWithin(page);
    return path === wanted || path.startsWith(wanted + '/');
  });
  if (!has) {
    const first = pages.value[0];
    const path = pathWithin(first);
    const target = path ? '/mods/' + encodeURIComponent(modId.value) + '/' + encodePath(path)
      : '/mods/' + encodeURIComponent(modId.value);
    if (router.currentRoute.value.path !== target) router.replace(target);
  }
}

async function openCurrent() {
  const page = currentPage.value;
  if (!page) return;
  try {
    const editor = await waitFor(() => window.SamsaraConfigEditor, 20000);
    if (!editor) throw new Error('配置编辑器尚未加载完成。');
    editor.setSourceFilter?.('third-party');
    editor.setNavEnabled?.(false);
    hideLegacyChrome();
    await editor.load(page.id);
  } catch (e) {
    error.value = e.message || String(e);
  }
}

function openFolder(path) {
  const first = pages.value.find((page) => pathWithin(page).startsWith(path + '/'));
  if (!first) return;
  navigateTo(first);
}

function openFile(file) {
  const page = file.page || pages.value.find((entry) => entry.id === file.id);
  if (!page) return;
  navigateTo(page);
}

function navigateTo(page) {
  const path = pathWithin(page);
  const target = path
    ? '/mods/' + encodeURIComponent(modId.value) + '/' + encodePath(path)
    : '/mods/' + encodeURIComponent(modId.value);
  if (router.currentRoute.value.path !== target) router.push(target);
  else openCurrent();
}

function groupId(page) {
  const relative = relativeOf(page);
  return relative.includes('/') ? relative.split('/')[0] : relative;
}

function relativeOf(page) {
  const id = String(page.id || '');
  if (id.startsWith('third-party:')) {
    return id.substring('third-party:'.length).replace(/\\/g, '/');
  }
  return String(page.fileName || '').replace(/\\/g, '/');
}

function pathWithin(page) {
  const relative = relativeOf(page);
  if (!relative.includes('/')) return '';
  if (relative === modId.value) return '';
  if (relative.startsWith(modId.value + '/')) {
    return relative.substring(modId.value.length + 1);
  }
  return relative;
}

function encodePath(path) {
  return path.split('/').map((part) => encodeURIComponent(part)).join('/');
}

function buildTree(list) {
  const root = { type: 'folder', name: '', path: '', children: [] };
  for (const page of list) {
    const relative = pathWithin(page);
    const parts = relative ? relative.split('/') : [];
    let node = root;
    for (const part of parts.slice(0, -1)) {
      let child = node.children.find((item) => item.type === 'folder'
        && item.name === part);
      if (!child) {
        child = {
          type: 'folder',
          name: part,
          path: node.path ? node.path + '/' + part : part,
          children: [],
        };
        node.children.push(child);
      }
      node = child;
    }
    node.children.push({
      type: 'file',
      name: page.fileName.split('/').pop(),
      path: relative,
      page,
      id: page.id,
    });
  }
  sortTree(root);
  return root.children;
}

function sortTree(node) {
  node.children.sort((a, b) => {
    if (a.type !== b.type) return a.type === 'folder' ? -1 : 1;
    return a.name.localeCompare(b.name);
  });
  for (const child of node.children) {
    if (child.type === 'folder') sortTree(child);
  }
}

function hideLegacyChrome() {
  const nav = document.getElementById('global-nav');
  if (nav) {
    nav.replaceChildren();
    nav.hidden = true;
  }
  const shell = document.querySelector('main.app-shell');
  if (shell) shell.classList.add('no-nav');
  // 顶部 hero 由 AdminLayout 统一维护，这里只清理旧页内导航。
}

function waitFor(check, timeoutMs) {
  return new Promise((resolve) => {
    const started = Date.now();
    const tick = () => {
      const value = check();
      if (value) {
        resolve(value);
        return;
      }
      if (Date.now() - started > timeoutMs) {
        resolve(null);
        return;
      }
      window.setTimeout(tick, 60);
    };
    tick();
  });
}
</script>

<style scoped>
.mod-config-layout {
  display: grid;
  grid-template-columns: minmax(220px, 280px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.mod-tree {
  position: sticky;
  top: calc(var(--header-h, 128px) + 58px);
  max-height: calc(100vh - var(--header-h, 128px) - 80px);
  overflow: auto;
}

.mod-tree h3 {
  margin: 0 0 3px;
}

.mod-editor {
  min-width: 0;
}

.mod-file-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
}

.mod-file-tab {
  padding: 6px 11px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  color: var(--fg-soft);
  font-size: 12px;
  cursor: pointer;
}

.mod-file-tab:hover {
  border-color: var(--line-strong);
  background: var(--panel-soft);
}

.mod-file-tab.active {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}

.mod-editor-host :deep(#panel-config-editor > .panel) {
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.mod-editor-host :deep(#panel-config-editor .panel-head) {
  display: none;
}

.mod-editor-host :deep(#config-editor-body) {
  padding: 0;
}

@media (max-width: 900px) {
  .mod-config-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .mod-tree {
    position: static;
    max-height: 260px;
  }
}
</style>

<style>
.folder-tree {
  list-style: none;
  margin: 0;
  padding: 0;
}

.folder-tree li {
  margin: 1px 0;
}

.folder-node,
.file-node {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 5px 8px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: var(--fg-soft);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.folder-node:hover,
.file-node:hover {
  background: var(--panel-soft);
  border-color: var(--line);
}

.folder-node.active,
.file-node.active {
  background: var(--accent-soft);
  border-color: color-mix(in srgb, var(--accent) 30%, var(--line));
  color: var(--accent);
}

.folder-icon,
.file-icon {
  flex: 0 0 auto;
  color: var(--muted);
}
</style>
