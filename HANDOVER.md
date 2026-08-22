# samsara（周目管理）模组 — 交接文档

> 交接日期：2026-08-22 ｜ 版本：v0.8.9（权限系统 + M4 完整 + 优雅停服）
> 交接人：AI 开发助手 ｜ 接收人：项目维护者

---

## 1. 项目概况

- **是什么**：Minecraft Fabric 服务端模组，实现"轮回周目"系统——每个周目（赛季）自动计算新出生中心、玩家分组出生、世界时间倒流（区块波浪退场 + 方块级恢复）、圈地保护。
- **技术栈**：Minecraft **26.2**（去混淆 Mojang 官方映射，无 yarn）、Fabric Loader 0.19.3、fabric-api 0.157.0+26.2、fabric-loom 1.17、Java 25、Gradle 9.5.1。
- **代码位置**：`mod/src/main/java/com/shenqianye/samsara/`（约 21 个 Java 文件）
- **设计文档**：`docs/` 目录（含 §2b 坐标规范、§10 删区块时机决策、§12 档3 倒流设计）

---

## 2. 核心功能（全部完成并验证）

| 功能 | 说明 | 状态 |
|---|---|---|
| **周目状态机** | PENDING → ACTIVE → ENDED / SKIPPED，season.json 持久化 | ✅ |
| **出生中心计算** | 新中心距**全部历史中心集合**均 ≥15000（可配 15000~40000） | ✅ |
| **分组出生** | 3~5 人/组（可配），组间距 ≥1000，组内抖动 40 格 | ✅ |
| **全员传送** | `/season start` 倒流完成后传送全员 + 离线玩家上线补传送 | ✅ |
| **档3 倒流** | 区块波浪退场（时间表驱动）+ 方块级逐格恢复（diff 差异库） | ✅ |
| **物理删除** | 倒流完成**即自动删除**未圈地区块（备份 + 卸载 + 磁盘清零，不等停服） | ✅ |
| **圈地保护** | `/samsara debug claim` 维护，保护集只取决于圈地 | ✅ |
| **M4a 几何库** | 化线为面（rasterize→graph→face→scanline→union），16 个单测全绿 | ✅ |
| **M4b 旗帜圈地** | 放置/破坏自动注册/移除，8 个 claim 子命令，双层粒子预览 | ✅ |
| **M4c 安全删除** | dryrun/backup/execute/report，按白名单保护 | ✅ |
| **中文名** | season/claim 名称用引号支持中文（`string()` 参数） | ✅ |
| **坐标语法糖** | 所有坐标参数用原版类型支持 `~`/`~N`（BlockPosArgument/ColumnPosArgument/Vec2Argument） | ✅ |
| **调试拆分** | `/samsara debug flow setup\|rewind\|teleport` 拆分 `/season start` | ✅ |
| **空中字画** | `diff text` 5×5 点阵字画，注入即显示，倒流保持 | ✅ |
| **性能** | diff 写入内存缓冲批量落盘；恢复走内存缓存 + 预算分摊 | ✅ |

---

## 3. 架构与关键文件

```
com/shenqianye/samsara/
├── Samsara.java              入口：注册命令、tick 驱动倒流、停服兜底删除、JOIN 补传送
├── command/
│   ├── SeasonCommand.java    /season 系列（create/start/end/skip/status/...）
│   ├── DebugCommand.java     /samsara debug 系列（调试全功能）
│   ├── ClaimCommand.java     /samsara claim 系列（give/list/info/scan/rebuild/preview/remove/export）
│   └── ActivityCommand.java  /samsara activity 系列（recent/stats/dead）
├── activity/
│   └── ActivityManager.java  活跃度管理（activity.log 读写/统计/死服判定/玩家分类）
├── geometry/
│   ├── Geometry.java         化线为面算法（rasterize→graph→face→scanline→union）
│   └── ChunkCoord.java       整数区块坐标 + 8 方向邻居 + 方向索引
├── claim/
│   ├── Extension.java        15 种图案→8 方向映射 + 旋转 + 颜色强度
│   ├── ClaimNode.java        旗帜节点（位置+朝向+图案→影响坐标）
│   ├── AreaService.java      圈地服务（单例，白名单惰性重算，claims.json 持久化）
│   ├── ClaimInteractions.java 右键交互 + 边界粒子预览 + 工具方法
│   ├── ClaimParticles.java   手持旗帜粒子预览（双层 Y 轴，停服自动停止）
│   ├── ClaimPlacementListener.java 放置自动注册
│   ├── ClaimBreakListener.java    破坏自动移除
│   └── SafeDeletion.java     安全删除（dryrun/backup/execute/report）
└── permission/
    ├── PermissionLevel.java  等级枚举（CONFIG > OWNER > OP > USER）
    ├── PermissionConfig.java 命令→等级映射持久化 + owner 配置
    └── PermissionChecker.java 玩家等级判定 + 命令检查
├── season/
│   ├── SeasonManager.java    状态机核心（setup/start/rewind/teleport/end/config）
│   ├── Season.java           Season 模型 + State 枚举
│   ├── SeasonData.java       Settings（所有可配项）+ 持久化
│   ├── SpawnCenterCalculator.java  新出生中心（历史中心集合约束）
│   ├── GroupPlanner.java     分组算法（3~5 人、组距、抖动）
│   ├── PlayerRegistry.java   注册玩家名单
│   └── DurationUtil.java     时长解析（1h/30m/3600s/1d/0.5d）
├── rewind/
│   ├── RewindEngine.java     倒流引擎：时间表/进度/tick/物理删除
│   ├── RewindData.java       倒流持久化（rewind.json）
│   ├── Tier3Restore.java     档3 逐格恢复调度（懒加载 + 预算分摊）
│   ├── DiffStore.java        diff 差异库（内存缓存 + 批量落盘）
│   ├── DiffRecorder.java     记录器 + 5×5 点阵字库 + fakeDiff/fakeDiffText
│   └── McaRegion.java        .mca 区域文件读写/备份/清零
├── whitelist/
│   ├── WhitelistStore.java   圈地持久化（whitelist.json）
│   ├── WhitelistData.java    圈地模型
│   └── ChunkProtector.java   保护判定（只依赖圈地）
└── mixin/
    └── ServerLevelMixin.java setBlock 记录 diff（HEAD 注入）
```

### 数据文件（config/samsara/）
- `season.json` — 周目列表 + Settings 配置
- `whitelist.json` — 圈地
- `rewind.json` — 进行中的倒流状态（完成后自动归档至 `config/samsara/archive/`，不删除；中止时清除）
- `diff/s<赛季id>/` — 每区块 diff 记录（24 字节/条：stateId+x+y+z+timeMs）

---

## 4. 命令手册

### 玩家命令（全部以 /samsara 开头，无别名，设计文档 §17）
```
/samsara season create <名> [模式]   # 创建周目（PENDING；模式 same-world/new-world）
/samsara season start                # 完整流程：setup→倒流→传送→删旧区
/samsara season end                  # 结束当前 ACTIVE
/samsara season skip                 # 跳过 PENDING
/samsara season status [页]          # 查看（附活跃度段落）
/samsara activity [recent [天数]|stats|dead]  # 活跃度（全员）
/samsara claim give <dir> <dist>     # 获取圈地旗（dir=n/ne/e/se/s/sw/wnw/all，dist=1~16）
/samsara claim list                  # 列出所有圈地区域
/samsara claim info [x z]            # 查看某位置详情
/samsara claim scan                  # 扫描世界中所有圈地旗帜
/samsara claim rebuild               # 重新计算白名单
/samsara claim preview               # 预览保护区域
/samsara claim remove <x> <z>        # 移除圈地节点
/samsara claim export                # 导出圈地数据
```

### OP 调试命令（玩家名须为 xh_unpkire）
```
/samsara debug flow setup               # 只准备（中心+分组+ACTIVE）
/samsara debug flow rewind <true|false> # 只倒流（完成后是否传送）
/samsara debug flow teleport            # 只传送
/samsara debug diff                     # diff 库状态（记录窗口/恢复源）
/samsara debug diff fake <pos> <数量> [stateId]   # 注入虚假 diff（区块，坐标支持~）
/samsara debug diff text <pos> <文字> [stateId] [scale]  # 空中字画（5×5 点阵）
/samsara debug rewind preview|status|abort|purge|compact|restore <pos>|set <时长>
/samsara debug claim add|addc <名> <p1> <p2>|remove <名>|list
/samsara debug delete dryrun|backup|execute|report   # 安全删除（M4c）
/samsara debug activity recent|stats|dead|simulate|clear  # 活跃度调试
/samsara debug perm list|set|reset|info|owner  # 权限管理
/samsara debug config [键] [值]         # groupdist/birthjitter/rewind/groupmin/deaddays/deadactive/...
/samsara debug whitelist json|reload
/samsara debug fakeplayers / groups / create / settime / reload / reset / spawninfo
```
**坐标一律支持 `~`/`~N`**（相对玩家）；区块坐标命令（fake/restore）自动 ÷16 转区块。

---

## 5. 测试环境

- **服务器目录**：`test-server/`（Fabric 服务端 26.2，端口 25565，RCON 25575）
- **RCON**：密码 `samsara-m1-test`，客户端 `RconClient.class`（小端序，串行发多条命令）
- **OP 玩家**：`xh_unpkire`（offline 模式，名字精确匹配）
- **启动**：`test-server/start-server.bat` 或 `java -jar fabric-server-launch.jar nogui`
- **配置**：`rewind=30s`（测试用）、组距 1000、中心距 15000~40000、半径 1500、抖动 40
- **备份策略**（用户要求，2026-08-17 起自动清理防磁盘膨胀）：`start-server.bat` 启动备份自动保留**初始 1 + 最新 2**；模组 `purge_*`/`compact_*` 各自动保留最新 **1** 份。当前保留：
  - `backups/world_20260816_040401`（初始固定）
  - `backups/world_20260816_165648`（流水）
  - `backups/world_20260816_224131`（流水最新）
- **purge 备份**：`config/samsara/backups/purge_<时间戳>/`（倒流删除前的区块备份，可回滚；不落存档目录，设计规范 §15）

### 开发环境
- JDK25：`C:\Users\Administrator\AppData\Local\Temp\dsh-Ln5i7u\jdk25\jdk-25.0.4+7`
- Gradle home：`dsh-YYFeCv\gradle-home`，命令：`gradle-9.5.1/bin/gradle.bat build --offline`
- **部署流程（重要）**：使用 `.dsh/skills/mc-build-deploy-test.md` 流水线（编译→部署→重启→RCON 验证→文档更新）。手动步骤：先停服 → 复制 `mod/build/libs/samsara-*.jar` 到 `test-server/mods/` → 启动。**部署后必须重启服务器**（Fabric mod jar 替换不热加载 mixin/事件注册）。

---

## 6. 已知问题 / 遗留事项

1. **RCON 中文传输乱码**（工具限制，游戏内正常）：`RconClient` 传中文会拆段报错，验证中文用游戏内。
2. **区块删除后重生成 ≠ 创建时地形**：MC 特性（邻接依赖/结构规划），非 bug；要完全还原用初始备份。
3. **`restoreChunkNow` 对远离出生点的区块**：强制 getChunk 后 setBlock 在 26.2 中仍可能受限（"not loaded"），但玩家在线视距内正常；字画注入已内置强制加载可绕过。
4. **（已修复 2026-08-17）多人 ACTIVE 残留**：create/setup 已拦截双 ACTIVE；现存残留可用 `/season end` 清理。
5. **（已修复 2026-08-17）多人 ACTIVE 残留**：create/setup 已拦截双 ACTIVE；现存残留可用 `/season end` 清理。
6. **旗帜圈地系统**：已实现 M4b（2026-08-22），`/samsara claim` 正式命令 + debug claim 调试入口并存。
7. **Intel Arc B580 客户端崩溃**：服务器 `stop` 时客户端可能原生崩溃（exit code -1，无 Java 堆栈），已通过停服钩子缓解；根本解决需更新驱动。
8. **`restoreChunkNow` 对远离出生点的区块**：强制 getChunk 后 setBlock 在 26.2 中仍可能受限（"not loaded"），但玩家在线视距内正常；字画注入已内置强制加载可绕过。
9. **Tier3Restore 无玩家在线时不恢复**（collectSpawningChunks 为空）——设计如此（懒加载），玩家在视距内才恢复；restore 命令可强制；无加载区块时视为恢复完成并归档。

---

## 7. 验收记录（关键测试结果）

- 空中字画注入即显示：8/8 像素点验证通过（服务器日志 `[Not Secure] [Rcon]`）
- 性能：5.3 万条 diff 注入 **234ms**（修复前卡死 75s）；恢复 2.1 万格 **1086ms** 零卡顿
- 倒流完成自动删除：`36908 个已标记区块清零（备份至 purge_20260816_212550）`，无需停服
- 坐标语法糖：`diff text ~ ~ ~ SAM` → (0,71,0)、`~5 ~10 ~5` → (5,81,5) 全部验证通过
- **2026-08-17 文件级回收实测**：`compact` 扫描 1060 个 .mca → 全空删除 862 个 + 压缩重写 20 个 → **释放 1,754 MB**（world 4,355 → 2,646 MB，region 384 → 13 个文件）

---

## 8. 2026-08-17 修复批次（未提交，工作树改动）

| 项 | 内容 |
|---|---|
| **diff 快照语义** | 记录常开（diff/current）；倒流启动冻结为恢复源 diff/s<N>（窗口=上次删块后至本次倒流开始）；完成时重置窗口；恢复读恢复源 → 倒流期间新改动不被本轮倒流 |
| **待删队列** | 删除时仍加载的区块入 `pending-delete.json`（卸载即删；启动/停服兜底）；入队/删除前复查圈地；幂等（chunkExists） |
| **文件级回收** | 清零后全空 .mca 直接整文件删除；`/samsara debug rewind compact` + 停服窗口自动压缩重写（备份至 `config/samsara/backups/compact_<ts>/`） |
| **归档** | 倒流"走完"（传送完成 && 恢复完成）后 rewind.json 归档至 `config/samsara/archive/`（不删除）；中止清除且不删区块 |
| **异步删除** | 备份+清零在后台线程执行；停服窗口 join 后执行 compact |
| **M1 术语** | "已到点"→"已到倒流时刻/待退场"；abort 文案与实际一致（中止=全保留） |
| **M2** | 禁止双 ACTIVE（create/setup 拦截） |
| **M3** | 无可倒流区块时 `/season start` 回退直接传送，不卡 ACTIVE |
| **L1-L8** | 组距文案动态/自适应散布、pending-teleports 持久化+清空再填充、fakeplayers 自动清空、死字段移除、rewind.json 落盘节流、恢复预算实际推进位置（不断档）、恢复在倒流结束后继续到完成 |
| **文档** | `docs/设计规范.md` 新增 §11 diff 窗口、§12 待删队列/术语/文件级回收、§13 状态机约束、§14 其它约定 |

**部署**：已部署 test-server（PID 随启动变化，端口 25565/25575）；备份 `backups/world_20260816_224131`；当前 #63 仍 ACTIVE（测试残留，可用 `/season end` 清理）。

---

## 9. 交接检查清单

- [ ] 工作树有未提交改动（2026-08-17 修复批次，见 §8）
- [x] 服务器运行中（25565/25575）
- [x] 赛季状态：#63 ACTIVE 残留（测试后需清理）
- [x] 备份：初始 1 + 流水 2 + 20260816_224131，磁盘空余 ~17GB
- [x] 最近 3 个 commit：`bb3b963`（交接文档）、`1329bcb`（自动删除）、`61269cd`（性能）

---

## 10. M3 活跃度（2026-08-18 实现并验收，v0.8.8）

**设计文档 §5 + §17.3 M3 预留兑现**；实现细节见 `docs/设计规范.md` §16。

- **数据文件**：`config/samsara/activity.log`（主数据源）。格式 `epoch_ms 玩家名 JOIN|LEAVE`，实时追加；行数 ≥100000 滚为 `activity.1.log`（覆盖）防膨胀；`tools/analyze.py` 复读（只取 JOIN，兼容两种目录位置 + 滚动档）。
- **钩子**：`Samsara.java` JOIN → `onJoin(name)`、DISCONNECT → `onLeave(name)`（`GameProfile.name()`，26.2 无 getName）。
- **指标**：近 7/14/30 天活跃玩家数、日均在线、峰值在线（事件重放）、近 30 天新见玩家；玩家分类 活跃≤7 / 回归 8~30（在线≥10 分）/ 流失 31~60 / 沉睡 >60。
- **死服判定**（建议，可配 `deaddays` 默认 14 / `deadactive` 默认 2）：连续 ≥N 天 0 人上线 且 近30天活跃 ≤M → 判「周目已死」建议开新周目；否则分「存活/接近死服」。
- **命令**：
  - `/samsara activity [recent [天数]|stats|dead]`（全员；recent 默认近 30 天）
  - `/samsara debug activity recent|stats|dead|simulate <天数前> [近30天活跃]|clear`（OWNER；simulate 不写文件，clear 需二次确认）
  - `/samsara season status` 末尾附紧凑活跃度段落（7/14/30天/峰值/日均/死服标记）
  - `/samsara debug config` 新增 `deaddays`/`deadactive`
- **新增/修改文件**：`activity/ActivityManager.java`（新）、`command/ActivityCommand.java`（新）、`SeasonData.Settings`（+deadDays/deadActiveMax）、`SeasonManager`（config 键 + statusText 段落 + setter）、`DebugCommand`（+activity 子树与帮助）、`Samsara.java`（+钩子）、`tools/analyze.py`（读新格式）。
- **验收（test-server 26.2 / RCON，2026-08-18）**：
  - 构造 18 条历史事件 → `stats` 输出正确：7/14/30 天活跃 3/3/4、日均 13 分、峰值 3、新见 4/共 6；分类 Bob/Alice/Carol 活跃、Dave 回归、Eve 流失、Frank 沉睡 ✓
  - `season status` 附「活跃度: 7/14/30天 3/3/4 人 · 峰值 3 · 日均 13 分 · 近1天有登录」✓
  - `simulate 14 2` → 死服；`simulate 7 3` → 存活；`simulate 20 1`/`300 1` → 死服 ✓
  - `clear` 二次确认 → 清空 → `stats` 返「尚无活动记录」✓
  - 空日志时 `recent/stats/dead/season status` 全部优雅降级 ✓
  - `analyze.py` 解析 JOIN 行正确（Alice..Frank 时间戳按天偏移 ✓）
- **部署**：samsara-0.8.8.jar（133129B）已复制到 `test-server/mods/`，当前运行中（25565/25575）。测试用的 activity.log 已清空，从部署起重新累计。
- **已知注意**：RCON 传输中文在控制台显示乱码（工具限制，游戏内正常）；activity.log 里玩家名即游戏名（离线模式）。
- **git**：工作树改动未提交（本次 M3 批次）。

> 下个里程碑：M5（Web 面板，可选）。

---

## 11. M4 区块管理（2026-08-22 实现并验收，v0.8.8）

**设计文档 §6 + §17.3 M4b/M4c 兑现**；实现细节见 `docs/设计规范.md`。

### M4a 化线为面几何库

- **文件**：`geometry/Geometry.java`（rasterize→graph→face traversal 半边→scanline fill→union）、`geometry/ChunkCoord.java`（整数坐标 + 8 方向邻居 + 方向索引 `dx+3*dz`）
- **单测**：`geometry/GeometryTest.java`（16 个测试全绿，§6.3.4 用例）、`geometry/GeometryDebug.java`（调试追踪工具）
- **算法**：栅格化线段→构建邻接图→面遍历（半边，稀疏图支持）→扫描线填充→并集去重
- **已知修复**：方向索引公式（`1→East, -2→NE, -3→North...`）、扫描线填充假对角线（传入真实邻接）、重复面去重（排序点集 key）

### M4b 旗帜圈地

- **核心文件**：
  - `claim/Extension.java` — 15 种 MC 26.2 图案→8 方向映射（全名 `stripe_top` 等）、颜色→强度、45° 旋转
  - `claim/ClaimNode.java` — 位置+朝向+图案列表，延迟计算旋转后扩展 + 影响坐标集
  - `claim/AreaService.java` — 单例，`ConcurrentHashMap<ChunkCoord, ClaimNode>`，`volatile Set<ChunkCoord> whitelist`，惰性重算（`dirty` 标记），`claims.json` 持久化
  - `claim/ClaimInteractions.java` — `UseBlockCallback` 右键交互：聊天详情 + 边界粒子（防抖 20 tick）、`sendBoundaryParticles()` 沿朝外边每 4 格放置 END_ROD
  - `claim/ClaimParticles.java` — `ServerTickEvents`，手持旗帜时扫描 32 格内圈地旗，双层 Y 轴粒子（玩家头顶 + 旗帜高度），空手不显示，停服自动停止
  - `claim/ClaimPlacementListener.java` — 放置含方向图案的旗帜 → 自动注册 `AreaService.addNode()`
  - `claim/ClaimBreakListener.java` — 破坏含方向图案的旗帜 → 自动 `AreaService.removeNode()`（`PlayerBlockBreakEvents.BEFORE`）
  - `command/ClaimCommand.java` — 8 个子命令：`give <dir> <dist>` / `list` / `info [x z]` / `scan` / `rebuild` / `preview` / `remove <x> <z>` / `export`
- **MC 26.2 API 关键点**：`Items.BANNER.white()` + `DataComponents.BANNER_PATTERNS` + `BannerPatternLayers` + `Holder.direct(BannerPattern)`；`Identifier.fromNamespaceAndPath()`；`BlockStateProperties.ROTATION_16`/`FACING`；`ItemTags.BANNERS` 检测手持物品
- **命令**：`/samsara claim give/list/info/scan/rebuild/preview/remove/export`（§17.3 已登记 ✅）

### M4c 安全删除

- **文件**：`claim/SafeDeletion.java` — dryrun（扫描已加载区块，报告非白名单）、backup（复制 .mca 文件）、execute（仅报告，安全约束 §6.6）
- **调试入口**：`/samsara debug delete dryrun|backup|execute|report`

### 粒子修复（2026-08-22）

- **Bug 1**：空手靠近也显示粒子 → 修复：`player.getMainHandItem().is(ItemTags.BANNERS)` 过滤
- **Bug 2**：粒子在同格高度看不清 → 修复：Y = `player.getY() + player.getBbHeight() + 1.0`（头顶上方）
- **Bug 3**：旗帜高度无粒子 → 修复：新增第二层 Y = `entry.getKey().getY() + 1.0`（两层差 ≤2 格时去重）

### 优雅停服（2026-08-22）

- `ClaimParticles.shutdown()` + `SERVER_STOPPING` 钩子：停服时停止粒子发包 + 发送告别消息
- 解决 Intel Arc B580 驱动在断连时因渲染线程清理导致的原生崩溃（exit code -1）

### 验收（test-server 26.2 / RCON + 客户端，2026-08-22）

- 几何库 16 个单测全绿 ✓
- 旗帜放置→自动注册→右键显示详情 ✓
- 旗帜破坏→自动移除节点 ✓
- 手持旗帜粒子预览：双层 Y 轴 ✓；空手无粒子 ✓
- `/samsara claim give/list/info/scan/rebuild/preview/remove/export` 全部可用 ✓
- 安全删除 dryrun/backup/report ✓

### 已知注意

- Intel Arc B580 驱动（32.0.101.8724）在服务器 `stop` 时客户端可能原生崩溃（exit code -1，无 Java 堆栈），已通过停服钩子缓解；根本解决需更新驱动
- RCON 传输中文在控制台显示乱码（工具限制，游戏内正常）
- MC 26.2 `ChunkPos` 是 Record，字段私有，用 `x()`/`z()` 访问器

---

## 12. 权限等级系统（2026-08-22 实现并验收）

### 功能概述

为所有 `/samsara` 命令提供可配置的权限等级控制：
- 4 个等级：CONFIG（最高）> OWNER > OP > USER（最低）
- 每个命令有默认权限等级，owner 可通过 `/samsara debug perm set` 自定义
- 只能设置 ≤ 自己等级的命令（不能越级提权）
- owner 名单可配置（通过 `/samsara debug perm owner add/remove`）

### 新增文件

- `permission/PermissionLevel.java` — 等级枚举 + 比较
- `permission/PermissionConfig.java` — 加载/保存/查询 + owner 配置，持久化至 `config/samsara/permissions.json`
- `permission/PermissionChecker.java` — 玩家等级判定（控制台=CONFIG, owner名单=OWNER, OP≥COMMANDS_MODERATOR=OP, 其他=USER）

### 命令

- `/samsara debug perm list` — 查看所有命令权限配置
- `/samsara debug perm set <command> <level>` — 设置命令权限等级
- `/samsara debug perm reset <command>` — 重置为默认等级
- `/samsara debug perm info <command>` — 查看单个命令权限
- `/samsara debug perm owner add|remove <name>` — 管理 owner 名单

### 修改文件

- `SeasonCommand.java` — 根节点添加 `.requires(PermissionChecker.check(...))`
- `ClaimCommand.java` — 根节点添加 `.requires(PermissionChecker.check(...))`
- `ActivityCommand.java` — 根节点添加 `.requires(PermissionChecker.check(...))`
- `DebugCommand.java` — 新增 `perm` 子命令树 + helper 方法
- `Samsara.java` — 启动时初始化 `PermissionConfig`

### 验收（RCON，2026-08-22）

- `perm list` 显示 37 条命令配置 ✓
- `perm info` 显示单个命令等级 ✓
- `perm owner add` 成功添加 owner ✓
- `claim list` 正常执行（USER 级） ✓
- `season status` 正常执行（USER 级） ✓

### 已知注意

- RCON 传输中文乱码（工具限制，游戏内正常）
- `perm set` 的 `greedyString` 参数在 RCON 中无法正确处理空格，需游戏内执行
