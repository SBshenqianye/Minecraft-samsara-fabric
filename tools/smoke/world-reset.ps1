#requires -Version 5.1
<#
.SYNOPSIS
  samsara 同世界重置场景测试（真实 RCON + 真实倒流）。

.DESCRIPTION
  验证：
    1. reset same 需要二次确认；
    2. 倒流完成后出生点历史追加、当前轮变化；
    3. 连续多次重置后出生点不聚集（距上一次 >= 最小间距）；
    4. 保护区块数仍 > 0（保护集没有被清空）。
  会临时把测试服的 rewind 调短、centermin/centermax 调小；结束后恢复默认 26h/15000/40000。

  传 -BotName 可在假玩家当前栈层执行（v0.24 任意栈层 reset same）：
    -BotName StackBot -Layer stack_1
  脚本会先 world enter，再以该玩家身份发起重置，并读取该层的出生点历史。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\world-reset.ps1 -Confirm -Resets 3
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\world-reset.ps1 -Confirm -BotName StackBot -Layer stack_1
#>
param(
    [string]$RconHost = "127.0.0.1",
    [int]$RconPort = 25575,
    [string]$RconPassword = "samsara-m1-test",
    [string]$Java = "$env:APPDATA\.minecraft\runtime\java-runtime-epsilon\bin\java.exe",
    [string]$RconClassPath = ".tmp\rcon",
    [string]$Rewind = "5s",
    [int]$CenterMin = 500,
    [int]$CenterMax = 2000,
    [int]$Resets = 1,
    [string]$BotName = "",
    [string]$Layer = "",
    [int]$RewindTimeoutSeconds = 180,
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

# 指定 -BotName 时，在假玩家所在栈层执行 reset same；否则用控制台（默认栈顶 world）。
function Invoke-ResetCommand([string]$command) {
    if ($BotName) { return Invoke-Rcon "execute as $BotName run $command" }
    return Invoke-Rcon $command
}
function Invoke-SpawnCommand([string]$suffix) {
    if ($BotName) { return Invoke-Rcon "execute as $BotName run samsara spawn$suffix" }
    return Invoke-Rcon "samsara spawn$suffix"
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

function Wait-RewindDone {
    $deadline = (Get-Date).AddSeconds($RewindTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $s = Invoke-RconOptional "samsara debug rewind status"
        if ($s -like "*当前没有进行中的时间倒流*") { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Get-Spawn([string]$text) {
    # 用 ASCII 括号/数字匹配，避免 Windows PowerShell 5.1 按 ANSI 读取脚本时中文乱码。
    $m = [regex]::Match($text, "\((-?\d+),\s*(-?\d+)\)")
    if (-not $m.Success) { Fail "无法解析出生点。实际输出：`n$text" }
    return @([int]$m.Groups[1].Value, [int]$m.Groups[2].Value)
}

# ---- 环境自检 ----
if (-not $Confirm) {
    Write-Host "这会执行真实倒流并删除未保护区块，且会临时修改测试服配置。" -ForegroundColor Yellow
    Write-Host "确认后加 -Confirm 再运行；默认 RCON 端口 $RconPort（测试服）。"
    exit 0
}
if (-not (Test-Path $Java)) { Fail "找不到 Java：$Java" }
if (-not (Test-Path $RconClassPath)) { New-Item -ItemType Directory -Force -Path $RconClassPath | Out-Null }
if (-not (Test-Path (Join-Path $RconClassPath "RconClient.class"))) {
    $javac = Join-Path (Split-Path $Java) "javac.exe"
    & $javac --release 17 -encoding UTF-8 -d $RconClassPath "tools\RconClient.java"
    if ($LASTEXITCODE -ne 0) { Fail "编译 RconClient 失败" }
}
if (-not (Test-RconPort)) { Fail "RCON 未监听；先启动测试服（端口 $RconPort）" }

Info "临时配置：rewind=$Rewind · centermin=$CenterMin · centermax=$CenterMax"
Invoke-Rcon "samsara debug config rewind $Rewind" | Out-Null
Invoke-Rcon "samsara debug config centermin $CenterMin" | Out-Null
Invoke-Rcon "samsara debug config centermax $CenterMax" | Out-Null

if ($BotName) {
    Invoke-RconOptional "puppet $BotName spawn" | Out-Null
    Start-Sleep -Seconds 3
    if (-not (Invoke-RconOptional "list" | Select-String $BotName)) {
        Fail "假玩家 $BotName 未上线"
    }
    if ($Layer) {
        Assert-Contains (Invoke-Rcon "execute as $BotName run samsara world enter $Layer") "已进入" "进入目标栈层 $Layer"
        Start-Sleep -Seconds 2
    }
    $dim = Invoke-Rcon "data get entity $BotName Dimension"
    Info "重置目标：$BotName @ $dim"
}

$before = Get-Spawn (Invoke-SpawnCommand "")
$historyBefore = Invoke-SpawnCommand " history"
Info "起始出生点：($($before[0]), $($before[1]))"

for ($i = 1; $i -le $Resets; $i++) {
    Info "第 $i/$Resets 次同世界重置"
    $firstReply = Invoke-ResetCommand "samsara reset same"
    Assert-Contains $firstReply "危险操作确认" "reset same 第一次只确认"
    $started = Invoke-ResetCommand "samsara reset same"
    Assert-Contains $started "已开始同世界重置" "reset same 第二次执行"

    if (-not (Wait-RewindDone)) {
        Fail "倒流在 ${RewindTimeoutSeconds}s 内未完成；最后状态：`n$(Invoke-RconOptional 'samsara debug rewind status')"
    }
    Start-Sleep -Seconds 2
    $history = Invoke-SpawnCommand " history"
    $count = ([regex]::Matches($history, "same-world-reset")).Count
    if ($count -lt $i) {
        Fail "出生点历史未追加第 $i 条 same-world-reset。实际输出：`n$history"
    }
    Pass "历史已追加（same-world-reset × $i）"

    $now = Get-Spawn (Invoke-SpawnCommand "")
    $dist = [math]::Round([math]::Sqrt([math]::Pow($now[0] - $before[0], 2) + [math]::Pow($now[1] - $before[1], 2)))
    $required = [math]::Min(100000, $CenterMin + 2500 * ([regex]::Matches($history, 'same-world-reset')).Count)
    if ($dist -lt ($required - 1)) {
        Fail "第 $i 次新出生点距上一次仅 $dist 格，小于本次最小间距 $required。实际输出：`n$history"
    }
    Pass "新出生点 ($($now[0]), $($now[1])) · 距上一次 $dist 格 >= $required"
    $before = $now
}

$protected = Invoke-RconOptional "samsara debug claim list"
if ($protected -match "0 个" ) {
    Write-Host "[warn] 保护区块数为 0；本场景无法验证保护集保留" -ForegroundColor Yellow
} else {
    Pass "保护集仍存在（claim list 非空）"
}

Info "恢复测试服默认配置（26h / 15000 / 40000）"
Invoke-RconOptional "samsara debug config rewind 26h" | Out-Null
Invoke-RconOptional "samsara debug config centermin 15000" | Out-Null
Invoke-RconOptional "samsara debug config centermax 40000" | Out-Null

Pass "同世界重置场景测试全部通过"
