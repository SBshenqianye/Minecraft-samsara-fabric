# 计划：洞穴地图与 Agent 三层自动化

> 状态：**仅方案，未实现**（2026-11-04）。落地前先在《需求文档》登记验收项。

## 1. 洞穴 / 分层地图

### 目标

在现有只读 region/tile 地图上增加“高度层”，看到地下洞穴/矿洞，而不是只看地表；同时不改变“地图只读、不加载区块、不写世界数据”的边界。

### 方案对比

| 方案 | 做法 | 服务器负担 | 结论 |
|---|---|---|---|
| A. 高度切片 | 复用 `MapTerrainRenderer`，按 `layer=Y` 判断该层是否有空气/洞穴，生成 PNG；tile 缓存键加 `layer` | 单 tile 与现有地形同量级；风险是层数 × region 的缓存膨胀 | **先做** |
| B. 跟随选中玩家 | 洞穴模式按选中玩家/实体 Y 自动选层；只请求已生成 region | 很小，和实时层天然结合 | 第二步 |
| C. 体素/柱状剖面 | 读 anvil section，生成地下透视图 | CPU/内存/缓存复杂，26.3 `PalettedContainer` 要单独验证 | 远期 |

### 推荐落地顺序

1. `GET /api/map/tile` 增加 `layer` 参数（16 格一档：-64、-48、…、320），tile 缓存键加入 layer，LRU 限额按层均分。
2. Web 地图加高度滑杆 + “洞穴模式”开关；关闭时保持现有地表行为。
3. 再接入“跟随选中玩家 Y”（现有实体选择面板已有选中对象）。
4. 体素剖面等 Web 面板稳定后再评估。

### 明确不做

- 不做“点击玩家后真实加载区块”：会改变 tick/实体状态，违背地图只读约定。
- 不在 HTTP 线程读 anvil；所有磁盘读取走异步 + 缓存。

## 2. 管理员-Agent-脚本三层自动化

### 目标

Agent 不内置到 Web。Web 只提供稳定的只读/受控写接口与文档；脚本无事不提醒 agent，agent 无事不提醒管理员。

### 三层职责

| 层 | 职责 | 频率 | 失败处理 |
|---|---|---|---|
| 脚本 watcher | 轮询 `/api/status`、`/api/map/live`、`/api/audit`、`/api/world-stack`；只做阈值/异常检测；无事不输出 | 30s~5min | 重试 + 本地日志；不直接改服务器 |
| Agent | 消费 watcher 事件，按需拉接口与文档，生成诊断/建议；写操作必须生成“待确认命令” | 事件驱动 | 不自动执行危险操作；输出可审计 |
| 管理员 | 确认 reset new/stop/rollback 等高风险操作；维护阈值 | 人工 | 最终否决权 |

### 接口边界

- 只读：`/api/status`、`/api/map`、`/api/map/live`、`/api/user/*`。
- 写：`/api/config`、`/api/audit/rollback`、`/api/server/console` 必须管理员确认；agent 默认不持有管理 token。
- 增量：`/api/map/live` 已带 `generatedAt`、`chat`、`system`；watcher 只取时间戳之后的增量。

### 落地顺序

1. `tools/samsara-watch.mjs`：轮询 status/live，异常才输出一行 JSON。
2. `docs/AGENT.md`：只读接口、字段、典型查询、禁止操作。
3. 接 DSH 或其他 agent：只消费 watcher 输出 + 按需拉接口，不内置到 Web。
