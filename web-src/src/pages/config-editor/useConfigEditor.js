import { computed, reactive, ref } from 'vue';
import { ElMessageBox } from 'element-plus';
import { useApi } from '../../composables/useApi.js';
import {
  collectValues,
  fieldGroups,
  filteredPages,
  navModel,
  pageKey,
  pageSummary,
  pageTitle,
} from './model.js';

/** PageSpec/FormSpec 配置编辑器状态；页面与第三方模组页共用。 */
export function useConfigEditor() {
  const { request } = useApi();
  const pages = ref([]);
  const loaded = ref(false);
  const current = ref(null);
  const filter = ref('');
  const edits = reactive(new Map());
  const fieldErrors = reactive(new Map());
  const message = ref('');
  const messageKind = ref('');
  const sourceFilter = ref(null);
  const previewOpen = ref(false);
  const previewInfo = reactive({ sub: '', diff: '', error: '', changed: 0 });
  const pending = ref(null);
  const loading = ref(false);

  function setMessage(text, kind = '') {
    message.value = text || '';
    messageKind.value = kind;
  }

  function pageById(id) {
    return pages.value.find((page) => page.id === id) || null;
  }

  function sourcePages() {
    return sourceFilter.value
      ? pages.value.filter((page) => (page.source || 'samsara') === sourceFilter.value)
      : pages.value;
  }

  function setSourceFilter(source) {
    const next = ['samsara', 'server', 'third-party'].includes(source) ? source : null;
    if (sourceFilter.value === next) return;
    sourceFilter.value = next;
    if (!loaded.value) return;
    const list = sourcePages();
    if (current.value && !list.some((page) => page.id === current.value.id)) {
      current.value = list.length ? list[0] : null;
      edits.clear();
    }
  }

  async function load(preferredId) {
    loading.value = true;
    try {
      const result = await request('/api/config/pages');
      if (!result.ok) throw new Error(result.data?.error || '读取配置页面失败');
      pages.value = Array.isArray(result.data?.pages) ? result.data.pages : [];
      loaded.value = true;
      edits.clear();
      fieldErrors.clear();
      const list = sourcePages();
      const target = preferredId && list.some((page) => page.id === preferredId) ? preferredId
        : current.value && list.some((page) => page.id === current.value.id) ? current.value.id
          : list.length ? list[0].id : '';
      current.value = target ? pageById(target) : null;
      return true;
    } catch (error) {
      setMessage(error.message || '读取配置页面失败', 'error');
      return false;
    } finally {
      loading.value = false;
    }
  }

  function openPage(id) {
    const page = pageById(id);
    if (!page) return false;
    current.value = page;
    edits.clear();
    fieldErrors.clear();
    return true;
  }

  function currentValue(field) {
    const page = current.value;
    if (!page) return undefined;
    const edit = edits.get(pageKey(page.id, field.path));
    if (edit !== undefined) return edit;
    if (Object.prototype.hasOwnProperty.call(page.values || {}, field.path)) {
      return page.values[field.path];
    }
    return field.defaultValue;
  }

  function setField(field, value) {
    if (!current.value) return;
    edits.set(pageKey(current.value.id, field.path), value);
    fieldErrors.delete(field.path);
  }

  const filtered = computed(() => filteredPages(pages.value, sourceFilter.value, filter.value));
  const summary = computed(() => pageSummary(sourcePages().length, filtered.value.length,
    filter.value));
  const navItems = computed(() => navModel(pages.value, sourceFilter.value, filter.value));
  const groups = computed(() => (current.value ? fieldGroups(current.value, filter.value) : []));
  const dirty = computed(() => edits.size > 0);

  async function preview() {
    const page = current.value;
    if (!page) return;
    setMessage('正在校验…');
    const collected = collectValues(page, edits);
    if (Object.keys(collected.errors).length) {
      for (const [path, text] of Object.entries(collected.errors)) fieldErrors.set(path, text);
      setMessage('请先修正字段错误。', 'error');
      return;
    }
    try {
      const result = await request('/api/config/preview', {
        method: 'POST',
        body: JSON.stringify({ file: page.id, values: collected.values }),
      });
      const data = result.data || {};
      if (!result.ok || data.ok === false) {
        for (const [path, text] of Object.entries(data.fields || {})) fieldErrors.set(path, text);
        setMessage(data.error || '预览失败', 'error');
        return;
      }
      pending.value = {
        page,
        values: collected.values,
        revision: data.revision || page.revision || '',
      };
      previewInfo.sub = (data.file || '') + ' · 将修改 ' + (data.changed || 0) + ' 项'
        + (data.requiresConfirmation ? ' · 确认后写入并备份' : '');
      previewInfo.diff = data.diff || '没有字段变化。';
      previewInfo.error = '';
      previewInfo.changed = data.changed || 0;
      previewOpen.value = true;
    } catch (error) {
      setMessage(error.message || '预览失败', 'error');
    }
  }

  async function confirmSave() {
    const data = pending.value;
    if (!data) return;
    try {
      const result = await request('/api/config', {
        method: 'PUT',
        body: JSON.stringify({
          file: data.page.id,
          source: data.page.source,
          values: data.values,
          revision: data.revision,
          confirm: true,
        }),
      });
      const body = result.data || {};
      if (!result.ok || body.ok === false) {
        for (const [path, text] of Object.entries(body.fields || {})) fieldErrors.set(path, text);
        previewInfo.error = body.error || '保存失败';
        return;
      }
      pending.value = null;
      edits.clear();
      previewOpen.value = false;
      setMessage(body.message || '已保存', 'ok');
      await load(data.page.id);
    } catch (error) {
      previewInfo.error = error.message || '保存失败';
    }
  }

  function cancelPreview() {
    pending.value = null;
    previewOpen.value = false;
  }

  async function reloadPage() {
    if (edits.size > 0) {
      try {
        await ElMessageBox.confirm(
          `有 ${edits.size} 处未保存修改，重新读取会全部丢弃。`,
          '重新读取配置',
          { type: 'warning', confirmButtonText: '丢弃并重读', cancelButtonText: '取消' },
        );
      } catch {
        return false;
      }
    }
    try {
      await request('/api/reload', { method: 'POST' });
    } catch {
      // 第三方/服务器配置不依赖 samsara 重载；继续刷新页面即可。
    }
    const ok = await load(current.value?.id);
    if (!ok) return false;
    setMessage('已重新读取配置。', 'ok');
    return true;
  }

  return {
    pages,
    loaded,
    current,
    filter,
    edits,
    fieldErrors,
    message,
    messageKind,
    sourceFilter,
    previewOpen,
    previewInfo,
    loading,
    filtered,
    summary,
    navItems,
    groups,
    dirty,
    pageTitle,
    currentValue,
    setField,
    setMessage,
    setSourceFilter,
    load,
    openPage,
    preview,
    confirmSave,
    cancelPreview,
    reloadPage,
  };
}
