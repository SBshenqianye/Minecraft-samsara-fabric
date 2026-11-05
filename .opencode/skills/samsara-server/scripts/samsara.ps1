# samsara Web API 薄封装（Windows PowerShell）
# 用法：
#   $env:SAMSARA_URL='http://127.0.0.1:8720'
#   $env:SAMSARA_TOKEN='...'
#   .\samsara.ps1 GET /api/status
#   .\samsara.ps1 POST /api/announcements/preview '{"id":"welcome"}'

param(
    [Parameter(Mandatory = $true)][string]$Method,
    [Parameter(Mandatory = $true)][string]$Path,
    [string]$Body = ''
)

$base = if ($env:SAMSARA_URL) { $env:SAMSARA_URL.TrimEnd('/') } else { 'http://127.0.0.1:8720' }
if (-not $env:SAMSARA_TOKEN) {
    throw 'SAMSARA_TOKEN 未设置'
}

$headers = @{ Authorization = "Bearer $env:SAMSARA_TOKEN" }
$args = @{
    Uri         = "$base$Path"
    Method      = $Method.ToUpperInvariant()
    Headers     = $headers
    TimeoutSec  = 30
    ErrorAction = 'Stop'
}

if ($Body) {
    $args['ContentType'] = 'application/json'
    $args['Body'] = $Body
}

try {
    $response = Invoke-RestMethod @args
    $response | ConvertTo-Json -Depth 20
} catch {
    $detail = $_.ErrorDetails.Message
    if ($detail) { Write-Output $detail } else { throw }
    exit 1
}
