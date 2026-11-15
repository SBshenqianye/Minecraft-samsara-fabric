import { computed, ref } from 'vue';
import { useApi } from '../../composables/useApi.js';
import { clientErrors, previewFragments } from './model.js';

export function cloneParts(parts) {
  return JSON.parse(JSON.stringify(parts || []));
}

/** 输出格式页状态：消息、草稿、预览与保存。 */
export function useOutput() {
  const { request } = useApi();
  const model = ref(null);
  const key = ref('');
  const parts = ref([]);
  const message = ref('');
  const messageKind = ref('');
  const loading = ref(false);
  const saving = ref(false);
  const autoContinue = ref(true);
  const fragments = ref([]);
  const previewHint = ref('');
  let previewSeq = 0;
  const editorVersion = ref(0);

  function bumpEditor() {
    editorVersion.value += 1;
  }

  function setMessage(text, kind = '') {
    message.value = text || '';
    messageKind.value = kind;
  }

  const selected = computed(() => (model.value?.messages || [])
    .find((item) => item.key === key.value) || null);
  const errors = computed(() => clientErrors(parts.value, selected.value));

  function select(nextKey) {
    if (!model.value) return;
    const exists = (model.value.messages || []).some((item) => item.key === nextKey);
    if (!exists) return;
    key.value = nextKey;
    const target = model.value.messages.find((item) => item.key === nextKey);
    parts.value = target ? cloneParts(target.parts) : [];
    bumpEditor();
    schedulePreview();
  }

  function setParts(next) {
    parts.value = next || [];
    schedulePreview();
  }

  async function load() {
    loading.value = true;
    try {
      const result = await request('/api/output');
      if (!result.ok) throw new Error(result.data?.error || '读取输出格式失败');
      model.value = result.data;
      autoContinue.value = result.data?.autoContinuePrefix !== false;
      const exists = (model.value.messages || []).some((item) => item.key === key.value);
      if (!exists) key.value = model.value.messages?.[0]?.key || '';
      select(key.value);
      setMessage('已读取 ' + (model.value.messages || []).length + ' 条输出消息', 'ok');
      return true;
    } catch (error) {
      setMessage(error.message || '读取输出格式失败', 'error');
      return false;
    } finally {
      loading.value = false;
    }
  }

  let previewTimer = 0;
  function schedulePreview() {
    window.clearTimeout(previewTimer);
    previewTimer = window.setTimeout(renderPreview, 260);
  }

  async function renderPreview() {
    const current = ++previewSeq;
    fragments.value = [];
    previewHint.value = '';
    if (!key.value) return;
    if (errors.value.length) {
      previewHint.value = '修正模板后才会生成预览：' + errors.value.join('；');
      return;
    }
    try {
      const result = await request('/api/output/preview', {
        method: 'POST',
        body: JSON.stringify({ key: key.value, parts: parts.value }),
      });
      if (current !== previewSeq) return;
      if (!result.ok || result.data?.ok === false) {
        throw new Error(result.data?.error || '预览失败');
      }
      fragments.value = previewFragments(result.data);
      if (!fragments.value.length) previewHint.value = '（预览为空）';
    } catch (error) {
      if (current !== previewSeq) return;
      previewHint.value = error.message || '预览失败';
    }
  }

  async function save() {
    const current = selected.value;
    if (!current) return false;
    saving.value = true;
    try {
      const result = await request('/api/output', {
        method: 'PUT',
        body: JSON.stringify({ key: key.value, parts: parts.value }),
      });
      if (!result.ok || result.data?.ok === false) {
        throw new Error(result.data?.error || '保存失败');
      }
      model.value = result.data.output;
      parts.value = cloneParts(selected.value?.parts || parts.value);
      setMessage(result.data?.message || '已保存并重载', 'ok');
      await renderPreview();
      return true;
    } catch (error) {
      setMessage(error.message || '保存失败', 'error');
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function reload() {
    try {
      const result = await request('/api/output/reload', { method: 'POST' });
      if (!result.ok || result.data?.ok === false) {
        throw new Error(result.data?.error || '重新读取失败');
      }
      model.value = result.data.output;
      const exists = (model.value.messages || []).some((item) => item.key === key.value);
      if (!exists) key.value = model.value.messages?.[0]?.key || '';
      select(key.value);
      setMessage(result.data?.message || '输出模板已重载', 'ok');
      return true;
    } catch (error) {
      setMessage(error.message || '重新读取失败', 'error');
      return false;
    }
  }

  function restoreDefault() {
    const current = selected.value;
    if (!current) return;
    parts.value = cloneParts(current.defaultParts);
    bumpEditor();
    setMessage('已恢复系统默认模板；点“保存并重载”后生效', 'ok');
    schedulePreview();
  }

  async function setAutoContinue(enabled) {
    autoContinue.value = enabled;
    try {
      const result = await request('/api/config', {
        method: 'PUT',
        body: JSON.stringify({
          file: 'commands',
          values: { 'output.autoContinuePrefix': enabled },
        }),
      });
      if (!result.ok || result.data?.ok === false) {
        throw new Error(result.data?.error || '保存自动续行前缀失败');
      }
      model.value = result.data?.config || model.value;
      setMessage('已' + (enabled ? '开启' : '关闭') + '自动续行前缀', 'ok');
      await renderPreview();
    } catch (error) {
      autoContinue.value = !enabled;
      setMessage(error.message || '保存自动续行前缀失败', 'error');
    }
  }

  return {
    model,
    key,
    parts,
    selected,
    errors,
    message,
    messageKind,
    loading,
    saving,
    autoContinue,
    editorVersion,
    bumpEditor,
    fragments,
    previewHint,
    load,
    select,
    setParts,
    setMessage,
    schedulePreview,
    renderPreview,
    save,
    reload,
    restoreDefault,
    setAutoContinue,
    cloneParts,
  };
}
