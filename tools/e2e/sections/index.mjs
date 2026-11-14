import announcements from './announcements.mjs';
import permissions from './permissions.mjs';

/**
 * 页面级 E2E 注册表。
 * 新迁移页面只加自己的 section；全量回归继续由 tools/web-e2e.mjs 承担。
 */
export const sections = {
  announcements,
  permissions,
};
