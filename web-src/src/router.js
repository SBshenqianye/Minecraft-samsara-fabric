import { createRouter, createWebHistory } from 'vue-router';

const AdminLayout = () => import('./layouts/AdminLayout.vue');
const ConfigPage = () => import('./pages/ConfigPage.vue');
const ConfigEditorPage = () => import('./pages/ConfigEditorPage.vue');
const ClaimRangePage = () => import('./pages/ClaimRangePage.vue');
const OutputPage = () => import('./pages/OutputPage.vue');
const PermissionsPage = () => import('./pages/PermissionsPage.vue');
const MapPage = () => import('./pages/MapPage.vue');
const AnnouncementsPage = () => import('./pages/AnnouncementsPage.vue');
const WorldStackPage = () => import('./pages/WorldStackPage.vue');
const LotteryPage = () => import('./pages/LotteryPage.vue');
const IdentityPage = () => import('./pages/IdentityPage.vue');
const ServerPage = () => import('./pages/ServerPage.vue');
const ContainerPage = () => import('./pages/ContainerPage.vue');
const InventoryPage = () => import('./pages/InventoryPage.vue');
const PoolInventoryPage = () => import('./pages/PoolInventoryPage.vue');
const TrackPage = () => import('./pages/TrackPage.vue');
const DiffPage = () => import('./pages/DiffPage.vue');
const ModConfigPage = () => import('./pages/ModConfigPage.vue');
const UserPage = () => import('./pages/UserPage.vue');

const samsara = (section, page, component, title, panel, extra = {}) => ({
  component,
  meta: { workspace: 'samsara', section, page, title, ...(panel ? { panel } : {}), ...extra },
});

const legacyConfig = (path, alias, component, page, title, panel) => ({
  path,
  alias,
  component,
  meta: { workspace: 'samsara', section: 'config', page, title, panel },
});

const legacyWorld = (path, alias, component, page, title, panel) => ({
  path,
  alias,
  component,
  meta: { workspace: 'samsara', section: 'world', page, title, panel },
});

const legacyCommunity = (path, alias, component, page, title, panel) => ({
  path,
  alias,
  component,
  meta: { workspace: 'samsara', section: 'community', page, title, panel },
});

const vanilla = (path, serverPanel, title) => ({
  path,
  component: ServerPage,
  meta: {
    workspace: 'vanilla',
    section: path.substring(path.lastIndexOf('/') + 1),
    page: 'server',
    title,
    serverPanel,
  },
});

function normalizeAliases(routes) {
  for (const route of routes) {
    if (Array.isArray(route.alias)) {
      route.alias = route.alias.map((path) => (path.startsWith('/') ? path : '/' + path));
    } else if (typeof route.alias === 'string' && !route.alias.startsWith('/')) {
      route.alias = '/' + route.alias;
    }
    if (Array.isArray(route.children)) normalizeAliases(route.children);
  }
}

/**
 * 二级分区下的三级导航。label 必须与对应路由 meta.title 完全一致。
 * 旧专用页（经济业务）继续使用页内锚点导航，所以这里不重复列。
 */
export const SECTION_NAV = {
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
  economy: [
    { id: 'overview', label: '概览', target: '/samsara/economy' },
    { id: 'fund', label: '服务器基金', target: '/samsara/economy/fund' },
    { id: 'lottery', label: '死人乐透', target: '/samsara/economy/lottery' },
    { id: 'insurance', label: '保险', target: '/samsara/economy/insurance' },
  ],
};

const routes = [
    {
      path: '/',
      component: AdminLayout,
      children: [
        { path: '', redirect: '/samsara/economy' },

        // ---- 本体（Minecraft / Vanilla）----
        vanilla('vanilla/config', 'server-config-panel', '服务器配置'),
        vanilla('vanilla/backup', 'server-backup-panel', '存档备份'),
        vanilla('vanilla/console', 'server-console-panel', 'RCON 控制台'),

        // Web 审计属于 samsara Web 管理，不再放在“本体”工作区。
        {
          path: 'samsara/system/audit',
          component: ServerPage,
          meta: {
            workspace: 'samsara',
            section: 'system',
            page: 'server',
            title: 'Web 审计',
            serverPanel: 'server-audit-panel',
          },
        },

        // 旧 /server/* 只做前端 redirect，不再作为可见规范路径。
        { path: 'server', redirect: '/vanilla/config' },
        { path: 'server/config', redirect: '/vanilla/config' },
        { path: 'server/backup', redirect: '/vanilla/backup' },
        { path: 'server/console', redirect: '/vanilla/console' },
        { path: 'server/audit', redirect: '/samsara/system/audit' },
        { path: 'vanilla/audit', redirect: '/samsara/system/audit' },

        // ---- samsara / 经济业务 ----
        {
          ...samsara('economy', 'lottery', LotteryPage, '概览', 'lottery',
              { subpage: 'overview' }),
          path: 'samsara/economy',
          alias: ['lottery', 'samsara/economy/overview'],
        },
        {
          ...samsara('economy', 'lottery', LotteryPage, '服务器基金', 'lottery',
              { subpage: 'fund' }),
          path: 'samsara/economy/fund',
          alias: ['samsara/economy/values'],
        },
        // 基金仓库已内嵌到「服务器基金」页下方；旧链接保留重定向，避免书签 404。
        { path: 'samsara/economy/fund-inventory', redirect: '/samsara/economy/fund' },
        {
          ...samsara('economy', 'lottery', LotteryPage, '死人乐透', 'lottery',
              { subpage: 'lottery' }),
          path: 'samsara/economy/lottery',
          alias: ['samsara/economy/current', 'samsara/economy/history'],
        },
        {
          ...samsara('economy', 'lottery', LotteryPage, '保险', 'lottery',
              { subpage: 'insurance' }),
          path: 'samsara/economy/insurance',
        },

        // ---- samsara / 天网 ----
        {
          ...samsara('sky', 'track', TrackPage, '轨迹回看', null),
          path: 'samsara/sky/track',
          alias: ['track'],
        },
        {
          ...samsara('sky', 'diff', DiffPage, 'Diff 回看', null),
          path: 'samsara/sky/diff',
          alias: ['diff'],
        },
        {
          ...samsara('sky', 'containers', ContainerPage, '天网', null),
          path: 'samsara/sky/containers',
          alias: ['sky/containers'],
          props: { embedded: true },
        },
        // 天网玩家已并入统一地图：旧页面只保留重定向，避免书签 404。
        { path: 'samsara/sky/inventory', alias: ['inventory'], redirect: '/samsara/sky/containers' },
        // Web 背包已改为 samsara 全局浮窗（右侧「背包」标签 / E 键）；旧链接保留重定向。
        { path: 'samsara/sky/web-inventory', redirect: '/samsara/sky/containers' },

        // ---- samsara / 配置 ----
        legacyConfig('samsara/config/editor', ['config-editor'], ConfigEditorPage, 'config-editor', '配置编辑', 'config-editor'),
        legacyConfig('samsara/config/advanced', ['config'], ConfigPage, 'config', '高级配置', 'config'),
        legacyConfig('samsara/config/claim-range', ['claim-range'], ClaimRangePage, 'claim-range', '圈地范围', 'claim-range'),
        legacyConfig('samsara/config/output', ['output'], OutputPage, 'output', '输出', 'output'),
        legacyConfig('samsara/config/permissions', ['permissions'], PermissionsPage, 'permissions', '权限', 'permissions'),
        { path: 'samsara/config', redirect: '/samsara/config/editor' },

        // ---- samsara / 世界 ----
        legacyWorld('samsara/world/map', ['map'], MapPage, 'map', '地图', 'map'),
        legacyWorld('samsara/world/stack', ['world-stack'], WorldStackPage, 'world-stack', '世界栈', 'world-stack'),
        { path: 'samsara/world', redirect: '/samsara/world/map' },

        // ---- samsara / 社区 ----
        legacyCommunity('samsara/community/announcements', ['announcements'], AnnouncementsPage, 'announcements', '公告', 'announcements'),
        legacyCommunity('samsara/community/identity', ['identity'], IdentityPage, 'identity', '身份组', 'identity'),
        { path: 'samsara/community', redirect: '/samsara/community/announcements' },

        // ---- 其他模组 ----
        {
          path: 'mods/:modId',
          component: ModConfigPage,
          meta: { workspace: 'mods', section: 'files', page: 'mod-config', title: '配置文件' },
        },
        {
          path: 'mods/:modId/:file(.*)',
          component: ModConfigPage,
          meta: { workspace: 'mods', section: 'files', page: 'mod-config', title: '配置文件' },
        },
      ],
    },
    { path: '/containers', name: 'containers', component: ContainerPage },
    // 身份组背包已改为全局大窗口（IdentityInventoryDialog），不再有独立 iframe 页面。
    { path: '/identity-inventory', redirect: '/samsara/community/identity' },
    { path: '/user', name: 'user', component: UserPage },
  { path: '/:pathMatch(.*)*', redirect: '/samsara/economy' },
];

normalizeAliases(routes);

const router = createRouter({
  history: createWebHistory('/'),
  routes,
});

export default router;
