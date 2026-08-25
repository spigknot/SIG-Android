# Matriz canônica de validação

Este documento é a fonte única dos comandos de validação do SIG Android. Ele é
carregado somente quando a tarefa exigir prova ou gate.

Contrato de ambiente: o wrapper usa Gradle 9.7.0 conforme
`gradle/wrapper/gradle-wrapper.properties`; launcher e daemon usam JDK 21
conforme `gradle/gradle-daemon-jvm.properties`; o app usa Java 17 como
toolchain e bytecode conforme `app/build.gradle`. O CI configura JDK 21 para
que esse contrato não dependa de download implícito.

## Porta curta do harness

Os comandos deste documento são PowerShell. A checagem silenciosa de baixo custo
valida o prefixo global e o diff:

    & .\scripts\validate-agent-harness.ps1 -Quiet

No Git Bash/MSYS, não execute um arquivo `.ps1` diretamente: invoque o host
PowerShell explicitamente:

    powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/validate-agent-harness.ps1 -Quiet

## Execução automática

O hook versionado `.githooks/pre-commit` executa automaticamente o gate completo
(`testDebugUnitTest`, `lintDebug` e `assembleDebug`) e bloqueia o commit quando
o exit code não é zero. Com `-Staged`, antes do Gradle ele compara a árvore de
trabalho com o índice, bloqueia qualquer alteração tracked unstaged e também
bloqueia entradas não rastreadas que possam entrar no build (`app/src`, scripts
e arquivos de configuração Gradle). Arquivos não relacionados ao build podem
continuar não rastreados. Assim, o Gradle não valida entradas de código
divergentes do snapshot que entrará no commit. O envelope JSON informa
`stagedDiffScope`, `stagedConsistencyScope` e a política aplicada. Para ativar
ou reparar a configuração do clone atual:

    & .\scripts\install-git-hooks.ps1 -Quiet

O repositório também repete o mesmo gate no GitHub Actions em todo push para
`main`, pull request e execução manual. A saída de sucesso permanece silenciosa;
falhas deixam somente o diagnóstico limitado do wrapper e a evidência JSON.

Os caminhos de sucesso/falha, redaction, evidência segura, snapshot staged,
instalador e execução real do hook pelo Git podem ser testados sem usar o
repositório de produção:

    & .\scripts\tests\validate-agent-harness.tests.ps1 -Quiet

Para obter a evidência JSON do prefixo, use o verificador diretamente:

    & .\scripts\verify-agent-prefix.ps1 -Quiet -Json

Para persistir somente o resultado redigido do gate em uma pasta ignorada:

    & .\scripts\validate-agent-harness.ps1 -RunAndroidGates -Quiet -Json -EvidencePath build/harness-validation.json

`EvidencePath` precisa estar dentro da raiz e ser aceito por `git check-ignore`;
o wrapper recusa caminhos não ignorados. Diagnósticos são limitados por linhas e
bytes e redigem tokens, headers de autorização, chaves, senhas e tokens Bearer.
`git commit --no-verify` pode contornar hooks locais; por isso o CI continua
sendo a segunda barreira obrigatória.

## Testes focais do STT

Antes de uma prova REST ou WebSocket, executar os seams unitários disponíveis:

    & .\gradlew.bat --quiet :app:testDebugUnitTest --tests "br.gov.sp.pcsp.launcher.AssemblyAiAsyncFlowTest" --tests "br.gov.sp.pcsp.launcher.SttDiarizationTest" --tests "br.gov.sp.pcsp.launcher.SttLanguageSettingsTest" --tests "br.gov.sp.pcsp.launcher.SttRequestBuildersTest" --tests "br.gov.sp.pcsp.launcher.LiveDiagnosticContextTest"

## Gate completo do Android

Qualquer mudança no hotspot STT exige os três gates juntos:

    & .\gradlew.bat --quiet :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain

O wrapper do harness executa a mesma prova de forma silenciosa:

    & .\scripts\validate-agent-harness.ps1 -RunAndroidGates -Quiet

O resultado só é aceito com exit code zero; o modo quiet não precisa imprimir
BUILD SUCCESSFUL. Se uma etapa falhar, interrompa o fluxo antes de commit, push
ou promoção; a evidência local deve registrar a etapa e seu exit code, sem
prompt ou segredo.

## Pacote nativo

Só executar quando a mudança tocar o pacote nativo:

    & .\scripts\build-android-native-dependencies.ps1 -Version <N>

Em automação, use -Quiet -Json para evitar a tabela de pacotes no contexto:

    & .\scripts\build-android-native-dependencies.ps1 -Version <N> -Quiet -Json

Antes de qualquer promoção, conferir versão, tamanho e SHA-256:

    & .\scripts\verify-native-dependencies.ps1 -Version <N> -OutputDir native-dependencies\build

Para automação, acrescente -Quiet -Json; em falha, o JSON preserva o exit code
e a divergência resumida.

## Prova de campo

O gate automatizado não substitui a prova controlada. Para REST/WebSocket, seguir
docs/validation/STT_ACCEPTANCE.md. Não registrar chaves, áudio, payloads ou
dados de produção.
