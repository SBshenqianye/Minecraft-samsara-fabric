/**
 * Web 管理端的信息架构唯一来源：工作区、二级分区、三级子页、旧链接别名。
 * router.js / AdminLayout.vue / main.js 都从这里取，避免同一套导航在四处各写一遍。
 */

export const WORKSPACES = [
  { id: 'vanilla', label: '本体', hint: 'Minecraft / Vanilla' },
  { id: 'samsara', label: '周目管理', hint: 'samsara' },
];

/** 二级分区：id 必须与路由 meta.section 一致。 */
export const SECTIONS = {
  samsara: [
    { id: 'economy', label: '经济业务', target: '/samsara/economy' },
    { id: 'sky', label: '天网', target: '/samsara/sky/track' },
    { id: 'config', label: '配置', target: '/samsara/config/editor' },
    { id: 'world', label: '世界', target: '/samsara/world/map' },
    { id: 'community', label: '社区', target: '/samsara/community/announcements' },
    { id: 'system', label: '系统', target: '/samsara/system/audit' },
  ],
  vanilla: [
    { id: 'config', label: '服务器配置', target: '/vanilla/config' },
    { id: 'backup', label: '存档备份', target: '/vanilla/backup' },
    { id: 'console', label: 'RCON', target: '/vanilla/console' },
  ],
};

/**
 * 二级分区下的三级导航。label 必须与对应路由 meta.title 完全一致。
 * 旧专用页（经济业务）继续使用页内锚点导航，所以这里不重复列。
 */
export const SECTION_NAV = {
  economy: [
    { id: 'overview', label: '概览', target: '/samsara/economy' },
    { id: 'fund', label: '服务器基金', target: '/samsara/economy/fund' },
    { id: 'lottery', label: '死人乐透', target: '/samsara/economy/lottery' },
    { id: 'insurance', label: '保险', target: '/samsara/economy/insurance' },
  ],
  sky: [
    { id: 'track', label: '轨迹回看', target: '/samsara/sky/track' },
    { id: 'diff', label: 'Diff 回看', target: '/samsara/sky/diff' },
    { id: 'sky', label: '天网', target: '/samsara/sky/containers' },
  ],
  world: [
    { id: 'map', label: '地图', target: '/samsara/world/map' },
    { id: 'world-stack', label: '世界栈', target: '/samsara/world/stack' },
  ],
  config: [
    { id: 'config-editor', label: '配置编辑', target: '/samsara/config/editor' },
    { id: 'config', label: '高级配置', target: '/samsara/config/advanced' },
    { id: 'claim-range', label: '圈地范围', target: '/samsara/config/claim-range' },
    { id: 'output', label: '输出', target: '/samsara/config/output' },
    { id: 'permissions', label: '权限', target: '/samsara/config/permissions' },
  ],
  community: [
    { id: 'announcements', label: '公告', target: '/samsara/community/announcements' },
    { id: 'identity', label: '身份组', target: '/samsara/community/identity' },
  ],
  system: [
    { id: 'audit', label: 'Web 审计', target: '/samsara/system/audit' },
  ],
};

/**
 * 旧 tab 名 / URL hash → 规范路径。
 * AdminLayout 的旧 setActiveTab 桥接与 main.js 的 hash 兼容共用这一份。
 */
export const LEGACY_ALIASES = {
  config: '/samsara/config/advanced',
  'config-editor': '/samsara/config/editor',
  'claim-range': '/samsara/config/claim-range',
  output: '/samsara/config/output',
  permissions: '/samsara/config/permissions',
  map: '/samsara/world/map',
  announcements: '/samsara/community/announcements',
  'world-stack': '/samsara/world/stack',
  lottery: '/samsara/economy',
  identity: '/samsara/community/identity',
  server: '/vanilla/config',
  'server-backup': '/vanilla/backup',
  'server-console': '/vanilla/console',
  'server-audit': '/samsara/system/audit',
  containers: '/containers',
};

/**
 * 需要跨路由保留实例的页面（keep-alive include）。
 * 对应页面必须用 defineOptions({ name }) 声明同样的组件名。
 */
export const KEEP_ALIVE_PAGES = [
  'LotteryPage',
  'ConfigPage',
  'ClaimRangePage',
  'OutputPage',
  'PermissionsPage',
  'MapPage',
  'AnnouncementsPage',
  'WorldStackPage',
  'IdentityPage',
  'ServerPage',
];
