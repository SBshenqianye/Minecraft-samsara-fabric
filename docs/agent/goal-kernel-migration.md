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

### 阶段 5 完成记录（身份组，整页 Vue）

- 新增 `pages/identity/model.js`（UUID/账号类型/设置、池子过滤与相似 ID 推荐、统计分类、成就分组与树布局）与 `useIdentity.js`（加载/保存/重载、分配/清空/拖拽、UUID 解析、重绑、备份、审计、预览）。
- 新增组件：`IdentityGroupCard.vue`（卡片/头像/备注/三个 UUID 区/操作）、`IdentityChip.vue`、`IdentityPool.vue`（搜索/过滤/推荐/三类池子）、`IdentityDialogs.vue`（选择 UUID/放入组/重绑）、`IdentityDetailDialog.vue`（备份、状态、背包 iframe、统计分页、成就树）。
- `IdentityPage.vue` 不再 `useLegacyPage`、不再 import `legacy-core/identity.js`；detail 弹窗通过 `useFrameworkDialog` 打开，关闭行为与框架一致。
- 删除 `legacy-core/identity.js`（2449 行）与 `AdminOverlays.vue` 里的 uuid/assign/rebind 旧弹窗；`IdentityPage` 从守卫白名单移除。
- **保留边界**：背包编辑器仍是独立 iframe 路由（`/identity-inventory-inline`），不参与身份组内核。
- `identity` section **6/6**；全量 E2E **281/281**。
- 提交：`refactor(web): migrate identity page to Vue`。

### 阶段 6 完成记录（输出格式，外围）

- 新增 `pages/output/model.js`：预览片段颜色/粗斜/下划线样式、信息行、可点击提示（单测 3 项）。
- `OutputPage.vue` 原生渲染搜索框、自动续行开关（`SegmentedButtons`）、消息信息行、服务端预览片段；预览请求走 `useApi`。
- 内核 `output.js`：`__samsaraOutputVueShell` 下派发 `samsara:output-selected` / `samsara:output-auto` / `samsara:output-parts`，跳过自己的 meta/预览 DOM 写入，并暴露 `setAutoContinue()`。
- **剩余边界（保留内核）**：contenteditable 画布、原子标签/按钮、拖放/撤销重做、变量/按钮面板、保存/恢复默认仍在 `output.js`；`OutputPage` 仍在守卫白名单。
- `output` section **5/5**；全量 E2E **281/281**。
- 提交：`refactor(web): move output toolbar and preview to Vue`。

### 阶段 7 完成记录（地图控制层）

- `MapPage.vue` 原生渲染 10 个图层/网格复选框、定位/缩放/总览/重读按钮；复选框与按钮通过 `window.SamsaraMap.setOption()/fitView()/zoomIn()/zoomOut()/toggleOverview()` 调用内核。
- 内核 `map.js`：新增 `applyOption(name)` 统一状态更新与 `getOptions()`；`__samsaraMapVueControls` 下跳过自己的复选框/按钮监听，避免重复绑定；栈层/维度下拉、缩放标签、总览文本仍由内核按实时数据写入。
- **剩余边界（保留内核）**：canvas/tile 渲染、图层与标记、世界栈总览、区块操作预览、活动列表仍在 `map.js`；`MapPage` 仍在守卫白名单。
- 新增 `tools/e2e/sections/map.mjs`（5 条）；`map` section **5/5**；全量 E2E **281/281**。
- 提交：`refactor(web): move map controls to Vue`。

### 阶段 8 完成记录（高级配置/圈地，整页 Vue）

- 新增 `pages/config/useConfig.js`（模型/草稿/保存/重载/条件显示/状态）、`ConfigFileBlock.vue`、`ConfigEntry.vue`、`ConfigEntryControl.vue`（布尔/枚举/数值/敏感/文本/string-list/int-list/level-map/curve）、`StringListEditor.vue`、`IntListEditor.vue`、`CurveField.vue`、`ParticlePreview.vue`。
- 配置编辑内核拆分到 `pages/config/`：`curve-kernel.js`（保留曲线 canvas 拖拽与容量预估算法）、`claim-kernel.js`（染料色板/图案浮窗、旗帜合成器、图案方向编辑、圈地 canvas 预览与几何算法）、`banners.js`、`particles.js`、`button-group.js`；数据读写通过 `getValue/setValue` 注入。
- `ConfigPage.vue` / `ClaimRangePage.vue` 不再使用 `window.SamsaraUI` 的配置渲染函数：文件块、条目控件、条件显示、搜索、保存/重载、页内导航全部 Vue；`app.js` 的旧渲染器在这些页面通过 `__samsaraConfigVuePage` / `__samsaraClaimVuePage` 短路，不再写 DOM。
- **保留边界**：`curve-kernel.js` 的 canvas 坐标算法、`claim-kernel.js` 的 canvas 预览/图案几何、`banners.js` 的旗帜绘制为不可替代算法；页面主体与数据层在 Vue。
- `app-config` section **5/5**；全量 E2E **281/281**。
- 提交：`refactor(web): migrate config and claim range pages to Vue`。

## 迁移收尾状态

| 阶段 | 结果 | 遗留内核 |
|---|---|---|
| 3 server | `server.js` 删除，整页 Vue | 无 |
| 4 lottery | `lottery.js` 删除，整页 Vue | `value-table.js`（旧用户端） |
| 5 identity | `identity.js` 删除，整页 Vue（设置/卡片/池子/详情/重绑/审计） | 背包编辑器仍是独立 iframe |
| 6 output | 工具栏/信息/预览 Vue | contenteditable 画布、标签、撤销重做 |
| 7 map | 复选框/按钮 Vue | canvas/tile、图层、总览、区块操作 |
| 8 app | 配置/圈地整页 Vue | 曲线 canvas、圈地 canvas 预览/旗帜绘制算法 |

## 门禁

```powershell
cd web-src
npm.cmd run check
node ../tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section world-stack
node ../tools/web-e2e.mjs --base http://127.0.0.1:8722 --token <token>
```

- 全量 E2E 保持 281/281 起，新增断言可增加总数。
- 不部署生产，除非用户明确要求。
