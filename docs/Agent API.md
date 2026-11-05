# samsara Agent API

本文面向**外部 Agent**（Claude Code、Codex、自建编排器等）。samsara Web 只做控制面：Agent 通过本机 HTTP API 读写配置、查看状态、发送公告；模型与 API key 不进入 Minecraft 服务端进程。

## 1. 连接方式

- 监听地址：`127.0.0.1`（只回环）
- 默认端口：生产 `8720`，测试服 `8722`
- 鉴权：`Authorization: Bearer <token>`
  - 管理员 token：`config/samsara/web.yml`，拥有全部 scope
  - Agent token：`/api/agent/tokens` 创建，按 scope 限权
- OpenAPI：`GET /openapi.json`
- Whoami：`GET /api/agent/whoami`
- 远程 Agent 建议通过 SSH 隧道或同机 bridge，不要把端口直接暴露公网。

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8720/api/status
```

## 1.1 Scoped token

scope：

| scope | 允许 |
|---|---|
| `read` | 所有 GET、preview、审计预览 |
| `announce` | 公告保存/发送/停止 |
| `config` | 配置写入、reload、权限/输出/身份组写入 |
| `ops` | 服务器配置、备份、世界栈、审计回滚、Agent token 管理 |
| `rcon` | `/api/server/console` |

创建 token 需要**管理员 token**（Agent token 不能自我提权）：

```bash
curl -X POST http://127.0.0.1:8720/api/agent/tokens \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"ops-agent","scopes":["read","announce","config"]}'
```

响应中的 `token` 只显示一次，服务端只保存 SHA-256。

`GET /api/agent/tokens` 列表不返回明文；`DELETE /api/agent/tokens` + `{"name":"ops-agent"}` 撤销。

## 1.2 限流与幂等

- 每分钟限额：read 300、announce 60、config 60、ops 30、rcon 20。
- 超限返回 `429` 和 `Retry-After: 60`。
- 写请求可带 `Idempotency-Key: <唯一值>`；同一 actor + method + path + key 在 10 分钟内重复提交直接返回首次响应，并带 `Idempotency-Replayed: true`。
- 建议 Agent 为每个任务生成一次 key，重试时复用同一个 key。

## 2. 通用约定

### 先预览，再确认

有副作用的接口尽量遵循：

1. `dryRun: true` / `preview` 获取变更、字段错误、revision、目标人数。
2. 用户或策略确认后，带 `confirm: true` 执行。
3. 配置写入必须带预览返回的 `revision`，防止覆盖人工修改。

公告发送默认 `dryRun=true`；真正发送必须显式：

```json
{"announcement": {...}, "dryRun": false, "confirm": true}
```

### 错误与审计

- `401`：token 缺失/错误。
- `400`：字段校验失败、revision 过期、无匹配玩家。
- `405`：方法不允许。
- Web 写请求会进入「Web 审计」，可在 `/api/audit` 查看快照；回滚用 `/api/audit/preview` + `/api/audit/rollback`。
- RCON 控制台是高风险接口，Agent 不应默认使用；优先使用意图接口。

## 3. 公告

### 读取模型

`GET /api/announcements` 返回公告列表、变量、枚举、历史：

```json
{
  "ok": true,
  "announcements": [
    {
      "id": "welcome",
      "name": "欢迎公告",
      "enabled": false,
      "trigger": "join",
      "target": "all",
      "chat": true,
      "chatText": "&e[公告] &f欢迎 {player} 来到服务器！\\n&7在线 {online} 人",
      "bossbar": false,
      "sound": false
    }
  ],
  "variables": ["player", "display", "world", "dimension", "online", "time", "date", "prefix"]
}
```

### 预览

```bash
curl -X POST http://127.0.0.1:8720/api/announcements/preview \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"announcement":{"chat":true,"chatText":"&e你好 {player}，在线 {online} 人"}}'
```

### 发送

```bash
# 1) 预览目标与文本
curl -X POST .../api/announcements/send -d '{"id":"welcome","dryRun":true}'

# 2) 确认发送
curl -X POST .../api/announcements/send \
  -d '{"id":"welcome","dryRun":false,"confirm":true}'
```

`player` 可指定单个测试玩家；留空按公告的 `target` 解析。

### 字段与变量

- 文本：`&0-9a-fk-or`、`&#RRGGBB`、`\n`；变量 `{player}`、`{display}`、`{world}`、`{dimension}`、`{online}`、`{time}`、`{date}`、`{prefix}`。
- 展示：`chat`、`actionbar`、`title`/`subtitle`、`bossbar`、`sound`。
- 触发：`manual`、`interval`、`schedule`、`join`。
- 目标：`all`、`permission`、`world`、`dimension`、`players`。
- 点击：`run`/`suggest`/`open_url`/`copy`；`run` 默认要求 OP，或配置 `clickPermission`。

## 4. 配置

1. `GET /api/config/pages` 获取页面与字段。
2. 找到目标 `id`（如 `samsara:commands`、`server:server-properties`、`third-party:mod/config.toml`）。
3. `POST /api/config/preview` 提交 `{file, values}`，读取 `fields`、`diff`、`revision`。
4. `PUT /api/config` 提交 `{file, source, values, revision, confirm}`。

第三方与服务器配置必须 `confirm=true`；samsara 自有配置按页面 `requiresConfirmation` 决定。

## 5. 建议的 Agent skill 结构

外部 skill 不需要把全部 API 写进上下文，建议：

```text
samsara-agent/
  SKILL.md          # 何时使用、安全规则、常用流程
  references/
    endpoints.md    # 本文件
    announcements.md
    config-editor.md
  scripts/
    samsara.sh      # 读取 SAMSARA_URL/SAMSARA_TOKEN 的薄封装
```

`SKILL.md` 至少包含：

- 先 `GET /api/status` 确认服务器在线。
- 公告先预览，发送前必须确认；默认不发全服。
- 配置修改必须 preview + revision + confirm；不要直接改文件。
- 不默认调用 `/api/server/console` 的 RCON 命令。
- 失败时读 `error`/`fields`，不要盲目重试写操作。

## 6. 当前边界

- 已支持 read/announce/config/ops/rcon scoped token；token 管理仅管理员。
- Web 审计已记录 actor；`/api/audit` 的每条记录带 `actor`。
- 写请求已支持 `Idempotency-Key`；长操作仍沿用各业务自己的 jobId（世界栈/备份），没有统一 job 查询层。
- 只有回环监听，远程访问需要 SSH 隧道或本机 bridge。
- 写审计快照范围仍是 Web 可管理的小文件，不覆盖整个世界数据。
