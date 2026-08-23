# samsara · 周目管理

原版红石生存服务器的**世界生命周期管理系统**（Fabric 服务端模组 + 数据包 + 离线工具）。

> 三大主题：**空间**（区块圈地/保存/重生成）· **时间**（周目轮回/新出生点/活跃度）· **逆熵**（时间倒流/玩偶影像）
> 设计文档：`docs/周目系统设计文档.md`（16 章）

## 仓库布局

```
samsara/
├── mod/        # Fabric 服务端模组（fabric-loom，mod id: samsara）
├── datapack/   # 数据包（pack.mcmeta 就绪，pack_format=107=26.2 的 data_major）
├── tools/      # 离线分析脚本（analyze.py：不开服读存档活跃度）
└── docs/       # 设计文档
```

## 当前状态：v0.8.9（命令重构 + 粒子配置增强 + 旗帜放置修复）

> **服务端模组**：服务器只装本模组即得全部功能；客户端装了也可在单人存档使用（`environment: "*"` + `"server"` entrypoint，机制见 `mod/README.md`）。

- [x] mod 项目骨架（**已锁定 26.2 版本链**并编译通过：fabric-api 0.157.0+26.2 / loader 0.19.3 / loom 1.17 / Java 25，见 `mod/README.md`）
- [x] `tools/analyze.py` v0（多来源推断玩家最后登录：activity.log / 日志 / usercache，输出"距上次玩家登录 X 天"；零依赖，已实测）
- [x] **M1 周目状态机**：Season 模型 + season.json 持久化 + `/season create/start/end/status` 状态机
- [x] **M2 分组出生 + 时间倒流**：出生中心计算 + 分组算法 + 全员传送 + 广播 + 倒流（区块波浪退场 + 方块级恢复）+ 倒流完成即自动物理删除
- [x] **M3 活跃度**：activity.log 实时追加 + 统计聚合 + 玩家分类 + 死服判定 + `/samsara activity` 命令
- [x] **M4 区块管理**：化线为面几何库 + 旗帜圈地（放置/破坏自动注册/移除）+ 安全删除 + 粒子预览
- [x] **权限等级系统**：4 级权限（CONFIG/OWNER/OP/USER）+ 命令可配置等级 + owner 名单可配置
- [x] **命令重构**：玩家命令 `/samsara claim info|boundary` + 管理命令 `/samsara debug claim ...` + ChatFormat 统一消息格式 + 粒子枚举建议 + 字体/光束粒子独立配置
- [ ] M5 面板 → M6 玩偶影像 → M7 世界栈 → M8 里程碑/资格 → M9 身份同步

## 命令结构

### 玩家命令（正常游玩使用）
```
/samsara claim info [x] [z]        查看区块圈地信息
/samsara claim boundary            切换边界持续显示
/samsara season status [页]        周目状态（附活跃度段落）
/samsara season info               世界种子与出生点
/samsara activity [recent|stats|dead]  活跃度查询
/samsara version                   模组版本号
```

### 管理员命令（/samsara debug ...）
```
/samsara debug claim give <dir> <dist>   给予圈地旗帜
/samsara debug claim list                列出所有圈地旗帜
/samsara debug claim rebuild             重算白名单
/samsara debug claim scan                扫描世界中的圈地旗帜
/samsara debug claim remove <x> <z>     移除圈地节点
/samsara debug claim info <x> <z>       查看指定区块详情
/samsara debug claim particle ...        粒子配置（day/night/font/beam/list/shared/global）
/samsara debug season|activity ...       周目/活跃度调试
/samsara debug status|json|config|...    状态与配置
/samsara debug fakeplayers|groups        测试数据注入与分组预览
/samsara debug rewind|diff|flow          倒流与差异控制
/samsara debug delete|whitelist|perm     安全删除/白名单/权限管理
```

## 消息格式

所有命令返回消息使用 `ChatFormat` 统一格式化：
- 首行 `[周目管理] ▸ 消息内容`，便于与上一条消息视觉区分
- 后续行 `  ▸ 消息内容` 缩进对齐
- 自动换行：CJK 字符按 2 宽度、ASCII 按 1 宽度，默认 34 宽度单位
- 调试命令使用 `[周目管理·调试] ▸` 前缀

## 粒子配置

圈地边界粒子支持独立配置（`/samsara debug claim particle ...`）：
- 昼间边界粒子（默认：smoke）
- 夜间边界粒子（默认：end_rod）
- 字体粒子（粒子文字渲染，默认：crit）
- 光束粒子（方向光束，默认：enchant）
- 共享显示 / 全图显示开关

可用粒子：crit, enchant, smoke, large_smoke, flame, soul_fire_flame, end_rod, happy_villager, composter, portal, witch, cloud, note, heart, angry_villager, snowflake, ash, white_ash

## 环境要求

- **JDK 25**（MC 26.2 硬性要求）
- Gradle 9.5.1（wrapper 已升级；loom 1.17 需要 Gradle 9.x）
- Python 3（analyze.py 零依赖，仅标准库）

## 生产模式测试服务器（test-server/）

一个**不依赖 Gradle、可直接双击启动**的 Fabric **26.2** 服务器，用于验证"生产加载路径"：

```
test-server/
├── start-server.bat       # 启动脚本（自动找 JDK 25；双击即可）
├── fabric-server-launch.jar  # fat jar 启动器
├── server.jar             # MC 26.2 服务端（bundler）
├── libraries/             # fabric-loader 及其依赖
├── mods/                  # 模组：samsara-*.jar + fabric-api-*.jar
├── server.properties      # RCON（25575，密码 samsara-m1-test）
└── eula.txt               # 已同意 EULA
```

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
