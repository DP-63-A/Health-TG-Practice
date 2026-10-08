$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\local-miniapp-common.ps1')
function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
$tempFile = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($tempFile, "# Keep comment`r`nPRIVATE_SETTING='synthetic-value'`r`nMINI_APP_URL=https://old.example/`r`nMINI_APP_URL=https://duplicate.example/`r`nUNRELATED=keep`r`n")
    Set-LocalSettings $tempFile @{ MINI_APP_URL = 'https://new.example/'; VITE_API_BASE_URL = '/api/v1' }
    $actual = Read-LocalSettings $tempFile
    Assert-True ($actual.PRIVATE_SETTING -eq 'synthetic-value') 'Unrelated private setting changed.'
    Assert-True ($actual.UNRELATED -eq 'keep') 'Unrelated setting changed.'
    Assert-True ($actual.MINI_APP_URL -eq 'https://new.example/') 'URL was not updated.'
    Assert-True ($actual.VITE_API_BASE_URL -eq '/api/v1') 'API prefix lost.'
    $lines = [IO.File]::ReadAllLines($tempFile)
    Assert-True ($lines[0] -eq '# Keep comment') 'Comment changed.'
    Assert-True (@($lines | Where-Object { $_ -match '^MINI_APP_URL=' }).Count -eq 1) 'Duplicate URL survived.'
    foreach ($badUrl in @('http://example.test', 'https://name:password@example.test', 'https://example.test/?key=value', 'https://example.test/#fragment', 'relative')) {
        $rejected = $false
        try { $null = Get-MiniAppUri $badUrl } catch { $rejected = $true }
        Assert-True $rejected 'Unsafe Mini App URL accepted.'
    }
    Assert-True ((Get-MiniAppUri 'https://example.test/').Host -eq 'example.test') 'Valid URL rejected.'
    $project = 'C:\test-project'
    $foreign = @{ Name = 'java.exe'; CommandLine = 'C:\another-project org.healthtg.HealthTgApplication' }
    Assert-True (-not (Test-ProjectApplication $foreign $project 'api')) 'Unrelated process selected.'
    $sibling = @{ Name = 'java.exe'; CommandLine = 'C:\test-project-copy org.healthtg.HealthTgApplication' }
    Assert-True (-not (Test-ProjectApplication $sibling $project 'api')) 'Sibling project process selected.'
    $daemon = @{ Name = 'java.exe'; CommandLine = 'C:\test-project org.gradle.launcher.daemon.bootstrap.GradleDaemon' }
    Assert-True (-not (Test-ProjectApplication $daemon $project 'api')) 'Gradle daemon selected.'
    $api = @{ Name = 'java.exe'; CommandLine = 'C:\test-project org.healthtg.HealthTgApplication' }
    Assert-True (Test-ProjectApplication $api $project 'api') 'Project API not recognized.'
    $bot = @{ Name = 'java.exe'; CommandLine = 'C:\test-project org.healthtg.bot.BotApplication' }
    Assert-True (Test-ProjectApplication $bot $project 'bot') 'Project bot not recognized.'
    Write-Host 'PASS: configuration preservation, URL validation and process ownership.'

    # Regression: changing only the default menu leaves old per-chat overrides alive.
    $global:HealthTgMenuTestState = @{ Menus = @{}; Writes = 0 }
    function Invoke-RestMethod {
        param($Method, $Uri, $ContentType, $Body, $TimeoutSec)
        $payload = [Text.Encoding]::UTF8.GetString($Body) | ConvertFrom-Json
        $scope = if ($payload.PSObject.Properties.Name -contains 'chat_id') { [string]$payload.chat_id } else { 'default' }
        switch (($Uri -split '/')[-1]) {
            'getMe' { return @{ ok = $true; result = @{ username = 'synthetic_test_bot' } } }
            'setChatMenuButton' {
                $global:HealthTgMenuTestState.Menus[$scope] = $payload.menu_button
                $global:HealthTgMenuTestState.Writes++
                return @{ ok = $true; result = $true }
            }
            'getChatMenuButton' { return @{ ok = $true; result = $global:HealthTgMenuTestState.Menus[$scope] } }
            default { throw 'Unexpected Telegram method in test.' }
        }
    }
    [IO.File]::WriteAllText($tempFile, "MINI_APP_URL=https://current.example/`nTELEGRAM_BOT_TOKEN=synthetic-only`nTELEGRAM_ALLOWED_USER_IDS=1001,2002`n")
    & (Join-Path $PSScriptRoot '..\sync-miniapp-menu.ps1') -EnvFile $tempFile
    Assert-True ($global:HealthTgMenuTestState.Writes -eq 3) 'Default and both per-chat menus were not synchronized.'
    foreach ($scope in @('default', '1001', '2002')) {
        Assert-True ($global:HealthTgMenuTestState.Menus[$scope].web_app.url -eq 'https://current.example/') 'Menu URL mismatch.'
    }
    Write-Host 'PASS: default and per-chat menus all receive and verify the current URL (no network).'
} finally {
    Remove-Item Function:\Invoke-RestMethod -ErrorAction SilentlyContinue
    Remove-Variable HealthTgMenuTestState -Scope Global -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $tempFile
}
