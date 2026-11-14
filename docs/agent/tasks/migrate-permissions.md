# Task: migrate permissions

## Goal
把 `mod/src/main/resources/web/permissions.js` 迁移为 `web-src/src/pages/PermissionsPage.vue`，删除该页的 `LegacyPanel` 与旧脚本加载。

## Scope
- allowed:
  - `web-src/src/pages/PermissionsPage.vue`
  - `web-src/src/pages/permissions/model.js`
  - `web-src/src/pages/permissions/model.test.js`
  - `web-src/src/pages/permissions/PermissionNode.vue`
  - `web-src/src/pages/permissions/PermLevelControl.vue`
  - `tools/e2e/sections/permissions.mjs`
  - `tools/e2e/sections/index.mjs`
- integration（本任务内允许一次）:
  - `web-src/src/router.js`（移除 `meta.panel`）
  - `web-src/src/navigation.js`（确认 keep-alive 名单）
  - `web-src/src/legacy/admin.js`（移除 `permissions.js`）
  - `web-src/src/layouts/AdminLayout.vue`（HERO_HINTS）
  - `tools/web-e2e.mjs`（恢复 UI 改为点击原生刷新按钮）
  - `CHANGELOG.md` / `docs/实现状态.md`

## Contract
- Route: `/samsara/config/permissions`
- API:
  - `GET /api/permissions` → `{ path, tree, owners, warnings }`
  - `PUT /api/permissions` body `{ nodes: [{ path, level?, disabled? }] }` 或 `{ owners: [...] }`
  - `POST /api/permissions/reload`
- Tree node: `{ path, label, level, effectiveLevel, disabled, disabledBy, source, recovery, children }`
- States: loading / empty / error / busy / owner-dirty
- Keep-alive: yes（`PermissionsPage` 已在 `KEEP_ALIVE_PAGES`）

## 必须保留的行为
- 统计行：命令路径 / 关闭权限 / 停用节点 / 自定义规则 / 叶子
- 权限树：层级缩进、展开/折叠、搜索过滤、祖先停用提示、recovery 标记
- 等级控件：deny / inherit / config / owner / op / user；`.perm-levels .segmented-btn[data-value]` 兼容
- “停用节点”与“关闭权限”分离
- Owner 名单：添加/移除/保存
- `#perm-node-<encodeURIComponent(path)>`、`#perm-tree`、`#perm-search`、`#perm-refresh` 等 E2E id

## Acceptance
- [ ] `cd web-src && npm.cmd run check`
- [ ] `node tools/agent/verify-page.mjs --page permissions --base http://127.0.0.1:8722 --token <token>`
- [ ] 全量 E2E 的权限分段控件与导航断言仍通过
- [ ] `permissions.js` 不再被 `web-src/src/legacy/admin.js` 加载
- [ ] `CHANGELOG.md` / `docs/实现状态.md` 已更新

## Evidence
- 单测：`web-src/src/pages/permissions/model.test.js`
- E2E：`tools/e2e/sections/permissions.mjs`
- 旧脚本保留在 `mod/src/main/resources/web/permissions.js` 供 `/legacy.html` 回退。
