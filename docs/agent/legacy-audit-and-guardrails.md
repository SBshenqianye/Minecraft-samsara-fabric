# 旧代码与框架守卫

> 日期：2026-11-14  
> 目的：说明“现在还剩什么旧代码”，以及以后如何阻止新代码绕开 Vue 框架。

## 结论

**不是“零旧代码”。** 旧静态页面壳和旧静态脚本已经清零，但复杂页面的命令式内核仍保留在 `web-src/src/legacy-core/`，这是 batch 5 明确的“Vue 壳 + 保留内核”方案。

| 区域 | 状态 |
|---|---|
| `/legacy.html`、`/legacy-user.html`、`/style.css`、旧 `/web/*.js` | ✅ 已删除，HTTP 404 |
| `LegacyPanel` | ✅ 已删除 |
| `mod/src/main/resources/web/*.js` | ✅ 已清零 |
| `web-src/src/legacy-core/` | ⚠️ 保留 16 个内核模块（约 1.44 万行）；阶段 1 已把世界栈卡片列表迁到 Vue，阶段 3 删除 `server.js`，阶段 4 删除 `lottery.js` |
| `web-src/src/legacy/` | ⚠️ 保留 3 个桥接文件：`admin.js`、`document.js`、`user.js` |
| 使用 `useLegacyPage` 的页面 | ⚠️ 6 个：config-editor、identity、map、mod-config、output、world-stack |
| `old-project/` | ⚠️ 15 个历史 Java 文件，属于旧 chunkmanager 参考，不参与构建 |

## 为什么保留内核

以下页面不是简单 CRUD，重写成本高且容易回归：

- 地图：tile/canvas 渲染、玩家图层、区块操作
- 世界栈：时间线、3D 纸盒人、图标上传
- 配置编辑器：PageSpec/FormSpec、预览/diff、写入前备份
- 输出格式：contenteditable 富文本、chips、撤销/重做
- 身份组：权限树、背包 iframe、UUID 重绑
- 本体服务器：配置/备份/RCON/审计四个面板（阶段 3 已拆成 Vue，只保留 `properties` 解析等纯逻辑在 `pages/server/model.js`）
- 经济业务：基金、乐透、保险（阶段 4 已迁 Vue；基金仓库继续复用 `ItemWorkbench`）
- 高级配置/圈地：`app.js` 的配置渲染与圈地预览

当前策略是“页面由 Vue 路由/组件挂载，内核按页动态加载”，不再有全局静态脚本和 HTML 回退。

## 自动守卫

新增 `tools/agent/check-framework.mjs`，并接入 `npm run check`：

```text
npm run check
  → check:framework
  → lint
  → typecheck
  → test
  → build
```

守卫规则：

1. 禁止重新引入 `LegacyPanel`。
2. 除白名单页面外，禁止 `import ... from 'legacy-core/'`。
3. `useLegacyPage` 只允许现有 8 个复杂页面使用。
4. 除框架桥接文件外，禁止从 `src/legacy/` 引入旧壳。
5. 新页面禁止依赖 `window.Samsara*` 全局内核。
6. 页面/组件禁止直接 `fetch()`，必须用 `useApi`。
7. 禁止 `innerHTML` / `insertAdjacentHTML` / `document.write`。
8. 禁止引用 `/legacy.html`、`/style.css`、`/app.js` 等旧静态路径。
9. 禁止在 `mod/src/main/resources/web/` 新增 `.js` 文件。

当前白名单只包含已经存在的 8 个复杂页面和 3 个桥接文件；新增页面默认不允许走旧内核。

## 远程门禁

新增 `.github/workflows/web.yml`：

- push / pull_request 修改 `web-src/`、`tools/agent/`、`tools/e2e/` 或 Vue 构建产物时触发。
- 执行 `npm install` + `npm run check`。
- 守卫失败会直接红叉。

## 人工约束

`AGENTS.md` 已同步：

- 新功能必须写 `web-src/src/`。
- 不要再新增 `/web/*.js` 静态脚本。
- 需要复用旧内核时，先抽 `pages/<page>/model.js` 并加单测。
- 共享组件改动必须单独任务并跑全量 `npm run check`。

## 后续真正“去旧”的路线

按收益排序：

1. `identity.js` 权限树/详情/UUID 重绑 → Vue（阶段 5；背包 iframe 最后处理）
2. `output.js` 外围工具栏/预览 → Vue（阶段 6；contenteditable 内核保留）
3. `map.js` 控制层/图层/标记 → Vue（阶段 7；canvas/tile 内核保留）
4. `app.js` 配置/圈地 → Vue（阶段 8；圈地 canvas 预览保留）
5. `world-stack.js` 时间线/排序/导入 → Vue（阶段 1/2 已迁卡片与对话框）

已完成：阶段 1、2a、2b（世界栈）、阶段 3（本体四面板，`server.js` 已删除）、阶段 4（经济业务，`lottery.js` 已删除）。

每拆一个，就从 `check-framework.mjs` 白名单移除对应页面，守卫会自动阻止回退。
