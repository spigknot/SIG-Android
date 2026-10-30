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
| `PromptsSettingsActivity.kt` | PROMPTS: abas Histórico/Oitiva, editores System/User independentes, criação por +, importação e atualização R2. |
| `RemoteSttActivity.kt` | **HOTSPOT**: transcrição remota REST + ao vivo (WS) e Ocorrência; UI e orquestração. |
| `GraniteActivity.kt` | Transcrição/geração local com Granite (STT/TTS) + assistente. |
| `WhisperActivity.kt` | Transcrição local com Whisper. |
| `FfmpegActivity.kt` | Hub das ferramentas FFmpeg. |
| `FfmpegCutActivity.kt` | Cortar vídeo. |
| `FfmpegJoinVideosActivity.kt` | Juntar vídeos. |
| `FfmpegExtractAudioActivity.kt` | Extrair áudio. |
| `FfmpegInsertAudioActivity.kt` | Inserir áudio em áudio, seleção de faixas e prévia com os efeitos escolhidos. |
| `FfmpegRotateVideoActivity.kt` | Girar vídeo. |
| `FfmpegCleanAudioActivity.kt` | Limpar/melhorar áudio. |
| `TextoActivity.kt` | Ferramenta Texto: tradução Hy-MT2 no aparelho (modelos, backend, log). |

## B. Views customizadas

| Arquivo | Responsabilidade |
|---|---|
| `FfmpegRangeSlider.kt` | Slider de intervalo (início/fim) do corte. |
| `FfmpegWaveformView.kt` | Forma de onda + seleção de trecho. |
| `FfmpegJoinTimelineView.kt` | Timeline da junção (clipes + miniaturas). |
| `FfmpegJoinPlaybackTimelineView.kt` | Timeline de reprodução do preview da junção. |
| `FfmpegInsertAudioTimelineView.kt` | Timeline da inserção de áudio. |
| `FfmpegPreviewOverlayView.kt` | Seleção de área e gestos (zoom/arrasto) do player FFmpeg. |
| `FfmpegPreviewSeeker.kt` | Buscas precisas agrupadas no MediaPlayer e descarte de callbacks do player. |
| `AppVersionTextView.kt` | TextView com a versão do app. |
| `SystemBars.kt` | Extensão para system bars / edge-to-edge. |

## C. Regra pura (seams sem UI/Activity)

| Arquivo | Responsabilidade |
|---|---|
| `FfmpegPreviewSeekQueue.kt` | Destino mais recente, debounce e serialização das buscas da prévia. |
| `SttResponseParsers.kt` | Parsing das respostas STT (REST, SSE e WS). |
| `GeminiSttProtocol.kt` | Contratos puros Files/Interactions/Live do Gemini Transcribe. |
| `SttRequestBuilders.kt` | Contratos de requisição REST/WS por provedor (URL, header, form). |
| `SttLanguageSettings.kt` | Regras de idioma por provedor STT. |
| `SttDiarization.kt` | Regras de diarização por provedor/modo. |
| `SttKeywords.kt` | Keywords do STT: normalização, limites e parâmetro por provedor. |
| `SttKeywordProfiles.kt` | Perfis de keywords (listas nomeadas) e a seleção ativa do app. |
| `SttKeywordsHelp.kt` | Texto da ajuda das keywords (o que faz e o limite de cada modelo). |
| `AssemblyAiAsyncFlow.kt` | Decisão sync/async + polling da AssemblyAI. |
| `GrokSttRequestPolicy.kt` | Grok STT na Transcrição: pacing compartilhado, cooldown, Retry-After, backoff, métricas e aviso de limite. |
| `GeminiSttRequestPolicy.kt` | Cota REST Gemini: pacing 8/min, teto 100/dia no Pacifico, backoff e estatisticas locais. |
| `LiveDiagnosticContext.kt` | Correlação de diagnóstico da sessão ao vivo. |
| `SttLiveAudioFlow.kt` | Cadência PCM, pausa com silêncio e política de replay por provedor ao vivo. |
| `TranscriptionReport.kt` | Relatório HTML, log de terminal, nomes e tamanhos. |
| `DownloadSizeFormat.kt` | Tamanhos de download: base decimal, plano por arquivo e log do download. |
| `DownloadFileStorage.kt` | Validação de tamanho e ativação de downloads com preservação do arquivo anterior. |
| `TranscriptionRunTimings.kt` | Tempos da rodada: preparação, janela de rede e processamento do servidor. |
| `SttAudioProbe.kt` | Sondagem FFmpeg + interpretação da saída (codec/Hz/canais). |
| `MediaTypeRules.kt` | `isVideo`/`isAudio`/MIME por extensão. |
| `MediaUriSupport.kt` | Nome de arquivo de URI + permissões de pasta. |
| `SharedMediaIntents.kt` | Regras de intent de compartilhamento (vídeo/áudio/URI). |
| `ApiKeysImportParser.kt` | Parser do arquivo de importação de chaves (uma linha por serviço). |
| `PromptStoreCore.kt` | Prompts da Ocorrência: slots, migração, prompt ativo e validação do download do R2. |
| `SttOutputStorage.kt` | Escolha/criação da pasta de saída. |
| `FfmpegOutputRemuxer.kt` | Remux da saída para o contêiner original (HEVC). |
| `FfmpegProgressText.kt` | Texto de encoder/duração nas etapas FFmpeg. |
| `FfmpegCommandPresenter.kt` | Prévia do comando FFmpeg exibida ao usuário. |
| `FfmpegMediaPolicies.kt` | Políticas de stream-copy/trim/junção por codec. |
| `FfmpegVideoEncoderRegistry.kt` | Registro dos encoders de vídeo disponíveis. |
| `FfmpegVideoEncoders.kt` | Catálogo de encoders do aparelho + escolha por tarefa (hardware/CPU). |
| `FfmpegCutModes.kt` | Modos de corte (SmartCut/Reencode/Cópia) e despacho com motivo. |
| `FfmpegPreviewSelection.kt` | Geometria do player: zoom, deslocamento e seleção de área (crop). |
| `FfmpegVideoQuality.kt` | Modelos de qualidade/bitrate de vídeo e áudio. |
| `SmartJoinPlanner.kt` | Plano da junção inteligente, GOP aberto, contagem de quadros e validação temporal. |
| `SmartInsertPlanner.kt` | Plano por amostras, fronteiras de pacotes e filtros da inserção/prévia de áudio. |
| `SmartInsertPipeline.kt` | Cópia parcial PCM/ALAC/FLAC, compatibilização e validação da inserção. |
| `SmartInsertWave.kt` | Montagem WAV/RF64 em limites de amostras por cópia de bytes PCM. |
| `SmartInsertFlac.kt` | Remontagem de headers, CRCs e metadados FLAC sem recodificar subframes copiados. |
| `RequestModelLabel.kt` | Rótulo curto do modelo usado numa requisição. |
| `TextModelStream.kt` | Parsing incremental SSE/NDJSON dos modelos de texto, com prévias e validação de término. |
| `LocalTextModel.kt` | Identificador e nome legível do modelo anunciado em /v1/models. |
| `LocalGraniteModel.kt` | Identificador do modelo Granite NAR anunciado em /health. |
| `ServiceEndpoints.kt` | Endpoints oficiais embutidos no app. |
| `LittleEndianIo.kt` | Leitura/escrita little-endian (WAV/pacotes). |
| `GraniteBinarySupport.kt` | Byte↔codepoint, cadeia de erro, WAV 16k mono, floats. |
| `HyMt2Translator.kt` | Tradução Hy-MT2: prompt oficial e template de chat do modelo. |
| `HyMt2ModelSupport.kt` | Política de backend por modelo do Hy-MT2 (1.25bit sem GPU). |
| `HyMt2BackendPolicy.kt` | Capabilities/política das rotas do menu Hy-MT2: seis entradas, ids estáveis e motivo de indisponibilidade. |
| `HyMt2Native.kt` | Ponte JNI do Hy-MT2 (carregar modelo, traduzir, liberar). |
| `HyMt2RequestIsolation.kt` | Isolamento de pedidos Hy-MT2: reset antes de cada prefill (contrato testável). |
| `WhisperSessionController.kt` | Sessão do Whisper: geração/ownership, release assíncrono e descarte de callbacks obsoletos. |
| `WhisperResultParser.kt` | Resultado da transcrição: status estruturado vs prefixo legado (Ok/Cancelado/Erro/Sem fala). |
| `LiveEventIdentity.kt` | Identidade de eventos ao vivo (run_id/act_id/job_id/seq/mono) gerada no PRODUTOR. |
| `LiveJobSupport.kt` | Portão só-zero-digital, resultados tipados/contadores do job ao vivo (R7/E2: `LiveSegmentTime` = tempos reais de segmento do JNI no `Success`) e retrato terminal. |
| `LivePcmPipeline.kt` | Pipeline leitor→spool→worker do caminho ao vivo (spool, contabilidade de amostras); R8/C: com planejador, o prefixo de contexto de cada chunk é ÁUDIO REAL imediatamente anterior no fluxo aceito (anel próprio), inclusive lacunas puladas — nunca tail distante colado como adjacente. |
| `LiveVadSegmenter.kt` | Estratégia de janela do caminho ao vivo (`LiveStrategy`) e VAD por energia (frame 20 ms, histerese, corte de silêncio) + `FixedWindowPlanner`; R8/C: pico com memória LIMITADA (2 s) — fala baixa após pico não é suprimida — e preservação do incerto: só zero-digital é pulado; trecho não nulo vira pré-roll absorvido ou janela `uncertain` agrupada (decode), nunca silêncio. |
| `LiveTextCommitter.kt` | Commit de texto do job ao vivo: provisório × comprometido por janela; R7: thread-safe (leitura da UI × mutação do worker no mesmo lock); R7/E2: reconciliação por INTERVALO de áudio (tempos reais de palavra/segmento quando válidos; sem alinhamento → hipóteses RETIDAS + dreno terminal tipado; fence de geração). |
| `LiveTextPublication.kt` | R8/D (integridade): política PURA de promoção do texto do committer ATÉ a UI (commit/repair/terminal/cancel-drop) — sem ela o texto resolvido/reparado/drenado ficava só nos contadores. |
| `LiveTranscriptionCoordinator.kt` | Coordenador do caminho ao vivo (reader/stop/dreno) sobre o LivePcmPipeline. |
| `FlashAttentionNotice.kt` | Mensagem do que foi de fato aplicado/recusado em Flash Attention (seam puro). |
| `MainTextCoalescer.kt` | R9/B2: publicador coalescido de texto na main (sem descartar linha) para tirar o layout O(n²) da thread de UI. |
| `ModelImportSupport.kt` | R12/F1: importação SAF idempotente, com hash do conteúdo para cache/skip, temporário exclusivo, publicação atômica do modelo/sidecar e orçamento de espaço. |
| `ModelLoadCache.kt` | R3/B2: cache do modelo carregado com dono/sessão explícito (fast-path só com chave e posse conferidas). |
| `OpenClProfileNotice.kt` | R11/E: espelho puro do critério de recusa do perfil OpenCL (large/turbo em Adreno A7X) e parser da linha `SIG_OCL_GUARD`. |
| `GravacaoResultadoFinal.kt` | Publicação idempotente do resultado final da gravação e pareamento do ACK com o terminal aceito pelo ledger. |
| `WhisperBackendDisponibilidade.kt` | Política pura de disponibilidade CPU/Vulkan e pausa temporária do OpenCL, preservando IDs nativos. |
| `WhisperJobLifecycle.kt` | R11/D4: contrato puro de vida do job (tipo de FGS por rota, posse por geração, teto do wake lock). |
| `WhisperJobTerminalSink.kt` | R56/B01-B07: sink terminal por JOB com identidade imutável (geração capturada na admissão), `concluir()` idempotente por instância, emissor injetável e falha de encaminhamento observável — o ACK não depende de Activity viva nem de Runnable da main. |
| `LoggerTeardownCoordinator.kt` | R56/F01-F02: coordena o dreno do logger no teardown — espera os produtores, conserva o writer enquanto houver produtor em voo, declara PERDA em vez de sucesso quando o teto vence e só então fecha o writer (uma vez), com pendência observável via `isAlive`. |
| `TranscriptionJobResult.kt` | R12/A2: resultado por job (job_id/owner/input_id/attempt_id, terminal, origem decode/parcial/none), ledger que recusa callback atrasado e export que NUNCA herda texto de outro job — sem decode o export é vazio/sem resultado e inelegível a WER. |
| `WhisperJobService.kt` | R11/D4: foreground service tipado (dataSync/microphone) + wake lock com teto e terminal por job — dono do lifecycle do job. |
| `CancelStageGate.kt` | R16/D2: porta pura do estágio de cancelamento lido do terminal (`progress N%`/`calling_full`) com limiar de meio/fim; separa "estágio observado" de "clique disparado". |
| `CaptureLog.kt` | R16/C1: leitor estrito do log de captura append-only do committer — `PASS`/`INCOMPLETE`/`FAIL` com o primeiro problema (truncada, buraco, ordem, campo, fase de reparo). |
| `PcmBalance.kt` | R16/C1: balanço de PCM da captura (lido == único + reenvio); overlap de contexto nunca conta como áudio novo. |
| `SessionBudget.kt` | R17/A2: orçamento ÚNICO da observação de ensaio — watcher do Drive e espera externa leem o MESMO valor, com origem (`extra`/`default`/`clamp`) registrada no JSONL. |
| `JobProtection.kt` | R17/B1: política pura de proteção do worker — só término provado (ack) libera; quarentena esgotada sem ack e canal ausente viram CONTENÇÃO (lock/owner mantidos, admissão bloqueada). |
| `WorkerReservation.kt` | R19/C1: RESERVA do worker antes do primeiro passo bloqueante — `reserve` idempotente por geração, `adopt` do serviço para a MESMA geração, `release` só pelo dono; recusa de admissão a outra geração e ACK atrasado não derruba o dono corrente. Store thread-safe compartilhado entre Activity e Service. |
| `AudioCtxSeam.kt` | R18/E1: seam de `audio_ctx` do job — publica o valor vinculado ao dono/tentativa e recusa dono ausente (chamada legada vira no-op), sem canal global entre jobs. |
| `AttemptRequest.kt` | R25/REQ: fotografia da tentativa (token/owner/gen/job/attempt, contexto, janela, submit/serial/K, captura, produtor) com `toWire`/`validate` — única fonte enviada NA chamada `transcribeOwnedReq`; `null`/ausente é DEFAULT explícito. |
| `CommitCapture.kt` | R16/C1: captura do committer para replay — abre/fecha por job, grava PCM e diário de commits com `capture_id`, e fecha no terminal tipado. |
| `JobCancelBridge.kt` | R18/B: ponte de cancelamento entre UI e worker — encaminha o pedido ao dono/generação correto e devolve ACK com o mesmo token. |
| `LiveStopRequest.kt` | R17/B2: pedido de parada do live — estado tipado (pedido, aceito, terminal) para não confundir “stop pedido” com “job terminou”. |
| `LiveTelemetry.kt` | R16/C2: telemetria do pipeline live — contadores e marcos por estágio (janela, VAD, commit) sem tocar a regra de texto. |
| `LiveTerminalPolicy.kt` | R17/B2: política do terminal live — classifica o desfecho (SUCCESS/ERROR/cancelado) e decide a publicação do parcial antes do erro. |
| `ModelIdentityGate.kt` | R18/A2: gate de identidade do modelo — exige SHA-256/bytes/arquitetura do arquivo realmente aberto antes do compute; divergência recusa a célula. |
| `SubmissionFenceProfile.kt` | R17/D2: perfil de submissão Vulkan — quantum real por grafo (`submits`, nós despachados) e limites observados, sem decidir política. |

| `sig_nodes_select.h` | R24/CAP: seleção explícita de intervalo `[begin,end)` para captura por nó (end exclusivo, step relativo ao begin, teto em capturas, parâmetro inválido recusado) — substitui a semântica de prefixo que nunca teve offset. |
| `sig_attempt_opts.h` | R24/REQ: fotografia imutável por tentativa (owner/attempt → janela, submit_mb, k, serial) com publicação única, conflito recusado e `take` de uso único — substitui o `g_audio_ctx_window_samples` global. |
| `sig_attempt_request.h` | R25/REQ: parser/validador puro do pedido da tentativa (`v=1;k=v;…`), com INVALID vs UNSUPPORTED, chave desconhecida/repetida recusada e identidade de 64 bits — validado antes de qualquer compute. |
| `sig_vk_ledger.h` | R25/GPU-K1: ledger por unidade de submissão (fila, fence, geração, estado) com admissão K antes do `queue.submit`, conclusão só em SUCESSO e fence reciclada marcada STALE — em `whisper.cpp/ggml/src/ggml-vulkan/`. |

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
| `TranscriptionModelStore.kt` | Modelos de transcrição selecionados e cache em memória do modelo Granite NAR anunciado. |
| `PromptTemplateStore.kt` | Prompts em uso da ferramenta Ocorrência + prompts legados de partes/qualificação. |
| `GrokApiSettings.kt` | Preferências e chaves das integrações de API. |
| `GeminiSttQuotaStore.kt` | Persistencia privada da cota Gemini por hash da chave, sem segredos. |
| `ApiKeyStore.kt` | Armazenamento cifrado das chaves digitadas. |
| `ImeiApiSettings.kt` | Chave da consulta de IMEI. |
| `PartsExtractionSettings.kt` | Método/modelo/config da extração de partes. |
| `GraniteParallelismSettings.kt` | Paralelismo do Granite. |
| `GraniteNarLlmSettings.kt` | Variante do LLM do NAR escolhida pelo usuário (prefs). |
| `GraniteNarManifest.kt` | Integridade do pacote NAR: parse do manifesto e verificação SHA-256. |
| `ConversionParallelismSettings.kt` | Paralelismo das conversões. |
| `NameDatabaseStore.kt` | Banco local de nomes (load/add/remove). |
| `AppCacheManager.kt` | Limpeza de caches por idade e tamanho. |
| `FfmpegRecoveryStore.kt` | Registro persistente de entradas e etapas concluídas das ferramentas FFmpeg. |
| `FfmpegTaskTracker.kt` | Estado das tarefas FFmpeg (progresso, encoder). |

## F. Sistema e dependências

| Arquivo | Responsabilidade |
|---|---|
| `SigApplication.kt` | `Application`: bootstrap do app. |
| `GrokSttHttpClient.kt` | Transporte Grok STT do lote: pacing nos headers e bloqueio de retentativas HTTP implícitas. |
| `GeminiSttHttpClient.kt` | Transporte Gemini: pacing nos headers da inferencia e tentativas explicitas. |
| `FfmpegPreviewSource.kt` | Abertura e liberação serializadas das fontes do MediaPlayer fora da UI. |
| `FfmpegRecoveryUi.kt` | Oferta de retomada, opções da tela e integração dos checkpoints com FFmpegKit. |
| `CancelExitGuard.kt` | Extensão: confirmar saída durante tarefa. |
| `AppUpdateChecker.kt` | Atualização via GitHub (release, parse, download). |
| `NativeDependencyManager.kt` | Pacote nativo: versão/URL/SHA-256 por ABI e ativação. |
| `QairtDependencyManager.kt` | Dependência QAIRT (Qualcomm NPU): detecção/instalação. |
| `NativeDependencyPrompt.kt` | Diálogo de download do pacote nativo. |
| `DownloadPlanDialog.kt` | Diálogo de download com lista rolável e log que acumula (todas as telas). |

## Adicionar arquivo novo

1. Escolha a área (A–F). Se não couber em nenhuma, discuta o desenho antes.
2. Adicione a linha de tabela com o nome do arquivo e a responsabilidade em uma
   linha (mesmo formato das tabelas acima).
3. Rode `powershell -File scripts\check-module-map.ps1 -Quiet` — sem isso o
   pre-commit bloqueia.

Mapa deliberadamente **grosso** (uma linha por arquivo). Detalhe de comportamento
mora no KDoc do arquivo e nos testes, não aqui.
