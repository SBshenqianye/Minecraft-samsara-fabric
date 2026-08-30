# samsara · 周目管理

> 原版红石生存服务器的**世界生命周期管理系统**（Fabric 服务端模组 + 数据包 + 离线工具）

三大主题：**空间**（区块圈地/保存/重生成）· **时间**（周目轮回/新出生点）· **逆熵**（时间倒流/方块级恢复）

## 当前状态

**v0.9.1** · MC **26.2** · Fabric Loader **0.19.3** · Java **25**

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 骨架 | ✅ | fabric-loom 项目 + tools/analyze.py |
| M1 周目状态机 | ✅ | season.json 持久化 + 状态流转 |
| M2 分组出生 + 时间倒流 | ✅ | 出生中心计算 + 分组算法 + 档2/档3 倒流 + 完成即删 |
| M3 活跃度 | ✅ | activity.log + 统计聚合 + 玩家分类 |
| M4 区块管理 | ✅ | 化线为面几何库 + 旗帜圈地 + 安全删除 + 粒子预览 |
| M4d 安全删除增强 | ✅ | 批量优化 + 白名单索引 + 旗帜归属权 + 边界持久化 |
| 权限系统 | ✅ | 4 级权限 + 命令可配置等级 + owner 名单 |
| M7 世界栈 | 🔒 预留 | new-world 模式世界压栈/跨世界传送 |
| M8 全员同意制 | 🔒 预留 | 在线玩家全员同意开新周目 |
| M9 身份同步 | 🔒 预留 | 正版/离线 UUID 映射 + 迁移 |
| M5 Web 面板 | 🔒 远期 | 独立项目：Web 地图 + 玩家数据 + 管理可视化 |

## 命令结构

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
│       ├── Samsara.java          主入口
│       ├── command/               命令（Season/Debug/Claim/Activity/ChatFormat）
│       ├── season/                周目模型与状态机
│       ├── claim/                 旗帜圈地 + 安全删除 + 粒子
│       ├── geometry/              化线为面几何库
│       ├── permission/            权限等级系统
│       ├── activity/              活跃度管理
│       ├── rewind/                时间倒流引擎
│       └── whitelist/             配置白名单
├── tools/        离线分析脚本（analyze.py，零依赖）
├── docs/         设计文档
└── test-server/  生产模式测试服务器（可双击启动）
```

## 环境要求

- **JDK 25**（MC 26.2 硬性要求）
- Gradle 9.5.1（wrapper 已内含）
- Python 3（analyze.py 零依赖，仅标准库）

## 构建

```bat
cd mod
set JAVA_HOME=C:\Program Files\Java\jdk-25
gradlew.bat build
```

产物：`mod/build/libs/samsara-<version>.jar` → 放入服务器 `mods/`

## 消息格式

所有命令返回使用 `ChatFormat` 统一格式化：
- 首行 `[周目管理] ▸ 消息内容`
- 后续行 `  ▸ 消息内容` 缩进对齐
- 自动换行：CJK 字符按 2 宽度、ASCII 按 1 宽度，默认 34 宽度单位
- 调试命令使用 `[周目管理·调试] ▸` 前缀

## 消息格式

所有命令返回使用 `ChatFormat` 统一格式化：首行 `[周目管理] ▸ 消息内容`，后续行缩进对齐，自动换行。调试命令用 `[周目管理·调试] ▸` 前缀。

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
├── whitelist.json         配置白名单
├── rewind.json            进行中的倒流（走完归档至 archive/）
├── pending-delete.json    待删队列（卸载即删）
├── pending-teleports.json 离线玩家补传送
├── permissions.json       命令权限等级配置
├── boundary-toggles.json  边界显示开关持久化
├── activity.log           玩家上下线事件日志
├── diff/                  方块变化差异库
│   ├── current/           常开记录窗口
│   └── s<赛季id>/         冻结恢复源
├── archive/               归档的 rewind.json
└── backups/               备份目录
```

## 相关文档

- [`docs/设计文档.md`](docs/设计文档.md) — 功能规格与技术设计（面向开发者）
- [`docs/设计规范.md`](docs/设计规范.md) — 代码规范与实现约定
- [`CHANGELOG.md`](CHANGELOG.md) — 变更记录
- [`HANDOVER.md`](HANDOVER.md) — 历史交接快照（已归档，以新文档为准）
