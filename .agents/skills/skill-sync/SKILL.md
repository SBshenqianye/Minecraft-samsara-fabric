---
name: skill-sync
description: 汇总各 agent 的全局 skill，判级为"项目级 / 全局级 / 暂存"，把项目级的落到仓库、暂存级放进项目 inbox 随 GitHub 传播，并在项目与全局之间双向回收；用于保证经验随仓库走遍所有设备和项目。
whenToUse: 接手或 clone 新仓库后第一次开工时；用户说"同步 skill""把经验带过来""扫一下全局"时；新增或修改任何 skill 之后；任务收尾时。建议每次 git pull 后顺手跑一次。
---

# Skill 同步（skill-sync）

## 定位

把散落在各 agent 的全局 skill 汇总成一张总账，按项目适用性下发或暂存，实现"**经验随仓库传播**"：

- **全局目录只在本机**，换设备就没了；**项目目录跟着 GitHub 走**。
- 所以项目里存 skill 的真正目的不是"只给本项目用"，而是当**传播载体**。
- 目标状态（蝗虫过境）：每个仓库都带一份可用的 skill 库 + 一个待命区；新机器 clone 后经验自动到位。
- 本 skill 装在全局，**每次加载时先做一次“项目 → 全局”采集**：项目里打磨好的通用经验并回全局，再由全局分发到其他项目，形成闭环。

## 三层角色

| 层 | 位置 | 作用 |
| --- | --- | --- |
| 全局 | `~/.config/opencode/skills` 等 | 本机工作副本，主副本，不进仓库 |
| 项目 | `.agents/skills/<id>/` | 本项目会用到的 skill，随仓库传播 |
| 暂存 | `.agents/skill-inbox/<id>/` | 本项目用不上、但别的项目可能用得上，先随仓库带着 |

暂存区不是 skill 源，不会被自动加载，不占会话上下文；它同时是**备份区**：重装 agent 或换设备后，clone 仓库就能取回这些 skill。

## 源清单（本机实测位置，低 → 高优先级）

| # | Agent | 位置 | 备注 |
| --- | --- | --- | --- |
| 1 | Claude Code | `~/.claude/skills` | 不存在就跳过 |
| 2 | 通用兼容 | `~/.agents/skills` | 跨 agent 约定目录 |
| 3 | GitHub Copilot / app mod | `~/.ghcp-appmod/skills`、`~/.copilot` | 多为安装包 |
| 4 | Cherry Studio | `%APPDATA%\CherryStudio\Data\Skills` | 内置/市场 skill |
| 5 | VS Code Copilot | `~/.vscode/agent-plugins/*/skills`、扩展目录 | 插件自带，排除 |
| 6 | 豆包 | `~/Doubao/skills` | 可能为空 |
| 7 | dsh | 项目 `.dsh/skills`（未见全局目录） | 内置 skill 藏在升级快照里 |
| 8 | OpenCode | `~/.config/opencode/skills` | 主副本 |
| 9 | 项目 | `.opencode/skills`、`.agents/skills` | 高优先级，覆盖同名 |
| 10 | 配置 | `opencode.json(c)` 的 `skills` 数组 | 显式目录或 HTTP catalog |

### 自动发现（别只盯 opencode）

```powershell
Get-ChildItem $HOME -Directory -Force -Recurse -Depth 4 -Filter skills -EA 0 |
  Where-Object { $_.FullName -notmatch '\\node_modules\\|\\sessions\\|\\cache\\|\\.vscode\\extensions\\' }
```

漏了就往 `$APPDATA` / `$LOCALAPPDATA` 各补扫一层，并看 `~/.dsh`、`~/Doubao`、`~/.claude`、`~/.agents` 这类 agent 目录是否存在。新 agent 先只读报告，用户确认后再加进上表。

发现规则：`<root>/<id>/SKILL.md` 或 `<root>/<id>.md`；ID 取目录名/文件名，区分大小写；隐藏目录、`node_modules` 跳过；没有 `description` 的记为"未广告，不会被自动发现"。

### 收不收（用户裁决 2026-11-04）

- **用户自建/自改** → 收，照常判 P/G/S。
- **厂商技能包 / 安装包、插件自带**（如 `.ghcp-appmod` 的 completeness/constitution/planning、VS Code 扩展和 Copilot CLI 同步下来的成套包）→ **不收**。
- **其他第三方独立技能**（市场安装、能独立运行）→ **收进 inbox 当备份**，等级 S；即使绑死某个应用也照样收，`META.md` 注明适用环境即可。
- **agent 内置 skill**（如 dsh 升级快照里的 `dsh-use-browser`）→ 不收。
- inbox 平时不会被加载、不占上下文，**备份和跨设备同步优先于仓库精简**；不要拿“体积大/与本项目无关”当拒绝理由。

## 冲突规则

- 同 ID 多处存在时，按上表高优先级胜出，但要**记录所有来源**。
- 优先级（OpenCode 规则，低 → 高）：`.claude/skills` < `.agents/skills` < `~/.config/opencode/skills` < 项目 `.opencode/skills`。所以 `.agents/skills` 里的同名副本**打不过**全局，只当传播/备份；真要项目定制覆盖全局，必须放 `.opencode/skills`。
- 因此 G 级 skill 的项目副本必须与全局**逐字一致**，不要加"本项目专属"段，否则会跟着提升污染全局。项目专属信息写进 `skill-inbox/README.md` 或 P 级 skill。
- 内容不一致时**不静默覆盖**：先生成一句话 diff 摘要（谁多了哪段、日期谁新），问用户保留哪份或合并。

## 判级（三档）

- **P / 项目级**：绑定本仓库的技术栈、构建、部署、文档口径、验收标准。例：`mc-build-deploy-test`。
- **G / 全局级**：跨项目通用的工作方式、工具坑、沟通习惯。例：`skill-curator`、`skill-sync`。
- **S / 暂存**：当前项目用不上，但换个项目可能用得上（领域相关、技术栈相关、团队流程）。

四问定级：本项目还会再用 ≥2 次吗？是否依赖本项目的路径/命令/版本？换个项目还成立吗？放进本项目会不会误导后续 agent？

## 流程

1. **扫描**：按源清单建索引，读取全局清单 `~/.config/opencode/skills/.inventory.md` 和项目 `.agents/skill-inbox/CATALOG.md`。
2. **对比**：算出新增、更新、消失、冲突四类。
3. **判级**：逐条给 P / G / S。
4. **落地**（每步动手前把计划汇总给用户确认，一次确认可批量执行）：
   - 全局新 skill 且 P → 复制到 `.agents/skills/<id>/`
   - 全局新 skill 且 S → 复制到 `.agents/skill-inbox/<id>/` 并写 `META.md`
   - 项目 skill 且 G → 提议提升到全局（全局不进仓库，需单独确认）
   - inbox 里的 skill 现在适用 → 提升到 `.agents/skills/`，`META.md` 标 `promoted`
   - 已失效（依赖的版本/模块不存在）→ 移入 inbox 或删除，先问
5. **记账**：更新 `CATALOG.md`（全量表格）和 `SYNC.md`（本次变更一行一条）。
6. **汇报**：新增/更新/暂存/冲突各几条；冲突单独列，等裁决；不要用"已完成"糊过去。

## 加载即采集（项目 → 全局）

触发：本 skill 被加载时（自动发现或 `@skill-sync`），**先跑这一步，再做分发**。

1. 扫当前项目的 `.agents/skills`、`.opencode/skills`、`.dsh/skills`。
2. 与全局同名 ID 比对：
   - 全局没有 → 判断是否通用（不含项目路径、业务名、版本号绑定）。通用则**提议提升**；项目专属则保持 P 不动。
   - 全局已有且内容一致 → 记"已同步"。
   - 全局已有但内容不同 → 不覆盖，列 diff 问用户。
3. 提升前先给全局留快照（见下一节）。
4. 提升后写 `SYNC.md`：`promote <id> from <project> @ <date>`。
5. **跨仓库巡检（可选）**：用户要求“全量巡检”时，在工作区根（如 `E:\workspace`）下找所有 `*/.agents/skills` 逐个比对；平时不要每次加载都扫盘。

边界：含本仓库路径/命令/验收标准的项目级 skill **不自动提升**，否则全局会被单个仓库污染。判断不了就问。

## 全局清单（收敛用）

- 位置：`~/.config/opencode/skills/.inventory.md`（隐藏文件，不会被当成 skill 加载）。
- 一行一个：`id | 状态(global/parked/local) | 首次来源 | 最近同步 | 备注`。
- 作用：记住"已经统合过什么"，避免每次巡检重复提议同一批；只有内容哈希变了才重新问。
- 项目里的 `CATALOG.md` 是单仓库视角，全局 `.inventory.md` 是跨仓库视角，两边同步更新。

## 蝗虫开关：阻断与恢复

阻断（任一命中，sync 只读不写，只报告）：

| 粒度 | 做法 |
| --- | --- |
| 全机器 | 建 `~/.config/opencode/skills/.locust-off` |
| 单仓库 | 建 `<repo>/.agents/.locust-off`（或 `.no-locust`） |
| 单 skill | frontmatter 加 `metadata: { locust: false }`，或写入 `.agents/skill-inbox/DENYLIST.md` |
| 敏感内容 | 命中密钥/token/被 `.gitignore` 忽略的路径，永不传播 |

恢复（写入前必留快照）：

- 全局：`~/.config/opencode/skills/.snapshots/<yyyyMMdd-HHmmss>/<id>/` + `MANIFEST.json`（id、sha256、动作 `added|updated`、来源项目）。
- 项目：写入前确认 `git status` 干净，回滚靠 git 本身。
- 恢复最近一次：读最新 `MANIFEST.json` → `updated` 的用快照覆盖回去 → `added` 的直接删除 → 在 `SYNC.md` 记 `restore <snapshot>`。
- 只保留最近 10 份快照，超出按时间删最旧。

硬性约束：**没有快照就不许写全局**；快照失败就整体中止，不要"先写了再说"。

## 目录契约

```text
.agents/
├── skills/<id>/SKILL.md            # 会被加载
├── .locust-off                     # 可选：存在则本仓库不参与同步
└── skill-inbox/
    ├── CATALOG.md                  # 总账
    ├── SYNC.md                     # 变更日志
    ├── DENYLIST.md                 # 可选：不参与传播的 ID
    └── <id>/
        ├── SKILL.md                # 原样副本
        └── META.md                 # 来源/日期/为何暂存/适合什么项目
```

`META.md` 必填：`source`（绝对路径或 agent 名）、`synced`（日期）、`level`（S）、`why-parked`、`fits`、`sensitive`（true/false）。

## 安全与整洁

- **进仓库 = 会推到 GitHub**。同步前扫敏感信息（密钥、token、cookie、内网地址、隐私），命中就留在全局，`META.md` 标 `sensitive: true`，正文不入库。
- 用**复制**，不要 symlink / junction，跨设备会断。
- 项目内同 ID 只保留一份，避免遮蔽链混乱。
- 纯个人偏好、一次性调参不要塞进 `.agents/skills`：每个 skill 的 description 每轮都会进上下文，塞多了是纯损耗。

## 双向传播（蝗虫模式）

- 全局 → 项目：新机器、新仓库自动补齐。
- 项目 → 全局：在项目里打磨出的通用经验回收，下次别的项目也能拿到。
- 项目 → 项目：暂存区随 GitHub 走，在下一个仓库落地时重新判级。
- 反例：只做"全局 → 项目"单向，经验就锁死在单个仓库，换项目归零。

## 与 skill-curator 的分工

- `skill-curator`：判断"这条经验值不值得写成 skill"，写入前问。
- `skill-sync`：判断"已有的 skill 该放哪一层、要不要搬"，搬运前问。
- 两边都会问用户，但问题不重叠：curator 生产条目，sync 负责分发。

## 收尾自检

- [ ] 全局新增/修改的 skill 都已判级并落地
- [ ] 项目新增的通用经验已考虑回收全局
- [ ] CATALOG 与 SYNC 已更新
- [ ] 无敏感信息进入 git 跟踪范围
- [ ] 冲突项已明确列出，没有静默覆盖
