# 运维任务 Prompt 模板

## 发公告

```text
用 samsara-server skill 发一条公告：
- 内容：<内容>
- 展示：聊天 / ActionBar / Title / BossBar / 声音
- 目标：全服 / 权限组 / 世界 / 指定玩家
- 时间：立即 / 每隔 N 分钟 / 指定时间 / 进服

先给我预览，不要直接发送。我确认后再发。
```

Agent 应执行：

1. `GET /api/announcements`
2. 构造或复用公告
3. `POST /api/announcements/preview`
4. 把预览和目标人数给用户
5. 用户确认后 `POST /api/announcements/send` with `dryRun=false, confirm=true`

## 改配置

```text
用 samsara-server skill 修改 <文件/配置项>，改成 <值>。
先预览，告诉我会影响哪些字段，不要直接保存。
```

Agent 应执行：

1. `GET /api/config/pages`
2. `POST /api/config/preview`
3. 展示 `changes` / `diff` / `fields`
4. 用户确认后 `PUT /api/config` 带 `revision` + `confirm`

## 查状态

```text
用 samsara-server skill 看一下服务器状态、在线玩家和最近 Web 审计。
```

Agent 应执行：

1. `GET /api/status`
2. `GET /api/server`（需要在线/本体信息时）
3. `GET /api/audit`
4. 汇总，不执行写操作

## 拒绝/升级

- 用户未确认全服公告：只预览，不发送。
- revision 不匹配：重新 preview，不覆盖。
- 请求 401/400：报告错误，不重试写操作。
- 用户要求 RCON：先说明风险并取得明确确认。
