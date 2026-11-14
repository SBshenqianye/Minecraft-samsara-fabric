# samsara Agent 接入

本目录放外部 Agent 的 prompt 模板；可加载的 skill 在：

```text
.opencode/skills/samsara-server/
```

## 文件

```text
AGENTS.md                         # 仓库级 Agent 开发约定
.opencode/skills/samsara-server/
  SKILL.md
  references/endpoints.md
  references/announcements.md
  references/config-editor.md
  scripts/samsara.ps1
  scripts/samsara.sh

docs/agent/
  tasks/_template.md              # 单页面迁移任务模板
  tasks/migrate-announcements.md  # 公告页样板
  legacy-inventory.md             # scan-legacy 生成
  legacy-inventory.json
  prompts/system.md
  prompts/operator.md
  README.md

tools/agent/
  scan-legacy.mjs                 # 扫描旧脚本 DOM/全局/API 依赖
  scaffold-page.mjs               # 生成 Vue 页面 + model + 单测 + 任务 spec

tools/e2e/
  harness.mjs                     # 浏览器/CDP/结果统计
  run.mjs                         # 按 section 跑页面级 E2E
  sections/announcements.mjs      # 公告页样板
```

## 代码迁移工作流

```powershell
node tools/agent/scan-legacy.mjs
node tools/agent/scaffold-page.mjs --name <page> --title <标题> --route <路由> --keep-alive
cd web-src && npm.cmd run check
node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token> --section <page>
```

具体见 `AGENTS.md` 与 `docs/agent/tasks/`。

## 使用

1. 从服务器 `config/samsara/web.yml` 读取 token。
2. 设置环境变量：

```powershell
$env:SAMSARA_URL='http://127.0.0.1:8720'
$env:SAMSARA_TOKEN='...'
```

3. 在项目内启动 OpenCode 会话（或在全局安装该 skill）。
4. 直接说：

```text
用 samsara-server skill 看一下服务器状态。
```

或显式加载：

```text
@samsara-server
```

## 会话目录

会话依托目录**可以是任意目录**：

- 只调用 Web API 时，目录完全不影响；API 只认 `SAMSARA_URL` / `SAMSARA_TOKEN`。
- 项目级 skill 只有在会话目录位于仓库内（或从仓库子目录启动）时才会被发现。
- 如果希望在任意目录使用，把 `.opencode/skills/samsara-server/` 复制到全局：
  `~/.config/opencode/skills/samsara-server/`，或在 `opencode.json` 的 `skills` 里加绝对路径。
- 会话目录还会影响文件工具的相对路径、项目配置、AGENTS.md 等；与 API 控制无关。

## 远程服务器

Web 只监听 `127.0.0.1`。远程 Agent 用 SSH 隧道：

```bash
ssh -L 8720:127.0.0.1:8720 user@server
```

然后仍然访问 `http://127.0.0.1:8720`。

## 安全

当前是单一管理员 token，没有分权。必须靠 prompt/skill 约束：

- 先 preview/dryRun
- 全服公告单独确认
- 配置带 revision
- 不默认使用 RCON
- 不泄露 token
