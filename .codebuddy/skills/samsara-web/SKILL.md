---
name: samsara-web
description: samsara 仓库 Web 管理控制台（Vue 3 + Vite）与公开用户端的开发约定：新增页面接线、router-view/keep-alive、useApi/useAuth、Element Plus、同源 iframe、地图瓦片/头像、ItemWorkbench 复用、E2E 与框架守卫。用于本仓库任何 Web 前端或 Web API 改动。
---

# samsara Web 前端约定

## 何时使用

- 修改 `web-src/src/` 下的 Vue 页面、组件、composable、路由。
- 新增/调整 Web 路由、导航、SPA 静态路径、Web API。
- 编写或修改 `tools/web-e2e.mjs` / `tools/e2e/sections/`。

## 固定位置

- 源码：`web-src/src/`；构建产物 `mod/src/main/resources/web/vue/`。
- 检查：`cd web-src && npm run check`（框架守卫 + ESLint + vue-tsc + Vitest + build）。
- 框架契约：`docs/agent/framework-contract.md`。
- 旧内核审计：`docs/agent/legacy-audit-and-guardrails.md`。
- 任务模板：`docs/agent/tasks/_template.md`。
- 后端：`mod/src/main/java/com/shenqianye/samsara/web/`。
- 信息架构：`web-src/src/navigation.js`。

## Web 硬规则

1. 页面只由 `router.js` 决定；内容区 `<router-view>`，不要往 `AdminLayout` 加页面 `v-if`。
2. HTTP/token 只用 `useApi` / `useAuth`；页面/组件禁止直接 `fetch`。
3. 新页面禁止 `import legacy-core/`、`useLegacyPage`、`window.Samsara*`。
4. 需要旧内核时，先抽 `src/pages/<page>/model.js` + 单测，再由 Vue 挂载；保留 canvas / contenteditable / 3D / PageSpec 算法。
5. 禁止 `innerHTML` / `insertAdjacentHTML` / `document.write`。
6. 新增页面接线两处：`router.js` + `WebSecurity.staticResource`；子导航再加 `navigation.js`。
7. 需要保留状态：加入 `KEEP_ALIVE_PAGES`，并 `defineOptions({ name: 'XxxPage' })`。
8. 不新增 `/web/*.js` 静态脚本；旧静态壳已删除。

## 新增页面

1. `node tools/agent/scaffold-page.mjs --name <page>`（如适用）。
2. 在 `docs/agent/tasks/` 写任务 spec，列出允许修改的文件。
3. 写原生 Vue 页面，复用 `TabShell` / `Segmented` / `ItemWorkbench`。
4. `router.js` 加路由；`WebSecurity.java` 加 SPA 路径。
5. 需要子导航时改 `navigation.js`。
6. 加 `tools/e2e/sections/<page>.mjs`。
7. 跑 `npm run check` + section E2E + 全量 E2E。

## 旧内核迁移

按页面绞杀，单页顺序：

```text
纯函数/格式化/计算
→ src/pages/<page>/model.js + Vitest
→ Vue 控制层/生命周期
→ 删除 window.Samsara* 桥
→ 从 check-framework.mjs 白名单移除
```

已抽取的 model：

- `pages/world-stack/model.js`：图标 region/URL/标签、字节/时间/来源/排序
- `pages/config/model.js`：配置值、搜索、条件显示、计数
- `pages/lottery/model.js`：VP/游戏日/时间/日期
- `pages/server/model.js`：字节/时间/properties 拆分

## 验证

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

- 页面行为必须补 section E2E。
- 提交前全量 E2E 必须保持 281/281（新增断言可增加总数）。
- 只提交本任务相关文件；不提交 `.tmp/`、日志、mapskip 副本。

## 坑

- **Element Plus 样式**：组件样式按需注入；`ElMessage` / `ElMessageBox` 是 API 调用，必须显式 import 对应 CSS。
- **CSP/iframe**：同源 iframe 需要 `X-Frame-Options: SAMEORIGIN` 与 CSP `frame-ancestors 'self'`。
- **`<img>` 不能带 Bearer**：地图瓦片/头像走 `/api/user/map/*` 公开只读端点。
- **不要给 `CustomModelData` 写 0**：会被当成无组件。
- **物品逻辑只写一份**：拖拽/合并/编辑复用 `ItemWorkbench.vue`。
- **构建产物要提交**：只改后端不跑 `npm run build` 会发布旧前端。
- **框架守卫会拦**：新页面直接 import `legacy-core`、`fetch`、`window.Samsara*`、`innerHTML` 都会失败。
