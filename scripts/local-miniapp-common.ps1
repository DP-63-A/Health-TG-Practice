function Read-LocalSettings([string]$Path) {
    $settings = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line.Trim() -eq '' -or $line.Trim().StartsWith('#')) { continue }
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            throw 'Invalid local environment assignment (contents suppressed).'
        }
        $settings[$Matches[1]] = $Matches[2].Trim().Trim('"', "'")
    }
    return $settings
}

function Set-LocalSettings([string]$Path, [hashtable]$Values) {
    # Preserve unrelated settings and comments, including secrets; never print them.
    $lines = [System.Collections.Generic.List[string]]::new()
    $seen = @{}
    if (Test-Path -LiteralPath $Path) {
        foreach ($line in [IO.File]::ReadAllLines($Path)) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=' -and $Values.ContainsKey($Matches[1])) {
                $key = $Matches[1]
                if (-not $seen.ContainsKey($key)) { $lines.Add("$key=$($Values[$key])") }
                $seen[$key] = $true
            } else { $lines.Add($line) }
        }
    }
    foreach ($key in ($Values.Keys | Sort-Object)) {
        if (-not $seen.ContainsKey($key)) { $lines.Add("$key=$($Values[$key])") }
    }
    [IO.File]::WriteAllLines($Path, $lines, [Text.UTF8Encoding]::new($false))
}

function Get-MiniAppUri([string]$Value) {
    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 'https' -or -not $uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        throw 'MINI_APP_URL must be an absolute HTTPS base URL without credentials, query or fragment.'
    }
    return $uri
}

function Invoke-LocalTelegram([string]$Token, [string]$Method, [hashtable]$Body = @{}) {
    try {
        $json = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 8 -Compress))
        $response = Invoke-RestMethod -Method Post -Uri ("https://api.telegram.org/bot$Token/$Method") `
            -ContentType 'application/json; charset=utf-8' -Body $json -TimeoutSec 20
        if (-not $response.ok) { throw 'Telegram rejected request.' }
        return $response.result
    } catch {
        # PowerShell exceptions can contain the request URI and therefore the token.
        throw "Telegram $Method failed; response details suppressed."
    }
}

function Test-ProjectApplication($Process, [string]$RepoRoot, [string]$Role) {
    $command = [string]$Process.CommandLine
    $projectPattern = [regex]::Escape($RepoRoot.TrimEnd('\', '/')) + '(?=[\\/";\s]|$)'
    if ($command -notmatch $projectPattern) { return $false }
    switch ($Role) {
        'api' { return $Process.Name -eq 'java.exe' -and $command -match 'org\.healthtg\.HealthTgApplication(?:\s|$)' }
        'bot' { return $Process.Name -eq 'java.exe' -and $command -match 'org\.healthtg\.bot\.BotApplication(?:\s|$)' }
        'frontend' { return $Process.Name -eq 'node.exe' -and $command -match 'vite[\\/]bin[\\/]vite\.js' }
    }
    return $false
}
