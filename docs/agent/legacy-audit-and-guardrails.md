# 旧代码与框架守卫

> 日期：2026-11-15  
> 目的：记录旧内核清零后的状态，以及阻止新代码绕开 Vue 框架的规则。

## 结论

**旧命令式内核已全部删除。** 没有 `web-src/src/legacy-core/`、`web-src/src/legacy/`、`useLegacyPage`、`LegacyPanel`、全局 `app.js` 壳，也没有 `window.Samsara*`。

| 区域 | 状态 |
|---|---|
| `/legacy.html`、`/legacy-user.html`、`/style.css`、旧 `/web/*.js` | ✅ 已删除，HTTP 404 |
| `mod/src/main/resources/web/*.js` | ✅ 已清零 |
| `web-src/src/legacy-core/` | ✅ 已删除 |
| `web-src/src/legacy/` | ✅ 已删除 |
| `useLegacyPage` / `LegacyPanel` | ✅ 已删除 |
| `window.Samsara*` | ✅ 已删除；仅保留框架弹窗 `window.FrameworkDialog` |
| 页面内核 | ✅ 全部在 `web-src/src/pages/<page>/`，随 Vite 打包 |
| `old-project/` | ⚠️ 15 个历史 Java 文件，属于旧 chunkmanager 参考，不参与构建 |

## 内核落点

算法没有重写，只是迁入页面目录并由 Vue 调用：

- 地图：`pages/map/kernel.js`（tile/canvas、玩家图层、区块操作）+ `pages/map/MapPanel.vue`
- 世界栈：`pages/world-stack/*`（图标、时间线、归档、导入队列、排序）
- 配置编辑器：`pages/config-editor/*`（PageSpec/FormSpec、预览/diff、写入前备份）
- 输出格式：`pages/output/OutputEditor.vue`（contenteditable、原子标签、撤销/重做）
- 高级配置/圈地：`pages/config/*`（曲线 canvas、圈地预览、旗帜几何）
- 身份组：`pages/identity/*`（设置、卡片、池子、详情、重绑、审计）
- 本体服务器：`pages/server/*`（配置/备份/RCON/审计）
- 经济业务：`pages/lottery/*`（基金、乐透、保险、价值表）
- 公开用户端：`pages/UserPage.vue` + `pages/user/UserValueTable.vue` + `MapPanel`

## 自动守卫

`tools/agent/check-framework.mjs` 接入 `npm run check`：

```text
npm run check
  → check:framework
  → lint
  → typecheck
  → test
  → build
```

守卫规则：

1. `web-src/src/legacy-core/`、`web-src/src/legacy/` 必须不存在。
2. 禁止 `LegacyPanel`、`useLegacyPage`。
3. 禁止 `import ... from 'legacy-core/'` 或 `src/legacy/`。
4. 禁止依赖 `window.Samsara*` 全局。
5. 页面/组件禁止直接 `fetch()`，必须用 `useApi`。
6. 禁止 `innerHTML` / `insertAdjacentHTML` / `document.write`。
7. 禁止引用 `/legacy.html`、`/style.css`、`/app.js` 等旧静态路径。
8. 禁止在 `mod/src/main/resources/web/` 新增 `.js` 文件。

没有页面白名单：任何重新引入旧内核的改动都会直接失败。

## 回归审计

`tools/agent/scan-legacy.mjs` 只做回归审计：

- 确认两个旧目录不存在。
- 扫描 `.vue` / `.js`，确认没有 `window.Samsara*`、`useLegacyPage` 和旧内核 import。
- 失败时列出文件与原因，可直接接入 CI。

## 远程门禁

`.github/workflows/web.yml`：

- push / pull_request 修改 `web-src/`、`tools/agent/`、`tools/e2e/` 或 Vue 构建产物时触发。
- 执行 `npm install` + `npm run check`。
- 守卫失败会直接红叉。

## 人工约束

`AGENTS.md` 与 `docs/agent/framework-contract.md` 已同步：

- 新功能必须写 `web-src/src/`。
- 复杂算法放 `pages/<page>/`，先抽纯逻辑并加单测。
- 连接/token 用 `useSession` / `useApi`；跨页导航用 `samsara:navigate`。
- 共享组件改动必须单独任务并跑全量 `npm run check`。
- 不恢复旧目录、全局桥或旧静态脚本。
