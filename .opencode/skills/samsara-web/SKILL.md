---
name: samsara-web
description: samsara 仓库 Web 管理控制台（Vue + 原生 JS）的开发约定与坑：新增页面接线、router-view/keep-alive、useApi/useAuth、Element Plus 样式、同源 iframe 安全头、地图瓦片/头像接口、ItemWorkbench 复用、E2E 断言写法。用于本仓库任何 Web 前端或 Web API 改动。
---

# samsara Web 前端约定

## 何时使用

- 改 `web-src/` 下的 Vue 页面/组件，或改 `mod/src/main/resources/web/` 下的原生 JS 页面。
- 新增/调整 Web 路由、二级导航、SPA 路径或 Web API。
- 编写或修复 `tools/web-e2e.mjs`。

## 固定位置

- 源码：`web-src/`；vite 直接输出到 `mod/src/main/resources/web/vue/`。
- 检查：`cd web-src && npm run check`（ESLint + vue-tsc + Vitest + build）。
- Agent 规范：根目录 `AGENTS.md`；单页面任务在 `docs/agent/tasks/`。
- 后端：`mod/src/main/java/com/shenqianye/samsara/web/`。
- 信息架构：`web-src/src/navigation.js`（工作区/二级/三级/旧链接/keep-alive 名单）。
- 壳层：`layouts/AdminLayout.vue`（router-view + 旧脚本桥）+ `AppHeader` / `WorkspaceRail` / `SectionNav` / `PageScrollbar`。
- 版本：Web 用 `WebVersion.java`，模组用 `mod/gradle.properties`，两者独立递增。

## 新增 Web 页面：两处必须一起改

1. `web-src/src/router.js`：import 组件、加路由项与 `meta`；需要三级导航时把子页加入 `navigation.js` 的 `SECTION_NAV`。
2. `mod/.../web/WebSecurity.java`：把 SPA 路径加入静态路径白名单。

不再需要改 `AdminLayout.vue` 的页面栈；内容区是 `<router-view>`。需要跨路由保留状态的页面，把组件名加入 `navigation.js` 的 `KEEP_ALIVE_PAGES`，并在页面里 `defineOptions({ name: 'XxxPage' })`。

判据：只做 1 → 直接 404；做 1+2 → 路由 meta 正确但内容区空白时，先查 `KEEP_ALIVE_PAGES` 的组件名和 `route.meta.page`，不要先怀疑 Vue 组件本身。

## 坑

- **确认弹窗跑到页面底部**：Element Plus 组件样式已按需注入，但 `ElMessage` / `ElMessageBox` 是 API 调用，必须在 `main.js` 显式 `import 'element-plus/theme-chalk/el-message.css'` 与 `el-message-box.css`。删掉后 `el-message-box` 会变成流内元素，看起来像"页面最底部多了一块"。
- **CSS 所有权**：Vue 壳层变量在 `web-src/src/tokens.css`；旧 `mod/.../web/style.css` 由 `main.js` 在构建期引入，供 `LegacyPanel` 使用，HTML 不再运行时引用 `/style.css`。改主题时两处变量一起改。
- **同源 iframe 读不到内容**：`LocalWebServer.securityHeaders` 需 `X-Frame-Options: SAMEORIGIN` 与 CSP `frame-ancestors 'self'`。用 `DENY`/`'none'` 时父页访问 `contentDocument` 抛 `SecurityError`，而 iframe 自身可能渲染正常，极易误判为前端 bug。
- **`<img>` 不能带 Bearer**：地图瓦片/头像必须走公开的 `/api/user/map/tile`、`/api/user/map/avatar`；管理员 tile 端点要鉴权，`<img>` 用不了。
- **空 `CustomModelData` 写 0**：`ItemEditor.buildPatch` 空字段过滤后不能留下 `floats: [0]`，否则每次保存都凭空给物品加组件。
- **交互逻辑只写一份**：容器、玩家背包、身份背包、基金仓库、Web 背包共用 `ItemWorkbench.vue`；新增仓库只传 `slots/items/layout/mode`，不要复制点击/拖拽/光标逻辑。
- **HTTP/token 只写一份**：统一用 `composables/useApi.js`；需要连接态的页面用 `composables/useAuth.js`。不要再复制 `fetch + Authorization + response.text()` 或 `samsara-token` 字符串。
- **keep-alive 页面**：E2E 断言仍建议写具体 `[data-page="x"]`；deactivated 页面的 DOM 不在 document 内，但不要把“当前页”假设成唯一匹配项。

## E2E 写法

- 浏览器/CDP/结果统计在 `tools/e2e/harness.mjs`；场景仍在 `tools/web-e2e.mjs`，分区说明见 `tools/e2e/README.md`。
- 页面级验证用 `node tools/e2e/run.mjs --section <page>`；新迁移页面在 `tools/e2e/sections/` 注册自己的断言。
- 新迁移旧页先跑 `node tools/agent/scan-legacy.mjs`，再用 `node tools/agent/scaffold-page.mjs` 生成骨架和任务 spec。
- 首次进入先真实点 `#token-input` / `#token-connect` 走完 UI，**成功之后**再 `Page.addScriptToEvaluateOnNewDocument` 注入 token。反例：一开始就注入会让 `token-dialog.open === false` 提前成立，后续连接断言全部偏掉。
- 有副作用的用例（建箱子、写容器、写玩家背包、Web 背包）必须自带还原步骤。
- 测试服配置与代码默认值不一致会让"恢复默认"类断言假失败，先核对 `server.properties`，再改代码。
- 旧命令式脚本会在脚本加载时缓存 `document.getElementById` 结果；从 `/vanilla/*` 直接切到世界栈/地图/公告时可能渲染空白。这是已知旧代码问题，不要误判成新的 Vue 路由 bug。

## 验收

- 前端改动后先 `npm run check`，再回 `mod/` 构建 jar；E2E 全绿才进入部署。
- 部署与三处哈希校验按 `samsara-release` 执行。

## 来源

- 2026-11-11 实践观察：本轮 Web 0.51.0 改动中新增页面 404/空白、弹窗样式缺失、iframe 安全头、`<img>` 鉴权、E2E token 注入时序、`v-show` 选择器歧义均实际发生。
