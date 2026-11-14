/** 身份组页的纯展示逻辑：UUID、账号类型、设置、池子、统计与成就布局。 */

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

export function roleLabel(role) {
  if (role === 'data') return '数据 UUID';
  return role === 'premium' ? '正版账号' : '离线账号';
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

export function groupOnline(group, online) {
  return [group?.premium, group?.offline].some((uuid) => uuid && online?.[uuid]);
}

/** 头像始终优先用组内正版 UUID 查皮肤。 */
export function groupSkin(group, names) {
  const uuid = group?.premium || group?.offline || group?.data || '';
  return { uuid: uuid || '', name: names?.[uuid] || group?.name || '' };
}

/** 组的派生展示信息（共享 data、在线、同步阻断）。 */
export function groupView(group, groups, states, online) {
  const data = String(group?.data || '');
  const state = data && states ? states[data] : null;
  const shared = data
    ? (groups || []).filter((item) => item.data && item.data === data).length : 0;
  return {
    name: group?.name || '',
    data,
    shared,
    online: groupOnline(group, online),
    syncBlocked: Boolean(state && state.syncBlocked),
    hasState: Boolean(state),
  };
}

export function allCandidates(candidates, extra) {
  const map = new Map();
  for (const item of [...(candidates || []), ...(extra || [])]) {
    if (item && item.uuid && !map.has(item.uuid)) map.set(item.uuid, item);
  }
  return [...map.values()];
}

export function candidateName(uuid, all) {
  const found = (all || []).find((item) => item.uuid === uuid);
  return found ? found.name || '' : '';
}

export function candidateType(uuid, all, types) {
  if (types?.[uuid]) return types[uuid];
  const found = (all || []).find((item) => item.uuid === uuid);
  return found?.type || '';
}

export function dataDisplayName(uuid, labels, dataList) {
  const meta = labels?.[uuid];
  if (meta?.name) return meta.name;
  const row = (dataList || []).find((item) => item.uuid === uuid);
  return row?.label || '';
}

export function dataTag(uuid, type) {
  if (type) return typeLabel(type) + '数据';
  return '空白 data';
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

/** 疑似测试账号：与旧内核的判定保持一致。 */
export function isProbeName(item) {
  const name = (item?.name ? item.name : '').trim();
  if (!name) return true;
  if (isUnknownName(item)) return false;
  return /^probe[_-]?\d*$/i.test(name)
    || /^dsh[_-]/i.test(name)
    || /^(test|bench|debug)[_-]?\d*$/i.test(name)
    || name === 'Y'
    || /^0+(_0+)*$/.test(name);
}

/** 占位名：`玩家-xxxxxxxx` 或后端标记 nameKnown=false。 */
export function isUnknownName(item) {
  const name = (item?.name ? item.name : '').trim();
  if (!name) return true;
  return item?.nameKnown === false || /^玩家-[0-9a-f]{8}$/i.test(name);
}

export function probeBadge(item) {
  if (isUnknownName(item)) return '占位名';
  if (isProbeName(item)) return 'probe';
  return '';
}

/** 池子过滤：搜索 + 隐藏 probe/占位名，按最后登录时间排序。 */
export function filterPool(items, filter) {
  const { text = '', hideProbe = true, hideUnknown = true } = filter || {};
  const needle = String(text || '').trim().toLowerCase();
  return (items || []).filter((item) => {
    if (hideProbe && isProbeName(item)) return false;
    if (hideUnknown && isUnknownName(item)) return false;
    if (!needle) return true;
    return String(item.name || '').toLowerCase().includes(needle)
      || String(item.uuid || '').toLowerCase().includes(needle);
  }).sort((a, b) => (b.lastLoginMs || 0) - (a.lastLoginMs || 0)
    || String(a.name || '').localeCompare(String(b.name || '')));
}

function normalizeName(name) {
  return (name || '').toLowerCase().replace(/[^a-z0-9]/g, '');
}

function levenshtein(a, b) {
  if (a === b) return 0;
  if (!a.length) return b.length;
  if (!b.length) return a.length;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i += 1) {
    const row = [i];
    for (let j = 1; j <= b.length; j += 1) {
      row[j] = Math.min(row[j - 1] + 1, prev[j] + 1,
        prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    prev = row;
  }
  return prev[b.length];
}

export function similarName(a, b) {
  const x = normalizeName(a);
  const y = normalizeName(b);
  if (!x || !y) return false;
  if (x === y) return true;
  if (Math.min(x.length, y.length) < 5) return false;
  if (x.startsWith(y) || y.startsWith(x)) return true;
  return levenshtein(x, y) <= 2;
}

/** 相似 ID 推荐：同名的正版/离线账号可以合成一个组。 */
export function suggestGroups(candidates, groups) {
  const assigned = new Set();
  for (const group of groups || []) {
    if (group.premium) assigned.add(group.premium);
    if (group.offline) assigned.add(group.offline);
  }
  const items = (candidates || []).filter((item) => !assigned.has(item.uuid)
    && !isProbeName(item) && !isUnknownName(item));
  if (items.length < 2) return [];
  const parent = items.map((_, index) => index);
  const find = (x) => {
    let i = x;
    while (parent[i] !== i) i = parent[i];
    return i;
  };
  const union = (x, y) => {
    const a = find(x);
    const b = find(y);
    if (a !== b) parent[b] = a;
  };
  for (let i = 0; i < items.length; i += 1) {
    for (let j = i + 1; j < items.length; j += 1) {
      if (similarName(items[i].name, items[j].name)) union(i, j);
    }
  }
  const clusters = new Map();
  items.forEach((item, index) => {
    const root = find(index);
    if (!clusters.has(root)) clusters.set(root, []);
    clusters.get(root).push(item);
  });
  return [...clusters.values()].filter((list) => list.length > 1).slice(0, 6)
    .map((cluster) => {
      const premium = cluster.find((item) => item.type === 'premium');
      const offline = cluster.find((item) => item.type === 'offline');
      return {
        premium,
        offline,
        title: (premium?.name || offline?.name || cluster[0].name || '未命名')
          + (premium && offline ? ' · 正版账号 + 离线账号' : ' · 相似 ID'),
        detail: cluster.map((item) => (item.name || '未命名') + ' ('
          + typeLabel(item.type) + ' ' + shortUuid(item.uuid) + ')').join(' · '),
      };
    });
}

/**
 * 池子模型：正版账号、离线账号、data 三类分开。
 * data 依次来自：账号候选、磁盘 data、随机生成的空白 data。
 */
export function poolModel({
  groups = [], candidates = [], extra = [], dataList = [], generated = [],
  modelStates = {}, labels: _labels = {}, online = {}, filter = {},
} = {}) {
  const all = allCandidates(candidates, extra);
  const visible = filterPool(all, filter);
  const assigned = new Set();
  for (const group of groups) {
    if (group.premium) assigned.add(group.premium);
    if (group.offline) assigned.add(group.offline);
  }
  const premium = [];
  const offline = [];
  const data = [];
  for (const item of visible) {
    if (assigned.has(item.uuid)) continue;
    const view = {
      uuid: item.uuid,
      name: item.name || '',
      online: Boolean(item.online || online[item.uuid]),
      type: item.type,
      badge: probeBadge(item),
    };
    if (item.type === 'offline') offline.push({ ...view, tag: '离线账号', role: 'offline' });
    else premium.push({ ...view, tag: '正版账号', role: 'premium' });
  }

  const usedData = new Set(groups.map((group) => group.data).filter(Boolean));
  const seen = new Set();
  for (const item of visible) {
    if (usedData.has(item.uuid) || seen.has(item.uuid)) continue;
    const accountGroup = groups.find((group) =>
      group.premium === item.uuid || group.offline === item.uuid);
    if (accountGroup && accountGroup.data !== item.uuid) continue;
    seen.add(item.uuid);
    data.push({
      uuid: item.uuid,
      name: item.name || '',
      online: Boolean(item.online || online[item.uuid]),
      type: item.type,
      tag: dataTag(item.uuid, item.type),
      role: 'data',
      badge: probeBadge(item),
      label: '',
    });
  }

  const needle = String(filter.text || '').trim().toLowerCase();
  for (const row of dataList) {
    const uuid = row.uuid;
    if (!uuid || usedData.has(uuid) || seen.has(uuid)) continue;
    if (needle && !String(row.label || '').toLowerCase().includes(needle)
      && !String(row.note || '').toLowerCase().includes(needle)
      && !uuid.toLowerCase().includes(needle)) continue;
    seen.add(uuid);
    data.push({
      uuid,
      name: row.label || (row.group ? '组 ' + row.group : '空白 data'),
      online: Boolean(online[uuid]),
      type: 'data',
      tag: row.group ? '已入组' : '未分组',
      role: 'data',
      badge: row.backups ? row.backups + ' 个备份' : '',
      label: row.label || '',
      canLabel: true,
      canBackups: true,
    });
  }

  const blank = [...generated];
  for (const key of Object.keys(modelStates || {})) blank.push(key);
  for (const uuid of blank) {
    if (!uuid || usedData.has(uuid) || seen.has(uuid)) continue;
    if (needle && !uuid.toLowerCase().includes(needle)) continue;
    seen.add(uuid);
    data.push({
      uuid,
      name: '空白 data',
      online: Boolean(online[uuid]),
      type: 'data',
      tag: 'data',
      role: 'data',
      badge: '',
      label: '',
      canRemove: generated.includes(uuid),
    });
  }

  return {
    premium,
    offline,
    data,
    counts: { premium: premium.length, offline: offline.length, data: data.length },
    poolText: '显示 ' + visible.length + ' 个账号候选'
      + ' · 隐藏 probe ' + all.filter(isProbeName).length
      + ' · 占位名 ' + all.filter(isUnknownName).length,
    suggestions: suggestGroups(visible, groups),
  };
}

// ---- 详情：统计与成就 ----

export const STAT_LABELS = {
  animals_bred: '繁殖动物', aviate_one_cm: '鞘翅飞行距离', bell_ring: '敲响钟',
  boat_one_cm: '划船距离', clean_armor: '清洗盔甲', clean_banner: '清洗旗帜',
  clean_shulker_box: '清洗潜影盒', climb_one_cm: '攀爬距离', crouch_one_cm: '潜行距离',
  damage_absorbed: '吸收伤害', damage_blocked_by_shield: '盾牌格挡伤害',
  damage_dealt: '造成伤害', damage_dealt_absorbed: '吸收的造成伤害',
  damage_dealt_resisted: '抵抗的造成伤害', damage_resisted: '抵抗伤害',
  damage_taken: '受到伤害', deaths: '死亡', drop: '丢弃物品',
  eat_cake_slice: '吃蛋糕', enchant_item: '附魔', fall_one_cm: '坠落距离',
  fill_cauldron: '装满炼药锅', fish_caught: '钓鱼', fly_one_cm: '飞行距离',
  happy_ghast_one_cm: '快乐恶魂骑乘距离', horse_one_cm: '骑马距离',
  inspect_dispenser: '查看发射器', inspect_dropper: '查看投掷器',
  inspect_hopper: '查看漏斗', interact_with_anvil: '使用铁砧',
  interact_with_beacon: '使用信标', interact_with_blast_furnace: '使用高炉',
  interact_with_brewingstand: '使用酿造台', interact_with_campfire: '使用营火',
  interact_with_cartography_table: '使用制图台', interact_with_crafting_table: '使用工作台',
  interact_with_furnace: '使用熔炉', interact_with_grindstone: '使用砂轮',
  interact_with_lectern: '使用讲台', interact_with_loom: '使用织布机',
  interact_with_smithing_table: '使用锻造台', interact_with_smoker: '使用烟熏炉',
  interact_with_stonecutter: '使用切石机', jump: '跳跃', leave_game: '退出游戏',
  minecart_one_cm: '矿车移动距离', mob_kills: '击杀生物', nautilus_one_cm: '鹦鹉螺骑乘距离',
  open_barrel: '打开木桶', open_chest: '打开箱子', open_enderchest: '打开末影箱',
  open_shulker_box: '打开潜影盒', pig_one_cm: '骑猪距离', play_noteblock: '演奏音符盒',
  play_record: '播放唱片', play_time: '游玩时间', player_kills: '击杀玩家',
  pot_flower: '种花', raid_trigger: '触发袭击', raid_win: '赢得袭击',
  sleep_in_bed: '睡床', sleep_in_straw_bed: '睡干草床', sneak_time: '潜行时间',
  sprint_one_cm: '疾跑距离', strider_one_cm: '骑炽足兽距离', swim_one_cm: '游泳距离',
  talked_to_villager: '与村民交谈', target_hit: '命中标靶',
  time_since_death: '上次死亡至今', time_since_rest: '上次睡觉至今',
  total_world_time: '世界总时间', traded_with_villager: '村民交易',
  trigger_trapped_chest: '触发陷阱箱', tune_noteblock: '调音音符盒',
  use_cauldron: '使用炼药锅', walk_on_water_one_cm: '水面行走距离',
  walk_one_cm: '步行距离', walk_under_water_one_cm: '水下行走距离',
  mine_block: '挖掘方块', craft_item: '合成物品', use_item: '使用物品',
  pickup: '拾取物品', drop_item: '丢弃物品', break_item: '损坏物品',
};

export function formatStat(key, value) {
  const number = Number(value) || 0;
  if (['play_time', 'total_world_time', 'time_since_death', 'time_since_rest', 'sneak_time']
    .includes(key)) {
    const hours = number / 20 / 3600;
    return hours >= 1 ? hours.toFixed(1) + ' 小时' : Math.round(number / 20) + ' 秒';
  }
  if (String(key).endsWith('_one_cm')) return (number / 100).toFixed(1) + ' 米';
  if (key === 'damage_dealt' || key === 'damage_taken') return (number / 10).toFixed(1);
  return String(number);
}

export const STAT_TABS = [
  { id: 'custom', label: '通用' },
  { id: 'items', label: '物品' },
  { id: 'mobs', label: '生物' },
];

export const STAT_ITEM_GROUPS = [
  ['mined', '挖掘'], ['crafted', '合成'], ['used', '使用'],
  ['picked_up', '拾取'], ['dropped', '丢弃'], ['broken', '损坏'],
];

export const STAT_MOB_GROUPS = [['killed', '击杀'], ['killed_by', '被击杀']];

/** 某标签下的统计行；items/mobs 按 group 分段。 */
export function statsForTab(stats, tab) {
  const source = stats || {};
  if (tab === 'custom') {
    const custom = source.custom && typeof source.custom === 'object'
      ? source.custom : Object.fromEntries(Object.entries(source)
        .filter(([key]) => key.startsWith('minecraft:')));
    return {
      kind: 'rows',
      rows: Object.entries(custom)
        .filter(([, value]) => Number.isFinite(Number(value)))
        .sort((a, b) => Number(b[1]) - Number(a[1]))
        .map(([key, value]) => {
          const clean = key.replace(/^minecraft:/, '');
          return { key: clean, label: STAT_LABELS[clean] || clean, value: formatStat(clean, value) };
        }),
    };
  }
  const groups = tab === 'items' ? STAT_ITEM_GROUPS : STAT_MOB_GROUPS;
  const sections = [];
  for (const [key, label] of groups) {
    const map = source[tab]?.[key];
    const entries = map && typeof map === 'object' ? Object.entries(map) : [];
    if (!entries.length) continue;
    sections.push({
      key,
      label: label + ' · ' + entries.length,
      items: entries.sort((a, b) => Number(b[1]) - Number(a[1])).slice(0, 300)
        .map(([id, count]) => {
          const clean = String(id || '').replace(/^minecraft:/, '');
          const zh = source.names?.[id] || clean;
          return { id, iconId: clean, name: zh, count: String(count) };
        }),
    });
  }
  return { kind: 'sections', sections };
}

export const ADV_TABS = [
  { id: 'adventure', label: '冒险' },
  { id: 'nether', label: '下界' },
  { id: 'husbandry', label: '农牧业' },
  { id: 'story', label: 'Minecraft' },
];

/** 按根节点把成就分到原版四个分类。 */
export function advancementGroups(nodes) {
  const list = Array.isArray(nodes) ? nodes : [];
  const byId = new Map(list.map((row) => [row.id, row]));
  const rootOf = (row) => {
    const seen = new Set();
    let current = row;
    while (current && current.parent && byId.has(current.parent) && !seen.has(current.id)) {
      seen.add(current.id);
      current = byId.get(current.parent);
    }
    return current || row;
  };
  const groups = { adventure: [], nether: [], husbandry: [], story: [] };
  for (const row of list) {
    const root = String(rootOf(row).id || '');
    if (root.includes('adventure')) groups.adventure.push(row);
    else if (root.includes('nether')) groups.nether.push(row);
    else if (root.includes('husbandry')) groups.husbandry.push(row);
    else groups.story.push(row);
  }
  return groups;
}

/**
 * 成就树布局：按父子关系重新排版（原版 x/y 会重叠），返回节点坐标与连线。
 * 算法与原内核一致，只生成数据，DOM 渲染由 Vue 负责。
 */
export function advancementLayout(nodes) {
  const list = Array.isArray(nodes) ? nodes.slice() : [];
  const byId = new Map(list.map((row) => [row.id, row]));
  const children = new Map();
  const roots = [];
  for (const row of list) {
    if (row.parent && byId.has(row.parent) && row.parent !== row.id) {
      if (!children.has(row.parent)) children.set(row.parent, []);
      children.get(row.parent).push(row);
    } else {
      roots.push(row);
    }
  }
  const sortRows = (a, b) => String(a.title || a.id)
    .localeCompare(String(b.title || b.id), 'zh-CN');
  for (const childList of children.values()) childList.sort(sortRows);
  roots.sort(sortRows);

  const COL = 96;
  const ROW = 92;
  const PAD = 42;
  const placed = new Map();
  const visiting = new Set();
  let leaf = 0;
  const place = (row, depth) => {
    if (visiting.has(row.id)) return leaf * COL;
    visiting.add(row.id);
    const kids = (children.get(row.id) || []).filter((child) => !visiting.has(child.id));
    let x;
    if (!kids.length) {
      x = leaf * COL;
      leaf += 1;
    } else {
      const xs = kids.map((child) => place(child, depth + 1));
      x = xs.reduce((sum, value) => sum + value, 0) / xs.length;
    }
    visiting.delete(row.id);
    placed.set(row.id, { x, y: depth * ROW });
    return x;
  };
  for (const root of roots) place(root, 0);
  for (const row of list) {
    if (!placed.has(row.id)) {
      placed.set(row.id, { x: leaf * COL, y: 0 });
      leaf += 1;
    }
  }
  const maxX = Math.max(0, ...[...placed.values()].map((point) => point.x));
  const maxY = Math.max(0, ...[...placed.values()].map((point) => point.y));
  const points = list.map((row) => {
    const point = placed.get(row.id) || { x: 0, y: 0 };
    return {
      row,
      id: row.id,
      x: point.x + PAD,
      y: point.y + PAD,
      done: Boolean(row.done),
    };
  });
  const pointById = new Map(points.map((point) => [point.id, point]));
  const edges = [];
  for (const row of list) {
    if (!row.parent || !byId.has(row.parent)) continue;
    const from = pointById.get(row.parent);
    const to = pointById.get(row.id);
    if (from && to) edges.push({ id: row.id, x1: from.x, y1: from.y, x2: to.x, y2: to.y, done: Boolean(row.done) });
  }
  const ancestorPath = (row) => {
    const chain = [];
    const seen = new Set();
    let current = row;
    while (current && !seen.has(current.id)) {
      seen.add(current.id);
      chain.push(current.id);
      current = current.parent ? byId.get(current.parent) : null;
    }
    return chain;
  };
  return {
    width: Math.max(320, maxX + PAD * 2),
    height: Math.max(260, maxY + PAD * 2),
    points,
    edges,
    children,
    pathOf: ancestorPath,
    doneCount: list.filter((row) => row.done).length,
    total: list.length,
  };
}
