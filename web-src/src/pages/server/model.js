/**
 * 本体服务器页的纯逻辑：格式化、properties 解析、配置规格、草稿脏值比较、
 * 备份/审计/玩家列表的展示数据整理，以及 § 颜色文本的解析。
 * 这里不碰 DOM，也不发请求，方便单测。
 */

export function formatBytes(bytes) {
  const n = Number(bytes || 0);
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB';
  return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB';
}

export function formatTime(ms) {
  const n = Number(ms || 0);
  if (!n) return '—';
  try {
    return new Date(n).toLocaleString();
  } catch (e) {
    return String(n);
  }
}

/**
 * 把 properties 文本拆成可编辑行：
 * - 去掉 UTF-8 BOM
 * - `#` / `!` 开头的是注释，不能当成键值
 * - 第一个 `=` 之后全部是值
 */
export function splitPropertyLines(content) {
  return String(content || '').replace(/^\uFEFF/, '').split(/\r?\n/).map((line) => {
    if (line === '') return { type: 'blank', raw: '' };
    if (/^\s*[#!]/.test(line) || /^\s*\\[#!]/.test(line)) {
      return { type: 'comment', raw: line };
    }
    const eq = line.indexOf('=');
    if (eq <= 0) return { type: 'raw', raw: line };
    const value = line.slice(eq + 1);
    return { type: 'prop', key: line.slice(0, eq), value, originalValue: value };
  });
}

/** 本地时间戳 `YYYY-MM-DD HH:mm:ss +0800`，与旧实现逐字一致。 */
export function stampNow(date = new Date()) {
  const pad = (n) => String(n).padStart(2, '0');
  const offset = -date.getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  const abs = Math.abs(offset);
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
    + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds())
    + ' ' + sign + pad(Math.floor(abs / 60)) + pad(abs % 60);
}

export const GROUP_ORDER = ['网络与状态', '世界与玩法', '管理与 RCON', '性能与区块', '其他'];

// 原版 server.properties 默认值；用于“恢复默认”而不是恢复本次加载值。
export const PROPERTY_DEFAULTS = {
  'accepts-transfers': 'false', 'allow-flight': 'false',
  'broadcast-console-to-ops': 'true', 'broadcast-rcon-to-ops': 'true',
  'bug-report-link': '', 'chat-spam-threshold-seconds': '10',
  'command-spam-threshold-seconds': '10', 'difficulty': 'easy',
  'enable-code-of-conduct': 'false', 'enable-jmx-monitoring': 'false',
  'enable-query': 'false', 'enable-rcon': 'false', 'enable-status': 'true',
  'enforce-secure-profile': 'true', 'enforce-whitelist': 'false',
  'entity-broadcast-range-percentage': '100', 'force-gamemode': 'false',
  'function-permission-level': '2', 'gamemode': 'survival',
  'generate-structures': 'true', 'generator-settings': '{}',
  'hardcore': 'false', 'hide-online-players': 'false',
  'initial-disabled-packs': '', 'initial-enabled-packs': 'vanilla',
  'level-name': 'world', 'level-seed': '', 'level-type': 'minecraft\\:normal',
  'log-ips': 'true', 'management-server-allowed-origins': '',
  'management-server-enabled': 'false', 'management-server-host': 'localhost',
  'management-server-port': '0', 'management-server-secret': '',
  'management-server-tls-enabled': 'true',
  'management-server-tls-keystore': '',
  'management-server-tls-keystore-password': '',
  'max-chained-neighbor-updates': '1000000', 'max-players': '20',
  'max-tick-time': '60000', 'max-world-size': '29999984',
  'motd': 'A Minecraft Server', 'network-compression-threshold': '256',
  'online-mode': 'true', 'op-permission-level': '4',
  'pause-when-empty-seconds': '60', 'player-idle-timeout': '0',
  'prevent-proxy-connections': 'false', 'query.port': '25565',
  'rate-limit': '0', 'rcon.password': '', 'rcon.port': '25575',
  'region-file-compression': 'deflate', 'require-resource-pack': 'false',
  'resource-pack': '', 'resource-pack-id': '', 'resource-pack-prompt': '',
  'resource-pack-sha1': '', 'server-ip': '', 'server-port': '25565',
  'simulation-distance': '10', 'spawn-protection': '16',
  'status-heartbeat-interval': '0', 'sync-chunk-writes': 'true',
  'text-filtering-config': '', 'text-filtering-version': '0',
  'use-native-transport': 'true', 'view-distance': '10', 'white-list': 'false',
  'eula': 'false',
};

export const PROPERTY_SPECS = {
  'accepts-transfers': { group: '网络与状态', type: 'bool', desc: '是否接受其它服务器通过 transfer 协议转入的玩家' },
  'allow-flight': { group: '世界与玩法', type: 'bool', desc: '允许生存模式飞行；关闭时飞行会被反作弊踢出' },
  'broadcast-console-to-ops': { group: '管理与 RCON', type: 'bool', desc: '把控制台命令的输出广播给 OP' },
  'broadcast-rcon-to-ops': { group: '管理与 RCON', type: 'bool', desc: '把 RCON 命令的输出广播给 OP' },
  'bug-report-link': { group: '网络与状态', type: 'url', desc: '客户端崩溃时显示的反馈链接；留空使用默认' },
  'chat-spam-threshold-seconds': { group: '世界与玩法', type: 'int', min: 0, desc: '聊天刷屏判定窗口（秒）' },
  'command-spam-threshold-seconds': { group: '世界与玩法', type: 'int', min: 0, desc: '命令刷屏判定窗口（秒）' },
  'difficulty': {
    group: '世界与玩法', type: 'enum', choices: [
      { value: 'peaceful', label: '和平' }, { value: 'easy', label: '简单' },
      { value: 'normal', label: '普通' }, { value: 'hard', label: '困难' },
    ], desc: '游戏难度',
  },
  'enable-code-of-conduct': { group: '网络与状态', type: 'bool', desc: '是否要求玩家遵守行为准则' },
  'enable-jmx-monitoring': { group: '管理与 RCON', type: 'bool', desc: '开启 JMX 监控（性能诊断）' },
  'enable-query': { group: '管理与 RCON', type: 'bool', desc: '开启 GameSpy4 查询协议' },
  'enable-rcon': { group: '管理与 RCON', type: 'bool', desc: '开启 RCON 远程控制台' },
  'enable-status': { group: '网络与状态', type: 'bool', desc: '在服务器列表显示在线状态' },
  'enforce-secure-profile': { group: '网络与状态', type: 'bool', desc: '要求正版签名聊天；离线服通常关闭' },
  'enforce-whitelist': { group: '世界与玩法', type: 'bool', desc: '白名单开启时踢出不在名单的玩家' },
  'entity-broadcast-range-percentage': { group: '性能与区块', type: 'int', min: 10, max: 1000, desc: '实体广播范围百分比（10-1000）' },
  'force-gamemode': { group: '世界与玩法', type: 'bool', desc: '加入时强制切换为默认游戏模式' },
  'function-permission-level': { group: '管理与 RCON', type: 'int', min: 0, max: 4, desc: '函数与数据包的默认权限等级（0-4）' },
  'gamemode': {
    group: '世界与玩法', type: 'enum', choices: [
      { value: 'survival', label: '生存' }, { value: 'creative', label: '创造' },
      { value: 'adventure', label: '冒险' }, { value: 'spectator', label: '旁观' },
    ], desc: '新玩家的默认游戏模式',
  },
  'generate-structures': { group: '世界与玩法', type: 'bool', desc: '生成村庄、要塞等结构' },
  'generator-settings': { group: '世界与玩法', type: 'multiline', desc: '自定义世界生成参数（JSON）；留空使用默认' },
  'hardcore': { group: '世界与玩法', type: 'bool', desc: '极限模式：死亡后不可复活' },
  'hide-online-players': { group: '网络与状态', type: 'bool', desc: '在服务器列表隐藏在线玩家' },
  'initial-disabled-packs': { group: '世界与玩法', type: 'multiline', desc: '默认禁用的数据包列表（逗号分隔）' },
  'initial-enabled-packs': { group: '世界与玩法', type: 'multiline', desc: '默认启用的数据包列表（逗号分隔）' },
  'level-name': { group: '世界与玩法', type: 'text', desc: '世界目录名；本体恢复要求与备份的 level-name 一致' },
  'level-seed': { group: '世界与玩法', type: 'text', desc: '世界种子；留空随机生成' },
  'level-type': {
    group: '世界与玩法', type: 'enum', choices: [
      { value: 'minecraft\\:normal', label: '默认' },
      { value: 'minecraft\\:flat', label: '超平坦' },
      { value: 'minecraft\\:large_biomes', label: '巨型生物群系' },
      { value: 'minecraft\\:amplified', label: '放大化' },
      { value: 'minecraft\\:single_biome_surface', label: '单一生物群系' },
    ], desc: '世界生成器类型',
  },
  'log-ips': { group: '网络与状态', type: 'bool', desc: '在日志中记录玩家 IP' },
  'management-server-allowed-origins': { group: '管理与 RCON', type: 'multiline', desc: '管理服务允许的 Origin；逗号分隔' },
  'management-server-enabled': { group: '管理与 RCON', type: 'bool', desc: '开启内置管理服务' },
  'management-server-host': { group: '管理与 RCON', type: 'text', desc: '管理服务监听地址' },
  'management-server-port': { group: '管理与 RCON', type: 'int', min: 1, max: 65535, desc: '管理服务端口' },
  'management-server-secret': { group: '管理与 RCON', type: 'password', desc: '管理服务密钥（敏感）' },
  'management-server-tls-enabled': { group: '管理与 RCON', type: 'bool', desc: '管理服务启用 TLS' },
  'management-server-tls-keystore': { group: '管理与 RCON', type: 'text', desc: 'TLS keystore 文件路径' },
  'management-server-tls-keystore-password': { group: '管理与 RCON', type: 'password', desc: 'TLS keystore 密码（敏感）' },
  'max-chained-neighbor-updates': { group: '性能与区块', type: 'int', min: -1, desc: '链式方块更新上限；-1 不限制' },
  'max-players': { group: '网络与状态', type: 'int', min: 0, desc: '最大在线玩家数' },
  'max-tick-time': { group: '性能与区块', type: 'int', min: -1, desc: '单 tick 超时（毫秒）；-1 关闭看门狗' },
  'max-world-size': { group: '世界与玩法', type: 'int', min: 1, desc: '世界边界半径（方块）' },
  'motd': { group: '网络与状态', type: 'text', chat: true, desc: '服务器列表显示的描述；支持 § 颜色代码，可直接选色并预览' },
  'network-compression-threshold': { group: '性能与区块', type: 'int', min: -1, desc: '网络压缩阈值（字节）；-1 关闭' },
  'online-mode': { group: '网络与状态', type: 'bool', desc: '正版验证；离线服设为 false' },
  'op-permission-level': { group: '管理与 RCON', type: 'int', min: 0, max: 4, desc: 'OP 的默认权限等级（0-4）' },
  'pause-when-empty-seconds': { group: '世界与玩法', type: 'int', min: 0, desc: '无人在线多少秒后暂停；0 不暂停' },
  'player-idle-timeout': { group: '世界与玩法', type: 'int', min: 0, desc: '挂机踢出时间（分钟）；0 不踢' },
  'prevent-proxy-connections': { group: '网络与状态', type: 'bool', desc: '拒绝通过代理 / VPN 连接的玩家' },
  'query.port': { group: '管理与 RCON', type: 'int', min: 1, max: 65535, desc: 'GameSpy4 查询端口' },
  'rate-limit': { group: '网络与状态', type: 'int', min: 0, desc: '每玩家每秒数据包上限；0 不限制' },
  'rcon.password': { group: '管理与 RCON', type: 'password', desc: 'RCON 密码（敏感）；建议只在本机使用' },
  'rcon.port': { group: '管理与 RCON', type: 'int', min: 1, max: 65535, desc: 'RCON 端口' },
  'region-file-compression': {
    group: '性能与区块', type: 'enum', choices: [
      { value: 'deflate', label: 'deflate' }, { value: 'lz4', label: 'lz4' }, { value: 'none', label: 'none' },
    ], desc: 'region 文件压缩算法',
  },
  'require-resource-pack': { group: '网络与状态', type: 'bool', desc: '强制玩家使用资源包' },
  'resource-pack': { group: '网络与状态', type: 'url', desc: '资源包下载地址' },
  'resource-pack-id': { group: '网络与状态', type: 'text', desc: '资源包唯一标识' },
  'resource-pack-prompt': { group: '网络与状态', type: 'text', chat: true, desc: '资源包提示文本；支持 § 颜色代码' },
  'resource-pack-sha1': { group: '网络与状态', type: 'text', desc: '资源包 SHA-1 校验值' },
  'server-ip': { group: '网络与状态', type: 'text', desc: '监听地址；留空表示所有网卡' },
  'server-port': { group: '网络与状态', type: 'int', min: 1, max: 65535, desc: '游戏端口' },
  'simulation-distance': { group: '性能与区块', type: 'int', min: 2, max: 32, desc: '模拟距离（区块）' },
  'spawn-protection': { group: '世界与玩法', type: 'int', min: 0, desc: '出生点保护半径（方块）；0 关闭' },
  'status-heartbeat-interval': { group: '网络与状态', type: 'int', min: 0, desc: '服务器列表状态心跳间隔（秒）' },
  'sync-chunk-writes': { group: '性能与区块', type: 'bool', desc: '强制同步区块写入（更安全但更慢）' },
  'text-filtering-config': { group: '世界与玩法', type: 'text', desc: '聊天过滤服务配置路径' },
  'text-filtering-version': { group: '世界与玩法', type: 'int', desc: '聊天过滤服务协议版本' },
  'use-native-transport': { group: '性能与区块', type: 'bool', desc: 'Linux 上使用原生网络传输' },
  'view-distance': { group: '性能与区块', type: 'int', min: 2, max: 32, desc: '视距（区块）' },
  'white-list': { group: '世界与玩法', type: 'bool', desc: '启用白名单' },
  'eula': { group: '其他', type: 'bool', desc: 'EULA 同意标记；必须为 true 才能启动服务器' },
};

export const JSON_SPECS = {
  whitelist: {
    addLabel: '+ 手动添加白名单玩家',
    player: true,
    fields: {
      uuid: { desc: '玩家 UUID（正版或离线）', type: 'text', mono: true },
      name: { desc: '玩家名' },
    },
  },
  ops: {
    addLabel: '+ 手动添加 OP',
    player: true,
    fields: {
      uuid: { desc: '玩家 UUID（正版或离线）', type: 'text', mono: true },
      name: { desc: '玩家名' },
      level: {
        desc: 'OP 权限等级', type: 'enum', numeric: true, choices: [
          { value: '1', label: '1' }, { value: '2', label: '2' },
          { value: '3', label: '3' }, { value: '4', label: '4' },
        ],
      },
      bypassesPlayerLimit: { desc: '允许在服务器满员时进入', type: 'bool' },
    },
  },
  'banned-players': {
    addLabel: '+ 手动添加玩家封禁',
    player: true,
    fields: {
      uuid: { desc: '玩家 UUID（正版或离线）', type: 'text', mono: true },
      name: { desc: '玩家名' },
      created: { desc: '封禁时间；添加时自动填入', type: 'text', quick: 'now' },
      source: { desc: '执行封禁的来源', type: 'text' },
      expires: { desc: '到期时间；forever 表示永久', type: 'text', quick: 'forever' },
      reason: { desc: '封禁原因', type: 'text' },
    },
  },
  'banned-ips': {
    addLabel: '+ 手动添加 IP 封禁',
    player: false,
    fields: {
      ip: { desc: 'IP 地址；添加时自动填时间与原因', type: 'text', mono: true },
      created: { desc: '封禁时间；添加时自动填入', type: 'text', quick: 'now' },
      source: { desc: '执行封禁的来源', type: 'text' },
      expires: { desc: '到期时间；forever 表示永久', type: 'text', quick: 'forever' },
      reason: { desc: '封禁原因', type: 'text' },
    },
  },
};

export const FILE_META = {
  whitelist: {
    className: 'file-access',
    icon: '白',
    badge: '权限 · 准入名单',
    note: '白名单只决定谁能进入服务器；保存后立即重载，但需要先开启 white-list。',
  },
  ops: {
    className: 'file-access',
    icon: 'OP',
    badge: '权限 · 管理员',
    note: 'OP 拥有管理命令权限；等级 4 等同服主，修改前请确认对象。',
  },
  'banned-players': {
    className: 'file-danger',
    icon: '禁',
    badge: '危险 · 玩家封禁',
    note: '玩家封禁会让目标无法进入服务器；保存后立即重载。',
  },
  'banned-ips': {
    className: 'file-danger',
    icon: 'IP',
    badge: '危险 · IP 封禁',
    note: 'IP 封禁可能影响同一网络下的其他玩家；请谨慎添加。',
  },
};

export function specFor(fileId, key) {
  if (fileId === 'server-properties') {
    return PROPERTY_SPECS[key] || {
      group: '其他',
      type: 'text',
      desc: '自定义或未收录的服务器属性；值会原样写回 server.properties。',
    };
  }
  if (fileId === 'eula') return PROPERTY_SPECS.eula;
  return { group: '其他', type: 'text', desc: '' };
}

/** 服务端文件 → 草稿模型。JSON 解析失败时降级为源码编辑。 */
export function parseDraft(file) {
  const content = file.content || '';
  if (file.kind === 'json_array') {
    try {
      const data = JSON.parse(content.trim() === '' ? '[]' : content);
      if (Array.isArray(data)) return { kind: 'json', data, dirty: false };
    } catch (e) {
      return { kind: 'text', text: content, parseError: true, dirty: false };
    }
    return { kind: 'text', text: content, parseError: true, dirty: false };
  }
  if (file.kind === 'properties' || file.kind === 'eula') {
    const tokens = splitPropertyLines(content);
    if (file.kind === 'eula' && !tokens.some((t) => t.type === 'prop' && t.key === 'eula')) {
      tokens.push({ type: 'prop', key: 'eula', value: 'false', originalValue: 'false' });
    }
    return { kind: 'lines', tokens, dirty: false };
  }
  return { kind: 'text', text: content, dirty: false };
}

export function buildContent(draft) {
  if (!draft) return '';
  if (draft.kind === 'json') return JSON.stringify(draft.data, null, 2) + '\n';
  if (draft.kind === 'lines') {
    return draft.tokens
      .map((token) => (token.type === 'prop' ? token.key + '=' + token.value : (token.raw || '')))
      .join('\n');
  }
  return draft.text || '';
}

export function samePropertyLines(a, b) {
  const left = a || [];
  const right = b || [];
  if (left.length !== right.length) return false;
  for (let i = 0; i < left.length; i++) {
    const x = left[i];
    const y = right[i];
    if (x.type !== y.type) return false;
    if (x.type === 'prop') {
      if (x.key !== y.key || String(x.value) !== String(y.value)) return false;
    } else if ((x.raw || '') !== (y.raw || '')) {
      return false;
    }
  }
  return true;
}

/**
 * 草稿是否真的改动过：只比较会写回的内容，避免 BOM、行尾和空白造成假修改。
 */
export function isDraftDirty(file, draft) {
  if (!draft) return false;
  if (draft.kind === 'json') {
    const next = buildContent(draft);
    try {
      return JSON.stringify(JSON.parse(next)) !== JSON.stringify(JSON.parse(file.content || '[]'));
    } catch (e) {
      return true;
    }
  }
  if (draft.kind === 'lines') {
    return !samePropertyLines(draft.tokens, splitPropertyLines(file.content));
  }
  const normalize = (text) => String(text || '')
    .replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').replace(/\s+$/, '');
  return normalize(buildContent(draft)) !== normalize(file.content);
}

export function defaultJsonItem(fileId, now = stampNow()) {
  if (fileId === 'whitelist') return { uuid: '', name: '' };
  if (fileId === 'ops') return { uuid: '', name: '', level: 4, bypassesPlayerLimit: false };
  if (fileId === 'banned-players') {
    return {
      uuid: '', name: '', created: now, source: 'Web 控制台',
      expires: 'forever', reason: 'Banned by an operator.',
    };
  }
  if (fileId === 'banned-ips') {
    return {
      ip: '', created: now, source: 'Web 控制台',
      expires: 'forever', reason: 'Banned by an operator.',
    };
  }
  return {};
}

export function autoFillBan(fileId, item, now = stampNow()) {
  if (fileId !== 'banned-players' && fileId !== 'banned-ips') return item;
  if ('created' in item && !item.created) item.created = now;
  if ('expires' in item && !item.expires) item.expires = 'forever';
  if ('source' in item && !item.source) item.source = 'Web 控制台';
  if ('reason' in item && !item.reason) item.reason = 'Banned by an operator.';
  return item;
}

/** properties/eula 的可见分组；搜索同时匹配键名和说明。 */
export function groupProperties(fileId, tokens, query) {
  const needle = String(query || '').trim().toLowerCase();
  const props = tokens
    .map((token, index) => ({ token, index }))
    .filter(({ token }) => token.type === 'prop');
  const visible = props.filter(({ token }) => {
    const spec = specFor(fileId, token.key);
    return !needle || token.key.toLowerCase().includes(needle)
      || String(spec.desc || '').toLowerCase().includes(needle);
  });
  const groups = new Map();
  for (const item of visible) {
    const spec = specFor(fileId, item.token.key);
    const name = fileId === 'eula' ? 'EULA' : (spec.group || '其他');
    if (!groups.has(name)) groups.set(name, []);
    groups.get(name).push(item);
  }
  const order = fileId === 'eula'
    ? ['EULA']
    : GROUP_ORDER.filter((name) => groups.has(name)).concat(
      [...groups.keys()].filter((name) => !GROUP_ORDER.includes(name)));
  return {
    count: visible.length,
    groups: order.filter((name) => (groups.get(name) || []).length)
      .map((name) => ({ name, items: groups.get(name) })),
  };
}

/** JSON 列表文件的可见条目：搜索匹配整条 JSON 或字段说明。 */
export function filterJsonItems(fileId, items, query) {
  const needle = String(query || '').trim().toLowerCase();
  const spec = JSON_SPECS[fileId] || { fields: {}, addLabel: '+ 添加一条', player: false };
  const visible = (items || [])
    .map((item, index) => ({ item, index }))
    .filter(({ item }) => {
      if (!needle) return true;
      return JSON.stringify(item).toLowerCase().includes(needle)
        || Object.keys(item).some((key) => String((spec.fields[key] || {}).desc || '')
          .toLowerCase().includes(needle));
    });
  return { spec, count: visible.length, items: visible };
}

/** 玩家选择列表：在线优先、按名字排序（服务端已排序，这里只做搜索过滤）。 */
export function filterPlayers(players, query) {
  const needle = String(query || '').trim().toLowerCase();
  return (players || []).filter((player) => !needle
    || String(player.name || '').toLowerCase().includes(needle)
    || String(player.uuid || '').toLowerCase().includes(needle));
}

/** 配置文件的绝对路径；服务端没给 path 时按根目录拼。 */
export function displayPath(file, serverRoot) {
  if (file.path) return file.path;
  if (!serverRoot) return file.name;
  const sep = String(serverRoot).includes('\\') ? '\\' : '/';
  return String(serverRoot).replace(/[\\/]+$/, '') + sep + file.name;
}

export function entryId(fileId, key, index) {
  return 'server-entry-' + fileId + '-' + encodeURIComponent(key || String(index));
}

export function jsonItemId(fileId, index) {
  return 'server-json-' + fileId + '-' + index;
}

export const DIFF_SIGN = { added: '+ ', removed: '- ', modified: '~ ' };

export function diffSign(status) {
  return DIFF_SIGN[status] || '';
}

export function diffLineClass(line) {
  if (line.startsWith('+')) return 'server-audit-line-add';
  if (line.startsWith('-')) return 'server-audit-line-del';
  return 'server-audit-line-ctx';
}

/** 审计条目展示用的两行文本。 */
export function auditEntryText(entry) {
  return {
    title: String(entry.action || '') + ' · ' + formatTime(entry.createdAt),
    meta: (entry.rollbackable ? '可回滚' : '不可自动回滚')
      + ' · ' + (entry.fileCount || 0) + ' 文件 · ' + formatBytes(entry.bytes)
      + (entry.note ? ' · ' + entry.note : ''),
  };
}

/** 备份时间线节点的展示数据。 */
export function timelineNodeMeta(node) {
  return formatBytes(node.zipBytes)
    + ' · world=' + (node.worldName || '—')
    + ' · ' + (node.fileCount || 0) + ' 个文件'
    + (node.parentId ? ' · 父节点 ' + node.parentId : ' · 时间线起点')
    + (node.message ? ' · ' + node.message : '');
}

export function backupStats(data) {
  const nodes = (data?.timeline?.nodes || []);
  return [
    ['备份数', String(nodes.length)],
    ['时间线头节点', data?.timeline?.headId || '—'],
    ['当前世界', '实时运行（不在此列表）'],
    ['world 大小', formatBytes(data?.worldBytes)],
    ['可用空间', formatBytes(data?.freeBytes)],
    ['保留', (data?.keep === 0 ? '不自动清理' : '最新 ' + data?.keep + ' 份 + 祖先')],
  ];
}

// ---- § 颜色/格式文本 ----

export const CHAT_COLORS = [
  ['0', '黑', '#000000'], ['1', '深蓝', '#0000aa'], ['2', '深绿', '#00aa00'],
  ['3', '深青', '#00aaaa'], ['4', '深红', '#aa0000'], ['5', '深紫', '#aa00aa'],
  ['6', '金', '#ffaa00'], ['7', '灰', '#aaaaaa'], ['8', '深灰', '#555555'],
  ['9', '蓝', '#5555ff'], ['a', '绿', '#55ff55'], ['b', '青', '#55ffff'],
  ['c', '红', '#ff5555'], ['d', '粉', '#ff55ff'], ['e', '黄', '#ffff55'],
  ['f', '白', '#ffffff'],
];

export const CHAT_FORMATS = [
  ['l', '粗', 'font-weight:700'],
  ['o', '斜', 'font-style:italic'],
  ['n', '下划线', 'text-decoration:underline'],
  ['m', '删除线', 'text-decoration:line-through'],
  ['k', '乱码', 'opacity:.55;letter-spacing:1px'],
  ['r', '重置', ''],
];

export const CHAT_COLOR_HEX = Object.fromEntries(CHAT_COLORS.map(([code, _label, hex]) => [code, hex]));

/** 把 §/& 代码切成带样式的片段；返回值可直接给 v-for 渲染 span。 */
export function parseChatSegments(text) {
  const segments = [];
  let style = {};
  let buffer = '';
  const flush = () => {
    if (!buffer) return;
    segments.push({
      text: buffer,
      style: {
        color: style.color || '',
        fontWeight: style.bold ? '700' : '',
        fontStyle: style.italic ? 'italic' : '',
        textDecoration: [style.underline ? 'underline' : '',
          style.strike ? 'line-through' : ''].filter(Boolean).join(' ') || '',
        opacity: style.obfuscated ? '.55' : '',
        letterSpacing: style.obfuscated ? '1px' : '',
      },
    });
    buffer = '';
  };
  const raw = String(text || '');
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if ((c !== '§' && c !== '&') || i + 1 >= raw.length) {
      buffer += c;
      continue;
    }
    const code = raw[i + 1].toLowerCase();
    if (code === 'x') {
      const hex = [];
      for (let j = 0; j < 6; j++) {
        const at = i + 2 + j * 2;
        if (raw[at] !== '§' && raw[at] !== '&') break;
        hex.push(raw[at + 1] || '');
      }
      if (hex.length === 6 && hex.every((value) => /[0-9a-f]/i.test(value))) {
        flush();
        style = { ...style, color: '#' + hex.join('') };
        i += 13;
        continue;
      }
    }
    if (CHAT_COLOR_HEX[code]) {
      flush();
      style = { ...style, color: CHAT_COLOR_HEX[code] };
      i++;
      continue;
    }
    if ('lonmk'.includes(code)) {
      flush();
      if (code === 'l') style = { ...style, bold: true };
      if (code === 'o') style = { ...style, italic: true };
      if (code === 'n') style = { ...style, underline: true };
      if (code === 'm') style = { ...style, strike: true };
      if (code === 'k') style = { ...style, obfuscated: true };
      i++;
      continue;
    }
    if (code === 'r') {
      flush();
      style = {};
      i++;
      continue;
    }
    buffer += c;
  }
  flush();
  return segments;
}

/** 服务器列表预览的两行 MOTD（超过 58 字符按空格断行）。 */
export function splitMotdLines(text) {
  const raw = String(text || '').replace(/\r/g, '');
  if (raw.includes('\n')) {
    const parts = raw.split('\n');
    return [parts[0], parts.slice(1).join(' ')];
  }
  if (raw.length <= 58) return [raw, ''];
  let cut = raw.lastIndexOf(' ', 58);
  if (cut < 24) cut = 58;
  return [raw.slice(0, cut), raw.slice(cut).trim()];
}
