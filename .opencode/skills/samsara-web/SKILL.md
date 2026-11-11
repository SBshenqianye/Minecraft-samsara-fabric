---
name: samsara-web
description: samsara 仓库 Web 管理控制台（Vue + 原生 JS）的开发约定与坑：新增页面三处接线、Element Plus 弹层样式、同源 iframe 安全头、地图瓦片/头像接口、ItemWorkbench 复用、E2E 断言写法。用于本仓库任何 Web 前端或 Web API 改动。
---

# samsara Web 前端约定

## 何时使用

- 改 `web-src/` 下的 Vue 页面/组件，或改 `mod/src/main/resources/web/` 下的原生 JS 页面。
- 新增/调整 Web 路由、二级导航、SPA 路径或 Web API。
- 编写或修复 `tools/web-e2e.mjs`。

## 固定位置

- 源码：`web-src/`；vite 直接输出到 `mod/src/main/resources/web/vue/`。
- 构建：`cd web-src && npm run build`；后端一起构建见 `samsara-release`。
- 后端：`mod/src/main/java/com/shenqianye/samsara/web/`。
- 版本：Web 用 `WebVersion.java`，模组用 `mod/gradle.properties`，两者独立递增。

## 新增 Web 页面：三处必须一起改

1. `web-src/src/router.js`：import 组件、加路由项；需要三级导航时补 `SECTION_NAV`。
2. `web-src/src/layouts/AdminLayout.vue`：import 组件，并加 `page === 'xxx'` 条件渲染。
3. `mod/.../web/WebSecurity.java`：把 SPA 路径加入静态路径白名单。

判据：只做 1 → 直接 404；做 1+3 → 路由 meta 正确但内容区空白。本轮 `web-inventory`、`fund-inventory` 正好分别踩到这两种表现，先从这三处排查，不要怀疑 Vue 组件本身。

## 坑

- **确认弹窗跑到页面底部**：`main.js` 必须保留 `import 'element-plus/dist/index.css';`。删掉后 `el-message-box` 变成流内元素，看起来像"页面最底部多了一块"。
- **同源 iframe 读不到内容**：`LocalWebServer.securityHeaders` 需 `X-Frame-Options: SAMEORIGIN` 与 CSP `frame-ancestors 'self'`。用 `DENY`/`'none'` 时父页访问 `contentDocument` 抛 `SecurityError`，而 iframe 自身可能渲染正常，极易误判为前端 bug。
- **`<img>` 不能带 Bearer**：地图瓦片/头像必须走公开的 `/api/user/map/tile`、`/api/user/map/avatar`；管理员 tile 端点要鉴权，`<img>` 用不了。
- **空 `CustomModelData` 写 0**：`ItemEditor.buildPatch` 空字段过滤后不能留下 `floats: [0]`，否则每次保存都凭空给物品加组件。
- **交互逻辑只写一份**：容器、玩家背包、身份背包、基金仓库、Web 背包共用 `ItemWorkbench.vue`；新增仓库只传 `slots/items/layout/mode`，不要复制点击/拖拽/光标逻辑。
- **`v-show` 页面常驻 DOM**：`AdminLayout` 里部分页面用 `v-show`，E2E 的 `querySelector('[data-page]')` 会命中隐藏的 `LotteryPage`。断言要写具体 `[data-page="x"]`，或先判可见（`offsetParent !== null`）。

## E2E 写法

- 首次进入先真实点 `#token-input` / `#token-connect` 走完 UI，**成功之后**再 `Page.addScriptToEvaluateOnNewDocument` 注入 token。反例：一开始就注入会让 `token-dialog.open === false` 提前成立，后续连接断言全部偏掉。
- 有副作用的用例（建箱子、写容器、写玩家背包、Web 背包）必须自带还原步骤。
- 测试服配置与代码默认值不一致会让"恢复默认"类断言假失败，先核对 `server.properties`，再改代码。

## 验收

- 前端改动后先 `npm run build`，再回 `mod/` 构建 jar；E2E 全绿才进入部署。
- 部署与三处哈希校验按 `samsara-release` 执行。

## 来源

- 2026-11-11 实践观察：本轮 Web 0.51.0 改动中新增页面 404/空白、弹窗样式缺失、iframe 安全头、`<img>` 鉴权、E2E token 注入时序、`v-show` 选择器歧义均实际发生。
