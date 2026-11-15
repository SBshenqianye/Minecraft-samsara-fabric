# 页面内核迁移作战板（已完成）

> 目标：把旧 `mod/src/main/resources/web/*.js` 页面、`legacy-core/`、`legacy/` 全部迁入 `web-src/src/`，同时保留 canvas / contenteditable / 3D / PageSpec 算法。
>
> 状态：**全部完成。** 旧目录、`LegacyPanel`、`useLegacyPage`、`window.Samsara*`、`app.js` 壳都已删除。

## 最终状态

| 页面 | 旧文件 | 新落点 | E2E |
|---|---|---|---|
| 公告 | `announcements.js` | `pages/AnnouncementsPage.vue` | `sections/announcements.mjs` |
| 权限 | `permissions.js` | `pages/PermissionsPage.vue` | `sections/permissions.mjs` |
| RCON 控制台 | `server.js` console 段 | `pages/ServerConsolePage.vue` | `sections/server-console.mjs` |
| 输出格式 | `output.js` | `pages/OutputPage.vue` + `pages/output/*` | `sections/output.mjs` |
| 经济业务 | `lottery.js` | `pages/LotteryPage.vue` + `pages/lottery/*` | `sections/lottery.mjs` |
| 身份组 | `identity.js` | `pages/IdentityPage.vue` + `pages/identity/*` | `sections/identity.mjs` |
| 配置编辑器 | `config-editor.js`、`structured.js`、`form-kit.js`、`chat-text.js` | `pages/ConfigEditorPage.vue` + `pages/config-editor/*` | `sections/config-editor.mjs` |
| 地图 | `map.js`、`stack-icon.js` | `pages/MapPage.vue` + `pages/map/*` | `sections/map.mjs` |
| 世界栈 | `world-stack.js` | `pages/WorldStackPage.vue` + `pages/world-stack/*` | `sections/world-stack.mjs` |
| 本体配置/备份/审计 | `server.js` | `pages/ServerPage.vue` + `pages/server/*` | `sections/server.mjs`、`sections/pages.mjs` |
| 高级配置/圈地 | `app.js` config 段 | `pages/ConfigPage.vue`、`pages/ClaimRangePage.vue` + `pages/config/*` | `sections/app-config.mjs` |
| 公开用户端 | `user.js` | `pages/UserPage.vue` + `pages/user/UserValueTable.vue` + `pages/map/MapPanel.vue` | 全量 `web-e2e.mjs` |
| app shell | `app.js`、`page-kit.js`、`legacy/admin.js` | `AdminLayout.vue`、`composables/useSession.js`、`AdminOverlays.vue` | `sections/batch5.mjs`、`sections/pages.mjs` |

## 已删除

| 项目 | 处理 |
|---|---|
| `web-src/src/legacy-core/` | 全部删除；算法迁入 `pages/<page>/` |
| `web-src/src/legacy/` | 全部删除 |
| `LegacyPanel` / `useLegacyPage` | 删除 |
| `window.Samsara*` | 删除；框架弹窗改用 `useFrameworkDialog.js` 模块状态 |
| 旧 HTML / `/style.css` / `/web/*.js` | 删除；HTTP 404 |
| `blackframe`/静态脚本路由 | `WebSecurity` 只保留 SPA 与 `/openapi.json` |

## Definition of Done

1. 纯逻辑进 `pages/<page>/model.js`，有单测。
2. 页面由 Vue 渲染，不依赖旧内核或全局桥。
3. `tools/e2e/sections/<page>.mjs` 注册并通过。
4. `npm run check` + `node tools/agent/verify-page.mjs --page <page>` 通过。
5. 全量 `web-e2e.mjs` 通过，且不使用“历史问题”豁免。
6. 更新 `CHANGELOG.md` / `docs/实现状态.md`。

## 并行规则

- 一个 Agent 只改自己的 `pages/<page>/*` 与 section。
- `router.js` / `navigation.js` / `AdminLayout.vue` / `WebSecurity.java` 只由集成 Agent 改。
- canvas / 富文本页面保留算法边界，禁止重写算法。
- 每个批次结束统一构建 jar、测试服 E2E、提交；生产部署按 `samsara-release`。
