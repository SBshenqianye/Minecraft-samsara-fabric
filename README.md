# samsara · 周目管理

原版红石生存服务器的**世界生命周期管理系统**（Fabric 服务端模组 + 数据包 + 离线工具）。

> 三大主题：**空间**（区块圈地/保存/重生成）· **时间**（周目轮回/新出生点/活跃度）· **逆熵**（时间倒流/玩偶影像）
> 设计文档：`docs/周目系统设计文档.md`（16 章，里程碑 M0~M9）

## 仓库布局

```
samsara/
├── mod/        # Fabric 服务端模组（fabric-loom，mod id: samsara）
├── datapack/   # 数据包（新周目开场流程/分组传送，规划中；pack_format=101）
├── tools/      # 离线分析脚本（analyze.py：不开服读存档活跃度）
└── docs/       # 设计文档
```

## 当前状态：M1 周目状态机（已完成：编译验证 + 运行时验收；**已升级到 MC 26.2**）

> **服务端模组**：服务器只装本模组即得全部功能；客户端装了也可在单人存档使用（`environment: "*"` + `"server"` entrypoint，机制见 `mod/README.md`）。

- [x] mod 项目骨架（**已锁定 26.2 版本链**并编译通过：fabric-api 0.157.0+26.2 / loader 0.19.3 / loom 1.17 / Java 25，见 `mod/README.md`）
- [x] `tools/analyze.py` v0（多来源推断玩家最后登录：activity.log / 日志 / usercache，输出"距上次玩家登录 X 天"；零依赖，已实测）
- [x] M1：Season 模型 + season.json 持久化 + `/season create/start/end/status` 状态机
  - **编译**：Gradle 9.5.1 + loom 1.17 + JDK 25，BUILD SUCCESSFUL（26.1→26.2 代码零改动）
  - **运行时验收**（开发服 runServer + 生产服 test-server，均基于 26.2 + RCON）：`create → PENDING`、`start → ACTIVE`、`end → ENDED` 全流程正确；
    season.json 落盘（schemaVersion/currentSeasonId/startTime/endTime）且**重启后状态从磁盘恢复**
  - **调试指令集**：`/samsara debug status/json/reload/reset/create/settime/fakeplayers`（COMMANDS_OWNER，详见 `mod/README.md`）
- [ ] M2 分组出生 → M3 活跃度 → M4 区块管理/倒流 → M5 面板 → M6 玩偶影像 → M7 世界栈 → M8 里程碑/资格 → M9 身份同步

## 环境要求

- **JDK 25**（MC 26.2 硬性要求；本机 `C:\Program Files\Java` 只有 8/17/21/24，构建前需 `set JAVA_HOME=<JDK25>`）
- Gradle 9.5.1（wrapper 已升级；loom 1.17 需要 Gradle 9.x）
- Python 3（analyze.py 零依赖，仅标准库）

## M1 验收记录（2026-08-15）

```
> season create S1-Test same-world   → 已创建周目 #1「S1-Test」（same-world，PENDING）
> season start                        → 周目 #1 开始（ACTIVE）
> season status                       → #1 ACTIVE · 始于 …
> season end                          → 周目 #1 已结束（ENDED）
> season status                       → #1 ENDED · 止于 … · 当前：#1 ENDED
> season create S2-Test               → 已创建周目 #2（ID 递增正确）
[重启服务器]
> season status                       → #1 ENDED + #2 PENDING（持久化恢复 ✓）
> season start / end                  → #2 ACTIVE → ENDED（流转正确）
```

season.json 示例（`mod/run/config/samsara/season.json`，随服务器运行自动生成）：

```json
{
  "schemaVersion": 1,
  "currentSeasonId": 2,
  "worldStack": [],
  "seasons": [ { "id": 1, "name": "S1-Test", "state": "ENDED", "mode": "same-world", ... } ]
}
```

## 生产模式测试服务器（test-server/）

一个**不依赖 Gradle、可直接双击启动**的 Fabric **26.2** 服务器，用于验证"生产加载路径"（模组从 `mods/` 目录扫描加载，而非开发服的 classpath 注入）：

```
test-server/
├── start-server.bat       # 启动脚本（自动找 JDK 25；双击即可）
├── fabric-server-launch.jar  # fat jar 启动器（loader+asm+mixin，与官方 installer 同构）
├── server.jar             # MC 26.2 服务端（bundler）
├── libraries/             # fabric-loader 及其依赖
├── mods/                  # ★ 模组放这里：samsara-0.3.0.jar + fabric-api-0.157.0+26.2.jar
├── server.properties      # 已开 RCON（25575，密码 samsara-m1-test）便于测试
└── eula.txt               # 已同意 EULA
```

- 启动：双击 `start-server.bat`（或 `java -jar fabric-server-launch.jar nogui`）
  - 脚本会**逐候选校验 JDK 版本 ≥25**（不再盲信 `JAVA_HOME` —— 若 `JAVA_HOME` 指向 JDK 8 会直接 UnsupportedClassVersionError 崩）
  - 找不到 JDK 25 时运行 `install-jdk25.bat` 自动下载安装到 `.\jdk25\`，再重试
- 已实测（26.2）：42 个模组从 `mods/` 正常加载，`/season create→start→end` 与 `/samsara debug` 全部可用
- **注意**：本目录已 gitignore（含 server.jar 等大文件），如需重新生成可参照 `mod/README.md` 的说明

> 背景：fabric-installer 在本沙箱无法联网（Windows 信任库被封锁），故按官方同构手工组装；期间定位并修复了两个关键点——fat jar 需写入 `Implementation-Version`（mixin 靠它探测 ASM 版本，否则 JAVA_25 兼容级判定失败）、`libraries/` 仅需 loader 的 7 个依赖。
