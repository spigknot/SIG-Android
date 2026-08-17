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
| Fluxo REST (arquivos) | `send*ApiTranscription` + `transcription_form_fields` | erro de form/header por provedor | testes unitários + teste real com chave |
| Fluxo WebSocket (ao vivo) | handlers `handleGrokLiveEvent`/Deepgram/AssemblyAI/Scribe | finalização travada, reconexão | testes unitários + teste em campo |
| Regras de idioma/diarização na UI | `refreshGrokApiControls` / `showLiveLanguageMenu` | checkbox/idioma sumindo por modo | testes unitários + `.\gradlew.bat :app:lintDebug` |

Antes de aceitar QUALQUER mudança no hotspot: `.\gradlew.bat :app:testDebugUnitTest`,
`.\gradlew.bat :app:lintDebug` e `.\gradlew.bat :app:assembleDebug`. Uma mudança
no fluxo assíncrono sem teste novo no `AssemblyAiAsyncFlowTest` não está pronta.

## Pacote nativo (release apenas)

`NativeDependencyManager.kt` publica versão/URLs/tamanhos/SHA-256 por ABI. O
build rápido (`assembleDebug`) NÃO compila nativos por desenho.

| Rota | Verificador |
|---|---|
| Gerar os ZIPs por ABI | `.\scripts\build-android-native-dependencies.ps1 -Version <N>` (ver `native-dependencies/README.md`) |
| Porta de aceitação (version/tamanho/SHA-256 vs manifesto) | `.\scripts\verify-native-dependencies.ps1 -Version <N> -OutputDir native-dependencies\build` |

APK e pacote nativo só podem ser promovidos JUNTOS depois de `verify-native-dependencies`
aceitar. Nunca publicar/assinar artefatos sem aprovação explícita do usuário.
