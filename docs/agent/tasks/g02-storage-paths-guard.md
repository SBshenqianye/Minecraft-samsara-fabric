# Task: G-02 路径收敛 + G-05 存储/依赖守卫（S1 / S2）

## Goal
把「谁拼 config 路径」收敛到 `storage/StoragePaths` 一处，并给这条规则与包依赖方向加可执行守卫，接入 `gradle build`。

## Scope
- allowed:
  - `mod/src/main/java/com/shenqianye/samsara/storage/StoragePaths.java`
  - `mod/src/main/java/com/shenqianye/samsara/opauth/OpAuthService.java`
  - `mod/src/main/java/com/shenqianye/samsara/web/AgentTokenStore.java`
  - `mod/src/main/java/com/shenqianye/samsara/config/spec/ConfigEditorService.java`
  - `mod/src/main/java/com/shenqianye/samsara/server/ServerBackup.java`
  - `mod/src/main/java/com/shenqianye/samsara/season/WorldStackAdmin.java`
  - `mod/src/main/java/com/shenqianye/samsara/audit/Audit.java`（S2 新增）
  - `mod/src/main/java/com/shenqianye/samsara/web/WebAuditService.java`（S2：实现 `Audit.Sink`）
  - `mod/src/main/java/com/shenqianye/samsara/container/PlayerInventoryService.java`（S2：去掉 `web` 依赖）
  - `mod/src/main/java/com/shenqianye/samsara/Samsara.java`（S2：安装审计 sink）
  - `mod/src/test/java/com/shenqianye/samsara/storage/StoragePathsTest.java`（新增）
  - `mod/src/test/java/com/shenqianye/samsara/architecture/ArchitectureGuardTest.java`（新增）
  - `docs/agent/tasks/g02-storage-paths-guard.md`
  - `docs/agent/legacy-audit-and-guardrails.md`（追加守卫说明）
  - `AGENTS.md`（第 3 节守卫清单补一行）
- forbidden:
  - 移动/重命名生产数据目录（本任务只收敛代码，不做真实迁移）
  - `mod/src/main/resources/**`、`web-src/**`
  - 其他业务逻辑改动

## Contract
- `StoragePaths` 新增：
  - `configFile(String name)`：`configRoot()` 下固定文件，拒绝子路径/`..`
  - `gameConfigDir(Path fallbackServerRoot)`：`FabricLoader.getConfigDir()`，不可用时回退 `<serverRoot>/config`
  - `configDirAt(Path serverRoot)`：`<serverRoot>/config/samsara`
  - 常量 `AGENT_TOKENS_FILE`、`OP_AUTH_FILE`
- `ServerBackup` / `WorldStackAdmin` 不再出现 `"config/samsara"` 字面量。
- 除 `storage/StoragePaths.java` 外，`mod/src/main/java` 不出现 `getConfigDir()`。
- 除 `Samsara.java` 外，`web` 包不被任何包 import；`storage` 包只依赖 `Samsara` 与 `config`。
- 审计写入：`container/PlayerInventoryService` 改调 `audit/Audit.captureInventory`，`WebAuditService` 实现 `Audit.Sink`，在 `Samsara.onInitializeServer` 安装。

## Acceptance
- [ ] `gradle test` 全绿（含新增 `StoragePathsTest`、`ArchitectureGuardTest`）
- [ ] `gradle build` 通过
- [ ] 故意制造一次 `getConfigDir()` 硬编码 / 一次 `container → web` import，守卫测试必须失败并报出文件名（演练后还原）
- [ ] 启动落点不变：`config/samsara/agent-tokens.json`、`config/samsara/op-auth.yml`、备份仍包含 `config/samsara`

## Evidence
- 单测：`StoragePathsTest`、`ArchitectureGuardTest`
- 手工：`gradle build` 输出；测试服起服日志中的存储根行
- 失败/回滚说明：只删本次新增方法/测试即可回滚，无数据迁移

## Notes
- S1 只收敛「谁拼路径」，不移动任何现有文件；真实迁移留给 G-02 第二批。
- `backups/samsara/*` 的字面量本轮不处理（不属于 config 路径），已在 G-02 待办中保留。
- 审计 sink 用接口隔离，是为了让「依赖方向守卫」零例外，而不是顺手重构审计实现。
