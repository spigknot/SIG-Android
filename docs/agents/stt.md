# Ownership do STT

RemoteSttActivity.kt é o hotspot principal do app. Ela reúne as ferramentas de
Transcrição e Ocorrência por EXTRA_MODE; mudanças nesse arquivo exigem a prova
funcional completa.

## Rotas críticas

| Rota | Owner | Risco principal |
|---|---|---|
| Decisão sync/async e polling AssemblyAI | AssemblyAiAsyncFlow.kt e sendAssemblyaiAsyncTranscription | loop sem término e diagnóstico perdido |
| Idioma por provedor | SttLanguageSettings.kt | parâmetro vazando entre provedores |
| Diarização por provedor | SttDiarization.kt | parâmetro incorreto por provedor ou modo |
| Contratos REST/WebSocket | SttRequestBuilders.kt e send*ApiTranscription | URL, form ou header inválido |
| REST de arquivos | send*ApiTranscription e transcription_form_fields | multipart ou autorização incompatível |
| WebSocket ao vivo | handlers handleGrokLiveEvent, Deepgram, AssemblyAI e Scribe | finalização travada ou reconexão incompleta |
| Diagnóstico live | LiveDiagnosticContext.kt e emitGrokConnectionEvent | estado sem vínculo com resultado |
| Controles da UI | refreshGrokApiControls e showLiveLanguageMenu | idioma ou checkbox desaparecendo por modo |

## Prova obrigatória

Use a matriz em docs/agents/validation.md para os testes focais e o gate
completo. Use docs/validation/STT_ACCEPTANCE.md para a aceitação REST/WebSocket.
Uma mudança assíncrona sem teste novo ou ajuste explícito no seam
AssemblyAiAsyncFlowTest não está pronta.

## Limites

Testes de campo usam dispositivo, áudio e chave de teste aprovados. Evidências
compartilhadas são redigidas. A prova não publica nem promove artefatos.
