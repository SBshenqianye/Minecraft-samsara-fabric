# samsara · 周目管理

> 原版红石生存服务器的**世界生命周期管理**：同一个存档，长期玩下去。
> **空间** 圈地保护 · **时间** 周目轮换 · **逆熵** 未圈地倒流重生

## 当前版本

**v0.15.0** · Minecraft **26.3** · Fabric Loader **0.20.1** · Java **25**

## 能做什么

| 功能 | 状态 | 说明 |
|---|---|---|
| 周目轮换 | ✅ 可用 | 记录每个周目的时间、出生中心与分组；同一世界内开新周目 |
| 分组出生 | ✅ 可用 | 玩家按 3~5 人分组，围绕新出生中心分散；离线玩家上线后补传送 |
| 时间倒流 | ✅ 可用 | 未圈地区块按设定周期逐格恢复：近处慢放、远处按曲线退场，完成后备份、删除并重新生成 |
| 旗帜圈地 | ⚠ 部分可用 | 图案定方向、染料定距离，保护区域取并集；图标图案校验尚未实现 |
| 安全删除 | ⚠ 部分可用 | 预览、备份、执行、报告；整档压缩备份尚未实现 |
| 活动记录 | ✅ 可用 | 静默记录登录、在线时长与本次会话的原版统计增量，供事后 Web 统计 |
| 玩家轨迹 | ✅ 可用 | 5Hz 采样位置/朝向，按「世界 + 周目 + 日期」留档并按年龄几何简化；回放为原版人偶（Mannequin），支持多人同屏、攻击/交互即捕捉 |
| 回看模式 | ✅ 可用 | `/samsara track view` 只读回看任意时间段：倍速、时间窗、跟随/旁观/可交互/发光/名牌、暂停恢复、倒放；观看者全程旁观，可在 32 格内自由活动，超出/人偶瞬移/换维度自动跟随；会话期间免伤，结束恢复观看者，不写轨迹/差异/截断点/存档/周目数据 |
| 回看控制 | ✅ 可用 | 聊天栏可点击控制条（倍速/暂停/继续/倒放/停止）；`/samsara track list` 条目可点击自动填入命令 |
| 可点击指令 | ✅ 可用 | `/samsara` 帮助、`season status/info`、`claim info`、`where` 等只读命令点击即执行；`season start`、`debug *`、`perm set` 等危险命令点击只填入、不执行 |
| Tab 补全 | ✅ 可用 | 玩家名、`cycleN`、回看开始时刻/参数/时长/倍速、周目模式、权限路径与等级、区块坐标、布尔开关等；补全项带说明，不按键读盘 |
| 权限 | ✅ 可用 | 四级权限，命令所需等级可配置，支持 owner 名单 |
| 位置查询 | ✅ 可用 | `/samsara where` 查看自己的维度与坐标（可带区块、朝向），`where broadcast` 广播全服；命令启用与输出字段在 `config/samsara/commands.json` 配置 |
| TPA 传送 | ✅ 可用 | `/tpa`、`/tpahere` 发起请求，`/tpaccept`、`/tpdeny`、`/tpcancel` 处理；默认 60 秒过期、每人同时只有 1 个请求、仅同维度；请求消息带 `[同意] [拒绝]` / `[取消请求]` 可点击按钮 |
| 命令短别名 | ✅ 可用 | `/season`、`/claim`、`/where`、`/version`、`/track`、`/tpa` 系列等；被其他模组占用时自动让位，只保留 `/samsara` 全名 |
| 新世界模式 | 🚧 规划中 | 旧世界入栈、新建世界、跨世界传送、栈顶限制 |
| 全员同意开新周目 | 🚧 规划中 | 无管理员时由在线玩家发起并全员同意 |
| 身份同步 | 🚧 规划中 | 正版/离线账号映射、密码保护、数据迁移 |
| 数据包开场流程 | 🚧 规划中 | 数据包目前只有骨架，出生区预生成也未接入 |
| Web 面板 | 🔭 远期 | 地图、玩家数据与管理可视化 |

## 常用命令

| 想做什么 | 命令 |
|---|---|
| 看当前周目 | `/samsara season status`、`/samsara season info` |
| 看圈地 | `/samsara claim info`、`/samsara claim boundary` |
| 开新周目 | `/samsara season create <名称>` → `/samsara season start`（开始前二次确认） |
| 管理/调试 | `/samsara debug ...`（owner；圈地、倒流、轨迹、配置、测试数据） |
| 回放玩家轨迹 | `/samsara debug track replay <玩家> <开始时刻> [倍速] [周目]` · `track stop [玩家\\|all]` · `track status` |
| 回看历史轨迹 | `/samsara track view <玩家> <开始时间> [--speed 4 --duration 30m --follow --spectator]` · `view stop/status/pause/resume` · `track list <玩家>`（短别名 `/track`） |
| 看自己的坐标/维度 | `/samsara where`；`/samsara where broadcast` 广播全服 |
| 传送到别人/让别人过来 | `/tpa <玩家>`、`/tpahere <玩家>`；对方 `/tpaccept` 或 `/tpdeny`；发起者 `/tpcancel` |
| 看版本 | `/samsara version`（别名 `/version`） |

> 周目管理在 `/samsara season`，调试与圈地管理在 `/samsara debug`；游戏内输入 `/samsara` 可看帮助（列表里的青色按钮点击即执行，黄色按钮点击只填入）。
> 所有玩家名、周目、时长、回看参数、权限路径/等级都支持 **Tab 补全**；回看时段列表与帮助里的危险命令只填入命令，不会直接执行。

## 安装与构建

需要 **JDK 25**；Gradle wrapper 已内含。先让 `JAVA_HOME` 指向 JDK 25，再构建：

```bat
cd mod
set JAVA_HOME=C:\Program Files\Java\jdk-25
gradlew.bat build
```

Linux/macOS 用 `./gradlew build`。产物是 `mod/build/libs/samsara-<版本>.jar`，放进服务器 `mods/`。

| 组件 | 版本 |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| Java | 25 |
| Gradle / Loom | 9.5.1 / 1.17 |

> 26.2 起使用 Mojang 官方映射，构建脚本不需要 yarn 映射；26.3 沿用同一套构建链。

## 加载方式

模组按“服务端入口 + 客户端也加载”的方式声明：

- **专用服务器**：全功能；
- **客户端 + 单人存档**：单人内置服务器启动时同样加载；
- **客户端 + 连别人服务器**：不执行服务端逻辑，零副作用。

> **不要改成仅服务端**：那样客户端（含单人存档）会把模组过滤掉，单人玩法直接不可用。

## 数据放在哪

所有模组数据都在 `config/samsara/`，**不写入存档目录 `world/`，也不在服务端根目录散落文件**。包括周目、圈地、倒流、玩家轨迹、备份、权限与活动记录。

命令功能开关在 `config/samsara/commands.json`：`where.enabled` 控制是否启用 `/samsara where`，`where.broadcast` 控制是否允许广播，`where.broadcastCooldownSeconds` 是广播冷却（0=不限），`where.showCoordinates/showDimension/showChunk/showFacing` 控制输出字段；`tpa.enabled` 控制 TPA 系列，`tpa.requestTimeoutSeconds` 是请求有效期（默认 60），`tpa.crossDimension` 控制是否允许跨维度；`view.protectViewer` 控制回看时是否免伤，`view.freeRoamRadius` 是回看时允许自由活动的半径（默认 32 格）。改完执行 `/samsara debug reload` 生效，无需重启。

活动记录按天保存，只写不读：登录、在线时长，以及本次会话的移动、挖掘、使用、击杀、交易等原版统计增量。没有 tick 轮询，也不提供游戏内查询命令；字段与开销见[《设计规范》](docs/设计规范.md)的〈活动记录：静默采集约定〉。

倒流记录按「世界 → 维度」分目录保存在常开窗口 `diff/live/<世界>/minecraft/overworld…`（主世界、下界、末地与不同世界互不混写），倒流开始时冻结为 `diff/cycle<周目id>/`；旧版 `diff/current`、`diff/s<id>` 与无世界层记录会在启动时自动迁入新布局，记录在运行中与停服时落盘，服务器重启不会丢掉最近一段改动。

玩家轨迹按「世界 + 周目 + 日期」保存在 `track/<世界>/cycle<周目id>/<日期>/<uuid>.bin`，越老的记录按 1/4/16/64/256 天档位做几何简化，并保留每天至少一份骨架点；回放用原版人偶（Mannequin，客户端无需安装模组、带玩家皮肤），支持多人同屏。人偶被玩家攻击/交互后会停止回放并变成普通实体；该玩家该周目从捕捉时刻起的轨迹不再回放（磁盘记录保留，截断点写在 `track/cutoffs.json`）。捕捉不保护所在区块：留在会被删的区块里的人偶会和区块一起消失，提前运到安全区块才能活到下一周目。

## 仓库结构

`mod/` 服务端模组 · `tools/` 离线脚本 · `docs/` 文档 · `datapack/` 数据包骨架

## 文档

| 文档 | 看什么 |
|---|---|
| [需求文档](docs/需求文档.md) | 要做成什么样、边界与验收标准 |
| [设计文档](docs/设计文档.md) | 技术方案、数据模型、算法 |
| [设计规范](docs/设计规范.md) | 代码约定、数据格式、实现约束 |
| [实现状态](docs/实现状态.md) | 哪些未实现/未达标，以及对应证据 |
| [决策记录](docs/decisions/) | 为什么这样定 |
| [变更记录](CHANGELOG.md) | 每个版本的功能变化 |
