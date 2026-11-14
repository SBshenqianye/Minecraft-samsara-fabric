# Legacy 全量迁移作战板

> 目标：把 `mod/src/main/resources/web/*.js` 的管理端页面全部迁到 `web-src/src/`，最终删除 `LegacyPanel`、`legacy/`、`legacy.html` 与旧静态路径。
>
> 迁移原则：**一次一个页面切片**，每片自带 model/单测/section E2E，不按文件大小硬切，不重写 canvas/富文本内核。

## 状态

| 状态 | 含义 |
|---|---|
| ✅ done | 原生页面 + section E2E + 旧脚本从 `legacy/admin.js` 移除 |
| 🚧 in-progress | 已建任务 spec，正在实现 |
| ⏳ pending | 尚未开始 |
| 🧱 shell | 旧壳/共享模块，最后处理 |
| 🔗 shared | 其他页面仍依赖，先保留 |

## 已完成

| 页面 | 旧文件 | 原生页面 | E2E |
|---|---|---|---|
| 公告 | `announcements.js` | `pages/AnnouncementsPage.vue` | `sections/announcements.mjs` |
| 权限 | `permissions.js` | `pages/PermissionsPage.vue` | `sections/permissions.mjs` |
| RCON 控制台 | `server.js` console 段 | `pages/ServerConsolePage.vue` | `sections/server-console.mjs` |
| 输出格式（壳） | `output.js` | `pages/OutputPage.vue` | `sections/output.mjs`（内核按页加载，待抽纯逻辑） |
| 经济业务（壳） | `lottery.js` | `pages/LotteryPage.vue` | `sections/legacy-shell.mjs` |
| 身份组（壳） | `identity.js` | `pages/IdentityPage.vue` | `sections/legacy-shell.mjs` |
| 配置编辑器（壳） | `config-editor.js` | `pages/ConfigEditorPage.vue` | `sections/legacy-shell.mjs` |
| 地图（壳） | `map.js` | `pages/MapPage.vue` | `sections/legacy-shell.mjs` |
| 世界栈（壳） | `world-stack.js` | `pages/WorldStackPage.vue` | `sections/legacy-shell.mjs` |
| 本体配置/备份/审计（壳） | `server.js` | `pages/ServerPage.vue` | `sections/legacy-shell.mjs` |

## 批次 1–4：已完成（页面壳 + 按页加载内核）

> 批次 2–4 的复杂页面采用 Vue 壳 + `useLegacyPage`：脚本不再进全局 `legacy/admin.js`，由页面挂载后按需加载。旧内核保留，后续可继续抽纯逻辑。

## 批次 1：中等复杂度、可独立迁移

| 任务 | 旧文件 | 目标 | 关键点 |
|---|---|---|---|
| output | `output.js` | `pages/OutputPage.vue` | **保留 contenteditable 内核**；Vue 管消息列表/工具栏/API；不重写 chips 拖拽/撤销 |
| server-console | `server.js` 的 console 段 | `pages/ServerConsolePage.vue` | 先拆 RCON；保留补全、历史、危险命令确认、E2E id |

## 批次 2：业务与 server 其余面板

| 任务 | 旧文件 | 目标 | 关键点 |
|---|---|---|---|
| lottery | `lottery.js` | `pages/LotteryPage.vue` | 抽金额/轮次/下注纯函数；保留基金仓库嵌入 |
| server-config | `server.js` config 段 | `pages/ServerConfigPage.vue` | 复用 PageSpec/FormSpec 渲染 |
| server-backup | `server.js` backup 段 | `pages/ServerBackupPage.vue` | 时间线、恢复预览、不可自动回滚标记 |
| server-audit | `server.js` audit 段 | `pages/ServerAuditPage.vue` | 分页、diff、快照预览 |

## 批次 3：高复杂度页面

| 任务 | 旧文件 | 目标 | 关键点 |
|---|---|---|---|
| identity | `identity.js` | `pages/IdentityPage.vue` | 列表/详情/背包 iframe/成就分页；可拆子组件 |
| config-editor | `config-editor.js` + `structured.js`/`form-kit.js` | `pages/ConfigEditorPage.vue` | 先抽 PageSpec/FormSpec 渲染器，再替换页面 |

## 批次 4：canvas / 3D

| 任务 | 旧文件 | 目标 | 关键点 |
|---|---|---|---|
| claim-range | `app.js` claim-range 段 | `pages/ClaimRangePage.vue` | 保留画布/旗帜算法，Vue 管表单与导航 |
| map | `map.js` | `pages/MapPage.vue` | 保留 tile/canvas 内核，Vue 管工具栏/详情/生命周期 |
| world-stack | `world-stack.js` | `pages/WorldStackPage.vue` | 保留时间线/3D/图标内核，Vue 管外层 |

## 批次 5：旧壳与共享模块（已完成）

> 全部页面面板已嵌入 Vue 页面；`app.js`、`page-kit.js`、共享脚本与 `style.css` 已迁入 `web-src/src/legacy-core/`、`web-src/src/legacy.css`，随 Vite 打包，不再作为 `/web/*.js`、`/style.css` 静态资源提供。
>
> `legacy.html`（旧 `index.html`）、`legacy-user.html`（旧 `user.html`）、`identity.html`、`lottery.html` 已删除；管理员浮窗迁到 `layouts/AdminOverlays.vue`，公开用户端结构迁到 `pages/UserPage.vue`。

| 项目 | 处理 |
|---|---|
| app shell | `app.js`、`page-kit.js` 作为 bundle 内核保留，页面生命周期由 Vue 接管 |
| shared | `chat-text.js`、`structured.js`、`form-kit.js`、`value-table.js`、`item-ui.js`、`banners.js`、`particles.js` 随 bundle 加载 |
| fallback | 旧 HTML 壳、`/style.css`、旧静态脚本路由全部删除；`WebSecurity` 只保留 SPA 与 `/openapi.json` |
| E2E | `tools/e2e/sections/batch5.mjs`：旧路径 404、`/user` 五分区、地图内核、无旧脚本请求 |

| 任务 | 旧文件 | 说明 |
|---|---|---|
| app shell | `app.js`、`page-kit.js` | 删除 AdminLayout 的 `loadLegacyAdminScripts`、`setActiveTab` 桥、旧 panel id |
| shared | `chat-text.js`、`structured.js`、`form-kit.js`、`value-table.js`、`item-ui.js`、`banners.js`、`particles.js` | 逐步替换为 Vue 组件/composable，最后删除 |
| fallback | `legacy.html`、`legacy-user.html`、`/style.css` | 全量原生覆盖后删除，保留 Git 历史即可 |

## 每个任务的 Definition of Done

1. `docs/agent/tasks/migrate-<page>.md` 已写。
2. 纯逻辑进 `pages/<page>/model.js`，有单测。
3. 原生 Vue 页面不再使用 `LegacyPanel`。
4. `legacy/admin.js` 移除旧脚本 import。
5. `router.js` 移除对应 `meta.panel`。
6. `tools/e2e/sections/<page>.mjs` 注册并通过。
7. `npm run check` + `node tools/agent/verify-page.mjs --page <page>` 通过。
8. 全量 `web-e2e.mjs` 相关断言通过。
9. 更新 `CHANGELOG.md` / `docs/实现状态.md`。

## 并行规则

- 批次 1/2 可按页面并行；一个 Agent 只改自己的 `pages/<page>/*` 与 section。
- `router.js` / `navigation.js` / `AdminLayout.vue` / `legacy/admin.js` 只由集成 Agent 改。
- canvas / 富文本页面必须先写“保留内核”的边界说明，禁止重写算法。
- 每个批次结束统一构建 jar、测试服 E2E、提交；生产部署按 `samsara-release`。
