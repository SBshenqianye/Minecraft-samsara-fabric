---
name: samsara-server
description: 管理 samsara Minecraft 服务器。通过本机 Web API 查看状态、编辑配置、发送公告、查看审计。用于服主或运维 Agent，不用于普通玩家。
---

# samsara 服务器管理

## 何时使用

- 查看服务器、周目、世界栈、在线状态
- 创建、预览、发送游戏内公告
- 修改 samsara YAML、服务器本体或第三方配置
- 查看 Web 写操作审计

## 连接

- 默认地址：`http://127.0.0.1:8720`；测试服通常 `8722`
- 鉴权：`Authorization: Bearer $SAMSARA_TOKEN`
- Whoami：`GET /api/agent/whoami`（actor/scope/限流）
- OpenAPI：`GET /openapi.json`
- 完整接口：`references/endpoints.md`；仓库内还有 `docs/Agent API.md`

token 可能是管理员 token（全 scope）或 scoped token（read/announce/config/ops/rcon）。
写请求可带 `Idempotency-Key`；429 时按 `Retry-After` 等待，不要狂重试。

不要猜测端口；先读环境变量 `SAMSARA_URL`，没有再用默认值。

## 安全规则

1. 有副作用的操作必须先预览，再确认。
2. 公告发送默认 `dryRun=true`；真正发送必须 `dryRun=false` + `confirm=true`。
3. 配置修改必须带 preview 返回的 `revision`，并 `confirm=true`。
4. 不要默认调用 `/api/server/console` 的 RCON 命令；优先使用意图接口。
5. 写操作失败不要盲目重试；先读 `error` / `fields`。
6. 不把 token 写进日志、聊天、公告、URL 或提交记录。
7. 全服公告、删配置、回滚审计等高风险操作，必须得到用户明确确认。

## 常用流程

### 查看状态

`GET /api/status`

### 发公告

1. `GET /api/announcements` 读取公告列表
2. `POST /api/announcements/preview` 预览变量替换
3. `POST /api/announcements/send`，先 `dryRun=true`
4. 用户确认后，再 `dryRun=false, confirm=true`

公告文本支持 `&` 颜色码、`&#RRGGBB`、`\n` 与变量
`{player}`、`{display}`、`{world}`、`{dimension}`、`{online}`、`{time}`、`{date}`、`{prefix}`。

### 改配置

1. `GET /api/config/pages`
2. `POST /api/config/preview`，提交 `{file, values}`
3. 检查 `fields`、`diff`、`revision`
4. `PUT /api/config`，提交 `{file, source, values, revision, confirm}`

第三方与服务器配置必须确认；samsara 自有配置按页面 `requiresConfirmation`。

### 查看审计

`GET /api/audit`
回滚前先 `POST /api/audit/preview`，再 `POST /api/audit/rollback`。

## 参考

- `references/endpoints.md`
- `references/announcements.md`
- `references/config-editor.md`

## 禁止

- 不要直接改服务器文件系统
- 不要绕过 preview/confirm
- 不要在未确认时发全服公告
- 不要把管理员 token 传给第三方
