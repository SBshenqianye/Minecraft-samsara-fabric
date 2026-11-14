# Web 页面开发约定

> 适用于 `web-src/` 的 Vue 页面与 `mod/src/main/resources/web/` 下的旧管理端页面。目标是让页面可以逐步迁移、互不污染，减少长期维护成本。
>
> **v0.47.0 起**：入口由 `web-src/`（Vue 3 + Vue Router + Vite）构建到 `mod/src/main/resources/web/vue/`；`/`、`/user`、`/containers` 是同一应用。复杂页面仍可复用旧命令式脚本，由 Vue 组件 `onMounted/onUnmounted` 挂载，不需要一次性重写算法。
>
> **v0.48.0 起**：目录统一为 `layouts/`（`AdminLayout.vue`）、`pages/`（所有路由页面）、`components/`（`ItemSlotGrid.vue`、`FolderTree.vue`、`LegacyPanel.vue`、`TabShell.vue`），删除 `views/`。管理端为左侧顶级工作区 + 二级 Tab + 页内导航；Element Plus 按需引入（`unplugin-vue-components` + `ElementPlusResolver`），不全局导入完整样式。
>
> **v0.48.1 起**：管理端 header 由 `AdminLayout.vue` 自绘（品牌/版本/面包屑/连接/操作栏），旧 header 不再导入；`.top-tabs` / `#mods-tabs` / `#server-tabs` 仅保留隐藏 DOM 兼容旧脚本和 E2E。header 必须是 sticky 容器本身（不要再套等高层 wrapper）；二级 Tab 贴 header，`#global-nav` 在二级 Tab 下方。`/user` 使用独立的公开信息壳层。
>
> **v0.49.0 起**：所有页面使用 `AdminLayout` 顶部统一 hero 横幅（`PageHeader.vue` 已于后续版本删除）；samsara 二级分区使用 `navigation.js` 的 `SECTION_NAV` 渲染三级左侧子页导航；面包屑固定为“工作区 / 二级分区 / 当前子页”。`/server/*` 只做 redirect，规范路径是 `/vanilla/*`；配置编辑器用 `setSourceFilter` 按来源过滤。内部路由应用必须包在 `legacyNavGuard` 中，避免旧 `setActiveTab` 桥接二次导航。
>
> **v0.59.0 起（Agent vertical slice）**：公告页已迁为原生 Vue，作为第一个样板；旧页迁移按 `AGENTS.md` 与 `docs/agent/tasks/` 的单页契约执行，每个页面只改允许的文件，并用 `tools/e2e/run.mjs --section <page>` 验证。
>
> **v0.58.0 起（P0/P1/P2 收口）**：`AdminLayout` 改用 `<router-view>` + `keep-alive :include="KEEP_ALIVE_PAGES"`，删除手写页面栈；工作区/分区/子页/旧链接别名统一到 `navigation.js`；HTTP/token 统一到 `composables/useApi.js`，连接态统一到 `composables/useAuth.js`；删除不可达的 `InventoryPage.vue` 与未使用的 `PageHeader.vue`。壳层拆成 `AppHeader` / `WorkspaceRail` / `SectionNav` / `PageScrollbar`；设计变量迁到 `tokens.css`；Element Plus 组件按需 + API 样式显式引入；新增 ESLint、`vue-tsc`、Vitest 与 `npm run check`。

## 1. 生命周期契约

页面通过 `SamsaraUI.registerPage(name, definition)` 注册。`name` 必须与 `panel-<name>` 对应。

```js
UI.registerPage('example', {
  mount(ctx) {
    // 只在这里初始化本页 DOM/数据/事件
  },
  unmount(ctx) {
    // 可选：离开页面时额外清理
  },
  buildNav(ctx) {
    // 可选：构建本页左侧导航；不实现则保持侧栏隐藏
  },
  destroy(ctx) {
    // 可选：更重的清理（与 unmount 二选一即可）
  },
});
```

切换 tab 时 router 会按顺序执行：

1. 上一页 `unmount` / `destroy`
2. 释放上一页 `ctx.scope`
3. 清空 `#global-nav` 并隐藏侧栏
4. 关闭上一页遗留的 `<dialog>`
5. 隐藏旧 panel、显示新 panel
6. 新页面 `mount`
7. 新页面 `buildNav`（如果有）

未注册的旧页面继续走 `activate()`，因此不需要一次性迁移。

## 2. 页面作用域

`mount` 收到的 `ctx.scope` 用于管理本页资源：

```js
mount(ctx) {
  ctx.scope.setTimeout(() => { /* ... */ }, 1000);
  ctx.scope.setInterval(() => { /* ... */ }, 5000);
  ctx.scope.listen(window, 'resize', () => { /* ... */ });
  ctx.scope.listen(button, 'click', () => { /* ... */ });
  ctx.scope.cleanup(() => { /* 自定义清理 */ });
}
```

页面卸载时这些定时器和监听会自动清理。新代码优先使用 `ctx.scope`，不要再直接 `setInterval` 或把监听挂到 `window` 上。

## 3. 导航规则

- 页面需要左侧导航时，在 `buildNav(ctx)` 中调用 `UI.buildNav(...)`
- 页面不需要导航（如公告页）时不要实现 `buildNav`，router 会保持侧栏隐藏
- 不要直接操作 `#global-nav`，也不要给其他页面建导航
- 导航标题、分组、目标必须属于当前页面

## 4. DOM 与安全

- 所有文本使用 `textContent`，禁止 `innerHTML`
- 动态节点优先用 `form-kit.js` / `item-ui.js` / `value-table.js` 的公共组件
- 浮窗使用 `<dialog class="app-dialog">`，切页时 router 会自动关闭
- 不要在页面外留下全局 DOM 或全局事件监听

## 5. 迁移步骤

1. 在页面模块底部注册 `UI.registerPage('<name>', { mount, unmount, buildNav })`
2. 把原 `activate()` 的内容放进 `mount`
3. 把 `setInterval` / `window.addEventListener` 换成 `ctx.scope`
4. 如果原页面自己清空/重建导航，改为 `buildNav`
5. 运行 `node tools/web-e2e.mjs`，确认切页、导航、无脚本错误
6. 迁移完成后删除旧的 `window.SamsaraXxx.activate` 兼容分支（确认没有其他调用）

## 6. Vue 页面

- 源目录 `web-src/`；构建命令 `cd web-src && npm.cmd ci && npm.cmd run build`。
- Vite `base=/vue/`，输出 `mod/src/main/resources/web/vue/`；构建产物必须提交，保证 `gradle clean build` 不需要 npm。
- 结构：`navigation.js` 维护工作区 / 二级分区 / 三级子页 / 旧链接别名；`layouts/AdminLayout.vue` 只管共享 header、导航壳、路由视图与浮窗；`pages/*.vue` 每个路由页面一个文件；`components/TabShell.vue` 是可复用页面壳；`components/LegacyPanel.vue` 负责把旧 panel 挂到页面里；`components/ItemSlotGrid.vue` 是容器/背包共用槽位网格；`components/FolderTree.vue` 是其他模组递归文件夹树。
- 路由：`/vanilla/*`、`/samsara/economy|sky|config|world|community|system/*`、`/mods/<modId>/<file...>`；旧 `/config`、`/config-editor`、`/claim-range`、`/identity`、`/output`、`/permissions`、`/map`、`/announcements`、`/world-stack`、`/lottery`、`/containers` 保留 alias；`/server/*` 只做前端 redirect 到 `/vanilla/*`（审计例外：`/server/audit` 与 `/vanilla/audit` 都 redirect 到 `/samsara/system/audit`）。
- **AdminLayout 不再手写页面栈**：内容区是 `<router-view>`，页面选择只由 `router.js` 的 `component` 决定；需要跨路由保留状态的页面加入 `navigation.js` 的 `KEEP_ALIVE_PAGES`，并在页面里 `defineOptions({ name: 'XxxPage' })` 声明同名组件。
- 新增页面：先写 `pages/XxxPage.vue`，再在 `router.js` 加路由与 `meta`（`workspace`、`section`、`page`、`panel`、`subpage`），需要三级导航时加入 `navigation.js` 的 `SECTION_NAV`，最后把 SPA 路径加到 `WebSecurity.staticResource`。`meta.title` 必须与三级导航文案一致；不要再改 `AdminLayout` 的模板。
- 配置来源过滤：`/samsara/config/editor` 调 `setSourceFilter('samsara')`；其他模组页调 `setSourceFilter('third-party')`；`/vanilla/config` 仍由 `server.js` 管服务器本体配置。不要在同一页混显示不同 source。
- 横幅：全站唯一横幅是 `AdminLayout` 顶部 hero（`#mods-actions` / `#server-actions`），统一显示面包屑、标题、说明和全局操作；页面标题/说明由 AdminLayout 按路由写入。`PageHeader.vue` 已删除，内容区不要再造标题横幅；页面自己的刷新/查询/保存按钮留在内容区（`TabShell` 的 actions 或普通 toolbar）。
- HTTP 与 token：统一走 `composables/useApi.js`（`request` 返回 `{ status, ok, data }`，`json` 严格抛错；`getToken` / `setToken` / `clearToken` 统一管理 `samsara-token`）。需要连接态的页面用 `composables/useAuth.js`，不要再复制 token 输入框、`/api/status` 校验与 401 处理。
- Element Plus：Vite 用 `unplugin-vue-components` + `ElementPlusResolver` 按需引入组件；`main.js` 目前仍显式 `import 'element-plus/dist/index.css'`，因为 `ElMessageBox` / `ElMessage` 等 API 组件的样式不经过模板解析器。若后续改成 API 样式按需引入，必须同步更新本文件、`samsara-web` skill 与 E2E 的弹窗断言。
- 第三层页面内导航继续用旧 `#global-nav`（`buildNav`）；其他模组页可调用 `SamsaraConfigEditor.setNavEnabled(false)` 隐藏全局导航，改用自己的文件夹树。
- 不在浏览器内编译模板：必须用 SFC 预编译；CSP 继续禁止 `unsafe-eval`。
- Vue 页面文本使用插值/`textContent`，禁止 `innerHTML`；旧页面壳通过 DOM API `importNode/appendChild` 挂载。
- 切页必须清理：定时器、事件监听、地图动画、轮询、dialog；Vue 组件卸载时调用对应旧页面的停止/清理入口。常驻页面（`KEEP_ALIVE_PAGES`）用 `onActivated/onDeactivated` 处理轮询与动画的启停。
- 共享状态用 composables，不引入 Pinia/TypeScript/第二套 UI 框架。
- 工程化检查：`cd web-src && npm.cmd run check` 依次执行 ESLint、`vue-tsc --noEmit`、Vitest 与 Vite 构建。纯逻辑（搜索、缓存、写入模式、HTTP 层、导航表）优先补 `src/**/*.test.js`，不要把全部回归都压在 `tools/web-e2e.mjs`。
- 构建拆包：`vite.config.js` 把 `vue` / `element-plus` / 其他 `node_modules` 分到独立 vendor chunk；业务页面继续按路由懒加载，不要重新在 `AdminLayout` 里静态 import 页面。

## 7. 维护检查清单

- [ ] 新增页面已注册生命周期
- [ ] 切页后没有上一页导航/浮窗/定时器残留
- [ ] 没有直接使用 `innerHTML`
- [ ] 共享组件优先于复制粘贴
- [ ] 新增交互有 E2E 断言
- [ ] Web 版本号 `WebVersion.VALUE` 已递增
- [ ] `CHANGELOG.md` 和 `docs/实现状态.md` 已更新
