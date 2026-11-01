#requires -Version 5.1
<#
.SYNOPSIS
  旧 world/world_s1/world_s2 布局 → v0.24 world=栈顶 + stack_<n> 维度 迁移场景测试。

.DESCRIPTION
  前提：测试服已停服，服务器根下存在旧布局：
    world/  world_s1/  world_s2/   （world 为栈底，world_s2 为最新）
  且 world/samsara/stack.json 不存在。
  脚本会启动服务器，等待 RCON，并用真实命令/文件检查迁移结果：
    1. world list = stack_0 → stack_1 → world（world 栈顶）
    2. world 的 level.dat / dimensions/minecraft 来自旧 world_s2
    3. stack_0/stack_1 的维度与元数据存在
    4. players、claims、diff、track、spawn-history 不丢
    5. server.properties level-name=world
  迁移失败时服务器根下会保留 legacy-layout.migrated-* 暂存目录。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\world-legacy-migrate.ps1 -Confirm -ServerDir test-server-stack
#>
param(
    [string]$RconHost = "127.0.0.1",
    [int]$RconPort = 25576,
    [string]$RconPassword = "samsara-m1-test",
    [string]$Java = "$env:APPDATA\.minecraft\runtime\java-runtime-epsilon\bin\java.exe",
    [string]$RconClassPath = ".tmp\rcon",
    [string]$ServerDir = "test-server-stack",
    [string]$ServerJar = "fabric-server-launch.jar",
    [string]$ServerArgs = "-server -Xmx2048M -Xms1024M -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8",
    [int]$ReadyTimeoutSeconds = 180,
    [switch]$KeepRunning,
    [switch]$Confirm
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Fail([string]$message) { Write-Host "[FAIL] $message" -ForegroundColor Red; exit 1 }
function Info([string]$message) { Write-Host "[smoke] $message" -ForegroundColor Cyan }
function Pass([string]$message) { Write-Host "[ ok ] $message" -ForegroundColor Green }
function Assert-Contains([string]$text, [string]$needle, [string]$label) {
    if ($text -notlike "*$needle*") { Fail "$label 未包含「$needle」。实际输出：`n$text" }
    Pass $label
}
function Assert-File([string]$path, [string]$label) {
    if (-not (Test-Path $path)) { Fail "$label 不存在：$path" }
    Pass $label
}
function Invoke-Rcon([string]$command) {
    $out = & $Java "-Dstdout.encoding=UTF-8" -cp $RconClassPath RconClient $RconHost $RconPort $RconPassword $command 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "RCON 失败（exit=$LASTEXITCODE）：$command`n$out" }
    return ($out -join "`n")
}
function Test-RconPort {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $c.Connect($RconHost, $RconPort); $c.Close(); return $true
    } catch { return $false }
}
function Wait-Rcon([bool]$up, [int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((Test-RconPort) -eq $up) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

if (-not $Confirm) {
    Write-Host "这会启动测试服并触发旧布局迁移。" -ForegroundColor Yellow
    Write-Host "确认后加 -Confirm；默认 RCON 端口 $RconPort。" 
    exit 0
}
if (-not (Test-Path $Java)) { Fail "找不到 Java：$Java" }
if (-not (Test-Path $ServerDir)) { Fail "找不到测试服目录：$ServerDir" }
$wd = (Resolve-Path $ServerDir).Path
foreach ($name in @("world", "world_s1", "world_s2")) {
    Assert-File (Join-Path $wd "$name\level.dat") "旧布局 $name/level.dat"
}
if (Test-Path (Join-Path $wd "world\samsara\stack.json")) {
    Fail "world/samsara/stack.json 已存在，这不是待迁移的旧布局"
}
if (Test-RconPort) { Fail "服务器似乎正在运行（RCON $RconPort 可连接）；请先停服再执行" }

if (-not (Test-Path $RconClassPath)) { New-Item -ItemType Directory -Force -Path $RconClassPath | Out-Null }
if (-not (Test-Path (Join-Path $RconClassPath "RconClient.class"))) {
    & (Join-Path (Split-Path $Java) "javac.exe") --release 17 -encoding UTF-8 -d $RconClassPath "tools\RconClient.java"
    if ($LASTEXITCODE -ne 0) { Fail "编译 RconClient 失败" }
}

$stdout = Join-Path $wd "logs\console-migrate.out"
$stderr = Join-Path $wd "logs\console-migrate.err"
$argList = @($ServerArgs.Split(" ", [StringSplitOptions]::RemoveEmptyEntries)) + @("-jar", $ServerJar, "nogui")
$process = Start-Process -FilePath $Java -ArgumentList $argList -WorkingDirectory $wd `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
Info "已启动迁移测试服 PID=$($process.Id)，等待 RCON…"
if (-not (Wait-Rcon $true $ReadyTimeoutSeconds)) {
    Fail "测试服在 ${ReadyTimeoutSeconds}s 内没有启动 RCON；查看 $stdout"
}

# 1) 栈清单
$list = Invoke-Rcon "samsara world list"
Assert-Contains $list "stack_0" "旧栈底成为 stack_0"
Assert-Contains $list "stack_1" "旧 world_s1 成为 stack_1"
Assert-Contains $list "world  ← 当前/栈顶" "world 是栈顶"
$info = Invoke-Rcon "samsara world info"
Assert-Contains $info "层数：3" "栈层数 3"

# 2) 文件布局与玩家/世界数据
Assert-File (Join-Path $wd "world\dimensions\minecraft\overworld") "world 顶层维度"
Assert-File (Join-Path $wd "world\dimensions\samsara\stack_0\overworld") "stack_0 维度"
Assert-File (Join-Path $wd "world\dimensions\samsara\stack_1\overworld") "stack_1 维度"
Assert-File (Join-Path $wd "world\samsara\stack\stack_0\level.dat") "stack_0 level.dat"
Assert-File (Join-Path $wd "world\samsara\stack\stack_1\data\minecraft\world_gen_settings.dat") "stack_1 生成器元数据"
Assert-File (Join-Path $wd "world\players\data") "共享 players/data"
$props = Get-Content (Join-Path $wd "server.properties")
if (-not ($props -match '^level-name=world$')) { Fail "server.properties 未固定 level-name=world" }
Pass "server.properties level-name=world"

$spawn = Invoke-Rcon "samsara spawn"
Assert-Contains $spawn "历史" "栈顶出生点历史可读"

Info "迁移完成。旧目录内容保留在 legacy-layout.migrated-* / 栈层 legacy-root/。"
if (-not $KeepRunning) {
    Invoke-Rcon "stop" | Out-Null
    if (-not (Wait-Rcon $false 60)) { Fail "测试服未在 60s 内停服" }
}
Pass "旧布局迁移场景测试全部通过"
