# Componentes nativos do SIG Android

O APK comum contém apenas a API Java do FFmpegKit. FFmpeg, Whisper, NPU e o
modelo Silero são distribuídos em um pacote versionado por arquitetura.

## Build comum

```powershell
.\gradlew.bat assembleDebug
```

Esse caminho não executa CMake e não inclui bibliotecas nativas no APK.

## Atualizar os componentes

1. Compile as bibliotecas do projeto:

```powershell
.\gradlew.bat assembleDebug -PbuildNativeComponents=true
```

2. Gere o JAR de API do FFmpegKit e os ZIPs por arquitetura:

```powershell
.\scripts\build-android-native-dependencies.ps1 -Version 1
```

3. Publique os ZIPs, atualize `COMPONENT_VERSION`, URLs, tamanhos e SHA-256
   em `NativeDependencyManager.kt` e gere novamente o APK comum.

O AAR original permanece em `app/libs` apenas como fonte reproduzível das
bibliotecas FFmpeg. Ele não participa do empacotamento do APK comum.

## Estado de strip do pacote (contrato)

O pacote nativo versionado contém libs com estados de strip distintos —
não assuma "todas stripadas":

- `libsig_llama.so`: **stripada** (`llvm-strip --strip-unneeded`, NDK) —
  sem `.debug_*`/`.symtab`.
- `libsig_whisper.so`: **NÃO stripada** (build cxx Debug do Gradle) —
  contém `.debug_info`/`.debug_str`/`.symtab` (~26 MB de debug info por
  ABI). O **código (text) é ~idêntico** ao release v9 (delta de text
  ≈ -1 KB); o delta de arquivo é quase todo símbolo de debug, não
  funcionalidade.
- `libav*` (FFmpeg): vêm do AAR `ffmpeg-kit-6.1.1-gpl-x264-16kb.aar`
  (idênticos ao v9).
- `libonnxruntime.so`, `libsig_npu_probe.so`, `libomp.so`: idênticos
  ao v9 (ou stripados).

**Contrato imutável**: o ZIP publicado (por versão+ABI) é imutável no
R2 — nunca re-stripar in-place. Corrija o estado de strip na **próxima
versão** (`COMPONENT_VERSION` + novo build + novo ZIP + gates), não no
pacote já publicado.

**Escopo/OpenCL**: o HEAD inclui o backend OpenCL (baseline + cache +
instrumentação do executor concorrente), mas o **artefato distribuído**
(lib/APK) não carrega instrumentação OpenCL nem depende de `libOpenCL.so`
via `NEEDED` (o loader usa `dlopen`/`dlsym` em runtime). O build do
produto vem do worktree isolado (`D:/svr11`, allowlist aplicada), não
de um rebuild direto do HEAD.
