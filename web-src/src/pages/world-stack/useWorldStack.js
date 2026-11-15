import { computed, ref } from 'vue';
import { useApi } from '../../composables/useApi.js';
import {
  filesView,
  importPreviewView,
  importQueueView,
  jobsView,
  orderView as buildOrderView,
  stackStats,
  worldStackNav,
} from './model.js';

function readFileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(new Error('读取图片失败'));
    reader.readAsDataURL(file);
  });
}

function loadImageElement(dataUrl) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('无法解析图片'));
    image.src = dataUrl;
  });
}

function canvasToBlob(canvas, mime, quality) {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('图片压缩失败'))), mime, quality);
  });
}

export async function compressIcon(file) {
  const allowed = ['image/png', 'image/jpeg', 'image/webp'];
  if (!allowed.includes(file.type)) throw new Error('只支持 PNG / JPEG / WebP 图片');
  if (file.size > 12 * 1024 * 1024) throw new Error('图片超过 12 MiB，请先裁剪');
  const dataUrl = await readFileAsDataUrl(file);
  const image = await loadImageElement(dataUrl);
  const maxSide = 1024;
  const scale = Math.min(1, maxSide / Math.max(image.width, image.height));
  const width = Math.max(1, Math.round(image.width * scale));
  const height = Math.max(1, Math.round(image.height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(image, 0, 0, width, height);
  const preferred = file.type === 'image/jpeg' ? 'image/jpeg' : 'image/webp';
  let blob = null;
  for (let quality = 0.88; quality >= 0.5; quality -= 0.08) {
    blob = await canvasToBlob(canvas, preferred, quality);
    if (blob.size <= 500 * 1024) break;
  }
  if (!blob) throw new Error('图片压缩失败');
  if (blob.size > 700 * 1024) throw new Error('图片压缩后仍然过大，请换一张更小的图');
  const encoded = await readFileAsDataUrl(blob);
  return { mime: blob.type || preferred, data: encoded.slice(encoded.indexOf(',') + 1) };
}

/** 世界栈页状态与动作；模板只做展示。 */
export function useWorldStack() {
  const { request } = useApi();
  const model = ref(null);
  const message = ref('');
  const messageKind = ref('');
  const loading = ref(false);
  const order = ref([]);
  const savedOrder = ref([]);
  const preview = ref(null);
  const importState = ref({ path: '', position: 'bottom', afterId: '', confirm: false, busy: false, error: '' });
  const iconBusy = ref(false);
  const iconTarget = ref('');
  const iconInput = ref(null);

  function setMessage(text, kind = '') {
    message.value = text || '';
    messageKind.value = kind;
  }

  function stacks() {
    return Array.isArray(model.value?.stacks) ? model.value.stacks : [];
  }

  function byId(id) {
    return stacks().find((stack) => stack.id === id) || null;
  }

  function applyModel(data) {
    model.value = data || null;
    order.value = stacks().slice().sort((a, b) => a.index - b.index).map((stack) => stack.id);
    savedOrder.value = order.value.slice();
    preview.value = null;
    importState.value = { ...importState.value, confirm: false, busy: false, error: '' };
  }

  async function load() {
    if (loading.value) return;
    loading.value = true;
    setMessage('正在读取世界栈…');
    try {
      const result = await request('/api/world-stack');
      if (!result.ok) throw new Error(result.data?.error || '世界栈读取失败');
      applyModel(result.data);
      setMessage('');
    } catch (error) {
      setMessage(error.message || '世界栈读取失败', 'error');
    } finally {
      loading.value = false;
    }
  }

  async function post(path, payload) {
    const result = await request(path, {
      method: 'POST',
      body: JSON.stringify(payload || {}),
    });
    if (!result.ok && result.data == null) throw new Error('HTTP ' + result.status);
    return result.data || {};
  }

  function setImportField(key, value) {
    importState.value = { ...importState.value, [key]: value };
  }

  const view = computed(() => buildOrderView(order.value, savedOrder.value, byId));

  function moveOrder(from, to) {
    if (to < 0 || to >= order.value.length || from === to) return;
    const next = order.value.slice();
    const [item] = next.splice(from, 1);
    next.splice(to, 0, item);
    order.value = next;
  }

  function moveBy(id, delta) {
    const from = order.value.indexOf(id);
    if (from < 0) return;
    moveOrder(from, from + delta);
  }

  function moveTo(dragged, target) {
    const from = order.value.indexOf(dragged);
    const to = order.value.indexOf(target);
    if (from >= 0 && to >= 0) moveOrder(from, to);
  }

  async function saveOrder() {
    if (!view.value.changed) return;
    try {
      const data = await post('/api/world-stack/reorder', { ids: order.value });
      if (!data.ok) {
        setMessage(data.error || '排序保存失败', 'error');
        return;
      }
      await load();
      setMessage(data.requiresRestart
        ? '已排队：' + (data.jobId || '') + '。重启后提升栈顶；原 world 会压入旧栈。'
        : '排序已保存；目录未改名，下次重启仍保持新顺序。', 'ok');
    } catch (error) {
      setMessage(error.message || '排序保存失败', 'error');
    }
  }

  async function previewImport() {
    const state = importState.value;
    if (state.busy) return;
    setImportField('busy', true);
    setImportField('error', '');
    try {
      preview.value = await post('/api/world-stack/import/preview', {
        path: String(state.path || '').trim(),
        position: state.position,
        afterId: state.afterId,
        deleteSource: false,
      });
      setImportField('confirm', false);
    } catch (error) {
      preview.value = null;
      setImportField('error', error.message || '导入预览失败');
      setMessage(error.message || '导入预览失败', 'error');
    } finally {
      setImportField('busy', false);
    }
  }

  async function runImport() {
    const state = importState.value;
    if (!state.confirm || state.busy) return;
    setImportField('busy', true);
    setImportField('error', '');
    try {
      const data = await post('/api/world-stack/import', {
        path: String(state.path || '').trim(),
        position: state.position,
        afterId: state.afterId,
        deleteSource: false,
      });
      if (!data.ok) {
        preview.value = data;
        setImportField('confirm', false);
        setImportField('error', (data.errors && data.errors[0]) || data.error || '导入排队失败');
        return;
      }
      preview.value = null;
      await load();
      const queued = data.job?.message || ('已排队：' + data.jobId);
      setMessage(queued + '。重启服务器后按队列依次执行；源目录保持不变。', 'ok');
    } catch (error) {
      setImportField('error', error.message || '导入排队失败');
      setMessage(error.message || '导入排队失败', 'error');
    } finally {
      setImportField('busy', false);
    }
  }

  async function cancelImport(jobId) {
    if (!jobId) return;
    if (!window.confirm('取消排队中的导入 ' + jobId + '？源目录不会被修改。')) return;
    try {
      const data = await post('/api/world-stack/import/cancel', { jobId });
      if (!data.ok) {
        setMessage(data.error || '取消导入失败', 'error');
        return;
      }
      await load();
      setMessage('已取消 ' + jobId + '；队列剩余 ' + data.remaining + ' 项。', 'ok');
    } catch (error) {
      setMessage(error.message || '取消导入失败', 'error');
    }
  }

  function chooseIcon(stack) {
    if (!iconInput.value || iconBusy.value) return;
    iconTarget.value = stack.id;
    iconInput.value.value = '';
    iconInput.value.click();
  }

  function setIconInput(element) {
    iconInput.value = element || null;
  }

  async function handleIconFile(file) {
    const id = iconTarget.value;
    if (!file || !id || iconBusy.value) return;
    iconBusy.value = true;
    setMessage('正在压缩并上传 ' + id + ' 的图标…');
    try {
      const payload = await compressIcon(file);
      const data = await post('/api/world-stack/icon', { id, mime: payload.mime, data: payload.data });
      if (!data.ok) {
        setMessage(data.error || '上传图标失败', 'error');
        return;
      }
      await load();
      setMessage('已更新 ' + id + ' 的自定义图标；旧图已原子替换。', 'ok');
    } catch (error) {
      setMessage(error.message || '上传图标失败', 'error');
    } finally {
      iconBusy.value = false;
      if (iconInput.value) iconInput.value.value = '';
      iconTarget.value = '';
    }
  }

  async function deleteIcon(stack) {
    if (iconBusy.value) return;
    if (!window.confirm('恢复 ' + stack.id + ' 的默认图标？只会删除手动上传的图标，'
      + '不会修改存档或世界数据。')) return;
    iconBusy.value = true;
    setMessage('正在恢复默认图标…');
    try {
      const data = await post('/api/world-stack/icon/delete', { id: stack.id });
      if (!data.ok) {
        setMessage(data.error || '恢复默认图标失败', 'error');
        return;
      }
      await load();
      setMessage(data.icon && data.icon.source === 'save'
        ? '已删除自定义图标；继续使用存档自带 icon.png。'
        : '已恢复默认图标；没有存档图标时会回退到出生点地图缩略图。', 'ok');
    } catch (error) {
      setMessage(error.message || '恢复默认图标失败', 'error');
    } finally {
      iconBusy.value = false;
    }
  }

  async function copyPath(path) {
    try {
      await navigator.clipboard.writeText(path);
      return true;
    } catch {
      return false;
    }
  }

  const stacksView = computed(() => stacks());
  const stats = computed(() => stackStats(model.value, stacks()));
  const importQueue = computed(() => importQueueView(model.value));
  const importPreview = computed(() => importPreviewView(preview.value));
  const jobs = computed(() => jobsView(model.value));
  const files = computed(() => filesView(model.value));
  const navItems = computed(() => worldStackNav());
  const afterOptions = computed(() => {
    const list = stacks().filter((stack) => !stack.top).sort((a, b) => a.index - b.index);
    return list.map((lower, index) => ({
      value: lower.id,
      label: lower.id + ' 与 ' + (index + 1 < list.length ? list[index + 1].id : 'world（栈顶）') + ' 之间',
    }));
  });

  return {
    model,
    message,
    messageKind,
    loading,
    order,
    savedOrder,
    preview,
    importState,
    iconInput,
    iconBusy,
    stacks: stacksView,
    stats,
    orderView: view,
    importQueue,
    importPreview,
    jobs,
    files,
    navItems,
    afterOptions,
    load,
    setMessage,
    setImportField,
    moveOrder,
    moveBy,
    moveTo,
    saveOrder,
    previewImport,
    runImport,
    cancelImport,
    chooseIcon,
    handleIconFile,
    deleteIcon,
    copyPath,
    setIconInput,
    byId,
  };
}
