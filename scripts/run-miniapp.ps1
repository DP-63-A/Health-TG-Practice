param([switch]$Restart)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-miniapp-common.ps1')
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFile = Join-Path $repoRoot '.env'
$frontendEnv = Join-Path $repoRoot 'frontend\.env.local'
$runtimeDir = Join-Path $repoRoot '.local-miniapp'
$null = New-Item -ItemType Directory -Path $runtimeDir -Force
$settings = Read-LocalSettings $envFile
if (-not $settings.TELEGRAM_BOT_TOKEN -or -not $settings.TELEGRAM_ALLOWED_USER_IDS) {
    throw 'Configure the bot token and allowlist in .env first.'
}
$apiPort = if ($settings.SERVER_PORT) { [int]$settings.SERVER_PORT } else { 8080 }
$apiOrigin = "http://127.0.0.1:$apiPort"
$cloudflared = (Get-Command cloudflared -ErrorAction Stop).Source
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem (Join-Path $env:ProgramFiles 'Eclipse Adoptium') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) { throw 'Set JAVA_HOME to a Java 21 JDK.' }

function Test-Ready([string]$Url) {
    try {
        $response = Invoke-RestMethod -Uri "$($Url.TrimEnd('/'))/api/v1/healthz" -TimeoutSec 4
        return $response.status -eq 'ok' -and $response.checks.mongo -eq 'ok'
    } catch { return $false }
}
function Wait-Ready([string]$Url, [int]$Seconds = 90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        if (Test-Ready $Url) { return }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "API readiness failed for $Url. Inspect local logs without sharing secrets."
}
function Start-LocalScript([string]$Name, [string]$LogName) {
    $script = Join-Path $PSScriptRoot $Name
    $logPrefix = Join-Path $runtimeDir ("$LogName-" + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
    $process = Start-Process powershell.exe -WindowStyle Hidden -PassThru -WorkingDirectory $repoRoot `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $script + '"')) `
        -RedirectStandardOutput "$logPrefix.out.log" -RedirectStandardError "$logPrefix.err.log"
    $process | Add-Member -NotePropertyName OutputLog -NotePropertyValue "$logPrefix.out.log"
    Write-Host "$LogName launcher PID $($process.Id); logs: $logPrefix.*.log"
    return $process
}
function Stop-ProjectRole([string]$Role) {
    foreach ($process in Get-CimInstance Win32_Process) {
        if (Test-ProjectApplication $process $repoRoot $Role) {
            # Re-read immediately: never act on a stale PID alone.
            $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.ProcessId)"
            if ($current -and (Test-ProjectApplication $current $repoRoot $Role)) {
                Write-Host "Restarting project $Role PID $($current.ProcessId)"
                Stop-Process -Id $current.ProcessId -ErrorAction Stop
            }
        }
    }
}
Push-Location $repoRoot
try {
    # Start only MongoDB. Never recreate the database or remove its volume.
    & docker compose -f compose.yaml -f compose.dev.yaml --env-file .env up -d mongo
    if ($LASTEXITCODE -ne 0) { throw 'MongoDB Compose startup failed.' }

    $stateFile = Join-Path $runtimeDir 'state.json'
    $state = if (Test-Path $stateFile) { Get-Content $stateFile -Raw | ConvertFrom-Json } else { $null }
    $url = $null
    if ($state -and $state.Url) {
        $tunnel = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$state.TunnelPid)"
        if ($tunnel -and $tunnel.Name -eq 'cloudflared.exe' -and
            $tunnel.CommandLine -like '*--url http://127.0.0.1:5173*' -and (Test-Ready $state.Url)) {
            $url = (Get-MiniAppUri $state.Url).AbsoluteUri
        }
    }
    if (-not $url) {
        if ($state -and $state.TunnelPid) {
            $oldTunnel = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$state.TunnelPid)"
            if ($oldTunnel -and $oldTunnel.Name -eq 'cloudflared.exe' -and
                $oldTunnel.ExecutablePath -eq $cloudflared -and
                $oldTunnel.CommandLine -like '*--url http://127.0.0.1:5173*') {
                Stop-Process -Id $oldTunnel.ProcessId
            }
        }
        # New log names keep an old URL from being mistaken for a new tunnel.
        $prefix = Join-Path $runtimeDir ('tunnel-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
        $tunnel = Start-Process $cloudflared -WindowStyle Hidden -PassThru -WorkingDirectory $repoRoot `
            -ArgumentList @('tunnel', '--url', 'http://127.0.0.1:5173', '--no-autoupdate') `
            -RedirectStandardOutput "$prefix.out.log" -RedirectStandardError "$prefix.err.log"
        @{ Url = ''; TunnelPid = $tunnel.Id; ApiOrigin = $apiOrigin } |
            ConvertTo-Json | Set-Content $stateFile -Encoding UTF8
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        do {
            $tunnel.Refresh()
            if ($tunnel.HasExited) { throw 'Cloudflare tunnel exited. Inspect its local log.' }
            $log = Get-Content -LiteralPath "$prefix.err.log" -Raw -ErrorAction SilentlyContinue
            if (-not $log) { $log = '' }
            $match = [regex]::Match($log, 'https://[a-z0-9-]+\.trycloudflare\.com')
            if ($match.Success) { $url = (Get-MiniAppUri $match.Value).AbsoluteUri; break }
            Start-Sleep -Seconds 1
        } while ([DateTime]::UtcNow -lt $deadline)
        if (-not $url) { throw 'Cloudflare did not return a new URL.' }
        @{ Url = $url; TunnelPid = $tunnel.Id; ApiOrigin = $apiOrigin } |
            ConvertTo-Json | Set-Content $stateFile -Encoding UTF8
    }
    $changed = $settings.MINI_APP_URL -ne $url
    $origins = @($settings.CORS_ALLOWED_ORIGINS -split ',' | ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -notmatch '^https://[a-z0-9-]+\.trycloudflare\.com/?$' })
    $origins += @($url.TrimEnd('/'), 'http://127.0.0.1:5173', 'http://localhost:5173')
    $cors = ($origins | Sort-Object -Unique) -join ','
    $changed = $changed -or $settings.CORS_ALLOWED_ORIGINS -ne $cors
    $frontendSettings = if (Test-Path $frontendEnv) { Read-LocalSettings $frontendEnv } else { @{} }
    $changed = $changed -or $frontendSettings.VITE_API_MODE -ne 'live' -or $frontendSettings.VITE_API_BASE_URL -ne '/api/v1'
    if ($changed) {
        $backup = Join-Path $runtimeDir ('config-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
        Copy-Item -LiteralPath $envFile -Destination "$backup.env"
        if (Test-Path $frontendEnv) { Copy-Item -LiteralPath $frontendEnv -Destination "$backup.frontend.env" }
        Set-LocalSettings $envFile @{ MINI_APP_URL = $url; CORS_ALLOWED_ORIGINS = $cors }
        Set-LocalSettings $frontendEnv @{ VITE_API_MODE = 'live'; VITE_API_BASE_URL = '/api/v1' }
    }
    if ($Restart -or $changed) {
        Stop-ProjectRole 'bot'
        Stop-ProjectRole 'frontend'
        Stop-ProjectRole 'api'
        Start-Sleep -Seconds 2
    }
    # Refuse to replace unrelated servers listening on the project's ports.
    foreach ($port in @($apiPort, 5173)) {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
        foreach ($listener in $listeners) {
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
            $role = if ($port -eq 5173) { 'frontend' } else { 'api' }
            if (-not $process -or -not (Test-ProjectApplication $process $repoRoot $role)) {
                throw "Port $port belongs to another process; it was not stopped."
            }
        }
    }
    if (-not (Test-Ready $apiOrigin)) { $null = Start-LocalScript 'run-backend.ps1' 'api' }
    Wait-Ready $apiOrigin
    if (-not (Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue)) {
        $null = Start-LocalScript 'run-frontend.ps1' 'frontend'
    }
    Wait-Ready 'http://127.0.0.1:5173'
    Wait-Ready $url
    # Update both the default and per-chat overrides; changing BotFather alone is insufficient.
    & (Join-Path $PSScriptRoot 'sync-miniapp-menu.ps1') -EnvFile $envFile
    $bots = @(Get-CimInstance Win32_Process | Where-Object { Test-ProjectApplication $_ $repoRoot 'bot' })
    if ($bots.Count -gt 1) { throw 'More than one project bot is running; resolve duplicate polling first.' }
    if ($bots.Count -eq 0) {
        $botLauncher = Start-LocalScript 'run-bot.ps1' 'bot'
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        $botReady = $false
        do {
            $botLauncher.Refresh()
            if ($botLauncher.HasExited) { throw 'Bot exited during startup. Inspect its local logs.' }
            $botLog = Get-Content -LiteralPath $botLauncher.OutputLog -Raw -ErrorAction SilentlyContinue
            if ($botLog -match 'Started BotApplication' -and $botLog -match 'long polling') { $botReady = $true; break }
            Start-Sleep -Seconds 1
        } while ([DateTime]::UtcNow -lt $deadline)
        if (-not $botReady) { throw 'Bot did not finish startup. Inspect its local logs.' }
    }
    Write-Host "Mini App: $url"
    Write-Host "API: $($url)api/v1 (live, same HTTPS origin); local backend: $apiOrigin"
    Write-Host 'Close old Telegram Mini App windows, send /start, then open the menu.'
} finally { Pop-Location }
