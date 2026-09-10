# MODULE-MAP — inventário de arquivos do SIG Android

Bússola do pacote `app/src/main/java/br/gov/sp/pcsp/launcher/`. O projeto é um
**pacote plano** (sem subpastas), então o nome do arquivo é a única pista de onde
mora o quê — este mapa é o índice que o código não tem.

**Como usar:** ache o arquivo pelo nome; a área diz o papel dele e as regras
transversais dizem o que **não** pode entrar. Antes de criar arquivo novo, veja
"Adicionar arquivo novo" no fim.

**Fonte da verdade:** o índice do Git (`git ls-files`). **Verificador:**
`scripts/check-module-map.ps1`, executado pelo gate
(`scripts/validate-agent-harness.ps1`) e pelo pre-commit. Arquivo de produção
fora deste mapa **bloqueia o commit**, e todo fonte listado precisa de um bloco
KDoc (`/** ... */`) acima da primeira declaração de topo — sem ele, o commit
também é bloqueado.

## Regras transversais (valem para todo arquivo)

1. **Segredo nunca no código.** Chave de API é digitada pelo usuário e fica em
   prefs (`ApiKeyStore`, `GrokApiSettings`, `ImeiApiSettings`). Nada de chave em
   fonte, teste, log ou relatório.
2. **Seam puro (área C) não tem Android.** Não importa `Context`, não abre
   Activity, não faz HTTP: recebe tudo por parâmetro e devolve valor. É o que
   permite testar sem aparelho.
3. **Activity (área A) = UI + estado de tela + chamada externa.** Regra testável
   vai para um seam (área C) — ver `AGENTS.md`.
4. **Store (área E) só lê/escreve estado persistido.** Não contém UI nem regra de
   transcrição/FFmpeg.
5. **Sem arquivo genérico.** `Utils.kt`, `Helpers.kt`, `Common.kt` não entram: se
   a responsabilidade não cabe em uma linha, o arquivo está mal desenhado.

## A. Telas (Activities) — UI

| Arquivo | Responsabilidade |
|---|---|
| `MainActivity.kt` | Tela inicial: menu de ferramentas, compartilhar localização, atalhos. |
| `ToolsActivity.kt` | Lista de ferramentas e limpeza de cache temporário. |
| `CalculatorToolsActivity.kt` | Hub das calculadoras (RG, IMEI). |
| `RgCalculatorActivity.kt` | Calculadora de dígitos RG/CPF. |
| `ImeiCalculatorActivity.kt` | Calculadora/consulta de IMEI (Luhn, API, histórico). |
| `ImeiSettingsActivity.kt` | Configuração da consulta de IMEI. |
| `ApiKeysSettingsActivity.kt` | Chaves de API: digitação, máscara, importação. |
| `AdvancedSettingsActivity.kt` | Configurações avançadas: paralelismo e tabela de keywords. |
| `ModelSettingsActivity.kt` | Servidores/modelos de texto e transcrição. |
| `RemoteSttActivity.kt` | **HOTSPOT**: transcrição remota REST + ao vivo (WS) e Ocorrência; UI e orquestração. |
| `GraniteActivity.kt` | Transcrição/geração local com Granite (STT/TTS) + assistente. |
| `WhisperActivity.kt` | Transcrição local com Whisper. |
| `FfmpegActivity.kt` | Hub das ferramentas FFmpeg. |
| `FfmpegCutActivity.kt` | Cortar vídeo. |
| `FfmpegJoinVideosActivity.kt` | Juntar vídeos. |
| `FfmpegExtractAudioActivity.kt` | Extrair áudio. |
| `FfmpegInsertAudioActivity.kt` | Inserir áudio em vídeo. |
| `FfmpegRotateVideoActivity.kt` | Girar vídeo. |
| `FfmpegCleanAudioActivity.kt` | Limpar/melhorar áudio. |

## B. Views customizadas

| Arquivo | Responsabilidade |
|---|---|
| `FfmpegRangeSlider.kt` | Slider de intervalo (início/fim) do corte. |
| `FfmpegWaveformView.kt` | Forma de onda + seleção de trecho. |
| `FfmpegJoinTimelineView.kt` | Timeline da junção (clipes + miniaturas). |
| `FfmpegJoinPlaybackTimelineView.kt` | Timeline de reprodução do preview da junção. |
| `FfmpegInsertAudioTimelineView.kt` | Timeline da inserção de áudio. |
| `AppVersionTextView.kt` | TextView com a versão do app. |
| `SystemBars.kt` | Extensão para system bars / edge-to-edge. |

## C. Regra pura (seams sem UI/Activity)

| Arquivo | Responsabilidade |
|---|---|
| `SttResponseParsers.kt` | Parsing das respostas STT (REST, SSE e WS). |
| `SttRequestBuilders.kt` | Contratos de requisição REST/WS por provedor (URL, header, form). |
| `SttLanguageSettings.kt` | Regras de idioma por provedor STT. |
| `SttDiarization.kt` | Regras de diarização por provedor/modo. |
| `SttKeywords.kt` | Keywords do STT: normalização, limites e parâmetro por provedor. |
| `SttKeywordProfiles.kt` | Perfis de keywords (listas nomeadas) e a seleção ativa do app. |
| `SttKeywordsHelp.kt` | Texto da ajuda das keywords (o que faz e o limite de cada modelo). |
| `AssemblyAiAsyncFlow.kt` | Decisão sync/async + polling da AssemblyAI. |
| `LiveDiagnosticContext.kt` | Correlação de diagnóstico da sessão ao vivo. |
| `TranscriptionReport.kt` | Relatório HTML, log de terminal, nomes e tamanhos. |
| `SttAudioProbe.kt` | Sondagem FFmpeg + interpretação da saída (codec/Hz/canais). |
| `MediaTypeRules.kt` | `isVideo`/`isAudio`/MIME por extensão. |
| `MediaUriSupport.kt` | Nome de arquivo de URI + permissões de pasta. |
| `SharedMediaIntents.kt` | Regras de intent de compartilhamento (vídeo/áudio/URI). |
| `ApiKeysImportParser.kt` | Parser do arquivo de importação de chaves (uma linha por serviço). |
| `SttOutputStorage.kt` | Escolha/criação da pasta de saída. |
| `FfmpegOutputRemuxer.kt` | Remux da saída para o contêiner original (HEVC). |
| `FfmpegProgressText.kt` | Texto de encoder/duração nas etapas FFmpeg. |
| `FfmpegCommandPresenter.kt` | Prévia do comando FFmpeg exibida ao usuário. |
| `FfmpegMediaPolicies.kt` | Políticas de stream-copy/trim/junção por codec. |
| `FfmpegVideoEncoderRegistry.kt` | Registro dos encoders de vídeo disponíveis. |
| `FfmpegVideoQuality.kt` | Modelos de qualidade/bitrate de vídeo e áudio. |
| `SmartJoinPlanner.kt` | Plano da junção inteligente (clipes, alvo, compatibilidade). |
| `RequestModelLabel.kt` | Rótulo curto do modelo usado numa requisição. |
| `ServiceEndpoints.kt` | Endpoints oficiais embutidos no app. |
| `LittleEndianIo.kt` | Leitura/escrita little-endian (WAV/pacotes). |
| `GraniteBinarySupport.kt` | Byte↔codepoint, cadeia de erro, WAV 16k mono, floats. |

## D. Motores locais (inferência)

| Arquivo | Responsabilidade |
|---|---|
| `GraniteEngine.kt` | Motor Granite: frontend ONNX, AGC e execução. |
| `GraniteNarEngine.kt` | Motor Granite NAR: CTC, half-float, frontend. |
| `WhisperNative.kt` | Ponte JNI do Whisper (load/transcribe/cancel). |
| `TranscriptAssistantClient.kt` | Cliente do assistente de texto (histórico, nomes). |

## E. Estado e preferências

| Arquivo | Responsabilidade |
|---|---|
| `ModelServerStore.kt` | Servidores/modelos configurados (texto e transcrição). |
| `TranscriptionModelStore.kt` | Modelos de transcrição selecionados. |
| `PromptTemplateStore.kt` | Templates de prompt (histórico, partes). |
| `GrokApiSettings.kt` | Preferências e chaves das integrações de API. |
| `ApiKeyStore.kt` | Armazenamento cifrado das chaves digitadas. |
| `ImeiApiSettings.kt` | Chave da consulta de IMEI. |
| `PartsExtractionSettings.kt` | Método/modelo/config da extração de partes. |
| `GraniteParallelismSettings.kt` | Paralelismo do Granite. |
| `ConversionParallelismSettings.kt` | Paralelismo das conversões. |
| `NameDatabaseStore.kt` | Banco local de nomes (load/add/remove). |
| `AppCacheManager.kt` | Limpeza de caches por idade e tamanho. |
| `FfmpegTaskTracker.kt` | Estado das tarefas FFmpeg (progresso, encoder). |

## F. Sistema e dependências

| Arquivo | Responsabilidade |
|---|---|
| `SigApplication.kt` | `Application`: bootstrap do app. |
| `CancelExitGuard.kt` | Extensão: confirmar saída durante tarefa. |
| `AppUpdateChecker.kt` | Atualização via GitHub (release, parse, download). |
| `NativeDependencyManager.kt` | Pacote nativo: versão/URL/SHA-256 por ABI e ativação. |
| `QairtDependencyManager.kt` | Dependência QAIRT (Qualcomm NPU): detecção/instalação. |
| `NativeDependencyPrompt.kt` | Diálogo de download do pacote nativo. |

## Adicionar arquivo novo

1. Escolha a área (A–F). Se não couber em nenhuma, discuta o desenho antes.
2. Adicione a linha de tabela com o nome do arquivo e a responsabilidade em uma
   linha (mesmo formato das tabelas acima).
3. Rode `powershell -File scripts\check-module-map.ps1 -Quiet` — sem isso o
   pre-commit bloqueia.

Mapa deliberadamente **grosso** (uma linha por arquivo). Detalhe de comportamento
mora no KDoc do arquivo e nos testes, não aqui.
