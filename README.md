# samsara · 周目管理

> 原版红石生存服务器的**世界生命周期管理系统**（Fabric 服务端模组 + 数据包 + 离线工具）

三大主题：**空间**（区块圈地/保存/重生成）· **时间**（周目轮回/新出生点）· **逆熵**（时间倒流/方块级恢复）

## 当前状态

**v0.9.1** · MC **26.2** · Fabric Loader **0.19.3** · Java **25**

> 下表只给**模块级**状态。**逐条**差额（未实现 / 未达标 / 文档陈述与代码不符，含 `F-01`~`F-63` 全表对照）
> 见 [`docs/实现状态.md`](docs/实现状态.md)——那是本仓库唯一的"做到哪一步"的权威来源。

| 功能 | 状态 | 说明 |
|---|---|---|
| 骨架 | ✅ | fabric-loom 项目 + tools/analyze.py |
| 周目状态机 | ✅ | season.json 持久化 + 状态流转 |
| 分组出生 + 时间倒流 | ✅ | 出生中心计算 + 分组算法 + 方块级倒流 + 完成即删 |
| 活跃度 | ✅ | activity.log + 统计聚合 + 玩家分类 |
| 区块管理 | ⚠ 部分 | **化线为面已接入生产路径**（border/cross→实心正方形、straight_cross→实心菱形）+ 旗帜圈地（15 种图案、8 方向延伸）+ 安全删除 + 粒子预览；**图标图案判定、整档 zip 备份未达标** |
| 安全删除增强 | ✅ | 批量优化 + 白名单索引 + 旗帜归属权 + 边界持久化 |
| 权限系统 | ✅ | 4 级权限 + 命令可配置等级 + owner 名单 |
| 命令短别名 | 🔒 未实现 | 设计要求全量别名 + 冲突谦让；现仅 `/mspt`、`/tps` |
| 世界栈 | 🔒 未实现 | new-world 模式世界压栈 + 跨世界传送 |
| 全员同意制 | 🔒 未实现 | 在线玩家全员同意开新周目 |
| 身份同步 | 🔒 未实现 | 正版/离线 UUID 映射 + 密码保护 + 迁移 |
| TPA 传送 | 🔒 未实现 | `/tpa` `/tpahere` `/tpaccept` `/tpdeny` `/tpcancel` |
| Web 面板 | 🔒 远期 | 独立项目：Web 地图 + 玩家数据 + 管理可视化 |

## 命令结构

> **别名**：设计约定所有命令均提供去掉 `/samsara ` 前缀的短别名并做冲突谦让
> （见 [`docs/设计文档.md`](docs/设计文档.md)、[`docs/decisions/005`](docs/decisions/005-命令谦让策略.md)）。
> **现行代码只注册了 `/mspt`、`/tps` 两个别名**，其余别名尚未实现——命令请用 `/samsara` 全名。

### 玩家命令（正常游玩使用）

```
/samsara season status [页]        周目状态（附活跃度段落）
/samsara season info               世界种子与出生点
/samsara claim info [x] [z]        查看区块圈地信息
/samsara claim boundary            切换边界持续显示
/samsara activity [recent|stats|dead]  活跃度查询
/samsara version                   模组版本号
```

### 管理员命令（/samsara debug ...，OWNER 权限）

```
/samsara debug claim give <dir> <dist>   给予圈地旗帜
/samsara debug claim list                列出所有圈地旗帜
/samsara debug claim rebuild             重算白名单
/samsara debug claim scan                扫描世界中的圈地旗帜
/samsara debug claim remove <x> <z>     移除圈地节点
/samsara debug claim info <x> <z>       查看指定区块详情
/samsara debug claim particle ...        粒子配置
/samsara debug claim delete dryrun|backup|execute|report  安全删除
/samsara debug season|activity ...       周目/活跃度调试
/samsara debug status|json|config|...    状态与配置
/samsara debug fakeplayers|groups        测试数据注入与分组预览
/samsara debug rewind|diff|flow          倒流与差异控制
/samsara debug perm                      权限管理
```

## 仓库布局

```
samsara/
├── mod/          Fabric 服务端模组（fabric-loom，mod id: samsara）
│   └── src/main/java/com/shenqianye/samsara/
│       ├── Samsara.java          主入口（server entrypoint + tick/停服/加入事件钩子）
│       ├── DebugToggle.java      全局调试输出开关
│       ├── command/               命令（Season/Debug/Claim/Activity/ChatFormat）
│       ├── season/                周目模型与状态机 + 分组出生 + 玩家名册
│       ├── claim/                 旗帜圈地 + 安全删除 + 粒子
│       ├── geometry/              化线为面几何库（无 MC 依赖，可单测）
│       ├── permission/            权限等级系统（CONFIG/OWNER/OP/USER）
│       ├── activity/              活跃度管理
│       ├── rewind/                时间倒流引擎 + diff 差异库 + MCA 区域文件操作
│       └── mixin/                 Level.setBlock 注入（方块变化记录）
├── tools/        离线分析脚本（analyze.py，零依赖）
├── datapack/     数据包（⚠ 目前只有 pack.mcmeta，functions 尚未编写）
├── docs/         设计文档（需求 / 设计 / 规范 / 实现状态 / ADR）
└── test-server/  生产模式测试服务器（可双击启动）
```

## 环境要求

- **JDK 25**（MC 26.2 硬性要求）
- Gradle 9.5.1（wrapper 已内含）
- Python 3（analyze.py 零依赖，仅标准库）

版本锁定值（`mod/gradle.properties`）：

| 属性 | 值 | 说明 |
|---|---|---|
| `minecraft_version` | `26.2` | 26.2 为"去混淆"版本：yarn 映射已停止维护，改用 Mojang 官方映射，构建脚本里**没有** `yarn_mappings`，loom 1.17 起自动处理 |
| `loader_version` | `0.19.3` | Fabric Loader 稳定版（26.1/26.2 通用） |
| `fabric_version` | `0.157.0+26.2` | Fabric API |
| `loom_version` | `1.17-SNAPSHOT` | fabric-loom（需 Gradle 9.x） |
| `java_version` | `25` | 26.2 要求 Java 25 |

## 构建

本机默认 `JAVA_HOME` 指向 JDK 8，**每次构建前必须改**：

```bat
cd mod
set JAVA_HOME=C:\Program Files\Java\jdk-25
gradlew.bat build
```

产物：`mod/build/libs/samsara-<version>.jar` → 放入服务器 `mods/`

## 加载方式（不要改成 server-only）

`fabric.mod.json` 采用 `environment: "*"` + `"server"` entrypoint（`Samsara` 实现 `DedicatedServerModInitializer`）：

| 场景 | 行为 |
|---|---|
| 纯服务器（dedicated） | ✅ 全功能 |
| 客户端安装 + 单人存档 | ✅ 全功能 —— fabric-loader 的 `EntrypointPatch` 会在 integrated server（单人=客户端进程内的逻辑服务器）启动时调用 `"server"` entrypoint |
| 客户端安装 + 连接他人服务器 | 模组被加载但 server entrypoint 不执行（无逻辑服务器），零副作用；功能由服务端提供 |

> ⚠ **不要改成 `environment: "server"`**：那会让模组在客户端进程（含单人）中被过滤不加载，单人存档将不可用。
> 依据：fabric-loader 0.19.3 的 `ModResolver` 按物理进程 EnvType 过滤模组，`EntrypointPatch` 负责单人时调用 server entrypoint。

## 消息格式

所有命令返回使用 `ChatFormat` 统一格式化：
- 首行 `[周目管理] ▸ 消息内容`，后续行 `  ▸ 消息内容` 缩进对齐
- 自动换行：CJK 字符按 2 宽度、ASCII 按 1 宽度，默认 34 宽度单位
- 调试命令使用 `[周目管理·调试] ▸` 前缀

## 粒子配置

圈地边界粒子支持独立配置（`/samsara debug claim particle ...`）：
- 昼间/夜间边界粒子、字体粒子、光束粒子
- 共享显示 / 全图显示开关
- 可用粒子：crit, enchant, smoke, large_smoke, flame, soul_fire_flame, end_rod, happy_villager, composter, portal, witch, cloud, note, heart, angry_villager, snowflake, ash, white_ash

## 数据文件

模组一切数据存放在 `config/samsara/`（**禁止写入存档目录 world/**）：

```
config/samsara/
├── season.json            周目列表 + Settings
├── claims.json            旗帜圈地节点（含 owner，多维度）
├── geometry.json          化线为面连通区域缓存
├── rewind.json            进行中的倒流（走完归档至 archive/）
├── pending-delete.json    待删队列（卸载即删）
├── pending-teleports.json 离线玩家补传送
├── permissions.json       命令权限等级配置
├── boundary-toggles.json  边界显示开关持久化
├── claim-particles.json   边界粒子配置（昼夜/字体/光束）
├── activity.log           玩家上下线事件日志（≥10 万行滚为 activity.1.log）
├── debug-delete.log       安全删除调试日志（debug 开启时写）
├── diff/                  方块变化差异库
│   ├── current/           常开记录窗口
│   └── s<赛季id>/         冻结恢复源
├── archive/               归档的 rewind.json
└── backups/               备份目录（purge_* / compact_* / delete_*）
```

> **硬约束**：模组一切数据与备份一律在 `config/samsara/`，**禁止写入存档目录 `world/` 与服务端根目录**
> （见 [`docs/设计规范.md`](docs/设计规范.md) 的「文件布局：模组数据与备份位置」）。

## 相关文档

| 文档 | 定位 |
|---|---|
| [`docs/需求文档.md`](docs/需求文档.md) | 做什么——功能需求、用户故事、验收标准（`F-01`~`F-63`） |
| [`docs/设计文档.md`](docs/设计文档.md) | 怎么做——技术架构、数据模型、算法、命令设计 |
| [`docs/设计规范.md`](docs/设计规范.md) | 代码规范——命名、格式、API 约定、实现约束 |
| [`docs/实现状态.md`](docs/实现状态.md) | 做了多少——未实现 / 未达标 / 文档与代码不符登记册 |
| [`docs/decisions/`](docs/decisions/) | 为什么——设计决策记录（ADR-001 ~ 006） |
| [`CHANGELOG.md`](CHANGELOG.md) | 变更记录（按里程碑） |

> 文档引用一律使用「文档名 + 章节标题」，不使用章节编号（编号会在文档重构时漂移失效）。
