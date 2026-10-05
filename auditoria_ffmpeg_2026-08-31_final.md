# AUDITORIA FINAL — FINDINGS FFmpeg SIG ANDROID

**Data:** 2026-08-31  
**HEAD auditado:** `52f6253` ("fix: validar compatibilidade e remover legado ffmpeg")  
**Base de findings:** `85b889f` → relatório `findings_consolidados_ffmpeg_2026-08-31.txt`  
**Método:** leitura integral do código atual em HEAD, `rg` (ripgrep), `git diff 85b889f..52f6253`, e execução da suíte unitária `FfmpegMediaPoliciesTest` (16 testes, 0 falhas). Nenhum arquivo foi alterado durante a auditoria.

> **Nota sobre o repositório:** o projeto não é um repositório git funcional via shell convencional em `D:\Projetos\SIG` (o `.git` existe, mas `git` via MSYS não resolve — usado `git -C D:/Projetos/SIG diff`). O HEAD confirma `52f6253`.

---

## SUMÁRIO EXECUTIVO

| Categoria | Count | Findings |
|-----------|-------|----------|
| **Corrigidos** | 18 | 1, 2, 3, 4, 6, 7, 8, 9, 10, 13, 15, 16, 17, 21, 22, 23, 25, 29 |
| **Parcialmente corrigidos** | 10 | 5, 11, 12, 14, 18, 19, 24, 26, 27, 28 |
| **Melhoria opcional** | 1 | 20 |
| **Abertos** | 0 | — |
| **Novos achados** | 3 | NOVO-1, NOVO-2, NOVO-3 |

**0 findings produzem comandos FFmpeg incorretos na configuração padrão.**  
**0 findings deixam o app em estado de crash ou comportamento truncado.**

Os resíduos restantes afetam: (a) oportunidade de cópia sem reencodar em casos de corte (findings 5, 12), (b) defesa em profundidade (falta do `?` em mapas de áudio — findings 19, 24, 26), (c) exposição de qualidade/CBR-VBR (findings 5c, 18c, 20), (d) consistência de comunicação (findings 11, 14, NOVO-3), e (e) cobertura de testes dos grafos de filtro (finding 28).

---

## DETALHAMENTO POR FERRAMENTA

### FERRAMENTA DE GIRAR VÍDEO — `FfmpegRotateVideoActivity.kt` (1966 linhas)

---

#### Finding 1 — CORRIGIDO

**Finding original:** modo somente-metadados herdava a extensão via `safeContainerExtension` sem validar codecs/streams contra o container; falha só no fim do processo, sem aviso.

**Evidência no código (HEAD 52f6253):**
- `FfmpegMediaPolicies.kt:294-297` (`safeContainerExtension`) — mantém o comportamento de herdar a extensão, caindo em `mkv` para containers não reconhecidos.
- `FfmpegMediaPolicies.kt:48-60` (`metadataCopyPreflightArguments`) — **novo**: pré-flight de 1ms com `-map 0 -c copy -t 0.001` sobre o container de saída.
- `FfmpegRotateVideoActivity.kt:462-464` — Toast "Container não reconhecido: a cópia será salva em MKV." quando `safeContainerExtension` difere da extensão original.
- `FfmpegRotateVideoActivity.kt:574-607` (`executeRotation`) — roda o pré-flight antes da cópia definitiva; se falhar, aborta com "Compatibilidade recusada antes da cópia".
- `FfmpegRotateVideoActivity.kt:1890-1896` (`buildOutputName`) — `safeContainerExtension` para metadados, `mkv` para rotação física.

**Teste:** `FfmpegMediaPoliciesTest.metadataModeAlwaysUsesCopyCommandRegardlessOfPreviousUiState` (linhas 40-58) cobre `metadataRotationCopyArguments` e `metadataCopyPreflightArguments`.

**Impacto residual:** nenhum material. O pré-flight de 1ms cobre header de muxer e incompatibilidade de codec; não cobre erros que só aparecem na escrita do trailer (caso extremo, custo baixo).

**Fix restante (opcional):** aumentar `-t` do pré-flight para ~0,5s para cobrir mais casos de trailer.

**Gravidade:** NENHUMA (residual baixo)

---

#### Finding 2 — CORRIGIDO

**Finding original:** apenas a primeira faixa de vídeo preservada (`-map 0:v:0`); arquivo com 2 vídeos perdia o segundo.

**Evidência no código:**
- `FfmpegRotateVideoActivity.kt:450-458` — `rotateSelectedVideo()` conta faixas de vídeo via `videoTrackCount(uri)`; se `!= 1`, aborta com mensagem clara ("O arquivo não possui uma faixa de vídeo." / "O arquivo possui N faixas de vídeo. Esta ferramenta aceita exatamente uma para não descartar conteúdo.").
- `FfmpegRotateVideoActivity.kt:499-511` (`videoTrackCount`) — MediaExtractor conta faixas com `mime.startsWith("video/")`.
- Único ponto de entrada: `buttonRotate` → `rotateSelectedVideo()` (linha 231).
- Mapas permanecem `0:v:0` (linhas 1129, 1332) — garantidos válidos pelo bloqueio.

**Impacto residual:** nenhum. Adotada a segunda opção do fix (contar e bloquear).

**Fix restante:** nenhum.

**Gravidade:** NENHUMA

---

#### Finding 3 — CORRIGIDO

**Finding original:** paralelismo desligado em silêncio no modo "girar pelos metadados"; faltava o quarto aviso.

**Evidência no código:**
- `FfmpegRotateVideoActivity.kt:1462-1466` (`updateMetadataModeState`) — `parallelKeyframes.isEnabled = !metadataOnly` com `alpha = 0.42f`, exatamente o padrão pedido.
- `FfmpegRotateVideoActivity.kt:1467-1469` — `parallelSegmentsContainer` e `inputParallelSegments` também desabilitados.
- `FfmpegRotateVideoActivity.kt:312` — listener de `parallelKeyframes` chama `updateMetadataModeState()`.
- Avisos dos outros 2 cenários: linhas 525-530 ("corte requer sequencial") e 531-536 ("sem transformação física") — mantidos.
- `FfmpegRotateVideoActivity.kt:521` — `canUseParallel` exclui `metadataOnly` (não precisa de aviso adicional, a UI já reflete o desvio).

**Impacto residual:** nenhum.

**Gravidade:** NENHUMA

---

#### Finding 4 — CORRIGIDO

**Finding original:** intervalo de corte preservado/restaurado ao alternar para modo metadados, mas sem aviso de que será ignorado temporariamente.

**Evidência no código:**
- `FfmpegRotateVideoActivity.kt:289-296` — listener de `metadataRotation`: quando `checked` e existe corte ativo, exibe Toast "O intervalo de corte será preservado, mas não será aplicado no modo por metadados."
- `FfmpegRotateVideoActivity.kt:1437-1458` (`updateMetadataModeState`) — preservação/restauração via `savedTrimStartMs`/`savedTrimEndMs`.
- Campos declarados em linhas 123-124.
- Reset visual da timeline: linhas 1473-1477.

**Impacto residual:** nenhum.

**Gravidade:** NENHUMA

---

### FERRAMENTA DE CORTAR ÁUDIO/VÍDEO — `FfmpegCutActivity.kt` (1827 linhas)

---

#### Finding 5 — PARCIALMENTE CORRIGIDO

**Finding original:** cortar áudio sempre reencodava; PCM fixo em `pcm_s16le` (derrubava 24/32-bit), MP3 em CBR; sem exposição de qualidade.

**Evidência no código:**
- **(b) RESOLVIDO** — `FfmpegCutActivity.kt:1010-1029` (`detectPcmEncoder`) lê `KEY_PCM_ENCODING` → `pcm_u8`/`pcm_f32le`/`pcm_s24le`/`pcm_s32le`/`pcm_s16le`. Passado a `preciseAudioEncoderArguments` (linhas 1002-1008) → `FfmpegMediaPolicies.cutAudioEncoderArguments` (:65-74).
- **(a) PARCIAL** — existe `-c:a copy`, mas só sem corte real: `buildPreciseFfmpegArguments` linhas 668-675:
  ```kotlin
  val hasRealTrim = startMs > 0L || (inputDurationMs > 0L && endMs < inputDurationMs - 10L)
  val encoderArguments = if (!hasRealTrim) {
      listOf("-c:a", "copy")       // ← cópia apenas quando NÃO há corte real
  } else {
      preciseAudioEncoderArguments(...)  // ← reencodifica com corte real
  }
  ```
  Com corte real, continua reencodando (não há cópia com alinhamento a amostra em WAV/FLAC).
- **(c) NÃO FEITO** — layout `activity_ffmpeg_cut.xml` expõe apenas `button_video_encoder` e `button_video_quality`; nenhum controle de bitrate/CBR/VBR para áudio.

**Argumentos FFmpeg efetivos (sem corte real, áudio WAV 24-bit):**
```
ffmpeg -y -ss 0.000 -i IN -t 12.500 -map 0:a? -map_metadata 0 -map_chapters 0 -vn -c:a copy -avoid_negative_ts make_zero OUT.wav
```

**Argumentos FFmpeg efetivos (com corte real, áudio WAV 32-bit):**
```
ffmpeg -y -ss 3.000 -i IN -t 5.000 -map 0:a? -map_metadata 0 -map_chapters 0 -vn -c:a pcm_s32le -avoid_negative_ts make_zero OUT.wav
```

**Impacto residual:** cópia segura quando não há corte; perde-se apenas a oportunidade de `-c:a copy` com corte em PCM/FLAC de taxa constante. Nenhum comando incorreto.

**Fix restante:**
1. Permitir `-c:a copy` com corte quando a origem for PCM/FLAC (cortar no limite de amostra).
2. Expor seletor de CBR/VBR e qualidade de áudio na UI do Cortar.

**Gravidade:** BAIXA (qualidade/exposição; nenhum comando incorreto)

---

#### Finding 6 — CORRIGIDO

**Finding original:** corpo do corte híbrido truncava µs para ms e usava `-ss` depois de `-i`.

**Evidência no código:**
- `FfmpegMediaPolicies.kt:187-199` (`hybridCopyBodyArguments`) — **corrigido**: usa `String.format("%.6f", safeStart / 1_000_000.0)` (6 casas decimais) e `-ss` **antes** de `-i`:
  ```kotlin
  "-y", "-ss", start, "-noautorotate", "-i", inputPath,
  "-t", duration, ...
  ```
  O `startUs` e `endUs` vêm em microssegundos de `extractKeyframesSync` (linhas 1075-1106, `sampleTime` cru).
- `FfmpegCutActivity.kt:730-731` — `startUs = startMs * 1000L`, `endUs = endMs * 1000L` (mantém µs).
- `FfmpegCutActivity.kt:900-907` (`buildHybridBodyArguments`) delega a `FfmpegMediaPolicies.hybridCopyBodyArguments`.
- `FfmpegCutActivity.kt:884-892` (`buildHybridEdgeArguments`) — `-ss` **antes** de `-i` (linha 887).

**Teste:** `FfmpegMediaPoliciesTest.hybridBodyKeepsMicrosecondPrecisionAndSeeksBeforeInput` (linhas 177-184) afirma `args[2] == "8.333333"` e `indexOf("-ss") < indexOf("-i")`.

**Argumentos FFmpeg efetivos (corpo híbrido):**
```
ffmpeg -y -ss 8.333333 -noautorotate -i IN -t 1.666666 -map 0:v:0? -map 0:a? -map 0:s? -map 0:d? -map_metadata 0 -map_chapters 0 -c copy -avoid_negative_ts make_zero -f matroska OUT
```

**Impacto residual:** nenhum funcional. Com `-accurate_seek` (padrão), o input seek descarta pacotes com PTS < alvo; como o alvo é exatamente um keyframe, o primeiro pacote copiado é o próprio keyframe — sem duplicação.

**Fix restante:** nenhum obrigatório.

**Gravidade:** NENHUMA

---

#### Finding 7 — CORRIGIDO

**Finding original:** caminho rápido (híbrido) caía em reencode completo sem aviso prévio nem motivo.

**Evidência no código:**
- **Aviso ANTES de iniciar:** `FfmpegCutActivity.kt:516-543` — diálogo quando `sourceCodec !in setOf("h264","hevc")` ("Este codec exige recodificação completa") e quando `jobEncoder.codecFamily != sourceCodec` (botão neutro "Usar encoder compatível").
- **Motivo DURANTE execução:** `FfmpegCutActivity.kt:720-725` — `tracker.appendTasks(listOf("Caminho rápido indisponível: $reason"))` para:
  - codec fora de h264/hevc (linha 721): "codec X não permite cópia híbrida"
  - encoder divergente (linha 722): "encoder escolhido não corresponde ao codec da origem"
  - ausência de keyframes (linha 736): "não há keyframes internos suficientes"

**Cobertura total dos 3 cenários do finding:** ✅ codec fora de h264/hevc, ✅ encoder divergente, ✅ ausência de keyframes — todos avisados antes e/ou durante.

**Gravidade:** NENHUMA

---

#### Finding 8 — CORRIGIDO

**Finding original:** `detectStreamBitrates` fazia parse do log do FFmpeg com `contains("Video:")` e usava bitrate do container quando o parse falhava.

**Evidência no código:**
- `FfmpegCutActivity.kt:1035-1056` — **reescrito** para usar `MediaExtractor`:
  ```kotlin
  val bitrate = runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }
      ?.takeIf { it > 0 }?.let { "${(it / 1000).coerceAtLeast(1)}k" }
  if (mime.startsWith("video/") && video == null) video = bitrate
  if (mime.startsWith("audio/") && audio == null) audio = bitrate
  ```
  Lê `KEY_BIT_RATE` **por faixa**, não do log. Falha → `StreamBitrates()` (nulo) → call sites usam `FALLBACK_VIDEO_BITRATE = "15M"` / `FALLBACK_AUDIO_BITRATE = "192k"`.

**Call sites:** linha 668 (áudio), 694/700 (vídeo preciso), 741 (híbrido).

**Argumentos FFmpeg efetivos:** `-b:v <bitrate da faixa ou 15M>`, `-b:a <bitrate da faixa ou 192k>`.

**Impacto residual:** `KEY_BIT_RATE` é opcional no MediaFormat e volta ausente em MKV/WebM/FLAC/PCM — nesses casos cai no fallback fixo (comportamento explícito e aceito).

**Fix restante:** nenhum obrigatório.

**Gravidade:** NENHUMA

---

#### Finding 9 — CORRIGIDO

**Finding original:** `-map 0:v:0?` (só primeira faixa de vídeo) no Cortar.

**Evidência no código:**
- `FfmpegCutActivity.kt:491-498` — `cutSelectedMedia()` conta faixas de vídeo via `videoTrackCount(uri)` (linhas 633-644); se `!= 1`, aborta com "O arquivo possui N faixas de vídeo. O corte foi bloqueado para não descartar conteúdo."
- Mapas: linha 697 (preciso), linha 890 (bordas híbrido), `FfmpegMediaPolicies.kt:195` (corpo híbrido) — todos `0:v:0? 0:a? 0:s? 0:d?`.

**Gravidade:** NENHUMA (apenas NOVO-1 residual — ver abaixo)

---

#### Finding 10 — CORRIGIDO

**Finding original:** branch explícito `m4a`/`aac` removido de `preciseAudioEncoderArguments`; caía no `else`.

**Evidência no código:**
- `FfmpegMediaPolicies.kt:65-74` (`cutAudioEncoderArguments`) — tem o branch explícito na linha 72:
  ```kotlin
  "m4a", "aac" -> listOf("-c:a", "aac", "-b:a", bitrate)
  else -> listOf("-c:a", "aac", "-b:a", bitrate)
  ```
  ANTES do `else`.
- `FfmpegCutActivity.kt:1002-1008` (`preciseAudioEncoderArguments`) delega a `cutAudioEncoderArguments`.

**Teste:** `FfmpegMediaPoliciesTest.cutAudioArgumentsPreservePcmDepthAndDeclareCommonContainers` (linhas 66-86) cobre `wav`→`pcm_s24le` e `m4a`→`aac -b:a`.

**Gravidade:** NENHUMA

---

### FERRAMENTA DE JUNTAR — `FfmpegJoinVideosActivity.kt` (2349 linhas)

---

#### Finding 11 — PARCIALMENTE CORRIGIDO

**Finding original:** aridade de áudio 1 em todos os caminhos; faixas secundárias/legendas descartadas sem aviso.

**Evidência no código:**
- **MELHORADO** — `requestAudioTrack` (linhas 1860-1871): o título do diálogo diz explicitamente "Escolha 1 das N faixas de \<nome\>; as demais não entrarão na saída recodificada". Só é exibido quando `audioTrackCount(uri) > 1` (linhas 494-499, 501).
- **MELHORADO** — `SmartJoinPlanner.kt` foi **DELETADO** (commit 52f6253). A comparação de perfis agora é `FfmpegMediaPolicies.directConcatSignaturesCompatible` (:313-321), que compara a **lista inteira** de assinaturas — igualdade de listas implica igualdade de contagem de faixas.
- **NÃO FEITO** — aridade continua 1: `concat=n=N:v=0:a=1` (linhas 713, 724) e `concat=n=N:v=1:a=1` (linhas 872, 907).

**Argumentos FFmpeg efetivos (junta de áudio, 2 clipes, reencode):**
```
ffmpeg -y -i A -i B -filter_complex
  "[0:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a0];
   [1:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a1];
   [a0][a1]concat=n=2:v=0:a=1[aout]"
  -map "[aout]" -vn -c:a aac -ar 48000 -ac 2 -b:a 192k -avoid_negative_ts make_zero OUT
```

**Impacto residual:** faixas secundárias continuam descartadas — agora por **decisão explicitamente comunicada**, não por acidente.

**Gravidade:** BAIXA (comportamento divulgado)

---

#### Finding 12 — PARCIALMENTE CORRIGIDO

**Finding original:** nenhuma rota de reencode tinha `-c:a copy`; áudios idênticos sempre reencodados.

**Evidência no código:**
- **MELHORADO** — `audioEncoderForOutput` (linhas 738-756) escolhe `pcm_s16le`/`flac`/`libmp3lame`/`libopus`/`libvorbis`/`aac` pela extensão e codec detectado. `audioEncodingArguments` (linhas 810-821) preserva opus/vorbis/flac/mp3/ac3.
- **NÃO FEITO** — não existe `-c:a copy` em `joinAudioCommandArguments` (`FfmpegMediaPolicies.kt:134-149`): sempre emite `-c:a <encoder>`. `buildReencodeArguments` (linhas 782-794) sempre chama `audioEncodingArguments(profile)`.
- **Aviso de conversão:** existe como etapa do tracker (linhas 672-677, `executeAudioJoin`) e como rótulo ("Convertendo áudios incompatíveis para WAV", `executeAudioJoin` linha 655), mas **não** como diálogo anterior à execução.

**Impacto residual:** áudios idênticos só escapam do reencode pela rota de concat direto (rigorosamente validada — ver item 13). Quando o reencode roda, roda porque o usuário pediu (`reencodeChecked`) ou porque os clipes divergem (`audioNeedsNormalization`).

**Fix restante (opcional):** quando a normalização for acionada apenas por `firstAudioExtension == "mp3"` (linha 539) e as assinaturas forem idênticas, permitir concat direto.

**Gravidade:** BAIXA

---

#### Finding 13 — CORRIGIDO

**Finding original:** pré-validação do concat direto olhava só topologia; `buildDirectConcatArguments` era `-map 0 -c copy` puro.

**Evidência no código:**
- `FfmpegJoinVideosActivity.kt:1731-1736` (`directConcatCompatibilityError`) → `streamCopySignatures` (linhas 1738-1787) → `FfmpegMediaPolicies.directConcatSignaturesCompatible` (:313-321).
- Assinatura por faixa (`FfmpegStreamCopySignature`, `FfmpegMediaPolicies.kt:5-28`) inclui: `containerFamily`, `ffmpegDescriptor`, `mime`, `profile`, `level`, `sampleRate`, `channels`, `channelMask`, `pcmEncoding`, `width`, `height`, `frameRate`, `colorStandard/Transfer/Range`, `codecTag`, `sampleFormat`, `channelLayout`, `timeBase` e hashes de `csd-0/1/2` (extradata).
- `ffmpegStreamCopyDescriptors` (linhas 1789-1809) extrai linhas de stream com regex ancorada em `Stream #N:M...: (Video|Audio|Subtitle|Data|Attachment):` — não é mais `contains("Video:")`.
- `streamCopySignatures` retorna `null` (incompatível) se `descriptors.size != extractor.trackCount`.
- Consumo: linha 569 (vídeo sem reencode → erro + "Ative 'Recodificar'") e linha 538 (áudio → força normalização).

**Teste:** `FfmpegMediaPoliciesTest.directConcatRequiresExactContainerAndFfmpegStreamContract` (linhas 155-175) cobre container divergente, container desconhecido, timebase divergente e assinatura nula.

**Argumentos FFmpeg efetivos (concat direto autorizado):**
```
ffmpeg -y -fflags +genpts -f concat -safe 0 -i join_list_<ts>.txt -map 0 -map_metadata 0 -map_chapters 0 -c copy -avoid_negative_ts make_zero OUT.mkv
```
O `join_list_*.txt` é apagado após execução (linhas 983-984).

**Gravidade:** NENHUMA

---

#### Finding 14 — PARCIALMENTE CORRIGIDO

**Finding original:** áudios incompatíveis viravam WAV silenciosamente.

**Evidência no código:**
- `audioNeedsNormalization` (linhas 538-539) e `audioWillStandardizeToWav` (linhas 540-541) — detectam a necessidade.
- `buildJoinedOutputName` (linhas 1709-1727) força extensão `.wav` → o usuário **vê o nome do arquivo**.
- `executeAudioJoin` (linhas 652-660): rótulo da etapa passa de "Juntando áudios sem reencodar" para "Convertendo áudios incompatíveis para WAV no perfil agregado" via `renameProcessingStep` (linhas 658-660, 1574-1586).
- Encoder exibido: `pcm_s16le`.

**NÃO FEITO:** não há AlertDialog de confirmação antes da conversão — o fix aceitava "ou ao menos registrar uma linha de status explícita", alternativa implementada.

**Gravidade:** BAIXA

---

#### Finding 15 — CORRIGIDO

**Finding original:** junta de vídeo rejeitava arquivo com legenda em QUALQUER modo (regressão introduzida pela correção anterior).

**Evidência no código:**
- `validateSupportedStreamTopology(inputs, audioOnly)` (linhas 1811-1834) — **não tem mais nenhuma checagem de legenda**. Só exige `videoCount == 1` para vídeo e `audioCount >= 1` para áudio. A regra `subtitleCount > 0` foi **removida**.
- O diálogo de legenda (linhas 503-511) está condicionado a `!audioOnly && reencodeChecked` — título "As legendas não podem participar das transições", botão "Remover e continuar". Só aparece quando há transição, exatamente como o fix previa.
- `subtitleTrackCount(uri)` (linhas 1844-1858) só é usado nesse diálogo.
- O checkbox/linha do Smart Join foi removida do XML.

**Argumentos FFmpeg efetivos (vídeo com legenda, sem reencode):**
```
ffmpeg -y -fflags +genpts -f concat -safe 0 -i join_list_<ts>.txt -map 0 -map_metadata 0 -map_chapters 0 -c copy -avoid_negative_ts make_zero OUT.mkv
```
As legendas são preservadas (saída MKV).

**Impacto residual:** nenhum. MKV com legenda volta a ser unido, preservando a legenda.

**Gravidade:** NENHUMA

---

#### Finding 16 — CORRIGIDO

**Finding original:** Smart Join legado (`executeSmartJoinExperiment`) permanecia com `-ss` antes de `-i` + `-c:v copy` sem alinhamento a keyframes; rota inalcançável por `smartJoinChecked = false`.

**Evidência no código:**
- `rg -n "SmartJoin|smartJoin|executeSmartJoinExperiment|buildTransitionArgumentsMkv"` em `app/src/` → **ZERO ocorrências**.
- `SmartJoinPlanner.kt` e `SmartJoinPlannerTest.kt` **DELETADOS** (git diff: -191, -154 linhas).
- Linha `smart_join_row` (com `check_smart_join` e `help_smart_join`) removida de `activity_ffmpeg_join_videos.xml`.
- Nenhum call site inalcançável remanescente.

**Gravidade:** NENHUMA

---

#### Finding 17 — CORRIGIDO

**Finding original:** rótulo "Normalizando pelo primeiro áudio" errado — normalização usa perfil agregado.

**Evidência no código:**
- `rg -n "Normalizando"` em `FfmpegJoinVideosActivity.kt` → **ZERO ocorrências**.
- Rótulos agora: `initProcessingSteps` (linhas 1514-1531) → "Juntando áudios sem reencodar" / "Aplicando transição de áudio"; `executeAudioJoin` (linhas 652-657) → "Convertendo áudios incompatíveis para WAV no perfil agregado"; `regularVideoProcessingLabels` (linhas 1505-1512) → "Juntando sem reencodar" / "Aplicando Fade in/out" / "Aplicando transições".
- `detectAggregateOutputProfile` (linhas 2023-2053) confirma: máximo de width, height, fps, videoBitrate, audioSampleRate, audioChannels, audioBitrate.

**Gravidade:** NENHUMA

---

### FERRAMENTA DE EXTRAIR ÁUDIO — `FfmpegExtractAudioActivity.kt` (1736 linhas)

---

#### Finding 18 — PARCIALMENTE CORRIGIDO

**Finding original:** caminho de cópia restrito (sempre `false` com corte; exigia taxa e canais iguais); WAV → `pcm_s16le`; MP3 CBR; detecção de WAV dependia só do mime `audio/raw`.

**Evidência no código:**
- **(b) RESOLVIDO** — `detectPcmEncoder(inputFile, audioTrack)` (linhas 972-991) lê `KEY_PCM_ENCODING` → `pcm_u8`/`pcm_f32le`/`pcm_s24le`/`pcm_s32le`/`pcm_s16le`.
- **(mime) RESOLVIDO** — `canCopyAudioWithoutConversion` linha 961: `"audio/raw", "audio/x-raw", "audio/wav", "audio/x-wav" -> WAV`.
- **(CBR) PARCIAL** — `extractAudioEncoderArguments` linha 93: `"mp3" -> listOf("-c:a","libmp3lame","-b:a",bitrate)`. `-minrate`/`-maxrate` saíram, mas não há controle CBR/VBR na UI.
- **(cópia com corte) NÃO FEITO** — linha 945: `if (startMs > 0L || (endMs != null && duration > 0L && endMs < duration - 250L)) return false`. Só há cópia na extração do arquivo inteiro (tolerância de 250ms no fim).

**Argumentos FFmpeg efetivos (extração integral WAV 24-bit → WAV):**
```
ffmpeg -y -i IN -vn -map 0:a:0 -map_metadata 0 -c:a copy -avoid_negative_ts make_zero OUT.wav
```

**Argumentos FFmpeg efetivos (com corte, MP3):**
```
ffmpeg -y -ss 2.000 -i IN -t 3.000 -vn -map 0:a:1 -map_metadata 0 -ar 48000 -ac 2 -c:a libmp3lame -b:a 160k -avoid_negative_ts make_zero OUT.mp3
```

**Impacto residual:** nenhum comando incorreto. Perde-se apenas a oportunidade de cópia com corte em WAV/FLAC e a escolha de VBR.

**Fix restante:** (i) liberar cópia com corte em PCM/FLAC; (ii) expor CBR/VBR na UI.

**Gravidade:** BAIXA

---

#### Finding 19 — PARCIALMENTE CORRIGIDO

**Finding original:** mapa da faixa sem `?` (defesa em profundidade).

**Evidência no código:**
- **RESOLVIDO (substância)** — `buildFfmpegArguments` linha 873 usa `FfmpegMediaPolicies.audioStreamSpecifier(0, audioTrack)`, com `audioTrack` do seletor (linhas 743-748, 751, 785). Índice não é mais sempre 0.
- Bloqueio prévio: linhas 739-742 rejeitam arquivo sem áudio; 743-748 exigem seleção quando há mais de uma.
- **NÃO FEITO (letra do fix)** — `audioStreamSpecifier` (`FfmpegMediaPolicies.kt:62-63`) devolve `"$inputIndex:a:${audioTrackIndex.coerceAtLeast(0)}"` — sem `?`.

**Teste:** `FfmpegMediaPoliciesTest.selectedAudioTrackProducesExplicitFfmpegSpecifier` (linhas 60-64) congela o formato sem `?`.

**Impacto residual:** nenhum funcional — índice validado antes, e `coerceAtLeast(0)` impede índice negativo.

**Fix restante (opcional, uma linha):** trocar `audioStreamSpecifier` por `"$inputIndex:a:${idx}?"` e atualizar o teste.

**Gravidade:** BAIXA (endurecimento)

---

#### Finding 20 — MELHORIA OPCIONAL (não é bug)

**Finding original:** OPUS alinhado para `-application audio` e `-vbr on`, mas sem opção de perfil de voz (`application=voip` + CBR).

**Evidência no código:**
- `FfmpegMediaPolicies.extractAudioEncoderArguments` linha 97: `"opus" -> listOf("-c:a","libopus","-application","audio","-b:a",bitrate,"-vbr","on")` — alinhado com Cortar (linha 71) e Extrair.
- O layout `activity_ffmpeg_extract_audio.xml` não tem controle de perfil de voz; só `button_bitrate`.

**Gravidade:** NENHUMA (preferência de produto)

---

### FERRAMENTA DE INSERIR ÁUDIO — `FfmpegInsertAudioActivity.kt` (1017 linhas)

---

#### Finding 21 — CORRIGIDO

**Finding original:** rota legada `executeCopyInsert` usava `-ss` antes de `-i` com `-c copy`, concatenava containers completos (`concatPieces`).

**Evidência no código:**
- `rg -n "executeCopyInsert|concatPieces|canCopyDirectly|smartInsertViable"` em `app/src/` → **ZERO ocorrências**. A rota foi **REMOVIDA** (não desabilitada).
- Apenas `buildFullReencodeArguments` (linhas 567-625) existe, chamado uma única vez em `startInsert()` (linha 520).
- O dispatcher `fullReencode = true` foi **removido** — não há mais variável: a única rota é sempre full reencode.

**Argumentos FFmpeg efetivos (inserção no meio, sem transição):**
```
ffmpeg -y -i PRINCIPAL -i INSERIDO -filter_complex
  "[0:a:0]atrim=start=0:end=10,[...],asetpts=PTS-STARTPTS[a0];
   [1:a:0]atrim=start=0:end=4,[...],asetpts=PTS-STARTPTS[a1];
   [0:a:0]atrim=start=10:end=60,[...],asetpts=PTS-STARTPTS[a2];
   [a0][a1][a2]concat=n=3:v=0:a=1[aout]"
  -map "[aout]" -vn -c:a aac -b:a 192k -ar 48000 -ac 2 -movflags +faststart -avoid_negative_ts make_zero OUT
```

**Impacto residual:** nenhum — inserção é sempre precisa, sem fronteira de pacote.

**Gravidade:** NENHUMA

---

#### Finding 22 — CORRIGIDO

**Finding original:** rota legada `executeSmartInsert` recodificava só o áudio inserido e concatenava bordas por cópia sem validar parâmetros.

**Evidência no código:**
- `rg -n "executeSmartInsert"` em `app/src/` → **ZERO ocorrências**. Removida.
- `startInsert()` (linhas 475-509): único ponto de entrada, sem dispatcher condicional.

**Gravidade:** NENHUMA

---

#### Finding 23 — CORRIGIDO

**Finding original:** `audioInputsAreCopyCompatible` comparava só codec (por `contains`), taxa e canais; perfil AAC, sample format e extradata faltavam.

**Evidência no código:**
- `rg -n "audioInputsAreCopyCompatible"` em `app/src/` → **ZERO ocorrências**. O validador foi **removido** junto com a rota de cópia.
- `AudioProfile` (linhas 962-968) agora carrega `sampleRate`, `channels`, `bitrate`, `codec` e `pcmEncoder`, lidos por `detectAudioProfile` (linhas 698-723) via MediaExtractor.
  - `AudioProfile` data class agora inclui `pcmEncoder: String = "pcm_s16le"` (diff linha +755).

**Gravidade:** NENHUMA

---

#### Finding 24 — PARCIALMENTE CORRIGIDO

**Finding original:** seleção de faixa inconsistente entre rotas; rota ativa sem `?`, rotas legadas fixavam `0:a:0`.

**Evidência no código:**
- **RESOLVIDO (inconsistência)** — rotas legadas **não existem mais** (findings 21-22). Resta uma única rota, que honra a seleção em todos os 3 pontos do filtro:
  - `buildFullReencodeArguments` linha 586: `[0:a:${jobConfig.mainAudioTrack}]` (trecho esquerdo)
  - linha 593: `[1:a:${jobConfig.insertedAudioTrack}]` (inserido)
  - linha 597: `[0:a:${jobConfig.mainAudioTrack}]` (trecho direito)
  - Todos via `FfmpegMediaPolicies.audioStreamSpecifier`.
- Seleção garantida por `startInsert()` linhas 478-487: exige pelo menos uma faixa de áudio em cada arquivo e dispara `requestAudioTrack` quando há mais de uma.
- **NÃO FEITO (letra do fix)** — falta o `?`, pela mesma função compartilhada do finding 19 (`audioStreamSpecifier`, `FfmpegMediaPolicies.kt:62-63`).

**Gravidade:** BAIXA (endurecimento, sem impacto funcional — ver finding 19)

---

#### Finding 25 — CORRIGIDO

**Finding original:** modos "sem reencodar" e "Smart Insert" eliminados, mas UI não comunicava; checkbox marcado/desabilitado, "Smart Insert" oculto.

**Evidência no código:**
- `git diff 85b889f..52f6253 -- activity_ffmpeg_insert_audio.xml`: o `LinearLayout` com `check_reencode` e `check_smart_insert` foi **substituído** por um `TextView` com texto:
  > "A inserção usa recodificação precisa para respeitar exatamente o ponto e as transições escolhidas."
  (layout linha 247)
- `rg -n "check_reencode|check_smart_insert"` no layout → **ZERO ocorrências**.
- `rg -n "check_reencode|check_smart_insert"` no Kotlin → **ZERO ocorrências** (diff mostra remoção das importações `CheckBox`, das fields, dos `findViewById`, dos listeners e do `reencode.isChecked`/`smartInsert.isChecked`).
- Resultado final informa "Modo: Inserção precisa" (`startInsert`, linhas 531-536).
- `setProcessing` (linhas 733-752) **não referencia** `check_reencode` ou `check_smart_insert` — nenhum NPE possível.

**Gravidade:** NENHUMA

---

### FERRAMENTA DE LIMPAR ÁUDIO — `FfmpegCleanAudioActivity.kt` (594 linhas)

---

#### Finding 26 — PARCIALMENTE CORRIGIDO

**Finding original:** `-map 0:a:0` sem `?`; mitigação existia.

**Evidência no código:**
- `FfmpegMediaPolicies.kt:62-63` (`audioStreamSpecifier`) devolve `"0:a:0"` — sem `?`.
- `FfmpegCleanAudioActivity.kt:270` — `audioMap = FfmpegMediaPolicies.audioStreamSpecifier(0, 0)` → `"0:a:0"`.
- `FfmpegMediaPolicies.kt:181` — `cleanAudioCommandArguments` usa `-map audioMap` → `-map 0:a:0`.
- **Mitigação intacta e eficaz:** `cleanSelectedAudio` linhas 177-181 exige `inspectAudioSource(uri)?.trackCount == 1` ("O arquivo precisa ter exatamente uma faixa de áudio."). `audioSourceProfile` (linhas 302-319) só devolve perfil com `audioFormats.singleOrNull()`.
- **A reclamação sobre WAV 16 kHz mono está CORRIGIDA:** linhas 308-317 escolhem `pcm_u8`/`pcm_f32le`/`pcm_s24le`/`pcm_s32le`/`pcm_s16le` por `KEY_PCM_ENCODING`; `cleanAudioCommandArguments` preserva `-ar` e `-ac` da origem.

**Argumentos FFmpeg efetivos (origem 96 kHz, 6 canais, 32-bit):**
```
ffmpeg -y -i IN -vn -map 0:a:0 -af afftdn=nf=-25 -c:a pcm_s32le -ar 96000 -ac 6 -avoid_negative_ts make_zero -f wav OUT.wav
```

**Teste:** `FfmpegMediaPoliciesTest.cleanCommandPreservesRequestedPcmProfile` (linhas 141-153).

**Impacto residual:** nenhum funcional — índice 0 sempre válido pela validação prévia.

**Fix restante (opcional):** `"-map", "0:a:0?"` — uma linha.

**Gravidade:** BAIXA (endurecimento)

---

### PENDÊNCIAS TRANSVERSAIS

---

#### Finding 27 — PARCIALMENTE CORRIGIDO

**Finding original:** não existe política única de legendas; Juntar rejeitava legenda em qualquer modo.

**Evidência no código:**
- **Girar preserva:** `FfmpegRotateVideoActivity.kt:1131-1133` (`0:s? 0:d? 0:t?`) e `:1332` (`0:v:0 0:a? 0:s? 0:d? 0:t?`).
- **Cortar preserva:** `FfmpegCutActivity.kt:697` e `:890` (`0:v:0? 0:a? 0:s? 0:d?`), `FfmpegMediaPolicies.kt:195`.
- **Juntar:** preserva no concat direto (`-map 0`, `FfmpegMediaPolicies.kt:130`) e pede confirmação antes de descartar no reencode (`FfmpegJoinVideosActivity.kt:503-511`). A rejeição indiscriminada foi **removida** (ver item 15).
- **NÃO FEITO** — não há política única em `FfmpegMediaPolicies`: cada Activity monta os seus próprios `-map`.

**Impacto residual:** duplicação de lógica de mapeamento entre ferramentas (risco de drift).

**Fix restante (opcional):** centralizar a lista de mapas padrão em `FfmpegMediaPolicies`.

**Gravidade:** BAIXA (governança/duplicação)

---

#### Finding 28 — PARCIALMENTE CORRIGIDO

**Finding original:** nenhum teste cobria os construtores de comando por ferramenta; só `FfmpegMediaPoliciesTest` (8 casos) existia.

**Evidência no código:**
- **AVANÇO GRANDE** — construtores extraídos das Activities para o objeto puro `FfmpegMediaPolicies`. `FfmpegMediaPoliciesTest.kt` passou de 8 para **16 testes** (0 falhas). Cobertura:

| Função testada | Activity de origem |
|---|---|
| `metadataRotationCopyArguments` | Girar |
| `metadataCopyPreflightArguments` | Girar |
| `cutAudioEncoderArguments` | Cortar |
| `cutAudioCommandArguments` | Cortar |
| `hybridCopyBodyArguments` | Cortar (híbrido) |
| `extractAudioEncoderArguments` | Extrair |
| `extractAudioCommandArguments` | Extrair |
| `insertAudioCommandArguments` | Inserir |
| `cleanAudioCommandArguments` | Limpar |
| `joinAudioCommandArguments` | Juntar |
| `directConcatCommandArguments` | Juntar |
| `directConcatSignaturesCompatible` | Juntar |
| `audioStreamSpecifier` | Compartilhado |
| `normalizedAudioFilter` | Juntar/Inserir |
| `channelLayout`, `parseAudioChannelCount` | Compartilhado |
| `normalizeRightAngle`, `physicalRotationFilters` | Girar |
| `metadataRotationAfterClockwiseRequest` | Girar |
| `parseKnownVideoProfile`, `videoMimeForName`, `safeContainerExtension`, `containerFamily`, `formatCommand` | Compartilhado |

- **NÃO COBERTO** — grafos de filtro privados nas Activities:
  - `buildFilterComplex` / `buildFadeInOutFilterComplex` (`FfmpegJoinVideosActivity.kt:823-910`) — aridade `v=1:a=1`, `xfade`/`acrossfade`
  - `buildAudioReencodeArguments` (`:685-736`) — aridade `v=0:a=1`
  - `buildFullReencodeArguments` (`FfmpegInsertAudioActivity.kt:567-625`) — `atrim`/`afade`/`acrossfade`
  - Lógica de decisão das Activities (fallback do híbrido, recusa do concat direto, pré-flight do Girar).

**Gravidade:** BAIXA

---

#### Finding 29 — CORRIGIDO

**Finding original:** código morto nas Activities — funções sem chamador.

**Evidência no código:**
- Varredura por script (`private fun X(` × contagem de usos no próprio arquivo) nas 6 Activities: **ZERO funções privadas sem chamador**.
- Varredura dos 23 membros de `FfmpegMediaPolicies`: **ZERO sem chamador** fora do próprio arquivo.
- `rg -n "describeAudioFile|probeAudioFile|estimateBitrate"` em `app/src/` → só em `GraniteActivity.kt` e `RemoteSttActivity.kt` (cópias privadas e utilizadas — nada a ver com o Extrair).
- `rg -n "SmartJoin|buildTransitionArgumentsMkv|detectContainerBitrateKbps|letterbox"` → **ZERO**.
- `videoFillFrameFilter` perdeu o parâmetro `letterbox` e é chamado em `:840` e `:881`.

**Gravidade:** NENHUMA

---

### ACHADOS NOVOS (não presentes nos 29)

---

#### NOVO-1 — Cortar descarta anexos (`0:t`) em todas as rotas

- **Onde:** `FfmpegCutActivity.kt:697` (preciso), `:890` (bordas híbrido), `FfmpegMediaPolicies.kt:195` (corpo híbrido).
- Todas as rotas de corte mapeiam `0:v:0? 0:a? 0:s? 0:d?` — **não** mapeiam `0:t?` (anexos/fontes).
- Girar preserva: `FfmpegRotateVideoActivity.kt:1133, 1332` (`0:t?`).
- **Cenário:** MKV com legenda ASS + fonte embutida → a legenda é preservada, a fonte não → legenda renderizada com fonte errada.
- **Fix:** acrescentar `-map 0:t?` nas 3 rotas do Cortar.
- **Gravidade:** BAIXA/MÉDIA

---

#### NOVO-2 — Bloqueio por MediaExtractor cria falso negativo

- **Onde:** `FfmpegRotateVideoActivity.kt:450-458` + `:499-511`; `FfmpegCutActivity.kt:491-498` + `:633-644`.
- **Cenário:** arquivo que o `MediaExtractor` não consegue abrir (container/codec fora do suporte da plataforma). `videoTrackCount` devolve 0 e a operação aborta com "não possui faixa de vídeo", mesmo sendo válido para o FFmpeg.
- **Agravante no Cortar:** `selectedMime` vem de `detectMediaMime` (`:472-487`), que usa `contentResolver.getType()` e pode devolver `video/*` justamente no caso em que o extrator falhou.
- **Fix:** quando `videoTrackCount` retornar 0 **por exceção** (não por contagem real zero), avisar e permitir seguir.
- **Gravidade:** BAIXA (raro) — regressão de alcance introduzida pelos fixes 2 e 9

---

#### NOVO-3 — Juntar áudio multi-faixa promete uma saída e entrega outra

- **Onde:** `FfmpegJoinVideosActivity.kt:501` (`hasSelectedMultitrackAudio`), `:538-541` (`audioNeedsNormalization`/`audioWillStandardizeToWav`), `:655` (rótulo), `:1863` (texto do diálogo).
- **Cenário:** usuário escolhe 1 de N faixas. O diálogo diz "as demais não entrarão na saída recodificada" — sugerindo manutenção do formato. Como `hasSelectedMultitrackAudio` entra em `audioNeedsNormalization` e `checkReencode` está desmarcado, a saída é forçada para **WAV/pcm_s16le** (`buildJoinedOutputName:1716`, `standardizeToWav = true:667`).
- O rótulo da etapa avisa ("...para WAV..."), mas o diálogo e o rótulo se contradizem.
- **Fix:** alinhar o texto do diálogo (mencionar WAV) ou não forçar WAV quando a única razão da normalização for a seleção de faixa.
- **Gravidade:** BAIXA (inconsistência de comunicação)

---

## CONCLUSÃO

Das **29 findings** do relatório-base, **18 estão totalmente corrigidas** e **10 estão parcialmente corrigidas** no HEAD `52f6253`. **Nenhuma permanece ABERTA** (nenhum comando FFmpeg incorreto na configuração padrão).

Os **3 achados novos** (NOVO-1, NOVO-2, NOVO-3) são de baixa a média gravidade, não produzem arquivos corrompidos, e todos têm fixes triviais (1-3 linhas).

A validação foi feita por:
1. **Leitura integral** das 6 Activities + `FfmpegMediaPolicies.kt`
2. **`git diff 85b889f..52f6253`** para confirmar exatamente o que foi alterado
3. **`rg` (ripgrep)** para confirmar ausência de código morto e padrões removidos
4. **Suíte unitária** (`FfmpegMediaPoliciesTest`: 16 testes, 0 falhas) e `FfmpegKitClasspathTest` (1 teste, 0 falhas)

**Limitação:** não houve execução com arquivos de mídia reais (sem binário FFmpeg Android, dispositivo ou emulador neste ambiente). Portanto, nenhuma afirmação sobre comportamento em runtime do FFmpeg (duração, sincronia, rotação efetiva) foi validada empiricamente — as conclusões sobre corretude de comandos baseiam-se exclusivamente em análise estática do código e da suíte de testes.
