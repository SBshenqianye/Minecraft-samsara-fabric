# Goal: 命令式内核全部删除（已完成）

## 结果

`web-src/src/legacy-core/`、`web-src/src/legacy/`、`useLegacyPage`、`LegacyPanel`、`app.js` 全局壳、`window.Samsara*` 全部删除。所有页面由 Vue 渲染，canvas / contenteditable / 3D / PageSpec 算法保留并迁入 `web-src/src/pages/<page>/`。

| 项目 | 结果 |
|---|---|
| 旧内核目录 | 已删除；`tools/agent/check-framework.mjs` 禁止重新出现 |
| 全局桥 | 已删除；框架弹窗由 `composables/useFrameworkDialog.js` 模块状态驱动，不再有 `window.*` 入口 |
| 静态脚本 | `mod/src/main/resources/web/*.js` 已全部下线，只保留 SPA 资源 |
| 页面壳 | `AdminLayout.vue` 由路由驱动；连接/导航/页内导航均为 Vue composable/组件 |
| 公开用户端 | `UserPage.vue` + `pages/map/MapPanel.vue` + `pages/user/UserValueTable.vue` |
| E2E | section **105/105**，全量 **281/281** |

## 最终迁移阶段

| 阶段 | 页面 | 落点 | 删除的旧文件 |
|---|---|---|---|
| 1 | world-stack | `pages/world-stack/*` + `useWorldStack.js` | `legacy-core/world-stack.js`、`stack-icon.js` |
| 2 | config-editor / mod-config | `pages/config-editor/*`、`ConfigEditorPage.vue`、`ModConfigPage.vue` | `legacy-core/config-editor.js`、`chat-text.js` |
| 3 | output | `pages/output/OutputEditor.vue` + `useOutput.js` | `legacy-core/output.js` |
| 4 | map / 公开用户端 | `pages/map/kernel.js`、`MapPanel.vue`、`UserPage.vue`、`pages/user/UserValueTable.vue` | `legacy-core/map.js`、`banners.js`、`particles.js`、`item-ui.js`、`value-table.js`、`legacy/user.js`、`legacy-core/user.js` |
| 5 | app shell | `composables/useSession.js`、`AdminOverlays.vue`、`AdminLayout.vue`、`AppHeader.vue` | `legacy-core/app.js`、`page-kit.js`、`form-kit.js`、`structured.js`、`legacy/admin.js`、`legacy/document.js`、`useLegacyPage.js` |
| 6 | 守卫与文档 | `check-framework.mjs` 无白名单；`scan-legacy.mjs` 只做回归审计 | `scaffold-page.mjs`、旧 `legacy` 文档描述 |

## 保留的算法边界

- `pages/map/kernel.js`：tile/canvas 渲染、图层、世界栈总览、区块操作预览。
- `pages/output/OutputEditor.vue`：contenteditable 画布、原子标签/按钮、拖放、撤销重做。
- `pages/config/curve-kernel.js` / `claim-kernel.js`：曲线 canvas、圈地预览与旗帜几何。
- `pages/config-editor/*`：PageSpec / FormSpec 渲染与取值序列化。
- `pages/world-stack/*`：图标回退/上传、时间线、归档、导入队列。

## 门禁

```powershell
cd web-src
npm.cmd run check
node ../tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token>
node ../tools/web-e2e.mjs --base http://127.0.0.1:8722 --token <token>
```

- 全量 E2E 保持 281/281 起，新增断言可增加总数。
- 任何“重新引入 legacy”的改动都会让 `npm run check` 失败。
- 不部署生产，除非用户明确要求。
