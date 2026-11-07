# Web 页面开发约定

> 适用于 `web-src/` 的 Vue 页面与 `mod/src/main/resources/web/` 下的旧管理端页面。目标是让页面可以逐步迁移、互不污染，减少长期维护成本。
>
> **v0.47.0 起**：入口由 `web-src/`（Vue 3 + Vue Router + Vite）构建到 `mod/src/main/resources/web/vue/`；`/`、`/user`、`/containers` 是同一应用。复杂页面仍可复用旧命令式脚本，由 Vue 组件 `onMounted/onUnmounted` 挂载，不需要一次性重写算法。
>
> **v0.47.1 起**：管理端使用 `layouts/AdminLayout.vue` + `components/TabPage.vue` + `components/LegacyPanel.vue`；每个 tab 对应 `pages/*.vue`，不再使用单个 `AdminView` 整壳。复杂页的旧逻辑仍通过 `LegacyPanel` 挂载，但生命周期、路由和可见性由各自的 Vue page 管理。

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
- 结构：`layouts/AdminLayout.vue` 管共享 header/tab/操作栏/token 弹窗/浮窗；`pages/*.vue` 每个 tab 一个页面；`components/TabPage.vue` 是可复用页面壳；`components/LegacyPanel.vue` 负责把旧 panel 挂到页面里。
- 路由：`/config`、`/config-editor`、`/claim-range`、`/identity`、`/output`、`/permissions`、`/map`、`/announcements`、`/world-stack`、`/lottery`、`/server/config|backup|console|audit`；`/containers` 与 `/user` 是独立应用模式。
- 新增 tab 时：先加 `pages/XxxPage.vue`（`TabPage` + `LegacyPanel`），再在 `router.js` 加路由与 `meta.tab`，最后在 `AdminLayout` 的页面栈里加 `v-show` 和 tab 映射。
- 不在浏览器内编译模板：必须用 SFC 预编译；CSP 继续禁止 `unsafe-eval`。
- Vue 页面文本使用插值/`textContent`，禁止 `innerHTML`；旧页面壳通过 DOM API `importNode/appendChild` 挂载。
- 切页必须清理：定时器、事件监听、地图动画、WebSocket/轮询、dialog；Vue 组件卸载时调用对应旧页面的停止/清理入口。
- 共享状态用 composables，不引入 Pinia/TypeScript/UI 框架。

## 7. 维护检查清单

- [ ] 新增页面已注册生命周期
- [ ] 切页后没有上一页导航/浮窗/定时器残留
- [ ] 没有直接使用 `innerHTML`
- [ ] 共享组件优先于复制粘贴
- [ ] 新增交互有 E2E 断言
- [ ] Web 版本号 `WebVersion.VALUE` 已递增
- [ ] `CHANGELOG.md` 和 `docs/实现状态.md` 已更新
