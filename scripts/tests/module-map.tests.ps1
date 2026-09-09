# Contrato do MODULE-MAP: fonte sem etiqueta e etiqueta sem fonte bloqueiam.
param([switch]$Quiet)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$checker = Join-Path $root "scripts\check-module-map.ps1"
$scriptHost = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
if (-not $scriptHost) { $scriptHost = (Get-Command powershell.exe -ErrorAction Stop).Source }

function Invoke-Checker {
    param([string]$RepositoryRoot, [switch]$Json)
    $arguments = @("-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", $checker, "-RepositoryRoot", $RepositoryRoot)
    if ($Json) { $arguments += @("-Json") } else { $arguments += @("-Quiet") }
    $output = (& $scriptHost @arguments 2>&1 | Out-String).Trim()
    [pscustomobject]@{ ExitCode = [int]$LASTEXITCODE; Output = $output }
}

function New-MapFile {
    param([string]$Fixture, [string[]]$Names)
    $lines = @("# mapa de teste", "", "| Arquivo | Responsabilidade |", "|---|---|")
    foreach ($name in $Names) { $lines += ('| `' + $name + '` | responsabilidade |') }
    Set-Content -LiteralPath (Join-Path $Fixture "MODULE-MAP.md") -Value $lines -Encoding ascii
}

$fixture = Join-Path ([IO.Path]::GetTempPath()) ("sig-map-test-" + [Guid]::NewGuid().ToString("N"))
$sourceDir = Join-Path $fixture "app\src\main\java\br\gov\sp\pcsp\launcher"

$emptyFixture = Join-Path ([IO.Path]::GetTempPath()) ("sig-map-empty-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $emptyFixture | Out-Null
    & git -C $emptyFixture init -q *> $null
    # Repositório sem fontes: invariante vazia (protege o fixture do hook).
    $vacuous = Invoke-Checker -RepositoryRoot $emptyFixture -Json
    if ($vacuous.ExitCode -ne 0) { throw "repositório sem fontes deveria passar" }
} finally {
    Remove-Item -LiteralPath $emptyFixture -Recurse -Force -ErrorAction SilentlyContinue
}

try {
    New-Item -ItemType Directory -Force -Path $sourceDir | Out-Null
    Set-Content -LiteralPath (Join-Path $sourceDir "Alpha.kt") -Value "class Alpha" -Encoding ascii
    Set-Content -LiteralPath (Join-Path $sourceDir "Beta.kt") -Value "class Beta" -Encoding ascii
    & git -C $fixture init -q *> $null
    & git -C $fixture add --all *> $null

    New-MapFile -Fixture $fixture -Names @("Alpha.kt", "Beta.kt")
    & git -C $fixture add --all *> $null

    $silent = Invoke-Checker -RepositoryRoot $fixture
    if ($silent.ExitCode -ne 0 -or $silent.Output) { throw "mapa completo deveria passar em silêncio: $($silent.Output)" }

    $complete = Invoke-Checker -RepositoryRoot $fixture -Json
    $completeResult = $complete.Output | ConvertFrom-Json
    if ($completeResult.status -ne "pass" -or $completeResult.sourceCount -ne 2 -or $completeResult.mappedCount -ne 2) { throw "envelope de mapa completo inválido" }

    Set-Content -LiteralPath (Join-Path $sourceDir "Gamma.kt") -Value "class Gamma" -Encoding ascii
    & git -C $fixture add --all *> $null
    $missing = Invoke-Checker -RepositoryRoot $fixture -Json
    $missingResult = $missing.Output | ConvertFrom-Json
    if ($missing.ExitCode -ne 2 -or $missingResult.missingCount -ne 1 -or $missingResult.missing[0] -ne "Gamma.kt") { throw "fonte fora do mapa não foi bloqueada" }

    New-MapFile -Fixture $fixture -Names @("Alpha.kt", "Beta.kt", "Gamma.kt", "Zeta.kt")
    & git -C $fixture add --all *> $null
    $stale = Invoke-Checker -RepositoryRoot $fixture -Json
    $staleResult = $stale.Output | ConvertFrom-Json
    if ($stale.ExitCode -ne 2 -or $staleResult.staleCount -ne 1 -or $staleResult.stale[0] -ne "Zeta.kt") { throw "etiqueta órfã não foi bloqueada" }

    Remove-Item -LiteralPath (Join-Path $fixture "MODULE-MAP.md") -Force
    & git -C $fixture add --all *> $null
    $absent = Invoke-Checker -RepositoryRoot $fixture -Json
    if ($absent.ExitCode -ne 2) { throw "MODULE-MAP ausente com fontes presentes não foi bloqueado" }
} finally {
    Remove-Item -LiteralPath $fixture -Recurse -Force -ErrorAction SilentlyContinue
}

if (-not $Quiet) { Write-Output "OK: MODULE-MAP cobre fontes, bloqueia órfãos e tolera repositório sem fontes" }
exit 0
