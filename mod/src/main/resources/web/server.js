'use strict';

// 「本体」页：服务器配置 + 存档备份时间线。
// 所有配置项走 form-kit 的统一控件；备份走 BackupTimeline 的树模型。

(function () {
  const UI = window.SamsaraUI;
  const KIT = window.SamsaraUIKit;
  if (!UI) return;
  const $ = (id) => document.getElementById(id);

  const state = {
    section: 'mods',
    serverPanel: 'server-config-panel',
    files: [],
    model: null,
    drafts: new Map(),
    search: '',
    openStates: new Map(),
    userCache: null,
    playerPick: null,
  };

  function message(text, kind) {
    const el = $('server-config-message');
    if (!el) return;
    el.textContent = text || '';
    el.className = 'message' + (kind ? ' ' + kind : '');
  }

  function backupMessage(text, kind) {
    const el = $('server-backup-message');
    if (!el) return;
    el.textContent = text || '';
    el.className = 'message' + (kind ? ' ' + kind : '');
  }

  function formatBytes(bytes) {
    const n = Number(bytes || 0);
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB';
    return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }

  function formatTime(ms) {
    const n = Number(ms || 0);
    if (!n) return '—';
    try {
      return new Date(n).toLocaleString();
    } catch (e) {
      return String(n);
    }
  }

  function nowStamp() {
    const date = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    const offset = -date.getTimezoneOffset();
    const sign = offset >= 0 ? '+' : '-';
    const abs = Math.abs(offset);
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
        + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds())
        + ' ' + sign + pad(Math.floor(abs / 60)) + pad(abs % 60);
  }

  // ---- 一级分栏与导航 ----

  function setSection(section) {
    state.section = section === 'server' ? 'server' : 'mods';
    document.body.classList.toggle('server-section', state.section === 'server');
    for (const tab of document.querySelectorAll('.top-tab')) {
      tab.classList.toggle('active', tab.dataset.section === state.section);
    }
    const panel = $('panel-server');
    if (panel) panel.hidden = state.section !== 'server';
    const serverTabs = $('server-tabs');
    const serverActions = $('server-actions');
    if (serverTabs) serverTabs.hidden = state.section !== 'server';
    if (serverActions) serverActions.hidden = state.section !== 'server';
    if (state.section === 'server') {
      setServerPanel(state.serverPanel);
      loadAll();
    } else {
      UI.setActiveTab(UI.state.tab || 'config');
    }
  }

  const SERVER_ACTIONS = {
    'server-config-panel': {
      title: '服务器配置',
      hint: '保存会写入所有已修改文件并热重载；properties/eula 需重启',
    },
    'server-backup-panel': {
      title: '存档备份',
      hint: '时间线可回滚到任意节点；恢复后下一次备份从该点分出新分支',
    },
  };

  const SERVER_HASH = {
    'server-config-panel': '#server',
    'server-backup-panel': '#server-backup',
  };

  function setServerPanel(id) {
    state.serverPanel = id;
    for (const tab of document.querySelectorAll('.tab[data-server-panel]')) {
      tab.classList.toggle('active', tab.dataset.serverPanel === id);
    }
    message('', '');
    backupMessage('', '');
    const config = $('server-config-panel');
    const backups = $('server-backup-panel');
    if (config) config.hidden = id !== 'server-config-panel';
    if (backups) backups.hidden = id !== 'server-backup-panel';
    const action = SERVER_ACTIONS[id] || SERVER_ACTIONS['server-config-panel'];
    const title = $('server-action-title');
    const hint = $('server-action-hint');
    const save = $('server-save');
    const create = $('server-backup-create');
    if (title) title.textContent = action.title;
    if (hint) hint.textContent = action.hint;
    if (save) save.hidden = id !== 'server-config-panel';
    if (create) create.hidden = id !== 'server-backup-panel';
    if (state.section === 'server') {
      try { history.replaceState(null, '', SERVER_HASH[id] || '#server'); } catch (e) { /* file:// 等场景忽略 */ }
      renderServerNav();
    }
    if (id === 'server-backup-panel') {
      loadBackupsOnly();
      window.SamsaraWorldStack?.load();
    } else {
      updateDirtyIndicator();
    }
  }

  function renderServerNav() {
    if (state.section !== 'server') return;
    if (state.serverPanel === 'server-backup-panel') {
      const timeline = (state.model && state.model.timeline) || { nodes: [] };
      const items = [
        { label: '备份时间线', target: 'server-backup-timeline', count: (timeline.nodes || []).length },
        { label: '任务与回滚', target: 'server-job-list' },
        { label: '文件位置', target: 'world-stack-files-panel' },
      ];
      const targets = items
          .map((item) => ({ id: item.target, el: document.getElementById(item.target) }))
          .filter((item) => item.el);
      UI.buildNav('存档备份', items, targets, { count: (timeline.nodes || []).length });
      return;
    }
    UI.buildNav('服务器配置', state.navItems || [], state.navTargets || [],
        { count: state.files.length });
  }

  // ---- 数据 ----

  async function loadAll() {
    try {
      const data = await UI.api('/api/server');
      state.model = data;
      state.files = data.configFiles || [];
      state.drafts.clear();
      state.userCache = null;
      message('');
      renderConfig();
      renderBackups(data);
      renderJobs(data.jobs || []);
      renderServerNav();
      return data;
    } catch (e) {
      message(UI.authMessage(e, '读取本体配置失败'), 'error');
      return null;
    }
  }

  async function loadBackupsOnly() {
    try {
      const data = await UI.api('/api/server');
      state.model = data;
      state.files = data.configFiles || [];
      renderBackups(data);
      renderJobs(data.jobs || []);
      renderServerNav();
    } catch (e) {
      backupMessage(UI.authMessage(e, '读取备份列表失败'), 'error');
    }
  }

  // ---- 配置：文件 / 条目结构 ----

  const GROUP_ORDER = ['网络与状态', '世界与玩法', '管理与 RCON', '性能与区块', '其他'];

  const PROPERTY_SPECS = {
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
    'motd': { group: '网络与状态', type: 'text', desc: '服务器列表显示的描述；支持 § 颜色代码' },
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
    'resource-pack-prompt': { group: '网络与状态', type: 'text', desc: '资源包提示文本' },
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

  const JSON_SPECS = {
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

  function specFor(fileId, key) {
    if (fileId === 'server-properties') {
      return PROPERTY_SPECS[key] || {
        group: '其他',
        type: 'text',
        desc: '自定义或未收录的服务器属性；值会原样写回 server.properties。',
      };
    }
    if (fileId === 'eula') {
      return PROPERTY_SPECS.eula;
    }
    return { group: '其他', type: 'text', desc: '' };
  }

  function splitPropertyLines(content) {
    return String(content || '').split(/\r?\n/).map((line) => {
      if (line === '') return { type: 'blank', raw: '' };
      if (/^\s*[#!]/.test(line)) return { type: 'comment', raw: line };
      const eq = line.indexOf('=');
      if (eq <= 0) return { type: 'raw', raw: line };
      return { type: 'prop', key: line.slice(0, eq), value: line.slice(eq + 1) };
    });
  }

  function parseDraft(file) {
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
        tokens.push({ type: 'prop', key: 'eula', value: 'false' });
      }
      return { kind: 'lines', tokens, dirty: false };
    }
    return { kind: 'text', text: content, dirty: false };
  }

  function draftFor(file) {
    if (!state.drafts.has(file.id)) state.drafts.set(file.id, parseDraft(file));
    return state.drafts.get(file.id);
  }

  function markDirty(file, draft) {
    draft.dirty = true;
    updateDirtyIndicator();
  }

  function refreshDirty(file) {
    const draft = state.drafts.get(file.id);
    if (!draft) return;
    const next = buildContent(draft);
    if (draft.kind === 'json') {
      try {
        draft.dirty = JSON.stringify(JSON.parse(next))
            !== JSON.stringify(JSON.parse(file.content || '[]'));
      } catch (e) {
        draft.dirty = true;
      }
    } else {
      const normalize = (text) => String(text || '').replace(/\r\n/g, '\n').replace(/\s+$/, '');
      draft.dirty = normalize(next) !== normalize(file.content);
    }
    updateDirtyIndicator();
  }

  function dirtyCount() {
    let count = 0;
    for (const draft of state.drafts.values()) {
      if (draft.dirty) count++;
    }
    return count;
  }

  function updateDirtyIndicator() {
    const count = dirtyCount();
    const save = $('server-save');
    if (save) save.textContent = count > 0 ? '保存并重载（' + count + '）' : '保存并重载';
  }

  function buildContent(draft) {
    if (!draft) return '';
    if (draft.kind === 'json') return JSON.stringify(draft.data, null, 2) + '\n';
    if (draft.kind === 'lines') {
      return draft.tokens
          .map((token) => (token.type === 'prop' ? token.key + '=' + token.value : (token.raw || '')))
          .join('\n');
    }
    return draft.text || '';
  }

  // ---- 配置渲染 ----

  function captureOpenStates() {
    for (const details of document.querySelectorAll('#server-config-files details[id]')) {
      state.openStates.set(details.id, details.open);
    }
  }

  function isOpen(id, fallback) {
    return state.openStates.has(id) ? state.openStates.get(id) : fallback;
  }

  function fileId(file) {
    return 'server-file-' + file.id;
  }

  function displayPath(file) {
    if (file.path) return file.path;
    const root = state.model && state.model.serverRoot;
    if (!root) return file.name;
    const sep = root.includes('\\') ? '\\' : '/';
    return root.replace(/[\\/]+$/, '') + sep + file.name;
  }

  function entryId(file, token, index) {
    return 'server-entry-' + file.id + '-' + encodeURIComponent(token.key || String(index));
  }

  function jsonItemId(file, index) {
    return 'server-json-' + file.id + '-' + index;
  }

  function renderConfig() {
    const container = $('server-config-files');
    const count = $('server-config-count');
    if (count) count.textContent = String(state.files.length);
    if (!container) return;
    captureOpenStates();
    container.textContent = '';
    const query = state.search.trim().toLowerCase();
    const navItems = [];
    const navTargets = [];
    let shown = 0;
    for (const file of state.files) {
      const draft = draftFor(file);
      const built = buildFileBlock(file, draft, query);
      if (!built) continue;
      shown += built.count;
      container.appendChild(built.el);
      navItems.push(built.nav);
      navTargets.push(...built.targets);
    }
    if (!state.files.length) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '没有可编辑的服务器配置文件。';
      container.appendChild(empty);
    } else if (shown === 0) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '没有匹配的配置项。';
      container.appendChild(empty);
    }
    state.navItems = navItems;
    state.navTargets = navTargets;
    if (state.section === 'server' && state.serverPanel === 'server-config-panel') {
      UI.buildNav('服务器配置', navItems, navTargets, { count: state.files.length });
    }
    updateDirtyIndicator();
  }

  function buildFileBlock(file, draft, query) {
    if (draft.kind === 'json') return buildJsonFile(file, draft, query);
    if (draft.kind === 'lines') return buildLinesFile(file, draft, query);
    return buildTextFile(file, draft);
  }

  function buildLinesFile(file, draft, query) {
    const props = draft.tokens
        .map((token, index) => ({ token, index }))
        .filter(({ token }) => token.type === 'prop');
    const visible = props.filter(({ token }) => {
      const spec = specFor(file.id, token.key);
      return !query || token.key.toLowerCase().includes(query)
          || String(spec.desc || '').toLowerCase().includes(query);
    });
    if (!visible.length) return null;

    const groups = new Map();
    for (const item of visible) {
      const spec = specFor(file.id, item.token.key);
      const name = file.kind === 'eula' ? 'EULA' : (spec.group || '其他');
      if (!groups.has(name)) groups.set(name, []);
      groups.get(name).push(item);
    }
    const groupOrder = file.kind === 'eula'
        ? ['EULA']
        : GROUP_ORDER.filter((name) => groups.has(name)).concat(
            [...groups.keys()].filter((name) => !GROUP_ORDER.includes(name)));

    const body = [];
    const targets = [];
    const navChildren = [];
    for (const name of groupOrder) {
      const items = groups.get(name);
      if (!items.length) continue;
      const groupId = 'server-group-' + file.id + '-'
          + encodeURIComponent(name);
      const entries = [];
      const entryNav = [];
      for (const { token, index } of items) {
        const id = entryId(file, token, index);
        const row = buildPropertyRow(file, draft, token, index, id);
        entries.push(row);
        entryNav.push({ label: token.key, target: id });
        targets.push({ id, el: row });
      }
      const groupEl = KIT.group(name, items.length, entries, {
        id: groupId,
        open: isOpen(groupId, true),
      });
      body.push(groupEl);
      targets.push({ id: groupId, el: groupEl });
      navChildren.push({ label: name, count: items.length, target: groupId, children: entryNav });
    }

    const id = fileId(file);
    const el = KIT.collapsible({
      id,
      title: file.name,
      path: displayPath(file),
      count: visible.length,
      open: isOpen(id, true),
      body,
    });
    targets.push({ id, el });
    return {
      el,
      count: visible.length,
      targets,
      nav: { label: file.name, count: visible.length, target: id, children: navChildren },
    };
  }

  function buildPropertyRow(file, draft, token, index, id) {
    const spec = specFor(file.id, token.key);
    const badges = [];
    if (file.restartRequired) badges.push({ text: '需重启', kind: 'restart' });
    if (spec.type === 'password') badges.push({ text: '敏感' });
    const control = KIT.control(spec, token.value, (next) => {
      token.value = typeof next === 'boolean' ? String(next) : String(next == null ? '' : next);
      markDirty(file, draft);
    });
    const actions = [];
    if (file.kind === 'properties') {
      const reset = KIT.button('恢复默认', 'ghost', () => {
        const at = draft.tokens.indexOf(token);
        if (at >= 0) draft.tokens.splice(at, 1);
        refreshDirty(file);
        renderConfig();
      });
      reset.title = '从文件中删除该项；保存后服务器会使用默认值';
      actions.push(reset);
    }
    return KIT.field({
      id,
      label: token.key,
      description: spec.desc || '',
      badges,
      control,
      actions,
    });
  }

  function buildJsonFile(file, draft, query) {
    const spec = JSON_SPECS[file.id] || { fields: {}, addLabel: '+ 添加一条', player: false };
    const visible = draft.data
        .map((item, index) => ({ item, index }))
        .filter(({ item }) => {
          if (!query) return true;
          return JSON.stringify(item).toLowerCase().includes(query)
              || Object.keys(item).some((key) => String((spec.fields[key] || {}).desc || '')
                  .toLowerCase().includes(query));
        });
    if (!visible.length && query) return null;

    const list = document.createElement('div');
    list.className = 'server-json-list';
    const targets = [];
    const itemNav = [];
    for (const { item, index } of visible) {
      const id = jsonItemId(file, index);
      const built = buildJsonItem(file, draft, item, index, id, spec);
      list.appendChild(built.el);
      targets.push({ id, el: built.el });
      itemNav.push({ label: built.title, target: id });
    }
    if (!visible.length) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '这个文件还是空数组。';
      list.appendChild(empty);
    }

    const actions = document.createElement('div');
    actions.className = 'server-json-actions';
    const add = KIT.button(spec.addLabel || '+ 添加一条', 'ghost', () => {
      const item = defaultJsonItem(file.id);
      draft.data.push(item);
      clearSearch();
      refreshDirty(file);
      renderConfig();
      scrollToId(jsonItemId(file, draft.data.length - 1));
    });
    actions.appendChild(add);
    if (spec.player) {
      const pick = KIT.button('从玩家选择', 'ghost', () => {
        openPlayerPicker('选择玩家' + (file.id === 'whitelist' ? '加入白名单' : ''), (player) => {
          const item = defaultJsonItem(file.id);
          item.uuid = player.uuid;
          item.name = player.name;
          autoFillBan(file.id, item);
          draft.data.push(item);
          clearSearch();
          refreshDirty(file);
          renderConfig();
          scrollToId(jsonItemId(file, draft.data.length - 1));
        });
      });
      actions.appendChild(pick);
    }
    list.appendChild(actions);

    const id = fileId(file);
    const el = KIT.collapsible({
      id,
      title: file.name,
      path: displayPath(file),
      count: draft.data.length,
      open: isOpen(id, true),
      body: list,
    });
    targets.push({ id, el });
    return {
      el,
      count: visible.length || draft.data.length,
      targets,
      nav: { label: file.name, count: draft.data.length, target: id, children: itemNav },
    };
  }

  function buildJsonItem(file, draft, item, index, id, spec) {
    const el = document.createElement('details');
    el.className = 'file-block server-json-item';
    el.id = id;
    el.open = isOpen(id, true);

    const summary = document.createElement('summary');
    summary.className = 'file-summary server-json-head';
    const info = document.createElement('div');
    const title = document.createElement('h4');
    title.className = 'file-title';
    title.textContent = item.name || item.ip || item.uuid || ('条目 ' + (index + 1));
    const path = document.createElement('p');
    path.className = 'file-path';
    path.textContent = item.uuid || item.ip || '';
    info.append(title, path);
    const actions = document.createElement('div');
    actions.className = 'server-json-item-actions';
    if (spec.player) {
      const pick = KIT.button('选择玩家', 'ghost', () => {
        openPlayerPicker('选择玩家', (player) => {
          item.uuid = player.uuid;
          item.name = player.name;
          autoFillBan(file.id, item);
          refreshDirty(file);
          renderConfig();
        });
      });
      actions.appendChild(pick);
    }
    const remove = KIT.button('删除', 'ghost danger', () => {
      draft.data.splice(index, 1);
      refreshDirty(file);
      renderConfig();
    });
    actions.appendChild(remove);
    summary.append(info, actions);
    el.appendChild(summary);

    const body = document.createElement('div');
    body.className = 'file-body';
    const rows = document.createElement('div');
    rows.className = 'struct-list';
    for (const key of Object.keys(item)) {
      const fieldSpec = (spec.fields && spec.fields[key]) || {
        desc: '自定义字段', type: typeof item[key] === 'boolean' ? 'bool'
          : typeof item[key] === 'number' ? 'int' : 'text',
      };
      const row = document.createElement('div');
      row.className = 'struct-row';
      const meta = document.createElement('div');
      meta.className = 'server-field-meta';
      const label = document.createElement('span');
      label.className = 'server-field-label';
      label.textContent = key;
      const desc = document.createElement('small');
      desc.textContent = fieldSpec.desc || '';
      meta.append(label, desc);
      row.appendChild(meta);

      const control = KIT.control(fieldSpec,
          fieldSpec.type === 'bool' ? item[key] === true : item[key],
          (next) => {
            if (fieldSpec.type === 'bool') item[key] = Boolean(next);
            else if (fieldSpec.type === 'enum') {
              item[key] = fieldSpec.numeric ? Number(next) : next;
            } else if (fieldSpec.type === 'int' || fieldSpec.type === 'number') {
              item[key] = next === '' ? 0 : Number(next);
            } else item[key] = next;
            markDirty(file, draft);
          });
      const controlWrap = document.createElement('div');
      controlWrap.className = 'server-field-control';
      controlWrap.appendChild(control);
      if (fieldSpec.quick === 'now') {
        const quick = KIT.button('现在', 'ghost', () => {
          item[key] = nowStamp();
          refreshDirty(file);
          renderConfig();
        });
        controlWrap.appendChild(quick);
      } else if (fieldSpec.quick === 'forever') {
        const quick = KIT.button('永久', 'ghost', () => {
          item[key] = 'forever';
          refreshDirty(file);
          renderConfig();
        });
        controlWrap.appendChild(quick);
      }
      row.appendChild(controlWrap);
      rows.appendChild(row);
    }
    body.appendChild(rows);
    el.appendChild(body);
    return { el, title: title.textContent };
  }

  function buildTextFile(file, draft) {
    const body = document.createElement('div');
    if (draft.parseError) {
      const warn = document.createElement('p');
      warn.className = 'message warning';
      warn.textContent = 'JSON 解析失败，已切换为源码编辑；保存前请先修正格式。';
      body.appendChild(warn);
    }
    const editor = document.createElement('textarea');
    editor.className = 'server-config-editor';
    editor.spellcheck = false;
    editor.value = draft.text || '';
    editor.addEventListener('input', () => {
      draft.text = editor.value;
      markDirty(file, draft);
    });
    body.appendChild(editor);
    const id = fileId(file);
    const el = KIT.collapsible({
      id,
      title: file.name,
      path: displayPath(file),
      count: 1,
      open: isOpen(id, true),
      body,
    });
    return {
      el,
      count: 1,
      targets: [{ id, el }],
      nav: { label: file.name, count: 1, target: id },
    };
  }

  function defaultJsonItem(fileId) {
    if (fileId === 'whitelist') return { uuid: '', name: '' };
    if (fileId === 'ops') return { uuid: '', name: '', level: 4, bypassesPlayerLimit: false };
    if (fileId === 'banned-players') {
      return {
        uuid: '', name: '', created: nowStamp(), source: 'Web 控制台',
        expires: 'forever', reason: 'Banned by an operator.',
      };
    }
    if (fileId === 'banned-ips') {
      return {
        ip: '', created: nowStamp(), source: 'Web 控制台',
        expires: 'forever', reason: 'Banned by an operator.',
      };
    }
    return {};
  }

  function autoFillBan(fileId, item) {
    if (fileId !== 'banned-players' && fileId !== 'banned-ips') return;
    if ('created' in item && !item.created) item.created = nowStamp();
    if ('expires' in item && !item.expires) item.expires = 'forever';
    if ('source' in item && !item.source) item.source = 'Web 控制台';
    if ('reason' in item && !item.reason) item.reason = 'Banned by an operator.';
  }

  function scrollToId(id) {
    if (UI.scrollToTarget) UI.scrollToTarget(id);
    else document.getElementById(id)?.scrollIntoView({ block: 'start' });
  }

  function clearSearch() {
    state.search = '';
    const search = $('server-config-search');
    if (search) search.value = '';
  }

  // ---- 玩家选择 ----

  async function openPlayerPicker(title, onPick) {
    const dialog = $('player-dialog');
    if (!dialog) return;
    state.playerPick = onPick;
    const titleEl = $('player-dialog-title');
    if (titleEl) titleEl.textContent = title || '选择玩家';
    const search = $('player-search');
    if (search) search.value = '';
    const list = $('player-list');
    if (list) {
      list.textContent = '';
      const loading = document.createElement('p');
      loading.className = 'muted small';
      loading.textContent = '正在读取玩家列表…';
      list.appendChild(loading);
    }
    dialog.showModal();
    if (!state.userCache) {
      try {
        const data = await UI.api('/api/server/players');
        state.userCache = data.players || [];
      } catch (e) {
        state.userCache = [];
      }
    }
    renderPlayerList();
  }

  function renderPlayerList() {
    const list = $('player-list');
    if (!list) return;
    list.textContent = '';
    const search = $('player-search');
    const query = (search && search.value || '').trim().toLowerCase();
    const players = (state.userCache || []).filter((player) => !query
        || String(player.name || '').toLowerCase().includes(query)
        || String(player.uuid || '').toLowerCase().includes(query));
    if (!players.length) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '没有匹配的已知玩家；可以先让玩家进一次服，或手动填写。';
      list.appendChild(empty);
      return;
    }
    for (const player of players) {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'player-row';
      const name = document.createElement('strong');
      name.textContent = player.name;
      const meta = document.createElement('span');
      meta.className = 'muted small';
      meta.textContent = (player.online ? '在线 · ' : '') + (player.uuid || '');
      const badge = document.createElement('span');
      badge.className = 'world-stack-badge' + (player.online ? ' ok' : '');
      badge.textContent = player.online ? '在线' : '缓存';
      row.append(name, meta, badge);
      row.addEventListener('click', () => {
        const pick = state.playerPick;
        const dialog = $('player-dialog');
        if (dialog) dialog.close();
        if (pick) pick(player);
      });
      list.appendChild(row);
    }
  }

  // ---- 保存 ----

  async function saveConfig() {
    const button = $('server-save');
    const dirtyFiles = state.files.filter((file) => {
      const draft = state.drafts.get(file.id);
      return draft && draft.dirty;
    });
    if (!dirtyFiles.length) {
      message('没有需要保存的修改。', 'ok');
      return;
    }
    if (button) button.disabled = true;
    message('保存中…', '');
    let saved = 0;
    let restart = false;
    let live = false;
    let lastFiles = null;
    try {
      for (const file of dirtyFiles) {
        const draft = state.drafts.get(file.id);
        const data = await UI.api('/api/server/config', {
          method: 'POST',
          body: JSON.stringify({ id: file.id, content: buildContent(draft) }),
        });
        saved++;
        restart = restart || Boolean(data.restartRequired);
        live = live || Boolean(data.applied);
        lastFiles = data.configFiles || lastFiles;
      }
      if (lastFiles) state.files = lastFiles;
      state.drafts.clear();
      renderConfig();
      message('已保存 ' + saved + ' 个文件'
          + (restart ? '；部分需要重启服务器生效' : (live ? '；已立即生效' : '')),
          restart ? 'warning' : 'ok');
    } catch (e) {
      message(UI.authMessage(e, '保存失败'), 'error');
    } finally {
      if (button) button.disabled = false;
      updateDirtyIndicator();
    }
  }

  // ---- 备份时间线 ----

  function renderBackups(data) {
    const stats = $('server-backup-stats');
    const count = $('server-backup-count');
    const timeline = data.timeline || { nodes: [] };
    if (count) count.textContent = String((timeline.nodes || []).length);
    if (stats) {
      stats.textContent = '';
      const items = [
        ['备份数', String((timeline.nodes || []).length)],
        ['当前节点', timeline.headId || '—'],
        ['world 大小', formatBytes(data.worldBytes)],
        ['可用空间', formatBytes(data.freeBytes)],
        ['保留', (data.keep === 0 ? '不自动清理' : '最新 ' + data.keep + ' 份 + 祖先')],
      ];
      for (const [label, value] of items) {
        const stat = document.createElement('div');
        stat.className = 'stat';
        const name = document.createElement('span');
        name.textContent = label;
        const strong = document.createElement('strong');
        strong.textContent = value;
        stat.append(name, strong);
        stats.appendChild(stat);
      }
    }
    renderPending(data);
    renderTimeline(timeline);
  }

  function renderPending(data) {
    const box = $('server-backup-pending');
    if (!box) return;
    const pending = data.pendingRestore || data.pendingBackup;
    if (!pending) {
      box.hidden = true;
      box.textContent = '';
      return;
    }
    box.hidden = false;
    box.textContent = '';
    const strong = document.createElement('strong');
    strong.textContent = pending.mode === 'restore' ? '已排队恢复：' : '已排队创建备份：';
    const text = document.createElement('span');
    text.textContent = (pending.id || '') + ' · 请停服并重新启动，任务会在世界打开前执行。';
    box.append(strong, text);
  }

  function renderTimeline(timeline) {
    const list = $('server-backup-timeline');
    if (!list) return;
    list.textContent = '';
    const nodes = timeline.nodes || [];
    if (!nodes.length) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '还没有本体备份。点右上角“创建备份”，停服重启后生成时间线起点。';
      list.appendChild(empty);
      return;
    }
    let previousBranch = '';
    for (const node of nodes) {
      const item = document.createElement('div');
      item.className = 'timeline-node' + (node.current ? ' current' : '');
      item.style.setProperty('--depth', String(node.depth || 0));
      if (node.depth > 0) item.classList.add('branch');
      if (node.branchId && node.branchId !== previousBranch) item.classList.add('branch-start');
      previousBranch = node.branchId || previousBranch;

      const rail = document.createElement('span');
      rail.className = 'timeline-rail';
      rail.setAttribute('aria-hidden', 'true');

      const card = document.createElement('div');
      card.className = 'timeline-card';
      const head = document.createElement('div');
      head.className = 'timeline-card-head';
      const title = document.createElement('div');
      title.className = 'timeline-card-title';
      const strong = document.createElement('strong');
      strong.textContent = formatTime(node.createdAt);
      const idText = document.createElement('span');
      idText.className = 'muted small mono';
      idText.textContent = node.id;
      title.append(strong, idText);
      const badges = document.createElement('div');
      badges.className = 'timeline-badges';
      if (node.current) {
        const badge = document.createElement('span');
        badge.className = 'world-stack-badge ok';
        badge.textContent = '当前时间线';
        badges.appendChild(badge);
      }
      if (node.restoredAt) {
        const badge = document.createElement('span');
        badge.className = 'world-stack-badge done';
        badge.textContent = '恢复过 ' + formatTime(node.restoredAt);
        badges.appendChild(badge);
      }
      if (node.branchId && node.branchId !== 'main') {
        const badge = document.createElement('span');
        badge.className = 'world-stack-badge';
        badge.textContent = '分支 ' + node.branchId.replace(/^br-/, '');
        badges.appendChild(badge);
      }
      head.append(title, badges);

      const meta = document.createElement('p');
      meta.className = 'muted small';
      meta.textContent = formatBytes(node.zipBytes)
          + ' · world=' + (node.worldName || '—')
          + ' · ' + (node.fileCount || 0) + ' 个文件'
          + (node.parentId ? ' · 父节点 ' + node.parentId : ' · 时间线起点')
          + (node.message ? ' · ' + node.message : '');
      card.append(head, meta);
      if (node.error) {
        const error = document.createElement('p');
        error.className = 'message error';
        error.textContent = '警告：' + node.error;
        card.appendChild(error);
      }

      const actions = document.createElement('div');
      actions.className = 'timeline-actions';
      if (node.current) {
        const current = KIT.button('当前世界', 'ghost', () => {});
        current.disabled = true;
        actions.appendChild(current);
      } else {
        const rollback = KIT.button('回滚到这里', 'primary', () => previewRestore(node.id));
        actions.appendChild(rollback);
      }
      const download = KIT.button('下载', 'ghost', () => downloadBackup(node));
      actions.appendChild(download);
      const del = KIT.button('删除', 'ghost danger', () => deleteBackup(node.id));
      del.disabled = node.deletable === false;
      if (node.deletable === false) {
        del.title = node.current ? '当前时间线节点不能删除' : '还有后续分支，先删除子节点';
      }
      actions.appendChild(del);
      card.appendChild(actions);

      item.append(rail, card);
      list.appendChild(item);
    }
  }

  async function createBackup() {
    backupMessage('读取创建预览…', '');
    try {
      const preview = await UI.api('/api/server/backups/preview', {
        method: 'POST',
        body: JSON.stringify({}),
      });
      renderCreatePreview(preview);
      backupMessage('', '');
      UI.scrollToTarget?.('server-backup-preview');
    } catch (e) {
      if (e.status === 400 && e.body && typeof e.body === 'object'
          && Object.prototype.hasOwnProperty.call(e.body, 'worldBytes')) {
        renderCreatePreview(e.body);
        UI.scrollToTarget?.('server-backup-preview');
        return;
      }
      backupMessage(UI.authMessage(e, '创建备份预览失败'), 'error');
    }
  }

  function previewBox(titleText) {
    const box = $('server-backup-preview');
    if (!box) return null;
    box.hidden = false;
    box.textContent = '';
    const title = document.createElement('div');
    title.className = 'world-stack-preview-title';
    title.textContent = titleText;
    box.append(title);
    return box;
  }

  function appendRows(parent, rows) {
    const dl = document.createElement('dl');
    dl.className = 'world-stack-rows';
    for (const [key, value] of rows) {
      const dt = document.createElement('dt');
      dt.textContent = key;
      const dd = document.createElement('dd');
      dd.textContent = value;
      dl.append(dt, dd);
    }
    parent.append(dl);
  }

  function appendList(parent, className, lines) {
    const list = document.createElement('ul');
    list.className = className;
    for (const line of lines) {
      const item = document.createElement('li');
      item.textContent = line;
      list.append(item);
    }
    parent.append(list);
  }

  function renderCreatePreview(preview) {
    const ok = Boolean(preview && preview.ok);
    const box = previewBox(ok ? '创建备份预览：可以执行' : '创建备份预览：需要修正');
    if (!box) return;
    appendRows(box, [
      ['world', preview.worldName || '—'],
      ['world 大小', formatBytes(preview.worldBytes)],
      ['可用空间', formatBytes(preview.freeBytes)],
      ['需要空间', formatBytes(preview.neededBytes)],
      ['保留', preview.keep === 0 ? '不自动清理' : '最新 ' + preview.keep + ' 份 + 祖先'],
      ['执行时机', '停服后、下次启动前；源 world 不会被删除'],
    ]);
    if (Array.isArray(preview.steps) && preview.steps.length) {
      const steps = document.createElement('div');
      steps.className = 'world-stack-steps';
      const heading = document.createElement('strong');
      heading.textContent = '将执行';
      const ol = document.createElement('ol');
      for (const step of preview.steps) {
        const li = document.createElement('li');
        li.textContent = step;
        ol.appendChild(li);
      }
      steps.append(heading, ol);
      box.appendChild(steps);
    }
    if (!ok) {
      const error = document.createElement('div');
      error.className = 'world-stack-error';
      appendList(error, 'world-stack-error-list', [preview.error || '预览未通过']);
      box.appendChild(error);
      return;
    }
    const confirm = document.createElement('label');
    confirm.className = 'check-line world-stack-confirm';
    const check = document.createElement('input');
    check.type = 'checkbox';
    check.addEventListener('change', () => { run.disabled = !check.checked; });
    const text = document.createElement('span');
    text.textContent = '我确认：停服并重新启动后打包 world、服务器配置与 config/samsara；源 world 保持不变';
    confirm.append(check, text);
    const run = KIT.button('确认创建（需重启）', 'primary', () => confirmCreate());
    run.disabled = true;
    box.append(confirm, run);
  }

  async function confirmCreate() {
    backupMessage('正在排队…', '');
    try {
      const data = await UI.api('/api/server/backups', {
        method: 'POST',
        body: JSON.stringify({ confirm: true }),
      });
      backupMessage(data.message || '已排队，请停服并重新启动。', 'ok');
      const box = $('server-backup-preview');
      if (box) box.hidden = true;
      await loadBackupsOnly();
    } catch (e) {
      backupMessage(UI.authMessage(e, '创建备份失败'), 'error');
    }
  }

  async function previewRestore(id) {
    backupMessage('读取恢复预览…', '');
    try {
      const data = await UI.api('/api/server/backups/preview-restore', {
        method: 'POST',
        body: JSON.stringify({ id }),
      });
      renderRestorePreview(id, data.preview || {});
      backupMessage('', '');
      UI.scrollToTarget?.('server-backup-preview');
    } catch (e) {
      if (e.status === 400 && e.body && e.body.preview) {
        renderRestorePreview(id, e.body.preview);
        UI.scrollToTarget?.('server-backup-preview');
        return;
      }
      backupMessage(UI.authMessage(e, '恢复预览失败'), 'error');
    }
  }

  function renderRestorePreview(id, preview) {
    const ok = Boolean(preview && preview.ok);
    const box = previewBox((ok ? '恢复预览：' : '恢复预览：需要修正 ') + id);
    if (!box) return;
    appendRows(box, [
      ['备份大小', formatBytes(preview.backupBytes)],
      ['备份 world', preview.worldName || '—'],
      ['当前 world', formatBytes(preview.worldBytes)],
      ['可用空间', formatBytes(preview.freeBytes)],
      ['执行时机', '停服后、下次启动前'],
    ]);
    if (Array.isArray(preview.warnings) && preview.warnings.length) {
      const warn = document.createElement('div');
      warn.className = 'world-stack-warning';
      appendList(warn, 'world-stack-warning-list', preview.warnings);
      box.appendChild(warn);
    }
    if (Array.isArray(preview.steps) && preview.steps.length) {
      const steps = document.createElement('div');
      steps.className = 'world-stack-steps';
      const heading = document.createElement('strong');
      heading.textContent = '将执行';
      const ol = document.createElement('ol');
      for (const step of preview.steps) {
        const li = document.createElement('li');
        li.textContent = step;
        ol.appendChild(li);
      }
      steps.append(heading, ol);
      box.appendChild(steps);
    }
    if (!ok || (Array.isArray(preview.errors) && preview.errors.length)) {
      const error = document.createElement('div');
      error.className = 'world-stack-error';
      appendList(error, 'world-stack-error-list',
          preview.errors && preview.errors.length ? preview.errors : ['恢复预览未通过']);
      box.appendChild(error);
      return;
    }
    const confirm = document.createElement('label');
    confirm.className = 'check-line world-stack-confirm';
    const check = document.createElement('input');
    check.type = 'checkbox';
    check.id = 'server-restore-confirm';
    check.addEventListener('change', () => { run.disabled = !check.checked; });
    const span = document.createElement('span');
    span.textContent = '我确认：恢复会替换当前 world；恢复前的世界保留在 pre-restore-<job>/world';
    confirm.append(check, span);
    const run = KIT.button('确认排队恢复（需重启）', 'danger', () => confirmRestore(id, check));
    run.disabled = true;
    box.append(confirm, run);
  }

  async function confirmRestore(id, check) {
    if (!check || !check.checked) {
      backupMessage('请先勾选确认项', 'warning');
      return;
    }
    try {
      const data = await UI.api('/api/server/backups/restore', {
        method: 'POST',
        body: JSON.stringify({ id, confirm: true }),
      });
      backupMessage(data.message || '已排队恢复，请停服并重新启动。', 'ok');
      const box = $('server-backup-preview');
      if (box) box.hidden = true;
      await loadBackupsOnly();
    } catch (e) {
      backupMessage(UI.authMessage(e, '排队恢复失败'), 'error');
    }
  }

  async function deleteBackup(id) {
    if (!window.confirm('删除备份 ' + id + '？源 world 不会受影响。')) return;
    try {
      const data = await UI.api('/api/server/backups/delete', {
        method: 'POST',
        body: JSON.stringify({ id, confirm: true }),
      });
      backupMessage(data.message || '已删除', 'ok');
      await loadBackupsOnly();
    } catch (e) {
      backupMessage(UI.authMessage(e, '删除失败'), 'error');
    }
  }

  async function downloadBackup(backup) {
    if (Number(backup.zipBytes) > 256 * 1024 * 1024) {
      const ok = window.confirm('备份超过 256MB，浏览器下载会占用大量内存。仍要下载？');
      if (!ok) return;
    }
    try {
      const response = await fetch('/api/server/backups/download?id=' + encodeURIComponent(backup.id), {
        headers: UI.authHeaders(),
      });
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = backup.id + '.zip';
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      backupMessage(UI.authMessage(e, '下载失败'), 'error');
    }
  }

  function renderJobs(jobs) {
    const list = $('server-job-list');
    if (!list) return;
    list.textContent = '';
    if (!jobs.length) {
      const empty = document.createElement('p');
      empty.className = 'muted small';
      empty.textContent = '还没有任务记录。';
      list.appendChild(empty);
      return;
    }
    for (const job of jobs) {
      const card = document.createElement('div');
      card.className = 'server-job ' + (job.status || '');
      const head = document.createElement('div');
      head.className = 'server-job-head';
      const title = document.createElement('strong');
      title.textContent = (job.type || 'task') + ' · ' + (job.id || '');
      const status = document.createElement('span');
      status.className = 'world-stack-badge ' + (job.status || '');
      status.textContent = (job.status || '') + ' · ' + (job.stage || '');
      head.append(title, status);
      card.appendChild(head);
      if (job.message) {
        const p = document.createElement('p');
        p.className = 'muted small';
        p.textContent = job.message;
        card.appendChild(p);
      }
      if (job.log && job.log.length) {
        const log = document.createElement('div');
        log.className = 'server-job-log';
        log.textContent = job.log.join('\n');
        card.appendChild(log);
      }
      list.appendChild(card);
    }
  }

  // ---- events ----

  for (const tab of document.querySelectorAll('.top-tab')) {
    tab.addEventListener('click', () => setSection(tab.dataset.section));
  }
  for (const tab of document.querySelectorAll('.tab[data-server-panel]')) {
    tab.addEventListener('click', () => setServerPanel(tab.dataset.serverPanel));
  }
  const search = $('server-config-search');
  if (search) {
    search.addEventListener('input', () => {
      state.search = search.value;
      renderConfig();
    });
  }
  const save = $('server-save');
  if (save) save.addEventListener('click', saveConfig);
  const reload = $('server-reload');
  if (reload) {
    reload.addEventListener('click', async () => {
      if (state.serverPanel === 'server-backup-panel') {
        await loadBackupsOnly();
        backupMessage('已重新读取', 'ok');
      } else {
        const data = await loadAll();
        if (data) message('已重新读取', 'ok');
      }
    });
  }
  const backupCreate = $('server-backup-create');
  if (backupCreate) backupCreate.addEventListener('click', createBackup);

  const playerDialog = $('player-dialog');
  const playerCancel = $('player-cancel');
  if (playerCancel && playerDialog) {
    playerCancel.addEventListener('click', () => playerDialog.close());
  }
  const playerSearch = $('player-search');
  if (playerSearch) playerSearch.addEventListener('input', renderPlayerList);

  const configFiles = $('server-config-files');
  if (configFiles) {
    configFiles.addEventListener('toggle', (event) => {
      const target = event.target;
      if (target && target.id) state.openStates.set(target.id, target.open);
    }, true);
  }

  UI.onConnect(() => {
    if (state.section !== 'server') return null;
    if (state.serverPanel === 'server-backup-panel') {
      backupMessage('', '');
      return loadBackupsOnly();
    }
    return loadAll();
  });

  UI.onConnected(() => {
    if (state.section === 'server') renderServerNav();
  });

  const initialHash = location.hash || '';
  if (initialHash === '#server' || initialHash === '#server-backup') {
    setSection('server');
    if (initialHash === '#server-backup') setServerPanel('server-backup-panel');
  }

  window.SamsaraServer = {
    activate: () => setSection('server'),
    reload: () => loadAll(),
    state,
  };
})();


