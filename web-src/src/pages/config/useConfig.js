import { computed, reactive, ref } from 'vue';
import { useApi } from '../../composables/useApi.js';
import {
  configTotalEntries,
  currentValue as modelCurrentValue,
  fileValues,
  matchesQuery,
  valueKey,
} from './model.js';

/** 高级配置/圈地范围共用：模型、草稿、保存、重载与条件显示。 */
export function useConfig({ includeClaim = false } = {}) {
  const { request } = useApi();
  const model = ref(null);
  const status = ref(null);
  const edits = reactive(new Map());
  const entryErrors = reactive(new Map());
  const search = ref('');
  const message = ref('');
  const messageKind = ref('');
  const loading = ref(false);
  const saving = ref(false);
  const invalidCurves = reactive(new Set());
  const listeners = new Set();

  function notify(path) {
    for (const listener of listeners) {
      try { listener(path); } catch { /* 监听器异常不影响编辑 */ }
    }
  }

  function onChange(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  }

  function setMessage(text, kind = '') {
    message.value = text || '';
    messageKind.value = kind;
  }

  function currentValue(file, entry) {
    return modelCurrentValue(edits, file.id, entry);
  }

  function setValue(file, entry, value) {
    edits.set(valueKey(file.id, entry.path), value);
    entryErrors.delete(entry.path);
    notify(file.id + '|' + entry.path);
  }

  function resetEntry(file, entry) {
    setValue(file, entry, entry.default);
  }

  function isCurveInvalid(file, entry) {
    return invalidCurves.has(valueKey(file.id, entry.path));
  }

  function setCurveInvalid(file, entry, valid) {
    const key = valueKey(file.id, entry.path);
    if (valid) invalidCurves.delete(key);
    else invalidCurves.add(key);
  }

  function applyModel(next) {
    model.value = next || null;
    edits.clear();
    entryErrors.clear();
    invalidCurves.clear();
  }

  async function loadStatus() {
    try {
      const result = await request('/api/status');
      if (result.ok) status.value = result.data || null;
    } catch {
      // 状态读取失败不影响配置编辑。
    }
  }

  async function load() {
    loading.value = true;
    try {
      const result = await request('/api/config');
      if (!result.ok) throw new Error(result.data?.error || '读取配置失败');
      applyModel(result.data);
      setMessage('已读取 ' + (result.data.files?.length || 0) + ' 个配置文件', 'ok');
      return true;
    } catch (error) {
      setMessage(error.message || '读取配置失败', 'error');
      return false;
    } finally {
      loading.value = false;
    }
  }

  async function save(onlyFile) {
    if (!model.value) {
      setMessage('还没有读取配置', 'error');
      return false;
    }
    if (!onlyFile && invalidCurves.size) {
      setMessage('轨迹保留曲线有非法值，已阻止保存', 'error');
      return false;
    }
    saving.value = true;
    setMessage('');
    entryErrors.clear();
    let saved = 0;
    try {
      for (const file of model.value.files || []) {
        if (onlyFile && file.id !== onlyFile) continue;
        if (file.id === 'claim-range' && !includeClaim && !onlyFile) continue;
        const result = await request('/api/config', {
          method: 'PUT',
          body: JSON.stringify({ file: file.id, values: fileValues(file, edits) }),
        });
        if (!result.ok || result.data?.ok === false) {
          const fields = result.data?.fields || {};
          if (Object.keys(fields).length) {
            for (const [path, text] of Object.entries(fields)) entryErrors.set(path, text);
            setMessage(file.name + ' · ' + Object.keys(fields).length + ' 个字段未通过校验，未保存', 'error');
            return false;
          }
          throw new Error(result.data?.error || '保存失败');
        }
        if (result.data?.config) model.value = result.data.config;
        saved++;
      }
      if (onlyFile) {
        for (const key of [...edits.keys()]) {
          if (key.startsWith(onlyFile + ':')) edits.delete(key);
        }
      } else {
        edits.clear();
        invalidCurves.clear();
      }
      setMessage('已保存并重载 ' + saved + ' 个配置文件', 'ok');
      await loadStatus();
      return true;
    } catch (error) {
      setMessage(error.message || '保存失败', 'error');
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function reload() {
    loading.value = true;
    try {
      const result = await request('/api/reload', { method: 'POST' });
      if (!result.ok || result.data?.ok === false) {
        throw new Error(result.data?.error || '重新读取失败');
      }
      applyModel(result.data?.config || null);
      setMessage(result.data?.message || '已重新读取', 'ok');
      await loadStatus();
      return true;
    } catch (error) {
      setMessage(error.message || '重新读取失败', 'error');
      return false;
    } finally {
      loading.value = false;
    }
  }

  const files = computed(() => (model.value?.files || []));
  const configFiles = computed(() => files.value.filter((file) => file.id !== 'claim-range'));
  const claimFile = computed(() => files.value.find((file) => file.id === 'claim-range') || null);
  const total = computed(() => configTotalEntries(model.value));
  const shown = computed(() => configFiles.value
    .reduce((sum, file) => sum + (file.entries || []).filter((entry) =>
      matchesQuery(file, entry, search.value)).length, 0));
  const summary = computed(() => (model.value ? shown.value + ' / ' + total.value + ' 项' : ''));
  const warnings = computed(() => (status.value?.warnings || []));
  const chips = computed(() => {
    const value = status.value;
    if (!value) return [];
    return [
      ['Web 版本', value.webVersion],
      ['模组版本', value.version],
      ['Minecraft', value.minecraft],
      ['配置根', value.configRoot],
      ['世界数据', value.worldDataRoot],
      ['身份数据', value.identityRoot],
    ].map(([label, text]) => [label, text == null || text === '' ? '—' : String(text)]);
  });

  return {
    model,
    status,
    edits,
    entryErrors,
    search,
    message,
    messageKind,
    loading,
    saving,
    warnings,
    files,
    configFiles,
    claimFile,
    total,
    shown,
    summary,
    chips,
    load,
    loadStatus,
    reload,
    save,
    currentValue,
    setValue,
    resetEntry,
    setMessage,
    onChange,
    isCurveInvalid,
    setCurveInvalid,
  };
}
