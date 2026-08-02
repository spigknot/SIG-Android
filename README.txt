SIG Android

Aplicativo Android da Delegacia de Taguai para transcricao remota/local,
assistencia de texto, calculadoras e ferramentas FFmpeg.

Requisitos de build:
- JDK 17 configurado no Android Studio ou pela variavel JAVA_HOME.
- Android SDK 35, NDK 27.2.12479018 e CMake 3.22.1.
- Conexao com a internet no primeiro build. O Gradle baixa o Zig 0.16.0,
  valida o SHA-256 oficial e o guarda em .tools (fora do Git).

Configuracao local:
- Defina sdk.dir em local.properties.
- Defina IMEICHECK_API_KEY em local.properties ou como variavel de ambiente.
- local.properties e demais segredos nao sao versionados.

Comandos:
  gradlew.bat :app:testDebugUnitTest
  gradlew.bat :app:lintDebug
  gradlew.bat :app:assembleDebug

APK de desenvolvimento:
  app/build/outputs/apk/debug/app-debug.apk
