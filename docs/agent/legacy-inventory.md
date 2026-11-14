# Legacy Web 迁移清单

> 由 `node tools/agent/scan-legacy.mjs` 生成，提交前重新生成。
> 扫描时间：2026-11-14T07:27:46.664Z

## 总览

| 文件 | KB | 页面 | 路由 | DOM id | 全局 | API | innerHTML | 风险分 | 建议 |
|---|---:|---|---|---:|---:|---:|---:|---:|---|
| `app.js` | 140.7 | config / claim-range / 全局壳 | /samsara/config/* | 1 | 17 | 4 | 1 | 199 | 核心旧壳，最后处理 |
| `map.js` | 112.7 | 地图 | /samsara/world/map | 2 | 6 | 5 | 0 | 135 | canvas/瓦片，保留渲染内核 |
| `identity.js` | 91.4 | 身份组 | /samsara/community/identity | 3 | 5 | 13 | 0 | 112 | 列表 + 详情 + 背包 iframe |
| `server.js` | 85.2 | 本体服务器 | /vanilla/* | 2 | 6 | 14 | 0 | 107 | 建议拆成 4 个页面 |
| `world-stack.js` | 68.5 | 世界栈 | /samsara/world/stack | 0 | 3 | 15 | 0 | 78 | 时间线 + 3D/图标 |
| `config-editor.js` | 19.2 | 配置编辑 | /samsara/config/editor | 0 | 4 | 4 | 0 | 51 | PageSpec/FormSpec，最高复杂度 |
| `output.js` | 28.1 | 输出格式 | /samsara/config/output | 0 | 3 | 4 | 0 | 37 | 表单型，低风险 |
| `lottery.js` | 22 | 经济业务 | /samsara/economy | 0 | 3 | 4 | 1 | 36 | 金额/轮次/下注 |
| `user.js` | 10.9 | 未分类 | — | 0 | 6 | 5 | 0 | 29 |  |
| `permissions.js` | 12.3 | 权限 | /samsara/config/permissions | 0 | 4 | 2 | 0 | 24 | 树形表单 |
| `announcements.js` | 15.3 | 公告 | /samsara/community/announcements | 0 | 2 | 3 | 0 | 21 | 首批迁移候选 |
| `banners.js` | 16.9 | 共享横幅/粒子 | — | 0 | 1 | 0 | 0 | 20 | 视觉资源 |
| `particles.js` | 13.2 | 共享粒子背景 | — | 0 | 1 | 0 | 0 | 16 | 视觉资源 |
| `chat-text.js` | 9.3 | 共享富文本 | — | 0 | 1 | 0 | 0 | 12 | 共享组件，先抽公用 |
| `form-kit.js` | 8.6 | 共享表单工具 | — | 0 | 1 | 0 | 0 | 12 | 共享组件 |
| `value-table.js` | 5.4 | 共享价值表 | — | 0 | 2 | 0 | 0 | 11 | 可复用 |
| `structured.js` | 4.6 | 共享结构化表单 | — | 0 | 1 | 0 | 0 | 8 | 共享组件 |
| `item-ui.js` | 3.8 | 共享物品 UI | — | 0 | 1 | 0 | 0 | 7 | 已有 Vue ItemIcon/ItemTooltip |
| `page-kit.js` | 4.2 | 旧页面生命周期 | — | 0 | 1 | 0 | 0 | 7 | 迁移完成后删除 |

## 按迁移批次

### 第一批（低风险表单/只读）

- `identity.js` → /samsara/community/identity
- `output.js` → /samsara/config/output
- `permissions.js` → /samsara/config/permissions
- `announcements.js` → /samsara/community/announcements

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

### `app.js`

- 页面：config / claim-range / 全局壳（/samsara/config/*）
- 大小：140.7 KB；事件监听：74；innerHTML：1
- 建议：核心旧壳，最后处理
- panel：`panel-`
- 全局：`SamsaraAnnouncements`、`SamsaraBanners`、`SamsaraConfigEditor`、`SamsaraDialog`、`SamsaraIdentity`、`SamsaraMap`、`SamsaraOutput`、`SamsaraPageKit`、`SamsaraParticles`、`SamsaraPermissions`、`SamsaraServer`、`SamsaraStructured`、`SamsaraTabActions`、`SamsaraUI`、`SamsaraUIKit`、`SamsaraUseRouter`、`SamsaraWorldStack`
- API：`/api/config`、`/api/config/retention-estimate`、`/api/reload`、`/api/status`
- DOM id（1）：`panel-`

### `map.js`

- 页面：地图（/samsara/world/map）
- 大小：112.7 KB；事件监听：40；innerHTML：0
- 建议：canvas/瓦片，保留渲染内核
- panel：`panel-map`
- 全局：`SamsaraBanners`、`SamsaraMap`、`SamsaraMapApiBase`、`SamsaraStackIcon`、`SamsaraUI`、`SamsaraUserView`
- API：`/api/diff/chunk/restore`、`/api/diff/view`、`/api/map`、`/api/user/map/avatar`、`/api/user/map/tile`
- DOM id（2）：`map-stack-players-`、`panel-map`

### `identity.js`

- 页面：身份组（/samsara/community/identity）
- 大小：91.4 KB；事件监听：71；innerHTML：0
- 建议：列表 + 详情 + 背包 iframe
- 全局：`SamsaraDialog`、`SamsaraIdentity`、`SamsaraItem`、`SamsaraUI`、`SamsaraUIKit`
- API：`/api/identity`、`/api/identity/audit`、`/api/identity/data`、`/api/identity/data/backup`、`/api/identity/data/backups`、`/api/identity/data/label`、`/api/identity/data/list`、`/api/identity/generate`、`/api/identity/preview`、`/api/identity/rebind`、`/api/identity/reload`、`/api/identity/resolve`、`/api/user/map/head`
- DOM id（3）：`identity-data-content`、`identity-data-dialog`、`identity-group-`

### `server.js`

- 页面：本体服务器（/vanilla/*）
- 大小：85.2 KB；事件监听：29；innerHTML：0
- 建议：建议拆成 4 个页面
- 全局：`SamsaraChatText`、`SamsaraServer`、`SamsaraUI`、`SamsaraUIKit`、`SamsaraUseRouter`、`SamsaraWorldStack`
- API：`/api/audit`、`/api/audit/preview`、`/api/audit/rollback`、`/api/server`、`/api/server/backups`、`/api/server/backups/delete`、`/api/server/backups/download`、`/api/server/backups/preview`、`/api/server/backups/preview-restore`、`/api/server/backups/restore`、`/api/server/config`、`/api/server/console`、`/api/server/console/complete`、`/api/server/players`
- DOM id（2）：`audit-diff-`、`server-audit-list`

### `world-stack.js`

- 页面：世界栈（/samsara/world/stack）
- 大小：68.5 KB；事件监听：34；innerHTML：0
- 建议：时间线 + 3D/图标
- 全局：`SamsaraStackIcon`、`SamsaraUI`、`SamsaraWorldStack`
- API：`/api/world-stack`、`/api/world-stack/archive`、`/api/world-stack/archive/preview`、`/api/world-stack/icon`、`/api/world-stack/icon/delete`、`/api/world-stack/import`、`/api/world-stack/import/cancel`、`/api/world-stack/import/preview`、`/api/world-stack/reorder`、`/api/world-stack/timeline`、`/api/world-stack/timeline/cleanup`、`/api/world-stack/timeline/export`、`/api/world-stack/timeline/preview`、`/api/world-stack/timeline/restore`、`/api/world-stack/timeline/snapshots`

### `config-editor.js`

- 页面：配置编辑（/samsara/config/editor）
- 大小：19.2 KB；事件监听：13；innerHTML：0
- 建议：PageSpec/FormSpec，最高复杂度
- 全局：`SamsaraChatText`、`SamsaraConfigEditor`、`SamsaraUI`、`SamsaraUIKit`
- API：`/api/config`、`/api/config/pages`、`/api/config/preview`、`/api/reload`

### `output.js`

- 页面：输出格式（/samsara/config/output）
- 大小：28.1 KB；事件监听：16；innerHTML：0
- 建议：表单型，低风险
- 全局：`SamsaraOutput`、`SamsaraUI`、`SamsaraUIKit`
- API：`/api/config`、`/api/output`、`/api/output/preview`、`/api/output/reload`

### `lottery.js`

- 页面：经济业务（/samsara/economy）
- 大小：22 KB；事件监听：6；innerHTML：1
- 建议：金额/轮次/下注
- 全局：`SamsaraItem`、`SamsaraUI`、`SamsaraValueTable`
- API：`/api/lottery`、`/api/lottery/cancel`、`/api/lottery/draw`、`/api/lottery/values`

### `user.js`

- 页面：未分类（—）
- 大小：10.9 KB；事件监听：1；innerHTML：0
- 全局：`SamsaraItem`、`SamsaraMapApiBase`、`SamsaraStackIcon`、`SamsaraUI`、`SamsaraUserView`、`SamsaraValueTable`
- API：`/api/user/lottery`、`/api/user/lottery/values`、`/api/user/map`、`/api/user/status`、`/api/user/worlds`

### `permissions.js`

- 页面：权限（/samsara/config/permissions）
- 大小：12.3 KB；事件监听：6；innerHTML：0
- 建议：树形表单
- 全局：`SamsaraPermissions`、`SamsaraStructured`、`SamsaraUI`、`SamsaraUIKit`
- API：`/api/permissions`、`/api/permissions/reload`

### `announcements.js`

- 页面：公告（/samsara/community/announcements）
- 大小：15.3 KB；事件监听：11；innerHTML：0
- 建议：首批迁移候选
- 全局：`SamsaraAnnouncements`、`SamsaraUI`
- registerPage：`announcements`
- API：`/api/announcements`、`/api/announcements/preview`、`/api/announcements/send`

### `banners.js`

- 页面：共享横幅/粒子（—）
- 大小：16.9 KB；事件监听：0；innerHTML：0
- 建议：视觉资源
- 全局：`SamsaraBanners`

### `particles.js`

- 页面：共享粒子背景（—）
- 大小：13.2 KB；事件监听：2；innerHTML：0
- 建议：视觉资源
- 全局：`SamsaraParticles`

### `chat-text.js`

- 页面：共享富文本（—）
- 大小：9.3 KB；事件监听：5；innerHTML：0
- 建议：共享组件，先抽公用
- 全局：`SamsaraChatText`

### `form-kit.js`

- 页面：共享表单工具（—）
- 大小：8.6 KB；事件监听：4；innerHTML：0
- 建议：共享组件
- 全局：`SamsaraUIKit`

### `value-table.js`

- 页面：共享价值表（—）
- 大小：5.4 KB；事件监听：5；innerHTML：0
- 建议：可复用
- 全局：`SamsaraItem`、`SamsaraValueTable`

### `structured.js`

- 页面：共享结构化表单（—）
- 大小：4.6 KB；事件监听：4；innerHTML：0
- 建议：共享组件
- 全局：`SamsaraStructured`

### `item-ui.js`

- 页面：共享物品 UI（—）
- 大小：3.8 KB；事件监听：5；innerHTML：0
- 建议：已有 Vue ItemIcon/ItemTooltip
- 全局：`SamsaraItem`

### `page-kit.js`

- 页面：旧页面生命周期（—）
- 大小：4.2 KB；事件监听：2；innerHTML：0
- 建议：迁移完成后删除
- 全局：`SamsaraPageKit`

