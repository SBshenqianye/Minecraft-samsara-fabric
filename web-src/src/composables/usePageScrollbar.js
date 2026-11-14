import { onBeforeUnmount, onMounted, ref } from 'vue';

/**
 * 页面级自定义滚动条：滚动/缩放/内容变化时更新滑块，1.2 秒后自动淡出。
 * 只服务管理端整页滚动，不接管页内滚动容器。
 */
export function usePageScrollbar() {
  const bar = ref(null);
  const thumb = ref(null);
  const visible = ref(false);

  let hideTimer = 0;
  let dragging = false;
  let startY = 0;
  let startScroll = 0;
  let trackH = 0;
  let thumbH = 0;
  let observer = null;

  function update() {
    const barEl = bar.value;
    const thumbEl = thumb.value;
    if (!barEl || !thumbEl) return;
    const doc = document.documentElement;
    const viewH = window.innerHeight || doc.clientHeight;
    const scrollH = doc.scrollHeight;
    if (scrollH <= viewH + 1) {
      visible.value = false;
      return;
    }
    const track = Math.max(1, barEl.clientHeight);
    const nextThumb = Math.max(36, Math.round(track * viewH / scrollH));
    const maxTop = Math.max(1, track - nextThumb);
    const ratio = Math.min(1, Math.max(0, window.scrollY / Math.max(1, scrollH - viewH)));
    thumbEl.style.height = nextThumb + 'px';
    thumbEl.style.transform = 'translateY(' + Math.round(ratio * maxTop) + 'px)';
    trackH = track;
    thumbH = nextThumb;
  }

  function show() {
    visible.value = true;
    window.clearTimeout(hideTimer);
    hideTimer = window.setTimeout(() => {
      if (!dragging) visible.value = false;
    }, 1200);
  }

  function onScroll() {
    update();
    show();
  }

  function onResize() {
    update();
  }

  function onPointerDown(event) {
    if (!bar.value || !thumb.value) return;
    dragging = true;
    startY = event.clientY;
    startScroll = window.scrollY;
    update();
    thumb.value.setPointerCapture?.(event.pointerId);
    event.preventDefault();
  }

  function onPointerMove(event) {
    if (!dragging) return;
    const doc = document.documentElement;
    const maxScroll = Math.max(1, doc.scrollHeight - window.innerHeight);
    const maxTop = Math.max(1, trackH - thumbH);
    const delta = (event.clientY - startY) / maxTop * maxScroll;
    window.scrollTo(0, Math.max(0, Math.min(maxScroll, startScroll + delta)));
    update();
    show();
  }

  function onPointerUp() {
    if (!dragging) return;
    dragging = false;
    show();
  }

  function onTrackClick(event) {
    if (event.target === thumb.value) return;
    const barEl = bar.value;
    if (!barEl) return;
    const track = Math.max(1, barEl.clientHeight);
    const ratio = Math.min(1, Math.max(0,
      (event.clientY - barEl.getBoundingClientRect().top) / track));
    window.scrollTo(0,
      ratio * Math.max(0, document.documentElement.scrollHeight - window.innerHeight));
    show();
  }

  onMounted(() => {
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onResize);
    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);
    if (window.ResizeObserver) {
      observer = new ResizeObserver(update);
      observer.observe(document.body);
    }
    // 首屏/切页后 DOM 高度会变，下一帧再量一次。
    window.requestAnimationFrame(update);
  });

  onBeforeUnmount(() => {
    window.removeEventListener('scroll', onScroll);
    window.removeEventListener('resize', onResize);
    window.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('pointerup', onPointerUp);
    observer?.disconnect();
    window.clearTimeout(hideTimer);
  });

  return { bar, thumb, visible, update, onPointerDown, onTrackClick };
}
