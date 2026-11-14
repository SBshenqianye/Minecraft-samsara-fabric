import { computed, reactive, ref } from 'vue';
import { useApi } from '../../composables/useApi.js';
import { setPageNav } from '../../composables/usePageNav.js';
import { openIdentityDetail } from './openDetail.js';
import {
  allCandidates,
  candidateType,
  dataDisplayName,
  groupView,
  isUuid,
  normalizeSettings,
  poolModel,
  roleLabel,
  shortUuid,
} from './model.js';

let seq = 0;

function cloneModel(model) {
  const config = model?.config || {};
  return {
    ...model,
    config: {
      ...config,
      dataLabels: { ...(config.dataLabels || {}) },
      groups: (config.groups || []).map((group) => ({
        name: group.name || '',
        data: group.data || '',
        premium: group.premium || null,
        offline: group.offline || null,
        note: group.note || '',
      })),
    },
  };
}

/** 身份组页的共享状态与动作；模板只做展示和调用。 */
export function useIdentity() {
  const { request } = useApi();
  const model = ref(null);
  const groups = ref([]);
  const settings = reactive(normalizeSettings());
  const candidates = ref([]);
  const extraCandidates = ref([]);
  const dataList = ref([]);
  const generated = ref([]);
  const labels = reactive({});
  const names = reactive({});
  const types = reactive({});
  const online = reactive({});
  const onlineMode = ref(true);
  const poolFilter = reactive({ text: '', hideProbe: true, hideUnknown: true });
  const message = ref('');
  const messageKind = ref('');
  const dirty = ref(false);
  const loading = ref(false);
  const drag = reactive({ active: false, uuid: '', role: '', groupId: '', copy: false, type: '' });
  const audit = reactive({ visible: false, text: '' });
  const assign = reactive({ open: false, uuid: '', name: '', type: '', groupId: '', role: 'data', error: '' });
  const rebind = reactive({
    open: false, groupId: '', groupName: '', newData: '', mode: 'reject', confirm: false, error: '',
  });
  const uuidDialog = reactive({
    open: false, groupId: '', role: 'data', input: '', selected: '', result: null, error: '', title: '',
  });
  let nameTimer = 0;

  function setMessage(text, kind = '') {
    message.value = text || '';
    messageKind.value = kind;
  }

  function applyModel(next) {
    if (!next) return;
    const copy = cloneModel(next);
    model.value = copy;
    const config = copy.config || {};
    Object.assign(settings, normalizeSettings(config));
    groups.value = (config.groups || []).map((group) => ({
      ...group,
      _id: 'g' + (++seq),
      _originalName: group.name || '',
    }));
    Object.assign(labels, config.dataLabels || {});
    if (Array.isArray(next.candidates)) candidates.value = next.candidates;
    if (typeof next.onlineMode === 'boolean') onlineMode.value = next.onlineMode;
    dirty.value = false;
    refreshNames();
  }

  function findExisting(group) {
    if (!group?._originalName) return null;
    return (model.value?.config?.groups || []).find((item) => item.name === group._originalName) || null;
  }

  function groupById(id) {
    return groups.value.find((group) => group._id === id) || null;
  }

  function markDirty() {
    dirty.value = true;
  }

  async function load() {
    loading.value = true;
    try {
      const result = await request('/api/identity');
      if (!result.ok) throw new Error(result.data?.error || '读取身份组失败');
      applyModel(result.data);
      await refreshDataList();
      setMessage('已读取身份组配置', 'ok');
      return true;
    } catch (error) {
      setMessage(error.message || '读取身份组失败', 'error');
      return false;
    } finally {
      loading.value = false;
    }
  }

  async function refreshDataList() {
    try {
      const result = await request('/api/identity/data/list');
      dataList.value = result.ok && Array.isArray(result.data?.data) ? result.data.data : [];
    } catch {
      dataList.value = [];
    }
  }

  async function refreshNames() {
    window.clearTimeout(nameTimer);
    nameTimer = window.setTimeout(async () => {
      const uuids = new Set();
      for (const group of groups.value) {
        for (const uuid of [group.premium, group.offline]) if (uuid && isUuid(uuid)) uuids.add(uuid);
      }
      for (const item of allCandidates(candidates.value, extraCandidates.value)) {
        if (item.uuid && isUuid(item.uuid)) uuids.add(item.uuid);
      }
      if (!uuids.size) return;
      try {
        const result = await request('/api/identity/resolve', {
          method: 'POST',
          body: JSON.stringify({ uuids: [...uuids] }),
        });
        if (!result.ok) return;
        Object.assign(names, result.data?.names || {});
        Object.assign(types, result.data?.types || {});
        Object.assign(online, result.data?.online || {});
      } catch {
        // 名称解析失败不影响编辑。
      }
    }, 120);
  }

  async function save() {
    if (!groups.value.length) {
      setMessage('至少需要一个身份组；点“新增组”创建。', 'error');
      return false;
    }
    const payload = [];
    for (const group of groups.value) {
      const name = String(group.name || '').trim();
      const data = String(group.data || '').trim();
      if (!name) {
        setMessage('有身份组还没有组名，请先填写。', 'error');
        return false;
      }
      if (!isUuid(data)) {
        setMessage('组「' + name + '」缺少有效的 data UUID。', 'error');
        return false;
      }
      const existing = findExisting(group);
      if (existing && existing.data !== data) {
        setMessage('组「' + name + '」的 data UUID 变了；请先在数据区重新选择，重绑确认会自动弹出。', 'error');
        return false;
      }
      payload.push({
        name,
        data,
        premium: group.premium || null,
        offline: group.offline || null,
        note: group.note || '',
      });
    }
    try {
      const result = await request('/api/identity', {
        method: 'PUT',
        body: JSON.stringify({
          enabled: settings.enabled,
          syncPlayerData: settings.sync,
          maxOwners: Number(settings.maxOwners || 32),
          groups: payload,
        }),
      });
      if (!result.ok || result.data?.ok === false) {
        const errors = result.data?.errors;
        if (errors && Object.keys(errors).length) {
          setMessage(Object.entries(errors).map(([key, value]) => key + '：' + value).join('；'), 'error');
        } else {
          throw new Error(result.data?.error || '保存身份组失败');
        }
        return false;
      }
      applyModel(result.data);
      setMessage(result.data?.message || '身份组配置已保存并热重载', 'ok');
      return true;
    } catch (error) {
      setMessage(error.message || '保存身份组失败', 'error');
      return false;
    }
  }

  async function reload() {
    try {
      const result = await request('/api/identity/reload', { method: 'POST' });
      if (!result.ok || result.data?.ok === false) {
        throw new Error(result.data?.error || '重新读取失败');
      }
      applyModel(result.data);
      setMessage(result.data?.message || '身份组已重载', 'ok');
      return true;
    } catch (error) {
      setMessage(error.message || '重新读取失败', 'error');
      return false;
    }
  }

  async function runAudit() {
    try {
      const result = await request('/api/identity/audit');
      if (!result.ok) throw new Error(result.data?.error || '审计失败');
      audit.visible = true;
      audit.text = result.data?.text || '';
      setMessage('审计完成', 'ok');
    } catch (error) {
      setMessage(error.message || '审计失败', 'error');
    }
  }

  async function generate(copy) {
    try {
      const result = await request('/api/identity/generate', { method: 'POST' });
      const uuid = result.ok ? result.data?.data : '';
      if (!uuid) throw new Error(result.data?.error || '生成 data UUID 失败');
      if (!generated.value.includes(uuid)) generated.value = [...generated.value, uuid];
      if (copy) await copyText(uuid);
      setMessage('已生成空白 data UUID：' + uuid + (copy ? '（已尝试复制）' : '')
        + '；把它放到某个组的数据区，或直接用账号数据候选。', 'ok');
      return uuid;
    } catch (error) {
      setMessage(error.message || '生成 data UUID 失败', 'error');
      return null;
    }
  }

  async function addGroup() {
    const uuid = await generate(false);
    groups.value = [...groups.value, {
      name: '新身份组 ' + (groups.value.length + 1),
      data: uuid || '',
      premium: null,
      offline: null,
      note: '',
      _id: 'g' + (++seq),
      _originalName: '',
    }];
    markDirty();
  }

  function removeGroup(group) {
    if (!window.confirm('确认删除身份组「' + (group.name || '未命名') + '」？不会删除玩家数据。')) return;
    groups.value = groups.value.filter((item) => item._id !== group._id);
    markDirty();
  }

  function updateGroup(group, patch) {
    Object.assign(group, patch);
    markDirty();
  }

  function setAccountName(uuid, name) {
    if (uuid && name && !names[uuid]) names[uuid] = name;
  }

  function assignRole(groupId, role, uuid, name) {
    const group = groupById(groupId);
    if (!group || !uuid) return;
    if (!isUuid(uuid)) {
      setMessage('UUID 格式不正确：' + uuid, 'error');
      return;
    }
    const all = allCandidates(candidates.value, extraCandidates.value);
    const accountType = candidateType(uuid, all, types) || drag.type || '';
    if (role === 'premium' && accountType === 'offline') {
      setMessage('这是离线账号 UUID，只能放进“离线账号 UUID”区域；正版账号和离线账号的 UUID 是两套不同的值。', 'error');
      return;
    }
    if (role === 'offline' && accountType === 'premium') {
      setMessage('这是正版账号 UUID，只能放进“正版账号 UUID”区域；正版账号和离线账号的 UUID 是两套不同的值。', 'error');
      return;
    }
    const existing = findExisting(group);
    if (role === 'data') {
      if (existing && group.data !== uuid) {
        openRebind(group, uuid);
        return;
      }
      group.data = uuid;
    } else {
      const dataOwner = groups.value.find((item) => item._id !== groupId && item.data === uuid);
      if (dataOwner) {
        setMessage('这个 UUID 已经是「' + (dataOwner.name || '未命名') + '」的数据 UUID，不能作为正版账号/离线账号入口。', 'error');
        return;
      }
      for (const other of groups.value) {
        if (other._id === groupId) continue;
        if (other.premium === uuid) other.premium = null;
        if (other.offline === uuid) other.offline = null;
      }
      if (role === 'premium' && group.offline === uuid) group.offline = null;
      if (role === 'offline' && group.premium === uuid) group.premium = null;
      group[role] = uuid;
    }
    setAccountName(uuid, name);
    markDirty();
    setMessage('已把 ' + shortUuid(uuid) + ' 放到「' + (group.name || '未命名') + '」的 '
      + roleLabel(role) + ' 区域；记得保存。', 'ok');
  }

  function clearRole(groupId, role) {
    const group = groupById(groupId);
    if (!group) return;
    if (role === 'data') {
      if (!window.confirm('清空这个组的 data UUID？\n不会删除玩家数据；保存前必须重新指定 data，否则无法保存。')) {
        return;
      }
      if (group.data && !generated.value.includes(group.data)) {
        generated.value = [...generated.value, group.data];
      }
      group.data = '';
      markDirty();
      setMessage('已清空 data UUID；请重新指定后再保存。', 'ok');
      return;
    }
    group[role] = null;
    markDirty();
  }

  function startDrag(payload) {
    Object.assign(drag, { active: true, ...payload });
  }

  function endDrag() {
    drag.active = false;
  }

  function dropOn(groupId, role) {
    if (!drag.active) return;
    assignRole(groupId, role, drag.uuid, '');
    endDrag();
  }

  async function pasteIntoZone(groupId, role) {
    try {
      if (!navigator.clipboard?.readText) {
        setMessage('浏览器不允许读取剪贴板；请用“选择”输入框粘贴。', 'error');
        return;
      }
      const text = (await navigator.clipboard.readText()).trim();
      if (!isUuid(text)) {
        setMessage('剪贴板里不是完整 UUID：' + (text || '空'), 'error');
        return;
      }
      assignRole(groupId, role, text, '');
    } catch {
      setMessage('读取剪贴板失败；请用“选择”输入框粘贴。', 'error');
    }
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(String(text || ''));
      return true;
    } catch {
      return false;
    }
  }

  async function copyUuid(uuid) {
    await copyText(uuid);
    setMessage('已复制 ' + uuid, 'ok');
  }

  async function editLabel(uuid) {
    const meta = labels[uuid] || {};
    const name = window.prompt('给这个 data UUID 起个名字（留空清除）\n'
      + uuid + '\n格式建议：所有者 + 用途 + 日期，例如「小明 主号 2026-01」',
    meta.name || dataDisplayName(uuid, labels, dataList.value) || '');
    if (name === null) return;
    const note = window.prompt('备注（可留空）\n用于区分借出/归还记录', meta.note || '');
    if (note === null) return;
    try {
      const result = await request('/api/identity/data/label', {
        method: 'POST',
        body: JSON.stringify({ uuid, name: name.trim(), note: note.trim() }),
      });
      if (!result.ok || result.data?.ok === false) throw new Error(result.data?.error || '保存失败');
      labels[uuid] = { name: name.trim(), note: note.trim() };
      await refreshDataList();
      setMessage(result.data?.message || '已保存 data UUID 元数据', 'ok');
    } catch (error) {
      setMessage(error.message || '保存 data UUID 元数据失败', 'error');
    }
  }

  async function backupData(uuid) {
    if (!window.confirm('备份 data UUID ' + uuid + ' 的玩家数据、成就与统计？')) return false;
    try {
      const result = await request('/api/identity/data/backup', {
        method: 'POST',
        body: JSON.stringify({ uuid }),
      });
      if (!result.ok || result.data?.ok === false) throw new Error(result.data?.error || '备份失败');
      setMessage((result.data.message || '已备份') + (result.data.path ? ' · ' + result.data.path : ''), 'ok');
      await refreshDataList();
      return true;
    } catch (error) {
      setMessage(error.message || '备份失败', 'error');
      return false;
    }
  }

  async function fetchBackups(uuid) {
    const result = await request('/api/identity/data/backups?uuid=' + encodeURIComponent(uuid));
    if (!result.ok) throw new Error(result.data?.error || '读取备份失败');
    return {
      root: result.data?.root || '',
      rows: Array.isArray(result.data?.backups) ? result.data.backups : [],
    };
  }

  async function fetchDetail(uuid) {
    const result = await request('/api/identity/data?uuid=' + encodeURIComponent(uuid));
    if (!result.ok) throw new Error(result.data?.error || '读取详情失败');
    return result.data;
  }

  async function previewGroup(group, onText) {
    onText('正在预览…');
    try {
      const result = await request('/api/identity/preview', {
        method: 'POST',
        body: JSON.stringify({
          group: group.name,
          data: group.data,
          uuids: [group.premium, group.offline].filter(Boolean),
        }),
      });
      if (!result.ok || result.data?.ok === false) throw new Error(result.data?.error || '预览失败');
      onText(result.data.text || JSON.stringify(result.data, null, 2));
    } catch (error) {
      onText(error.message || '预览失败');
    }
  }

  async function resolveIdentity(value) {
    const result = await request('/api/identity/resolve', {
      method: 'POST',
      body: JSON.stringify(isUuid(value) ? { uuids: [value] } : { name: value }),
    });
    if (!result.ok) throw new Error(result.data?.error || '解析失败');
    Object.assign(names, result.data?.names || {});
    Object.assign(types, result.data?.types || {});
    if (result.data?.online) Object.assign(online, result.data.online);
    return result.data;
  }

  async function resolveToolbarValue(value) {
    const raw = String(value || '').trim();
    if (!raw) {
      setMessage('请输入玩家名或 UUID。', 'error');
      return null;
    }
    try {
      const result = await resolveIdentity(raw);
      for (const [type, uuid, name] of [
        ['premium', result.premium, result.name || raw],
        ['offline', result.offline, result.name || raw],
      ]) {
        if (uuid && !extraCandidates.value.some((item) => item.uuid === uuid)) {
          extraCandidates.value = [...extraCandidates.value, { uuid, name: name || '', type }];
        }
      }
      for (const [uuid, name] of Object.entries(result.names || {})) {
        if (uuid === result.premium || uuid === result.offline) continue;
        if (!extraCandidates.value.some((item) => item.uuid === uuid)) {
          extraCandidates.value = [...extraCandidates.value, { uuid, name, type: '' }];
        }
      }
      refreshNames();
      setMessage('解析完成；结果已放进“未进组账号”，拖到卡片即可。', 'ok');
      return result;
    } catch (error) {
      setMessage(error.message || '解析失败', 'error');
      return null;
    }
  }

  async function resolveToolbar() {
    const input = document.getElementById('identity-resolve-input');
    return resolveToolbarValue(input?.value || '');
  }

  function openAssign(uuid, name, role, type) {
    if (!groups.value.length) {
      setMessage('还没有身份组，请先点右上角“新增组”。', 'error');
      return;
    }
    assign.open = true;
    assign.uuid = uuid;
    assign.name = name || '';
    assign.type = type || '';
    assign.groupId = groups.value[0]?._id || '';
    assign.role = role || 'data';
    assign.error = '';
  }

  function applyAssign() {
    const group = groupById(assign.groupId);
    if (!group) {
      assign.error = '请选择目标组。';
      return;
    }
    assign.open = false;
    assignRole(assign.groupId, assign.role, assign.uuid, assign.name);
  }

  function openUuid(group, role) {
    uuidDialog.open = true;
    uuidDialog.groupId = group._id;
    uuidDialog.role = role;
    uuidDialog.title = '设置「' + (group.name || '未命名') + '」的 ' + roleLabel(role) + ' UUID';
    uuidDialog.input = group[role] || '';
    uuidDialog.selected = '';
    uuidDialog.result = null;
    uuidDialog.error = '';
    if (uuidDialog.input) resolveUuidDialog(uuidDialog.input);
  }

  async function resolveUuidDialog(value) {
    if (!value) {
      uuidDialog.result = null;
      return;
    }
    try {
      uuidDialog.result = await resolveIdentity(value);
    } catch (error) {
      uuidDialog.error = error.message || '解析失败';
    }
  }

  function applyUuid() {
    const value = String(uuidDialog.input || '').trim();
    let uuid = uuidDialog.selected;
    if (!uuid && isUuid(value)) uuid = value;
    if (!uuid) {
      uuidDialog.error = '请选择解析出的正版账号/离线账号 UUID，或直接输入完整 UUID。';
      return;
    }
    const all = allCandidates(candidates.value, extraCandidates.value);
    const type = candidateType(uuid, all, types);
    if ((uuidDialog.role === 'premium' && type === 'offline')
      || (uuidDialog.role === 'offline' && type === 'premium')) {
      uuidDialog.error = '类型不匹配：' + (type === 'offline' ? '离线账号' : '正版账号')
        + ' UUID 不能放进“' + roleLabel(uuidDialog.role) + ' UUID”区域。';
      return;
    }
    uuidDialog.open = false;
    assignRole(uuidDialog.groupId, uuidDialog.role, uuid, '');
  }

  function clearUuid() {
    const groupId = uuidDialog.groupId;
    const role = uuidDialog.role;
    uuidDialog.open = false;
    clearRole(groupId, role);
  }

  function openRebind(group, newData) {
    if (!findExisting(group)) {
      group.data = newData;
      markDirty();
      return;
    }
    rebind.open = true;
    rebind.groupId = group._id;
    rebind.groupName = group.name || '';
    rebind.newData = newData || '';
    rebind.mode = 'reject';
    rebind.confirm = false;
    rebind.error = '';
  }

  async function applyRebind() {
    const group = groupById(rebind.groupId);
    if (!group) return;
    if (!rebind.confirm) {
      rebind.error = '请先勾选“我已了解会先备份旧数据与目标数据”。';
      return;
    }
    let newData = String(rebind.newData || '').trim();
    if (!newData) {
      newData = await generate(false);
      if (!newData) return;
    }
    if (!isUuid(newData)) {
      rebind.error = '新的 data UUID 格式不正确。';
      return;
    }
    const existing = findExisting(group);
    rebind.error = '';
    try {
      const result = await request('/api/identity/rebind', {
        method: 'POST',
        body: JSON.stringify({
          group: existing ? existing.name : group.name,
          newData,
          mode: rebind.mode,
          confirm: true,
        }),
      });
      if (!result.ok || result.data?.ok === false) throw new Error(result.data?.error || '重绑失败');
      rebind.open = false;
      applyModel(result.data);
      setMessage(result.data?.message || 'data UUID 已重绑', 'ok');
    } catch (error) {
      rebind.error = error.message || '重绑失败';
    }
  }

  function createDraftGroup(cluster) {
    const premium = cluster.premium;
    const offline = cluster.offline;
    const first = premium || offline;
    if (!first) return;
    let name = String(first.name || '新身份组').replace(/^玩家-[0-9a-f]{8}$/i, '').trim() || '新身份组';
    if (groups.value.some((group) => group.name.toLowerCase() === name.toLowerCase())) {
      name += ' (推荐)';
    }
    groups.value = [...groups.value, {
      name,
      data: first.uuid,
      premium: premium ? premium.uuid : null,
      offline: offline ? offline.uuid : null,
      note: '由相似 ID 推荐创建，请确认',
      _id: 'g' + (++seq),
      _originalName: '',
    }];
    markDirty();
    setMessage('已创建草稿组「' + name + '」；同类型多余账号仍需手动处理，确认后再保存。', 'ok');
  }

  function openInventory(group) {
    window.dispatchEvent(new CustomEvent('samsara:identity-inventory', {
      detail: { data: group.data || '', name: group.name || '', scope: 'inventory' },
    }));
  }

  function openDetail(uuid) {
    openIdentityDetail(api, uuid);
  }

  const pool = computed(() => poolModel({
    groups: groups.value,
    candidates: candidates.value,
    extra: extraCandidates.value,
    dataList: dataList.value,
    generated: generated.value,
    modelStates: model.value?.states || {},
    labels,
    online,
    filter: poolFilter,
  }));

  const navItems = computed(() => {
    const items = groups.value.map((group) => ({
      label: group.name || '未命名组',
      count: [group.data, group.premium, group.offline].filter(Boolean).length,
      target: 'identity-group-' + group._id,
      children: [
        { label: '数据 UUID · ' + (group.data ? (names[group.data] || dataDisplayName(group.data, labels, dataList.value) || '已设置') : '未设置'), target: 'identity-group-' + group._id },
        { label: '正版账号 · ' + (group.premium ? (names[group.premium] || shortUuid(group.premium)) : '未设置'), target: 'identity-group-' + group._id },
        { label: '离线账号 · ' + (group.offline ? (names[group.offline] || shortUuid(group.offline)) : '未设置'), target: 'identity-group-' + group._id },
      ],
    }));
    items.push({ label: '未进组账号 / data UUID 库', target: 'identity-pool-panel' });
    return items;
  });

  function syncNav() {
    setPageNav({ title: '身份组导航', count: groups.value.length, items: navItems.value });
  }

  function groupDetail(group) {
    return groupView(group, groups.value, model.value?.states || {}, online);
  }

  function setUuidInput(value) {
    uuidDialog.input = value;
  }

  function setAssignField(key, value) {
    assign[key] = value;
  }

  function setRebindField(key, value) {
    rebind[key] = value;
  }

  function closeDialog(name) {
    if (name === 'uuid') uuidDialog.open = false;
    if (name === 'assign') assign.open = false;
    if (name === 'rebind') rebind.open = false;
  }

  function selectUuid(uuid) {
    uuidDialog.selected = uuid;
    uuidDialog.input = uuid;
    uuidDialog.error = '';
  }

  function setUuidError(text) {
    uuidDialog.error = text || '';
  }

  const api = {
    model,
    groups,
    settings,
    poolFilter,
    message,
    messageKind,
    dirty,
    loading,
    audit,
    assign,
    rebind,
    uuidDialog,
    pool,
    navItems,
    names,
    types,
    online,
    labels,
    dataList,
    load,
    save,
    reload,
    runAudit,
    generate,
    addGroup,
    removeGroup,
    updateGroup,
    assignRole,
    clearRole,
    startDrag,
    endDrag,
    dropOn,
    pasteIntoZone,
    copyUuid,
    copyText,
    editLabel,
    backupData,
    fetchBackups,
    fetchDetail,
    previewGroup,
    resolveIdentity,
    resolveToolbar,
    resolveToolbarValue,
    resolveUuidDialog,
    openAssign,
    applyAssign,
    openUuid,
    applyUuid,
    clearUuid,
    openRebind,
    applyRebind,
    createDraftGroup,
    openInventory,
    findExisting,
    groupById,
    setMessage,
    syncNav,
    groupDetail,
    markDirty,
    openDetail,
    setUuidInput,
    setAssignField,
    setRebindField,
    closeDialog,
    selectUuid,
    setUuidError,
  };
  return api;
}
