import { createApp } from 'vue';
import App from './App.vue';
import router from './router.js';
import './style.css';
import './ia.css';
import './shell.css';

window.SamsaraUseRouter = true;

const LEGACY_HASH_ROUTES = {
  config: '/config',
  'config-editor': '/config-editor',
  'claim-range': '/claim-range',
  identity: '/identity',
  output: '/output',
  permissions: '/permissions',
  map: '/map',
  announcements: '/announcements',
  'world-stack': '/world-stack',
  lottery: '/lottery',
  server: '/server/config',
  'server-backup': '/server/backup',
  'server-console': '/server/console',
  'server-audit': '/server/audit',
  containers: '/containers',
};

const app = createApp(App).use(router);
const legacy = LEGACY_HASH_ROUTES[window.location.hash.replace(/^#/, '')];
const boot = legacy ? router.replace(legacy) : Promise.resolve();
boot.finally(() => app.mount('#app'));
