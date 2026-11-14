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
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { pageNavState as state } from '../composables/usePageNav.js';
import NavItems from './NavItems.vue';

const active = ref('');
let scrollHandler = null;

function targetIds(items) {
  const out = [];
  for (const item of items || []) {
    if (item.target) out.push(item.target);
    for (const child of item.children || []) if (child.target) out.push(child.target);
  }
  return out;
}

function select(item) {
  if (!item?.target) return;
  active.value = item.target;
  const el = document.getElementById(item.target);
  if (el) el.scrollIntoView({ block: 'start', behavior: 'smooth' });
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
  scrollHandler = () => {
    let current = ids[0];
    for (const id of ids) {
      const el = document.getElementById(id);
      if (el && el.getBoundingClientRect().top <= threshold) current = id;
    }
    active.value = current;
  };
  active.value = ids[0];
  window.addEventListener('scroll', scrollHandler, { passive: true });
}

watch(() => state.items, watchScroll, { deep: true });

onMounted(watchScroll);
onBeforeUnmount(() => {
  if (scrollHandler) window.removeEventListener('scroll', scrollHandler);
});
</script>
