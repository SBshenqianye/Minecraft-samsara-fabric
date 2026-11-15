param(
  [string]$BaseUrl = 'http://127.0.0.1:8720',
  [string]$Token = $env:SAMSARA_TOKEN,
  [int]$MaxWaitSeconds = 120,
  [switch]$AllowOnline,
  [int]$ForcePid = 0
)

$ErrorActionPreference = 'Stop'
if (-not $Token) { throw '缺少 token：传 -Token 或设置 SAMSARA_TOKEN' }

function Invoke-Console([string]$Command) {
  $body = @{ command = $Command } | ConvertTo-Json -Compress
  Invoke-RestMethod -Uri "$BaseUrl/api/server/console" -Method Post `
    -Headers @{ Authorization = "Bearer $Token"; 'Content-Type' = 'application/json' } `
    -Body $body -TimeoutSec 15
}

$list = Invoke-Console 'list'
$online = 0
if ($list.output -match 'There are (\d+) of') { $online = [int]$Matches[1] }
if ($online -gt 0 -and -not $AllowOnline) {
  Write-Error "服务器仍有 $online 人在线，拒绝停服。确认安全后加 -AllowOnline，并先发倒计时公告。"
  exit 2
}

Write-Host "发送 stop（在线 $online 人）..."
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
  Write-Host '服务器已优雅停止。'
  exit 0
}

Write-Error "等待 ${MaxWaitSeconds}s 后服务器仍未退出。默认不强杀。请检查日志/进程后人工决定。"
if ($ForcePid -gt 0) {
  Write-Warning "按显式 -ForcePid $ForcePid 强制结束。未保存数据可能回档。"
  taskkill /PID $ForcePid /T /F
  exit 0
}
exit 3
