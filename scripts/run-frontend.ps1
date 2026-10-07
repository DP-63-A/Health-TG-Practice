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

Push-Location (Join-Path $repoRoot "frontend")
try {
    & npm.cmd run dev -- --host localhost --port 5173 --strictPort
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $exitCode
