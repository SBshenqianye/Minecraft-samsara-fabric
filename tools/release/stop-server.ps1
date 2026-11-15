param(
  [string]$BaseUrl = 'http://127.0.0.1:8720',
  [string]$Token = $env:SAMSARA_TOKEN,
  [int]$MaxWaitSeconds = 120,
  [switch]$AllowOnline,
  [int]$ForcePid = 0,
  # 重启前要公示的更新摘要（一句话即可）。有值时会先 say 给全服，再进入倒计时。
  [string]$UpdateNote = '',
  # 有人在线时的倒计时秒数；默认 60。设 0 表示不倒数（仅 -AllowOnline 时生效）。
  [int]$CountdownSeconds = 60
)

$ErrorActionPreference = 'Stop'
if (-not $Token) { throw 'Missing token: pass -Token or set SAMSARA_TOKEN' }

function Invoke-Console([string]$Command, [int]$Retries = 3) {
  $body = @{ command = $Command } | ConvertTo-Json -Compress
  $lastError = $null
  for ($attempt = 0; $attempt -lt $Retries; $attempt++) {
    try {
      return Invoke-RestMethod -Uri "$BaseUrl/api/server/console" -Method Post `
        -Headers @{ Authorization = "Bearer $Token"; 'Content-Type' = 'application/json' } `
        -Body $body -TimeoutSec 15
    } catch {
      # 服务端正在保存（或刚启动）时会短暂拒绝；重试而不是直接失败。
      $lastError = $_
      if ($attempt -lt $Retries - 1) { Start-Sleep -Seconds 3 }
    }
  }
  throw $lastError
}

function Say([string]$Message) {
  Invoke-Console ('say ' + $Message) | Out-Null
}

$list = Invoke-Console 'list'
$online = 0
$listText = ($list.output | Out-String)
if ($listText -match 'There are (\d+) of') { $online = [int]$Matches[1] }
if ($online -gt 0 -and -not $AllowOnline) {
  Write-Error "Refusing to stop: $online player(s) online. Announce a countdown and pass -AllowOnline when safe."
  exit 2
}

# 先公示更新内容：玩家需要在重启前知道“更新了什么”，而不是只看到停服。
if ($UpdateNote) {
  Say "[维护] 稍后更新：$UpdateNote" | Out-Null
  Write-Host "Announced update note: $UpdateNote"
}

# 有人在线时按秒倒计时（每 15 秒 + 最后 10 秒逐秒）。0 人在线不打扰。
if ($online -gt 0 -and $CountdownSeconds -gt 0) {
  for ($remaining = $CountdownSeconds; $remaining -gt 0; $remaining--) {
    if ($remaining -eq $CountdownSeconds -or $remaining -eq 30 -or $remaining -eq 10 -or $remaining -le 5) {
      Say "[维护] $remaining 秒后停服，请回安全区并结束手上的操作。" | Out-Null
    }
    Start-Sleep -Seconds 1
  }
  Say '[维护] 正在保存并停服…' | Out-Null
  Start-Sleep -Seconds 1
}

Write-Host "Sending stop (online: $online)..."
Invoke-Console 'stop' | Out-Null

$deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
$down = $false
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 2
  try {
    Invoke-WebRequest -Uri "$BaseUrl/api/status" -Headers @{ Authorization = "Bearer $Token" } `
      -UseBasicParsing -TimeoutSec 2 | Out-Null
  } catch {
    $down = $true
    break
  }
}

if ($down) {
  Start-Sleep -Seconds 3
  Write-Host 'Server stopped gracefully.'
  exit 0
}

Write-Error "Server still up after ${MaxWaitSeconds}s. Not forcing by default; inspect logs/PIDs first."
if ($ForcePid -gt 0) {
  Write-Warning "Explicit -ForcePid $ForcePid requested. Unsaved data may roll back."
  taskkill /PID $ForcePid /T /F
  exit 0
}
exit 3
