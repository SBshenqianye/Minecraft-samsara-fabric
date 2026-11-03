# Skill 总账（CATALOG）

级别：**P** = 项目级（仅本项目适用）/ **G** = 全局级（跨项目通用）/ **S** = 暂存（本项目不用，别处可能用）。

| ID | 级别 | 位置 | 来源 | 摘要 | 最近同步 | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| `docs-maintainer` | P | `.agents/skills/docs-maintainer` | 项目原创 | README/CHANGELOG 与文档口径维护、版本同步、链接校验 | 2026-11-03 | — |
| `mc-build-deploy-test` | P | `.agents/skills/mc-build-deploy-test` | 项目原创 | 编译 → 打包 → 部署 → 启动 → RCON 验证全流水线 | 2026-11-03 | — |
| `version-policy` | P | `.agents/skills/version-policy` | 项目原创 | Minecraft/Fabric 版本基线与升级流程 | 2026-11-03 | 基线 26.3 |
| `skill-curator` | G | 全局 + `.agents/skills/skill-curator` | `~/.config/opencode/skills` | 识别可复用经验并询问是否沉淀为 skill | 2026-11-03 | 双份：项目副本 = 核心 + 专属 |
| `skill-sync` | G | 全局 + `.agents/skills/skill-sync` | `~/.config/opencode/skills` | 全局 skill 汇总、判级、分发与回收 | 2026-11-03 | 双份：项目副本 = 核心 + 专属 |
| `windows-powershell` | G | 全局 + `.agents/skills/windows-powershell` | `~/.config/opencode/skills` | PowerShell 中文编码、空格路径、乱码与假警报 | 2026-11-04 | 实测 118 行被数成 70 行 |
| `cherry-tool-guide` | S | `.agents/skill-inbox/cherry-tool-guide` | `%APPDATA%\CherryStudio\Data\Skills` | Cherry Studio 工具路由 | 2026-11-04 | 仅该应用内有效，备份用 |
| `find-skills` | S | `.agents/skill-inbox/find-skills` | 同上 | 查找/安装可用的 agent skill | 2026-11-04 | 跨项目通用，可考虑提 G |
| `skill-creator` | S | `.agents/skill-inbox/skill-creator` | 同上 | 创建、修改、评估 skill | 2026-11-04 | 跨项目通用，可考虑提 G |

## 统计

- P：3　G：3　S：3
- 上次全量扫描：2026-11-04（多 agent 全扫，见下表）

## 已扫描的源（2026-11-04）

| Agent / 来源 | 位置 | 数量 | 结论 |
| --- | --- | --- | --- |
| OpenCode | `~/.config/opencode/skills` | 3 | 已收录（G） |
| Claude Code | `~/.claude/skills` | 0（目录不存在） | 跳过 |
| 通用兼容 | `~/.agents/skills` | 0（目录不存在） | 跳过 |
| dsh | `~/.dsh`（无全局 skills 目录）、项目 `.dsh/skills` | 0 | 跳过；仅升级快照里有内置 `dsh-use-browser` |
| 豆包 | `~/Doubao/skills` | 0（空目录） | 跳过 |
| Cherry Studio | `%APPDATA%\CherryStudio\Data\Skills` | 10（3 个有内容，7 个空目录） | 3 个进 inbox（S），空目录跳过 |
| GitHub Copilot app mod | `~/.ghcp-appmod/skills` | 11 | 第三方安装包，未收 |
| VS Code Copilot | `~/.vscode/agent-plugins/*/skills` + 扩展内置 | 45+ | 插件自带，未收 |
| 兄弟项目 | `mc-mod-samsara-trackv2/.agents/skills` | 3 | 与本项目同源；`mc-build-deploy-test` 本项目多 63 行，不回灌 |
| 同工作区其余项目 | `hanzi-ascii-art` / `mbti-filed` / `playground` 等 | 0 | 无 skills 目录 |
