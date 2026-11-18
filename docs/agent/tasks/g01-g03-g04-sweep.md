# Task: G-01 第一轮 + G-03 第一轮 + G-04 第一片

## Goal
按 `docs/待办.md` 的 G-01/G-03/G-04 做第一轮可交付切片：修掉最危险的数据/误导问题，建立 UX 公共件，拆出 HTTP 边界与权限映射。

## Scope
- allowed:
  - `mod/src/main/java/com/shenqianye/samsara/**`（只改本 spec 列出的类）
  - `mod/src/test/java/**`
  - `web-src/src/**`
  - `tools/e2e/sections/server.mjs`
  - `mod/src/main/resources/web/vue/**`（构建产物）
  - `docs/**`
- forbidden:
  - 世界数据迁移（G-02 第二批）
  - 共享布局 `AdminLayout.vue` / `router.js` / `navigation.js` 的并发改动
  - 部署脚本与生产配置

## Contract
- 危险操作（删除/恢复/丢弃未保存修改）必须二次确认；确认文案要说明“是否立即生效”。
- 读取失败必须显示为错误，不得渲染成空列表/零统计。
- 倒流/安全删除在物理删除前必须复查圈地白名单；无法读取备份文件时按整文件备份。
- `ApiScope` 是唯一“路径 → scope”映射，未映射写操作回退 OPS。

## Acceptance
- [x] `cd web-src && npm.cmd run check`
- [x] `gradle test`（579/0/0）与 `gradle build`
- [x] `server.mjs` E2E 已按新确认框更新（未在本次环境实跑，缺 token）
- [x] 违反架构规则时 `ArchitectureGuardTest` 失败（已演练）

## Evidence
- 单测：`StoragePathsTest`、`ArchitectureGuardTest`、`WebHttpTest`、`ApiScopeTest`
- 手工验证清单：见最终汇报“需要在测试服确认”一节
- 回滚：按 commit 单独 revert；无数据迁移

## Notes
- 本轮没有新增/删除任何玩家数据；`AreaService` 解析失败只会“少写”，不会覆盖。
- 未完成项已写回 `docs/待办.md`，不要在本轮继续扩大范围。
