/** 身份组页的纯展示逻辑：UUID、账号类型、设置与池子过滤。 */

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function isUuid(value) {
  return UUID_RE.test(String(value || ''));
}

export function shortUuid(uuid) {
  if (!uuid) return '—';
  return uuid.length > 13 ? uuid.slice(0, 8) + '…' + uuid.slice(-4) : uuid;
}

export function typeLabel(type) {
  if (type === 'premium') return '正版账号';
  if (type === 'offline') return '离线账号';
  if (type === 'data') return '数据';
  return '账号';
}

/** 配置 → 页面设置；缺省值与后端一致。 */
export function normalizeSettings(config) {
  const value = config || {};
  return {
    enabled: Boolean(value.enabled),
    sync: value.syncPlayerData !== false,
    maxOwners: Number(value.maxOwners || 32),
  };
}

/** 一条池子候选的显示文本与不稳定标记。 */
export function candidateView(item) {
  const uuid = item?.uuid || '';
  const name = item?.name || shortUuid(uuid);
  return {
    uuid,
    name,
    meta: (item?.online ? '在线 · ' : '') + shortUuid(uuid),
    type: item?.type || 'data',
    typeLabel: typeLabel(item?.type),
    online: Boolean(item?.online),
  };
}

/** 池子过滤：搜索 + 隐藏 probe/占位名。 */
export function filterPool(items, { text = '', hideProbe = true, hideUnknown = true } = {}) {
  const needle = String(text || '').trim().toLowerCase();
  return (items || []).filter((item) => {
    if (hideProbe && isProbeName(item)) return false;
    if (hideUnknown && isUnknownName(item)) return false;
    if (!needle) return true;
    return String(item.name || '').toLowerCase().includes(needle)
      || String(item.uuid || '').toLowerCase().includes(needle);
  });
}

/** 疑似测试账号：名字里带 probe/test/测试 等前缀。 */
export function isProbeName(item) {
  const name = String(item?.name || '').toLowerCase();
  if (!name) return false;
  return /^(probe|test|e2e|debug)[-_ ]/.test(name) || name.includes('probe');
}

/** 占位名：`玩家-xxxxxxxx` 或空名字的离线账号。 */
export function isUnknownName(item) {
  const name = String(item?.name || '').trim();
  if (!name) return true;
  return /^玩家-[0-9a-f]{6,}$/i.test(name);
}

/** 组卡片标题与徽标所需的派生数据。 */
export function groupView(group, groups, states) {
  const data = String(group?.data || '');
  const state = data && states ? states[data] : null;
  const shared = data
    ? (groups || []).filter((item) => item.data && item.data === data).length : 0;
  return {
    name: group?.name || '',
    data,
    shared,
    badge: shared > 1 ? '共享 data ×' + shared : (group?.note || ''),
    online: groupOnline(group),
    premium: group?.premium || null,
    offline: group?.offline || null,
    hasState: Boolean(state),
  };
}

/** 组里只要有一个账号在线，就算在线组。 */
export function groupOnline(group) {
  return Boolean(group?.premium?.online || group?.offline?.online);
}
