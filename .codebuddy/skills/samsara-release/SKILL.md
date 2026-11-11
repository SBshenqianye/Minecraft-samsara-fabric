---
name: samsara-release
description: samsara 仓库的开发发布流程：构建与单测、测试服部署与实测、提交 push、生产公告/停服/备份/替换 jar、重启与三处哈希验证。用于本仓库每次功能改动收尾。
---

# samsara 开发发布流程

## 何时使用

- 本仓库完成一轮代码改动，需要回归、部署测试服、再部署生产服。
- 用户说“测完、提交、提示、部署、重启”。

## 固定环境

- 仓库：`E:\workspace\deepseek harness\mc-mod-samsara`
- Java：`C:\Program Files\Java\jdk-26.0.2.1`
- Gradle：`C:\Users\Administrator\.gradle\wrapper\dists\gradle-9.5.1-bin\iq79hdu3mqx29lgffhp8bfmx\gradle-9.5.1\bin\gradle.bat`
- 测试服：`test-server`，Web 8722，RCON 25575，游戏 25566
- 生产服：`C:\Games\0-minecraft\server\servers\Vanilia 26.3`，Web 8720，RCON 25585
- 生产 `gold.syncEnabled=true`，测试服 false，不要改。

## 1. 构建与单测

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-26.0.2.1'
cd mod
& '<gradle.bat>' clean build --console=plain
```

- 从 `mod/build/test-results/test/TEST-*.xml` 统计 tests/failures。
- 记录 jar：`mod/build/libs/samsara-<版本>.jar` 与 SHA-256。

## 2. 测试服部署

先确认测试服 Java PID（不要误杀生产）：

```powershell
Get-CimInstance Win32_Process |
  Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'fabric-server-launch.jar' -and $_.CommandLine -match 'headless' }
taskkill /PID <pid> /F
Copy-Item -LiteralPath <新jar> -Destination test-server\mods\samsara-<版本>.jar -Force
```

启动测试服：用 `Start-Process` 直接启 java，**不要重定向到旧日志**（旧进程未释放日志会静默失败）：

```powershell
Start-Process -FilePath 'C:\Program Files\Java\jdk-26.0.2.1\bin\java.exe' `
  -WorkingDirectory '<repo>\test-server' -WindowStyle Hidden `
  -ArgumentList '-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-Xmx4096M','-Xms1024M','-XX:+UseG1GC','-jar','fabric-server-launch.jar','nogui'
```

等待约 20-25 秒，再 `GET http://127.0.0.1:8722/api/status` 确认启动完成。

## 3. 实测与回归

- Web 改动：`node tools/web-e2e.mjs --base http://127.0.0.1:8722 --token <token>`
- 游戏改动：用 Web RCON（`POST /api/server/console`）或 RCON 客户端执行命令。
- 需要假玩家：`player <name> spawn`，测完 `player <name> kill`。
- 记录证据：命令输出、状态、文件增长、日志中的失败/回滚。
- 不要清空配置、世界、玩家数据；不要改 `gold.syncEnabled` 差异。

## 4. 提交与推送

- 每阶段至少一个提交。
- 先 `git status` 确认没有误带 Web 构建产物（若本轮明确不改 Web）。
- `git add -A && git commit -m "..." && git push`。

## 5. 生产部署

1. 公告：`say [维护] ...`，先 `list` 检查在线人数。
2. 有人在线：60 秒倒计时；0 人可直接停服。
3. 找到 `Launch Server.bat` 的 cmd PID，`taskkill /PID <loop> /T /F` 连同 Java 一起结束。
4. 备份旧 jar 到 `backups/samsara/mod-jars/`，移除 `mods/` 下旧 samsara jar。
5. 复制新 jar，**先校验目标文件 SHA-256** 再启动。
6. 启动：优先用 WMI 脱离当前 shell：

```powershell
$cmd='cmd.exe /c "C:\Games\0-minecraft\server\servers\Vanilia 26.3\Launch Server.bat"'
Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
  CommandLine=$cmd; CurrentDirectory='C:\Games\0-minecraft\server\servers\Vanilia 26.3'
}
```

7. 生产启动会先做世界备份哈希，可能 3-4 分钟才有 Web；不要过早判定失败。
8. 验证 `GET /api/status` 的 `version` / `webVersion`，并抽查 `/api/lottery`、`/api/containers`、`/api/track`、`/user`。
9. 校验构建产物 / 测试服 / 生产服三处 jar SHA-256 一致。

## 坑

- 测试服/生产服的 `stop` 有时不会真正退出；不要只信 API，查端口和 PID，必要时 `taskkill`。
- `Start-Process -RedirectStandardOutput` 指向旧进程占用的日志会失败且无输出。
- `Copy-Item` 在旧 Java 仍持锁时可能静默失败；复制后必须立刻校验目标哈希。
- 生产 `Launch Server.bat` 是循环启动；直接替换 jar 前必须停循环或极快完成，否则会被旧进程锁住。
- 不要在生产验证时执行写配置、回滚、删备份等有副作用操作。
- 测试服 `server.properties` 与代码内置默认值不一致时，“恢复默认使用真实默认值”这类 E2E 断言会假失败（例：`pause-when-empty-seconds` 测试服为 0、默认 60）。先核对测试服配置，不要改代码去迎合环境。
- Web 前端改动同样要 `cd web-src && npm run build`，产物才会进入 jar；只构建后端会发布旧前端。
