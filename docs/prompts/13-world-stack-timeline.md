# Prompt：世界栈操作备份接入时间线并支持“撤销这次操作”（F-162）

你在仓库 `E:\workspace\deepseek harness\mc-mod-samsara` 工作。先阅读：

- `docs/需求文档.md` 的 F-162
- `docs/设计文档.md` 的 9.15.1
- `docs/设计规范.md` 的“世界栈操作时间线”
- `docs/实现状态.md` 中世界栈、时间线相关记录
- `AGENTS`/`.agents/skills` 下的构建、文档维护、版本策略

## 目标

把世界栈的导入 / 提升栈顶 / 归档“操作前备份”接入统一时间线，并在时间线节点上提供“撤销这次操作”。

世界栈操作在时间上线性、不能分支，因此：

- 复用 `BackupTimeline`，但所有节点 `branchId="world-stack"`；
- 节点按 `createdAt` 线性排列，不产生分支；
- “撤销这次操作”就是回滚到该操作前状态。

## 当前代码事实（先核实，不要直接假设）

- `mod/src/main/java/com/shenqianye/samsara/server/BackupTimeline.java` 已经是通用的父节点/分支/头节点模型；`ServerBackup.java` 是第一个适配器，可参考它的 `parentId`/`branchId`/`headId`/`timeline()` 用法。
- 世界栈操作在 `mod/src/main/java/com/shenqianye/samsara/season/WorldStackAdmin.java`：
  - 导入前备份目录：`samsara/world-stack-backup/import-<jobId>`
  - 提升栈顶前备份目录：`samsara/world-stack-backup/promote-<jobId>`
  - 任务报告：`world/samsara/world-stack-jobs/*.json`
  - 栈清单：`world/samsara/stack.json`
- **重要**：现有 `import-*` 备份只保存了旧栈顶的 `level.dat` 与 `data-minecraft`；旧 `dimensions/minecraft`、`samsara/claims|diff|track`、`spawn-history.json` 在导入过程中被移动到被降级的栈层，`stack.json` 的备份在 `stack.json.import-backup`，并不在备份目录内。因此**旧备份很可能不足以安全撤销**。不要猜测恢复；旧节点必须标记为只读不可撤销，并说明缺少哪些信息。
- 恢复必须在世界打开前执行，参考 `StackBootstrap` 对 `WorldStackAdmin.consumePending(worldDir)` / `ServerBackup.prepare()` 的调用顺序与 pending 文件模式。
- Web 入口：`web/WorldStackWebService.java`、`web/LocalWebServer.java`、`web/WebSecurity.java`、`resources/web/world-stack.js`、`index.html`、`style.css`。
- Web 改动必须跑 `tools/web-e2e.mjs` / `tools/web-e2e.ps1`。

## 必须要做的设计

### 1. 操作快照格式

从本次改动开始，每次导入 / 提升 / 归档在备份目录写 `operation.json`：

```json
{
  "format": 1,
  "id": "ws-<jobId>",
  "operation": "import|promote|archive",
  "jobId": "...",
  "createdAt": 0,
  "beforeManifest": { },
  "afterManifest": { },
  "affectedIds": ["..."],
  "restorable": true,
  "backupDir": "...",
  "notes": []
}
```

要求：

- `beforeManifest` / `afterManifest` 至少保存操作前后的完整 `stack.json` 结构（或能等价重建）；
- 备份目录必须包含撤销所需的全部内容：旧栈顶数据、被移动的层目录、manifest、必要的 playerdata / 身份关联信息；
- `restorable=false` 时写清楚原因；
- 旧备份没有 `operation.json`：仍可在 UI 中展示，但不可撤销。

### 2. 时间线适配器

新增类似 `WorldStackTimeline` 的适配器（或放在 `WorldStackAdmin` 内的独立方法）：

- 读取 `samsara/world-stack-backup/*` 与 job 报告；
- 生成 `BackupTimeline.Entry`：`id=ws-<jobId>`、`parentId=上一个操作`、`branchId="world-stack"`、`createdAt`、`data` 中带 `operation`/`jobId`/`restorable`/`affectedIds`；
- 输出给 Web 的节点字段与本体时间线保持兼容：`id/parentId/branchId/depth/current/restorable/deletable/createdAt/...`；
- 线性链不需要 `pendingBranch`。

### 3. 撤销执行

新增 pending（例如 `world/samsara/pending-stack-restore.json`），在 `StackBootstrap` 世界打开前消费：

- 先校验：备份存在、`operation.json` 可读、`restorable=true`、当前栈清单仍能匹配 `afterManifest`；
- 预览：显示将恢复的层、将移动/删除的目录、预计大小、风险；
- 执行前把当前世界栈状态备份到 `pre-restore-<job>`（或等价目录）；
- 按操作类型恢复：
  - `import`：撤销导入，恢复旧栈顶为 top，新的栈顶退回来源状态或按 `beforeManifest` 复位；
  - `promote`：撤销栈顶交换，恢复提升前顶层数据与层顺序；
  - `archive`：从归档目录恢复层并写回 manifest（源归档不删除，采用复制/移动后保留策略需写清楚）；
- 失败必须回滚到执行前状态；
- 源世界、归档、用户备份永不自动删除；
- 任务报告写 `world-stack-jobs`，包含每一步与失败原因。

### 4. API 与 UI

- 新增：
  - `GET /api/world-stack/timeline`
  - `POST /api/world-stack/timeline/preview`
  - `POST /api/world-stack/timeline/restore`
- 鉴权、body 上限、固定路径白名单按现有 Web 规则；
- 世界栈页新增“操作时间线”面板，复用本体时间线的视觉/预览/勾选确认；
- 节点操作：`撤销这次操作`、查看 job、查看 manifest 摘要；
- 不可撤销节点禁用按钮并显示原因；
- 撤销也必须二次确认，并提示“停服后、下次启动前执行”。

## 非目标

- 不做分支；
- 不删除源世界、归档或用户手动备份；
- 不改 track v2；
- 不重做世界栈导入/提升算法，只加可逆快照与撤销。

## 测试与验收

必须新增/更新：

- 单测：
  - `operation.json` 读写与缺失字段；
  - 旧备份无 `operation.json` → `restorable=false`；
  - 时间线线性顺序、parentId、branchId 固定；
  - 预览拒绝不可逆节点；
  - 撤销成功、失败回滚、执行前备份存在；
  - manifest 不匹配时拒绝。
- 集成/临时目录测试：
  - 构造导入前/后目录，执行撤销，断言栈清单与目录恢复；
  - 构造 promote 备份，执行撤销，断言顶层数据恢复；
  - 不触碰真实测试服世界。
- Web E2E：
  - 世界栈时间线渲染、节点类型/可撤销标记；
  - 预览打开、不可撤销节点禁用；
  - 默认不执行真实撤销。
- 文档：需求、设计、规范、实现状态、CHANGELOG 同步。
- 命令：
  - `cd mod && ./gradlew build`
  - `python tools/docs_sync.py --check`
  - `powershell -ExecutionPolicy Bypass -File tools/web-e2e.ps1 -ServerDir .\test-server-backup-test`

## 验收标准

- 新产生的导入/提升/归档操作都有 `operation.json` 与完整快照；
- 时间线按时间线性展示，节点能区分操作类型与可撤销性；
- 可撤销节点能预览并排队撤销；撤销后栈清单、顶层数据、层目录与操作前一致；
- 失败自动回滚，源世界/归档不被删除；
- 旧备份不会被误撤销；
- 单测、构建、Web E2E、docs_sync 全部通过；
- 不破坏 `test-server-web` / `test-server-accept` 两个毕业档。

完成后输出：改动文件、测试结果、已知限制、下一步建议。
