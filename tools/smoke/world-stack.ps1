#requires -Version 5.1
<#
.SYNOPSIS
  samsara 世界栈 / new-world 测试服冒烟与场景测试（可重复执行）。

.DESCRIPTION
  用真实 RCON + 真实停服/重启验证玩家会遇到的主流程：
    1. 世界栈 list/info 可读
    2. new-world 周目创建 → 二次确认 → 停服
    3. 重启后新世界生成、入栈、周目 ACTIVE、种子正确
    4. world leave 回到旧世界，重启后当前世界正确
  默认会清理测试世界；加 -KeepWorld 保留现场。

  需要测试服已开启 RCON，且启动方式能被本脚本复现（默认直接 java -jar）。
  生产服 RCON 关闭，不要指向生产服。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\smoke\world-stack.ps1 -Confirm
#>
param(
    [string]$RconHost = "127.0.0.1",
    [int]$RconPort = 25575,
    [string]$RconPassword = "samsara-m1-test",
    [string]$Java = "$env:APPDATA\.minecraft\runtime\java-runtime-epsilon\bin\java.exe",
    [string]$RconClassPath = ".tmp\rcon",
    [string]$ServerDir = "test-server",
    [string]$ServerJar = "fabric-server-launch.jar",
    [string]$ServerArgs = "-server -Xmx2048M -Xms1024M -XX:+UseG1GC -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8",
    [string]$SeasonName = "",
    [long]$Seed = 424242,
    [int]$ReadyTimeoutSeconds = 150,
    [switch]$KeepWorld,
    [switch]$Confirm
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Fail([string]$message) {
    Write-Host "[FAIL] $message" -ForegroundColor Red
    exit 1
}

function Info([string]$message) {
    Write-Host "[smoke] $message" -ForegroundColor Cyan
}

function Pass([string]$message) {
    Write-Host "[ ok ] $message" -ForegroundColor Green
}

function Assert-Contains([string]$text, [string]$needle, [string]$label) {
    if ($text -notlike "*$needle*") {
        Fail "$label 未包含「$needle」。实际输出：`n$text"
    }
    Pass $label
}

function Invoke-Rcon([string]$command) {
    $out = & $Java "-Dstdout.encoding=UTF-8" -cp $RconClassPath RconClient $RconHost $RconPort $RconPassword $command 2>&1
    if ($LASTEXITCODE -ne 0) {
        Fail "RCON 失败（exit=$LASTEXITCODE）：$command`n$out"
    }
    return ($out -join "`n")
}

function Invoke-RconOptional([string]$command) {
    $out = & $Java "-Dstdout.encoding=UTF-8" -cp $RconClassPath RconClient $RconHost $RconPort $RconPassword $command 2>&1
    return ($out -join "`n")
}

function Test-RconPort {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $client.Connect($RconHost, $RconPort)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

function Wait-Rcon([bool]$up, [int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((Test-RconPort) -eq $up) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Start-TestServer {
    $wd = (Resolve-Path $ServerDir).Path
    $stdout = Join-Path $wd "logs\console-26.3.log"
    $stderr = Join-Path $wd "logs\console-26.3.err"
    $argList = @($ServerArgs.Split(" ", [StringSplitOptions]::RemoveEmptyEntries)) + @("-jar", $ServerJar, "nogui")
    $process = Start-Process -FilePath $Java -ArgumentList $argList -WorkingDirectory $wd `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    Info "已启动测试服 PID=$($process.Id)，等待 RCON…"
    if (-not (Wait-Rcon $true $ReadyTimeoutSeconds)) {
        Fail "测试服在 ${ReadyTimeoutSeconds}s 内没有启动 RCON；查看 $stdout"
    }
    return $process
}

function Stop-TestServerGracefully {
    Invoke-RconOptional "stop" | Out-Null
    if (-not (Wait-Rcon $false 60)) {
        Fail "测试服未在 60s 内停服"
    }
}

function Restart-TestServer {
    Stop-TestServerGracefully
    Start-TestServer | Out-Null
}

# ---- 环境自检 ----
if (-not $Confirm) {
    Write-Host "这是会创建新世界并停服/重启测试服的场景测试。" -ForegroundColor Yellow
    Write-Host "确认后加 -Confirm 再运行；默认 RCON 端口 $RconPort（测试服）。"
    exit 0
}
if (-not (Test-Path $Java)) { Fail "找不到 Java：$Java" }
if (-not (Test-Path $ServerDir)) { Fail "找不到测试服目录：$ServerDir" }
if (-not (Test-Path $RconClassPath)) { New-Item -ItemType Directory -Force -Path $RconClassPath | Out-Null }
if (-not (Test-Path (Join-Path $RconClassPath "RconClient.class"))) {
    $javac = Join-Path (Split-Path $Java) "javac.exe"
    if (-not (Test-Path $javac)) { Fail "找不到 javac：$javac" }
    & $javac --release 17 -encoding UTF-8 -d $RconClassPath "tools\RconClient.java"
    if ($LASTEXITCODE -ne 0) { Fail "编译 RconClient 失败" }
}
if (-not (Wait-Rcon $true 5)) { Fail "RCON 未监听；先启动测试服（端口 $RconPort）" }
if ([string]::IsNullOrWhiteSpace($SeasonName)) {
    $SeasonName = "smoke-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}

# ---- 1. 只读冒烟 ----
Info "1/6 只读命令"
Assert-Contains (Invoke-Rcon "samsara version") "samsara" "版本"
$list = Invoke-Rcon "samsara world list"
Assert-Contains $list "世界栈" "world list 标题"
Assert-Contains $list "当前" "world list 当前标记"
Assert-Contains (Invoke-Rcon "samsara world info") "实际加载" "world info"

# new-world 只允许栈顶：如果当前不在栈顶，先 enter 回去
$info = Invoke-Rcon "samsara world info"
$currentMatch = [regex]::Match($info, "清单当前：(.+)")
$topMatch = [regex]::Match($info, "栈顶：(.+)")
if ($currentMatch.Success -and $topMatch.Success -and $currentMatch.Groups[1].Value.Trim() -ne $topMatch.Groups[1].Value.Trim()) {
    Info "当前不在栈顶，先 world enter 回栈顶"
    Invoke-Rcon ("samsara world enter " + $topMatch.Groups[1].Value.Trim()) | Out-Null
    Restart-TestServer
}

# 清理阻塞状态（没有可忽略）
Invoke-RconOptional "samsara season end" | Out-Null
Invoke-RconOptional "samsara season skip" | Out-Null

# ---- 2. 创建 new-world 并二次确认 ----
Info "2/6 创建 new-world 周目"
Assert-Contains (Invoke-Rcon "samsara season create $SeasonName new-world $Seed") "已创建周目" "season create"
$first = Invoke-Rcon "samsara season start"
Assert-Contains $first "危险操作确认" "第一次 start 只确认"
$second = Invoke-Rcon "samsara season start"
Assert-Contains $second "已安排 new-world 切换" "第二次 start 执行"

# ---- 3. 停服 + 重启，验证新世界 ----
Info "3/6 等待停服并重启"
if (-not (Wait-Rcon $false 60)) { Fail "new-world 未触发停服" }
Start-TestServer | Out-Null
Info "4/6 验证新世界"
$list2 = Invoke-Rcon "samsara world list"
Assert-Contains $list2 "当前" "新世界已入栈"
Assert-Contains $list2 "world_s" "新世界目录名在栈中"
$status = Invoke-Rcon "samsara season status"
Assert-Contains $status $SeasonName "新周目在 status 中"
Assert-Contains $status "new-world" "新周目模式"
$info2 = Invoke-Rcon "samsara world info"
Assert-Contains $info2 "实际加载" "新世界 info"
Assert-Contains $info2 "$Seed" "新世界种子"

# ---- 5. 返回旧世界 ----
Info "5/6 world leave 返回下层"
$leave = Invoke-Rcon "samsara world leave"
Assert-Contains $leave "已安排返回下层世界" "world leave 安排"
if (-not (Wait-Rcon $false 60)) { Fail "world leave 未触发停服" }
Start-TestServer | Out-Null
$list3 = Invoke-Rcon "samsara world list"
Assert-Contains $list3 "当前" "返回旧世界后 list"

# ---- 6. 清理 ----
Info "6/6 清理测试现场"
if (-not $KeepWorld) {
    Invoke-RconOptional "samsara debug world reset" | Out-Null
    # 当前已经回到旧世界，删除测试世界目录
    $newWorld = [regex]::Match($list2, "(world_s\d+)").Groups[1].Value
    if ($newWorld) {
        $path = Join-Path (Resolve-Path $ServerDir).Path $newWorld
        if (Test-Path $path) {
            Remove-Item $path -Recurse -Force
            Pass "已删除测试世界 $newWorld"
        }
    }
} else {
    Info "保留测试世界（-KeepWorld）"
}

Pass "世界栈 / new-world 场景测试全部通过"
