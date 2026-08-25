# Testa a política conservadora de bloquear divergência staged/working tree.
param([switch]$Quiet)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$checker = Join-Path $root "scripts\check-staged-snapshot.ps1"
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("sig-staged-test-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

function Invoke-Checker {
    param([string]$RepositoryRoot)
    $output = (& powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $checker -RepositoryRoot $RepositoryRoot -Quiet -Json 2>&1 | Out-String).Trim()
    [pscustomobject]@{ ExitCode = [int]$LASTEXITCODE; Result = ($output | ConvertFrom-Json) }
}

try {
    & git -C $tempRoot init -q *> $null
    & git -C $tempRoot config user.email "sig-tests@example.invalid" *> $null
    & git -C $tempRoot config user.name "SIG tests" *> $null
    Set-Content -LiteralPath (Join-Path $tempRoot "tracked.txt") -Value "base" -Encoding ascii
    & git -C $tempRoot add -- tracked.txt *> $null
    $clean = Invoke-Checker $tempRoot
    if ($clean.ExitCode -ne 0 -or $clean.Result.status -ne "pass") { throw "snapshot staged limpo foi rejeitado" }

    Set-Content -LiteralPath (Join-Path $tempRoot "tracked.txt") -Value "staged" -Encoding ascii
    & git -C $tempRoot add -- tracked.txt *> $null
    Set-Content -LiteralPath (Join-Path $tempRoot "tracked.txt") -Value "divergente" -Encoding ascii
    $divergent = Invoke-Checker $tempRoot
    if ($divergent.ExitCode -ne 2 -or $divergent.Result.unstagedTrackedCount -ne 1) { throw "divergência staged/working tree não bloqueou" }
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

if (-not $Quiet) { Write-Output "OK: snapshot staged limpo aceito e divergência bloqueada" }
exit 0
