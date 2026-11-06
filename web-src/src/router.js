import { createRouter, createWebHistory } from 'vue-router';

const AdminView = () => import('./views/AdminView.vue');
const UserView = () => import('./views/UserView.vue');
const ContainerView = () => import('./views/ContainerView.vue');

const router = createRouter({
  history: createWebHistory('/'),
  routes: [
    { path: '/', name: 'admin', component: AdminView },
    { path: '/containers', name: 'containers', component: ContainerView },
    { path: '/user', name: 'user', component: UserView },
    { path: '/:pathMatch(.*)*', name: 'fallback', component: AdminView },
  ],
});

export default router;
