# Task: migrate server console

## Goal
把 `mod/src/main/resources/web/server.js` 里的 RCON 控制台迁为 `web-src/src/pages/ServerConsolePage.vue`，server 其余面板暂留旧实现。

## Scope
- allowed:
  - `web-src/src/pages/ServerConsolePage.vue`
  - `web-src/src/pages/server-console/*`
  - `tools/e2e/sections/server-console.mjs`
- integration:
  - `web-src/src/router.js`（`/vanilla/console` 指向新页）
  - `web-src/src/navigation.js`（keep-alive）
  - `web-src/src/layouts/AdminLayout.vue`（serverMode / HERO）
  - `tools/web-e2e.mjs`
  - `CHANGELOG.md` / `docs/实现状态.md`

## Contract
- Route: `/vanilla/console`
- API:
  - `POST /api/server/console` body `{ command }` → `{ ok, command, output, timeMs, stopping }`
  - `POST /api/server/console/complete` body `{ command, cursor }` → `{ suggestions, range }`
- 状态：connecting / ready / running / error / stopping

## 必须保留的行为
- 命令输入、发送、输出日志、耗时
- 命令历史 ↑/↓
- Tab 补全与候选列表
- 快捷命令分组（常用/世界/玩家/调试/危险）
- 危险命令 `stop` 二次确认
- 过滤、清空、复制
- E2E id：`server-console-command`、`server-console-send`、`server-console-output`、`server-console-message`、`server-console-complete`

## 推荐实现
- 先抽出 `pages/server-console/model.js`：历史、过滤、快捷命令、响应格式化。
- 页面用 `useAuth` + `useApi`；不要依赖 `server.js` 的全局状态。
- `/vanilla/config|backup|audit` 继续走旧 `ServerPage`，直到后续任务拆分。

## Acceptance
- [ ] `cd web-src && npm.cmd run check`
- [ ] `node tools/agent/verify-page.mjs --page server-console --base http://127.0.0.1:8722 --token <token>`
- [ ] 全量 E2E 的 RCON 路由/执行/补全断言通过
- [ ] `server-console-panel` 不再由原生路由使用
- [ ] `CHANGELOG.md` / `docs/实现状态.md` 已更新
