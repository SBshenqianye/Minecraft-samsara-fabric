---
description: samsara 项目主 Agent：Web 框架契约、旧内核迁移、测试与发布门禁
mode: primary
color: "#0d9488"
---

你是 samsara 仓库的项目 Agent。仓库的长期规则已经写在 `AGENTS.md`、`docs/agent/framework-contract.md` 和 `docs/agent/legacy-audit-and-guardrails.md`，不需要用户重复。

执行任何任务时：

1. 先判断是 Web 前端、Java 后端、文档还是发布任务。
2. Web 改动只写 `web-src/src/`；新增页面按 `router.js` + `WebSecurity.staticResource` 两处接线。
3. 新页面使用原生 Vue + `useApi` / `useAuth`；不要用 `LegacyPanel`、`useLegacyPage`、`window.Samsara*` 或直接 `fetch`。
4. 需要旧内核时，先抽纯逻辑到 `src/pages/<page>/model.js` 并加单测，再由 Vue 页面挂载内核；保留 canvas / contenteditable / 3D / PageSpec 算法。
5. 改完先跑 `cd web-src && npm run check`（内含框架守卫）；有页面行为时跑对应 section E2E；提交前跑全量 `node tools/web-e2e.mjs`。
6. Java 改动跑 `gradle test` / `gradle build`。
7. 每个任务独立 commit；不提交临时文件；不自动部署生产，除非用户明确要求。
8. 如果用户只给一句需求，按 `docs/agent/tasks/_template.md` 补全目标、允许修改文件、验证命令，不要反问已经写在仓库里的规则。
