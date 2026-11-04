# 同步日志（SYNC）

每次执行 `skill-sync` 追加一段，最新的放最上面。

## 2026-11-04（增强：加载即采集 + 蝗虫开关）

- 规则：`skill-sync` 加载时先做“项目 → 全局”采集，再分发
- 阻断：`~/.config/opencode/skills/.locust-off`（全机器）、`.agents/.locust-off`（单仓库）、`DENYLIST.md`、frontmatter `metadata.locust: false`
- 恢复：写全局前必须留快照 `~/.config/opencode/skills/.snapshots/<时间戳>/` + `MANIFEST.json`，保留最近 10 份
- 变更文件：`skill-sync/SKILL.md`（全局 + 项目两份）

## 2026-11-04（多 agent 全扫）

- 扫描源：`~/.config/opencode/skills`、`~/.claude/skills`、`~/.agents/skills`、`~/.dsh`、`~/Doubao/skills`、`%APPDATA%\CherryStudio\Data\Skills`、`~/.ghcp-appmod/skills`、`~/.vscode/agent-plugins/*/skills`、同工作区兄弟项目
- 新增（项目级）：无
- 新增（暂存）：`cherry-tool-guide`、`find-skills`、`skill-creator`（Cherry Studio；其余 7 个目录为空，跳过）
- 未收录：Copilot app mod 11 个、VS Code Copilot 45+ 个（厂商/安装包自带，用户裁决不收）
- 回灌评估：`mc-mod-samsara-trackv2` 的 3 个 skill 与本项目同源，本项目 `mc-build-deploy-test` 更新，无需回灌
- 冲突：无
- 备注：`skill-sync` 源清单已改为多 agent 实测表 + 自动发现命令 + "第三方不收"规则

## 2026-11-04

- 扫描源：`~/.config/opencode/skills`（3 个），`.claude/skills` / `.agents/skills` 仍不存在
- 新增（项目级 + 全局）：`windows-powershell` → 全局主副本 + `.agents/skills/windows-powershell`
- 新增（暂存）：无
- 更新：`CATALOG.md` 统计改为 P3 / G3 / S0
- 冲突：无
- 备注：来源是本次会话实测——默认 `Get-Content` 把 118 行读成 70 行、中文乱码、含空格路径报错

## 2026-11-03

- 扫描源：`~/.config/opencode/skills`
- 新增（项目级）：`skill-curator`、`skill-sync` 在 `.agents/skills/` 建立副本（G 级，但项目留副本用于传播）
- 新增（暂存）：无
- 更新：无
- 冲突：无
- 备注：首次建立总账；`.claude` / `.agents` 全局目录尚不存在
