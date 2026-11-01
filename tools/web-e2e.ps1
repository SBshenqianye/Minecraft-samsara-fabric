param(
    [string]$ServerDir = "",
    [string]$Base = "http://127.0.0.1:8730",
    [string]$Token = "testtoken",
    [string]$Java = "$env:APPDATA\.minecraft\runtime\java-runtime-epsilon\bin\java.exe",
    [int]$RconPort = 25742,
    [string]$RconPassword = "samsara-m1-test",
    [switch]$NoServer
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$started = $false

function Test-WebUp {
    try {
        $response = Invoke-WebRequest -Uri "$Base/" -UseBasicParsing -TimeoutSec 2
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Get-RconClass {
    $classes = Join-Path $root ".tmp\rcon"
    if (-not (Test-Path (Join-Path $classes "RconClient.class"))) {
        New-Item -ItemType Directory -Force $classes | Out-Null
        $javac = Join-Path (Split-Path $Java -Parent) "javac.exe"
        & $javac --release 17 -encoding UTF-8 -d $classes (Join-Path $root "tools\RconClient.java")
        if ($LASTEXITCODE -ne 0) { throw "RconClient 编译失败" }
    }
    return $classes
}

function Stop-TestServer {
    if (-not $started) { return }
    try {
        $classes = Get-RconClass
        & $Java "-Dstdout.encoding=UTF-8" -cp $classes RconClient 127.0.0.1 $RconPort $RconPassword "stop" | Out-Null
        for ($i = 0; $i -lt 90; $i++) {
            if (-not (Get-NetTCPConnection -LocalPort $RconPort -State Listen -ErrorAction SilentlyContinue)) { break }
            Start-Sleep -Seconds 2
        }
    } catch {
        Write-Warning "停止测试服失败：$($_.Exception.Message)"
    }
}

try {
    if (-not (Test-WebUp)) {
        if ($NoServer) { throw "Web 未启动，且指定了 -NoServer" }
        if (-not $ServerDir) { throw "Web 未启动；请用 -ServerDir 指定测试服目录，或加 -NoServer" }
        if (-not (Test-Path $ServerDir)) { throw "测试服目录不存在：$ServerDir" }
        if (-not (Test-Path $Java)) { throw "Java 不存在：$Java" }

        New-Item -ItemType Directory -Force (Join-Path $ServerDir "logs") | Out-Null
        Start-Process -FilePath $Java -ArgumentList @(
            "-server", "-Xmx2048M", "-Xms1024M", "-XX:+UseG1GC",
            "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            "-jar", "fabric-server-launch.jar", "nogui"
        ) -WorkingDirectory $ServerDir `
          -RedirectStandardOutput (Join-Path $ServerDir "logs\e2e-console.out") `
          -RedirectStandardError (Join-Path $ServerDir "logs\e2e-console.err") | Out-Null
        $started = $true

        for ($i = 0; $i -lt 90; $i++) {
            if (Test-WebUp) { break }
            Start-Sleep -Seconds 2
        }
        if (-not (Test-WebUp)) { throw "测试服 Web 在 180 秒内没有启动" }
    } else {
        Write-Host "Web 已在运行：$Base（复用）"
    }

    & node (Join-Path $root "tools\web-e2e.mjs") --base $Base --token $Token
    $code = $LASTEXITCODE
    exit $code
} finally {
    Stop-TestServer
}
