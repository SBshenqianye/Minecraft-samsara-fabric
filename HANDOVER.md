# samsara（周目管理）模组 — 交接文档

> 交接日期：2026-08-16 ｜ 版本：v0.8.8（git HEAD：`1329bcb`）
> 交接人：AI 开发助手 ｜ 接收人：项目维护者

---

## 1. 项目概况

- **是什么**：Minecraft Fabric 服务端模组，实现"轮回周目"系统——每个周目（赛季）自动计算新出生中心、玩家分组出生、世界时间倒流（区块波浪退场 + 方块级恢复）、圈地保护。
- **技术栈**：Minecraft **26.2**（去混淆 Mojang 官方映射，无 yarn）、Fabric Loader 0.19.3、fabric-api 0.157.0+26.2、fabric-loom 1.17、Java 25、Gradle 9.5.1。
- **代码位置**：`mod/src/main/java/com/shenqianye/samsara/`（约 21 个 Java 文件）
- **设计文档**：`docs/` 目录（含 §2b 坐标规范、§10 禁止热删、§12 档3 倒流设计）

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
│   └── DebugCommand.java     /samsara debug 系列（调试全功能）
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
- `rewind.json` — 进行中的倒流状态（完成后自动清除）
- `diff/s<赛季id>/` — 每区块 diff 记录（24 字节/条：stateId+x+y+z+timeMs）

---

## 4. 命令手册

### 玩家命令
```
/season create <名> [模式]      # 创建周目（PENDING；模式 same-world/new-world）
/season start                   # 完整流程：setup→倒流→传送→删旧区
/season end                     # 结束当前 ACTIVE
/season skip                    # 跳过 PENDING
/season status [页]             # 查看
```

### OP 调试命令（玩家名须为 xh_unpkire）
```
/samsara debug flow setup               # 只准备（中心+分组+ACTIVE）
/samsara debug flow rewind <true|false> # 只倒流（完成后是否传送）
/samsara debug flow teleport            # 只传送
/samsara debug diff                     # diff 库状态
/samsara debug diff fake <pos> <数量> [stateId]   # 注入虚假 diff（区块，坐标支持~）
/samsara debug diff text <pos> <文字> [stateId] [scale]  # 空中字画（5×5 点阵）
/samsara debug rewind preview|status|abort|purge|restore <pos>|set <时长>
/samsara debug claim add|addc <名> <p1> <p2>|remove <名>|list
/samsara debug config [键] [值]         # groupdist/birthjitter/rewind/groupmin/...
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
- **备份策略**（用户要求）：固定 1 个初始备份 + 流水最新 2 个，当前保留：
  - `backups/world_20260816_040401`（初始固定，2.3GB）
  - `backups/world_20260816_155257`（2.3GB）
  - `backups/world_20260816_165648`（3.9GB，测试前最后完整状态）
- **purge 备份**：`world/backups/purge_<时间戳>/`（倒流删除前的区块备份，可回滚）

### 开发环境
- JDK25：`C:\Users\Administrator\AppData\Local\Temp\dsh-Ln5i7u\jdk25\jdk-25.0.4+7`
- Gradle home：`dsh-YYFeCv\gradle-home`，命令：`gradle-9.5.1/bin/gradle.bat build --offline`
- **部署流程（重要）**：先停服 → 复制 `mod/build/libs/samsara-0.8.8.jar` 到 `test-server/mods/` → 删 `world/session.lock` → 启动。不停服部署会导致"An unexpected error"（旧 class 与新 jar 不一致）。

---

## 6. 已知问题 / 遗留事项

1. **RCON 中文传输乱码**（工具限制，游戏内正常）：`RconClient` 传中文会拆段报错，验证中文用游戏内。
2. **区块删除后重生成 ≠ 创建时地形**：MC 特性（邻接依赖/结构规划），非 bug；要完全还原用初始备份。
3. **`restoreChunkNow` 对远离出生点的区块**：强制 getChunk 后 setBlock 在 26.2 中仍可能受限（"not loaded"），但玩家在线视距内正常；字画注入已内置强制加载可绕过。
4. **多人 ACTIVE 残留**：`current()` 取第一个 ACTIVE，测试后需清理 season.json（ACTIVE→ENDED）或用 `/season end`。
5. **可能待做**：档3 逐步恢复后"结尾自动删除区块"已实现（倒流完成即删）；旗帜圈地系统尚未接入（目前用 debug claim 指令，按用户要求）。
6. **Tier3Restore 无玩家在线时不恢复**（collectSpawningChunks 为空）——设计如此（懒加载），玩家在视距内才恢复；restore 命令可强制。

---

## 7. 验收记录（关键测试结果）

- 空中字画注入即显示：8/8 像素点验证通过（服务器日志 `[Not Secure] [Rcon]`）
- 性能：5.3 万条 diff 注入 **234ms**（修复前卡死 75s）；恢复 2.1 万格 **1086ms** 零卡顿
- 倒流完成自动删除：`36908 个已标记区块清零（备份至 purge_20260816_212550）`，无需停服
- 坐标语法糖：`diff text ~ ~ ~ SAM` → (0,71,0)、`~5 ~10 ~5` → (5,81,5) 全部验证通过

---

## 8. 交接检查清单

- [x] 工作树干净（git status 无未提交改动）
- [x] 服务器运行中（PID 37320，25565/25575）
- [x] 赛季状态干净（无 ACTIVE 残留）
- [x] 备份 3 个（初始 1 + 流水 2），磁盘空余 20.5GB
- [x] 最近 3 个 commit：`1329bcb`（自动删除）、`61269cd`（性能）、`2ea73b3`（注入即显示）
