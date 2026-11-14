# Goal: 完成 Legacy 迁移 batch 2–5 并部署

## 目标

按 `docs/agent/migration-board.md` 连续完成 batch 2–5，全部批次测试通过并提交后，再按
`.opencode/skills/samsara-release/SKILL.md` 部署生产。

当前已完成并提交：

- 公告、权限、RCON 控制台、输出格式壳（batch 1，commit `e6e9ce3`）
- 生产仍是 0.58.0，未部署 0.59.0
- 测试服当前已停止

## 执行循环（每个页面/批次）

1. 实现当前页面/批次。
2. `cd web-src && npm.cmd run check`。
3. 构建 `mod` jar，启动测试服。
4. `node tools/agent/verify-page.mjs --page <page> --base http://127.0.0.1:8722 --token <token>`。
5. 跑相关全量 E2E（必要时用 map-skip 副本绕过已知地图旧脚本问题）。
6. `git add` 明确路径（不提交 `.codebuddy/worktrees/`、临时 mapskip 文件）。
7. commit + push。
8. 更新 `docs/agent/migration-board.md`、`docs/agent/legacy-inventory.md`、`CHANGELOG.md`、`docs/实现状态.md`。
9. 立即进入下一个页面/批次，不要等待用户确认。

## 批次

### batch 2

- `lottery`
- `server-config`
- `server-backup`
- `server-audit`

### batch 3

- `identity`
- `config-editor`

### batch 4

- `claim-range`
- `map`
- `world-stack`

### batch 5

- `app.js` 旧壳、`page-kit.js`
- 共享模块：`chat-text.js`、`structured.js`、`form-kit.js`、`value-table.js`、`item-ui.js`、`banners.js`、`particles.js`
- `legacy.html`、`legacy-user.html`、`/style.css`

## 约束

- 复杂页面采用 **Vue 壳 + 保留内核**：不重写 canvas、contenteditable、3D、PageSpec 渲染算法。
- 每批必须独立可回滚；不允许把未测试的批次一起提交。
- 不提前部署；只有全部批次提交后才进入生产部署。
- 不修改 `gold.syncEnabled` 的生产配置。
- 不删除生产备份、不清空测试服世界/玩家数据。
- 遇到需要产品决策或环境不可用时，输出具体 blocker，不要硬猜。

## 当前已知历史问题

全量 E2E 当前为 **281/281**；新增页面/改动后仍要求：
- 本页 section E2E 全绿；
- 全量 E2E 中与本次迁移相关的断言全绿；
- 没有新增失败。

## 完成记录（2026-11-14）

- batch 2–4 页面壳 + 按需内核：`8d5f374 feat(web): page-scope remaining legacy pages`
- batch 5 面板嵌入与 `LegacyPanel` 删除：`7412ab1 feat(web): embed legacy panels in Vue pages and drop LegacyPanel`
- batch 5 内核迁入 Vite：`988d0ff refactor(web): move legacy kernels into Vite source`
- batch 5 旧壳/静态资源删除：`209159d feat(web): remove legacy static shell for batch 5`
- Web 版本：`ad79c20 chore(web): bump web version to 0.60.0`
- `legacy-shell` section E2E：**11 / 11**
- `batch5` section E2E：**8 / 8**（旧路径全 404、`/user` 五分区、地图内核、无旧脚本请求）
- 联合 section E2E（legacy-shell + batch5）：**19 / 19**
- 全量 E2E：**281 / 281** 通过（不再需要 map-skip 副本）
- `npm run check`：**48 / 48** 单测；Gradle clean build：**540 tests / 0 failures**
- batch 5 结果：`LegacyPanel`、`legacy.html`/`legacy-user.html`、`/style.css`、旧静态脚本路由全部删除；`app.js`/`page-kit.js`/共享内核迁入 `web-src/src/legacy-core/` 随 bundle 加载；`AdminOverlays.vue` 接管浮窗，`UserPage.vue` 接管公开端结构
- 生产 jar SHA-256：`E083C7ABC98B20C240AC48366C2AC20955F324F5FE2BFC56807F4BB999560BE8`
- 构建 / 测试服 / 生产三处哈希一致
- 生产：`version=0.41.2`，`webVersion=0.60.0+1114.2058`
- 端点：`/api/lottery`、`/api/containers`、`/api/track`、`/api/status`、`/user`、`/`、`/openapi.json` 均 200；`/legacy.html`、`/legacy-user.html`、`/style.css`、`/app.js` 均 404
- 旧 jar 备份：`backups/samsara/mod-jars/samsara-0.41.2.bak-20261114-batch5.jar`

## 完成标志

全部批次提交后：

1. `git status` 干净（仅允许 `.codebuddy/worktrees/`）。
2. `docs/agent/migration-board.md` 所有非 shared/shell 项为 done。
3. 部署生产并完成三处哈希校验、`/api/status`、`/api/lottery`、`/api/containers`、`/api/track`、`/user` 验证。
4. 输出：

```text
[goal:evidence] batch 2-5 全部提交；npm run check / section E2E / 全量相关 E2E 通过；生产部署与三处哈希校验通过
[goal:complete]
```

需要用户决策或环境阻塞时：

```text
<具体阻塞原因>
[goal:blocked]
```
