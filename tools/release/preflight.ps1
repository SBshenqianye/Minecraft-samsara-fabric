param(
  [string]$BaseUrl = 'http://127.0.0.1:8722',
  [string]$Token = $env:SAMSARA_TOKEN,
  [switch]$SkipE2E
)

$ErrorActionPreference = 'Stop'
$repo = Resolve-Path (Join-Path $PSScriptRoot '../..')
$web = Join-Path $repo 'web-src'

function Assert-LastExit([string]$Step) {
  if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit $LASTEXITCODE" }
}

Write-Host '== npm run check =='
Push-Location $web
try {
  & npm.cmd run check
  Assert-LastExit 'npm run check'
} finally {
  Pop-Location
}

if (-not $SkipE2E) {
  if (-not $Token) { throw 'E2E requires -Token or SAMSARA_TOKEN' }
  Push-Location $repo
  try {
    Write-Host '== section E2E =='
    & node tools/e2e/run.mjs --base $BaseUrl --token $Token
    Assert-LastExit 'section E2E'

    Write-Host '== full web E2E =='
    & node tools/web-e2e.mjs --base $BaseUrl --token $Token
    Assert-LastExit 'full web E2E'
  } finally {
    Pop-Location
  }
}

if ($Token) {
  try {
    $body = @{ command = 'list' } | ConvertTo-Json -Compress
    $list = Invoke-RestMethod -Uri "$BaseUrl/api/server/console" -Method Post `
      -Headers @{ Authorization = "Bearer $Token"; 'Content-Type' = 'application/json' } `
      -Body $body -TimeoutSec 15
    $text = ($list.output | Out-String)
    if ($text -match 'There are (\d+) of') {
      $online = [int]$Matches[1]
      if ($online -gt 0) {
        Write-Warning "Preflight passed, but $online player(s) are online. Announce a countdown before deploy."
      } else {
        Write-Host 'Online players: 0'
      }
    }
  } catch {
    Write-Warning "Could not read player list: $($_.Exception.Message)"
  }
}

Write-Host '== jar hashes =='
$paths = @(
  (Join-Path $repo 'mod/build/libs/samsara-0.41.2.jar'),
  (Join-Path $repo 'test-server/mods/samsara-0.41.2.jar'),
  'C:\Games\0-minecraft\server\servers\Vanilia 26.3\mods\samsara-0.41.2.jar'
)
foreach ($path in $paths) {
  if (Test-Path $path) {
    $hash = (Get-FileHash $path -Algorithm SHA256).Hash
    Write-Host "$hash  $path"
  } else {
    Write-Host "missing      $path"
  }
}

Write-Host 'Preflight OK.'
exit 0
