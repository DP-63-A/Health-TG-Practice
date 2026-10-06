param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("seed", "reset")]
    [string]$Command,
    [string]$StartDate,
    [long]$Seed = 20260505,
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\.env")
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Configuration file not found: $EnvFile. Copy .env.example to .env first."
}

$lineNumber = 0
foreach ($line in Get-Content -LiteralPath $EnvFile) {
    $lineNumber++
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
        continue
    }

    $separator = $trimmed.IndexOf("=")
    if ($separator -le 0) {
        throw "Invalid configuration at line $lineNumber. Expected NAME=VALUE."
    }

    $name = $trimmed.Substring(0, $separator).Trim()
    $value = $trimmed.Substring($separator + 1).Trim()
    if ($name -notmatch "^[A-Za-z_][A-Za-z0-9_]*$") {
        throw "Invalid variable name at line $lineNumber."
    }
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    Set-Item -Path "Env:$name" -Value $value
}

$arguments = $Command
if ($Command -eq "seed") {
    if ($StartDate) {
        $arguments += " --start-date=$StartDate"
    }
    $arguments += " --seed=$Seed"
}

Push-Location $repoRoot
try {
    & (Join-Path $repoRoot "gradlew.bat") :backend:api:demoData "-PdemoArgs=$arguments" --console=plain
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

exit $exitCode
