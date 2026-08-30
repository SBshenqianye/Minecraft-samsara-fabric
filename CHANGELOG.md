# samsara · 变更记录

> 按里程碑组织的变更历史。最新状态见 `README.md`，功能设计见 `docs/设计文档.md`。

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
- 档2 区块级波浪退场（时间表驱动）
- 档3 方块级逐格恢复（diff 差异库，懒加载）
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
