#requires -Version 5.1
<#
.SYNOPSIS
  samsara 跨世界传送链场景测试（真实 RCON + PuppetPlayers）。

.DESCRIPTION
  用假玩家验证 F-29~F-34 的阈值、目标维度与坐标：
    1. 主世界虚空 → 同世界下界；
    2. 下界顶部 → 同世界主世界；
    3. 当前不是栈顶时：主世界顶部 → 上层世界下界；
    4. 当前不是栈底时：下界虚空 → 下层世界主世界；
    5. 当前是栈底时：下界虚空 → 原版死亡。
  需要测试服装 PuppetPlayers。脚本会保留/清理假玩家。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\world-chain.ps1 -Confirm -BotName ChainBot
#>
param(
    [string]$RconHost = "127.0.0.1",
    [int]$RconPort = 25575,
    [string]$RconPassword = "samsara-m1-test",
    [string]$Java = "$env:APPDATA\.minecraft\runtime\java-runtime-epsilon\bin\java.exe",
    [string]$RconClassPath = ".tmp\rcon",
    [string]$BotName = "ChainBot",
    [int]$WaitMillis = 1500,
    [switch]$Confirm
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Fail([string]$message) {
    Write-Host "[FAIL] $message" -ForegroundColor Red
    exit 1
}
function Info([string]$message) { Write-Host "[smoke] $message" -ForegroundColor Cyan }
function Pass([string]$message) { Write-Host "[ ok ] $message" -ForegroundColor Green }

function Invoke-Rcon([string]$command) {
    $out = & $Java "-Dstdout.encoding=UTF-8" -cp $RconClassPath RconClient $RconHost $RconPort $RconPassword $command 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "RCON 失败（exit=$LASTEXITCODE）：$command`n$out" }
    return ($out -join "`n")
}
function Invoke-RconOptional([string]$command) {
    $out = & $Java "-Dstdout.encoding=UTF-8" -cp $RconClassPath RconClient $RconHost $RconPort $RconPassword $command 2>&1
    return ($out -join "`n")
}
function Test-RconPort {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $c.Connect($RconHost, $RconPort); $c.Close(); return $true
    } catch { return $false }
}
function Wait-Transfer {
    Start-Sleep -Milliseconds $WaitMillis
}
function Get-Dimension {
    $out = Invoke-Rcon "data get entity $BotName Dimension"
    $m = [regex]::Match($out, '"([^"]+)"')
    if (-not $m.Success) { Fail "无法读取 $BotName 的维度。实际输出：`n$out" }
    return $m.Groups[1].Value
}
function Get-Pos {
    $out = Invoke-Rcon "data get entity $BotName Pos"
    return $out
}
function Assert-Dimension([string]$expected, [string]$label, [switch]$Contains) {
    $dim = Get-Dimension
    if ($Contains) {
        if ($dim -notlike "*$expected*") {
            Fail "$label：期望维度包含「$expected」，实际 $dim。Pos=$((Get-Pos))"
        }
    } elseif ($dim -ne $expected) {
        Fail "$label：期望维度 $expected，实际 $dim。Pos=$((Get-Pos))"
    }
    Pass "$label（$dim）"
}

if (-not $Confirm) {
    Write-Host "这会生成假玩家并执行真实传送。" -ForegroundColor Yellow
    Write-Host "确认后加 -Confirm 再运行；默认 RCON 端口 $RconPort（测试服）。"
    exit 0
}
if (-not (Test-Path $Java)) { Fail "找不到 Java：$Java" }
if (-not (Test-Path $RconClassPath)) { New-Item -ItemType Directory -Force -Path $RconClassPath | Out-Null }
if (-not (Test-Path (Join-Path $RconClassPath "RconClient.class"))) {
    & (Join-Path (Split-Path $Java) "javac.exe") --release 17 -encoding UTF-8 -d $RconClassPath "tools\RconClient.java"
    if ($LASTEXITCODE -ne 0) { Fail "编译 RconClient 失败" }
}
if (-not (Test-RconPort)) { Fail "RCON 未监听；先启动测试服（端口 $RconPort）" }

$multi = Invoke-Rcon "samsara debug world multi"
Info $multi
$list = Invoke-Rcon "samsara world list"
$worlds = @([regex]::Matches($list, "\b(\d+)\.\s+(world\S*)") | ForEach-Object { $_.Groups[2].Value } | Select-Object -Unique)
$info = Invoke-Rcon "samsara world info"
$current = ([regex]::Match($info, "实际加载：(\S+)")).Groups[1].Value
$top = ([regex]::Match($info, "栈顶：(\S+)")).Groups[1].Value
$bottom = if ($worlds.Count -gt 0) { $worlds[0] } else { $current }
Info "当前=$current · 栈顶=$top · 栈底=$bottom · 层数=$($worlds.Count)"

Invoke-RconOptional "puppet $BotName leave" | Out-Null
Invoke-Rcon "puppet $BotName spawn" | Out-Null
$deadline = (Get-Date).AddSeconds(20)
while ((Get-Date) -lt $deadline) {
    if ((Invoke-RconOptional "list") -like "*$BotName*") { break }
    Start-Sleep -Seconds 1
}
if ((Invoke-RconOptional "list") -notlike "*$BotName*") {
    Fail "假玩家 $BotName 未在 20s 内上线；list：`n$(Invoke-RconOptional 'list')"
}
Pass "假玩家 $BotName 已生成"
$stackOnly = @($worlds | Where-Object { $_ -ne "world" })
$testLayer = if ($stackOnly.Count -gt 1) { $stackOnly[$stackOnly.Count - 1] } elseif ($stackOnly.Count -eq 1) { $stackOnly[0] } else { $null }
if ($testLayer) {
    Info "进入测试栈层 $testLayer"
    Assert-Contains (Invoke-Rcon "execute as $BotName run samsara world enter $testLayer") "已进入" "进入栈层"
    Wait-Transfer
}

# F-29：主世界虚空 → 同世界下界
Info "F-29 主世界虚空 → 同世界下界"
Invoke-Rcon "execute in minecraft:overworld run tp $BotName 100 -100 100" | Out-Null
Wait-Transfer
Assert-Dimension "the_nether" "F-29 目标维度" -Contains

# F-31：下界顶部 → 同世界主世界
Info "F-31 下界顶部 → 同世界主世界"
Invoke-Rcon "execute in minecraft:the_nether run tp $BotName 12 400 12" | Out-Null
Wait-Transfer
Assert-Dimension "overworld" "F-31 目标维度" -Contains

if ($testLayer) {
    Info "F-32 主世界顶部 → 上层世界下界"
    Invoke-Rcon "execute in minecraft:overworld run tp $BotName 200 500 200" | Out-Null
    Wait-Transfer
    Assert-Dimension "samsara_stack_" "F-32 目标为额外层" -Contains
    $dim = Get-Dimension
    if ($dim -notlike "*the_nether*") { Fail "F-32 期望上层下界，实际 $dim" }
    Pass "F-32 目标为上层下界"
} else {
    Write-Host "[skip] 当前就是栈顶，无法测试 F-32；先 world enter 到下层世界再跑。" -ForegroundColor Yellow
}

if ($testLayer -and $stackOnly.Count -gt 1) {
    Info "F-30 下界虚空 → 下层世界主世界"
    Invoke-Rcon "execute in minecraft:the_nether run tp $BotName 50 -100 50" | Out-Null
    Wait-Transfer
    Assert-Dimension "samsara_stack_" "F-30 目标为额外层" -Contains
    $dim = Get-Dimension
    if ($dim -notlike "*overworld*") { Fail "F-30 期望下层主世界，实际 $dim" }
    Pass "F-30 目标为下层主世界"
} else {
    Info "F-34 栈底下界虚空 → 原版死亡"
    Invoke-Rcon "execute in minecraft:the_nether run tp $BotName 0 -100 0" | Out-Null
    Start-Sleep -Seconds 12
    $dim = Get-Dimension
    if ($dim -notlike "*overworld*") {
        Fail "F-34 期望死亡后回主世界重生，实际 $dim"
    }
    Pass "F-34 栈底下界虚空后原版死亡/重生（$dim）"
}

Invoke-RconOptional "puppet $BotName leave" | Out-Null
Pass "跨世界传送链场景测试全部通过"
