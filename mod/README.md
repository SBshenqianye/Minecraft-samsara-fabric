# samsara mod（Fabric 服务端模组）

## 架构：服务端模组（server-only 逻辑），单人存档也可用

**服务器只需安装本模组即可获得全部功能**（周目状态机、后续的传送/区块管理/倒流均为服务端逻辑）。
fabric.mod.json 采用 `environment: "*"` + `"server"` entrypoint（`Samsara` 实现 `DedicatedServerModInitializer`）：

| 场景 | 行为 |
|---|---|
| 纯服务器（dedicated） | ✅ 全功能（已验证：/season 状态机正常） |
| 客户端安装 + 单人存档 | ✅ 全功能 —— fabric-loader 的 `EntrypointPatch` 在 integrated server（单人=客户端进程内的逻辑服务器）启动时调用 `"server"` entrypoint |
| 客户端安装 + 连接他人服务器 | 模组被加载但 server entrypoint 不执行（无逻辑服务器），零副作用；功能由服务器端提供 |

> ⚠️ 不要改成 `environment: "server"`：那会让模组在客户端进程（含单人）中被过滤不加载，单人存档将不可用。
> 依据：fabric-loader 0.19.3 的 `ModResolver` 按物理进程 EnvType 过滤模组；`EntrypointPatch` 负责单人时调用 server entrypoint。

## 版本锁定（已完成，M1；已升级 26.1 → 26.2）

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

## M2 分组出生 + 时间倒流（档2）

`/season start`（same-world 且 settings.rewindDurationDays&gt;0 时）执行完整流程：
`新出生中心 → 分组（3~5人/组，角度均分+抖动，组距默认 1000）→ 持久化 groupSpawns → 启动倒流（默认 1 天可配置）→ 倒流到期 → 全员传送 + 广播；离线玩家上线补传送`。

- 注册玩家来源：测试注入池 + 在线 + `usercache.json` + `world/dimensions/.../players/data/`
- 倒流档2：未圈地块分配均匀倒流时刻，物理删除仅在**停服窗口**（SERVER_STOPPED，备份到 `backups/purge_<时间戳>/` 后清零区块槽位，重启按当前版本重新生成）
- 保护集：世界出生保护圈 + 各周目出生区 + 分组出生点 + `whitelist.json` 配置区域
- **新出生中心**：与全部历史中心集合（世界初始点 + 每个已开始周目中心）的距离都 ≥ `newCenterMinDistance`（默认 15000，无上限）
- **每人独立系统出生点**：`resetIndividualBirthpoint` 开时，玩家无床死亡重生点重置到各自组出生点 ± `birthpointJitter`（确定性抖动，同组玩家也相差几十格，0=同组共用）
- 配置文件：`config/samsara/season.json`（settings）、`config/samsara/rewind.json`（倒流时刻表）、`config/samsara/whitelist.json`
- **配置命令**：`/samsara debug config`（无参=全部；`config <键>`=当前值；`config <键> <值>`=设置并回显）。键：`groupdist`/`birthjitter`/`birthreset`/`rewind`/`groupmin`/`groupmax`/`spawnradius`/`centermin`/`centermax`
- **倒流时长单位**：`config rewind <时长>` 支持 `d/h/m/s` 和纯数字=秒，如 `1h`、`30m`、`3600s`、`1d`、`0.5d`；亦可 `/samsara debug rewind set <时长>`
- **倒流中 `/season end`**：先中止倒流（未到点区块保留、已到点待停服清理），再置周目 ENDED

## 结构

```
src/main/java/com/shenqianye/samsara/
├── Samsara.java              # 主入口（server entrypoint + tick/停服/加入事件钩子）
├── command/
│   ├── SeasonCommand.java    # /season status/info/create/start/end/respawn-groups
│   └── DebugCommand.java     # /samsara debug 调试指令集（COMMANDS_OWNER）
├── season/
│   ├── Season.java           # 周目模型（§3.3，含 spawnCenter/groupSpawns）
│   ├── SeasonData.java       # season.json 根结构（含 settings）
│   ├── SeasonManager.java    # 状态机 + 分组/传送/广播 + 调试支持（M1+M2）
│   ├── GroupPlanner.java     # 分组算法（纯逻辑，可单测）
│   ├── PlayerRegistry.java   # 注册玩家列表（来源合并 + 测试注入池）
│   └── SpawnCenterCalculator.java # 出生中心计算（纯逻辑）
├── whitelist/
│   ├── WhitelistData.java    # whitelist.json 结构
│   ├── WhitelistStore.java   # 配置白名单单例
│   └── ChunkProtector.java   # 倒流保护集判定（区块粒度）
└── rewind/
    ├── RewindData.java       # rewind.json 结构
    ├── McaRegion.java        # MCA 区域文件扫描/槽位清零/备份（独立于 MC 内部类）
    └── RewindEngine.java     # 倒流调度器 + 停服物理删除 + dry-run
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
| `/samsara debug fakeplayers <clear\|数字\|前缀 数量\|名字,名字...>` | 注入测试玩家（M2 分组测试数据） |
| `/samsara debug groups [名单]` | 分组 dry-run（不落盘；缺省用注册玩家） |
| `/samsara debug rewind preview\|status\|abort\|set <天>` | 倒流：预览扫描/状态/中止/时长配置 |
| `/samsara debug whitelist json\|reload` | 白名单查看/重载 |

> 权限说明：`/season` 管理子命令用 COMMANDS_ADMIN（原 OP 等级 2），`/samsara debug` 用 COMMANDS_OWNER（最高权限）。
> 26.1 API 要点：类名用 Mojang 官方名（`net.minecraft.commands.CommandSourceStack` / `Commands` / `CommandBuildContext` / `net.minecraft.network.chat.Component`），Fabric API 的 `CommandRegistrationCallback` 未改名。
