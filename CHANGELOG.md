# samsara · 变更记录

> 按里程碑组织的变更历史。最新状态见 `README.md`，功能设计见 `docs/设计文档.md`，
> **实现进度（未实现/未达标）见 [`docs/实现状态.md`](docs/实现状态.md)**。

---

## v0.9.2（2026-09-10）· 文档一致性修订

**背景**：全量代码审查（41 个 Java 文件 = 主代码 39 + 测试 2，主代码约 9,600 行）对照《需求文档》63 条验收标准与《设计文档》全章节，发现文档与实现存在系统性偏差。

**新增文档**：

- `docs/实现状态.md` —— 缺口登记册（本仓库唯一的"做到哪一步"权威来源）：**13 项整块未实现 / 18 项部分未达标 / 7 项文档陈述与代码不符（5 项已按代码更正 + 2 项代码注释缺口）/ 3 项文档结构缺口**，含 `F-01`~`F-63` 全表对照（✅ 22 · ⚠ 8 · 🔒 33，未实现率 52.4%）

**设计裁决**：

- **命令短别名**：确认**保留**别名需求（以 `decisions/005` 与《设计文档》〈命令规范〉为准）。《设计规范》〈权限约定〉中与之冲突的"不留别名"旧写法已删除——三处文档 + 一份 ADR 均要求全量别名 + 冲突谦让，仅该一行写反
- **引用方式**：文档间与文档内引用**一律改为「文档名 + 章节标题」，不再使用章节编号**。原 `§` 引用因文档重构已大面积漂移失效（如《设计规范》引用的「设计文档 §12.2 预留表」实为「§11.2」、「§5.1/§5.2 活跃度」在设计文档中根本不存在）

**文档纠错**（文档声称存在、代码中不存在的陈述）：

- 《设计规范》〈M4b 旗帜圈地与安全删除〉：删除「白名单**惰性重算（`dirty` 标记）**」——代码无 `dirty` 字段，节点变更时 `rebuildGeometry()` **全量重建**
- 《设计规范》活跃度章节：删除 `/samsara debug activity simulate` 命令行——命令不存在，`ActivityManager.simulateText()` 是**无调用方的死代码**
- 《设计规范》debug 工具章节：更正 `claim add|addc <名> <p1> <p2>|remove <名>` 矩形圈地描述（该组命令与 `whitelist.json` 已随 v0.8"保护集仅圈地"废弃），补全现行 `claim give|list|rebuild|scan|remove|info|owner|particle|delete` 命令清单
- 《设计规范》坐标参数章节：更正「统一用 `Vec2Argument`」——现行为 `ColumnPosArgument`（支持 `~`）与 `IntegerArgumentType`；该节误写的编号 `§2b`（排在 §9 之后）修正为 `§9b`
- `docs/decisions/002`：关联行悬空编号 `§9.2.3`（设计文档该节无此子节）已改为章节名引用

**文档结构**：

- 根 `README.md`：「消息格式」重复段落合并；数据文件表补 `geometry.json`/`claim-particles.json`/`debug-delete.log`、删不存在的 `whitelist.json`；仓库布局删不存在的 `whitelist/` 包、补 `mixin/` 与 `datapack/`；相关文档删已删除的 `HANDOVER.md` 链接，补《需求文档》与《实现状态》
- **删除 `mod/README.md`**（131 行旧版模组文档，与根 README + 设计规范大量重复且严重过时：描述 5 个不存在的类、4 条不存在的命令、"23 个 Java 文件"、错误的字体尺寸）：其仍有效内容（构建须知、版本锁定表、`environment: "*"` 不可改的约束与依据、包结构）已迁入根 `README.md`

**历史条目标注**（不改写历史）：

- v0.8.8「M4b 旗帜圈地：15 种 MC 26.2 图案→8 方向映射」与实际不符——实现为 **9 个 pattern ID → 4 个方向**，且 `border` 只激活 4 方向而非设计中"全部 8 方向"。差额见《实现状态》

**未改动（按 2026-09-10 裁决）**：

- 《需求文档》《设计文档》**正文保持纯设计态**，不插入状态标记；一切实现状态登记于《实现状态》

---

## v0.9.1（2026-08-30）

- **M4d 修复**：dimensionFromPath 键格式修复 + 队列 key 含维度 + onChunkUnload 批量缓冲 + 停服兜底 drainNonLoaded
- **文档拆分**：原"周目系统设计文档"拆为 README + 设计文档 + CHANGELOG
- **M6 合并**：玩偶影像记录层合并到 diff 系统，重放层标记为远期扩展
- **M8 重设计**：全员同意制（在线玩家全员同意，>1人；管理员可直接开）

## v0.9.0（2026-08-26）

### M4d 安全删除增强

**批量删除优化**：
- 按维度分组 → 按区域文件分组 → 白名单索引快删（0-wl 文件整文件秒删）→ 混合文件只读一次 header
- 5 万区块从 50 分钟降到秒级

**旗帜归属权**：
- `ClaimNode` 新增 `ownerUUID`/`ownerName` 字段
- 破坏检查：主人或 OP 可拆

**边界显示持久化**：
- `boundaryToggle`/`showFixed`/`showDynamic` → `boundary-toggles.json`

**粒子沿边线采样**：
- 边界粒子沿边线每 4 格采样（旧版仅角点）
- Y 轴统一跟随玩家高度
- 右键防抖 20 tick

**ResourceKey 解析修复**：
- MC 26.2 `ResourceKey.toString()` 返回 `"ResourceKey[minecraft:overworld]"` 格式
- 新增 `parseResourceKey()` 辅助方法

### 权限等级系统

- 4 级权限：CONFIG > OWNER > OP > USER
- 每个命令有默认权限等级，owner 可自定义
- `permissions.json` 持久化

### 命令重构 + 粒子配置增强

- 玩家命令 `/samsara claim info|boundary`
- 管理命令 `/samsara debug claim ...`
- ChatFormat 统一消息格式
- 字体/光束粒子独立配置

## v0.8.8（2026-08-22）

### M4 区块管理

**M4a 化线为面几何库**：
- `Geometry.java`（rasterize → graph → face traversal → scanline fill → union）
- 16 个单测全绿

**M4b 旗帜圈地**：
- 15 种 MC 26.2 图案→8 方向映射
- 放置/破坏自动注册/移除
- 右键交互 + 粒子预览
- `AreaService` 单例 + `claims.json` 持久化

**M4c 安全删除**：
- dryrun/backup/execute/report
- 多维度支持

### M3 活跃度

- `activity.log` 主数据源（JOIN/LEAVE 实时追加）
- 统计聚合：近 7/14/30 天活跃、日均、峰值
- 玩家分类：活跃/回归/流失/沉睡
- 死服判定（可配阈值）
- `/samsara activity recent|stats|dead`

### v0.8.8 修复批次

- diff 快照语义（记录常开 → 倒流启动冻结 → 完成重置）
- 待删队列（`pending-delete.json`，卸载即删，启动/停服兜底）
- 文件级回收（全空 .mca 整文件删除 + compact 压缩重写）
- rewind.json 归档（走完归档至 `archive/`，不删除）
- 异步删除（后台线程备份+清零）
- 禁止双 ACTIVE（create/setup 拦截）
- 无可倒流区块时回退直接传送

## v0.8.7（2026-08-16）

### M2 分组出生 + 时间倒流

**分组出生**：
- 出生中心计算（距全部历史中心集合 ≥15000）
- 分组算法（3~5 人/组，组距 ≥1000，组内抖动 40 格）
- 全员传送 + 广播
- 离线玩家上线补传送（`pending-teleports.json`）

**时间倒流**：
- 区块级波浪退场（时间表驱动）
- 方块级逐格恢复（diff 差异库，懒加载）
- 倒流完成即自动物理删除

### v0.8.7 修复

- 组距文案动态/自适应散布
- fakeplayers 自动清空
- 恢复预算实际推进位置
- 恢复在倒流结束后继续到完成

## v0.8.6（2026-08-15）

### M1 周目状态机

- `Season` 模型 + `SeasonData`（season.json 根结构）
- `SeasonManager` 单例状态机
- `/samsara season create/start/end/status`
- 状态机：PENDING → ACTIVE → ENDED → ARCHIVED
- 重启持久化验证通过

---

## 验收记录

### M1 验收（2026-08-15）
```
> season create S1-Test same-world   → 已创建周目 #1（PENDING）
> season start                        → 周目 #1 开始（ACTIVE）
> season status                       → #1 ACTIVE
> season end                          → 周目 #1 已结束（ENDED）
```

### M2 验收（2026-08-16）
```
> samsara debug fakeplayers Alice,Bob,... (10)   → 注入 10 名测试玩家
> season start (J1)                              → 出生中心计算 · 分组生成 · 倒流启动
> 倒流完成 → 传送阶段 → 广播
```

### M3 验收（2026-08-17）
```
> samsara activity stats    → 近7/14/30天活跃 3/3/4、日均13分、峰值3
> samsara activity dead     → 死服判定正确
```

### M4 验收（2026-08-22）
```
> 几何库 16 个单测全绿
> 旗帜放置→自动注册→右键显示详情
> 旗帜破坏→自动移除节点
> 手持旗帜粒子预览：双层 Y 轴 ✓
> 安全删除 dryrun/backup/report ✓
```

### v0.8.9 验收（2026-08-23）
```
> 旗帜放置→自动注册（修复1 tick延迟问题）
> /samsara debug claim particle list → 字体/光束粒子配置生效
> ChatFormat 统一消息格式 → 自动换行 + 前缀分隔
```

### M4d 验收（2026-08-26）
```
> 批量删除：按维度分组 → McaRegion.deleteChunks 一次调用处理整个维度
> 白名单索引：0-wl 文件整文件秒删（不读 header），混合文件只读一次 header
> 备份去重：RegionPos hash 去重，不重复复制
> 旗帜归属权：ClaimNode ownerUUID/ownerName，主人或 OP 可拆
> 边界显示持久化：boundaryToggle/showFixed/showDynamic → boundary-toggles.json
> 粒子修复：边界粒子沿边线每4格采样、Y轴跟随玩家高度、右键防抖
> ResourceKey 解析：修复 MC 26.2 "ResourceKey[minecraft:overworld]" 格式
```

### 2026-08-17 性能验收
```
> 5.3 万条 diff 注入 234ms（修复前卡死 75s）
> 恢复 2.1 万格 1086ms 零卡顿
> compact 扫描 1060 个 .mca → 全空删除 862 个 + 压缩重写 20 个 → 释放 1,754 MB
```
