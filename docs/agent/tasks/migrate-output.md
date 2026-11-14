# Task: migrate output

## Goal
把 `mod/src/main/resources/web/output.js` 的输出格式页迁为 `web-src/src/pages/OutputPage.vue`。

**重要**：`output.js` 不是普通表单，而是 contenteditable 富文本编辑器（chips、拖拽、撤销/重做、服务端预览）。本任务采用 **保留内核、Vue 管外层** 的方式，不重写编辑器算法。

## Scope
- allowed:
  - `web-src/src/pages/OutputPage.vue`
  - `web-src/src/pages/output/*`
  - `tools/e2e/sections/output.mjs`
- integration（本任务内允许一次）:
  - `web-src/src/router.js`
  - `web-src/src/navigation.js`
  - `web-src/src/legacy/admin.js`
  - `web-src/src/layouts/AdminLayout.vue`（HERO_HINTS）
  - `tools/web-e2e.mjs`
  - `CHANGELOG.md` / `docs/实现状态.md`

## Contract
- Route: `/samsara/config/output`
- API:
  - `GET /api/output` → `{ messages, autoContinuePrefix }`
  - `PUT /api/output` body `{ key, parts }`
  - `POST /api/output/preview` body `{ key, parts }` → `{ fragments }`
  - `POST /api/output/reload`
  - `PUT /api/config` body `{ file:'commands', values:{ 'output.autoContinuePrefix': bool } }`
- Message: `{ key, group, description, editable, disabledReason, variables, buttons, parts, defaultParts }`
- 状态：loading / empty / error / dirty / undo-redo

## 必须保留的行为
- 消息列表（按 group 分组、搜索、选择）
- contenteditable 画布：文本、变量 chip、按钮 chip、前缀、换行、拖拽、撤销/重做
- 变量/按钮面板插入
- 客户端校验：未知变量/按钮、缺少必需项
- 服务端预览的颜色/粗斜体/下划线/删除线/可点击片段
- 保存并重载、重新读取、恢复默认、自动续行前缀
- E2E id：`output-editor`、`output-preview`、`output-errors`、`output-message`、`output-search` 等

## 推荐实现
1. 先把 `output.js` 的编辑器内核抽到 `pages/output/editor.js`，接受 `{ root, api, onChange }`，不再直接访问 `window.SamsaraUI`。
2. Vue 页面负责消息列表、搜索、工具栏、保存/预览/重载、自动续行开关。
3. 编辑器只负责 DOM→parts / parts→DOM / 光标 / 历史 / 拖拽。
4. 如果时间不足，允许先保留 `window.SamsaraUI.api` 适配层，但页面不能再用 `LegacyPanel`。

## Acceptance
- [ ] `cd web-src && npm.cmd run check`
- [ ] `node tools/agent/verify-page.mjs --page output --base http://127.0.0.1:8722 --token <token>`
- [ ] 全量 E2E 的输出格式断言改为原生页面后通过
- [ ] `output.js` 不再被 `web-src/src/legacy/admin.js` 加载
- [ ] `CHANGELOG.md` / `docs/实现状态.md` 已更新
