# 配置编辑器

## 页面

`GET /api/config/pages` 返回三类 `source`：

- `samsara`：samsara 自有 YAML
- `server`：服务器本体 6 个文件
- `third-party`：第三方 YAML / JSON / TOML / properties

每个页面有：

- `id`、`fileName`、`path`、`format`
- `revision`：当前文件 SHA-256
- `writable`、`requiresConfirmation`、`readOnlyReason`
- `form.fields[]`：`path`、`type`、`default`、`min/max`、`choices`、`group`、`description`、`secret`
- `values`：当前值（敏感字段为空）

## 保存流程

1. `POST /api/config/preview`：

```json
{ "file": "third-party:mod/config.toml", "values": { "server.port": 25566 } }
```

2. 检查响应：`ok`、`fields`、`changes`、`diff`、`revision`。

3. `PUT /api/config`：

```json
{
  "file": "third-party:mod/config.toml",
  "source": "third-party",
  "values": { "server.port": 25566 },
  "revision": "预览返回的 revision",
  "confirm": true
}
```

## 规则

- 必须带 preview 返回的 `revision`。
- 第三方/服务器配置必须 `confirm=true`。
- 不要直接改磁盘文件。
- 写前备份在 `backups/samsara/config-editor/`。
- YAML/JSON/TOML 重新序列化会丢注释；properties 保留注释与顺序。
