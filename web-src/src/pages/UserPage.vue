<template>
  <div class="user-page admin-layout ia-layout user-public">
    <header class="app-header ia-header user-topbar user-header">
      <div class="ia-header-main">
        <div class="brand ia-brand user-brand">
          <div class="brand-mark" aria-hidden="true">
            <span></span><span></span><span></span>
          </div>
          <div class="brand-text">
            <h1>samsara</h1>
            <p>服务器公开信息 <span class="version-pill">只读</span></p>
          </div>
        </div>
        <div class="user-top-actions">
          <el-tag size="small" effect="plain" type="success">公开只读</el-tag>
          <a class="user-view-link" href="/" title="进入管理端，需要 token">管理端</a>
        </div>
      </div>

      <section class="page-hero" aria-label="公开信息横幅">
        <div class="page-hero-art" aria-hidden="true"></div>
        <div class="page-hero-context">
          <div class="ia-crumbs" aria-label="面包屑">
            <span>samsara</span>
            <span class="ia-crumb-sep">/</span>
            <span class="ia-crumb-title">公开信息</span>
          </div>
          <span class="page-hero-tag">PUBLIC</span>
        </div>
        <div class="page-hero-main">
          <div class="page-hero-text">
            <h1 class="page-hero-title">服务器公开信息</h1>
            <p class="page-hero-hint">只读视图，不需要 token；数据由服务端公开白名单生成，不包含 UUID、路径、配置与写操作。</p>
          </div>
        </div>
      </section>
    </header>

    <div class="ia-body">
      <div class="ia-main">
        <nav class="section-tabs user-nav" aria-label="公开信息导航">
          <button
            v-for="item in sections"
            :key="item.id"
            class="section-tab user-nav-item"
            :class="{ active: activeSection === item.id }"
            type="button"
            @click="scrollTo(item.id)"
          >
            {{ item.label }}
          </button>
        </nav>
        <main ref="host" class="content user-content"></main>
      </div>
    </div>

    <button v-if="showTop" class="user-top-button" type="button" @click="scrollTop">
      回到顶部
    </button>
  </div>
</template>

<script setup>
import { nextTick, onMounted, onUnmounted, ref } from 'vue';
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
  window.addEventListener('resize', measure, { passive: true });
  await nextTick();
  measure();
  updateActive();
});

onUnmounted(() => {
  window.removeEventListener('scroll', onScroll);
  window.removeEventListener('resize', measure);
});

function measure() {
  const header = document.querySelector('.user-topbar');
  const tabs = document.querySelector('.user-nav');
  const root = document.documentElement;
  root.style.setProperty('--header-h',
      Math.round(header?.getBoundingClientRect().height || 0) + 'px');
  root.style.setProperty('--ia-section-h',
      Math.round(tabs?.getBoundingClientRect().height || 0) + 'px');
}

function onScroll() {
  if (ticking) return;
  ticking = true;
  window.requestAnimationFrame(() => {
    ticking = false;
    updateActive();
  });
}

function updateActive() {
  const root = getComputedStyle(document.documentElement);
  const offset = (parseInt(root.getPropertyValue('--header-h'), 10) || 0)
    + (parseInt(root.getPropertyValue('--ia-section-h'), 10) || 0) + 24;
  const y = window.scrollY + offset;
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
  const root = getComputedStyle(document.documentElement);
  const offset = (parseInt(root.getPropertyValue('--header-h'), 10) || 0)
    + (parseInt(root.getPropertyValue('--ia-section-h'), 10) || 0) + 12;
  const top = node.getBoundingClientRect().top + window.scrollY - offset;
  window.scrollTo({ top, behavior: 'smooth' });
  activeSection.value = id;
}

function scrollTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' });
}
</script>

<style scoped>
.user-page {
  width: 100%;
  padding-bottom: 72px;
}

.user-public .ia-main {
  margin-left: 0;
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

.user-nav {
  flex-wrap: nowrap;
}

.user-content {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding-top: 12px;
}

.user-content :deep(section[id^="user-section-"]) {
  scroll-margin-top: 180px;
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
    padding-bottom: 60px;
  }

  .user-content {
    padding-top: 8px;
  }
}
</style>
