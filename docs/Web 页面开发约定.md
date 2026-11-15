# Web 页面开发约定

> 适用于 `web-src/` 的 Vue 3 + Vue Router + Vite 应用。旧命令式页面、`LegacyPanel`、`useLegacyPage`、`legacy-core/` 已全部删除。

## 1. 生命周期契约

页面就是 Vue 组件，由 `router.js` 决定：

- 内容区固定是 `<router-view>`；需要保留状态时加入 `KEEP_ALIVE_PAGES`，并在页面里 `defineOptions({ name: 'XxxPage' })`。
- 数据加载放 `onMounted` / `onActivated`；轮询、监听、canvas 动画在 `onDeactivated` / `onBeforeUnmount` 停止。
- 连接建立后由 `composables/useSession.js` 广播 `samsara:connected`，页面监听后自行刷新，不依赖全局壳调用。
- 跨页跳转广播 `samsara:navigate`（`detail.path`），由 `AdminLayout` 统一处理，不直接操作 router 实例。

## 2. 页面结构

- 路由页面：`pages/XxxPage.vue` 一个文件。
- 复杂算法：`pages/<page>/model.js`（纯逻辑 + Vitest）+ `pages/<page>/*.vue`（展示）+ `pages/<page>/use<Page>.js`（状态与 API）。
- 共享组件：`components/`，例如 `TabShell.vue`、`ItemWorkbench.vue`、`FolderTree.vue`、`PageNav.vue`。
- 壳层：`layouts/AdminLayout.vue` / `AppHeader.vue` / `WorkspaceRail.vue` / `SectionNav.vue` / `AdminOverlays.vue`。

## 3. 导航规则

- 信息架构只改 `navigation.js`：工作区、二级分区、三级子页、`ROUTE_ALIASES`、`KEEP_ALIVE_PAGES`。
- 页内导航用 `composables/usePageNav.js` + `components/PageNav.vue`；页面挂载时 `setPageNav`，卸载时 `clearPageNav`。
- 导航标题、分组、目标必须属于当前页面；不要给其他页面建导航。

## 4. DOM 与安全

- 所有文本使用插值或 `textContent`，禁止 `innerHTML` / `insertAdjacentHTML` / `document.write`。
- 浮窗统一使用 `<dialog class="app-dialog">`，或 `useFrameworkDialog` 打开 `FrameworkDialogHost` 里的对话框。
- 页面/组件禁止直接 `fetch`；统一走 `useApi`（含 `authHeaders` / `loadBinary`）。
- 不在浏览器内编译模板；CSP 禁止 `unsafe-eval`。

## 5. 新增或重构页面

1. 先写 `pages/XxxPage.vue`，复杂逻辑抽到 `pages/<page>/model.js` 并补单测。
2. 在 `router.js` 加路由与 `meta`（`workspace`、`section`、`page`、`subpage`），需要三级导航时加入 `navigation.js` 的 `SECTION_NAV`。
3. 把 SPA 路径加到 `WebSecurity.staticResource`。
4. 加或更新 `tools/e2e/sections/<page>.mjs`。
5. 跑 `tools/agent/verify-page.mjs --page <page>` 与全量 `web-e2e.mjs`。
6. 更新 `CHANGELOG.md` / `docs/实现状态.md`。

## 6. Vue 页面细节

- 源目录 `web-src/`；构建 `cd web-src && npm.cmd run build`。Vite `base=/vue/`，输出 `mod/src/main/resources/web/vue/`；构建产物必须提交。
- 路由：`/vanilla/*`、`/samsara/economy|sky|config|world|community|system/*`、`/mods/<modId>/<file...>`；旧 `/config`、`/config-editor`、`/claim-range`、`/identity`、`/output`、`/permissions`、`/map`、`/announcements`、`/world-stack`、`/lottery`、`/containers` 保留 alias；`/server/*` 只做前端 redirect 到 `/vanilla/*`。
- samsara 常规配置唯一入口是 `/samsara/config/advanced`；旧 `/samsara/config/editor` 重定向到高级配置。PageSpec/FormSpec 只用于其他模组文件页（`/mods/<modId>/...`），`/vanilla/config` 由 `ServerConfigPanel` 管服务器本体配置。
- 横幅：全站唯一横幅是 `AdminLayout` 顶部 hero，统一显示面包屑、标题、说明；页面自己的刷新/查询/保存按钮留在内容区。
- HTTP 与 token：统一 `composables/useApi.js`（`request` 返回 `{ status, ok, data }`）与 `useSession.js`；不要再复制 token 输入框与 401 处理。
- Element Plus：`unplugin-vue-components` + `ElementPlusResolver` 按需引入；`ElMessage` / `ElMessageBox` 样式在 `main.js` 显式引入。
- 共享状态用 composable，不引入 Pinia / TypeScript 大迁移 / 第二套 UI 框架。
- 工程化检查：`npm.cmd run check` 依次执行框架守卫、ESLint、`vue-tsc --noEmit`、Vitest 与 Vite 构建。
- 构建拆包由 `vite.config.js` 负责；业务页面按路由懒加载，不要在 `AdminLayout` 里静态 import 页面。

## 7. 维护检查清单

- [ ] 页面由路由决定，未往 `AdminLayout` 加 `v-if`
- [ ] 切页后没有上一页导航/浮窗/定时器残留
- [ ] 没有直接 `fetch` / `innerHTML` / `window.Samsara*`
- [ ] 共享组件优先于复制粘贴
- [ ] 新增交互有 E2E 断言
- [ ] Web 版本号 `WebVersion.VALUE` 已递增
- [ ] `CHANGELOG.md` 和 `docs/实现状态.md` 已更新
