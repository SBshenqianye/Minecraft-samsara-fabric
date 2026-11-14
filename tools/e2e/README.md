# Web E2E

`tools/web-e2e.mjs` 是零依赖的 headless Edge/Chrome 冒烟测试；`harness.mjs` 只负责浏览器/CDP/结果统计。

## 运行

```powershell
# 全量回归（先启动测试服，Web 8722）
node tools/web-e2e.mjs --base http://127.0.0.1:8722 --token <token>

# 只跑某个页面/分区
node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section announcements
node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section permissions
node tools/e2e/run.mjs --list

# 两个都跑
node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section announcements,permissions
```

- `--browser <path>`：指定 Edge/Chrome。
- `SAMSARA_E2E_SCREENSHOT=<path>`：结束时额外截一张图。
- 退出码 0 表示全部断言通过；有失败时仍会跑完当前能继续的用例。

## run.mjs 与 sections/

- `run.mjs`：按 section 启动浏览器、注入 token、执行注册表里的页面级断言。
- `sections/index.mjs`：section 注册表；新迁移页面加自己的模块。
- `sections/announcements.mjs`：原生公告页样板（列表、编辑器、预览、dirty、无脚本错误）。

section 测试默认注入 token；真实 token 对话框流程仍由全量 `web-e2e.mjs` 覆盖。

## harness.mjs

- `parseArgs` / `browserPath` / `freePort`：命令行与浏览器探测。
- `CDP`：极简 DevTools 协议客户端（`send` / `once`）。
- `launchPage` / `closePage`：启动 headless 浏览器、连接第一个 page target、清理临时 profile。
- `createReporter`：`PASS/FAIL` 输出与最终汇总。

## 场景分区（当前顺序）

`web-e2e.mjs` 仍按真实用户路径串行执行，临时 profile 隔离，但同一轮内共享 `sessionStorage` 与页面状态：

1. 启动、token 连接、Web 版本号
2. 本体服务器配置（MOTD、server.properties、玩家选择）
3. 存档备份 / 世界栈时间线与栈层图标
4. 身份组、data UUID 详情、成就/统计
5. 配置编辑器、公告、Agent whoami、Idempotency
6. 经济业务、权限、圈地范围
7. 地图、世界栈总览、Diff
8. 天网容器、Web 背包、审计、独立容器页
9. 信息架构、旧路由、用户端
10. 清理与未捕获错误检查

后续拆分建议：把 `evaluate/until/navigate` 与共享上下文（`base/token/authHeaders/cdp`）收进一个 `ctx` 对象，再按上面的分区逐步移到 `tools/e2e/scenarios/*.mjs`；不要直接按行号切割，场景之间有先后依赖（例如 token、假玩家、容器夹具）。
