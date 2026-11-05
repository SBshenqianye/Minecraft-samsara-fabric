# samsara Web API 速查

Base：`$SAMSARA_URL`，默认 `http://127.0.0.1:8720`
鉴权：`Authorization: Bearer $SAMSARA_TOKEN`

完整 OpenAPI：`GET /openapi.json`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/agent/whoami` | 当前 token actor/scope/限流 |
| GET/POST/DELETE | `/api/agent/tokens` | Agent token 管理（仅管理员） |
| GET | `/api/status` | 服务器/配置路径/警告 |
| GET | `/api/config/pages` | PageSpec/FormSpec 配置页面 |
| POST | `/api/config/preview` | 预览配置变更，返回 fields/diff/revision |
| PUT | `/api/config` | 保存配置；第三方/服务器需 revision + confirm |
| GET | `/api/announcements` | 公告列表、变量、枚举、历史 |
| PUT | `/api/announcements` | 保存公告列表 |
| POST | `/api/announcements/preview` | 预览公告 |
| POST | `/api/announcements/send` | 发送公告；默认 dryRun，confirm 才执行 |
| POST | `/api/announcements/stop` | 停止 BossBar |
| GET | `/api/audit` | 写操作审计 |
| POST | `/api/audit/preview` | 预览回滚 |
| POST | `/api/audit/rollback` | 回滚（需 confirm） |
| GET | `/api/server` | 本体配置/备份概览 |
| GET | `/api/map` | 地图模型 |
| GET | `/api/world-stack` | 世界栈模型 |

## 错误

- `401`：token 缺失/错误
- `400`：字段校验失败、revision 过期、无匹配玩家
- `405`：方法不允许

不要自动重试写操作；先读 `error` / `fields`。
