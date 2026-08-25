# Teste determinístico do verificador de prefixo.
param([switch]$Quiet)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$verifier = Join-Path $root "scripts\verify-agent-prefix.ps1"
$stable = Join-Path $PSScriptRoot "fixtures\agent-prefix-stable.md"
$dynamic = Join-Path $PSScriptRoot "fixtures\agent-prefix-dynamic.md"

function Invoke-VerifierJson([string]$Target) {
    $output = (& $verifier -Path $Target -Json -Quiet 2>&1 | Out-String).Trim()
    $exitCode = $LASTEXITCODE
    if ([string]::IsNullOrWhiteSpace($output)) {
        throw "verificador não produziu JSON para $Target"
    }
    [pscustomobject]@{
        ExitCode = $exitCode
        Json = ($output | ConvertFrom-Json)
    }
}

$first = Invoke-VerifierJson $stable
if ($first.ExitCode -ne 0 -or $first.Json.status -ne "pass") {
    throw "fixture estável foi rejeitada"
}

$second = Invoke-VerifierJson $stable
foreach ($field in @("prefixFingerprint", "prefixBytes", "estimatedTokens")) {
    if ($first.Json.$field -ne $second.Json.$field) {
        throw "fingerprint instável no campo $field"
    }
}
if ($first.Json.PSObject.Properties.Name -contains "path" -or $first.Json.PSObject.Properties.Name -contains "fingerprint") {
    throw "envelope de prompt cache contém nomes legados ou caminho local"
}
if ($first.Json.telemetryStatus -ne "unavailable") {
    throw "telemetria não observada não foi marcada como unavailable"
}

$rootResult = Invoke-VerifierJson (Join-Path $root "AGENTS.md")
if ($rootResult.ExitCode -ne 0 -or $rootResult.Json.status -ne "pass") {
    throw "AGENTS.md excede o contrato estável"
}

$rejected = Invoke-VerifierJson $dynamic
if ($rejected.ExitCode -eq 0 -or $rejected.Json.status -ne "fail") {
    throw "fixture dinâmica foi aceita"
}
if ($rejected.Json.forbiddenMatches.Count -lt 3) {
    throw "fixture dinâmica não cobriu os padrões proibidos"
}

if (-not $Quiet) {
    Write-Output "OK: prefixo estável, fingerprint determinístico e fixture dinâmica rejeitada"
}
exit 0
