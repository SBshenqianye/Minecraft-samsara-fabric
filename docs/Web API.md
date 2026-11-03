# samsara · Web API 接口文档

> 状态：v0.42.0（2026-11-04）
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
| GET | `/api/user/map/players` | 在线玩家公开快照：名字、栈层、维度、坐标；不含 UUID/生命/朝向 |
| GET | `/api/user/map/live` | 已加载实体、在线玩家、短轨迹与最近方块操作；不含 UUID/生命/朝向 |
| GET | `/api/user/map/avatar?uuid=` / `?name=` | 本机代理 Mojang 官方皮肤 PNG；失败返回 404，前端回退本地 SVG |
| GET | `/api/user/map/tile` | 地形 PNG；参数同管理端地图 tile |

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

本机代理 Mojang 官方皮肤（完整 64×64 PNG）。`uuid` 优先；没有 UUID 时用玩家名查询 `api.mojang.com`，再读 `sessionserver.mojang.com` 的 textures 属性，只允许 `textures.minecraft.net` 的 PNG。成功返回 `image/png`；无正版档案/网络失败返回 `404`，前端使用本地 SVG 首字母头。成功缓存 6 小时、失败缓存 2 分钟，不返回 UUID 解析结果或文件路径。

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
  "truncated": false
}
```

只枚举当前已加载区块中的实体；轨迹为进程内短轨迹，不写存档。`chat` 是最近 120 条玩家发言，只保留内存；完整发言同时写入轨迹 `.evt`（`TYPE_CHAT = 0x09`）。用户端不返回 `uuid`、`health`、`yaw` 和操作者 UUID。

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
| GET | `/api/config` | 读取全部配置模型（文件/分组/条目/值/默认值/说明） |
| PUT | `/api/config` | 保存配置；请求体 `{"file":"commands","values":{...}}` |
| POST | `/api/reload` | 热重载配置 |
| GET | `/api/status` | 管理端运行状态、警告、配置路径等 |
| GET | `/api/config/retention-estimate` | 轨迹保留容量预估；查询参数 `curve`（JSON 数组）、`keepAll` |

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
| POST | `/api/identity/resolve` | 解析玩家名/UUID |

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

### 3.9 Web 审计与回滚

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/audit` | 最近 100 条管理端写请求快照 |
| POST | `/api/audit/preview` | `{id}` 预览快照文件数/大小/是否可回滚 |
| POST | `/api/audit/rollback` | `{id,confirm}` 回滚配置类快照；回滚前自动保存当前状态 |

审计快照范围：`config/samsara/`、`server.properties`、`eula.txt`、`whitelist.json`、`ops.json`、`banned-players.json`、`banned-ips.json`、栈层图标与 `pending-*.json`。世界栈归档/导入/提升仍由世界栈时间线回滚，本体存档仍由本体备份回滚；RCON 任意命令只审计命令请求，不承诺世界回滚。请求需 Bearer token。

## 4. 静态资源白名单

| 路径 | 资源 |
|---|---|
| `/`、`/index.html` | 管理端页面 |
| `/user`、`/user.html` | 用户端只读页面 |
| `/style.css` | 两端共用样式 |
| `/map.js` | 两端共用的地图逻辑 |
| `/banners.js` | 两端共用旗帜贴图 |
| `/user.js` | 用户端适配层 |
| `/app.js`、`/structured.js`、`/form-kit.js`、`/particles.js`、`/output.js`、`/permissions.js`、`/world-stack.js`、`/server.js`、`/identity.js` | 管理端专用 |

静态资源只按固定表返回，不拼接路径；不存在目录穿越。

## 5. 安全边界与扩展约定

- **管理端 token 等同管理员密码**：拿到 token 就能读写全部配置、备份、权限和世界栈；不要把它发给普通玩家，不要放进 URL、日志、截图或公开仓库。token 轮换后需要重启 Web 服务才会生效（运行中的服务持有启动时的 token）。
- **管理端静态页壳可能公开，但不含数据**：`/`、`/app.js` 等静态资源本身不需要 token；没有 token 时所有管理 API（`/api/config`、`/api/status`、`/api/server`、`/api/world-stack`、`/api/permissions`、`/api/map` 等）返回 `401`。如果连页面壳都不希望公开，需要在反向代理层做路径过滤、加独立端口或额外登录页。
- **默认只公开用户端**：推荐反向代理只转发 `/user`、`/user.js`、`/style.css`、`/map.js`、`/banners.js`、`/api/user/*`。要公开管理端时，至少强制 HTTPS，并在代理层加限流/IP 白名单；当前 Mod 自身没有登录锁定、速率限制或二次验证。
- **用户端无 token**：只注册 `/api/user/*`，不注册任何管理端 handler。用户端服务类 `WebUserService` 只依赖地图只读服务，不持有配置/身份/权限/备份服务。
- **不要直接把管理端 Map 透传**：新增用户端字段时必须显式白名单重建；尤其是 `worldRoot`、`metaPath`、`cache`、`ownerUUID`、`uuid`、`health`、`yaw`、备份路径、token。
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
