# skill-inbox（暂存区）

这里放**本项目暂时用不上、但换个项目可能用得上**的 skill，随 GitHub 一起传播。

- 不是 skill 源，不会被 OpenCode 加载，不占会话上下文。
- 每个子目录 = 一个 skill：`SKILL.md`（原样副本）+ `META.md`（来源、日期、为何暂存、适合什么项目）。
- 总账：`CATALOG.md`；变更记录：`SYNC.md`。
- 提升到 `.agents/skills/` 时，在 `META.md` 里标 `promoted`，不要直接删。
- 含密钥、token、内网地址的内容不入库。

维护流程见 `.agents/skills/skill-sync/SKILL.md`。

## 本仓库约定（2026-11-04）

- skill 目录：`.agents/skills/<id>/SKILL.md`；不要新建 `.opencode/skills`，除非确实要让项目版覆盖全局。
- 现有 P 级：`mc-build-deploy-test`、`docs-maintainer`、`version-policy`；G 级：`skill-curator`、`skill-sync`、`windows-powershell`。
- **G 级副本必须与 `~/.config/opencode/skills` 逐字一致**，项目专属信息写在本文件，不写进 SKILL.md（否则提升时会把本仓库内容带进全局）。
- 优先级：`.agents/skills` < `~/.config/opencode/skills` < 项目 `.opencode/skills`；同名时全局副本生效，项目副本只在全局缺失时兜底。
- 阻断开关：`.agents/.locust-off`。
- 敏感：仓库根有 `frp-dog.com.crt` / `frp-dog.com.key`（内网穿透私钥），任何 skill 或文档都不得写其内容、域名或密钥。
- 本仓库路径含空格，shell 一律用 `workdir`；RCON 的引号与编码坑见 `mc-build-deploy-test`。
