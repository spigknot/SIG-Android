# Executa o hook pelo Git em um repositório temporário e testa o bloqueio staged.
param([switch]$Quiet)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("sig-hook-test-" + [Guid]::NewGuid().ToString("N"))
$copyPaths = @(
    ".githooks\pre-commit",
    ".gitattributes",
    "AGENTS.md",
    "scripts\validate-agent-harness.ps1",
    "scripts\check-staged-snapshot.ps1",
    "scripts\verify-agent-prefix.ps1",
    "scripts\lib\diagnostics.ps1",
    "scripts\tests\build-environment.tests.ps1",
    "scripts\tests\verify-agent-prefix.tests.ps1",
    "scripts\tests\diagnostics.tests.ps1",
    "scripts\tests\fixtures\agent-prefix-stable.md",
    "scripts\tests\fixtures\agent-prefix-dynamic.md",
    "gradle\gradle-daemon-jvm.properties",
    "gradle\wrapper\gradle-wrapper.properties",
    "app\build.gradle",
    "README.txt",
    "UPDATE.md",
    ".github\workflows\validation.yml"
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
    [IO.File]::WriteAllText(
        (Join-Path $tempRoot "gradlew.bat"),
        "@echo off`r`necho Gradle 9.7.0`r`necho Launcher JVM: 21.0.12.1`r`necho Daemon JVM: Compatible with Java 21`r`nexit /b 0`r`n",
        [Text.ASCIIEncoding]::new()
    )

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
    if ($clean.ExitCode -ne 0 -or -not [string]::IsNullOrWhiteSpace($clean.Output)) {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $diffOutput = (& git -C $tempRoot -c core.safecrlf=false -c core.whitespace=cr-at-eol diff --cached --check -- 2>&1 | Out-String).Trim()
            $diffExitCode = [int]$LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        throw ("hook via Git não foi silencioso ou falhou no snapshot limpo: hook={0}; diff={1}; diffOutput={2}" -f $clean.Output, $diffExitCode, $diffOutput)
    }

    Add-Content -LiteralPath (Join-Path $tempRoot "AGENTS.md") -Value "divergencia staged"
    $blocked = Invoke-Hook
    if ($blocked.ExitCode -eq 0 -or $blocked.Output -notmatch "staged-snapshot-consistency") {
        throw "hook via Git não bloqueou divergência staged"
    }
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

if (-not $Quiet) {
    Write-Output "OK: hook via Git silencioso no sucesso e bloqueia divergência"
}
exit 0
