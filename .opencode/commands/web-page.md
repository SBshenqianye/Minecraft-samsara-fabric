---
description: 用仓库框架契约开始一个 Web 页面任务（新建/迁移/改功能）
agent: samsara-dev
---

任务：$ARGUMENTS

按 `docs/agent/framework-contract.md` 执行：

1. 在 `docs/agent/tasks/` 建一个任务 spec，写清目标、允许修改的文件、验证命令。
2. 如果是新页面：先跑 `node tools/agent/scaffold-page.mjs --name <page>`，再补 `router.js` 和 `WebSecurity.staticResource`。
3. 如果是旧内核页面：先抽 `src/pages/<page>/model.js` + Vitest，再改 Vue 控制层；保留 canvas / contenteditable / 3D / PageSpec 内核。
4. 实现后运行 `cd web-src && npm run check`。
5. 页面行为补 `tools/e2e/sections/<page>.mjs` 并运行它；提交前跑全量 `node tools/web-e2e.mjs`。
6. 只提交本任务相关文件，提交信息用 `feat/test/refactor(web): ...`。
