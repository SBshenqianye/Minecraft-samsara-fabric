# samsara 离线工具

| 脚本 | 说明 |
|---|---|
| `analyze.py` | 活跃度分析 v0：不开服，多来源推断玩家最后登录（activity.log / 日志 joined 行 / usercache expiresOn 估算 / 旧版 lastPlayed），输出"距上次玩家登录 X 天"（零依赖，Python 3 标准库，已实测；兼容 `epoch_ms name JOIN|LEAVE` 新格式，只取 JOIN，支持滚动档 activity.1.log 与服务端根配置目录两种位置） |

用法示例：

```
python analyze.py "E:\服务器存档\world"
python analyze.py "C:\Games\0-minecraft\server\saves\玩家背包" --json reports\report.json
```

> 注意：1.19 起 `lastPlayed`/`firstPlayed` 已从玩家 .dat 移除，仅提供 .dat 目录时无法推断登录时间（脚本会给出提示）；建议提供 `usercache.json` + `logs/` 目录，或等待活跃度模块的 `activity.log`。

规划中：身份迁移工具（§9 正版/离线 UUID 映射）。
