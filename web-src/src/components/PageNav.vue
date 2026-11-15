<template>
  <aside id="page-nav" class="side-nav" aria-label="页内导航" :hidden="!state.title">
    <div class="nav-title">
      <span>{{ state.title }}</span>
      <span v-if="state.count != null" class="count-pill">{{ state.count }}</span>
    </div>
    <div v-if="state.items.length" class="nav-group">
      <NavItems :items="state.items" :active="active" @select="select" />
    </div>
    <div v-else class="nav-empty">暂无可导航内容</div>
  </aside>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { pageNavState as state } from '../composables/usePageNav.js';
import NavItems from './NavItems.vue';

const active = ref('');
let scrollHandler = null;
let suppressScrollUntil = 0;

function itemKey(item) {
  return item?.id || item?.target || item?.label || '';
}

function targetIds(items) {
  const out = [];
  const collect = (list) => {
    for (const item of list || []) {
      if (item.target) out.push(item.target);
      if (item.children?.length) collect(item.children);
    }
  };
  collect(items);
  return out;
}

function waitForTarget(id, timeoutMs = 2500) {
  return new Promise((resolve) => {
    const started = Date.now();
    const tick = () => {
      const el = document.getElementById(id);
      if (el || Date.now() - started >= timeoutMs) {
        resolve(el);
        return;
      }
      window.setTimeout(tick, 50);
    };
    tick();
  });
}

function openAncestors(el) {
  let changed = false;
  for (let node = el.parentElement; node; node = node.parentElement) {
    if (node.tagName === 'DETAILS' && !node.open) {
      node.open = true;
      changed = true;
    }
  }
  return changed;
}

function navOffset() {
  const nav = document.getElementById('page-nav');
  const rectTop = nav ? nav.getBoundingClientRect().top : 0;
  if (rectTop >= 0) return rectTop;
  const root = getComputedStyle(document.documentElement);
  const header = parseFloat(root.getPropertyValue('--header-h')) || 0;
  const section = parseFloat(root.getPropertyValue('--ia-section-h')) || 0;
  return header + section + 12;
}

function alignTo(el) {
  const delta = el.getBoundingClientRect().top - navOffset();
  if (Math.abs(delta) > 1) {
    window.scrollTo({ top: window.scrollY + delta, behavior: 'instant' });
  }
}

async function select(item) {
  if (!item) return;
  const key = itemKey(item);
  if (key) active.value = key;
  // 程序化滚动期间不让 scroll 监听把高亮改回父级。
  suppressScrollUntil = Date.now() + 1400;
  try {
    if (typeof item.onClick === 'function') await item.onClick();
  } catch {
    // 页面自己的错误提示负责展示。
  }
  if (!item.target) {
    suppressScrollUntil = 0;
    return;
  }
  await nextTick();
  let el = await waitForTarget(item.target);
  if (!el) {
    suppressScrollUntil = 0;
    return;
  }
  if (openAncestors(el)) {
    await nextTick();
    await new Promise((resolve) => requestAnimationFrame(resolve));
  }
  if (key) active.value = key;
  const top = Math.max(0, window.scrollY + el.getBoundingClientRect().top - navOffset());
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches;
  if (reduce) {
    window.scrollTo({ top, behavior: 'instant' });
  } else {
    const from = window.scrollY;
    const started = performance.now();
    const duration = 320;
    const step = (now) => {
      const progress = Math.min(1, (now - started) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      window.scrollTo({ top: from + (top - from) * eased, behavior: 'instant' });
      if (progress < 1) requestAnimationFrame(step);
      else alignTo(el);
    };
    requestAnimationFrame(step);
  }
  for (const delay of [0, 120, 360, 800]) {
    window.setTimeout(() => {
      if (key) active.value = key;
      alignTo(el);
      if (delay === 800) suppressScrollUntil = 0;
    }, delay);
  }
}

function watchScroll() {
  if (scrollHandler) window.removeEventListener('scroll', scrollHandler);
  scrollHandler = null;
  const ids = targetIds(state.items);
  if (!ids.length) return;
  const shell = document.querySelector('.ia-header');
  const section = document.querySelector('.section-tabs');
  const threshold = (shell?.getBoundingClientRect().height || 0)
    + (section?.getBoundingClientRect().height || 0) + 24;
  const previous = active.value;
  scrollHandler = () => {
    if (Date.now() < suppressScrollUntil) return;
    let current = ids[0];
    for (const id of ids) {
      const el = document.getElementById(id);
      if (el && el.getBoundingClientRect().top <= threshold) current = id;
    }
    active.value = current;
  };
  if (!previous || !ids.includes(previous)) active.value = ids[0];
  window.addEventListener('scroll', scrollHandler, { passive: true });
}

watch(() => state.title, (title) => {
  if (!title) {
    active.value = '';
    if (scrollHandler) window.removeEventListener('scroll', scrollHandler);
    scrollHandler = null;
    return;
  }
  // 旧脚本 clearNav 会给 app-shell 加 no-nav；Vue 页内导航出现时要恢复侧栏。
  document.querySelector('main.app-shell')?.classList.remove('no-nav');
}, { flush: 'post' });

watch(() => state.items, watchScroll, { deep: true });

onMounted(watchScroll);
onBeforeUnmount(() => {
  if (scrollHandler) window.removeEventListener('scroll', scrollHandler);
});
</script>
