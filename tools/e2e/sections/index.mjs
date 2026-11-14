import announcements from './announcements.mjs';
import output from './output.mjs';
import permissions from './permissions.mjs';
import serverConsole from './server-console.mjs';

/**
 * 页面级 E2E 注册表。
 * 新迁移页面只加自己的 section；全量回归继续由 tools/web-e2e.mjs 承担。
 */
export const sections = {
  announcements,
  output,
  permissions,
  'server-console': serverConsole,
};
