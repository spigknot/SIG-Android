# Porta central do harness do SIG Android.
# Sucesso silencioso; falhas retornam diagnóstico redigido e limitado.
param(
    [switch]$Quiet,
    [switch]$Json,
    [switch]$Staged,
    [switch]$RunAndroidGates,
    [string]$EvidencePath = "",
    [int]$FailureTailLines = 80,
    [int]$FailureTailBytes = 12000
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
$diagnosticsModule = Join-Path $PSScriptRoot "lib\diagnostics.ps1"
$failures = @()
$steps = @()

function Resolve-SafeEvidencePath {
    param([AllowEmptyString()][string]$RequestedPath = "")
    if ([string]::IsNullOrWhiteSpace($RequestedPath)) { return $null }
    try {
        $candidate = if ([IO.Path]::IsPathRooted($RequestedPath)) { [IO.Path]::GetFullPath($RequestedPath) } else { [IO.Path]::GetFullPath((Join-Path $root $RequestedPath)) }
        $rootFull = [IO.Path]::GetFullPath($root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
        $prefix = $rootFull + [IO.Path]::DirectorySeparatorChar
        if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { return $null }
        $relative = $candidate.Substring($prefix.Length).Replace([IO.Path]::DirectorySeparatorChar, "/")
        Push-Location $root
        try { & git check-ignore --no-index --quiet -- $relative *> $null; $ignoreCode = [int]$LASTEXITCODE } finally { Pop-Location }
        if ($ignoreCode -ne 0) { return $null }
        return $candidate
    } catch { return $null }
}

function Write-Evidence {
    param([object]$Envelope)
    $safePath = Resolve-SafeEvidencePath -RequestedPath $EvidencePath
    if ($null -eq $safePath) { return }
    try {
        $directory = Split-Path -Parent $safePath
        if ($directory) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
        [IO.File]::WriteAllText($safePath, (($Envelope | ConvertTo-Json -Depth 8 -Compress) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
    } catch { }
}

if (-not (Test-Path -LiteralPath $diagnosticsModule -PathType Leaf)) {
    $missing = [ordered]@{
        contract = "sig-agent-gate/v1"
        status = "fail"
        exitCode = 2
        bootstrapFailure = $true
        steps = @()
        failures = @(@{ name = "diagnostics-module"; exitCode = 2 })
    }
    Write-Evidence $missing
    if ($Json) { $missing | ConvertTo-Json -Depth 8 -Compress } else { [Console]::Error.WriteLine("módulo de diagnóstico ausente") }
    exit 2
}
. $diagnosticsModule

function Register-Failure {
    param([string]$Name, [int]$ExitCode, [string]$Diagnostic = "")
    $entry = [ordered]@{ name = $Name; exitCode = $ExitCode }
    if ($Diagnostic) { $entry.diagnostic = Redact-Diagnostic -Text $Diagnostic -MaxLines $FailureTailLines -MaxBytes $FailureTailBytes }
    $script:failures += $entry
}

function Invoke-Step {
    param([string]$Name, [scriptblock]$Action)
    $captured = ""
    $code = 0
    try {
        if ($Quiet -or $Json) {
            $captured = (& $Action 2>&1 | Out-String).Trim()
        } else {
            & $Action
        }
        $code = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
    } catch {
        $code = 1
        $captured = $_.Exception.Message
    }
    if ($code -eq 0) {
        $script:steps += [ordered]@{ name = $Name; exitCode = 0 }
    } else {
        Register-Failure -Name $Name -ExitCode $code -Diagnostic $captured
    }
}

if ($Staged -and $failures.Count -eq 0) {
    $checker = Join-Path $PSScriptRoot "check-staged-snapshot.ps1"
    if (-not (Test-Path -LiteralPath $checker -PathType Leaf)) {
        Register-Failure -Name "staged-snapshot-consistency" -ExitCode 2 -Diagnostic "verificador staged ausente"
    } else {
        $scriptHostPath = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
        if (-not $scriptHostPath) { $scriptHostPath = (Get-Command powershell.exe -ErrorAction Stop).Source }
        Invoke-Step -Name "staged-snapshot-consistency" -Action { & $scriptHostPath -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $checker -RepositoryRoot $root -Quiet -Json }
    }
}

if ($failures.Count -eq 0) {
    Push-Location $root
    try { Invoke-Step -Name "git-diff-check" -Action { & git -c core.safecrlf=false -c core.whitespace=cr-at-eol diff --check -- } } finally { Pop-Location }
}

if ($RunAndroidGates -and $failures.Count -eq 0) {
    $gradle = Join-Path $root "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
        Register-Failure -Name "android-gates" -ExitCode 2 -Diagnostic "Gradle wrapper ausente"
    } else {
        Push-Location $root
        try { Invoke-Step -Name "android-gates" -Action { & $gradle --quiet :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain } } finally { Pop-Location }
    }
}

$safeEvidence = Resolve-SafeEvidencePath -RequestedPath $EvidencePath
if ($EvidencePath -and $null -eq $safeEvidence) {
    Register-Failure -Name "evidence-path-safety" -ExitCode 2 -Diagnostic "EvidencePath deve estar dentro da raiz e em caminho ignorado pelo Git"
}

$status = if ($failures.Count -eq 0) { "pass" } else { "fail" }
$exitCode = if ($failures.Count -eq 0) { 0 } else { [int]$failures[0].exitCode }
$result = [ordered]@{
    contract = "sig-agent-gate/v1"
    status = $status
    exitCode = $exitCode
    steps = @($steps)
    failures = @($failures)
    stagedRequested = [bool]$Staged
    androidGatesRequested = [bool]$RunAndroidGates
}
Write-Evidence $result

if ($Json) {
    $result | ConvertTo-Json -Depth 8 -Compress
} elseif ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        [Console]::Error.WriteLine(("etapa {0} falhou com exit code {1}" -f $failure.name, $failure.exitCode))
        if ($failure.diagnostic) { [Console]::Error.WriteLine(("diagnóstico limitado: {0}" -f $failure.diagnostic)) }
    }
} elseif (-not $Quiet) {
    Write-Output ("PASS: {0} etapa(s)" -f $steps.Count)
}
exit $exitCode
