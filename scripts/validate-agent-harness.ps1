# Porta central do harness do SIG Android.
# O sucesso é silencioso; falhas mantêm somente um diagnóstico redigido e limitado.
param(
    [switch]$Quiet,
    [switch]$Json,
    [switch]$Staged,
    [switch]$RunAndroidGates,
    [switch]$RunNativeDependencies,
    [int]$NativeVersion = 0,
    [string]$NativeOutputDir = "native-dependencies\build",
    [string]$EvidencePath = "",
    [int]$FailureTailLines = 80,
    [int]$FailureTailBytes = 12000
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
$failures = @()
$steps = @()
$diagnosticsModule = Join-Path $PSScriptRoot "lib\diagnostics.ps1"
$stagedVerifier = Join-Path $PSScriptRoot "check-staged-snapshot.ps1"
$diagnosticsTests = Join-Path $PSScriptRoot "tests\diagnostics.tests.ps1"
$environmentTests = Join-Path $PSScriptRoot "tests\build-environment.tests.ps1"
$scriptHostCommand = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
if ([string]::IsNullOrWhiteSpace($scriptHostCommand)) {
    $scriptHostCommand = (Get-Command powershell.exe -ErrorAction SilentlyContinue).Source
}
if ($FailureTailLines -lt 1) { $FailureTailLines = 80 }
if ($FailureTailBytes -lt 256) { $FailureTailBytes = 12000 }

function Resolve-SafeEvidencePath {
    param(
        [AllowEmptyString()]
        [string]$RequestedPath = ""
    )

    if ([string]::IsNullOrWhiteSpace($RequestedPath)) {
        return $null
    }

    try {
        $candidatePath = if ([IO.Path]::IsPathRooted($RequestedPath)) {
            [IO.Path]::GetFullPath($RequestedPath)
        } else {
            [IO.Path]::GetFullPath((Join-Path $root $RequestedPath))
        }
        $rootFullPath = [IO.Path]::GetFullPath($root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
        $rootPrefix = $rootFullPath + [IO.Path]::DirectorySeparatorChar
        if (-not $candidatePath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            return $null
        }

        $relativeEvidencePath = $candidatePath.Substring($rootPrefix.Length).Replace([IO.Path]::DirectorySeparatorChar, "/")
        Push-Location $root
        try {
            & git check-ignore --no-index --quiet -- $relativeEvidencePath *> $null
            $ignoreExitCode = if ($null -eq $LASTEXITCODE) { 1 } else { [int]$LASTEXITCODE }
        } finally {
            Pop-Location
        }
        if ($ignoreExitCode -ne 0) {
            return $null
        }
        return $candidatePath
    } catch {
        return $null
    }
}

function Write-BootstrapEvidence {
    param([object]$Envelope)

    $safePath = Resolve-SafeEvidencePath -RequestedPath $EvidencePath
    if ([string]::IsNullOrWhiteSpace($safePath)) {
        return
    }

    try {
        $evidenceDirectory = Split-Path -Parent $safePath
        if (-not [string]::IsNullOrWhiteSpace($evidenceDirectory)) {
            New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
        }
        $evidenceJson = $Envelope | ConvertTo-Json -Depth 8 -Compress
        [IO.File]::WriteAllText(
            $safePath,
            $evidenceJson + [Environment]::NewLine,
            [Text.UTF8Encoding]::new($false)
        )
    } catch {
        # A bootstrap error must never mask the original failure or leak details.
    }
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
    Write-BootstrapEvidence -Envelope $missing
    if ($Json) { $missing | ConvertTo-Json -Depth 6 -Compress }
    else { [Console]::Error.WriteLine("módulo de diagnóstico ausente") }
    exit 2
}
. $diagnosticsModule

function Register-Failure {
    param(
        [string]$Label,
        [int]$ExitCode,
        [string]$Diagnostic = ""
    )

    $step = [ordered]@{
        name = $Label
        exitCode = $ExitCode
    }
    if (-not [string]::IsNullOrWhiteSpace($Diagnostic)) {
        $step.diagnostic = (Redact-Diagnostic -Text $Diagnostic -MaxLines $FailureTailLines -MaxBytes $FailureTailBytes)
    }
    $script:steps += $step

    $failure = [ordered]@{
        name = $Label
        exitCode = $ExitCode
    }
    if (-not [string]::IsNullOrWhiteSpace($Diagnostic)) {
        $failure.diagnostic = (Redact-Diagnostic -Text $Diagnostic -MaxLines $FailureTailLines -MaxBytes $FailureTailBytes)
    }
    $script:failures += $failure
}

function Invoke-Step {
    param(
        [string]$Label,
        [string]$File,
        [string[]]$Arguments = @()
    )

    $tempLog = $null
    $captured = ""
    $executionError = ""
    $code = 1

    Push-Location $root
    try {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $isPowerShellScript = ([IO.Path]::GetExtension($File) -ieq ".ps1")
            if ($Quiet -or $Json) {
                $tempLog = Join-Path ([IO.Path]::GetTempPath()) ("sig-agent-gate-" + [Guid]::NewGuid().ToString("N") + ".log")
                if ($isPowerShellScript) {
                    & $scriptHostCommand -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $File @Arguments *> $tempLog
                } else {
                    & $File @Arguments *> $tempLog
                }
            } else {
                if ($isPowerShellScript) {
                    & $scriptHostCommand -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $File @Arguments
                } else {
                    & $File @Arguments
                }
            }
            $code = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
        } catch {
            $code = 1
            $executionError = $_.Exception.Message
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }

        if ($tempLog -and (Test-Path -LiteralPath $tempLog -PathType Leaf)) {
            $captured = [IO.File]::ReadAllText($tempLog)
        }
    } finally {
        Pop-Location
        if ($tempLog -and (Test-Path -LiteralPath $tempLog -PathType Leaf)) {
            Remove-Item -LiteralPath $tempLog -Force -ErrorAction SilentlyContinue
        }
    }

    if ($code -eq 0) {
        $script:steps += [ordered]@{
            name = $Label
            exitCode = 0
        }
        return
    }

    $diagnostic = ($executionError + [Environment]::NewLine + $captured).Trim()
    Register-Failure -Label $Label -ExitCode $code -Diagnostic $diagnostic
}

$prefixVerifier = Join-Path $PSScriptRoot "verify-agent-prefix.ps1"
$prefixTests = Join-Path $PSScriptRoot "tests\verify-agent-prefix.tests.ps1"

if (-not (Test-Path -LiteralPath $prefixVerifier -PathType Leaf)) {
    Register-Failure -Label "agent-prefix-verifier" -ExitCode 2 -Diagnostic "verificador ausente"
} elseif (-not (Test-Path -LiteralPath $prefixTests -PathType Leaf)) {
    Register-Failure -Label "agent-prefix-fixtures" -ExitCode 2 -Diagnostic "testes do verificador ausentes"
} else {
    Invoke-Step -Label "agent-prefix-fixtures" -File $prefixTests -Arguments @("-Quiet")
}

if ($failures.Count -eq 0) {
    if (-not (Test-Path -LiteralPath $environmentTests -PathType Leaf)) {
        Register-Failure -Label "build-environment-contract" -ExitCode 2 -Diagnostic "teste de ambiente ausente"
    } else {
        Invoke-Step -Label "build-environment-contract" -File $environmentTests -Arguments @("-Quiet")
    }
}

if ($failures.Count -eq 0) {
    if (-not (Test-Path -LiteralPath $diagnosticsTests -PathType Leaf)) {
        Register-Failure -Label "diagnostic-fixtures" -ExitCode 2 -Diagnostic "testes de redaction ausentes"
    } else {
        Invoke-Step -Label "diagnostic-fixtures" -File $diagnosticsTests -Arguments @("-Quiet")
    }
}

if ($Staged -and $failures.Count -eq 0) {
    if (-not (Test-Path -LiteralPath $stagedVerifier -PathType Leaf)) {
        Register-Failure -Label "staged-snapshot-consistency" -ExitCode 2 -Diagnostic "verificador staged ausente"
    } else {
        Invoke-Step -Label "staged-snapshot-consistency" -File $stagedVerifier -Arguments @("-RepositoryRoot", $root, "-Quiet", "-Json")
    }
}

if ($failures.Count -eq 0) {
    $diffArguments = @("-c", "core.safecrlf=false", "-c", "core.whitespace=cr-at-eol", "diff")
    if ($Staged) {
        $diffArguments += "--cached"
    }
    $diffArguments += @("--check", "--")
    Invoke-Step -Label "git-diff-check" -File "git" -Arguments $diffArguments
}

if ($RunAndroidGates -and $failures.Count -eq 0) {
    Invoke-Step -Label "android-gates" -File (Join-Path $root "gradlew.bat") -Arguments @("--quiet", ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug", "--console=plain")
}

if ($RunNativeDependencies -and $failures.Count -eq 0) {
    if ($NativeVersion -le 0) {
        Register-Failure -Label "native-dependencies" -ExitCode 2 -Diagnostic "NativeVersion deve ser informado quando RunNativeDependencies estiver ativo"
    } else {
        $nativeVerifier = Join-Path $PSScriptRoot "verify-native-dependencies.ps1"
        if (-not (Test-Path -LiteralPath $nativeVerifier -PathType Leaf)) {
            Register-Failure -Label "native-dependencies" -ExitCode 2 -Diagnostic "verificador nativo ausente"
        } else {
            Invoke-Step -Label "native-dependencies" -File $nativeVerifier -Arguments @("-Version", "$NativeVersion", "-OutputDir", $NativeOutputDir, "-Quiet", "-Json")
        }
    }
}

$stagedDiffScope = if ($Staged) { "git-index" } else { "working-tree" }
$androidGatesScope = if (-not $RunAndroidGates) {
    "not-requested"
} elseif ($Staged) {
    "working-tree-after-staged-consistency-guard"
} else {
    "working-tree"
}

$evidenceFullPath = $null
if (-not [string]::IsNullOrWhiteSpace($EvidencePath)) {
    $evidenceFullPath = Resolve-SafeEvidencePath -RequestedPath $EvidencePath
    if ($null -eq $evidenceFullPath) {
        Register-Failure -Label "evidence-path-safety" -ExitCode 2 -Diagnostic "EvidencePath deve estar dentro da raiz e em caminho ignorado pelo Git"
    }
}

$status = if ($failures.Count -eq 0) { "pass" } else { "fail" }
$exitCode = if ($failures.Count -eq 0) { 0 } else { [int]$failures[0].exitCode }
$result = [ordered]@{
    contract = "sig-agent-gate/v1"
    status = $status
    exitCode = $exitCode
    steps = @($steps)
    failures = @($failures)
    stagedDiffRequested = [bool]$Staged
    stagedDiffScope = $stagedDiffScope
    stagedConsistencyRequested = [bool]$Staged
    stagedConsistencyScope = if ($Staged) { "working-tree-vs-index" } else { "not-requested" }
    stagedConsistencyPolicy = if ($Staged) {
        "blocks-unstaged-tracked-and-untracked-gradle-inputs"
    } else {
        "not-requested"
    }
    androidGatesRequested = [bool]$RunAndroidGates
    androidGatesScope = $androidGatesScope
    nativeVerificationRequested = [bool]$RunNativeDependencies
}

if ($evidenceFullPath) {
    $evidenceDirectory = Split-Path -Parent $evidenceFullPath
    if (-not [string]::IsNullOrWhiteSpace($evidenceDirectory)) {
        New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
    }
    $evidenceJson = $result | ConvertTo-Json -Depth 8 -Compress
    [IO.File]::WriteAllText($evidenceFullPath, $evidenceJson + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

if ($Json) {
    $result | ConvertTo-Json -Depth 8 -Compress
} elseif ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        [Console]::Error.WriteLine(("etapa {0} falhou com exit code {1}" -f $failure.name, $failure.exitCode))
        if (($failure.Keys -contains "diagnostic") -and $failure.diagnostic) {
            [Console]::Error.WriteLine(("diagnóstico limitado: {0}" -f $failure.diagnostic))
        }
    }
} elseif (-not $Quiet) {
    Write-Output ("PASS: {0} etapa(s), saída detalhada suprimida apenas com -Quiet" -f $steps.Count)
}

exit $exitCode
