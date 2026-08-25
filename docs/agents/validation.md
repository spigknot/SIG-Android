# Validação do harness e do app

## Suíte contratual do harness

A suíte abaixo é obrigatória antes dos gates Android e deve ser executada em
modo silencioso:

    & .\scripts\tests\validate-agent-harness.tests.ps1 -Quiet

Ela verifica o bootstrap do gate, a segurança do caminho de evidência, o
snapshot staged, a instalação idempotente do hook e a execução real do
pre-commit em um repositório temporário. O teste do instalador nunca deve
alterar a configuração local do clone de desenvolvimento.

## Gates Android

    & .\scripts\validate-agent-harness.ps1 -RunAndroidGates -Quiet -Json -EvidencePath build\harness-validation.json

O arquivo de evidência só é aceito em caminho dentro da raiz e ignorado pelo
Git. Falhas devem preservar exit code não-zero e diagnóstico redigido.

## Atualizador do APK

O AppUpdateChecker possui teste focal em
app/src/test/java/br/gov/sp/pcsp/launcher/AppUpdateCheckerTest.kt.
Use o teste focal para mudanças no contrato HTTP, comparação de versão, asset
sig.apk, download ou retry:

    & .\gradlew.bat --quiet :app:testDebugUnitTest --tests "br.gov.sp.pcsp.launcher.AppUpdateCheckerTest" --console=plain
