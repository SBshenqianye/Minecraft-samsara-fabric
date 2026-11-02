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
$e2eFixtures = @()

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
        & $Java "-Dstdout.encoding=UTF-8" -cp $classes RconClient 127.0.0.1 $RconPort $RconPassword "stop" 2>$null | Out-Null
        for ($i = 0; $i -lt 90; $i++) {
            if (-not (Get-NetTCPConnection -LocalPort $RconPort -State Listen -ErrorAction SilentlyContinue)) { break }
            Start-Sleep -Seconds 2
        }
    } catch {
        Write-Warning "停止测试服失败：$($_.Exception.Message)"
    }
}

function New-E2ETimelineFixtures {
    param([string]$ServerDir)
    $created = @()
    $stackJson = Join-Path $ServerDir "world\samsara\stack.json"
    if (-not (Test-Path $stackJson)) { return $created }
    $backupRoot = Join-Path $ServerDir "samsara\world-stack-backup"
    $legacy = Join-Path $backupRoot "import-e2e-legacy"
    New-Item -ItemType Directory -Force $legacy | Out-Null
    [IO.File]::WriteAllText((Join-Path $legacy "marker.txt"), "legacy",
        (New-Object Text.UTF8Encoding($false)))
    $created += $legacy

    $fixture = Join-Path $backupRoot "import-e2e-noop"
    New-Item -ItemType Directory -Force $fixture | Out-Null
    $manifest = Get-Content $stackJson -Raw | ConvertFrom-Json
    $operation = [ordered]@{
        format = 1
        id = "ws-e2e-noop"
        operation = "import"
        jobId = "e2e-noop"
        createdAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        beforeManifest = $manifest
        afterManifest = $manifest
        affectedIds = @()
        restorable = $true
        backupDir = $fixture
        notes = @("Web E2E 临时夹具；预览后不执行撤销")
        failReason = ""
        restoredAt = 0
        restoreJobId = ""
        sourcePath = ""
        archivePath = ""
        addedPlayers = @()
        createdLayerIds = @()
        removedLayerIds = @()
    }
    $json = $operation | ConvertTo-Json -Depth 40
    [IO.File]::WriteAllText((Join-Path $fixture "operation.json"), $json,
        (New-Object Text.UTF8Encoding($false)))
    $created += $fixture
    return $created
}

try {
    if (-not (Test-WebUp)) {
        if ($NoServer) { throw "Web 未启动，且指定了 -NoServer" }
        if (-not $ServerDir) { throw "Web 未启动；请用 -ServerDir 指定测试服目录，或加 -NoServer" }
        if (-not (Test-Path $ServerDir)) { throw "测试服目录不存在：$ServerDir" }
        if (-not (Test-Path $Java)) { throw "Java 不存在：$Java" }
        $e2eServerDir = "$ServerDir"

        # 在服务器启动前写临时时间线夹具，避免 Web 已起但 stack.json 尚未稳定时读到空时间线。
        $e2eStackManifestPath = Join-Path $e2eServerDir "world\samsara\stack.json"
        if (Test-Path $e2eStackManifestPath) {
            $e2eBackupRoot = Join-Path $e2eServerDir "samsara\world-stack-backup"
            $e2eLegacyDir = Join-Path $e2eBackupRoot "import-e2e-legacy"
            New-Item -ItemType Directory -Force $e2eLegacyDir | Out-Null
            [IO.File]::WriteAllText((Join-Path $e2eLegacyDir "marker.txt"), "legacy",
                (New-Object Text.UTF8Encoding($false)))
            $e2eFixtures += $e2eLegacyDir

            $e2eNoopDir = Join-Path $e2eBackupRoot "import-e2e-noop"
            New-Item -ItemType Directory -Force $e2eNoopDir | Out-Null
            $e2eManifest = Get-Content $e2eStackManifestPath -Raw | ConvertFrom-Json
            $e2eOperation = [ordered]@{
                format = 1
                id = "ws-e2e-noop"
                operation = "import"
                jobId = "e2e-noop"
                createdAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                beforeManifest = $e2eManifest
                afterManifest = $e2eManifest
                affectedIds = @()
                restorable = $true
                backupDir = $e2eNoopDir
                notes = @("Web E2E 临时夹具；预览后不执行撤销")
                failReason = ""
                restoredAt = 0
                restoreJobId = ""
                sourcePath = ""
                archivePath = ""
                addedPlayers = @()
                createdLayerIds = @()
                removedLayerIds = @()
            }
            $e2eJson = $e2eOperation | ConvertTo-Json -Depth 40
            [IO.File]::WriteAllText((Join-Path $e2eNoopDir "operation.json"), $e2eJson,
                (New-Object Text.UTF8Encoding($false)))
            $e2eFixtures += $e2eNoopDir
        }

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
        if ($ServerDir) {
            $e2eFixtures = @(New-E2ETimelineFixtures -ServerDir $ServerDir)
        }
    }

    & node (Join-Path $root "tools\web-e2e.mjs") --base $Base --token $Token
    $code = $LASTEXITCODE
    exit $code
} finally {
    Stop-TestServer
    if (-not $env:SAMSARA_E2E_KEEP) {
        foreach ($fixture in $e2eFixtures) {
            try { Remove-Item -Recurse -Force $fixture -ErrorAction SilentlyContinue } catch { }
        }
    }
}
