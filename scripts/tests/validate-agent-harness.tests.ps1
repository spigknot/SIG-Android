# Suíte contratual silenciosa do gate, do hook e das fronteiras de estado.
param([switch]$Quiet)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$harness = Join-Path $root "scripts\validate-agent-harness.ps1"
$scriptHost = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
if (-not $scriptHost) { $scriptHost = (Get-Command powershell.exe -ErrorAction Stop).Source }

function Invoke-Gate {
    param([string[]]$Arguments)
    $output = (& $scriptHost -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $harness @Arguments 2>&1 | Out-String).Trim()
    [pscustomobject]@{ ExitCode = [int]$LASTEXITCODE; Output = $output }
}

$quietSuccess = Invoke-Gate @("-Quiet")
if ($quietSuccess.ExitCode -ne 0 -or $quietSuccess.Output) { throw "sucesso quiet produziu saída ou exit code inválido" }

$jsonSuccess = Invoke-Gate @("-Quiet", "-Json")
if ($jsonSuccess.ExitCode -ne 0) { throw "sucesso JSON falhou" }
$jsonSuccessResult = $jsonSuccess.Output | ConvertFrom-Json
if ($jsonSuccessResult.status -ne "pass" -or $jsonSuccessResult.exitCode -ne 0) { throw "envelope JSON de sucesso inválido" }

$stagedTests = Join-Path $root "scripts\tests\staged-snapshot.tests.ps1"
$stagedOutput = (& $scriptHost -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $stagedTests -Quiet 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $stagedOutput) { throw "teste de snapshot staged falhou" }

$bootstrapRoot = Join-Path ([IO.Path]::GetTempPath()) ("sig-bootstrap-test-" + [Guid]::NewGuid().ToString("N"))
$bootstrapEvidenceRelative = "build\bootstrap-failure.json"
$bootstrapEvidencePath = Join-Path $bootstrapRoot $bootstrapEvidenceRelative
New-Item -ItemType Directory -Force -Path (Join-Path $bootstrapRoot "scripts") | Out-Null
try {
    Copy-Item -LiteralPath $harness -Destination (Join-Path $bootstrapRoot "scripts\validate-agent-harness.ps1") -Force
    Set-Content -LiteralPath (Join-Path $bootstrapRoot ".gitignore") -Value "build/" -Encoding ascii
    & git -C $bootstrapRoot init -q *> $null
    $bootstrapOutput = (& $scriptHost -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $bootstrapRoot "scripts\validate-agent-harness.ps1") -Quiet -Json -EvidencePath $bootstrapEvidenceRelative 2>&1 | Out-String).Trim()
    if ([int]$LASTEXITCODE -ne 2) { throw "falha de bootstrap não preservou exit code 2" }
    $bootstrapResult = $bootstrapOutput | ConvertFrom-Json
    if ($bootstrapResult.status -ne "fail" -or $bootstrapResult.failures[0].name -ne "diagnostics-module") { throw "envelope de bootstrap inválido" }
    if (-not (Test-Path -LiteralPath $bootstrapEvidencePath -PathType Leaf)) { throw "falha de bootstrap não gerou evidência" }
} finally {
    Remove-Item -LiteralPath $bootstrapRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$safeEvidence = "build\harness-test-evidence.json"
$safeEvidencePath = Join-Path $root $safeEvidence
Remove-Item -LiteralPath $safeEvidencePath -Force -ErrorAction SilentlyContinue
$safeResult = Invoke-Gate @("-Quiet", "-Json", "-EvidencePath", $safeEvidence)
if ($safeResult.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $safeEvidencePath -PathType Leaf)) { throw "evidência em caminho ignorado foi rejeitada" }
Remove-Item -LiteralPath $safeEvidencePath -Force -ErrorAction SilentlyContinue

$unsafeEvidence = "scripts\harness-test-evidence.json"
$unsafeResult = Invoke-Gate @("-Quiet", "-Json", "-EvidencePath", $unsafeEvidence)
if ($unsafeResult.ExitCode -ne 2) { throw "evidência fora de caminho ignorado não foi bloqueada" }
Remove-Item -LiteralPath (Join-Path $root $unsafeEvidence) -Force -ErrorAction SilentlyContinue

$realHooksPathBefore = (& git -C $root config --local --get core.hooksPath 2>$null | Out-String).Trim()
$installerRoot = Join-Path ([IO.Path]::GetTempPath()) ("sig-installer-test-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path (Join-Path $installerRoot "scripts") | Out-Null
try {
    $installer = Join-Path $installerRoot "scripts\install-git-hooks.ps1"
    Copy-Item -LiteralPath (Join-Path $root "scripts\install-git-hooks.ps1") -Destination $installer -Force
    & git -C $installerRoot init -q *> $null
    foreach ($attempt in 1..2) {
        & $scriptHost -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installer -Quiet
        if ($LASTEXITCODE -ne 0) { throw "instalador de hooks falhou na execução $attempt" }
    }
    $tempHooksPath = (& git -C $installerRoot config --local --get core.hooksPath 2>$null | Out-String).Trim()
    if ($tempHooksPath -ne ".githooks") { throw "instalador não configurou o fixture temporário" }
} finally {
    Remove-Item -LiteralPath $installerRoot -Recurse -Force -ErrorAction SilentlyContinue
}
$realHooksPathAfter = (& git -C $root config --local --get core.hooksPath 2>$null | Out-String).Trim()
if ($realHooksPathBefore -ne $realHooksPathAfter) { throw "teste do instalador alterou core.hooksPath do clone real" }

$hookPath = Join-Path $root ".githooks\pre-commit"
if (-not (Test-Path -LiteralPath $hookPath -PathType Leaf)) { throw "hook pre-commit versionado ausente" }
$hookTests = Join-Path $root "scripts\tests\pre-commit.tests.ps1"
$hookOutput = (& $scriptHost -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $hookTests -Quiet 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $hookOutput) { throw "teste do hook via Git falhou" }

if (-not $Quiet) { Write-Output "OK: contratos do gate, EvidencePath, instalador e hook" }
exit 0
