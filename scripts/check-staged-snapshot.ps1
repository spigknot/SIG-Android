# Garante que o hook e o build não usem entradas de código fora do índice.
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
$raw = (& git -C $root -c core.safecrlf=false diff --name-only --no-ext-diff --diff-filter=ACDMRTUXB -- 2>$null | Out-String).Trim()
$gitExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }

if ($gitExitCode -ne 0) {
    $result = [ordered]@{
        contract = "sig-staged-snapshot/v1"
        status = "fail"
        exitCode = 2
        unstagedTrackedCount = 0
        diagnostic = "não foi possível comparar a árvore de trabalho com o índice"
    }
    if ($Json) { $result | ConvertTo-Json -Depth 5 -Compress }
    elseif (-not $Quiet) { [Console]::Error.WriteLine($result.diagnostic) }
    exit 2
}

$files = if ([string]::IsNullOrWhiteSpace($raw)) {
    @()
} else {
    @($raw -split "\r?\n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}
$untrackedRaw = (& git -C $root -c core.safecrlf=false ls-files --others --exclude-standard -- 2>$null | Out-String).Trim()
$untrackedGitExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
if ($untrackedGitExitCode -ne 0) {
    $result = [ordered]@{
        contract = "sig-staged-snapshot/v1"
        status = "fail"
        exitCode = 2
        unstagedTrackedCount = @($files).Count
        untrackedBuildInputCount = 0
        diagnostic = "não foi possível listar entradas não rastreadas do build"
    }
    if ($Json) { $result | ConvertTo-Json -Depth 5 -Compress }
    elseif (-not $Quiet) { [Console]::Error.WriteLine($result.diagnostic) }
    exit 2
}

$untrackedFiles = if ([string]::IsNullOrWhiteSpace($untrackedRaw)) {
    @()
} else {
    @($untrackedRaw -split "\r?\n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Test-BuildInputPath {
    param([string]$Path)

    $normalized = $Path.Replace("\", "/")
    return $normalized -match '^(app/src/|app/build\.gradle(?:\.kts)?$|build\.gradle(?:\.kts)?$|settings\.gradle(?:\.kts)?$|gradle/|gradle\.properties$|buildSrc/|build-logic/|scripts/|\.githooks/)'
}

$untrackedBuildInputs = @($untrackedFiles | Where-Object { Test-BuildInputPath $_ })
$trackedCount = @($files).Count
$untrackedBuildInputCount = @($untrackedBuildInputs).Count
$status = if ($trackedCount -eq 0 -and $untrackedBuildInputCount -eq 0) { "pass" } else { "fail" }
$exitCode = if ($status -eq "pass") { 0 } else { 2 }
$result = [ordered]@{
    contract = "sig-staged-snapshot/v1"
    status = $status
    exitCode = $exitCode
    unstagedTrackedCount = $trackedCount
    untrackedBuildInputCount = $untrackedBuildInputCount
    comparison = "working-tree-vs-index-for-build-inputs"
}

if ($Json) {
    $result | ConvertTo-Json -Depth 5 -Compress
} elseif (-not $Quiet) {
    if ($status -eq "pass") {
        Write-Output "PASS: índice e árvore de trabalho estão alinhados"
    } else {
        if ($trackedCount -gt 0) {
            [Console]::Error.WriteLine(("commit bloqueado: {0} arquivo(s) tracked fora do índice; faça stage ou descarte a divergência" -f $trackedCount))
        }
        if ($untrackedBuildInputCount -gt 0) {
            [Console]::Error.WriteLine(("commit bloqueado: {0} entrada(s) do Gradle não rastreada(s); faça stage ou remova a entrada" -f $untrackedBuildInputCount))
        }
    }
}

exit $exitCode
