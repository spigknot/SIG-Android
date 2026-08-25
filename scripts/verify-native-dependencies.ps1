# Porta de aceitação do pacote nativo (F3 do Better Harness)
#
# Compara os ZIPs por arquitetura gerados pelo build-android-native-dependencies.ps1
# com o manifesto publicado em app/src/main/java/.../NativeDependencyManager.kt
# (COMPONENT_VERSION + url/sha256/tamanho por ABI). NÃO compila, NÃO publica e
# NÃO assina nada: apenas aceita ou REJEITA a correspondência, separando o
# build rápido comum (assembleDebug sem nativos) da porta de release.
#
# Uso:
#   .\scripts\build-android-native-dependencies.ps1 -Version 1
#   .\scripts\verify-native-dependencies.ps1 -Version 1 -OutputDir native-dependencies\build
#
# Saída: exit 0 com o resumo aceito; exit 1 com cada divergência (versão,
# tamanho ou SHA-256) e a instrução de correção.

param(
    [int]$Version = 0,
    [string]$OutputDir = "native-dependencies\build"
)
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$ktPath = Join-Path $root "app\src\main\java\br\gov\sp\pcsp\launcher\NativeDependencyManager.kt"
if (-not (Test-Path $ktPath)) { Write-Error "NativeDependencyManager.kt não encontrado: $ktPath"; exit 1 }

$text = Get-Content $ktPath -Raw

$versionMatch = [regex]::Match($text, 'COMPONENT_VERSION\s*=\s*"(\d+)"')
if (-not $versionMatch.Success) { Write-Error "COMPONENT_VERSION não encontrada no NativeDependencyManager.kt"; exit 1 }
$manifestVersion = [int]$versionMatch.Groups[1].Value
$checkVersion = if ($Version -gt 0) { $Version } else { $manifestVersion }

if ($Version -gt 0 -and $Version -ne $manifestVersion) {
    Write-Error "Divergência de versão: build solicitou v$Version, mas NativeDependencyManager.kt declara COMPONENT_VERSION=$manifestVersion. Atualize o manifesto ou gere a versão correta."
    exit 1
}

$errors = @()
$accepted = @()

# Blocos: "abi" to PackageSpec("url", "sha256", N_L)
$specPattern = [regex]'"(arm64-v8a|x86_64)"\s+to\s+PackageSpec\(\s*"([^"]+)",\s*"([0-9a-f]{64})",\s*([0-9_]+)L\s*\)'
foreach ($match in $specPattern.Matches($text)) {
    $abi = $match.Groups[1].Value
    $url = $match.Groups[2].Value
    $expectedSha = $match.Groups[3].Value
    $expectedSize = [long]($match.Groups[4].Value -replace "_", "")

    $zipPath = Join-Path $OutputDir "sig-android-dependencies-v$checkVersion-$abi.zip"
    if (-not (Test-Path $zipPath)) {
        $errors += "FALTA o artefato ${abi}: ${zipPath} (rode scripts\build-android-native-dependencies.ps1 -Version ${checkVersion})"
        continue
    }
    $actualSize = (Get-Item $zipPath).Length
    if ($actualSize -ne $expectedSize) {
        $errors += "TAMANHO divergente ($abi): manifest=$expectedSize artefato=$actualSize"
    }
    $actualSha = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha -ne $expectedSha) {
        $errors += "SHA-256 divergente ($abi): manifest=$expectedSha artefato=$actualSha"
    }
    if ($actualSize -eq $expectedSize -and $actualSha -eq $expectedSha) {
        $accepted += "$abi OK (v$checkVersion, $actualSize bytes)"
    }
}

if ($errors.Count -gt 0) {
    Write-Host "REJEITADO: pacote nativo não corresponde ao NativeDependencyManager.kt:"
    $errors | ForEach-Object { Write-Host "  - $_" }
    exit 1
}

Write-Host "ACEITO: pacote nativo v$checkVersion corresponde ao NativeDependencyManager.kt:"
$accepted | ForEach-Object { Write-Host "  + $_" }
exit 0
