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
.\scripts\build-android-native-dependencies.ps1 -Version 2
```

3. Publique os ZIPs, atualize `COMPONENT_VERSION`, URLs, tamanhos e SHA-256
   em `NativeDependencyManager.kt` e gere novamente o APK comum.

O AAR original permanece em `app/libs` apenas como fonte reproduzível das
bibliotecas FFmpeg. Ele não participa do empacotamento do APK comum.
