# Executa o hook pelo Git em um repositório temporário.
param([switch]$Quiet)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("sig-hook-test-" + [Guid]::NewGuid().ToString("N"))
$copyPaths = @(
    ".githooks\pre-commit",
    ".gitattributes",
    "scripts\validate-agent-harness.ps1",
    "scripts\check-staged-snapshot.ps1",
    "scripts\lib\diagnostics.ps1"
)
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

function Invoke-Hook {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = (& git -C $tempRoot hook run pre-commit 2>&1 | Out-String).Trim()
        $exitCode = [int]$LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}

try {
    foreach ($relativePath in $copyPaths) {
        $source = Join-Path $root $relativePath
        $destination = Join-Path $tempRoot $relativePath
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Force
    }
    Set-Content -LiteralPath (Join-Path $tempRoot "tracked.txt") -Value "base" -Encoding ascii
    Set-Content -LiteralPath (Join-Path $tempRoot "gradlew.bat") -Value @("@echo off", "exit /b 0") -Encoding ascii
    & git -C $tempRoot init -q *> $null
    & git -C $tempRoot config user.email "sig-tests@example.invalid" *> $null
    & git -C $tempRoot config user.name "SIG tests" *> $null
    & git -C $tempRoot config core.hooksPath .githooks *> $null
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & git -C $tempRoot add --all *> $null
        $addExitCode = [int]$LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($addExitCode -ne 0) { throw "git add falhou no repositório temporário" }

    $clean = Invoke-Hook
    if ($clean.ExitCode -ne 0 -or $clean.Output) { throw "hook falhou no snapshot limpo: $($clean.Output)" }

    Add-Content -LiteralPath (Join-Path $tempRoot "tracked.txt") -Value "divergencia staged"
    $blocked = Invoke-Hook
    if ($blocked.ExitCode -eq 0 -or $blocked.Output -notmatch "staged-snapshot-consistency") { throw "hook não bloqueou divergência staged" }
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

if (-not $Quiet) { Write-Output "OK: hook via Git silencioso no sucesso e bloqueia divergência" }
exit 0
