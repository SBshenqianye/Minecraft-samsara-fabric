# samsara · Agent 开发约定

本文件面向在本仓库写代码的 AI Agent。目标不是“让 Agent 自由发挥”，而是把任务变成**小切片、明确契约、可自动验证、可回滚**的工作。

## 1. 仓库地图

| 区域 | 路径 | 说明 |
|---|---|---|
| Web 源码 | `web-src/src/` | Vue 3 + Vue Router；唯一前端源码 |
| Web 构建 | `mod/src/main/resources/web/vue/` | Vite 产物，提交入库 |
| 旧 Web | `mod/src/main/resources/web/*.js` | 命令式旧页面，**只修 bug，不加新功能** |
| 后端 Web | `mod/src/main/java/com/shenqianye/samsara/web/` | HTTP/API |
| Web 约定 | `docs/Web 页面开发约定.md` | 页面/路由/样式规范 |
| Agent 任务 | `docs/agent/tasks/` | 每个迁移任务一个 spec |
| E2E | `tools/web-e2e.mjs` / `tools/e2e/` | 全量回归 + 分区 harness |
| 发布 | `.opencode/skills/samsara-release/SKILL.md` | 生产部署流程 |

## 2. 常用命令

```powershell
cd web-src
npm.cmd run check    # lint + vue-tsc + vitest + vite build
npm.cmd run lint
npm.cmd run typecheck
npm.cmd run test
npm.cmd run build

cd ..\mod
$env:JAVA_HOME='C:\Program Files\Java\jdk-26.0.2.1'
& 'C:\Users\Administrator\.gradle\wrapper\dists\gradle-9.5.1-bin\iq79hdu3mqx29lgffhp8bfmx\gradle-9.5.1\bin\gradle.bat' build --console=plain
```

## 3. 架构硬约束

- **页面只由路由决定**：内容区是 `<router-view>`；不要往 `AdminLayout` 加页面 `v-if`。
- **信息架构只改 `web-src/src/navigation.js`**：工作区、二级分区、三级子页、旧链接别名、keep-alive 名单。
- **HTTP/token 只用 `useApi` / `useAuth`**：不要复制 `fetch + Authorization + response.text()`。
- **新增页面只改两处**：`router.js` + `WebSecurity.staticResource`；需要子导航时再加 `navigation.js`。
- **需要保留状态的页面**：加入 `KEEP_ALIVE_PAGES`，并在页面里 `defineOptions({ name: 'XxxPage' })`。
- **旧代码只修 bug**：新功能必须写在 `web-src/src/`；不要继续扩展 `mod/src/main/resources/web/*.js`。
- **不要引入 Pinia / TypeScript 大迁移 / 第二套 UI 框架**。
- **禁止 `innerHTML`**；文本用插值或 `textContent`。

## 4. 单任务工作流

1. 读 `docs/agent/tasks/<task>.md`，只加载该任务列出的文件。
2. 若任务是迁移旧页：先补/确认 characterization test，再抽纯逻辑，再重生成 UI。
3. 每个任务一个 `git worktree` / 分支；只改 spec 的 `allowed files`。
4. 先跑最窄验证（页面级 E2E / 单测），再跑 `npm run check`。
5. 绿了才合并；合并后由集成任务删除旧脚本、桥接和旧 E2E 断言。
6. 每个任务提交信息写清：`feat(web): migrate announcements page`。

## 5. 验证门禁

最低门禁：

```powershell
cd web-src && npm.cmd run check
```

涉及页面行为时追加：

```powershell
# 单页面一键验证：npm run check + section E2E
node tools/agent/verify-page.mjs --page announcements --base http://127.0.0.1:8722 --token <token>

# 只跑 section E2E
node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section announcements
```

全量回归（发布前）：

```powershell
node tools/web-e2e.mjs --base http://127.0.0.1:8722 --token <token>
```

E2E 失败时先判断是否已知旧脚本问题（世界栈/地图/公告缓存 DOM），不要把所有失败都归因于当前改动。

## 6. 迁移旧页面的固定顺序

全量迁移的批次、依赖与状态见 `docs/agent/migration-board.md`。

1. 扫描依赖：`node tools/agent/scan-legacy.mjs`（清单带 `status` 列）
2. 生成任务与骨架：`node tools/agent/scaffold-page.mjs --name ...`
3. 抽纯逻辑到 `web-src/src/pages/<page>/model.js` + 单测
4. 写 Vue 页面，复用 `ItemWorkbench` / `Segmented` / `TabShell`
5. 加 section E2E
6. 一键验证：`node tools/agent/verify-page.mjs --page <page> ...`
7. 删除 `LegacyPanel`、旧脚本 import、`meta.panel`
8. 更新 `CHANGELOG.md` / `docs/实现状态.md`

**不要**一次性重写所有 legacy 内核；batch 5 已删除 `LegacyPanel` 与旧 HTML/静态脚本壳，内核统一放在 `web-src/src/legacy-core/` 随 Vite 打包。后续只在新页面稳定后逐步抽纯逻辑，不要再新增 `/web/*.js` 静态脚本。

## 7. 文件所有权（并行 Agent）

- 单页任务：只改 `web-src/src/pages/<page>/`、对应 `pages/XxxPage.vue`、`tools/e2e/sections/<page>.mjs`。
- 集成任务：才允许改 `router.js`、`navigation.js`、`AdminLayout.vue`、`legacy/admin.js`、`WebSecurity.java`。
- 共享组件改动必须单独任务，并附全量 `npm run check`。
- 禁止多个 Agent 同时改 `AdminLayout.vue` / `navigation.js` / `router.js`。

## 8. 发布

生产部署只按 `.opencode/skills/samsara-release/SKILL.md` 执行；不要在普通任务里重启生产服。
