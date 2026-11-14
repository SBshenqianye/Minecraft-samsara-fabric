# samsara 框架契约（Agent 常驻规则）

> 本文件通过 `opencode.jsonc` 的 `instructions` 自动加载；不需要在每次对话里重复。

## 1. 技术栈

- 前端唯一源码：`web-src/src/`（Vue 3 + Vue Router + Vite）。
- 构建产物：`mod/src/main/resources/web/vue/`，提交入库。
- 后端 Web：`mod/src/main/java/com/shenqianye/samsara/web/`。
- 旧命令式内核：`web-src/src/legacy-core/`，按页动态加载，禁止新增静态脚本。

## 2. Web 硬规则

1. 页面只由 `router.js` 决定；内容区是 `<router-view>`，不要往 `AdminLayout` 加页面 `v-if`。
2. 信息架构只改 `web-src/src/navigation.js`。
3. HTTP/token 只用 `useApi` / `useAuth`；页面/组件禁止直接 `fetch`。
4. 新页面不得 `import legacy-core/`，不得使用 `useLegacyPage`，不得依赖 `window.Samsara*`。
5. 复杂页面需要旧内核时，先抽纯逻辑到 `src/pages/<page>/model.js` 并加单测，再由 Vue 页面挂载内核。
6. 禁止 `innerHTML` / `insertAdjacentHTML` / `document.write`。
7. 新增 SPA 路由只改两处：`router.js` + `WebSecurity.staticResource`；需要子导航再加 `navigation.js`。
8. 保持状态页面加入 `KEEP_ALIVE_PAGES`，并 `defineOptions({ name: 'XxxPage' })`。

## 3. 每次改动的固定流程

```powershell
cd web-src
npm.cmd run check
```

```powershell
node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section <section>
```

```powershell
node tools/web-e2e.mjs --base http://127.0.0.1:8722 --token <token>
```

- 先跑最窄验证，再跑全量。
- 涉及页面行为必须补/跑 section E2E。
- 纯逻辑必须有 Vitest 单测。
- 每个任务一个 commit；提交信息写清 `feat/test/refactor/docs`。
- 不提交 `.tmp/`、测试服日志、临时 mapskip 副本。

## 4. 旧内核迁移规则

- 保留 canvas / contenteditable / 3D / PageSpec 内核，不重写算法。
- 顺序：纯逻辑 → model + 单测 → Vue 控制层 → 删除 `window.Samsara*` 桥 → 从守卫白名单移除。
- 禁止一次性重写所有内核；按页面绞杀。
- 白名单在 `tools/agent/check-framework.mjs`；拆完一个页面就移除对应条目。

## 5. 后端与发布

- Java 改动：`gradle test` / `gradle build`。
- 生产部署只在用户明确要求时执行，按 `.opencode/skills/samsara-release/SKILL.md`。
- 不修改生产 `gold.syncEnabled`，不删除生产备份。

## 6. 守卫

`npm run check` 会先跑：

```text
node tools/agent/check-framework.mjs
```

它阻止新页面接回旧内核、直接 fetch、innerHTML、旧静态路径和新增 `/web/*.js`。
