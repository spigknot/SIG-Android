# SIG Android — guia de rotas críticas (F4 do Better Harness)

Mapa curto de ownership, riscos e verificadores. Não duplica o README; aponta
para os comandos que já existem.

## Hotspot principal: STT (`RemoteSttActivity.kt`)

`app/src/main/java/br/gov/sp/pcsp/launcher/RemoteSttActivity.kt` é o arquivo
MAIOR e mais arriscado do app (milhares de linhas; ferramentas Transcrição e
Ocorrência na mesma Activity via `EXTRA_MODE`). Mudanças aqui exigem a prova
funcional completa antes de aceitar.

| Rota | Onde | Risco | Verificador |
|---|---|---|---|
| Decisão sync/async + polling AssemblyAI | `AssemblyAiAsyncFlow.kt` (seam puro) + `sendAssemblyaiAsyncTranscription` | loop sem término, perda de diagnóstico | `.\gradlew.bat :app:testDebugUnitTest` (AssemblyAiAsyncFlowTest) |
| Parâmetros de idioma por provedor | `SttLanguageSettings.kt` | vazamento de parâmetro entre provedores | testes unitários (SttLanguageSettings/SttDiarizationTest) |
| Diarização por provedor | `SttDiarization.kt` | parâmetro errado por provedor/modo | testes unitários (SttDiarizationTest) |
| Contratos de requisição REST/WS | `SttRequestBuilders.kt` + `send*ApiTranscription` | erro de URL/form/header por provedor | `SttRequestBuildersTest` + gates completos |
| Fluxo REST (arquivos) | `send*ApiTranscription` + `transcription_form_fields` | erro de form/header por provedor | testes de contrato + teste real com chave |
| Fluxo WebSocket (ao vivo) | handlers `handleGrokLiveEvent`/Deepgram/AssemblyAI/Scribe | finalização travada, reconexão | testes de contrato + teste em campo |
| Correlação de diagnóstico live | `LiveDiagnosticContext.kt` + `emitGrokConnectionEvent` | estado sem vínculo com resultado/diagnóstico | `LiveDiagnosticContextTest` + cenário de reconexão |
| Regras de idioma/diarização na UI | `refreshGrokApiControls` / `showLiveLanguageMenu` | checkbox/idioma sumindo por modo | testes unitários + `.\gradlew.bat :app:lintDebug` |

Antes de aceitar QUALQUER mudança no hotspot: `.\gradlew.bat :app:testDebugUnitTest`,
`.\gradlew.bat :app:lintDebug` e `.\gradlew.bat :app:assembleDebug`. Uma mudança
no fluxo assíncrono sem teste novo no `AssemblyAiAsyncFlowTest` não está pronta.

## Aceitação REST e WebSocket

O roteiro executável e os limites de credencial estão em
`docs/validation/STT_ACCEPTANCE.md`. Ele complementa este mapa sem mover o
ownership para outra Activity ou para um Skill.

| Prova | Entrada controlada | Resultado obrigatório | Evidência de falha |
|---|---|---|---|
| REST (arquivos) | Áudio curto aprovado + provedor/chave de teste autorizados | Resposta HTTP esperada e transcrição não vazia, com form/header conforme a rota | `terminal.txt`, `log.txt` e `correlation.txt` redigidos; nunca chave ou áudio |
| WebSocket (ao vivo) | Dispositivo aprovado + microfone autorizado + provedor live | sequência de conexão, parcial/final, finalização e desconexão sem travamento; reconexão deve ser observável quando provocada | estados `RECONNECTING`, `RECONNECTED`, `RECONNECT_FAILED`, `AUDIO_LOST` e diagnóstico redigido |

Antes de qualquer prova real, executar os testes unitários focais disponíveis.
Depois de qualquer mudança no hotspot, executar obrigatoriamente o gate
completo já listado acima. Testes de campo não recebem chaves no repositório,
não usam dados de produção e não promovem APK ou pacote nativo sem aprovação.

## Pacote nativo (release apenas)

`NativeDependencyManager.kt` publica versão/URLs/tamanhos/SHA-256 por ABI. O
build rápido (`assembleDebug`) NÃO compila nativos por desenho.

| Rota | Verificador |
|---|---|
| Gerar os ZIPs por ABI | `.\scripts\build-android-native-dependencies.ps1 -Version <N>` (ver `native-dependencies/README.md`) |
| Porta de aceitação (version/tamanho/SHA-256 vs manifesto) | `.\scripts\verify-native-dependencies.ps1 -Version <N> -OutputDir native-dependencies\build` |

APK e pacote nativo só podem ser promovidos JUNTOS depois de `verify-native-dependencies`
aceitar. Nunca publicar/assinar artefatos sem aprovação explícita do usuário.

## Harness e validação de mudanças

O gate central está em scripts/validate-agent-harness.ps1; a suíte contratual
está em scripts/tests/validate-agent-harness.tests.ps1 e deve permanecer
silenciosa com -Quiet. A suíte testa bootstrap, EvidencePath, snapshot staged,
instalação idempotente dos hooks e execução do pre-commit em repositório
temporário. O workflow .github/workflows/validation.yml executa essa suíte
antes dos gates Android.

Validação local do harness:

    & .\scripts\tests\validate-agent-harness.tests.ps1 -Quiet
