<template>
  <div class="user-page">
    <header class="app-header user-header user-topbar">
      <div class="brand user-brand">
        <div class="brand-mark" aria-hidden="true">
          <span></span><span></span><span></span>
        </div>
        <div class="brand-text">
          <h1>samsara</h1>
          <p>服务器公开信息</p>
        </div>
      </div>
      <div class="user-top-actions">
        <el-tag size="small" effect="plain" type="success">公开只读</el-tag>
        <a class="user-view-link" href="/" title="进入管理端（需要 token）">管理端</a>
      </div>
    </header>

    <div class="user-layout">
      <aside class="user-nav" aria-label="公开信息导航">
        <div class="user-nav-title">公开信息</div>
        <a
          v-for="item in sections"
          :key="item.id"
          class="user-nav-item"
          :class="{ active: activeSection === item.id }"
          :href="'#' + item.id"
          @click.prevent="scrollTo(item.id)"
        >
          {{ item.label }}
        </a>
      </aside>

      <main ref="host" class="user-content"></main>
    </div>

    <button v-if="showTop" class="user-top-button" type="button" @click="scrollTop">
      回到顶部
    </button>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue';
import { mountLegacyUser } from '../legacy/user.js';

const host = ref(null);
const activeSection = ref('user-section-status');
const showTop = ref(false);
const sections = [
  { id: 'user-section-status', label: '服务器状态' },
  { id: 'user-section-worlds', label: '世界栈' },
  { id: 'user-section-lottery', label: '死人乐透' },
  { id: 'user-section-values', label: '物品价值表' },
  { id: 'user-section-map', label: '地图' },
];

let ticking = false;

onMounted(async () => {
  document.title = 'samsara · 服务器公开信息';
  try {
    await mountLegacyUser(host.value);
  } catch (error) {
    const box = document.createElement('div');
    box.className = 'container-message error';
    box.textContent = '用户端加载失败：' + (error?.message || error);
    host.value?.appendChild(box);
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  updateActive();
});

onUnmounted(() => {
  window.removeEventListener('scroll', onScroll);
});

function onScroll() {
  if (ticking) return;
  ticking = true;
  window.requestAnimationFrame(() => {
    ticking = false;
    updateActive();
  });
}

function updateActive() {
  const y = window.scrollY + 180;
  let current = sections[0].id;
  for (const item of sections) {
    const node = document.getElementById(item.id);
    if (node && node.offsetTop <= y) current = item.id;
  }
  activeSection.value = current;
  showTop.value = window.scrollY > 700;
}

function scrollTo(id) {
  const node = document.getElementById(id);
  if (!node) return;
  const top = node.getBoundingClientRect().top + window.scrollY - 86;
  window.scrollTo({ top, behavior: 'smooth' });
  activeSection.value = id;
}

function scrollTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' });
}
</script>

<style scoped>
.user-page {
  width: min(1440px, 100%);
  margin: 0 auto;
  padding: 0 20px 72px;
}

.user-topbar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 74px;
  margin-bottom: 20px;
  padding: 10px 0;
  background: color-mix(in srgb, var(--bg) 90%, transparent);
  border-bottom: 1px solid var(--line);
  backdrop-filter: blur(16px);
}

.user-brand {
  min-width: 0;
}

.user-brand .brand-text p {
  margin-top: 2px;
}

.user-top-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
}

.user-layout {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.user-nav {
  position: sticky;
  top: 92px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--panel);
  box-shadow: var(--shadow-soft);
}

.user-nav-title {
  padding: 4px 8px 7px;
  color: var(--muted);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .12em;
  text-transform: uppercase;
}

.user-nav-item {
  padding: 7px 9px;
  border: 1px solid transparent;
  border-radius: 8px;
  color: var(--fg-soft);
  font-size: 13px;
  text-decoration: none;
}

.user-nav-item:hover {
  background: var(--panel-soft);
  border-color: var(--line);
}

.user-nav-item.active {
  background: var(--accent-soft);
  border-color: color-mix(in srgb, var(--accent) 28%, var(--line));
  color: var(--accent);
  font-weight: 600;
}

.user-content {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.user-content :deep(section[id^="user-section-"]) {
  scroll-margin-top: 96px;
}

.user-top-button {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 25;
  padding: 8px 14px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  background: var(--panel);
  color: var(--fg-soft);
  box-shadow: var(--shadow);
  cursor: pointer;
}

.user-top-button:hover {
  border-color: var(--accent);
  color: var(--accent);
}

@media (max-width: 900px) {
  .user-page {
    padding: 0 12px 64px;
  }

  .user-topbar {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
    padding: 10px 0;
  }

  .user-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .user-nav {
    position: static;
    display: flex;
    flex-direction: row;
    gap: 6px;
    overflow-x: auto;
    padding: 8px;
  }

  .user-nav-title {
    display: none;
  }

  .user-nav-item {
    flex: 0 0 auto;
    white-space: nowrap;
  }
}
</style>
