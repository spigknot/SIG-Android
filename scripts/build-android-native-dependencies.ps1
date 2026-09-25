param(
    [string]$Version = "1",
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$app = Join-Path $root "app"
$aar = Join-Path $app "libs\ffmpeg-kit-6.1.1-gpl-x264-16kb.aar"
$apiJar = Join-Path $app "libs\ffmpeg-kit-api.jar"
$nativeRoot = Join-Path $app "build\intermediates\merged_native_libs\debug\mergeDebugNativeLibs\out\lib"
$silero = Join-Path $root "native-dependencies\silero\ggml-silero-v6.2.0.bin"
$output = if ($OutputDirectory) { $OutputDirectory } else { Join-Path $root "build\native-dependencies" }

# ONNX Runtime (engine do Granite) — as libs nativas TAMBÉM vão no pacote
# (o APK as exclui via packaging.jniLibs.excludes). O AAR fica no cache do Gradle
# depois do 1º assembleDebug; o nome do arquivo é estável por versão.
# ⚠️ v3+: usamos o AAR onnxruntime-android-qnn (QNN EP embutido no .so).
$onnxVersion = "1.29.0"
$onnxGroup = "onnxruntime-android-qnn"
$onnxAarDir = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\com.microsoft.onnxruntime\$onnxGroup\$onnxVersion"
$onnxAar = Get-ChildItem -Path $onnxAarDir -Recurse -Filter "onnxruntime-android-qnn-$onnxVersion.aar" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
# x86_64 (emulador): o AAR qnn só traz arm64-v8a — usar o AAR oficial do
# onnxruntime-android (mesma versão; foi assim no pacote v3).
$onnxPlainDir = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\com.microsoft.onnxruntime\onnxruntime-android\$onnxVersion"
$onnxPlainAar = Get-ChildItem -Path $onnxPlainDir -Recurse -Filter "onnxruntime-android-$onnxVersion.aar" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName

Add-Type -AssemblyName System.IO.Compression.FileSystem
New-Item -ItemType Directory -Force -Path $output | Out-Null

if (!(Test-Path -LiteralPath $aar)) { throw "AAR do FFmpegKit não encontrado: $aar" }
if (!(Test-Path -LiteralPath $nativeRoot)) { throw "Compile o APK completo antes de gerar os componentes." }
if (!(Test-Path -LiteralPath $silero)) { throw "Modelo Silero não encontrado: $silero" }

$apiTemp = Join-Path $env:TEMP ("sig-ffmpeg-api-" + [Guid]::NewGuid().ToString("N") + ".jar")
$aarZip = [IO.Compression.ZipFile]::OpenRead($aar)
try {
    $classesEntry = $aarZip.GetEntry("classes.jar")
    if ($null -eq $classesEntry) { throw "classes.jar ausente no AAR." }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($classesEntry, $apiTemp, $true)
} finally {
    $aarZip.Dispose()
}
$classesZip = [IO.Compression.ZipFile]::Open($apiTemp, [IO.Compression.ZipArchiveMode]::Update)
try {
    $nativeLoader = $classesZip.GetEntry("com/arthenica/ffmpegkit/NativeLoader.class")
    if ($null -eq $nativeLoader) { throw "NativeLoader.class ausente em classes.jar." }
    $nativeLoader.Delete()
} finally {
    $classesZip.Dispose()
}
Copy-Item -Force -LiteralPath $apiTemp -Destination $apiJar
Remove-Item -Force -LiteralPath $apiTemp

$ffmpegLibraries = @(
    "libc++_shared.so",
    "libavutil.so",
    "libswscale.so",
    "libswresample.so",
    "libavcodec.so",
    "libavformat.so",
    "libavfilter.so",
    "libavdevice.so",
    "libffmpegkit_abidetect.so",
    "libffmpegkit.so"
)
$projectLibraries = @(
    "libomp.so",
    "libsig_whisper.so",
    "libsig_npu_probe.so",
    "libsig_llama.so"
)
$onnxLibraries = @(
    "libonnxruntime.so",
    "libonnxruntime4j_jni.so"
)

$packageRecords = @()

# llama.cpp + JNI (ferramenta Texto) — projeto CMake ISOLADO em
# app\src\main\cpp\llama-jni (o ggml do whisper e o do llama não coexistem no
# mesmo projeto CMake: nomes de target iguais). Compilado aqui por ABI e
# empacotado como libsig_llama.so.
$llamaJniSource = Join-Path $root "app\src\main\cpp\llama-jni"
$androidSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$ndkDir = Join-Path $androidSdk "ndk\27.2.12479018"
$cmakeExe = Join-Path $androidSdk "cmake\3.22.1\bin\cmake.exe"
$ninjaExe = Join-Path $androidSdk "cmake\3.22.1\bin\ninja.exe"
# Zig compila o vulkan-shaders-gen para o HOST (mesmo arranjo do build do
# whisper via Gradle — preparado pelo task prepareZig em .tools/).
$zigExe = Join-Path $root ".tools\zig-0.16.0\zig.exe"
if (!(Test-Path -LiteralPath $llamaJniSource)) { throw "Projeto llama-jni nao encontrado: $llamaJniSource" }
if (!(Test-Path -LiteralPath $cmakeExe)) { throw "CMake do Android SDK nao encontrado: $cmakeExe" }
if (!(Test-Path -LiteralPath $zigExe)) { throw "Zig nao encontrado: $zigExe (rode o Gradle para preparar o .tools/zig-0.16.0)." }

foreach ($abi in @("arm64-v8a", "x86_64")) {
    $source = Join-Path $nativeRoot $abi
    $staging = Join-Path $env:TEMP ("sig-native-" + $Version + "-" + $abi + "-" + [Guid]::NewGuid().ToString("N"))
    $libTarget = Join-Path $staging "lib"
    $modelTarget = Join-Path $staging "models"
    New-Item -ItemType Directory -Force -Path $libTarget, $modelTarget | Out-Null

    # Compila o llama-jni (ferramenta Texto) para esta ABI.
    # Sem backtick de continuação: o parser do PowerShell + CRLF engole a
    # expansão de variável e o CMake recebe o literal (dor real, 24/09).
    $llamaBuild = Join-Path $root "native-dependencies\build\llama\$abi"
    $ndkToolchain = Join-Path $ndkDir "build\cmake\android.toolchain.cmake"
    $llamaArgs = @(
        "-S", $llamaJniSource,
        "-B", $llamaBuild,
        "-G", "Ninja",
        "-DCMAKE_MAKE_PROGRAM=$ninjaExe",
        "-DCMAKE_TOOLCHAIN_FILE=$ndkToolchain",
        "-DANDROID_ABI=$abi",
        "-DANDROID_PLATFORM=android-24",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DSIG_ZIG_EXE=$($zigExe.Replace('\','/'))"
    )
    & $cmakeExe @llamaArgs
    if ($LASTEXITCODE -ne 0) { throw "Configure CMake do llama-jni falhou para $abi." }
    & $cmakeExe --build $llamaBuild -j 8
    if ($LASTEXITCODE -ne 0) { throw "Build do llama-jni falhou para $abi." }

    try {
        $fileRecords = @()
        $aarForLibraries = [IO.Compression.ZipFile]::OpenRead($aar)
        try {
            foreach ($library in $ffmpegLibraries) {
                $entry = $aarForLibraries.GetEntry("jni/$abi/$library")
                if ($null -eq $entry) { throw "$library ausente no AAR para $abi." }
                $target = Join-Path $libTarget $library
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
                $fileRecords += [ordered]@{
                    path = "lib/$library"
                    size = (Get-Item -LiteralPath $target).Length
                    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant()
                }
            }
        } finally {
            $aarForLibraries.Dispose()
        }
        foreach ($library in $projectLibraries) {
            $input = Join-Path $source $library
            if ($library -eq "libsig_llama.so") {
                $input = Join-Path $llamaBuild $library
            }
            if (!(Test-Path -LiteralPath $input)) {
                # Libs no packaging.jniLibs.excludes (ex.: libsig_npu_probe.so) nao
                # chegam ao merged_native_libs; procurar no output do CMake (cxx obj).
                $cxxLib = Get-ChildItem -Path (Join-Path $root "app\build\intermediates\cxx") -Recurse -Filter $library -ErrorAction SilentlyContinue |
                    Where-Object { $_.FullName -match [regex]::Escape($abi) } |
                    Select-Object -First 1
                if ($null -ne $cxxLib) { $input = $cxxLib.FullName }
            }
            if (!(Test-Path -LiteralPath $input)) { throw "$library ausente para $abi." }
            $target = Join-Path $libTarget $library
            Copy-Item -LiteralPath $input -Destination $target
            $fileRecords += [ordered]@{
                path = "lib/$library"
                size = (Get-Item -LiteralPath $target).Length
                sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant()
            }
        }
        # ONNX Runtime: extraído do AAR onnxruntime-android (o APK exclui essas libs
        # via packaging.jniLibs.excludes; aqui entram no pacote R2).
        if ($null -eq $onnxAar -or $onnxAar -eq "") { throw "AAR do ONNX Runtime nao encontrado em $onnxAarDir" }
        $onnxSourceAar = $onnxAar
        if ($abi -ne "arm64-v8a") {
            if ($null -eq $onnxPlainAar -or $onnxPlainAar -eq "") { throw "AAR oficial do ONNX Runtime nao encontrado para $abi." }
            $onnxSourceAar = $onnxPlainAar
        }
        $onnxAarForLibraries = [IO.Compression.ZipFile]::OpenRead($onnxSourceAar)
        try {
            foreach ($library in $onnxLibraries) {
                $entry = $onnxAarForLibraries.GetEntry("jni/$abi/$library")
                if ($null -eq $entry) { throw "$library ausente no AAR do ONNX Runtime para $abi." }
                $target = Join-Path $libTarget $library
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
                $fileRecords += [ordered]@{
                    path = "lib/$library"
                    size = (Get-Item -LiteralPath $target).Length
                    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant()
                }
            }
        } finally {
            $onnxAarForLibraries.Dispose()
        }
        $sileroTarget = Join-Path $modelTarget (Split-Path -Leaf $silero)
        Copy-Item -LiteralPath $silero -Destination $sileroTarget
        $fileRecords += [ordered]@{
            path = "models/$(Split-Path -Leaf $silero)"
            size = (Get-Item -LiteralPath $sileroTarget).Length
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $sileroTarget).Hash.ToLowerInvariant()
        }

        $internalManifest = [ordered]@{
            format = 1
            version = $Version
            abi = $abi
            files = $fileRecords
        }
        $internalManifest | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $staging "manifest.json")

        $zipPath = Join-Path $output "sig-android-dependencies-v$Version-$abi.zip"
        if (Test-Path -LiteralPath $zipPath) { Remove-Item -Force -LiteralPath $zipPath }
        [IO.Compression.ZipFile]::CreateFromDirectory(
            $staging,
            $zipPath,
            [IO.Compression.CompressionLevel]::Fastest,
            $false
        )
        $zip = Get-Item -LiteralPath $zipPath
        $packageRecords += [ordered]@{
            version = $Version
            abi = $abi
            file = $zip.Name
            size = $zip.Length
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipPath).Hash.ToLowerInvariant()
        }
    } finally {
        if (Test-Path -LiteralPath $staging) { Remove-Item -Recurse -Force -LiteralPath $staging }
    }
}

$index = [ordered]@{ format = 1; componentVersion = $Version; packages = $packageRecords }
$index | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $output "packages.json")
$packageRecords | ForEach-Object { [pscustomobject]$_ } | Format-Table abi, file, size, sha256 -AutoSize
