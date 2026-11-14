import { onBeforeUnmount, onMounted, ref } from 'vue';

/**
 * 左侧工作区导航的展开/收起状态与指针行为。
 * 默认收起；悬停或点击把手展开，鼠标离开 140ms 后收回。
 */
export function useWorkspaceRail() {
  const railEl = ref(null);
  const railHandle = ref(null);
  const open = ref(false);
  const pinned = ref(false);
  const closing = ref(false);

  let railTimer = 0;
  let railCloseTimer = 0;
  let lastPointerX = -1;
  let lastPointerY = -1;
  let ignoreOutsideUntil = 0;

  function storePinned() {
    try {
      localStorage.setItem('samsara.ia.rail', pinned.value ? 'pinned' : 'collapsed');
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

  function openRail(pin = false) {
    window.clearTimeout(railCloseTimer);
    window.clearTimeout(railTimer);
    closing.value = false;
    open.value = true;
    // 展开动画期间不要因为指针还在左侧滑动区就判定为“离开”。
    ignoreOutsideUntil = performance.now() + 300;
    if (pin) pinned.value = true;
  }

  function collapse() {
    window.clearTimeout(railTimer);
    if (!open.value && !closing.value) return;
    // 点击页面后焦点可能还在导航按钮上；先移走焦点，避免 :focus-visible 把导航重新撑开。
    const active = document.activeElement;
    if (active && railEl.value && railEl.value.contains(active)
        && typeof active.blur === 'function') {
      active.blur();
    }
    open.value = false;
    pinned.value = false;
    storePinned();
    // 等收回动画结束再显示“工作区”把手，避免把手和导航同时出现。
    closing.value = true;
    window.clearTimeout(railCloseTimer);
    railCloseTimer = window.setTimeout(() => {
      closing.value = false;
    }, 240);
  }

  function toggle() {
    if (open.value) {
      collapse();
      return;
    }
    openRail(true);
    storePinned();
  }

  function onRailEnter() {
    openRail(false);
  }

  function maybePeek() {
    if (open.value) return;
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
        if (!pointerInsideRail(lastPointerX, lastPointerY)) collapse();
      }, 3000);
    }, 350);
  }

  function onPointerMove(event) {
    lastPointerX = event.clientX;
    lastPointerY = event.clientY;
    if (!open.value) return;
    if (performance.now() < ignoreOutsideUntil) return;
    if (pointerInsideRail(lastPointerX, lastPointerY)) {
      window.clearTimeout(railCloseTimer);
      return;
    }
    // 短延迟做二次确认，避免鼠标从把手移到导航、或动画途中误判。
    window.clearTimeout(railCloseTimer);
    railCloseTimer = window.setTimeout(() => {
      if (open.value && !pointerInsideRail(lastPointerX, lastPointerY)) collapse();
    }, 140);
  }

  function onWindowBlur() {
    window.clearTimeout(railCloseTimer);
    collapse();
  }

  onMounted(() => {
    document.addEventListener('pointermove', onPointerMove, { passive: true });
    window.addEventListener('blur', onWindowBlur);
  });

  onBeforeUnmount(() => {
    document.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('blur', onWindowBlur);
    window.clearTimeout(railTimer);
    window.clearTimeout(railCloseTimer);
  });

  return {
    railEl,
    railHandle,
    open,
    pinned,
    closing,
    toggle,
    onRailEnter,
    collapse,
    maybePeek,
  };
}
