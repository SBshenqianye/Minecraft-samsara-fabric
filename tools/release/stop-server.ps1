param(
  [string]$BaseUrl = 'http://127.0.0.1:8720',
  [string]$Token = $env:SAMSARA_TOKEN,
  [int]$MaxWaitSeconds = 120,
  [switch]$AllowOnline,
  [int]$ForcePid = 0
)

$ErrorActionPreference = 'Stop'
if (-not $Token) { throw 'Missing token: pass -Token or set SAMSARA_TOKEN' }

function Invoke-Console([string]$Command) {
  $body = @{ command = $Command } | ConvertTo-Json -Compress
  Invoke-RestMethod -Uri "$BaseUrl/api/server/console" -Method Post `
    -Headers @{ Authorization = "Bearer $Token"; 'Content-Type' = 'application/json' } `
    -Body $body -TimeoutSec 15
}

$list = Invoke-Console 'list'
$online = 0
$listText = ($list.output | Out-String)
if ($listText -match 'There are (\d+) of') { $online = [int]$Matches[1] }
if ($online -gt 0 -and -not $AllowOnline) {
  Write-Error "Refusing to stop: $online player(s) online. Announce a countdown and pass -AllowOnline when safe."
  exit 2
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
