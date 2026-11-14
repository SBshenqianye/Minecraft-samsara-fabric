# Task: migrate <page>

## Goal
把 `<legacy script>` 的 `<page>` 页面迁移为 `web-src/src/pages/<XxxPage>.vue`，删除该页的 `LegacyPanel` 与旧脚本加载。

## Scope
- allowed:
  - `web-src/src/pages/<XxxPage>.vue`
  - `web-src/src/pages/<page>/model.js`
  - `web-src/src/pages/<page>/model.test.js`
  - `tools/e2e/sections/<page>.mjs`
- forbidden（除非任务明确允许）:
  - `web-src/src/layouts/AdminLayout.vue`
  - `web-src/src/router.js`
  - `web-src/src/navigation.js`
  - `mod/src/main/resources/web/*.js`
  - `mod/src/main/java/**`

## Contract
- Route: `<route>`
- API:
  - `GET <api>`
  - `<METHOD> <api>`
- States: loading / empty / error / dirty / saving
- Keep-alive: `<yes|no>`
- 旧页面行为必须保留：
  - `<list>`
  - `<editor fields>`
  - `<preview/send/save>`

## Acceptance
- [ ] `cd web-src && npm.cmd run check`
- [ ] `node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section <page>`
- [ ] 旧 `#panel-<page>` 不再被使用
- [ ] `<legacy>.js` 不再被 `web-src/src/legacy/admin.js` 加载
- [ ] `CHANGELOG.md` / `docs/实现状态.md` 已更新

## Evidence
- 单测：`web-src/src/pages/<page>/model.test.js`
- E2E：`tools/e2e/sections/<page>.mjs`
- 失败/回滚说明：

## Notes
- 先抽纯逻辑，再写 UI；不要逐段翻译旧 DOM。
- 旧脚本保留在 `mod/src/main/resources/web/` 作为 `/legacy.html` 回退，直到全量迁移完成。
