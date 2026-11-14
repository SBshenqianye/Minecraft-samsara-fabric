# Task: migrate announcements

## Goal
把 `mod/src/main/resources/web/announcements.js` 迁移为 `web-src/src/pages/AnnouncementsPage.vue`，删除该页的 `LegacyPanel` 与旧脚本加载。

## Scope
- allowed:
  - `web-src/src/pages/AnnouncementsPage.vue`
  - `web-src/src/pages/announcements/model.js`
  - `web-src/src/pages/announcements/model.test.js`
  - `tools/e2e/sections/announcements.mjs`
  - `tools/e2e/sections/index.mjs`
  - `tools/e2e/run.mjs`
- integration（本任务内允许一次）:
  - `web-src/src/router.js`（移除 `meta.panel`）
  - `web-src/src/navigation.js`（确认 keep-alive 名单）
  - `web-src/src/legacy/admin.js`（移除 `announcements.js`）
  - `web-src/src/layouts/AdminLayout.vue`（HERO_HINTS）
  - `tools/web-e2e.mjs`（公告断言改为原生页面）
  - `CHANGELOG.md` / `docs/实现状态.md`

## Contract
- Route: `/samsara/community/announcements`
- API:
  - `GET /api/announcements` → `{ announcements, triggers, targets, colors, overlays, clickActions, history }`
  - `PUT /api/announcements` body `{ announcements }`
  - `POST /api/announcements/preview` body `{ announcement }`
  - `POST /api/announcements/send` body `{ announcement, dryRun, confirm }`
  - `POST /api/announcements/stop` body `{ id? }`
- States: loading / empty / error / dirty / saving
- Keep-alive: yes（`AnnouncementsPage` 已在 `KEEP_ALIVE_PAGES`）

## 必须保留的行为
- 左侧公告列表 + 新建 / 刷新
- 右侧编辑器：基础 / 目标 / 展示 / 交互四组字段
- 保存、预览、发送（dryRun 确认后 confirm）、复制、删除
- `#announcements-summary`、`#announcements-message`、`#announcement-preview` 等 E2E 兼容 id
- 页面不显示全局左侧导航

## Acceptance
- [ ] `cd web-src && npm.cmd run check`
- [ ] `node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section announcements`
- [ ] `#panel-announcements` 不再被 Vue 页面使用
- [ ] `announcements.js` 不再被 `web-src/src/legacy/admin.js` 加载
- [ ] 全量 E2E 的公告断言改成原生页面后仍通过
- [ ] `CHANGELOG.md` / `docs/实现状态.md` 已更新

## Evidence
- 单测：`web-src/src/pages/announcements/model.test.js`
- E2E：`tools/e2e/sections/announcements.mjs`
- 旧脚本保留在 `mod/src/main/resources/web/announcements.js` 供 `/legacy.html` 回退，直到全量迁移完成。
