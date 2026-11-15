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

- Web 改动先跑全部 section：

  ```powershell
  node tools/e2e/run.mjs --base http://127.0.0.1:8722 --token <token>
  ```

- 再跑全量：

  ```powershell
  node tools/web-e2e.mjs --base http://127.0.0.1:8722 --token <token>
  ```

- 两者都必须 0 失败、exit 0 才能进入生产部署；section 失败时先修当前页面，不要直接跑全量掩盖。
- 游戏改动：用 Web RCON（`POST /api/server/console`）或 RCON 客户端执行命令。
- 需要假玩家：`player <name> spawn`，测完 `player <name> kill`。
- 记录证据：命令输出、状态、文件增长、日志中的失败/回滚。
- 不要清空配置、世界、玩家数据；不要改 `gold.syncEnabled` 差异。

## 4. 提交与推送

- 每阶段至少一个提交。
- 先 `git status` 确认没有误带 Web 构建产物（若本轮明确不改 Web）。
- `git add -A && git commit -m "..." && git push`。

## 5. 生产部署

> 2026-11-15 起优先用一键脚本，下面 1~9 条是它内部做的事（便于排障）：
>
> ```powershell
> powershell -NoProfile -ExecutionPolicy Bypass -File tools/release/deploy-jar.ps1 `
>   -Jar mod/build/libs/samsara-0.41.2.jar `
>   -ServerDir 'C:\Games\0-minecraft\server\servers\Vanilia 26.3' `
>   -BaseUrl http://127.0.0.1:8720 -Token <token> `
>   -AllowOnline -UpdateNote '0.62.7 弹窗统一为框架弹窗，修复圈地预览边界'
> ```
>
> 脚本会：写 `maintenance.flag` → 公示更新摘要 → 倒计时 → 优雅停服 → 等 java 退出 →
> 备份旧 jar → 换 jar 并校验 SHA-256 → 删标记 → WMI 启动 → 等 `/api/status` 并打印版本。

1. **必须先公示更新内容**：`-UpdateNote` 一句话摘要（或手动 `say [维护] 稍后更新：...`）。
   只喊“停服”不给内容不算完成发布提醒。
2. 公告后 `list` 检查在线人数；有人在线用 `-AllowOnline`（脚本默认 60 秒倒计时）。
3. **必须优雅停服**：
   - `tools/release/stop-server.ps1` 会拒绝在有人在线且未加 `-AllowOnline` 时停服。
   - 脚本只发 RCON `stop` 并等待退出；超时默认不强杀，必须人工检查后再决定是否 `-ForcePid`。
   - 不要在玩家在线时强杀进程：未保存的区块/玩家数据会回档到上一次自动保存。
4. `maintenance.flag` 由部署脚本在停服前写入，`Launch Server.bat` 检测到就不再自动重启；
   换完 jar 后脚本会删掉它。若手工部署，记得自己写/删，否则会出现“停了又自己起来”。
5. 备份旧 jar 到 `backups/samsara/mod-jars/`，移除 `mods/` 下旧 samsara jar。
6. 复制新 jar，**先校验目标文件 SHA-256** 再启动。
7. 启动：优先用 WMI 脱离当前 shell：

```powershell
$cmd='cmd.exe /c "C:\Games\0-minecraft\server\servers\Vanilia 26.3\Launch Server.bat"'
Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
  CommandLine=$cmd; CurrentDirectory='C:\Games\0-minecraft\server\servers\Vanilia 26.3'
}
```

8. 生产启动会先做世界备份哈希，可能 3-4 分钟才有 Web；不要过早判定失败。
9. 验证 `GET /api/status` 的 `version` / `webVersion`，并抽查 `/api/lottery`、`/api/containers`、`/api/track`、`/user`。
10. 校验构建产物 / 测试服 / 生产服三处 jar SHA-256 一致。

## 坑

- 测试服/生产服的 `stop` 有时不会真正退出；不要只信 API，查端口和 PID，**先等优雅停服**，超时再 `taskkill`。
- **禁止在玩家在线时直接 `taskkill /F`**：未保存的区块与玩家数据会回档到上一次自动保存。我们 2026-11-15 的连续部署中曾这样做，导致 kalinote 的进度回档；之后必须走 `stop` + 等待退出。
- `Start-Process -RedirectStandardOutput` 指向旧进程占用的日志会失败且无输出。
- `Copy-Item` 在旧 Java 仍持锁时可能静默失败；复制后必须立刻校验目标哈希。
- **绝不要在旧 Java 还在运行时覆盖 jar**：Windows 不报错，但旧 JVM 仍用启动时读到的 zip 索引，结果 `index.html` 是新的、`/vue/assets/*.js` 全 404，前端白屏。必须先停服并确认 java 进程退出，再复制。
- 生产 `Launch Server.bat` 是循环启动：java 退出后会 `timeout /t 5` 再重启。要换 jar 就必须在 java 退出后 5 秒内结束循环 cmd，否则会带着旧 jar 自动重启（本次部署就遇到过一次自重启）。
- 更稳的换 jar 顺序（2026-11-15 第二次部署实践）：先 `Stop-Process -Id <循环 cmd>`（只结束 cmd，不碰 java，java 会继续跑），确认 java 仍在运行且 8720 仍可用，再 `stop-server.ps1` 优雅停服；这样不存在 5 秒抢跑窗口。
- 停服后要同时检查 `java.exe`（fabric-server-launch.jar）与 `cmd.exe`（Launch Server.bat）；循环 cmd 还在时，任何“已停服”都是临时的。
- 重装 jar 后记得同步测试服那一份，否则“三处哈希一致”会被版本号差异打破。
- 不要在生产验证时执行写配置、回滚、删备份等有副作用操作。
- 测试服 `server.properties` 与代码内置默认值不一致时，“恢复默认使用真实默认值”这类 E2E 断言会假失败（例：`pause-when-empty-seconds` 测试服为 0、默认 60）。先核对测试服配置，不要改代码去迎合环境。
- Web 前端改动同样要 `cd web-src && npm run build`，产物才会进入 jar；只构建后端会发布旧前端。
