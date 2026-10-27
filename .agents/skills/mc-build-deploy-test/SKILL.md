---
name: mc-build-deploy-test
description: "MC Fabric mod 编译→打包→部署→测试 全流水线。修复语法/编译错误后自动走完 gradle build → copy jar → restart server → RCON 验证。"
whenToUse: "when fixing compilation errors, building the mod, deploying to test server, or running RCON commands to verify changes in-game"
---

# MC Build → Deploy → Test 流水线

本 skill 管理 samsara Fabric mod 项目的 **编译 → 打包 → 部署 → 服务端测试** 全流程。
适用于任何 agent（主会话、子代理、Ralph 轮次）。

> **常量以 2026-10-27 的实测结果为准。** DSH 的 `Temp\dsh-*` 会话目录会在会话之间被清理，
> 所以工具链（JDK、Gradle、RCON 客户端）**不要硬编码在临时目录里**；每次会话先跑〈环境自检〉。

---

## 环境自检（新会话第一件事）

```powershell
# JDK：优先 Minecraft 启动器自带的正版 Java 25（生产服用它跑 26.3），
# 其次 Program Files 下的 jdk-25/26（--release 25 产物一致，class 69.0）
$candidates = @(
    "$env:APPDATA\.minecraft\runtime\java-runtime-epsilon\bin\java.exe"
)
$candidates += Get-ChildItem "C:\Program Files\Java\jdk-*" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match 'jdk-(25|26)' } | Sort-Object Name |
    ForEach-Object { Join-Path $_.FullName 'bin\java.exe' }
$jdk = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $jdk) { throw "未找到 Java 25/26；先安装再继续" }

# Gradle：用 ~/.gradle 里已缓存的 9.5.1 发行版（不要用 gradlew.bat）
$gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match 'gradle-9\.5\.1' } | Select-Object -First 1 -ExpandProperty FullName
if (-not $gradle) { throw "未找到缓存的 Gradle 9.5.1" }

$env:JAVA_HOME = Split-Path (Split-Path $jdk)
"JAVA=$jdk"
"GRADLE=$gradle"
& $jdk -version 2>&1 | Select-Object -First 1
```

**期望输出**（2026-10-27 实测）：
```
JAVA=C:\Users\Administrator\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon\bin\java.exe
GRADLE=C:\Users\Administrator\.gradle\wrapper\dists\gradle-9.5.1-bin\iq79hdu3mqx29lgffhp8bfmx\gradle-9.5.1\bin\gradle.bat
openjdk version "25.0.1" 2025-10-21 LTS
```

---

## 环境常量（2026-10-27 核对）

| 变量 | 值 | 说明 |
|---|---|---|
| `JAVA_HOME` | 由〈环境自检〉解析 → 当前 `%APPDATA%\.minecraft\runtime\java-runtime-epsilon`（Java 25.0.1） | 生产服就是用它跑 26.3；`Program Files` 下只有 jdk-17/21/24/26，**没有 jdk-25**，jdk-26 配 `--release 25` 也能编译。⚠ 本机 `JAVA_HOME` 默认指向 `jdk-8`，**必须显式覆盖**，否则 `UnsupportedClassVersionError` |
| `GRADLE_BIN` | `C:\Users\Administrator\.gradle\wrapper\dists\gradle-9.5.1-bin\iq79hdu3mqx29lgffhp8bfmx\gradle-9.5.1\bin\gradle.bat` | 缓存中的发行版；`gradlew.bat` 会尝试联网下载 |
| `GRADLE_USER_HOME` | `C:\Users\Administrator\.gradle`（默认值，勿覆盖） | 依赖、loom 缓存、wrapper 发行版都在这里；指到临时目录会重新下载 |
| `PROJECT_DIR` | `E:\workspace\deepseek harness\mc-mod-samsara\mod` | 模组源码目录 |
| `TEST_SERVER` | `E:\workspace\deepseek harness\mc-mod-samsara\test-server` | 测试服（**2026-10-27 起已是 MC 26.3**，端口 **25566**） |
| `RCON_CLIENT_SRC` | `tools\RconClient.java` | 单文件无依赖 RCON 客户端（退出码 0/1/2/3），**随仓库提交**，与 `tools/README.md` 一并维护 |
| `RCON_CP` | `.tmp\rcon` | `javac -d` 输出目录（`.class` 已被 `.gitignore` 忽略） |
| `RCON_HOST` / `RCON_PORT` / `RCON_PASS` | `127.0.0.1` / `25575` / `samsara-m1-test` | 只对 test-server 有效（生产服 RCON 关闭） |
| `JAR_PATTERN` | `mod\build\libs\samsara-*.jar` | 构建产物（排除 `*-sources.jar`） |
| `DEPLOY_MODS_DIR` | 见 Step 2 的两个目标 | 部署目录 |

---

## Step 1: 编译 + 打包（gradle build）

```powershell
# 承接〈环境自检〉解析出的 $jdk / $gradle
Set-Location "E:\workspace\deepseek harness\mc-mod-samsara\mod"
& $gradle build 2>&1
Write-Host "EXITCODE=$LASTEXITCODE"
```

**预期输出**：`BUILD SUCCESSFUL in Xm Xs`（依赖已缓存约 1.5 分钟，冷缓存 3-5 分钟）
**超时**：300 秒；首次会下载 loom/MC 依赖

**只能改编译参数，不要降 JDK**：MC 26.3 的 class 是 69.0（Java 25），
jdk-21/24 会直接失败（`release 25` 不被支持）。JDK 26 + `options.release=25` 是当前唯一可用组合。

**编译失败处理**：
1. 读取错误信息，定位到具体 `.java` 文件和行号
2. 修复代码（用 edit 工具）
3. 重新执行 Step 1
4. 如果连续 3 次失败，暂停并汇报具体编译错误

---

## Step 2: 部署 jar 到服务器的 `mods/`

### 2a. 选目标

| 目标 | 路径 | 版本现状 | 备注 |
|---|---|---|---|
| 测试服 | `E:\workspace\deepseek harness\mc-mod-samsara\test-server\mods\` | MC **26.3** · loader 0.20.1 · fabric-api 0.165.0+26.3 · 现存 samsara 0.11.0 | 2026-10-27 已升级；**端口 25566**（生产服占用 25565）；RCON 开在 25575，可直接验证 |
| 生产服 Vanilia 26.3 | `C:\Games\0-minecraft\server\servers\Vanilia 26.3\mods\` | MC **26.3** · loader 0.20.1 · fabric-api 0.165.0+26.3 | RCON 关闭（`enable-rcon=false`），只能看日志；换 jar 后需重启；**它可能在运行**，动它之前先确认 |

### 2b. 复制 + 校验

```powershell
$libs = "E:\workspace\deepseek harness\mc-mod-samsara\mod\build\libs"
$jar  = Get-ChildItem "$libs\samsara-*.jar" -Exclude '*-sources.jar' |
        Sort-Object LastWriteTime | Select-Object -Last 1
$dest = "E:\workspace\deepseek harness\mc-mod-samsara\test-server\mods"   # 或生产服 mods 目录
Copy-Item $jar.FullName $dest -Force
Get-FileHash $jar.FullName, (Join-Path $dest $jar.Name) | Select-Object Path, Hash
```

**验证**：两侧 SHA256 相同、文件大小 > 0。
⚠ 只复制主 jar，**不要**复制 `-sources.jar`（同名 mod 会被当成重复加载）。

### 2c. 部署前核对版本契约

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
$sr  = New-Object IO.StreamReader(($zip.Entries | Where-Object FullName -eq 'fabric.mod.json').Open())
($sr.ReadToEnd() -split "`n") | Select-String '"version"|minecraft|"java"'
$sr.Close(); $zip.Dispose()
```

jar 里 `minecraft` 约束必须与目标服务器核心版本一致；不一致就不要部署。

---

## Step 3: 检查服务器状态 & 按需重启

### 3a. 检查服务器是否在运行

```powershell
# 机器上常年有 VSCode/Gradle 的 java 进程，别按进程名判断服务端
Get-NetTCPConnection -LocalPort 25575 -ErrorAction SilentlyContinue | Select-Object State, OwningProcess
```

### 3b. 检查 RCON 是否可用

```powershell
$rc = "E:\workspace\deepseek harness\mc-mod-samsara\.tmp\rcon"
New-Item -ItemType Directory -Force -Path $rc | Out-Null

# 编译客户端（首次或源码改动后）
& "$env:JAVA_HOME\bin\javac.exe" --release 17 -encoding UTF-8 -d $rc `
  "E:\workspace\deepseek harness\mc-mod-samsara\tools\RconClient.java"

# 测试连接
& "$env:JAVA_HOME\bin\java.exe" -cp $rc RconClient 127.0.0.1 25575 samsara-m1-test "list"
```

退出码：`0` 成功 · `1` 参数错误 · `2` 连接失败 · `3` 认证失败。

**如果 RCON 连不上但服务端进程在**：只杀服务端进程（⚠️ 不要杀所有 java，否则编辑器/客户端也会被杀）：
```powershell
$serverPid = (Get-NetTCPConnection -LocalPort 25575 -ErrorAction SilentlyContinue | Where-Object { $_.State -eq 'Listen' }).OwningProcess
if ($serverPid) { Stop-Process -Id $serverPid -Force }
Start-Sleep -Seconds 3
```

### 3c. 启动服务器（如需）

```powershell
# $jdk 来自〈环境自检〉。⚠ -D 开头的 JVM 参数必须加引号，
# 否则 PowerShell 会把它拆成两段（JVM 报 ClassNotFoundException /encoding=UTF-8）
Set-Location "E:\workspace\deepseek harness\mc-mod-samsara\test-server"
& $jdk -server -Xmx2048M -Xms1024M -XX:+UseG1GC `
  "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" `
  -jar fabric-server-launch.jar nogui *> "logs\console-26.3.log"
```

> **端口**：测试服是 `25566`（`server.properties`）。生产服用 25565，两个可以同时开。
> 若日志出现 `FAILED TO BIND TO PORT ... Address already in use`，先查 25565/25566 的占用者，
> **不要**为了让测试服起来而去停生产服。

> `test-server\start-server.bat` 也能起服，但它的 JDK 探测正则只认 `version .25`；
> 它会在 `jdk25\`、`Program Files\Java\jdk-25`、`JAVA_HOME` 等位置找 Java，
> 找不到就报 “JDK 25 not found”——此时用上面的命令手动起，或先跑 `install-jdk25.bat`。

**等待 RCON 就绪**（最多 90 秒）：
```powershell
$timeout = 90; $elapsed = 0
while ($elapsed -lt $timeout) {
    Start-Sleep -Seconds 3; $elapsed += 3
    $port = Get-NetTCPConnection -LocalPort 25575 -ErrorAction SilentlyContinue | Where-Object { $_.State -eq 'Listen' }
    if ($port) { Write-Host "RCON up after ${elapsed}s"; break }
}
```

### 3d. 热替换（如服务器已在运行）

Fabric mod jar 在 `mods/` 目录被替换后，下次 `/reload` 或重启才生效。
- 如果改动不涉及 mixin/注册表：执行 `reload` 即可
- 如果涉及 mixin/注册表变更：必须重启服务器（kill + start）
- 自己的测试服优先用 RCON `stop` 优雅停服，再重启

---

## Step 4: RCON 测试验证

### RCON 客户端用法

```
RconClient <host> <port> <password> "<command>"
```

**注意**：命令必须用引号包裹为**单个参数**（漏引号会被拆成多个参数，客户端会把多余参数用空格拼回去）。
中文回包要在 PowerShell 里正确显示，先 `[Console]::OutputEncoding = [Text.Encoding]::UTF8`，
并给客户端加 `"-Dstdout.encoding=UTF-8"`。

### 常用测试命令（2026-10-27 在测试服实测通过）

| 用途 | RCON 命令 |
|---|---|
| 检查在线玩家 | `list` |
| 查看 mod 版本与游戏版本 | `samsara version` |
| 查看服务器性能 | `samsara mspt` / `samsara tps` |
| 查看周目列表 | `samsara season status [页码]` |
| 查看周目详情 | `samsara season info` |
| 查看命令树 | `help samsara` 或直接发 `samsara` |
| 查看圈地旗帜列表 | `samsara debug claim list` |
| 查看指定区块归属 | `samsara debug claim info <x> <z>` |
| 重建白名单 | `samsara debug claim rebuild` |
| 扫描世界中的圈地旗 | `samsara debug claim scan` |
| 圈地粒子预览 | `samsara debug claim particle …` |
| 删除未保留区块（预演） | `samsara debug delete dryrun` |
| 查看差异库状态 | `samsara debug diff` |
| 查看调试总览 | `samsara debug status` |
| 给予旗帜（需玩家在线） | `execute as @p at @s run samsara debug claim give <dir> <dist>` |
| 重载（仅 datapack） | `reload` |
| 停服 | `stop` |

> ⚠ 不存在 `samsara status` / `samsara claim list` / `samsara activity stats` 这类写法：
> 圈地与管理类命令都在 `samsara debug …` 下，周目类在 `samsara season …` 下；
> 活跃度系统已在 0.10.0 移除。用 `help samsara` 查真实命令树。

### 批量测试模板

```powershell
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$rc = "E:\workspace\deepseek harness\mc-mod-samsara\.tmp\rcon"
$java = "$env:JAVA_HOME\bin\java.exe"

# 逐条执行（每条单独调用 RconClient）
& $java "-Dstdout.encoding=UTF-8" -cp $rc RconClient 127.0.0.1 25575 samsara-m1-test "samsara version"
& $java "-Dstdout.encoding=UTF-8" -cp $rc RconClient 127.0.0.1 25575 samsara-m1-test "samsara debug claim list"
& $java "-Dstdout.encoding=UTF-8" -cp $rc RconClient 127.0.0.1 25575 samsara-m1-test "samsara debug claim info 0 0"
```

---

## Step 5: 服务端日志检查

如果测试结果不符预期，检查服务端日志：

```powershell
Get-Content "E:\workspace\deepseek harness\mc-mod-samsara\test-server\logs\latest.log" -Tail 50
# 生产服：
Get-Content "C:\Games\0-minecraft\server\servers\Vanilia 26.3\logs\latest.log" -Tail 50
```

关注关键字：`ERROR`、`WARN`、`Exception`、`samsara`、`周目管理`。
生产服 RCON 关闭，**日志是唯一**的验证途径。

---

## 错误恢复指南

| 错误 | 原因 | 解决 |
|---|---|---|
| `UnsupportedClassVersionError` | 用了 `JAVA_HOME` 默认的 jdk-8 | 先跑〈环境自检〉，显式覆盖 `JAVA_HOME` |
| `release version 25 not supported` | 用了 jdk-21/24 编译 | 换 jdk-25 或 jdk-26（本项目实测 jdk-26 可用） |
| `gradle.bat 不是可执行程序` / 找不到 gradle | 硬编码了已清理的 Temp 路径 | 用〈环境自检〉从 `~/.gradle/wrapper/dists` 解析 |
| `Failed to load native library` | Gradle native 锁文件损坏 | 删除 `%USERPROFILE%\.gradle\native\` 下的 `.lock` 文件 |
| `SetNamedSecurityInfoW failed ... grantWrite` | 工作区缺 WRITE_OWNER 权限，任何 pwsh 命令都被拒 | 先加载 `diagnose-windows-sandbox-acl` skill 修复权限 |
| RCON `Connection refused` | 服务端未启动 / 端口不通 / 生产服 RCON 未开 | 查端口监听与 `server.properties` 的 `enable-rcon` |
| `auth failed: wrong password` | RCON 密码错误 | 核对 `server.properties` 的 `rcon.password` |
| `Expected whitespace to end one argument` | RCON 命令没用引号包裹 | 命令作为单个带引号字符串传入 |
| `ClassNotFoundException: /encoding=UTF-8` | `-Dfile.encoding=…` 没加引号，被 PowerShell 拆成两段 | 给所有 `-D` 参数加引号 |
| `FAILED TO BIND TO PORT … Address already in use` | 25565 被生产服占用（测试服应是 25566） | 改测试服 `server-port`，不要停生产服 |
| mod 加载失败 `requires minecraft ~26.3` | 目标服版本低于 jar 契约 | 升级目标服，或改回与服务器匹配的 mod 版本 |
| `BUILD FAILED` | Java 编译错误 | 读取错误行号 → 修复 → 重新 build |

---

## 快速一键脚本（修复后全量验证）

```powershell
# 0. 环境自检（见文档开头，得到 $jdk / $gradle）

# 1. Build
Set-Location "E:\workspace\deepseek harness\mc-mod-samsara\mod"
& $gradle build 2>&1

# 2. Deploy
$libs = "E:\workspace\deepseek harness\mc-mod-samsara\mod\build\libs"
$jar  = Get-ChildItem "$libs\samsara-*.jar" -Exclude '*-sources.jar' | Sort-Object LastWriteTime | Select-Object -Last 1
Copy-Item $jar.FullName "E:\workspace\deepseek harness\mc-mod-samsara\test-server\mods\" -Force

# 3. RCON verify（假设服务器已运行，客户端已编译到 .tmp\rcon）
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$rc = "E:\workspace\deepseek harness\mc-mod-samsara\.tmp\rcon"
& "$env:JAVA_HOME\bin\java.exe" "-Dstdout.encoding=UTF-8" -cp $rc RconClient 127.0.0.1 25575 samsara-m1-test "list"
& "$env:JAVA_HOME\bin\java.exe" "-Dstdout.encoding=UTF-8" -cp $rc RconClient 127.0.0.1 25575 samsara-m1-test "samsara version"
& "$env:JAVA_HOME\bin\java.exe" "-Dstdout.encoding=UTF-8" -cp $rc RconClient 127.0.0.1 25575 samsara-m1-test "samsara debug claim list"
```

---

## 注意事项

1. **不要硬编码 Temp 路径** — `Temp\dsh-*` 会话间会被清理；JDK/Gradle/RCON 客户端一律用〈环境自检〉解析，或放在仓库内。
2. **JAVA_HOME 必须显式设置** — 本机默认指向 jdk-8；用正版启动器自带 Java 25 或 jdk-26（`--release 25`）。
3. **`-D` 开头的 JVM 参数必须加引号** — 否则 PowerShell 拆包，JVM 报 `ClassNotFoundException: /encoding=UTF-8`，服务器根本起不来。
4. **RCON 每条命令必须单独调用** — 参数顺序 `host port password command`，命令用引号包裹。
5. **RCON `give` 类命令需要玩家在线** — 用 `execute as @p at @s run ...` 包装。
6. **jar 文件名含版本号** — 部署时用通配符匹配，并排除 `-sources.jar`。
7. **Fabric mod 替换 jar 后需要重启** — 仅 `/reload` 不足以加载新的 mixin 或事件注册。
8. **部署前核对 `fabric.mod.json` 的 minecraft 约束** — 测试服与生产服现在都是 26.3，jar 必须是 `~26.3`。
9. **生产服 RCON 关闭** — 部署后只能靠 `logs\latest.log` 验证，且必须由用户重启服务端。
10. **测试服端口是 25566，生产服 25565** — 两者可并存；`FAILED TO BIND` 先查占用者，别停生产服。

---

## Step 6: 测试通过后更新项目文档

功能开发/修复完成并验证后，必须同步更新以下文档（按需选取）：

> **文档体系已于 2026-09-10 重构**：`HANDOVER.md` 与 `docs/周目系统设计文档.md` **均已删除**；
> 本节已按新体系更新。引用文档时一律用「文档名 + 章节标题」，**不要用章节编号**（编号会漂移失效）。

### 需更新的文档清单

| 文档 | 何时更新 | 更新内容 |
|---|---|---|
| `README.md` | 里程碑完成、新功能上线 | 「当前状态」功能表的状态列、版本号、命令结构 |
| `docs/实现状态.md` | **任何功能实现/修复后（必更）** | 把对应条目从 🔒/⚠ 改为 ✅ 或删除；更新 `F-xx` 状态表与统计数字；追加修订历史一行 |
| `docs/设计规范.md` | 新规范/约定 | 对应章节新增或更新 |
| `docs/需求文档.md` | 需求本身变化时 | 验收标准条目（**不要在这里写实现状态**，状态归《实现状态》） |
| `docs/设计文档.md` | 设计本身变化时 | 对应章节 |
| `mod/build.gradle` | 版本变更 | `mod_version` 字段 |
| `.gitignore` | 新增忽略项 | 如 `crash-reports/`、`.dsh-trash/` 等 |

### 更新模板

#### README.md 当前状态表更新
把对应功能行的状态列改为 `✅` / `⚠ 部分` / `🔒 未实现`，并在「说明」列写一句差额；
逐条明细写进 `docs/实现状态.md`，README 只给模块级状态。

#### docs/实现状态.md 条目更新
```markdown
| # | 条目 | 设计要求 | 代码现状 | 涉及文件 | 建议优先级 |
| 1 | **<条目名>** | <文档要求> | ✅ 已实现：<做法>（<日期>） | `<文件:行>` | — |
```
同时更新 `F-xx` 全表的对应行与末尾统计（`✅ x 条 · ⚠ y 条 · 🔒 z 条`），
并在〈修订历史〉追加一行。

### 注意事项

- 更新文档时保持**格式一致**（表格对齐、缩进统一）
- 版本号同步：`build.gradle` 的 `mod_version` → `README.md`
- `.gitignore` 新增项需确认不会误删重要文件
- **删除/移动文件用 `safe_delete`**（进回收站可还原），不要直接 `rm`
