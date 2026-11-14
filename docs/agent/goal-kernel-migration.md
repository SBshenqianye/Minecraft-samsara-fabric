# Goal: 命令式内核渐进迁移

## 目标

按页面绞杀 `web-src/src/legacy-core/`，保留 canvas / contenteditable / 3D / PageSpec 算法，把控制层、渲染层和数据层迁到 Vue。

## 迁移顺序

| 阶段 | 页面 | 保留的内核 | 目标 |
|---|---|---|---|
| 1 | world-stack 卡片列表 | 图标回退/上传 | Vue 渲染卡片与图标，内核只保留动作/时间线 |
| 2 | world-stack 时间线/归档 | 无 | 对话框与预览 Vue 化 |
| 3 | server 四面板 | properties 解析 | 配置/备份/审计/任务拆 Vue |
| 4 | lottery | 基金仓库 ItemWorkbench | 概览/轮次/奖池/历史 Vue 化 |
| 5 | identity | 背包 iframe（最后） | 权限树/详情 Vue 化 |
| 6 | output | contenteditable | 外围工具栏/预览 Vue 化 |
| 7 | map | canvas/tile | 控制层/图层/标记 Vue 化 |
| 8 | app.js 配置/圈地 | 圈地 canvas 预览 | 表单/条件显示/搜索 Vue 化 |

每阶段完成后：

1. 从 `tools/agent/check-framework.mjs` 白名单移除对应 `legacy-core` 依赖（如果整页完成）。
2. 更新 `docs/agent/legacy-audit-and-guardrails.md`。
3. 跑 `npm run check` + 对应 section E2E + 全量 E2E。
4. 单独 commit。

## 阶段 1 详细

- 新增 `web-src/src/pages/world-stack/WorldStackCards.vue`、`WorldStackIcon.vue`。
- `WorldStackPage.vue` 用 Vue 渲染 `#world-stack-list`。
- 内核 `renderList()` 改为只派发 `samsara:world-stack-updated`，不再创建 DOM。
- 内核暴露 `getStacks()`、`previewArchive()`、`chooseIcon()`、`deleteIcon()`。
- 保留时间线、归档、导入、排序、任务面板在 kernel。

### 阶段 1 完成记录

- `world-stack.js` 从 1692 行降到 1608 行。
- 卡片/图标/上传/归档入口由 Vue 渲染；内核只保留动作与其余面板。
- `world-stack` section E2E **9/9**；全量 E2E **281/281**。
- 提交：`refactor(web): render world-stack cards in Vue`。

### 阶段 2a 完成记录（归档对话框）

- 新增 `WorldStackArchiveDialog.vue`，预览/确认/排队全部走 `useApi`。
- 从 `AdminOverlays.vue` 移除旧归档对话框。
- 内核不再被页面调用 `previewArchive`；归档动作由 Vue 接管。
- `world-stack` section E2E **9/9**。
- 提交：`refactor(web): move world-stack archive dialog to Vue`。

## 门禁

```powershell
cd web-src
npm.cmd run check
node ../tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section world-stack
node ../tools/web-e2e.mjs --base http://127.0.0.1:8722 --token <token>
```

- 全量 E2E 保持 281/281 起，新增断言可增加总数。
- 不部署生产，除非用户明确要求。
