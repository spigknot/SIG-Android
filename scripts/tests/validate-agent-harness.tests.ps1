# Testes silenciosos do gate central e dos seus caminhos de falha.
param([switch]$Quiet)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$harness = Join-Path $root "scripts\validate-agent-harness.ps1"

function Invoke-Gate {
    param([string[]]$Arguments)

    $output = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $harness @Arguments 2>&1 | Out-String).Trim()
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = $output
    }
}

$quietSuccess = Invoke-Gate @("-Quiet")
if ($quietSuccess.ExitCode -ne 0 -or -not [string]::IsNullOrWhiteSpace($quietSuccess.Output)) {
    throw "sucesso quiet produziu saída ou exit code inválido"
}

$jsonSuccess = Invoke-Gate @("-Quiet", "-Json")
if ($jsonSuccess.ExitCode -ne 0) {
    throw "sucesso JSON falhou"
}
$jsonSuccessResult = $jsonSuccess.Output | ConvertFrom-Json
if ($jsonSuccessResult.status -ne "pass" -or $jsonSuccessResult.exitCode -ne 0) {
    throw "envelope JSON de sucesso inválido"
}

$stagedTests = Join-Path $root "scripts\tests\staged-snapshot.tests.ps1"
$stagedOutput = (& powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $stagedTests -Quiet 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not [string]::IsNullOrWhiteSpace($stagedOutput)) {
    throw "teste de snapshot staged falhou"
}

$jsonFailure = Invoke-Gate @("-RunNativeDependencies", "-NativeVersion", "999", "-Quiet", "-Json")
if ($jsonFailure.ExitCode -ne 2) {
    throw "falha nativa não preservou exit code 2"
}
$jsonFailureResult = $jsonFailure.Output | ConvertFrom-Json
if ($jsonFailureResult.status -ne "fail" -or $jsonFailureResult.exitCode -ne 2) {
    throw "envelope JSON de falha inválido"
}
if ($jsonFailureResult.failures.Count -lt 1 -or $jsonFailureResult.failures[0].exitCode -ne 2) {
    throw "etapa nativa não preservou o exit code"
}
if ([string]::IsNullOrWhiteSpace($jsonFailureResult.failures[0].diagnostic)) {
    throw "falha nativa não preservou diagnóstico limitado"
}

$bootstrapRoot = Join-Path ([IO.Path]::GetTempPath()) ("sig-bootstrap-test-" + [Guid]::NewGuid().ToString("N"))
$bootstrapEvidenceRelative = "build\bootstrap-failure.json"
$bootstrapEvidencePath = Join-Path $bootstrapRoot $bootstrapEvidenceRelative
New-Item -ItemType Directory -Force -Path (Join-Path $bootstrapRoot "scripts") | Out-Null
try {
    Copy-Item -LiteralPath $harness -Destination (Join-Path $bootstrapRoot "scripts\validate-agent-harness.ps1") -Force
    Set-Content -LiteralPath (Join-Path $bootstrapRoot ".gitignore") -Value "build/" -Encoding ascii
    & git -C $bootstrapRoot init -q *> $null

    $bootstrapHarness = Join-Path $bootstrapRoot "scripts\validate-agent-harness.ps1"
    $bootstrapArguments = @("-Quiet", "-Json", "-EvidencePath", $bootstrapEvidenceRelative)
    $bootstrapOutput = (& powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $bootstrapHarness @bootstrapArguments 2>&1 | Out-String).Trim()
    $bootstrapExitCode = [int]$LASTEXITCODE
    if ($bootstrapExitCode -ne 2) {
        throw "falha de bootstrap não preservou exit code 2"
    }
    $bootstrapResult = $bootstrapOutput | ConvertFrom-Json
    if ($bootstrapResult.status -ne "fail" -or $bootstrapResult.failures[0].name -ne "diagnostics-module") {
        throw "envelope de falha de bootstrap inválido"
    }
    if (-not (Test-Path -LiteralPath $bootstrapEvidencePath -PathType Leaf)) {
        throw "falha de bootstrap não gerou evidência"
    }
    $bootstrapEvidence = (Get-Content -LiteralPath $bootstrapEvidencePath -Raw) | ConvertFrom-Json
    if ($bootstrapEvidence.status -ne "fail" -or -not $bootstrapEvidence.bootstrapFailure -or $bootstrapEvidence.failures[0].name -ne "diagnostics-module") {
        throw "evidência de falha de bootstrap inválida"
    }
} finally {
    Remove-Item -LiteralPath $bootstrapRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$safeEvidence = "build\harness-test-evidence.json"
$safeEvidencePath = Join-Path $root $safeEvidence
Remove-Item -LiteralPath $safeEvidencePath -Force -ErrorAction SilentlyContinue
$safeResult = Invoke-Gate @("-Quiet", "-Json", "-EvidencePath", $safeEvidence)
if ($safeResult.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $safeEvidencePath -PathType Leaf)) {
    throw "evidência em caminho ignorado foi rejeitada"
}
Remove-Item -LiteralPath $safeEvidencePath -Force -ErrorAction SilentlyContinue

$unsafeEvidence = "scripts\harness-test-evidence.json"
$unsafeEvidencePath = Join-Path $root $unsafeEvidence
Remove-Item -LiteralPath $unsafeEvidencePath -Force -ErrorAction SilentlyContinue
$unsafeResult = Invoke-Gate @("-Quiet", "-Json", "-EvidencePath", $unsafeEvidence)
if ($unsafeResult.ExitCode -ne 2) {
    throw "evidência fora de caminho ignorado não foi bloqueada"
}
Remove-Item -LiteralPath $unsafeEvidencePath -Force -ErrorAction SilentlyContinue

$hookPath = Join-Path $root ".githooks\pre-commit"
if (-not (Test-Path -LiteralPath $hookPath -PathType Leaf)) {
    throw "hook pre-commit versionado ausente"
}
$hookText = [IO.File]::ReadAllText($hookPath)
foreach ($requiredHookArgument in @("-Staged", "-RunAndroidGates", "-Quiet")) {
    if ($hookText -notmatch [regex]::Escape($requiredHookArgument)) {
        throw "hook pre-commit não contém $requiredHookArgument"
    }
}

$installer = Join-Path $root "scripts\install-git-hooks.ps1"
foreach ($attempt in 1..2) {
    & powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installer -Quiet
    if ($LASTEXITCODE -ne 0) {
        throw "instalador de hooks falhou na execução $attempt"
    }
}
$hookConfig = (& git config --local --get core.hooksPath 2>$null | Out-String).Trim()
if ($hookConfig -ne ".githooks") {
    throw "core.hooksPath não foi ativado pelo instalador"
}

$hookTests = Join-Path $root "scripts\tests\pre-commit.tests.ps1"
$hookOutput = (& powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $hookTests -Quiet 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not [string]::IsNullOrWhiteSpace($hookOutput)) {
    throw "teste do hook via Git falhou"
}

if (-not $Quiet) {
    Write-Output "OK: sucesso quiet, JSON pass, falha redigida e exit code preservado"
}
exit 0
