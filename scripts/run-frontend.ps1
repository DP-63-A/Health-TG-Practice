param(
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\.env")
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$urlLine = @(Get-Content -LiteralPath $EnvFile | Where-Object { $_ -match '^MINI_APP_URL=' })
if ($urlLine.Count -ne 1) { throw "Expected one MINI_APP_URL in local configuration." }
$miniAppUrl = [Uri](($urlLine[0] -split '=', 2)[1].Trim().Trim('"', "'"))
if ($miniAppUrl.Scheme -ne 'https') { throw "MINI_APP_URL must use HTTPS." }
# Allow only the configured tunnel host. Never disable Vite's host validation.
$env:__VITE_ADDITIONAL_SERVER_ALLOWED_HOSTS = $miniAppUrl.DnsSafeHost
$portLine = @(Get-Content -LiteralPath $EnvFile | Where-Object { $_ -match '^SERVER_PORT=' })
$apiPort = if ($portLine.Count -eq 1) { [int](($portLine[0] -split '=', 2)[1].Trim().Trim('"', "'")) } else { 8080 }
$env:LOCAL_API_ORIGIN = "http://127.0.0.1:$apiPort"
# The phone calls its own HTTPS origin; Vite forwards API requests on the host.
$env:VITE_API_MODE = 'live'
$env:VITE_API_BASE_URL = '/api/v1'

Push-Location (Join-Path $repoRoot "frontend")
try {
    & npm.cmd run dev -- --host 127.0.0.1 --port 5173 --strictPort
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $exitCode
