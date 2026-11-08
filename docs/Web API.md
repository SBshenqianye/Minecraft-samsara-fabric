# samsara · Web API 接口文档

> 状态：v0.49.1（2026-11-08）
> 适用：本地管理端 `/`、用户端只读页 `/user`、以及未来接入的脚本/面板。
> 代码入口：`mod/src/main/java/com/shenqianye/samsara/web/LocalWebServer.java`
> 安全边界：`WebSecurity.java`、`WebUserService.java`

## 1. 通用约定

- 默认只监听 `127.0.0.1`，端口由 `config/samsara/web.yml` 的 `web.port` 决定（默认 `8720`，测试服为 `8722`）。
- 管理端 API 需要：
  ```http
  Authorization: Bearer <web.yml 的 token>
  ```
- 用户端 API **不需要 token**，但只提供只读公开数据；不会返回配置、文件路径、备份、权限、token、旗帜主人等管理字段。
- token 不接受 URL 查询参数，避免进入浏览器历史/日志。
- 请求体上限默认 `256 KiB`，超限返回 `413`；仅 `POST /api/world-stack/icon` 放宽到 `1 MiB`（base64 内嵌图片）。
- 所有响应带 `Cache-Control: no-store` 与 `X-Content-Type-Options: nosniff`。
- 错误响应统一为 JSON：
  ```json
  { "error": "说明", "fields": { "字段": "原因" } }
  ```
  `fields` 只在表单校验失败时出现。
- 方法不支持返回 `405`；路径不存在返回 `404`；管理端 token 缺失/错误返回 `401`。

## 2. 用户端只读 API（无需 token）

用户页：`GET /user`（静态 HTML）。页面只加载 `/style.css`、`/banners.js`、`/user.js`、`/map.js`，不会加载管理端 `app.js`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/user/status` | 服务器公开概况：在线人数、最大人数、版本、MOTD、tick 数 |
| GET | `/api/user/worlds` | 世界栈公开列表：栈层 id/顺序/是否栈顶/种子/在线人数/出生点/图标白名单；栈顶在前、`stack_0` 在最后 |
| GET | `/api/user/worlds/icon?stack=` | 栈层图标 PNG/JPEG/WebP 字节；无图返回 404，前端回退地图 |
| GET | `/api/user/map` | 白名单地图模型：栈层、维度、region、保护区块、旗帜、出生点；不含路径/缓存 |
| GET | `/api/user/map/players` | 在线玩家公开快照：混淆显示的名字（保留首字符、分隔符和末 3 位，其余 `*`，如 `xh_unpkire` → `x*_****ire`）、栈层、维度、坐标；不含 UUID/生命/朝向 |
| GET | `/api/user/map/live` | 已加载实体、在线玩家、短轨迹与最近方块操作；不含 UUID/生命/朝向 |
| GET | `/api/user/map/avatar?uuid=` / `?name=` | 本机代理 Mojang 官方皮肤 PNG；失败返回 404，前端回退本地 SVG |
| GET | `/api/user/map/tile` | 地形 PNG；参数同管理端地图 tile |

> 公网用户端的玩家名、操作者、发言者名做混淆显示：保留首字符、分隔符（如 `_`）和末 3 位，其余字符用 `*` 替换，长度不变（如 `xh_unpkire` → `x*_****ire`）。系统提示、聊天文本、实体自定义名中的已知玩家名也会替换。管理端 `/api/map/*`、`/api/server/*` 仍返回完整名字。

### 2.1 `GET /api/user/status`

响应示例：

```json
{
  "ok": true,
  "scope": "user",
  "generatedAt": 1760000000000,
  "online": 3,
  "maxPlayers": 20,
  "version": "26.3",
  "motd": "samsara",
  "tickCount": 123456
}
```

### 2.2 `GET /api/user/worlds`

```json
{
  "ok": true,
  "scope": "user",
  "topStackId": "world",
  "stacks": [
    { "id": "world", "index": 1, "top": true, "label": "world（栈顶）", "seed": 424243, "online": 2,
      "spawn": { "x": 160, "y": 65, "z": -96 },
      "icon": { "source": "map", "custom": false } },
    { "id": "stack_0", "index": 0, "top": false, "label": "stack_0", "seed": 1077061463, "online": 1,
      "spawn": { "x": 0, "y": 71, "z": 0 },
      "icon": { "source": "save", "custom": false, "mime": "image/png",
                "version": "1793629000000-4621", "url": "/api/user/worlds/icon?stack=stack_0&v=1793629000000-4621" } }
  ]
}
```

不同栈层的种子互相独立；用户端按栈层显示，不能用栈顶种子代替其他栈层。`icon.source` 为 `upload`（管理员上传）、`save`（存档 `icon.png`）或 `map`（前端按出生点 region 回退地图）；`url` 只在存在图片时出现，带 mtime/size 版本号避免旧缓存，不包含文件路径。

### 2.3 `GET /api/user/map`

与管理端 `/api/map` 结构相近，但经过白名单重建：

- 保留：`ok`、`generatedAt`、`claimRange`、`topStackId`、`stacks[]`
- 栈层保留：`id`、`index`、`top`、`label`、`createdAt`、`seed`、`spawn`、`levelType`、`dimensions`、`spawnHistory`
- 维度保留：`id`、`dim`、`name`、`regions`、`protectedChunks`、`banners`、`protectedCount`、`bannerCount`、`bounds`
- 旗帜保留：`x`、`y`、`z`、`facing`、`chunkX`、`chunkZ`、`patterns`
- 不返回：`worldRoot`、`cache`、`metaPath`、`spawnHistory.file`、旗帜 `owner`/`ownerUUID`
- 地图形状、圈地、种子与出生点属于用户可见信息；文件位置、缓存、主人身份不属于。

### 2.4 `GET /api/user/map/players`

```json
{
  "ok": true,
  "scope": "user",
  "generatedAt": 1760000000000,
  "players": [
    {
      "name": "Steve",
      "stackId": "world",
      "dimension": "overworld",
      "dimensionId": "minecraft:overworld",
      "x": 160.5,
      "y": 65.0,
      "z": -96.5
    }
  ]
}
```

不返回 `uuid`、`yaw`、`health`。前端地图每 250ms 轮询一次，并在独立玩家图层按帧插值。

### 2.5 `GET /api/user/worlds/icon?stack=`

返回该栈层当前生效的图标字节（PNG/JPEG/WebP），无需 token；优先管理员上传图，其次存档 `icon.png`，都没有时返回 `404`，前端再回退到出生点地图缩略图。响应带 `Cache-Control: no-store`，调用方应使用 `icon.url` 里的 `v=` 版本号。不返回文件路径、mtime 原值或上传元数据。

### 2.6 `GET /api/user/map/avatar?uuid=` / `?name=`

本机代理 Mojang 官方皮肤（完整 64×64 PNG）。`uuid` 优先；没有 UUID 时用玩家名查询 `api.mojang.com`，再读 `sessionserver.mojang.com` 的 textures 属性，只允许 `textures.minecraft.net`/`textures.mojang.com` 的 PNG。官方查不到（离线玩家/档案隐藏/网络失败）时按名字或 UUID 回退 `mc-heads.net`、`minotar.net`，仍只接受 PNG 魔数；全部失败返回 `404`，前端使用本地 SVG 首字母头。成功缓存 6 小时、失败缓存 2 分钟，不返回 UUID 解析结果或文件路径。

> 前端不再把整张皮肤缩成头像：`map.js` 统一从 `(8,8)` 取 8×8 头部正面并叠加 `(40,8)` 帽子层，只在 UI 上显示头部；接口本身仍返回完整皮肤，方便以后做披风/全身预览。

### 2.7 `GET /api/user/map/live`

```json
{
  "ok": true,
  "generatedAt": 1760000000000,
  "players": [],
  "entities": [
    {
      "kind": "entity", "name": "Pig", "type": "minecraft:pig", "category": "creature",
      "stackId": "world", "dimension": "overworld",
      "x": 0.5, "y": 100.0, "z": 0.5,
      "trail": [{ "x": 0.5, "z": 0.5, "t": 1760000000000 }]
    }
  ],
  "operations": [
    { "timeMs": 1760000000000, "actor": "Steve", "stackId": "world", "dimension": "overworld",
      "x": 1, "y": 64, "z": 2, "action": "place",
      "before": "minecraft:air", "after": "minecraft:stone" }
  ],
  "chat": [
    { "timeMs": 1760000000000, "name": "Steve", "text": "有人吗",
      "stackId": "world", "dimension": "overworld", "x": 1, "y": 64, "z": 2 }
  ],
  "system": [
    { "timeMs": 1760000000000, "text": "Steve was slain by Zombie" }
  ],
  "truncated": false
}
```

只枚举当前已加载区块中的实体；轨迹为进程内短轨迹，不写存档。实体带 `rarity`（0 玩家/命名/稀有，1 敌对，2 被动，3 其他）与可选 `customName`。`chat` 是最近 120 条玩家发言，`system` 是最近 80 条玩法层系统提示（加入/离开/死亡/进度，过滤命令反馈），都只保留内存；完整发言同时写入轨迹 `.evt`（`TYPE_CHAT = 0x09`）。用户端不返回 `uuid`、`health`、`yaw` 和操作者 UUID，玩家名与发言者名做部分脱敏；管理端仍返回完整名字。

### 2.8 `GET /api/user/map/tile`

参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `stack` | 是 | 栈层 id，如 `world` / `stack_0` |
| `dim` | 是 | `overworld` / `the_nether` / `the_end` |
| `rx` | 是 | region X |
| `rz` | 是 | region Z |
| `scale` | 是 | `1` / `2` / `4` / `8` |
| `shade` | 否 | `0` 关闭高度明暗；其他值开启 |

成功返回 `image/png`；未知栈层/维度/region 返回 `404`。

## 3. 管理端 API（需要 Bearer token）

### 3.1 配置与状态

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/config` | 读取 samsara 自有 YAML 的兼容模型（文件/分组/条目/值/默认值/说明） |
| GET | `/api/config/pages` | PageSpec/FormSpec 模型：samsara YAML、服务器本体 6 文件、第三方 YAML/JSON/TOML/properties；含 `source`/`format`/`revision`/`writable`/`requiresConfirmation`/`customWidget`/`values`；另有 `modNames`（从 `mods/*.jar` 的 `fabric.mod.json` 读取 modId → 显示名，供工作区导航） |
| POST | `/api/config/preview` | 预览配置变更；请求体 `{"file":"samsara:commands","values":{...}}`，返回字段级错误、变更清单、diff、revision |
| PUT | `/api/config` | 保存配置；旧格式 `{"file":"commands","values":{...}}`；PageSpec 页面使用 `{"file":"samsara:commands","source":"samsara","values":{...},"revision":"...","confirm":true}`（第三方/服务器配置必须 `confirm`） |
| POST | `/api/reload` | 热重载 samsara 配置（第三方/服务器页面刷新会重新读取文件） |
| GET | `/api/status` | 管理端运行状态、警告、配置路径等；返回 `version`（模组版本）与 `webVersion`（Web 独立版本） |
| GET | `/api/config/retention-estimate` | 轨迹保留容量预估；查询参数 `curve`（JSON 数组）、`keepAll` |
| GET | `/api/announcements` | 公告模型：列表、变量、枚举、发送历史 |
| PUT | `/api/announcements` | 保存公告列表；请求体 `{"announcements":[...]}`，字段级校验，写前 `.bak` |
| POST | `/api/announcements/preview` | 预览公告变量替换；`{announcement}` 或 `{id}`，可带 `player` |
| POST | `/api/announcements/send` | 发送公告；默认 `dryRun=true`，真正发送需 `dryRun=false` + `confirm=true` |
| POST | `/api/announcements/stop` | 停止指定 `id` 的 BossBar；省略 `id` 停止全部 |
| GET | `/api/agent/whoami` | 当前 token 的 actor、scope、限流 |
| GET | `/api/agent/tokens` | Agent token 列表（不返回明文） |
| POST | `/api/agent/tokens` | 创建 Agent token（仅管理员）；响应中的 token 只显示一次 |
| DELETE | `/api/agent/tokens` | 撤销 Agent token（仅管理员） |

**公告字段速览**：`trigger`（manual/interval/schedule/join）、`target`（all/permission/world/dimension/players）、`chat`/`actionbar`/`title`/`bossbar`/`sound`、`clickAction`（none/run/suggest/open_url/copy）、`clickValue`、`clickPermission`、`hoverText`、`bossbarColor`/`bossbarOverlay`/`durationSeconds`、`intervalSeconds`/`startAt`、`priority`。文本支持 `&` 颜色码、`&#RRGGBB`、`\n` 与变量 `{player}`/`{display}`/`{world}`/`{dimension}`/`{online}`/`{time}`/`{date}`/`{prefix}`。`run` 点击默认要求 OP，或用 `clickPermission` 指定权限路径。

### 3.2 身份组

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/identity` | 身份组模型、候选、映射、审计摘要 |
| PUT/POST | `/api/identity` | 保存身份组模型 |
| POST | `/api/identity/preview` | 预览绑定/重绑影响 |
| POST | `/api/identity/rebind` | 执行 data UUID 重绑 |
| POST | `/api/identity/reload` | 重载身份配置 |
| GET | `/api/identity/audit` | 身份审计记录 |
| POST | `/api/identity/generate` | 生成 data UUID |
| POST | `/api/identity/resolve` | 解析玩家名/UUID，返回正版账号（premium）与离线账号（offline）UUID 及类型 |
| GET | `/api/identity/data?uuid=<dataUuid>` | 只读查看 data UUID 的玩家数据：背包、末影箱、成就、统计、生命/饥饿/经验/坐标 |
| POST | `/api/identity/data/backup` | 备份 `{uuid}` 的 .dat / advancements / stats 到 `identity/backups/data/<uuid>/<时间戳>/` |

> 字段语义：`premium` = **正版账号 UUID**（Mojang），`offline` = **离线账号 UUID**（非正版）。两者都是账号类型，不是“当前在线/当前不在线”；只有 `online`/`states` 才表示连接状态。前端不得把账号类型简写成裸“正版/离线”。

> 保存身份组时（`PUT/POST /api/identity`）会自动把各组的 `premium`、`offline` UUID 补充到 `whitelist.json`，保留手动白名单条目，并立即调用 `reloadWhiteList()`；data UUID 不写入白名单。响应中的 `whitelist` 字段返回新增/更新数量。

### 3.3 输出格式

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/output` | 输出模板模型 |
| PUT/POST | `/api/output` | 保存输出模板 |
| POST | `/api/output/preview` | 渲染预览 |
| POST | `/api/output/reload` | 重载输出配置 |

### 3.4 权限树

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/permissions` | 权限树、默认值、owner 名单、警告 |
| PUT/POST | `/api/permissions` | 保存节点等级/disabled 与 owner 名单 |
| POST | `/api/permissions/reload` | 重载权限配置 |

### 3.5 地图

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/map` | 管理端完整地图模型（含 `worldRoot`、缓存、meta 路径、旗帜主人） |
| GET | `/api/map/players` | 管理端玩家快照（含 UUID/生命/朝向） |
| GET | `/api/map/live` | 实时实体/玩家轨迹与最近方块操作（管理端含 UUID/生命） |
| GET | `/api/map/tile` | 地形 PNG，参数同用户端 |

### 3.6 世界栈

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/world-stack` | 栈清单、文件位置树、备份/任务摘要 |
| GET | `/api/world-stack/timeline` | 操作时间线 |
| GET | `/api/world-stack/timeline/export?id=` | 导出时间线节点 |
| GET | `/api/world-stack/timeline/snapshots` | 快照列表 |
| GET | `/api/world-stack/jobs` | 任务列表 |
| GET | `/api/world-stack/jobs/{id}` | 单个任务详情 |
| POST | `/api/world-stack/import/preview` | 导入预览 |
| POST | `/api/world-stack/import` | 排队导入 |
| POST | `/api/world-stack/import/cancel` | 取消排队导入 |
| POST | `/api/world-stack/reorder` | 调整栈位顺序 |
| POST | `/api/world-stack/archive/preview` | 归档预览 |
| POST | `/api/world-stack/archive` | 排队归档 |
| POST | `/api/world-stack/icon` | 上传/替换栈层图片 `{id,mime,data}`，body 上限 1 MiB，只收 PNG/JPEG/WebP |
| POST | `/api/world-stack/icon/delete` | 删除管理员上传图，恢复存档图标/地图回退 |
| POST | `/api/world-stack/timeline/preview` | 时间线恢复预览 |
| POST | `/api/world-stack/timeline/restore` | 时间线恢复 |
| POST | `/api/world-stack/timeline/cleanup` | 清理时间线快照 |

### 3.7 Diff / 回溯 / 回看

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/diff/chunk?stack=&dim=&cx=&cz=&to=&load=` | 单区块 diff 摘要 |
| GET | `/api/diff/view/status` | 当前回看状态 |
| POST | `/api/diff/chunk/restore` | 单区块回溯（预览/确认/备份） |
| POST | `/api/diff/view` | 启动/停止只读回看 |

### 3.8 本体（服务器配置与备份）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/server` | 本体模型（配置、备份、任务） |
| GET | `/api/server/config` | 服务器配置文件模型 |
| POST | `/api/server/config` | 保存服务器配置（写前备份） |
| GET | `/api/server/players` | 在线玩家 / usercache / 白名单 / OP 候选 |
| GET | `/api/server/jobs` | 本体任务列表 |
| GET | `/api/server/backups` | 备份列表 |
| GET | `/api/server/backups/download?id=` | 下载备份 zip |
| POST | `/api/server/backups/preview` | 创建备份预览 |
| POST | `/api/server/backups` | 创建备份（排队到停服） |
| POST | `/api/server/backups/preview-restore` | 恢复预览 |
| POST | `/api/server/backups/restore` | 恢复备份（排队到停服） |
| POST | `/api/server/backups/delete` | 删除备份 |
| POST | `/api/server/console` | Web RCON：在服务器线程执行命令并回显 `{command}` |
| POST | `/api/server/console/complete` | Brigadier Tab 补全；`{command,cursor}` → `range` + `suggestions[{text,tooltip}]` |

### 3.9 Web 审计与回滚

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/audit` | 最近 100 条管理端写请求快照 |
| POST | `/api/audit/preview` | `{id}` 预览快照文件数/大小/是否可回滚 |
| POST | `/api/audit/rollback` | `{id,confirm}` 回滚配置类快照；回滚前自动保存当前状态 |

审计快照范围：`config/samsara/`、`server.properties`、`eula.txt`、`whitelist.json`、`ops.json`、`banned-players.json`、`banned-ips.json`、栈层图标与 `pending-*.json`。世界栈归档/导入/提升仍由世界栈时间线回滚，本体存档仍由本体备份回滚；RCON 任意命令只审计命令请求，不承诺世界回滚。请求需 Bearer token。

### 3.10 容器索引与编辑

容器索引记录所有 `Container` 方块实体（箱子、桶、潜影盒、熔炉等）的维度、坐标、类型、槽位数、非空槽位物品编码与 `revision`。已加载区块优先读实时容器；未加载区块读 `world/samsara/containers/` 的磁盘缓存。物品编码复用乐透的 `LotteryItemCodec`，完整保留附魔、名字、耐久与数据组件。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/containers?dimension=&x=&z=&radius=&type=` | 列出索引中的容器；返回位置、类型、槽位数、非空数、`revision`、`updatedAt`、`source`（`loaded`/`cache`）；最多 500 条 |
| GET | `/api/containers/read?dimension=&x=&y=&z=` | 读取完整槽位；已加载读实时容器，未加载读缓存；缓存不存在返回 `404` 与明确错误 |
| POST | `/api/containers/scan` | 手动刷新指定维度/坐标半径内**已加载**区块；body `{dimension?,x?,z?,radius?}`；3 秒限流，超限返回 `429` + `retryAfterMs` |
| POST | `/api/containers/write` | 写入容器槽位（P2）；body `{dimension,x,y,z,revision,slots:[...]}`；`revision` 冲突返回 `409`；写入前保存容器审计快照，写入后 `setChanged` 并广播方块更新 |
| POST | `/api/containers/item` | 容器页辅助：body `{encoded?,id?,count}`；传 `encoded` 时只改数量并保留组件，传 `id` 时新建物品；返回写入所需的 Base64(NBT) |
| GET | `/api/containers/offline?uuid=` | 只读返回离线玩家 `.dat` 中背包/末影箱的物品编码集合 |

响应示例：

```json
{
  "ok": true,
  "dimension": "minecraft:overworld",
  "radius": 16,
  "type": "",
  "total": 1,
  "containers": [
    { "dimension": "minecraft:overworld", "x": 8, "y": 100, "z": 8,
      "type": "minecraft:chest", "size": 27, "nonEmpty": 1, "empty": false,
      "revision": 3, "updatedAt": 1793986978755, "source": "loaded" }
  ],
  "indexSize": 4
}
```

`GET /api/containers/read` 的 `container.slots` 是与容器格数等长的字符串数组，空槽为 `""`，非空槽为 Base64(NBT)；调用方必须原样回传未修改的编码，不能只传 item id。写入时槽位数必须与容器格数一致，数量不能超过该物品的最大堆叠数。

> 容器写入只允许管理端 token（`ops` scope）；每次写入创建独立的容器审计记录（`container.json`，不可自动回滚）。扫描不会强制加载未加载区块；未加载区块只读索引缓存。`source=cache` 的条目在区块重新加载后会自动刷新。

### 3.11 玩家背包 / 末影箱

在线玩家在服务器线程直接读写内存 `Inventory`/`PlayerEnderChestContainer`；离线玩家只读 `playerdata/<uuid>.dat`，写入进入 `world/samsara/inventory-pending/<uuid>-<scope>.json` 安全队列，玩家 JOIN 后下一 tick 或服务器停服保存前应用。只编辑背包、盔甲、副手与末影箱，不编辑光标物品和合成网格。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/inventory?player=<uuid\|name>&scope=inventory\|ender\|armor` | 读取槽位与元数据；在线 `writable=true`，离线 `writable=false`、`queueWritable=true`；有待写入队列时 `pendingWrite=true` 并返回队列内容 |
| POST | `/api/inventory/write` | body `{player,scope,revision,slots:[...]}`；在线直接写入，离线入队；`revision` 冲突返回 `409` + `currentRevision`；写入前创建 `inventory.json` 审计快照 |
| POST | `/api/inventory/item` | 背包页辅助：body `{encoded?,id?,count}`，返回写入所需的 Base64(NBT) |

`scope=inventory` 为 41 槽：0–35 主背包、36–39 盔甲、40 副手；`ender` 为 27 槽；`armor` 为 4 槽（映射到 36–39）。空槽为 `""`，非空槽为 Base64(NBT)；调用方必须原样回传未修改的编码。

离线写入会校验玩家文件 `DataVersion` 与服务器 `SharedConstants.WORLD_VERSION`；不一致返回 `409` 并写审计。队列应用成功后删除队列文件；失败保留并记录原因。写入接口要求 `inventory` scope；读取要求 `read` scope；管理员 token 拥有全部权限。在线写入后调用 `setChanged`、`inventoryMenu.broadcastChanges()` 与 `containerMenu.broadcastChanges()`。

### 3.12 轨迹回看只读列表

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/track?days=7&limit=200&player=<可选>` | 列出最近可回看的玩家/时段，返回 `cycleId`、`firstMs/lastMs`、`samples`、`command`（游戏内 `/samsara track view ...`）；只读，不启动回放、不删除/改写轨迹 |

Diff 回看继续使用现有 `/api/diff/chunk`、`/api/diff/view`、`/api/diff/view/status`；Web 只做入口与状态展示，不重写算法。

## 4. 静态资源白名单

| 路径 | 资源 |
|---|---|
| `/`、`/index.html`、`/user`、`/containers` | Vue 应用入口（`/web/vue/index.html`） |
| `/vanilla/*` | 本体工作区路由回 Vue 入口（服务器配置/备份/RCON/审计） |
| `/samsara/*` | samsara 工作区路由回 Vue 入口（经济业务/天网/配置/世界/社区） |
| `/mods/<modId>/...` | 其他模组工作区路由回 Vue 入口；只允许安全字符并拒绝 `..` |
| `/vue/*` | Vite 构建产物（JS/CSS/图片），只允许固定字符集且拒绝 `..` |
| `/legacy.html` | 旧管理端页面壳（Vue 兼容层使用） |
| `/legacy-user.html`、`/user.html` | 旧用户端页面壳（Vue 兼容层使用） |
| `/style.css` | 两端共用样式 |
| `/map.js` | 两端共用的地图逻辑 |
| `/banners.js` | 两端共用旗帜贴图 |
| `/user.js` | 用户端适配层 |
| `/app.js`、`/structured.js`、`/form-kit.js`、`/particles.js`、`/output.js`、`/permissions.js`、`/world-stack.js`、`/server.js`、`/identity.js` | 旧管理端脚本；Vue 兼容层按需加载，保留回退路径 |

静态资源只按固定表返回，不拼接路径；不存在目录穿越。Vue 构建产物文件名带 hash，`/vue/*` 只接受 `[A-Za-z0-9_./-]` 且拒绝 `..`。

## 5. 安全边界与扩展约定

- **管理端 token 等同管理员密码**：拿到管理员 token 就能读写全部配置、备份、权限和世界栈；不要把它发给普通玩家，不要放进 URL、日志、截图或公开仓库。token 轮换后需要重启 Web 服务才会生效（运行中的服务持有启动时的 token）。
- **Agent token 按 scope 限权**：`/api/agent/tokens` 由管理员创建 read/announce/config/ops/rcon/inventory scope；服务端只存 SHA-256，明文只在创建时返回。token 管理接口本身仅管理员可用，Agent token 不能自我提权。写请求支持 `Idempotency-Key`，超限返回 `429` + `Retry-After: 60`。scoped token 限额为 read 300 / announce 60 / config 60 / ops 30 / rcon 20 / inventory 30 每分钟；管理员 token（本地控制台）为 read 1200 / announce 120 / config 120 / ops 60 / rcon 40 / inventory 60 每分钟，避免正常浏览触发 429。
- **管理端静态页壳可能公开，但不含数据**：`/`、`/app.js` 等静态资源本身不需要 token；没有 token 时所有管理 API（`/api/config`、`/api/status`、`/api/server`、`/api/world-stack`、`/api/permissions`、`/api/map` 等）返回 `401`。如果连页面壳都不希望公开，需要在反向代理层做路径过滤、加独立端口或额外登录页。
- **默认只公开用户端**：推荐反向代理只转发 `/user`、`/user.js`、`/style.css`、`/map.js`、`/banners.js`、`/api/user/*`。要公开管理端时，至少强制 HTTPS，并在代理层加限流/IP 白名单；当前 Mod 自身没有登录锁定、速率限制或二次验证。
- **公网名字混淆**：`WebUserService` 对玩家名、操作者、发言者做混淆显示（保留首字符、分隔符和末 3 位，其余 `*`，如 `xh_unpkire` → `x*_****ire`）；系统提示/聊天/自定义名中的已知玩家名也替换。完整名字只走管理端。不要把完整名字重新加回 `/api/user/*`。
- **浏览器安全头**：所有响应带 `Content-Security-Policy`（仅同源脚本）、`X-Frame-Options: DENY`、`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`、`Cache-Control: no-store`；前端不使用 `innerHTML`/内联脚本，CSP 不需要放开脚本，也不允许任何页面被 iframe 嵌入。Vue 3 使用 SFC 预编译，CSP 继续禁止 `unsafe-eval`；`/vue/*` 只返回构建产物。
- **token 不落日志**：启动日志只打印 token 前缀，完整值只在 `config/samsara/web.yml`；不要恢复完整 token 日志。
- **用户端无 token**：只注册 `/api/user/*`，不注册任何管理端 handler。用户端服务类 `WebUserService` 只依赖地图只读服务，不持有配置/身份/权限/备份服务。
- **不要直接把管理端 Map 透传**：新增用户端字段时必须显式白名单重建；尤其是 `worldRoot`、`metaPath`、`cache`、`ownerUUID`、`uuid`、`health`、`yaw`、备份路径、token。
- **用户端不提供 TP 信息**：公共 `/user` 地图只显示玩家位置与只读详情，不显示传送命令、不提供“复制 TP 命令”；`window.SamsaraUserView` 为真时 `map.js` 会跳过所有 TP 复制入口，管理端不受影响。
- **写接口只出现在管理端**：用户端只接受 `GET`，其他方法直接 `405`。栈层图标是公开只读字节，管理端页面与用户端都通过 `/api/user/worlds/icon` 显示；上传/删除仍要求 Bearer token，避免 `<img>` 无法附带 Authorization 头。头像同理通过公开只读 `/api/user/map/avatar` 代理 Mojang，URL 不带 token。
- **Web RCON 等同控制台权限**：`POST /api/server/console` 在服务器线程执行命令，权限为 `ALL_PERMISSIONS`，危险命令由前端二次确认；不要把管理端 token 暴露给普通玩家。RCON 密码不进入浏览器。
- **Web 审计不是世界备份**：它只快照 Web 可管理的小文件；世界栈/本体世界数据分别依赖各自时间线和备份，RCON 任意命令可能修改快照范围外的世界数据。
- **共享资源**：`style.css`、`map.js`、`banners.js` 两端共用；地图 API 基址通过 `window.SamsaraMapApiBase` 覆盖（管理端默认 `/api/map`，用户端 `/api/user/map`）。
- **反向代理**：如果要把用户端暴露到公网，只转发 `/user`、`/user.js`、`/api/user/*` 与所需静态资源；**不要**把 `/api/config`、`/api/server`、`/api/world-stack` 等管理路径一起转发。管理端 token 仍只应在本机使用。
- **新增接口时**：
  1. 在 `LocalWebServer` 注册 handler；
  2. 同步 `WebSecurity.staticResource` / `safeApiPath`；
  3. 更新本文件；
  4. 用户端接口必须经过 `WebUserService` 白名单；
  5. 加单测或 Web E2E，至少覆盖 401/404/方法限制与敏感字段。

## 6. 相关文档

- 《设计文档》〈Web 管理·地图标签页〉〈世界栈总览与在线玩家〉〈Web 本体管理〉
- 《设计规范》〈Web 地图只读〉〈Web 权限与 token〉
- 《实现状态》F-123~F-126、F-144~F-146、F-170、F-172~F-180

## 9. 死人乐透（v0.45.0）

### 9.1 管理端

需要 `Authorization: Bearer <token>`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/lottery` | 当前可下注日、正在统计日、实时死亡数、票、奖池、基金、历史、配置路径 |
| GET | `/api/lottery/values` | 物品价值表（分页 + 服务端搜索）：`q` 中文/英文/ID/拼音、`category` 分类、`sort`（`value-desc`/`value-asc`/`name`）、`offset`/`limit`（默认 100，最大 200）；返回全部注册物品（含 0 VP） |
| POST | `/api/lottery/draw` | 手动开奖（服务器线程执行，5 秒超时） |
| POST | `/api/lottery/cancel` | 取消当前统计日并退款 |
| | | `GET /api/lottery` 额外返回 `activePot`（当前统计轮下注 + 种子 + 滚存）、`bettingPot`（下注轮已入池）、`activePotItems` / `bettingPotItems`（物品卡片模型）、`rollover` / `rolloverItems`、`claims`（待领取：owner/reason/at/items）、`gold`（国际金价、金块/金锭/金粒估值）以及 `countingDay.ownPot/seedValue/seedItems`。`GET /api/user/lottery` 返回 `active`（待开奖轮，含 `seedValue`）与 `round`（可下注轮），各自带 `pot`、`tickets`、`items`，并返回公开的 `gold` 摘要。 |

响应示例（节选）：

```json
{
  "enabled": true,
  "mode": "game",
  "bettingDay": { "day": "game-67", "state": "BETTING", "tickets": 1, "pot": 32.0, "bets": [{ "name": "Bot", "guess": 3, "value": 32.0 }] },
  "countingDay": { "day": "game-66", "covered": true, "deathCount": 0 },
  "fund": { "value": 0.0, "items": [] },
  "history": [],
  "insurance": { "enabled": true, "policies": [] }
}
```

价值表响应示例：

```json
{
  "ok": true,
  "total": 1657,
  "offset": 0,
  "limit": 100,
  "values": [
    { "id": "minecraft:dragon_egg", "name": "Dragon Egg", "zh": "龙蛋", "py": "longdan", "initials": "ld", "syllables": ["long", "dan"], "icon": "dragon_egg", "value": 65536.0, "acceptable": true, "category": "其他" },
    { "id": "minecraft:diamond", "name": "Diamond", "zh": "钻石", "py": "zuanshi", "initials": "zs", "syllables": ["zuan", "shi"], "icon": "diamond", "value": 32.0, "acceptable": true, "category": "材料" },
    { "id": "minecraft:barrier", "name": "Barrier", "zh": "屏障", "value": 0.0, "acceptable": false, "category": "其他" }
  ]
}
```

返回全部注册物品（含 0 VP）；`zh` / `py` / `initials` / `syllables` 用于中文与拼音搜索（全拼、首字母、逐字前缀）。前端按 `offset`/`limit` 懒加载，滚动到底或点“加载更多”继续取下一页。

### 9.2 用户端（无需 token）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/user/lottery` | 奖池、票数、开奖/截止时间、上期死亡数与脱敏中奖名、基金实物列表、保险聚合计数；**不含实时死亡数、UUID、坐标、路径、保单主人** |
| GET | `/api/user/lottery/values` | 公开物品价值表（结构/分页/搜索参数同管理端，含中文名与拼音字段）；无玩家/保单信息 |

```json
{
  "ok": true,
  "scope": "user",
  "enabled": true,
  "round": { "day": "game-67", "drawTime": "第 67 游戏日 07:00", "cutoff": "第 67 游戏日 00:00", "pot": 32.0, "tickets": 1, "players": 1 },
  "last": null,
  "fund": { "value": 0.0, "count": 0, "items": [] },
  "insurance": { "enabled": true, "total": 0, "active": 0, "pending": 0, "claimed": 0 }
}
```
