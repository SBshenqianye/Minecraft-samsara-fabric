param(
    [string]$ServerDir = "",
    [string]$Java = "$env:APPDATA\.minecraft\runtime\java-runtime-epsilon\bin\java.exe",
    [string]$Memory = "4096M",
    [int]$Port = 8722
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $ServerDir) { $ServerDir = Join-Path $root "test-server" }
$ServerDir = (Resolve-Path $ServerDir).Path
$launcher = Join-Path $ServerDir "fabric-server-launch.jar"
if (-not (Test-Path $launcher)) { throw "找不到 $launcher" }
if (-not (Test-Path $Java)) { throw "找不到 Java：$Java" }

$version = (& $Java -version 2>&1 | Out-String)
if ($version -notmatch 'version "(?<major>\d+)') {
    throw "无法识别 Java 版本：$version"
}
if ([int]$Matches['major'] -lt 25) {
    throw "MC 26.3 需要 Java 25+，当前：$version"
}

$logs = Join-Path $ServerDir "logs"
New-Item -ItemType Directory -Force $logs | Out-Null
$stdout = Join-Path $logs "headless-console.out"
$stderr = Join-Path $logs "headless-console.err"

$process = Start-Process -FilePath $Java -WorkingDirectory $ServerDir -WindowStyle Hidden `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru `
    -ArgumentList @(
        "-Djava.awt.headless=true",
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Xmx$Memory", "-Xms1024M",
        "-XX:+UseG1GC",
        "-jar", "fabric-server-launch.jar",
        "nogui"
    )

Write-Host "已静默启动 samsara 服务器"
Write-Host "  PID   : $($process.Id)"
Write-Host "  目录  : $ServerDir"
Write-Host "  stdout: $stdout"
Write-Host "  stderr: $stderr"
Write-Host "  Web   : http://127.0.0.1:$Port/"
Write-Host ""
Write-Host "服务器启动后可在 Web『本体 → RCON 控制台』执行 list / save-all / stop 等命令，"
Write-Host "也可以直接执行 stop 关闭服务器，不需要保留控制台窗口。"
