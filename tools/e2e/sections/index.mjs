import announcements from './announcements.mjs';
import appConfig from './app-config.mjs';
import batch5 from './batch5.mjs';
import configEditor from './config-editor.mjs';
import identity from './identity.mjs';
import lottery from './lottery.mjs';
import map from './map.mjs';
import navigation from './navigation.mjs';
import output from './output.mjs';
import pages from './pages.mjs';
import permissions from './permissions.mjs';
import server from './server.mjs';
import serverConsole from './server-console.mjs';
import skyInventory from './sky-inventory.mjs';
import storage from './storage.mjs';
import worldStack from './world-stack.mjs';

/**
 * 页面级 E2E 注册表。
 * 新迁移页面只加自己的 section；全量回归继续由 tools/web-e2e.mjs 承担。
 */
export const sections = {
  announcements,
  'app-config': appConfig,
  'config-editor': configEditor,
  identity,
  lottery,
  map,
  navigation,
  output,
  permissions,
  server,
  'server-console': serverConsole,
  pages,
  'world-stack': worldStack,
  'sky-inventory': skyInventory,
  storage,
  batch5,
};
