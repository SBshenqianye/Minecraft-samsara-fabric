param(
  [Parameter(Mandatory = $true)][string]$Jar,
  [Parameter(Mandatory = $true)][string]$ServerDir,
  [string]$BaseUrl = 'http://127.0.0.1:8720',
  [string]$Token = $env:SAMSARA_TOKEN,
  [string]$UpdateNote = '',
  [int]$CountdownSeconds = 60,
  [switch]$AllowOnline,
  [switch]$NoStart,
  [switch]$NoMaintenanceFlag,
  [int]$StartTimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$repo = Resolve-Path (Join-Path $PSScriptRoot '../..')
$ServerDir = (Resolve-Path $ServerDir).Path
$Jar = (Resolve-Path $Jar).Path
$flag = Join-Path $ServerDir 'maintenance.flag'
$bat = Join-Path $ServerDir 'Launch Server.bat'
$mods = Join-Path $ServerDir 'mods'
$jarName = Split-Path $Jar -Leaf
$target = Join-Path $mods $jarName
$backupDir = Join-Path $ServerDir 'backups/samsara/mod-jars'
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'

function Assert-LastExit([string]$Step) {
  if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit $LASTEXITCODE" }
}

Write-Host "== deploy $(Split-Path $Jar -Leaf) -> $ServerDir =="

# 1) 维护标记：让 Launch Server.bat 在 java 退出后不再自动重启，
#    避免 5 秒循环把旧 jar 拉起来（2026-11-15 两次部署踩过）。
if (-not $NoMaintenanceFlag) {
  Set-Content -LiteralPath $flag -Value "deploy $stamp" -Encoding ASCII
  Write-Host "maintenance.flag created: $flag"
}

# 2) 公示更新摘要 + 倒计时 + 优雅停服（脚本内部幂等，可重复调用）。
$stopArgs = @{
  BaseUrl = $BaseUrl
  Token = $Token
  MaxWaitSeconds = 180
  CountdownSeconds = $CountdownSeconds
}
if ($UpdateNote) { $stopArgs.UpdateNote = $UpdateNote }
if ($AllowOnline) { $stopArgs.AllowOnline = $true }
& (Join-Path $PSScriptRoot 'stop-server.ps1') @stopArgs
if ($LASTEXITCODE -ne 0) {
  # 停服没成功：撤掉维护标记，避免后续自然停服时服务器起不来。
  if (Test-Path $flag) { Remove-Item -LiteralPath $flag -Force }
  throw "stop-server.ps1 failed with exit $LASTEXITCODE"
}

# 3) 等 java 真正退出。循环 cmd 若仍在（老版本 bat 不认 maintenance.flag），结束它。
$loop = Get-CimInstance Win32_Process |
  Where-Object { $_.Name -eq 'cmd.exe' -and $_.CommandLine -and $_.CommandLine.Contains($bat) }
if ($loop) {
  $deadline = (Get-Date).AddSeconds(180)
  while ((Get-Date) -lt $deadline) {
    $alive = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($loop.ProcessId)" |
      Where-Object { $_.Name -eq 'java.exe' }
    if (-not $alive) { break }
    Start-Sleep -Seconds 2
  }
  Start-Sleep -Seconds 2
  if (Get-Process -Id $loop.ProcessId -ErrorAction SilentlyContinue) {
    Write-Warning "循环启动进程仍在（maintenance.flag 未被识别），结束 pid=$($loop.ProcessId)。"
    Stop-Process -Id $loop.ProcessId -Force
  }
}
Start-Sleep -Seconds 2

# 4) 备份旧 jar，替换并校验哈希。
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
if (Test-Path $target) {
  $oldBackup = Join-Path $backupDir ("$jarName.bak-$stamp")
  Copy-Item -LiteralPath $target -Destination $oldBackup -Force
  Write-Host "旧 jar 已备份: $oldBackup"
}
Copy-Item -LiteralPath $Jar -Destination $target -Force
$hashSrc = (Get-FileHash $Jar -Algorithm SHA256).Hash
$hashDst = (Get-FileHash $target -Algorithm SHA256).Hash
if ($hashSrc -ne $hashDst) { throw "哈希不一致：$hashSrc != $hashDst" }
Write-Host "jar SHA-256: $hashDst"

# 清理其他 samsara 主 jar（保留 .bak-*），避免同目录两个版本同时加载。
Get-ChildItem -LiteralPath $mods -Filter 'samsara-*.jar' |
  Where-Object { $_.Name -ne $jarName } |
  ForEach-Object {
    Write-Host "移除旧版本: $($_.Name)"
    Remove-Item -LiteralPath $_.FullName -Force
  }

if ($NoMaintenanceFlag) { Write-Host 'maintenance.flag 未启用，跳过删除。' }
elseif (Test-Path $flag) { Remove-Item -LiteralPath $flag -Force }

if ($NoStart) {
  Write-Host 'NoStart: 已替换 jar，未启动服务器。'
  exit 0
}

# 5) 通过 WMI 脱离当前 shell 启动 Launch Server.bat。
$cmdLine = 'cmd.exe /c "' + $bat + '"'
$created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
  CommandLine = $cmdLine
  CurrentDirectory = $ServerDir
}
if ($created.ReturnValue -ne 0) { throw "启动失败 rc=$($created.ReturnValue)" }
Write-Host "已启动: pid=$($created.ProcessId)"

# 6) 等 Web 起来并打印版本。
$deadline = (Get-Date).AddSeconds($StartTimeoutSeconds)
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 5
  try {
    $status = Invoke-RestMethod -Uri "$BaseUrl/api/status" `
      -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 4
    Write-Host "启动完成: version=$($status.version) webVersion=$($status.webVersion)"
    exit 0
  } catch {
    # 继续等待
  }
}
Write-Error "服务器 ${StartTimeoutSeconds}s 内未就绪，请查看日志。"
exit 1
