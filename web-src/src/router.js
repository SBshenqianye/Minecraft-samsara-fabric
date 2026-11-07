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
const ContainerView = () => import('./views/ContainerView.vue');
const UserView = () => import('./views/UserView.vue');

const router = createRouter({
  history: createWebHistory('/'),
  routes: [
    {
      path: '/',
      component: AdminLayout,
      children: [
        { path: '', redirect: '/config' },
        { path: 'config', component: ConfigPage, meta: { tab: 'config' } },
        { path: 'config-editor', component: ConfigEditorPage, meta: { tab: 'config-editor' } },
        { path: 'claim-range', component: ClaimRangePage, meta: { tab: 'claim-range' } },
        { path: 'output', component: OutputPage, meta: { tab: 'output' } },
        { path: 'permissions', component: PermissionsPage, meta: { tab: 'permissions' } },
        { path: 'map', component: MapPage, meta: { tab: 'map' } },
        { path: 'announcements', component: AnnouncementsPage, meta: { tab: 'announcements' } },
        { path: 'world-stack', component: WorldStackPage, meta: { tab: 'world-stack' } },
        { path: 'lottery', component: LotteryPage, meta: { tab: 'lottery' } },
        { path: 'identity', component: IdentityPage, meta: { tab: 'identity' } },
        { path: 'server/config', component: ServerPage, meta: { server: true, serverPanel: 'server-config-panel' } },
        { path: 'server/backup', component: ServerPage, meta: { server: true, serverPanel: 'server-backup-panel' } },
        { path: 'server/console', component: ServerPage, meta: { server: true, serverPanel: 'server-console-panel' } },
        { path: 'server/audit', component: ServerPage, meta: { server: true, serverPanel: 'server-audit-panel' } },
      ],
    },
    { path: '/containers', name: 'containers', component: ContainerView },
    { path: '/user', name: 'user', component: UserView },
    { path: '/:pathMatch(.*)*', redirect: '/config' },
  ],
});

export default router;
