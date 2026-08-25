SIG Android

Aplicativo Android da Delegacia de Taguai para transcricao remota/local,
assistencia de texto, calculadoras e ferramentas FFmpeg.

Requisitos de build:
- JDK 21 para executar o launcher e o daemon Gradle 9.7.0 (conforme
  gradle/wrapper/gradle-wrapper.properties e gradle/gradle-daemon-jvm.properties);
  o app usa Java 17 como toolchain e bytecode (conforme app/build.gradle).
- Android SDK 35, NDK 27.2.12479018 e CMake 3.22.1.
- Conexao com a internet no primeiro build. O Gradle baixa o Zig 0.16.0,
  valida o SHA-256 oficial e o guarda em .tools (fora do Git).

Configuracao local:
- Defina sdk.dir em local.properties.
- Configure a chave do serviço de consulta do IMEI na tela de configurações do app.
- local.properties e demais segredos nao sao versionados.

Validação e gates:
  Consulte docs/agents/validation.md para a matriz única e os comandos
  silenciosos. O clone atual usa o pre-commit versionado; para ativá-lo
  manualmente em outro clone, execute uma vez:
  & .\scripts\install-git-hooks.ps1 -Quiet
  A checagem curta do harness é:
  & .\scripts\validate-agent-harness.ps1 -Quiet
  No Git Bash/MSYS, invoque o PowerShell explicitamente; não execute o .ps1
  diretamente:
  powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/validate-agent-harness.ps1 -Quiet

APK de desenvolvimento:
  app/build/outputs/apk/debug/app-debug.apk
