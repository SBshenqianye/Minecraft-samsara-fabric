# Task: <page> 页面

## Goal
在 `web-src/src/pages/<XxxPage>.vue` 实现或重构页面；复杂算法放 `web-src/src/pages/<page>/`。

## Scope
- allowed:
  - `web-src/src/pages/<XxxPage>.vue`
  - `web-src/src/pages/<page>/model.js`
  - `web-src/src/pages/<page>/model.test.js`
  - `web-src/src/pages/<page>/*.vue`
  - `tools/e2e/sections/<page>.mjs`
- forbidden（除非任务明确允许）:
  - `web-src/src/layouts/AdminLayout.vue`
  - `web-src/src/router.js`
  - `web-src/src/navigation.js`
  - `mod/src/main/resources/web/*.js`
  - `mod/src/main/java/**`
  - `web-src/src/legacy-core/`、`web-src/src/legacy/`（已删除，不允许恢复）

## Contract
- Route: `<route>`
- API:
  - `GET <api>`
  - `<METHOD> <api>`
- States: loading / empty / error / dirty / saving
- Keep-alive: `<yes|no>`
- 交互必须保留：
  - `<list>`
  - `<editor fields>`
  - `<preview/save>`

## Acceptance
- [ ] `cd web-src && npm.cmd run check`
- [ ] `node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section <page>`
- [ ] 没有 `window.Samsara*` / `useLegacyPage` / 直接 `fetch` / `innerHTML`
- [ ] `CHANGELOG.md` / `docs/实现状态.md` 已更新

## Evidence
- 单测：`web-src/src/pages/<page>/model.test.js`
- E2E：`tools/e2e/sections/<page>.mjs`
- 失败/回滚说明：

## Notes
- 先抽纯逻辑，再写 UI；不要逐段翻译旧 DOM。
- 内核算法放 `pages/<page>/`，由 Vue 组件调用；禁止恢复旧目录或全局桥。
