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
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & git -C $tempRoot add -- tracked.txt *> $null
        $addExitCode = [int]$LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($addExitCode -ne 0) { throw "git add inicial falhou" }

    $clean = Invoke-Checker $tempRoot
    if ($clean.ExitCode -ne 0 -or $clean.Result.status -ne "pass" -or $clean.Result.unstagedTrackedCount -ne 0) {
        throw "snapshot staged limpo foi rejeitado"
    }

    Set-Content -LiteralPath (Join-Path $tempRoot "tracked.txt") -Value "staged" -Encoding ascii
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & git -C $tempRoot add -- tracked.txt *> $null
        $addExitCode = [int]$LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($addExitCode -ne 0) { throw "git add staged falhou" }
    Set-Content -LiteralPath (Join-Path $tempRoot "tracked.txt") -Value "divergente" -Encoding ascii
    $divergent = Invoke-Checker $tempRoot
    if ($divergent.ExitCode -ne 2 -or $divergent.Result.status -ne "fail" -or $divergent.Result.unstagedTrackedCount -ne 1) {
        throw "divergência staged/working tree não bloqueou"
    }

    Set-Content -LiteralPath (Join-Path $tempRoot "tracked.txt") -Value "staged" -Encoding ascii
    Set-Content -LiteralPath (Join-Path $tempRoot "docs-note.txt") -Value "fora do build" -Encoding ascii
    $nonBuildUntracked = Invoke-Checker $tempRoot
    if ($nonBuildUntracked.ExitCode -ne 0 -or $nonBuildUntracked.Result.untrackedBuildInputCount -ne 0) {
        throw "arquivo não relacionado ao build foi bloqueado"
    }

    New-Item -ItemType Directory -Force -Path (Join-Path $tempRoot "app\src\main\java") | Out-Null
    Set-Content -LiteralPath (Join-Path $tempRoot "app\src\main\java\Unstaged.kt") -Value "class Unstaged" -Encoding ascii
    $untrackedBuildInput = Invoke-Checker $tempRoot
    if ($untrackedBuildInput.ExitCode -ne 2 -or $untrackedBuildInput.Result.untrackedBuildInputCount -ne 1) {
        throw "entrada de build não rastreada não bloqueou"
    }

    Remove-Item -LiteralPath (Join-Path $tempRoot "app") -Recurse -Force
    Remove-Item -LiteralPath (Join-Path $tempRoot "docs-note.txt") -Force
    $cleanAgain = Invoke-Checker $tempRoot
    if ($cleanAgain.ExitCode -ne 0 -or $cleanAgain.Result.status -ne "pass") {
        throw "snapshot staged voltou inconsistente após limpeza"
    }
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

if (-not $Quiet) {
    Write-Output "OK: snapshot staged limpo aceito e divergência bloqueada"
}
exit 0
