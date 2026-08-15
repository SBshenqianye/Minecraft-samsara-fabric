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

## 运行（开发服）

```
gradlew.bat runServer
```

## 结构

```
src/main/java/com/shenqianye/samsara/
├── Samsara.java              # 主入口（DedicatedServerModInitializer，server entrypoint）
├── command/
│   ├── SeasonCommand.java    # /season status/info/create/start/end（M1）
│   └── DebugCommand.java     # /samsara debug 调试指令集（COMMANDS_OWNER）
└── season/
    ├── Season.java           # 周目模型（设计文档 §3.3）
    ├── SeasonData.java       # season.json 根结构
    └── SeasonManager.java    # 状态机 + 持久化 + 调试支持方法（M1）
```

## 调试指令集（/samsara debug，COMMANDS_OWNER）

| 指令 | 作用 |
|---|---|
| `/samsara` | 帮助 |
| `/samsara debug status` | 状态机详细状态 + season.json 绝对路径 |
| `/samsara debug json` | 打印 season.json 原始内容（验证落盘） |
| `/samsara debug reload` | 重新从磁盘加载（测 load 路径） |
| `/samsara debug reset` | 清空全部周目数据（危险操作） |
| `/samsara debug create <名> [模式] [数量]` | 批量创建历史周目（ENDED、每天一个，测列表/ID 递增） |
| `/samsara debug settime <id> <start\|end> <now\|epoch毫秒>` | 直接设置时间戳 |
| `/samsara debug fakeplayers <数量>` | 生成离线玩家名称→UUID（"OfflinePlayer:"+名字，与 §16 身份设计一致，M2 分组测试用） |

> 权限说明：`/season` 管理子命令用 COMMANDS_ADMIN（原 OP 等级 2），`/samsara debug` 用 COMMANDS_OWNER（最高权限）。
> 26.1 API 要点：类名用 Mojang 官方名（`net.minecraft.commands.CommandSourceStack` / `Commands` / `CommandBuildContext` / `net.minecraft.network.chat.Component`），Fabric API 的 `CommandRegistrationCallback` 未改名。
