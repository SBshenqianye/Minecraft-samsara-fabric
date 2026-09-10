# samsara mod（Fabric 服务端模组）

## 架构：服务端模组（server-only 逻辑），单人存档也可用

**服务器只需安装本模组即可获得全部功能**（周目状态机、后续的传送/区块管理/倒流均为服务端逻辑）。
fabric.mod.json 采用 `environment: "*"` + `"server"` entrypoint（`Samsara` 实现 `DedicatedServerModInitializer`）：

| 场景 | 行为 |
|---|---|
| 纯服务器（dedicated） | ✅ 全功能（已验证：/samsara season 状态机正常） |
| 客户端安装 + 单人存档 | ✅ 全功能 —— fabric-loader 的 `EntrypointPatch` 在 integrated server（单人=客户端进程内的逻辑服务器）启动时调用 `"server"` entrypoint |
| 客户端安装 + 连接他人服务器 | 模组被加载但 server entrypoint 不执行（无逻辑服务器），零副作用；功能由服务器端提供 |

> ⚠️ 不要改成 `environment: "server"`：那会让模组在客户端进程（含单人）中被过滤不加载，单人存档将不可用。
> 依据：fabric-loader 0.19.3 的 `ModResolver` 按物理进程 EnvType 过滤模组；`EntrypointPatch` 负责单人时调用 server entrypoint。

## 版本锁定（已完成；已升级 26.1 → 26.2）

**MC 26.2 是"去混淆"版本：yarn 映射已停止维护（2025 年 Fabric 宣布弃用），使用 Mojang 官方映射，构建脚本里没有 `yarn_mappings`，loom 1.17 起自动处理，`build.gradle` 不再写 `mappings` 行。**

`gradle.properties` 当前值（来源：官方示例模板 FabricMC/fabric-example-mod 的 26.2 分支 + https://fabricmc.net/develop/ ）：

| 属性 | 值 | 说明 |
|---|---|---|
| `minecraft_version` | `26.2` | 26.2 正式版（设计文档"26.2 稳定后跟进"已执行，2026-08-16） |
| `loader_version` | `0.19.3` | meta.fabricmc.net 最新稳定（26.1/26.2 通用） |
| `fabric_version` | `0.157.0+26.2` | Fabric API（26.2 模板锁定；新增 fabric-permission-api-v1 官方权限 API） |
| `loom_version` | `1.17-SNAPSHOT` | fabric-loom（26.2 模板锁定） |
| `java_version` | `25` | **26.2 要求 Java 25**（version.json java_version=25） |

Gradle wrapper 已升到 **9.5.1**（loom 1.17 需要 Gradle 9.x）。

> 26.1 → 26.2 升级记录：仅改 `minecraft_version`/`fabric_version`/`fabric.mod.json` 的 `~26.1`→`~26.2`，**代码零改动**（26.2 为小版本更新，变更集中在渲染/GPU）；数据包 pack_format 101→107。两条验证路径（开发服 runServer + 生产服 test-server）均已实测通过。

## 构建（重要：JAVA_HOME 必须是 JDK 25）

本机默认 `JAVA_HOME` 指向 JDK 8，**每次构建前必须改**：

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-25
gradlew.bat build
```

产物：`build/libs/samsara-<version>.jar`，放入服务器 `mods/`。

## 分组出生 + 时间倒流

`/samsara season start`（same-world 且 settings.rewindDurationMs&gt;0 时）执行完整流程：
`新出生中心 → 分组（3~5人/组，角度均分+抖动，组距默认 1000）→ 持久化 groupSpawns → 启动倒流（默认 1 天可配置）→ 倒流到期 → 全员传送 + 广播；离线玩家上线补传送（持久化，重启不丢）`。

- 注册玩家来源：测试注入池 + 在线 + `usercache.json` + `world/dimensions/.../players/data/`（真实 start 自动清空测试注入池）
- **diff 快照语义（2026-08-17）**：记录**常开**写入 `diff/current/`；倒流启动时冻结为 `diff/s<赛季id>/` = 本次恢复源（窗口 = 上次删块完成之后至本次倒流开始）；完成时重置窗口。恢复只读恢复源 → 倒流期间新改动不会被本轮倒流恢复
- **时间倒流**：未圈地块分配均匀倒流时刻（区块波浪退场）；对加载区块按进度逐格恢复（懒加载 + 自适应预算，倒流结束后继续恢复到完成）
- **物理删除（完成即删 + 文件级回收 + 批量优化）**：已卸载区块后台备份+清零；**仍加载的入待删队列 `pending-delete.json`（卸载即删，启动/停服兜底）**；清零后**全空的 .mca 直接整文件删除**，停服窗口自动**压缩重写**残留文件（`compact`，也可 `/samsara debug rewind compact` 手动）→ 磁盘空间真正回收
- **安全删除增强**：`/samsara debug claim delete execute` 支持多维度 + 白名单索引快删 + drainNonLoaded 即时处理未加载区块；批量按维度/区域文件分组删除（设计规范 §18）
- **rewind.json 归档**：倒流"走完"（传送完成 && 恢复完成）后归档至 `config/samsara/archive/`（不删除，供审计）；中止则清除且不删任何区块
- 保护集：**仅圈地**（`whitelist.json` 配置区域，`/samsara debug claim` 维护；v0.8 起已去掉自动出生圈/周目中心/分组点保护）
- **新出生中心**：与全部历史中心集合（世界初始点 + 每个已开始周目中心）的距离都 ≥ `newCenterMinDistance`（默认 15000，无上限）
- **每人独立系统出生点**：`resetIndividualBirthpoint` 开时，玩家无床死亡重生点重置到各自组出生点 ± `birthpointJitter`（确定性抖动，同组玩家也相差几十格，0=同组共用）
- 配置文件：`config/samsara/season.json`（settings）、`rewind.json`（倒流时刻表）、`whitelist.json`、`pending-delete.json`（待删队列）、`pending-teleports.json`（离线补传送）、`diff/current/`（常开记录窗口）、`diff/s<N>/`（冻结恢复源）、`archive/`（归档）
- **配置命令**：`/samsara debug config`（无参=全部；`config <键>`=当前值；`config <键> <值>`=设置并回显）。键：`groupdist`/`birthjitter`/`birthreset`/`rewind`/`groupmin`/`groupmax`/`spawnradius`/`centermin`/`centermax`
- **倒流时长单位**：`config rewind <时长>` 支持 `d/h/m/s` 和纯数字=秒，如 `1h`、`30m`、`3600s`、`1d`、`0.5d`；亦可 `/samsara debug rewind set <时长>`
- **倒流中 `/samsara season end`**：中止倒流（所有区块保持现状、不删除），再置周目 ENDED
- **禁止双 ACTIVE**：已有进行中的周目时 create/start 会被拒绝；世界无可倒流区块时 `/samsara season start` 自动回退为直接传送
- **方块级倒流**：`/samsara debug diff` 查看记录窗口/恢复源状态；mixin 记录方块变化（`ServerLevelMixin` HEAD 注入），恢复读冻结恢复源（懒加载，`DiffRestore` 预算分摊）；未记录区域走删除兜底

## 结构

```
src/main/java/com/shenqianye/samsara/
├── Samsara.java              # 主入口（server entrypoint + tick/停服/加入事件钩子）
├── command/
│   ├── SeasonCommand.java    # /samsara season status/info/create/start/end/respawn-groups
│   └── DebugCommand.java     # /samsara debug 调试指令集（COMMANDS_OWNER）
├── season/
│   ├── Season.java           # 周目模型（§3.3，含 spawnCenter/groupSpawns）
│   ├── SeasonData.java       # season.json 根结构（含 settings）
│   ├── SeasonManager.java    # 状态机 + 分组/传送/广播 + 调试支持
│   ├── GroupPlanner.java     # 分组算法（纯逻辑，可单测）
│   ├── PlayerRegistry.java   # 注册玩家列表（来源合并 + 测试注入池）
│   ├── SpawnCenterCalculator.java # 出生中心计算（纯逻辑）
│   └── DurationUtil.java     # 时长解析（1h/30m/3600s/1d/0.5d）
├── whitelist/
│   ├── WhitelistData.java    # whitelist.json 结构
│   ├── WhitelistStore.java   # 配置白名单单例
│   └── ChunkProtector.java   # 倒流保护判定（仅圈地）
├── claim/                     # 旗帜圈地 + 安全删除（23 个 Java 文件）
│   ├── Extension.java        # 15 种图案→8 方向映射 + 旋转 + 颜色强度
│   ├── ClaimNode.java        # 旗帜节点（含 ownerUUID/ownerName 归属权）
│   ├── AreaService.java      # 圈地服务（单例，维度白名单索引，claims.json 持久化）
│   ├── SafeDeletion.java     # 安全删除（多维度 + 白名单索引 + drainNonLoaded）
│   └── ...                   # ClaimInteractions/Particles/ParticleConfig/Text/PlacementListener/BreakListener
├── permission/                # 权限等级系统（CONFIG/OWNER/OP/USER）
│   ├── PermissionLevel.java  # 等级枚举 + 比较
│   ├── PermissionConfig.java # 加载/保存/查询 + owner 配置，permissions.json 持久化
│   └── PermissionChecker.java # 玩家等级判定 + 命令检查
└── rewind/
    ├── RewindData.java       # rewind.json 结构
    ├── McaRegion.java        # MCA 区域文件扫描/槽位清零/备份/批量删除（含白名单索引优化）
    ├── RewindEngine.java     # 倒流调度器 + PendingDeleteQueue + drainNonLoaded + 批量删除
    ├── DiffRestore.java      # 逐格恢复调度（懒加载 + 预算分摊）
    ├── DiffStore.java        # diff 差异库（内存缓存 + 批量落盘）
    └── DiffRecorder.java     # 记录器 + 5×5 点阵字库 + fakeDiff/fakeDiffText
```

## 调试指令集（/samsara debug，COMMANDS_OWNER）

| 指令 | 作用 |
|---|---|
| `/samsara` | 帮助 |
| `/samsara debug status` | 状态机概要 + 注入玩家数 + season.json 路径 |
| `/samsara debug json` | 打印 season.json 原始内容 |
| `/samsara debug reload` | 重新从磁盘加载 |
| `/samsara debug reset` | 清空全部周目数据（危险操作） |
| `/samsara debug create <名> [模式] [数量]` | 批量创建历史周目 |
| `/samsara debug settime <id> <start\|end> <now\|epoch毫秒>` | 设置时间戳 |
| `/samsara debug fakeplayers <clear\|数字\|前缀 数量\|名字,名字...>` | 注入测试玩家（分组测试数据；真实 start 时自动清空） |
| `/samsara debug groups [名单]` | 分组 dry-run（不落盘；缺省用注册玩家） |
| `/samsara debug flow setup\|rewind <true\|false>\|teleport` | 拆分 `/samsara season start`：只准备 / 只倒流 / 只传送 |
| `/samsara debug diff` | diff 差异库状态（记录窗口 / 恢复源） |
| `/samsara debug diff fake <pos> <数量> [stateId]` | 注入虚假 diff（区块坐标，支持 ~/~N；倒流中注入到恢复源） |
| `/samsara debug diff text <pos> <文字> [stateId] [scale]` | 空中字画（5×5 点阵，倒流保持） |
| `/samsara debug rewind preview\|status\|abort\|purge\|compact\|restore <pos>\|set <时长>` | 倒流：预览/状态/中止/立即删已到倒流时刻区块/区域文件瘦身(全空删+压缩重写)/强制恢复某区块/时长配置 |
| `/samsara debug claim add\|addc <名> <p1> <p2>\|remove <名>\|list` | 圈地维护（whitelist.json） |
| `/samsara debug claim delete dryrun\|backup\|execute\|report` | 安全删除（按白名单，多维度，批量优化） |
| `/samsara debug config [键] [值]` | 配置查看/设置（groupdist/birthjitter/birthreset/rewind/groupmin/groupmax/spawnradius/centermin/centermax） |
| `/samsara debug whitelist json\|reload` | 白名单查看/重载 |
| `/samsara debug spawninfo` | 出生点信息 |

> 权限说明：`/samsara season` 管理子命令用 COMMANDS_ADMIN（原 OP 等级 2），`/samsara debug` 用 COMMANDS_OWNER（最高权限）。
> 26.1 API 要点：类名用 Mojang 官方名（`net.minecraft.commands.CommandSourceStack` / `Commands` / `CommandBuildContext` / `net.minecraft.network.chat.Component`），Fabric API 的 `CommandRegistrationCallback` 未改名。
