# samsara · Agent 开发约定

本文件面向在本仓库写代码的 AI Agent。目标不是“让 Agent 自由发挥”，而是把任务变成**小切片、明确契约、可自动验证、可回滚**的工作。

## 1. 仓库地图

| 区域 | 路径 | 说明 |
|---|---|---|
| Web 源码 | `web-src/src/` | Vue 3 + Vue Router；唯一前端源码 |
| Web 构建 | `mod/src/main/resources/web/vue/` | Vite 产物，提交入库 |
| 页面内核 | `web-src/src/pages/<page>/` | canvas / contenteditable / PageSpec 等算法与 composable，随 Vite 打包 |
| 后端 Web | `mod/src/main/java/com/shenqianye/samsara/web/` | HTTP/API |
| Web 约定 | `docs/Web 页面开发约定.md` | 页面/路由/样式规范 |
| Agent 任务 | `docs/agent/tasks/` | 每个功能任务一个 spec |
| E2E | `tools/web-e2e.mjs` / `tools/e2e/` | 全量回归 + 分区 harness |
| 发布 | `.opencode/skills/samsara-release/SKILL.md` | 生产部署流程 |

## 2. 常用命令

```powershell
cd web-src
npm.cmd run check    # 框架守卫 + lint + vue-tsc + vitest + vite build
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
- **旧内核已全部删除**：`legacy-core/`、`legacy/`、`useLegacyPage`、`LegacyPanel`、`app.js` 全局壳都不存在；算法已迁入 `pages/<page>/`，不要以任何形式重新引入。
- **不要引入 Pinia / TypeScript 大迁移 / 第二套 UI 框架**。
- **禁止 `innerHTML`**；文本用插值或 `textContent`。
- **框架守卫**：`npm run check` 会先跑 `tools/agent/check-framework.mjs`；它会阻止旧内核目录/import、`useLegacyPage`、`window.Samsara*`、旧静态脚本、直接 `fetch` 与 `innerHTML`。规则见 `docs/agent/legacy-audit-and-guardrails.md`。

## 4. 单任务工作流

0. **多步任务自主执行**：任务需要分多步时，不要逐步找用户确认。自己拆步骤，每步实现→测试→提交，然后发一条简短进度消息继续下一步；全部步骤提交后再部署。只有缺产品决策、凭据或环境阻塞时才停。
1. 读 `docs/agent/tasks/<task>.md`，只加载该任务列出的文件。
2. 若任务涉及现有页面：先补/确认 characterization test，再抽纯逻辑，再改 Vue 组件。
3. 每个任务一个 `git worktree` / 分支；只改 spec 的 `allowed files`。
4. 先跑最窄验证（页面级 E2E / 单测），再跑 `npm run check`。
5. 绿了才合并；合并后清理旧 E2E 断言与临时桥接。
6. 每个任务提交信息写清：`feat(web): add announcements preview`。

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

E2E 失败一律视为当前改动或测试环境的真实问题，定位到根因后再继续；不要用“历史问题”跳过。

## 6. 页面开发与重构顺序

旧命令式内核已全部删除；新增或重构页面按下面顺序：

1. 需要新算法时，先抽纯逻辑到 `web-src/src/pages/<page>/model.js` 并加单测。
2. 复杂内核（canvas / contenteditable / PageSpec）放 `web-src/src/pages/<page>/`，由 Vue 组件调用。
3. 页面只通过 `useApi` / `useAuth` / `useSession` 访问后端；跨页状态用 composable 或自定义事件。
4. 复用 `ItemWorkbench` / `SegmentedButtons` / `TabShell` / `PageNav`。
5. 加或更新 `tools/e2e/sections/<page>.mjs`。
6. 一键验证：`node tools/agent/verify-page.mjs --page <page> ...`。
7. 更新 `CHANGELOG.md` / `docs/实现状态.md`。

`tools/agent/scan-legacy.mjs` 现在只做回归审计：确认没有旧目录、`useLegacyPage`、`window.Samsara*` 或旧内核 import 残留。`tools/agent/check-framework.mjs` 没有页面白名单，任何重新引入 legacy 的改动都会让 `npm run check` 失败。

## 7. 文件所有权（并行 Agent）

- 单页任务：只改 `web-src/src/pages/<page>/`、对应 `pages/XxxPage.vue`、`tools/e2e/sections/<page>.mjs`。
- 集成任务：才允许改 `router.js`、`navigation.js`、`AdminLayout.vue`、`WebSecurity.java`。
- 共享组件改动必须单独任务，并附全量 `npm run check`。
- 禁止多个 Agent 同时改 `AdminLayout.vue` / `navigation.js` / `router.js`。

## 8. 发布

生产部署只按 `.opencode/skills/samsara-release/SKILL.md` 执行；不要在普通任务里重启生产服。停服必须用 `tools/release/stop-server.ps1`（RCON `stop` + 等待退出）；禁止直接 `taskkill /F`。
