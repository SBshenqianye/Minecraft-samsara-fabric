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
- 页面不再调用内核 `previewArchive`；归档动作由 Vue 接管。
- `world-stack` section E2E **9/9**。
- 提交：`refactor(web): move world-stack archive dialog to Vue`。

### 阶段 2b 完成记录（时间线/快照/恢复预览）

- 新增 `WorldStackTimelineDialog.vue`：节点、job、manifest、导出、撤销预览、确认、快照清理全部 Vue。
- 从 `AdminOverlays.vue` 移除旧时间线对话框。
- 内核 `bind()` 跳过时间线按钮/快照刷新监听。
- `world-stack` section 9/9；全量 E2E **281/281**。
- 提交：`refactor(web): move world-stack timeline dialog to Vue`。

### 阶段 3 完成记录（本体四面板）

- `pages/server/model.js` 承接全部纯逻辑：properties 解析/默认值/规格表、草稿脏值比较、JSON 名单默认条目、玩家与审计/备份展示数据、§ 颜色文本解析（含单测 17 项）。
- 新增 `ServerConfigPanel.vue`（分组/搜索/折叠/恢复默认/MOTD 色板/JSON 名单/玩家选择）、`ServerBackupPanel.vue`（统计/时间线/任务/文件位置/创建与恢复预览）、`ServerAuditPanel.vue`（列表/加载更多/文件级 diff/回滚）、`ChatTextEditor.vue`、`SegmentedButtons.vue`、`PlayerPickerDialog.vue`。
- 新增原生页内导航：`composables/usePageNav.js` + `components/PageNav.vue`（`#page-nav`），不再依赖旧 `buildNav`；`AdminLayout` 在原生导航出现时清掉 `#global-nav`。
- `app.js` 连接回调改为按路由判断本体页，避免把页面切回配置。
- 删除 `legacy-core/server.js`（2150 行），`ServerPage.vue` 不再使用 `useLegacyPage`；从 `check-framework.mjs` 白名单移除。
- 新增 `tools/e2e/sections/server.mjs`（15 条）；`server` section **15/15**；全量 E2E **281/281**。
- 提交：`refactor(web): migrate server panels to Vue`。

### 阶段 4 完成记录（经济业务）

- `pages/lottery/model.js` 增加统计行、轮次徽标/分区、待领取/保险/历史行、概览大屏比例、价值表行等纯函数（单测 8 项）。
- 新增 `LotteryPage` 原生页面 + `lottery/EconomyDashboard.vue`（基金/乐透/保险/历史走势四卡）与 `lottery/ValueTable.vue`（服务端分页 + 搜索/分类/排序 + 加载更多）。
- 概览、轮次/奖池、待领取、保险、历史全部 Vue 渲染；基金仓库继续复用 `PoolInventoryPage` + `ItemWorkbench`。
- 页内导航改用 `#page-nav`；`PageNav` / `AdminLayout` 在旧 `clearNav()` 之后恢复 `no-nav`，避免侧栏被隐藏。
- 删除 `legacy-core/lottery.js`；`LotteryPage` 从 `check-framework.mjs` 白名单移除（`value-table.js` 仍供旧用户端使用）。
- 新增 `tools/e2e/sections/lottery.mjs`（9 条）；`lottery` section **9/9**；全量 E2E **281/281**。
- 提交：`refactor(web): migrate lottery panels to Vue`。

### 阶段 5 完成记录（身份组，控制层）

- 新增 `pages/identity/model.js`：UUID 校验/短显示、账号类型、设置归一化、池子过滤（probe/占位名）、组卡片派生数据（单测 7 项）。
- `IdentityPage.vue` 原生渲染设置卡片（启用、同步、主人上限、配置/运行路径）与页内工具按钮；配置变化经 `window.SamsaraIdentity.setSettings()` 回写内核，保存仍复用内核流程。
- 内核 `identity.js`：`renderSettings()` 在 `__samsaraIdentityVueSettings` 下跳过，`applyModel()` 派发 `samsara:identity-model`，暴露 `setSettings()` / `getModel()`。
- **剩余边界（保留内核）**：组卡片拖拽、UUID/data 池子渲染与分配、data 详情弹窗（统计/成就/内嵌背包 iframe）、重绑确认、审计输出仍在 `identity.js`；`IdentityPage` 仍在守卫白名单。
- 新增 `tools/e2e/sections/identity.mjs`（6 条）；`identity` section **6/6**；全量 E2E **281/281**。
- 提交：`refactor(web): move identity settings to Vue`。

### 阶段 6 完成记录（输出格式，外围）

- 新增 `pages/output/model.js`：预览片段颜色/粗斜/下划线样式、信息行、可点击提示（单测 3 项）。
- `OutputPage.vue` 原生渲染搜索框、自动续行开关（`SegmentedButtons`）、消息信息行、服务端预览片段；预览请求走 `useApi`。
- 内核 `output.js`：`__samsaraOutputVueShell` 下派发 `samsara:output-selected` / `samsara:output-auto` / `samsara:output-parts`，跳过自己的 meta/预览 DOM 写入，并暴露 `setAutoContinue()`。
- **剩余边界（保留内核）**：contenteditable 画布、原子标签/按钮、拖放/撤销重做、变量/按钮面板、保存/恢复默认仍在 `output.js`；`OutputPage` 仍在守卫白名单。
- `output` section **5/5**；全量 E2E **281/281**。
- 提交：`refactor(web): move output toolbar and preview to Vue`。

## 门禁

```powershell
cd web-src
npm.cmd run check
node ../tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section world-stack
node ../tools/web-e2e.mjs --base http://127.0.0.1:8722 --token <token>
```

- 全量 E2E 保持 281/281 起，新增断言可增加总数。
- 不部署生产，除非用户明确要求。
