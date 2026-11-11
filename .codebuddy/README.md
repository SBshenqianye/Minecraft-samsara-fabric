# CodeBuddy skills

本目录由 `skill-sync` 的 `sync-targets.ps1 -Scope Project` 从 `.opencode/skills` 生成，
供 CodeBuddy 读取（CodeBuddy 的用户级目录是 `~/.codebuddy/skills`，项目级只认 `.codebuddy/skills`）。

- 不要直接修改这里的文件；改 `.opencode/skills/<id>/SKILL.md` 后重新同步。
- 两边内容应逐字一致，出现 diff 说明忘了同步。
- 重同步：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File "$HOME/.config/opencode/skills/skill-sync/scripts/sync-targets.ps1" `
  -Scope Project -ProjectRoot "<仓库根目录>"
```

- 只想预览：加 `-DryRun`。
