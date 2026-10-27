# samsara 离线工具

| 脚本 | 说明 |
|---|---|
| `analyze.py` | 活跃度分析 v0：不开服，多来源推断玩家最后登录（`config/samsara/activity/*.jsonl` / 日志 joined 行 / usercache expiresOn 估算 / 旧版 lastPlayed），输出"距上次玩家登录 X 天"（零依赖，Python 3 标准库，已实测；JSONL 只取 `ev=join`，兼容旧版 `activity.log` 及其滚动档，支持服务端根配置目录） |
| `docs_sync.py` | 文档同步检查：项目版本（`mod/gradle.properties`）与 README 当前版本、CHANGELOG 最新条目对齐；校验 README 本地链接；提示 README/CHANGELOG 里的内部编号、类名与源码文件名。`--apply` 只同步 README 版本等机械字段，`--strict` 把内部标识告警当错误 |
| `RconClient.java` | 最小 Minecraft RCON 客户端（单文件、无第三方依赖，Java 17+ 编译）：向服务端发一条命令并打印回包，用于部署后验证。退出码 `0` 成功 / `1` 参数错误 / `2` 连接失败 / `3` 认证失败 |

用法示例：

```
python analyze.py "E:\服务器存档\world"
python analyze.py "C:\Games\0-minecraft\server\saves\玩家背包" --json reports\report.json
python docs_sync.py --check
python docs_sync.py --apply

javac --release 17 -encoding UTF-8 -d .tmp\rcon tools\RconClient.java
java -cp .tmp\rcon RconClient 127.0.0.1 25575 "<rcon密码>" "list"
```

> 注意：1.19 起 `lastPlayed`/`firstPlayed` 已从玩家 .dat 移除，仅提供 .dat 目录时无法推断登录时间（脚本会给出提示）；建议提供 `usercache.json` + `logs/` 目录，或 `config/samsara/activity/`（模组 v0.10.0 起自动写入）。

规划中：身份迁移工具（实现《设计文档》〈身份同步〉的正版/离线 UUID 映射，见 `docs/实现状态.md`）。
