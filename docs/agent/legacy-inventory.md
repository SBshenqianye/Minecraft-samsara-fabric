# Legacy Web 迁移清单

> 由 `node tools/agent/scan-legacy.mjs` 生成，提交前重新生成。
> 扫描时间：2026-11-14T12:55:38.599Z

## 总览

| 文件 | 状态 | KB | 页面 | 路由 | DOM 读取 | DOM id | 全局 | API | innerHTML | 风险分 | 建议 |
|---|---|---:|---|---|---:|---:|---:|---:|---:|---:|---|
| `server.js` | 🧩 shell-migrated | 85.5 | 本体服务器 | /vanilla/* | 78 | 47 | 7 | 14 | 0 | 279 | 建议拆成 4 个页面 |
| `app.js` | 📦 bundled | 140.7 | config / claim-range / 全局壳 | /samsara/config/* | 32 | 20 | 17 | 4 | 1 | 269 | 核心旧壳，已作为 Vite 内核打包，不再走静态脚本 |
| `map.js` | 🧩 shell-migrated | 112.7 | 地图 | /samsara/world/map | 35 | 35 | 6 | 5 | 0 | 236 | canvas/瓦片，保留渲染内核 |
| `world-stack.js` | 🧩 shell-migrated | 68.5 | 世界栈 | /samsara/world/stack | 34 | 34 | 3 | 15 | 0 | 180 | 时间线 + 3D/图标 |
| `identity.js` | 🧩 shell-migrated | 91.4 | 身份组 | /samsara/community/identity | 4 | 3 | 5 | 13 | 0 | 116 | 列表 + 详情 + 背包 iframe |
| `lottery.js` | 🧩 shell-migrated | 22 | 经济业务 | /samsara/economy | 23 | 21 | 3 | 4 | 1 | 101 | 金额/轮次/下注 |
| `config-editor.js` | 🧩 shell-migrated | 19.2 | 配置编辑 | /samsara/config/editor | 19 | 12 | 4 | 4 | 0 | 94 | PageSpec/FormSpec，最高复杂度 |
| `user.js` | 📦 bundled | 10.9 | 公开用户端内核 | /user | 7 | 6 | 6 | 5 | 0 | 48 | 公开只读 API 适配，已由 UserPage.vue 挂载 |
| `output.js` | 🧩 shell-migrated | 28.1 | 输出格式 | /samsara/config/output | 0 | 0 | 3 | 4 | 0 | 37 | contenteditable 富文本编辑器，中等复杂度 |
| `banners.js` | 📦 bundled | 16.9 | 共享横幅/粒子 | — | 0 | 0 | 1 | 0 | 0 | 20 | 视觉资源 |
| `particles.js` | 📦 bundled | 13.2 | 共享粒子背景 | — | 0 | 0 | 1 | 0 | 0 | 16 | 视觉资源 |
| `chat-text.js` | 📦 bundled | 9.3 | 共享富文本 | — | 0 | 0 | 1 | 0 | 0 | 12 | 共享组件，先抽公用 |
| `form-kit.js` | 📦 bundled | 8.6 | 共享表单工具 | — | 0 | 0 | 1 | 0 | 0 | 12 | 共享组件 |
| `value-table.js` | 📦 bundled | 5.4 | 共享价值表 | — | 0 | 0 | 2 | 0 | 0 | 11 | 可复用 |
| `structured.js` | 📦 bundled | 4.6 | 共享结构化表单 | — | 0 | 0 | 1 | 0 | 0 | 8 | 共享组件 |
| `item-ui.js` | 📦 bundled | 3.8 | 共享物品 UI | — | 0 | 0 | 1 | 0 | 0 | 7 | 已有 Vue ItemIcon/ItemTooltip |
| `page-kit.js` | 📦 bundled | 4.2 | 旧页面生命周期 | — | 0 | 0 | 1 | 0 | 0 | 7 | 已随 Vite 内核打包，页面走 Vue 生命周期 |

## 状态汇总

- 🧩 shell-migrated：`server.js`、`map.js`、`world-stack.js`、`identity.js`、`lottery.js`、`config-editor.js`、`output.js`
- 📦 bundled：`app.js`、`user.js`、`banners.js`、`particles.js`、`chat-text.js`、`form-kit.js`、`value-table.js`、`structured.js`、`item-ui.js`、`page-kit.js`

## 按迁移批次

### 第一批（低风险表单/只读）

- `identity.js` → /samsara/community/identity
- `output.js` → /samsara/config/output

### 第二批（经济 / RCON）

- `server.js` → /vanilla/*
- `lottery.js` → /samsara/economy

### 第三批（配置编辑器）

- `config-editor.js` → /samsara/config/editor

### 第四批（canvas / 3D）

- `app.js` → /samsara/config/*
- `map.js` → /samsara/world/map
- `world-stack.js` → /samsara/world/stack

## 明细

### `server.js`

- 状态：🧩 shell-migrated
- 页面：本体服务器（/vanilla/*）
- 大小：85.5 KB；DOM 读取：78；事件监听：29；innerHTML：0
- 建议：建议拆成 4 个页面
- panel：`panel-server`、`server-action-hint`、`server-action-title`、`server-actions`、`server-audit-changes`、`server-audit-diff`、`server-audit-diff-close`、`server-audit-diff-title`、`server-audit-diff-wrap`、`server-audit-diffs`、`server-audit-list`、`server-audit-message`、`server-audit-panel`、`server-audit-refresh`、`server-backup-count`、`server-backup-create`、`server-backup-message`、`server-backup-panel`、`server-backup-pending`、`server-backup-preview`、`server-backup-stats`、`server-backup-timeline`、`server-config-count`、`server-config-files`、`server-config-message`、`server-config-panel`、`server-config-search`、`server-console-clear`、`server-console-command`、`server-console-complete`、`server-console-copy`、`server-console-filter`、`server-console-message`、`server-console-output`、`server-console-panel`、`server-console-quick`、`server-console-send`、`server-job-list`、`server-reload`、`server-save`、`server-tabs`
- 全局：`SamsaraChatText`、`SamsaraNativeConsole`、`SamsaraServer`、`SamsaraUI`、`SamsaraUIKit`、`SamsaraUseRouter`、`SamsaraWorldStack`
- API：`/api/audit`、`/api/audit/preview`、`/api/audit/rollback`、`/api/server`、`/api/server/backups`、`/api/server/backups/delete`、`/api/server/backups/download`、`/api/server/backups/preview`、`/api/server/backups/preview-restore`、`/api/server/backups/restore`、`/api/server/config`、`/api/server/console`、`/api/server/console/complete`、`/api/server/players`
- DOM id（47）：`audit-diff-`、`panel-server`、`player-cancel`、`player-dialog`、`player-dialog-title`、`player-list`、`player-search`、`server-action-hint`、`server-action-title`、`server-actions`、`server-audit-changes`、`server-audit-diff`、`server-audit-diff-close`、`server-audit-diff-title`、`server-audit-diff-wrap`、`server-audit-diffs`、`server-audit-list`、`server-audit-message`、`server-audit-panel`、`server-audit-refresh`、`server-backup-count`、`server-backup-create`、`server-backup-message`、`server-backup-panel`、`server-backup-pending`、`server-backup-preview`、`server-backup-stats`、`server-backup-timeline`、`server-config-count`、`server-config-files` …

### `app.js`

- 状态：📦 bundled
- 页面：config / claim-range / 全局壳（/samsara/config/*）
- 大小：140.7 KB；DOM 读取：32；事件监听：74；innerHTML：1
- 建议：核心旧壳，已作为 Vite 内核打包，不再走静态脚本
- panel：`panel-`
- 全局：`SamsaraAnnouncements`、`SamsaraBanners`、`SamsaraConfigEditor`、`SamsaraDialog`、`SamsaraIdentity`、`SamsaraMap`、`SamsaraOutput`、`SamsaraPageKit`、`SamsaraParticles`、`SamsaraPermissions`、`SamsaraServer`、`SamsaraStructured`、`SamsaraTabActions`、`SamsaraUI`、`SamsaraUIKit`、`SamsaraUseRouter`、`SamsaraWorldStack`
- API：`/api/config`、`/api/config/retention-estimate`、`/api/reload`、`/api/status`
- DOM id（20）：`action-default`、`action-hint`、`action-title`、`app-skeleton`、`app-version`、`back-to-top`、`claim-range-files`、`connection-state`、`files`、`global-nav`、`panel-`、`save`、`search`、`status-chips`、`status-warning`、`summary`、`token-connect`、`token-dialog`、`token-error`、`token-input`

### `map.js`

- 状态：🧩 shell-migrated
- 页面：地图（/samsara/world/map）
- 大小：112.7 KB；DOM 读取：35；事件监听：40；innerHTML：0
- 建议：canvas/瓦片，保留渲染内核
- panel：`panel-map`
- 全局：`SamsaraBanners`、`SamsaraMap`、`SamsaraMapApiBase`、`SamsaraStackIcon`、`SamsaraUI`、`SamsaraUserView`
- API：`/api/diff/chunk/restore`、`/api/diff/view`、`/api/map`、`/api/user/map/avatar`、`/api/user/map/tile`
- DOM id（35）：`map-activity`、`map-canvas`、`map-chunk-grid-legend`、`map-chunk-mode`、`map-dim-anchors`、`map-dimension`、`map-fit`、`map-live`、`map-live-status`、`map-message`、`map-overview-stacks`、`map-overview-status`、`map-overview-toggle`、`map-players`、`map-reload`、`map-selection`、`map-shading`、`map-show-chunks`、`map-show-claims`、`map-show-entities`、`map-show-players`、`map-show-rare-names`、`map-show-separation`、`map-show-spawns`、`map-show-trails`、`map-stack`、`map-stack-overview`、`map-stack-players-`、`map-stage-anchor`、`map-stats` …

### `world-stack.js`

- 状态：🧩 shell-migrated
- 页面：世界栈（/samsara/world/stack）
- 大小：68.5 KB；DOM 读取：34；事件监听：34；innerHTML：0
- 建议：时间线 + 3D/图标
- panel：`panel-world-stack`
- 全局：`SamsaraStackIcon`、`SamsaraUI`、`SamsaraWorldStack`
- API：`/api/world-stack`、`/api/world-stack/archive`、`/api/world-stack/archive/preview`、`/api/world-stack/icon`、`/api/world-stack/icon/delete`、`/api/world-stack/import`、`/api/world-stack/import/cancel`、`/api/world-stack/import/preview`、`/api/world-stack/reorder`、`/api/world-stack/timeline`、`/api/world-stack/timeline/cleanup`、`/api/world-stack/timeline/export`、`/api/world-stack/timeline/preview`、`/api/world-stack/timeline/restore`、`/api/world-stack/timeline/snapshots`
- DOM id（34）：`world-stack-after-wrap`、`world-stack-archive-dialog`、`world-stack-archive-error`、`world-stack-archive-preview`、`world-stack-files`、`world-stack-files-panel`、`world-stack-icon-input`、`world-stack-import-after`、`world-stack-import-error`、`world-stack-import-panel`、`world-stack-import-path`、`world-stack-import-position`、`world-stack-import-preview`、`world-stack-import-preview-button`、`world-stack-import-queue`、`world-stack-jobs`、`world-stack-jobs-panel`、`world-stack-layers`、`world-stack-list`、`world-stack-message`、`world-stack-order`、`world-stack-order-panel`、`world-stack-order-preview`、`world-stack-order-save`、`world-stack-overview`、`world-stack-refresh`、`world-stack-snapshot-refresh`、`world-stack-snapshots`、`world-stack-stats`、`world-stack-timeline` …

### `identity.js`

- 状态：🧩 shell-migrated
- 页面：身份组（/samsara/community/identity）
- 大小：91.4 KB；DOM 读取：4；事件监听：71；innerHTML：0
- 建议：列表 + 详情 + 背包 iframe
- 全局：`SamsaraDialog`、`SamsaraIdentity`、`SamsaraItem`、`SamsaraUI`、`SamsaraUIKit`
- API：`/api/identity`、`/api/identity/audit`、`/api/identity/data`、`/api/identity/data/backup`、`/api/identity/data/backups`、`/api/identity/data/label`、`/api/identity/data/list`、`/api/identity/generate`、`/api/identity/preview`、`/api/identity/rebind`、`/api/identity/reload`、`/api/identity/resolve`、`/api/user/map/head`
- DOM id（3）：`identity-data-content`、`identity-data-dialog`、`identity-group-`

### `lottery.js`

- 状态：🧩 shell-migrated
- 页面：经济业务（/samsara/economy）
- 大小：22 KB；DOM 读取：23；事件监听：6；innerHTML：1
- 建议：金额/轮次/下注
- panel：`panel-lottery`
- 全局：`SamsaraItem`、`SamsaraUI`、`SamsaraValueTable`
- API：`/api/lottery`、`/api/lottery/cancel`、`/api/lottery/draw`、`/api/lottery/values`
- DOM id（21）：`lottery-bets`、`lottery-cancel`、`lottery-claims`、`lottery-claims-count`、`lottery-connect`、`lottery-count`、`lottery-dashboard-body`、`lottery-dashboard-refresh`、`lottery-draw`、`lottery-history`、`lottery-insurance`、`lottery-insurance-summary`、`lottery-json`、`lottery-message`、`lottery-refresh`、`lottery-round`、`lottery-round-badge`、`lottery-stats`、`lottery-token`、`lottery-values`、`panel-lottery`

### `config-editor.js`

- 状态：🧩 shell-migrated
- 页面：配置编辑（/samsara/config/editor）
- 大小：19.2 KB；DOM 读取：19；事件监听：13；innerHTML：0
- 建议：PageSpec/FormSpec，最高复杂度
- 全局：`SamsaraChatText`、`SamsaraConfigEditor`、`SamsaraUI`、`SamsaraUIKit`
- API：`/api/config`、`/api/config/pages`、`/api/config/preview`、`/api/reload`
- DOM id（12）：`config-editor-body`、`config-editor-count`、`config-editor-message`、`config-editor-preview-cancel`、`config-editor-preview-confirm`、`config-editor-preview-dialog`、`config-editor-preview-diff`、`config-editor-preview-errors`、`config-editor-preview-sub`、`config-editor-search`、`config-editor-summary`、`global-nav`

### `user.js`

- 状态：📦 bundled
- 页面：公开用户端内核（/user）
- 大小：10.9 KB；DOM 读取：7；事件监听：1；innerHTML：0
- 建议：公开只读 API 适配，已由 UserPage.vue 挂载
- 全局：`SamsaraItem`、`SamsaraMapApiBase`、`SamsaraStackIcon`、`SamsaraUI`、`SamsaraUserView`、`SamsaraValueTable`
- API：`/api/user/lottery`、`/api/user/lottery/values`、`/api/user/map`、`/api/user/status`、`/api/user/worlds`
- DOM id（6）：`user-fund`、`user-lottery`、`user-message`、`user-status`、`user-values`、`user-worlds`

### `output.js`

- 状态：🧩 shell-migrated
- 页面：输出格式（/samsara/config/output）
- 大小：28.1 KB；DOM 读取：0；事件监听：16；innerHTML：0
- 建议：contenteditable 富文本编辑器，中等复杂度
- 全局：`SamsaraOutput`、`SamsaraUI`、`SamsaraUIKit`
- API：`/api/config`、`/api/output`、`/api/output/preview`、`/api/output/reload`

### `banners.js`

- 状态：📦 bundled
- 页面：共享横幅/粒子（—）
- 大小：16.9 KB；DOM 读取：0；事件监听：0；innerHTML：0
- 建议：视觉资源
- 全局：`SamsaraBanners`

### `particles.js`

- 状态：📦 bundled
- 页面：共享粒子背景（—）
- 大小：13.2 KB；DOM 读取：0；事件监听：2；innerHTML：0
- 建议：视觉资源
- 全局：`SamsaraParticles`

### `chat-text.js`

- 状态：📦 bundled
- 页面：共享富文本（—）
- 大小：9.3 KB；DOM 读取：0；事件监听：5；innerHTML：0
- 建议：共享组件，先抽公用
- 全局：`SamsaraChatText`

### `form-kit.js`

- 状态：📦 bundled
- 页面：共享表单工具（—）
- 大小：8.6 KB；DOM 读取：0；事件监听：4；innerHTML：0
- 建议：共享组件
- 全局：`SamsaraUIKit`

### `value-table.js`

- 状态：📦 bundled
- 页面：共享价值表（—）
- 大小：5.4 KB；DOM 读取：0；事件监听：5；innerHTML：0
- 建议：可复用
- 全局：`SamsaraItem`、`SamsaraValueTable`

### `structured.js`

- 状态：📦 bundled
- 页面：共享结构化表单（—）
- 大小：4.6 KB；DOM 读取：0；事件监听：4；innerHTML：0
- 建议：共享组件
- 全局：`SamsaraStructured`

### `item-ui.js`

- 状态：📦 bundled
- 页面：共享物品 UI（—）
- 大小：3.8 KB；DOM 读取：0；事件监听：5；innerHTML：0
- 建议：已有 Vue ItemIcon/ItemTooltip
- 全局：`SamsaraItem`

### `page-kit.js`

- 状态：📦 bundled
- 页面：旧页面生命周期（—）
- 大小：4.2 KB；DOM 读取：0；事件监听：2；innerHTML：0
- 建议：已随 Vite 内核打包，页面走 Vue 生命周期
- 全局：`SamsaraPageKit`

