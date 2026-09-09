# Garante que todo fonte de produção esteja no MODULE-MAP.md e vice-versa.
param(
    [string]$RepositoryRoot = "",
    [switch]$Quiet,
    [switch]$Json
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Split-Path -Parent $PSScriptRoot
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$mapPath = Join-Path $root "MODULE-MAP.md"

function Write-Failure {
    param([string]$Diagnostic, [int]$SourceCount = 0)
    $result = [ordered]@{
        contract     = "sig-module-map/v1"
        status       = "fail"
        exitCode     = 2
        sourceCount  = $SourceCount
        mappedCount  = 0
        missingCount = 0
        staleCount   = 0
        comparison   = "production-sources-vs-MODULE-MAP.md"
        diagnostic   = $Diagnostic
    }
    if ($Json) { $result | ConvertTo-Json -Depth 5 -Compress }
    elseif (-not $Quiet) { [Console]::Error.WriteLine(("commit bloqueado: {0}" -f $Diagnostic)) }
    exit 2
}

$sourcesRaw = (& git -C $root -c core.safecrlf=false ls-files -- "app/src/main/java/br/gov/sp/pcsp/launcher/" 2>$null | Out-String).Trim()
$gitExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }

if ($gitExitCode -ne 0) {
    Write-Failure -Diagnostic "não foi possível listar os fontes de produção via git"
}

$sources = @(
    if (-not [string]::IsNullOrWhiteSpace($sourcesRaw)) {
        $sourcesRaw -split "\r?\n" |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Where-Object { $_ -match '\.(kt|java)$' } |
            ForEach-Object { Split-Path -Leaf $_ }
    }
) | Sort-Object -Unique

# Repositório sem fontes de produção (ex.: fixture de teste do hook): invariante vazia.
if (@($sources).Count -eq 0) {
    $empty = [ordered]@{
        contract     = "sig-module-map/v1"
        status       = "pass"
        exitCode     = 0
        sourceCount  = 0
        mappedCount  = 0
        missingCount = 0
        staleCount   = 0
        comparison   = "production-sources-vs-MODULE-MAP.md"
        note         = "nenhum fonte de produção no índice"
    }
    if ($Json) { $empty | ConvertTo-Json -Depth 5 -Compress }
    elseif (-not $Quiet) { Write-Output "PASS: nenhum fonte de produção no índice" }
    exit 0
}

if (-not (Test-Path -LiteralPath $mapPath -PathType Leaf)) {
    Write-Failure -Diagnostic "MODULE-MAP.md ausente na raiz do repositório" -SourceCount @($sources).Count
}

$mapText = Get-Content -LiteralPath $mapPath -Raw
$mapped = @(
    [regex]::Matches($mapText, '^\|\s*`([A-Za-z0-9_]+\.(?:kt|java))`', [System.Text.RegularExpressions.RegexOptions]::Multiline) |
        ForEach-Object { $_.Groups[1].Value }
) | Sort-Object -Unique

$sourceSet = New-Object System.Collections.Generic.HashSet[string] ([StringComparer]::OrdinalIgnoreCase)
foreach ($name in $sources) { [void]$sourceSet.Add($name) }
$mappedSet = New-Object System.Collections.Generic.HashSet[string] ([StringComparer]::OrdinalIgnoreCase)
foreach ($name in $mapped) { [void]$mappedSet.Add($name) }

$missing = @($sources | Where-Object { -not $mappedSet.Contains($_) } | Sort-Object)
$stale = @($mapped | Where-Object { -not $sourceSet.Contains($_) } | Sort-Object)

$status = if ($missing.Count -eq 0 -and $stale.Count -eq 0) { "pass" } else { "fail" }
$exitCode = if ($status -eq "pass") { 0 } else { 2 }
$preview = 10

$result = [ordered]@{
    contract     = "sig-module-map/v1"
    status       = $status
    exitCode     = $exitCode
    sourceCount  = @($sources).Count
    mappedCount  = @($mapped).Count
    missingCount = $missing.Count
    staleCount   = $stale.Count
    missing      = @($missing | Select-Object -First $preview)
    stale        = @($stale | Select-Object -First $preview)
    comparison   = "production-sources-vs-MODULE-MAP.md"
}

if ($Json) {
    $result | ConvertTo-Json -Depth 5 -Compress
} elseif (-not $Quiet) {
    if ($status -eq "pass") {
        Write-Output ("PASS: MODULE-MAP.md cobre os {0} fontes de produção" -f @($sources).Count)
    } else {
        if ($missing.Count -gt 0) {
            [Console]::Error.WriteLine(("commit bloqueado: {0} fonte(s) de produção fora do MODULE-MAP.md: {1}" -f $missing.Count, (($missing | Select-Object -First $preview) -join ", ")))
        }
        if ($stale.Count -gt 0) {
            [Console]::Error.WriteLine(("commit bloqueado: MODULE-MAP.md cita {0} arquivo(s) inexistente(s): {1}" -f $stale.Count, (($stale | Select-Object -First $preview) -join ", ")))
        }
    }
}

exit $exitCode
