param([string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-miniapp-common.ps1')
$settings = Read-LocalSettings $EnvFile
$url = (Get-MiniAppUri $settings.MINI_APP_URL).AbsoluteUri
if (-not $settings.TELEGRAM_BOT_TOKEN) { throw 'TELEGRAM_BOT_TOKEN is missing.' }
$token = $settings.TELEGRAM_BOT_TOKEN
$me = Invoke-LocalTelegram $token 'getMe'
$button = @{ type = 'web_app'; text = 'Open diary'; web_app = @{ url = $url } }
$scopes = @(@{})
foreach ($id in ($settings.TELEGRAM_ALLOWED_USER_IDS -split ',')) {
    if ($id.Trim()) { $scopes += @{ chat_id = [long]$id.Trim() } }
}
foreach ($scope in $scopes) {
    $body = $scope.Clone()
    $body.menu_button = $button
    $null = Invoke-LocalTelegram $token 'setChatMenuButton' $body
    $actual = Invoke-LocalTelegram $token 'getChatMenuButton' $scope
    if ($actual.type -ne 'web_app' -or $actual.web_app.url -ne $url) {
        throw 'Telegram menu verification failed.'
    }
}
Write-Host "@$($me.username): default menu and $($scopes.Count - 1) allowed chat menus verified: $url"
