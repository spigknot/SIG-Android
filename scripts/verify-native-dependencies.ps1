# Porta de aceitação do pacote nativo (F3 do Better Harness).
# Não compila, publica ou assina: compara os ZIPs com o manifesto Kotlin.
param(
    [int]$Version = 0,
    [string]$OutputDir = "native-dependencies\build",
    [switch]$Quiet,
    [switch]$Json
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$errors = @()
$accepted = @()
$manifestVersion = 0
$checkVersion = $Version

function Emit-AndExit {
    param([int]$Code)

    $status = if ($Code -eq 0) { "pass" } else { "fail" }
    $result = [ordered]@{
        contract = "sig-native-dependencies/v1"
        status = $status
        exitCode = $Code
        version = $checkVersion
        accepted = @($accepted)
        errors = @($errors)
    }

    if ($Json) {
        $result | ConvertTo-Json -Depth 6 -Compress
    } elseif ($Code -ne 0) {
        foreach ($errorMessage in $errors) {
            Write-Error $errorMessage
        }
    } elseif (-not $Quiet) {
        Write-Output ("PASS: pacote nativo v{0} corresponde ao manifesto:" -f $checkVersion)
        $accepted | ForEach-Object { Write-Output ("  + " + $_) }
    }

    exit $Code
}

$ktPath = Join-Path $root "app\src\main\java\br\gov\sp\pcsp\launcher\NativeDependencyManager.kt"
if (-not (Test-Path -LiteralPath $ktPath -PathType Leaf)) {
    $errors += "NativeDependencyManager.kt não encontrado"
    Emit-AndExit 2
}

$text = Get-Content -LiteralPath $ktPath -Raw
$versionMatch = [regex]::Match($text, 'COMPONENT_VERSION\s*=\s*"(\d+)"')
if (-not $versionMatch.Success) {
    $errors += "COMPONENT_VERSION não encontrada no NativeDependencyManager.kt"
    Emit-AndExit 2
}

$manifestVersion = [int]$versionMatch.Groups[1].Value
$checkVersion = if ($Version -gt 0) { $Version } else { $manifestVersion }

if ($Version -gt 0 -and $Version -ne $manifestVersion) {
    $errors += "Divergência de versão: solicitado v$Version; manifesto v$manifestVersion"
    Emit-AndExit 2
}

# Blocos: "abi" to PackageSpec("url", "sha256", N_L)
$specPattern = [regex]'"(arm64-v8a|x86_64)"\s+to\s+PackageSpec\(\s*"([^"]+)",\s*"([0-9a-f]{64})",\s*([0-9_]+)L\s*\)'
$matches = @($specPattern.Matches($text))
if ($matches.Count -eq 0) {
    $errors += "nenhum PackageSpec de ABI foi encontrado no manifesto"
    Emit-AndExit 2
}

foreach ($match in $matches) {
    $abi = $match.Groups[1].Value
    $expectedSha = $match.Groups[3].Value
    $expectedSize = [long]($match.Groups[4].Value -replace "_", "")
    $zipPath = Join-Path $OutputDir "sig-android-dependencies-v$checkVersion-$abi.zip"

    if (-not (Test-Path -LiteralPath $zipPath -PathType Leaf)) {
        $errors += "FALTA o artefato ${abi}: ${zipPath}"
        continue
    }

    $actualSize = (Get-Item -LiteralPath $zipPath).Length
    if ($actualSize -ne $expectedSize) {
        $errors += "TAMANHO divergente ($abi): manifesto=$expectedSize artefato=$actualSize"
    }

    $actualSha = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha -ne $expectedSha) {
        $errors += "SHA-256 divergente ($abi): manifesto=$expectedSha artefato=$actualSha"
    }

    if ($actualSize -eq $expectedSize -and $actualSha -eq $expectedSha) {
        $accepted += "$abi OK (v$checkVersion, $actualSize bytes)"
    }
}

if ($errors.Count -gt 0) {
    Emit-AndExit 1
}

Emit-AndExit 0
