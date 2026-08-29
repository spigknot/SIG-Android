# build-qairt-r2-package.ps1
# Monta o pacote de libs do Qualcomm AI Runtime (QAIRT/QNN) para distribuição via R2.
# O pacote contém libs runtime para GPU (Adreno) e NPU (Hexagon HTP) v73-v81.
# Zip final: ~53 MB com compressão máxima.
#
# Uso:
#   .\tools\qairt\build-qairt-r2-package.ps1
#
# Requer: QAIRT SDK instalado em D:\Projetos\qairt\2.49.0.260730
#          (com lib/aarch64-android/ e lib/hexagon-v{73,75,79,81}/unsigned/)
param(
    [string]$QairtRoot = "D:\Projetos\qairt\2.49.0.260730",
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$toolsDir = Join-Path $root "tools\qairt"
$output = if ($OutputDir) { $OutputDir } else { Join-Path $toolsDir "package" }

New-Item -ItemType Directory -Force -Path $output, $toolsDir | Out-Null

$libs = Join-Path $QairtRoot "lib\aarch64-android"
$hexagons = Join-Path $QairtRoot "lib"

# --- libs do runtime (nomes legados libQnn* — usados pelo ORT 1.29) ---
$runtimeLibs = @(
    "libQnnSystem.so",      # 4 MB  — system-level
    "libQnnGpu.so",         # 9 MB  — GPU backend (Adreno)
    "libQnnHtp.so",         # 4 MB  — HTP backend runtime (NPU/Hexagon)
    "libQnnHtpPrepare.so"   # 76 MB — on-device graph preparation (HTP)
)

# --- stubs + skels por arquitetura HTP ---
$htpArchs = @("V73", "V75", "V79", "V81")

# --- montagem ---
$staging = Join-Path $env:TEMP ("sig-qairt-" + [Guid]::NewGuid().ToString("N"))
$libTarget = Join-Path $staging "lib"
New-Item -ItemType Directory -Force -Path $libTarget | Out-Null

$fileRecords = @()
$totalSize = 0L

# Runtime libs
foreach ($lib in $runtimeLibs) {
    $src = Join-Path $libs $lib
    if (!(Test-Path $src)) { throw "lib ausente: $src" }
    $target = Join-Path $libTarget $lib
    Copy-Item -LiteralPath $src -Destination $target
    $size = (Get-Item $target).Length
    $totalSize += $size
    $fileRecords += [ordered]@{
        path = "lib/$lib"
        size = $size
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant()
        kind = if ($lib -match "HtpPrepare") { "prepare" } elseif ($lib -match "Gpu") { "gpu" } elseif ($lib -match "Htp") { "htp" } elseif ($lib -match "System") { "system" } else { "other" }
    }
}

# Stubs e skels por arquitetura
foreach ($arch in $htpArchs) {
    $archLower = $arch.ToLowerInvariant()
    $archNum = $arch -replace "V", ""

    # Stub (CPU-side)
    $stubName = "libQnnHtp${arch}Stub.so"
    $stubSrc = Join-Path $libs $stubName
    if (!(Test-Path $stubSrc)) { throw "stub ausente: $stubSrc" }
    $stubTarget = Join-Path $libTarget $stubName
    Copy-Item -LiteralPath $stubSrc -Destination $stubTarget
    $stubSize = (Get-Item $stubTarget).Length
    $totalSize += $stubSize
    $fileRecords += [ordered]@{
        path = "lib/$stubName"
        size = $stubSize
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $stubTarget).Hash.ToLowerInvariant()
        kind = "stub"
        htp_arch = $archNum
    }

    # Skel (DSP-side, pasta unsigned)
    $skelName = "libQnnHtp${arch}Skel.so"
    $skelSrc = Join-Path $hexagons "hexagon-$archLower\unsigned\$skelName"
    if (!(Test-Path $skelSrc)) { throw "skel ausente: $skelSrc" }
    $skelTarget = Join-Path $libTarget $skelName
    Copy-Item -LiteralPath $skelSrc -Destination $skelTarget
    $skelSize = (Get-Item $skelTarget).Length
    $totalSize += $skelSize
    $fileRecords += [ordered]@{
        path = "lib/$skelName"
        size = $skelSize
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $skelTarget).Hash.ToLowerInvariant()
        kind = "skel"
        htp_arch = $archNum
    }
}

# --- manifest.json interno ---
$manifest = [ordered]@{
    format = 1
    version = "1"
    qairt_version = "2.49.0.260730"
    abi = "arm64-v8a"
    htp_archs = @($htpArchs -replace "V", "")
    total_size_uncompressed = $totalSize
    created_at = (Get-Date -Format "yyyy-MM-ddTHH:mm:sszzz")
    files = $fileRecords
}
$manifestPath = Join-Path $staging "manifest.json"
$manifest | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $manifestPath

# --- zip final ---
# ⚠️ NUNCA usar [IO.Compression.ZipFile]::CreateFromDirectory: no Windows ele grava
# os nomes das entradas com '\' (0x5c). No Android/Linux o ZipInputStream entrega
# esse '\' literal, File() não o trata como separador, e a pasta lib/ nunca é
# criada -> "Pacote QAIRT incompleto." O zip precisa de entradas com '/' (0x2f).
$zipName = "sig-qairt-arm64-v8a-v1.zip"
$zipPath = Join-Path $output $zipName
if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$fs = [System.IO.File]::Open($zipPath, [System.IO.FileMode]::Create)
$zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem -LiteralPath $staging -Recurse -File | ForEach-Object {
        # Caminho relativo com separador '/' SEMPRE (não usar [IO.Path]::GetRelativePath
        # nem Replace('\','/') sobre caminho absoluto — monta da raiz do staging).
        $rel = $_.FullName.Substring($staging.Length + 1).Replace('\', '/')
        $entry = $zip.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
        $es = $entry.Open()
        try {
            $in = [System.IO.File]::OpenRead($_.FullName)
            try { $in.CopyTo($es) } finally { $in.Dispose() }
        } finally { $es.Dispose() }
    }
} finally {
    $zip.Dispose()
    $fs.Dispose()
}

$zipSize = (Get-Item $zipPath).Length
$zipSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipPath).Hash.ToLowerInvariant()

# --- report ---
Write-Host ""
Write-Host "==================== PACOTE QAIRT GERADO ===================="
Write-Host "Arquivo: $zipName"
Write-Host "Tamanho: $($zipSize) bytes ($([math]::Round($zipSize/1MB, 1)) MB)"
Write-Host "SHA-256: $zipSha"
Write-Host "Descomprimido: $($totalSize) bytes ($([math]::Round($totalSize/1MB, 1)) MB)"
Write-Host "Arquiteturas HTP: $($htpArchs -join ', ')"
Write-Host "QAIRT SDK: 2.49.0.260730"
Write-Host "============================================================="
Write-Host ""
Write-Host "Destino no R2: packages/qairt/$zipName"
Write-Host ""

# Limpeza
Remove-Item -Recurse -Force $staging

# Salva o SHA para o upload/registro
$infoPath = Join-Path $output "package-info.json"
[ordered]@{
    file = $zipName
    size = $zipSize
    sha256 = $zipSha
    uncompressed_size = $totalSize
    htp_archs = @($htpArchs -replace "V", "")
    qairt_version = "2.49.0.260730"
    created_at = (Get-Date -Format "yyyy-MM-ddTHH:mm:sszzz")
} | ConvertTo-Json -Depth 3 | Set-Content -Encoding UTF8 $infoPath
Write-Host "Info salvo em: $infoPath"