================================================================================
AUDITORIA INDEPENDENTE DOS 29 FINDINGS — FERRAMENTAS FFmpeg (SIG ANDROID)
================================================================================
Data:        2026-08-31
HEAD auditado: 52f6253 ("fix: validar compatibilidade e remover legado ffmpeg")
Base dos findings: 85b889f
Relatório-base: findings_consolidados_ffmpeg_2026-08-31.txt (tratado como hipótese)
Método: leitura integral do código atual, `rg`, `git diff 85b889f..52f6253`,
        execução da suíte unitária. Nenhum arquivo alterado; nenhum commit.

Teste executado (registro):
  ./gradlew.bat :app:testDebugUnitTest --tests "br.gov.sp.pcsp.launcher.Ffmpeg*"
      --rerun-tasks --console=plain
  Resultado: BUILD SUCCESSFUL in 39s — 26 actionable tasks: 26 executed
  app/build/test-results/testDebugUnitTest/TEST-br.gov.sp.pcsp.launcher.FfmpegMediaPoliciesTest.xml
      tests="16" skipped="0" failures="0" errors="0"
  (compileDebugUnitTestKotlin executado => o código das 6 Activities compila)

Legenda: CORRIGIDO · PARCIALMENTE CORRIGIDO · ABERTO · NÃO É BUG ·
         MELHORIA OPCIONAL · CONTRAPRODUCENTE

================================================================================
RESUMO
================================================================================
- Corrigidos (18):              1, 2, 3, 4, 6, 7, 8, 9, 10, 13, 15, 16, 17, 21,
                                22, 23, 25, 29
- Parcialmente corrigidos (10): 5, 11, 12, 14, 18, 19, 24, 26, 27, 28
- Abertos (0):                  —
- Não são bugs (0):             —
- Melhorias opcionais (1):      20
- Contraproducentes (0):        —

Achados novos identificados nesta auditoria (não presentes nos 29):
  NOVO-1  Cortar descarta anexos (`0:t`) em todas as rotas.
  NOVO-2  Bloqueio por MediaExtractor cria falso negativo (Girar/Cortar).
  NOVO-3  Juntar áudio multi-faixa promete "saída recodificada" mas entrega WAV.

================================================================================
FERRAMENTA DE GIRAR VÍDEO — FfmpegRotateVideoActivity.kt (1966 linhas)
================================================================================

1) Status: CORRIGIDO
Finding: modo somente-metadados herdava a extensão sem validar codecs, legendas
  e demais streams contra o container; falha só no fim do processo, sem aviso.
Evidência no código:
  - `rotateSelectedVideo()` (~linha 462): quando `metadataOnly` e
    `FfmpegMediaPolicies.safeContainerExtension(selectedName)` difere da
    extensão original, exibe Toast "Container não reconhecido: a cópia será
    salva em MKV." — o remapeamento deixou de ser silencioso.
  - `executeRotation()` (linhas 574-607): antes da cópia definitiva roda um
    PRÉ-FLIGHT real:
    `FfmpegMediaPolicies.metadataCopyPreflightArguments(...)` sobre o mesmo
    container de saída, com `-map 0 -c copy -t 0.001`. Se falhar, a operação é
    abortada com "Compatibilidade recusada antes da cópia: <motivo>" e o
    arquivo temporário do pré-flight é apagado no `finally`.
  - `FfmpegMediaPolicies.metadataCopyPreflightArguments`
    (FfmpegMediaPolicies.kt:48-60) e `metadataRotationCopyArguments`
    (FfmpegMediaPolicies.kt:33-46).
  - `buildOutputName()` (linha 1890-1896): `safeContainerExtension` para
    metadados, `mkv` para rotação física.
Argumentos FFmpeg efetivos (pré-flight):
  ffmpeg -y -hide_banner -loglevel error -display_rotation:v:0 <ccw> -i IN
         -map 0 -c copy -t 0.001 <saida.mesma.extensao>
Argumentos FFmpeg efetivos (cópia):
  ffmpeg -y -display_rotation:v:0 <ccw> -i IN -map 0 -c copy OUT
Impacto residual: nenhum material. O pré-flight de 1 ms cobre header de muxer e
  incompatibilidade de codec; não cobre erros que só aparecem na escrita do
  trailer/índice (caso extremo, custo baixo).
Fix restante, se houver: opcional — aumentar `-t` para ~0,5 s no pré-flight.
Gravidade: BAIXA (residual)

--------------------------------------------------------------------------------

2) Status: CORRIGIDO
Finding: apenas a primeira faixa de vídeo era preservada (`-map 0:v:0`) sem
  bloqueio nem aviso; arquivo com 2 vídeos perdia o segundo.
Evidência no código:
  - `rotateSelectedVideo()` linhas 450-458: `val videoTracks = videoTrackCount(uri)`;
    se `videoTracks != 1`, aborta. Mensagens: "O arquivo não possui uma faixa de
    vídeo." (0) e "O arquivo possui N faixas de vídeo. Esta ferramenta aceita
    exatamente uma para não descartar conteúdo." (2+).
  - `videoTrackCount(uri)` linhas 499-511: MediaExtractor, conta mime `video/`.
  - Único ponto de entrada: `buttonRotate` → `rotateSelectedVideo()` (linha 231).
    Não há rota alternativa que pule a checagem.
  - Mapas permanecem `0:v:0` (linha 1332 sequencial; linha 1129 paralelo) —
    agora garantido válido pelo bloqueio.
Argumentos FFmpeg efetivos (sequencial):
  ffmpeg -y [-ss INICIO] -noautorotate -display_rotation:v:0 0 -i IN [-t DUR]
         [-vf <filtros>] -map 0:v:0 -map 0:a? -map 0:s? -map 0:d? -map 0:t?
         -c copy -c:v <encoder> ... -map_metadata 0 -avoid_negative_ts make_zero OUT
Impacto residual: nenhum. Adotada a segunda opção do fix (contar e bloquear).
Fix restante, se houver: nenhum obrigatório. Ver NOVO-2 (risco do bloqueio).
Gravidade: NENHUMA

--------------------------------------------------------------------------------

3) Status: CORRIGIDO
Finding: paralelismo desligado em silêncio quando "girar pelos metadados" está
  ativo; faltava o quarto cenário de aviso.
Evidência no código:
  - `canUseParallel` (linha 521) continua excluindo `metadataOnly`, mas o quarto
    cenário deixou de ser silencioso: `updateMetadataModeState()` linhas 1462-1466
    faz `parallelKeyframes.isEnabled = !metadataOnly` e aplica alpha 0.42f —
    exatamente o padrão de `updateOptionState` pedido no fix.
  - O listener de `parallelKeyframes` (linha 312) chama `updateMetadataModeState()`.
  - Os outros dois avisos seguem presentes: linhas 525-530 ("corte requer
    processamento sequencial") e 531-536 ("não há transformação física").
  - `parallelSegmentsContainer` e `inputParallelSegments` também são
    desabilitados (linhas 1467-1469).
Argumentos FFmpeg efetivos: inalterados; a mudança é de estado de UI.
Impacto residual: nenhum.
Fix restante, se houver: nenhum.
Gravidade: NENHUMA

--------------------------------------------------------------------------------

4) Status: CORRIGIDO
Finding: intervalo de corte preservado/restaurado ao alternar para modo
  metadados, mas sem aviso de que será ignorado temporariamente.
Evidência no código:
  - Listener de `metadataRotation` linhas 289-296: quando `checked` e existe
    corte ativo, exibe Toast "O intervalo de corte será preservado, mas não será
    aplicado no modo por metadados." — o aviso pedido existe.
  - Preservação/restauração em `updateMetadataModeState()` linhas 1437-1458
    (`savedTrimStartMs`/`savedTrimEndMs`, campos declarados em 123-124) e
    reset visual da timeline em 1473-1477 / restauração em 1451-1454.
Argumentos FFmpeg efetivos: `metadataRotationCopyArguments` não emite `-ss`/`-t`
  — o corte é realmente ignorado, como avisado.
Impacto residual: nenhum.
Fix restante, se houver: nenhum.
Gravidade: NENHUMA

================================================================================
FERRAMENTA DE CORTAR ÁUDIO/VÍDEO — FfmpegCutActivity.kt (1827 linhas)
================================================================================

5) Status: PARCIALMENTE CORRIGIDO
Finding: corte de áudio sempre reencodava; `preciseAudioEncoderArguments` fixava
  `pcm_s16le` (derrubava 24/32-bit), MP3 em CBR; sem exposição de qualidade.
Evidência no código:
  - (b) RESOLVIDO — `detectPcmEncoder(inputFile)` (linhas 1010-1029) lê
    `MediaFormat.KEY_PCM_ENCODING` e devolve `pcm_u8` / `pcm_f32le` /
    `pcm_s24le` / `pcm_s32le` / `pcm_s16le`. É passado a
    `preciseAudioEncoderArguments` (linhas 1002-1008) →
    `FfmpegMediaPolicies.cutAudioEncoderArguments` (:65-74), que usa o parâmetro
    `pcmEncoder` no branch `wav`.
  - (a) PARCIAL — existe `-c:a copy`, mas só sem corte real:
    `buildPreciseFfmpegArguments` linhas 668-675:
    `hasRealTrim = startMs > 0L || (inputDurationMs > 0L && endMs < inputDurationMs - 10L)`;
    se `!hasRealTrim` → `listOf("-c:a", "copy")`. Com corte real continua
    reencodando (não há cópia com alinhamento a pacote em WAV/FLAC).
  - (c) NÃO FEITO — o layout `activity_ffmpeg_cut.xml` só expõe
    `button_video_encoder` e `button_video_quality`; não há nenhum controle de
    bitrate/CBR/VBR para áudio.
Argumentos FFmpeg efetivos (sem corte real, WAV 24-bit):
  ffmpeg -y -ss 0.000 -i IN -t 12.500 -map 0:a? -map_metadata 0 -map_chapters 0
         -vn -c:a copy -avoid_negative_ts make_zero OUT
Argumentos FFmpeg efetivos (com corte, WAV 32-bit):
  ffmpeg -y -ss 3.000 -i IN -t 5.000 -map 0:a? -map_metadata 0 -map_chapters 0
         -vn -c:a pcm_s32le -avoid_negative_ts make_zero OUT
Impacto residual: cópia segura porque `buildOutputName()` (linhas 1241-1246)
  preserva a extensão da origem para áudio — o container de saída é sempre o
  mesmo da entrada. Falta apenas escolha de qualidade/bitrate para o usuário.
Fix restante, se houver: (i) permitir `-c:a copy` com corte quando a origem for
  PCM/FLAC de taxa constante, cortando no limite de amostra; (ii) expor seletor
  de qualidade de áudio na UI do Cortar (hoje só existe vídeo).
Gravidade: BAIXA (qualidade/exposição; nenhum comando incorreto)

--------------------------------------------------------------------------------

6) Status: CORRIGIDO
Finding: corpo do corte híbrido truncava microssegundos para milissegundos e
  usava `-ss` depois de `-i` (custo de leitura integral).
Evidência no código:
  - `buildHybridBodyArguments` (linhas 900-907) delega a
    `FfmpegMediaPolicies.hybridCopyBodyArguments` (FfmpegMediaPolicies.kt:187-199):
    `-ss` ANTES de `-i`, formatado com `String.format(Locale.US, "%.6f",
    safeStart / 1_000_000.0)`. Sem truncamento: os keyframes vêm em
    microssegundos de `extractKeyframesFromFile` (linhas 909-930, `sampleTime`
    cru do MediaExtractor) e `startUs = startMs * 1000L` (linha 730).
  - Teste de cobertura: `FfmpegMediaPoliciesTest.hybridBodyKeepsMicrosecondPrecisionAndSeeksBeforeInput`
    (linhas 177-184) — afirma `args[2] == "8.333333"`, `indexOf("-ss") < indexOf("-i")`.
Argumentos FFmpeg efetivos (corpo, keyframes em 8,333333 s e 9,999999 s):
  ffmpeg -y -ss 8.333333 -noautorotate -i IN -t 1.666666 -map 0:v:0? -map 0:a?
         -map 0:s? -map 0:d? -map_metadata 0 -map_chapters 0 -c copy
         -avoid_negative_ts make_zero -f matroska OUT
Impacto residual: nenhum funcional. Com `-accurate_seek` (padrão do FFmpeg), o
  input seek descarta pacotes com PTS < alvo; como o alvo é exatamente um
  keyframe, o primeiro pacote copiado é o próprio keyframe — sem duplicação.
  Risco teórico: se o demuxer não suportar busca precisa, o FFmpeg avisa
  "Using non-accurate seek" e pode incluir um GOP extra.
Fix restante, se houver: nenhum obrigatório.
Gravidade: NENHUMA

--------------------------------------------------------------------------------

7) Status: CORRIGIDO
Finding: caminho rápido caía em reencode completo sem aviso prévio nem motivo.
Evidência no código:
  - Aviso ANTES de iniciar: `cutSelectedMedia` linhas 516-543 — diálogo quando
    `sourceCodec !in setOf("h264","hevc")` ("Este codec exige recodificação
    completa") e quando `jobEncoder.codecFamily != sourceCodec` (com botão
    neutro "Usar <encoder compatível>").
  - Motivo DURANTE a execução: `executeHybridVideoCut` linhas 720-726
    ("Caminho rápido indisponível: codec X não permite cópia híbrida" /
    "encoder escolhido não corresponde ao codec da origem") e linhas 735-739
    ("não há keyframes internos suficientes"), ambos via
    `tracker.appendTasks(...)`.
  - Cobertura total dos cenários do finding: codec fora de h264/hevc, encoder
    divergente e ausência de keyframes.
Argumentos FFmpeg efetivos: inalterados; mudança é de comunicação.
Impacto residual: nenhum.
Fix restante, se houver: nenhum.
Gravidade: NENHUMA

--------------------------------------------------------------------------------

8) Status: CORRIGIDO
Finding: `detectStreamBitrates` fazia parse do log do FFmpeg com
  `contains("Video:")` e caía no bitrate do container quando o parse falhava.
Evidência no código:
  - `detectStreamBitrates(inputFile)` reescrito (linhas 1035-1056): usa
    `MediaExtractor`, percorre as faixas e lê `MediaFormat.KEY_BIT_RATE` por
    faixa (`video` para a primeira `video/`, `audio` para a primeira `audio/`),
    convertendo para `"<kbps>k"`.
  - Falha de parse → `StreamBitrates()` (nulo) → os call sites usam
    `FALLBACK_VIDEO_BITRATE = "15M"` (linha 1806) e
    `FALLBACK_AUDIO_BITRATE = "192k"` (linha 1807). O bitrate do container
    nunca mais é usado como bitrate de stream.
  - Call sites: linha 668 (áudio), linha 694/700 (preciso de vídeo), linha 741
    (híbrido).
Argumentos FFmpeg efetivos: `-b:v <bitrate da faixa de vídeo ou 15M>`;
  `-b:a` vem de `preciseAudioEncoderArguments` com o bitrate da faixa de áudio.
Impacto residual: `KEY_BIT_RATE` é opcional no MediaFormat e volta ausente em
  vários containers (MKV, WebM, FLAC, PCM). Nesses casos cai no fallback fixo
  — comportamento explícito e aceito pelo próprio fix.
Fix restante, se houver: nenhum obrigatório.
Gravidade: NENHUMA

--------------------------------------------------------------------------------

9) Status: CORRIGIDO
Finding: corte preservava áudios/legendas/dados, mas só a primeira faixa de
  vídeo (`-map 0:v:0?`), sem bloqueio nem aviso.
Evidência no código:
  - `cutSelectedMedia` linhas 491-498: `if (selectedMime.startsWith("video/")) {
    val videoTracks = videoTrackCount(uri); if (videoTracks != 1) { ...aborta } }`.
    Mensagens: "O arquivo não possui vídeo." / "O arquivo possui N faixas de
    vídeo. O corte foi bloqueado para não descartar conteúdo."
  - `videoTrackCount(uri)` linhas 633-645 (MediaExtractor).
  - Único ponto de entrada: `buttonCut` → `cutSelectedMedia()` (linha 230).
  - Mapas: linha 697 (preciso), linha 890 (bordas do híbrido) e
    FfmpegMediaPolicies.kt:195 (corpo do híbrido) — todos `0:v:0? 0:a? 0:s? 0:d?`.
Argumentos FFmpeg efetivos (preciso):
  ffmpeg -y -noautorotate [-display_rotation:v:0 <grau>] -ss INI -i IN -t DUR
         -map 0:v:0? -map 0:a? -map 0:s? -map 0:d? -map_metadata 0
         -map_chapters 0 -c copy -c:v <enc> [-b:v X] -avoid_negative_ts make_zero OUT
Impacto residual: ver NOVO-1 (anexos `0:t` não são mapeados).
Fix restante, se houver: ver NOVO-1.
Gravidade: BAIXA (apenas NOVO-1)

--------------------------------------------------------------------------------

10) Status: CORRIGIDO
Finding: branch explícito `m4a`/`aac` removido de `preciseAudioEncoderArguments`;
  as extensões caíam no `else`.
Evidência no código:
  - `FfmpegMediaPolicies.cutAudioEncoderArguments` (FfmpegMediaPolicies.kt:65-74)
    tem o branch explícito: `"m4a", "aac" -> listOf("-c:a", "aac", "-b:a", bitrate)`
    na linha 72, ANTES do `else` (linha 73).
  - `preciseAudioEncoderArguments` (linhas 1002-1008) delega a ele.
  - Teste: `FfmpegMediaPoliciesTest.cutAudioArgumentsPreservePcmDepthAndDeclareCommonContainers`
    (linhas 66-86) cobre `wav`→`pcm_s24le` e `m4a`→`aac -b:a`.
Argumentos FFmpeg efetivos (m4a): `-c:a aac -b:a <bitrate>`
Impacto residual: nenhum.
Fix restante, se houver: nenhum.
Gravidade: NENHUMA

================================================================================
FERRAMENTA DE JUNTAR VÍDEO/ÁUDIO — FfmpegJoinVideosActivity.kt (2349 linhas)
================================================================================

11) Status: PARCIALMENTE CORRIGIDO
Finding: aridade de áudio 1 em todos os caminhos; faixas secundárias e legendas
  descartadas sem aviso de quantas foram perdidas; `profilesCompatible` não
  comparava contagem de faixas.
Evidência no código:
  - MELHORADO — `requestAudioTrack` (linhas 1860-1871) agora informa
    explicitamente a perda no título do diálogo: "Escolha 1 das N faixas de
    <nome>; as demais não entrarão na saída recodificada". Só é exibido quando
    `audioTrackCount(uri) > 1` (linhas 494-499 e 501).
  - MELHORADO — `SmartJoinPlanner.kt` foi DELETADO no commit 52f6253 (com o seu
    teste). A comparação de perfis agora é
    `FfmpegMediaPolicies.directConcatSignaturesCompatible` (:313-321), que
    compara a LISTA INTEIRA de assinaturas — a igualdade de listas implica
    igualdade de contagem de faixas, cobrindo `audioStreamCount`.
  - NÃO FEITO — a aridade continua 1: `concat=n=N:v=0:a=1` (linhas 713 e 724,
    junta de áudio) e `concat=n=N:v=1:a=1` (linhas 872 e 907, reencode de vídeo).
  - `audioInputLabel` (linhas 1836-1840) usa
    `FfmpegMediaPolicies.audioStreamSpecifier(inputIndex, track)` — índice
    escolhido, não fixo 0.
Argumentos FFmpeg efetivos (junta de áudio com reencode, 2 clipes):
  ffmpeg -y -i A -i B -filter_complex
    "[0:a:0]aresample=48000,aformat=...,asetpts=PTS-STARTPTS[a0];
     [1:a:0]aresample=48000,aformat=...,asetpts=PTS-STARTPTS[a1];
     [a0][a1]concat=n=2:v=0:a=1[aout]"
    -map "[aout]" -vn -c:a aac -ar 48000 -ac 2 -b:a 192k
    -avoid_negative_ts make_zero OUT
Impacto residual: faixas secundárias continuam descartadas — agora por decisão
  explicitamente comunicada, não por acidente. Legendas: preservadas no concat
  direto (`-map 0`), removidas com confirmação no reencode.
Fix restante, se houver: opcional — concatenar N faixas quando a contagem
  coincidir entre os clipes (exige filter_complex por faixa). Ver NOVO-3.
Gravidade: BAIXA (comportamento divulgado)

--------------------------------------------------------------------------------

12) Status: PARCIALMENTE CORRIGIDO
Finding: nenhuma rota de reencode tinha `-c:a copy`; áudios idênticos eram
  sempre reencodados; escolha de codec sem validação cruzada nem aviso.
Evidência no código:
  - MELHORADO — `audioEncoderForOutput` (linhas 738-756) escolhe
    `pcm_s16le`/`flac`/`libmp3lame`/`libopus`/`libvorbis`/`aac` pela extensão da
    saída e, na ausência dela, pelo codec detectado
    (`flac`/`mp3`/`opus`/`vorbis`/`ac3`). `audioEncodingArguments`
    (linhas 810-821) preserva opus/vorbis/flac/mp3/ac3 e só força `-b:a` fora
    do FLAC. O perfil vem de `detectAggregateOutputProfile` (linhas 2023-2053),
    que tira o MÁXIMO de taxa, canais e bitrate entre os clipes — não é mais
    16 kHz mono.
  - NÃO FEITO — não existe `-c:a copy`: `joinAudioCommandArguments`
    (FfmpegMediaPolicies.kt:134-149) sempre emite `-c:a <encoder>`;
    `buildReencodeArguments` (linhas 782-794) sempre chama
    `audioEncodingArguments(profile)`.
  - Aviso de conversão: existe como etapa do tracker (`executeFfmpegWithProgress`
    recebe `encoderName`, linhas 672-677) e como rótulo da etapa
    ("Convertendo áudios incompatíveis para WAV no perfil agregado", linha 655),
    mas NÃO como diálogo anterior à execução.
Argumentos FFmpeg efetivos (2 MP3 idênticos, sem reencode, não-normalizado):
  ffmpeg -y -fflags +genpts -f concat -safe 0 -i join_list_<ts>.txt -map 0
         -map_metadata 0 -map_chapters 0 -c copy -avoid_negative_ts make_zero OUT
Argumentos FFmpeg efetivos (com reencode, saída .mp3):
  ... -filter_complex ... -map "[aout]" -vn -c:a libmp3lame -ar 44100 -ac 2
      -b:a 128k -avoid_negative_ts make_zero OUT
Impacto residual: áudios idênticos só escapam do reencode pela rota de concat
  direto (que agora é rigorosamente validada — ver item 13). Quando o reencode
  roda, ele roda porque o usuário pediu ou porque os clipes divergem.
Fix restante, se houver: opcional — quando a rota de normalização for acionada
  apenas por `firstAudioExtension == "mp3"` (linha 539) e as assinaturas forem
  idênticas, permitir concat direto.
Gravidade: BAIXA

--------------------------------------------------------------------------------

13) Status: CORRIGIDO
Finding: pré-validação do concat direto olhava só topologia; `buildDirectConcatArguments`
  era `-map 0 -c copy` puro, sem comparar codec, perfil AAC, sample format,
  extradata, codec tag nem container.
Evidência no código:
  - `directConcatCompatibilityError(inputs)` (linhas 1731-1736) →
    `streamCopySignatures(file)` (linhas 1738-1787) →
    `FfmpegMediaPolicies.directConcatSignaturesCompatible` (:313-321).
  - A assinatura por faixa (FfmpegMediaPolicies.kt:5-28) inclui:
    `containerFamily`, `ffmpegDescriptor`, `mime`, `profile`, `level`,
    `sampleRate`, `channels`, `channelMask`, `pcmEncoding`, `width`, `height`,
    `frameRate`, `colorStandard/Transfer/Range`, `codecTag`, `sampleFormat`,
    `channelLayout`, `timeBase` e hashes de `csd-0/1/2` (extradata).
  - `ffmpegStreamCopyDescriptors` (linhas 1789-1809) extrai a linha de stream do
    próprio FFmpeg com regex ancorada em `Stream #N:M...: (Video|Audio|Subtitle|Data|Attachment):`
    — não é mais `contains("Video:")` — e remove só o trecho de bitrate.
  - `streamCopySignatures` retorna `null` (incompatível) se
    `descriptors.size != extractor.trackCount`.
  - Consumo: linha 569 (vídeo sem reencode → erro e instrução "Ative 'Recodificar'")
    e linha 538 (áudio → força normalização).
  - Teste: `directConcatRequiresExactContainerAndFfmpegStreamContract`
    (linhas 155-175) cobre container divergente, container desconhecido,
    timebase divergente e assinatura nula.
Argumentos FFmpeg efetivos (concat direto autorizado):
  ffmpeg -y -fflags +genpts -f concat -safe 0 -i join_list_<ts>.txt -map 0
         -map_metadata 0 -map_chapters 0 -c copy -avoid_negative_ts make_zero OUT
  (o `join_list_*.txt` é apagado após a execução, linhas 983-984)
Impacto residual: a comparação é intencionalmente estrita — qualquer divergência
  empurra para o reencode. Direção segura; pode recusar concatenações que
  funcionariam (falso positivo), nunca autorizar uma que quebre.
Fix restante, se houver: nenhum obrigatório.
Gravidade: NENHUMA

--------------------------------------------------------------------------------

14) Status: PARCIALMENTE CORRIGIDO
Finding: áudios incompatíveis viravam WAV silenciosamente; sem diálogo, aviso
  ou linha de status.
Evidência no código:
  - `audioNeedsNormalization` (linhas 538-539) e
    `audioWillStandardizeToWav` (linhas 540-541).
  - `buildJoinedOutputName(forceAudioStandardization = ...)` (linhas 1709-1727)
    força extensão `wav` antes de executar — o usuário vê o nome do arquivo.
  - `executeAudioJoin` (linhas 652-660): o rótulo da etapa passa de
    "Juntando áudios sem reencodar" para "Convertendo áudios incompatíveis para
    WAV no perfil agregado" via `renameProcessingStep` (linhas 658-660, 1574-1586),
    renderizado antes da chamada ao FFmpeg. O encoder exibido é `pcm_s16le`.
  - A padronização usa taxa e canais do perfil agregado
    (`detectAggregateOutputProfile`), não mais 16 kHz mono.
  - NÃO FEITO: não há AlertDialog de confirmação antes da conversão — o fix
    aceitava "ou ao menos registrar uma linha de status explícita", alternativa
    que foi implementada.
Argumentos FFmpeg efetivos (padronização para WAV):
  ffmpeg -y -i A -i B -filter_complex "[0:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a0];[1:a:0]...[a1];[a0][a1]concat=n=2:v=0:a=1[aout]"
    -map "[aout]" -vn -c:a pcm_s16le -ar 48000 -ac 2
    -avoid_negative_ts make_zero <nome>.wav
Impacto residual: o usuário é informado, mas não pode recusar a conversão sem
  cancelar a operação inteira. Ver NOVO-3 (texto do diálogo de faixa múltipla
  promete "saída recodificada", mas a saída é WAV).
Fix restante, se houver: opcional — AlertDialog de confirmação, como no modo
  forte do Limpar Áudio.
Gravidade: BAIXA

--------------------------------------------------------------------------------

15) Status: CORRIGIDO
Finding: junta de vídeo rejeitava arquivo com legenda em QUALQUER modo
  (regressão introduzida pela correção anterior).
Evidência no código:
  - `validateSupportedStreamTopology(inputs, audioOnly)` (linhas 1811-1834) NÃO
    tem mais nenhuma checagem de legenda: só exige `videoCount == 1` para vídeo e
    `audioCount >= 1` para áudio. A regra `subtitleCount > 0` saiu do arquivo.
  - O diálogo de legenda (linhas 503-511) está condicionado a
    `!audioOnly && reencodeChecked`: título "As legendas não podem participar das
    transições", botão positivo "Remover e continuar" — exatamente a segunda
    opção do fix.
  - `subtitleTrackCount(uri)` (linhas 1844-1858) só é usado nesse diálogo.
  - O checkbox/linha do Smart Join também saíram de
    `activity_ffmpeg_join_videos.xml`.
Argumentos FFmpeg efetivos (vídeo com legenda, sem reencode):
  ffmpeg -y -fflags +genpts -f concat -safe 0 -i join_list_<ts>.txt -map 0
         -map_metadata 0 -map_chapters 0 -c copy -avoid_negative_ts make_zero OUT.mkv
  → as legendas são preservadas (saída MKV).
Impacto residual: nenhum. MKV com legenda volta a ser unido, e agora preservando
  a legenda no modo sem reencode.
Fix restante, se houver: nenhum.
Gravidade: NENHUMA

--------------------------------------------------------------------------------

16) Status: CORRIGIDO
Finding: Smart Join legado (`executeSmartJoinExperiment`) permanecia no código
  com `-ss` antes de `-i` + `-c:v copy` sem alinhamento a keyframes; rota
  inalcançável por `smartJoinChecked = false`.
Evidência no código:
  - `rg -n "SmartJoin|smartJoin|executeSmartJoinExperiment|buildTransitionArgumentsMkv"`
    em `app/src/` → ZERO ocorrências. `SmartJoinPlanner.kt` e
    `SmartJoinPlannerTest.kt` foram DELETADOS (git diff 85b889f..52f6253:
    SmartJoinPlanner.kt -191, SmartJoinPlannerTest.kt -154).
  - A linha `smart_join_row` (com `check_smart_join` e `help_smart_join`) foi
    removida de `activity_ffmpeg_join_videos.xml`.
  - Não há mais call sites inalcançáveis (`:710`, `:770` do relatório-base).
Argumentos FFmpeg efetivos: nenhum — a rota não existe mais.
Impacto residual: nenhum. Adotada a primeira opção do fix (remover o caminho
  legado). A funcionalidade de junta parcial também foi removida de vez —
  decisão de produto encerrada pelo próprio commit.
Fix restante, se houver: nenhum.
Gravidade: NENHUMA

--------------------------------------------------------------------------------

17) Status: CORRIGIDO
Finding: rótulo "Normalizando pelo primeiro áudio" errado — a normalização usa
  o perfil agregado.
Evidência no código:
  - `rg -n "Normalizando"` em `FfmpegJoinVideosActivity.kt` → ZERO ocorrências.
  - Os rótulos agora são: `initProcessingSteps` (linhas 1514-1531) →
    "Juntando áudios sem reencodar" ou "Aplicando transição de áudio";
    `executeAudioJoin` (linhas 652-657) → "Convertendo áudios incompatíveis para
    WAV no perfil agregado"; `regularVideoProcessingLabels` (linhas 1505-1512) →
    "Juntando sem reencodar" / "Aplicando Fade in/out" / "Aplicando transições".
  - `detectAggregateOutputProfile` (linhas 2023-2053) confirma o comportamento:
    máximo de width, height, fps, videoBitrate, audioSampleRate, audioChannels e
    audioBitrate; o resto vem do primeiro clipe (`first.copy(...)`).
Argumentos FFmpeg efetivos: inalterados; mudança é de texto.
Impacto residual: nenhum.
Fix restante, se houver: nenhum.
Gravidade: NENHUMA

================================================================================
FERRAMENTA DE EXTRAIR ÁUDIO — FfmpegExtractAudioActivity.kt (1736 linhas)
================================================================================

18) Status: PARCIALMENTE CORRIGIDO
Finding: caminho de cópia restrito (sempre false com corte; exige taxa e canais
  iguais); WAV caía em `pcm_s16le`; MP3 com `-b:a`/`-minrate`/`-maxrate` (CBR
  forçado); detecção de WAV dependia só do mime `audio/raw`.
Evidência no código:
  - (b) RESOLVIDO — `detectPcmEncoder(inputFile, audioTrack)` (linhas 972-991)
    lê `KEY_PCM_ENCODING` e devolve `pcm_u8`/`pcm_f32le`/`pcm_s24le`/`pcm_s32le`/
    `pcm_s16le`; `buildFfmpegArguments` (linha 865) repassa a
    `FfmpegMediaPolicies.extractAudioEncoderArguments` (:90-100), branch `wav`
    na linha 92.
  - (mime) RESOLVIDO — `canCopyAudioWithoutConversion` linha 961:
    `"audio/raw", "audio/x-raw", "audio/wav", "audio/x-wav" -> WAV`.
  - (CBR) PARCIAL — `extractAudioEncoderArguments` linha 93:
    `mp3 -> listOf("-c:a","libmp3lame","-b:a",bitrate)`. `-minrate`/`-maxrate`
    saíram (há teste `assertFalse("-minrate" in mp3)`, linha 92), mas não foi
    exposta escolha CBR/VBR: o layout expõe `button_bitrate`,
    `button_sample_rate`, `button_channels` e `button_output_extension`, sem
    controle de modo de taxa.
  - (cópia com corte) NÃO FEITO — linha 945: `if (startMs > 0L || (endMs != null
    && duration > 0L && endMs < duration - 250L)) return false`. Só há cópia na
    extração do arquivo inteiro (com tolerância de 250 ms no fim).
Argumentos FFmpeg efetivos (extração integral de WAV 24-bit → WAV, taxa/canais
  iguais aos ajustes):
  ffmpeg -y -i IN -vn -map 0:a:0 -map_metadata 0 -c:a copy
         -avoid_negative_ts make_zero OUT.wav
Argumentos FFmpeg efetivos (com corte, MP3):
  ffmpeg -y -ss 2.000 -i IN -t 3.000 -vn -map 0:a:1 -map_metadata 0
         -ar 48000 -ac 2 -c:a libmp3lame -b:a 160k -avoid_negative_ts make_zero OUT.mp3
Impacto residual: nenhum comando incorreto. Perde-se apenas a oportunidade de
  cópia com corte em WAV/FLAC e a escolha de VBR.
Fix restante, se houver: (i) liberar cópia com corte quando a origem for
  PCM/FLAC (corte no limite de amostra); (ii) expor CBR/VBR (ou `-q:a`) na UI.
Gravidade: BAIXA

--------------------------------------------------------------------------------

19) Status: PARCIALMENTE CORRIGIDO
Finding: mapa da faixa de áudio sem o operador `?` (defesa em profundidade).
Evidência no código:
  - RESOLVIDO (substância) — `buildFfmpegArguments` linha 873 usa
    `FfmpegMediaPolicies.audioStreamSpecifier(0, audioTrack)`, com `audioTrack`
    vindo do seletor (linhas 743-748, 751, 785). O índice deixou de ser sempre 0.
    Há bloqueio prévio: linhas 739-742 rejeitam arquivo com zero faixas de áudio
    e linhas 743-748 exigem seleção quando há mais de uma.
  - NÃO FEITO (letra do fix) — `audioStreamSpecifier`
    (FfmpegMediaPolicies.kt:62-63) devolve `"$inputIndex:a:${audioTrackIndex.coerceAtLeast(0)}"`
    — sem o `?`. O teste congela esse formato
    (`assertEquals("0:a:0", ...)`, linha 62; `assertEquals("2:a:3", ...)`, linha 63).
Argumentos FFmpeg efetivos: `-map 0:a:<faixa escolhida>`
Impacto residual: nenhum funcional — o índice é validado antes, e
  `coerceAtLeast(0)` impede índice negativo. O `?` só protegeria contra uma
  divergência futura entre a ordem de faixas do MediaExtractor e a do FFmpeg.
Fix restante, se houver: opcional (uma linha) — trocar
  `audioStreamSpecifier` por `"$inputIndex:a:${idx}?"` e atualizar o teste.
Gravidade: BAIXA (endurecimento)

--------------------------------------------------------------------------------

20) Status: MELHORIA OPCIONAL (não é bug)
Finding: OPUS corrigido para `-application audio` e `-vbr on`, mas sem opção de
  perfil de voz (`application=voip` + CBR) na UI.
Evidência no código:
  - `FfmpegMediaPolicies.extractAudioEncoderArguments` linha 97:
    `"opus" -> listOf("-c:a","libopus","-application","audio","-b:a",bitrate,"-vbr","on")`
    — alinhado com Cortar (`cutAudioEncoderArguments` linha 71) e com o padrão
    pedido. Teste em `FfmpegMediaPoliciesTest` linhas 93-95.
  - O layout `activity_ffmpeg_extract_audio.xml` não tem nenhum controle de
    perfil de voz; só `button_bitrate`.
Argumentos FFmpeg efetivos (opus):
  ffmpeg -y [-ss X] -i IN [-t Y] -vn -map 0:a:N -map_metadata 0 -ar 48000 -ac 2
         -c:a libopus -application audio -b:a 96k -vbr on
         -avoid_negative_ts make_zero OUT.opus
Impacto residual: nenhum — o padrão está correto e é o de melhor qualidade
  geral. Falta apenas a opção para quem quer fala/CBR.
Fix restante, se houver: opcional — acrescentar item "Voz (CBR)" que emita
  `-application voip -vbr off`.
Gravidade: NENHUMA (preferência de produto)

================================================================================
FERRAMENTA DE INSERIR ÁUDIO — FfmpegInsertAudioActivity.kt (1017 linhas)
================================================================================

21) Status: CORRIGIDO
Finding: rota legada `executeCopyInsert` usava `-ss` antes de `-i` com `-c copy`
  no trecho direito e concatenava containers completos (`concatPieces`).
Evidência no código:
  - `rg -n "executeCopyInsert|concatPieces|canCopyDirectly|smartInsertViable"` em
    `app/src/` → ZERO ocorrências. A rota foi REMOVIDA (não desabilitada).
  - Só existe `buildFullReencodeArguments` (linhas 567-625), chamado uma única
    vez em `startInsert()` (linha 520).
Argumentos FFmpeg efetivos (sem transição, inserção no meio):
  ffmpeg -y -i PRINCIPAL -i INSERIDO -filter_complex
    "[0:a:0]atrim=start=0:end=10.000,aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a0];
     [1:a:0]atrim=start=0:end=4.000,...,asetpts=PTS-STARTPTS[a1];
     [0:a:0]atrim=start=10.000:end=60.000,...,asetpts=PTS-STARTPTS[a2];
     [a0][a1][a2]concat=n=3:v=0:a=1[aout]"
    -map "[aout]" -vn -c:a aac -b:a 192k -ar 48000 -ac 2 -movflags +faststart
    -avoid_negative_ts make_zero OUT
Impacto residual: nenhum — a inserção é sempre precisa, sem fronteira de pacote.
Fix restante, se houver: nenhum.
Gravidade: NENHUMA

--------------------------------------------------------------------------------

22) Status: CORRIGIDO
Finding: rota legada `executeSmartInsert` recodificava só o áudio inserido e
  concatenava as bordas por cópia sem validar parâmetros.
Evidência no código:
  - `rg -n "executeSmartInsert"` em `app/src/` → ZERO ocorrências. Removida.
  - `grep -n "startInsert"` → único ponto de entrada (linhas 199/475), sem
    dispatcher condicional.
Argumentos FFmpeg efetivos: os do item 21.
Impacto residual: nenhum.
Fix restante, se houver: nenhum.
Gravidade: NENHUMA

--------------------------------------------------------------------------------

23) Status: CORRIGIDO
Finding: `audioInputsAreCopyCompatible` comparava só codec (por `contains`),
  taxa e canais; perfil AAC, sample format e extradata ficavam de fora.
Evidência no código:
  - `rg -n "audioInputsAreCopyCompatible"` em `app/src/` → ZERO ocorrências.
    O validador foi removido junto com a rota de cópia — a primeira opção do fix.
  - `AudioProfile` (linhas 962-968) agora carrega `sampleRate`, `channels`,
    `bitrate`, `codec` e `pcmEncoder`, lidos por `detectAudioProfile`
    (linhas 698-723) com MediaExtractor.
Argumentos FFmpeg efetivos: nenhum validador incorreto remanescente.
Impacto residual: nenhum.
Fix restante, se houver: nenhum.
Gravidade: NENHUMA

--------------------------------------------------------------------------------

24) Status: PARCIALMENTE CORRIGIDO
Finding: seleção de faixa inconsistente entre rotas; rota ativa sem `?`;
  rotas legadas fixavam `0:a:0` e ignoravam a escolha do usuário.
Evidência no código:
  - RESOLVIDO (inconsistência) — as rotas legadas não existem mais; resta uma
    única rota, que honra a seleção nos três pontos do filtro:
    `buildFullReencodeArguments` linhas 586 (`0:a:<mainAudioTrack>`, trecho
    esquerdo), 593 (`1:a:<insertedAudioTrack>`, inserido) e 597
    (`0:a:<mainAudioTrack>`, trecho direito), todos via
    `FfmpegMediaPolicies.audioStreamSpecifier`.
  - Seleção garantida por `startInsert()` linhas 478-487: exige pelo menos uma
    faixa de áudio em cada arquivo e dispara `requestAudioTrack` quando há mais
    de uma.
  - NÃO FEITO (letra do fix) — falta o `?`, pela mesma função compartilhada do
    item 19 (`FfmpegMediaPolicies.audioStreamSpecifier`, :62-63).
Argumentos FFmpeg efetivos: ver item 21 (`[0:a:N]`, `[1:a:M]`).
Impacto residual: nenhum funcional — ver item 19.
Fix restante, se houver: opcional — mesmo ajuste de uma linha do item 19.
Gravidade: BAIXA (endurecimento)

--------------------------------------------------------------------------------

25) Status: CORRIGIDO
Finding: modos "sem reencodar" e "Smart Insert" eliminados, mas a UI não
  comunicava; checkbox marcado e desabilitado, "Smart Insert" oculto.
Evidência no código:
  - `git diff 85b889f..52f6253 -- app/src/main/res/layout/activity_ffmpeg_insert_audio.xml`:
    o `LinearLayout` com `check_reencode` e `check_smart_insert` foi SUBSTITUÍDO
    por um `TextView` com o texto "A inserção usa recodificação precisa para
    respeitar exatamente o ponto e as transições escolhidas."
  - `rg -n "check_reencode|check_smart_insert"` no layout → ZERO ocorrências.
  - O resultado final informa "Modo: Inserção precisa" (`startInsert`, linhas
    531-536).
Argumentos FFmpeg efetivos: inalterados; mudança é de UI.
Impacto residual: nenhum. Adotada a segunda opção do fix (remover os controles
  remanescentes e documentar).
Fix restante, se houver: nenhum.
Gravidade: NENHUMA

================================================================================
FERRAMENTA DE LIMPAR ÁUDIO — FfmpegCleanAudioActivity.kt (594 linhas)
================================================================================

26) Status: PARCIALMENTE CORRIGIDO
Finding: mapa da faixa de áudio sem o `?`; mitigação prévia existe.
Evidência no código:
  - `buildFfmpegArguments` linha 270:
    `audioMap = FfmpegMediaPolicies.audioStreamSpecifier(0, 0)` → `"0:a:0"`,
    sem `?`. O teste congela o formato
    (`FfmpegMediaPoliciesTest.cleanCommandPreservesRequestedPcmProfile`,
    linhas 141-153).
  - Mitigação intacta e eficaz: `cleanSelectedAudio` linhas 177-181 exige
    `inspectAudioSource(uri)?.trackCount == 1` ("O arquivo precisa ter exatamente
    uma faixa de áudio."), e `audioSourceProfile` (linhas 302-319) só devolve
    perfil com `audioFormats.singleOrNull()`.
  - A reclamação sobre WAV 16 kHz mono está corrigida: linhas 311-317 escolhem
    `pcm_u8`/`pcm_f32le`/`pcm_s24le`/`pcm_s32le`/`pcm_s16le` por
    `KEY_PCM_ENCODING`, e `cleanAudioCommandArguments`
    (FfmpegMediaPolicies.kt:172-185) preserva `-ar` e `-ac` da origem.
Argumentos FFmpeg efetivos (origem 96 kHz, 6 canais, 32-bit, modo equilibrado):
  ffmpeg -y -i IN -vn -map 0:a:0 -af afftdn=nf=-25 -c:a pcm_s32le -ar 96000
         -ac 6 -avoid_negative_ts make_zero -f wav OUT.wav
Impacto residual: nenhum funcional — índice 0 sempre válido pela validação
  prévia. Falta só a defesa em profundidade.
Fix restante, se houver: opcional — `"0:a:0?"`.
Gravidade: BAIXA (endurecimento)

================================================================================
PENDÊNCIAS TRANSVERSAIS
================================================================================

27) Status: PARCIALMENTE CORRIGIDO
Finding: não existe política única de legendas; Juntar rejeitava legenda em
  qualquer modo.
Evidência no código:
  - Girar preserva: `FfmpegRotateVideoActivity.kt:1131-1133` (paralelo:
    `0:s? 0:d? 0:t?`) e `:1332` (sequencial: `0:v:0 0:a? 0:s? 0:d? 0:t?`).
  - Cortar preserva: `FfmpegCutActivity.kt:697` e `:890`, e
    FfmpegMediaPolicies.kt:195.
  - Juntar: preserva no concat direto (`-map 0`, FfmpegMediaPolicies.kt:130) e
    pede confirmação antes de descartar no reencode
    (`FfmpegJoinVideosActivity.kt:503-511`, "Remover e continuar"). A rejeição
    indiscriminada saiu (ver item 15).
  - NÃO FEITO — não há política una em `FfmpegMediaPolicies`: cada Activity
    monta os seus próprios `-map`.
  - NOVO-1 (achado desta auditoria): **Cortar descarta anexos (`0:t`)** em todas
    as rotas (`:697`, `:890`, FfmpegMediaPolicies.kt:195 só mapeiam
    `v/a/s/d`), enquanto Girar preserva (`:1133`, `:1332`). Um MKV com legenda
    ASS e fonte embutida perde a fonte ao ser cortado.
Argumentos FFmpeg efetivos (Cortar): `-map 0:v:0? -map 0:a? -map 0:s? -map 0:d?`
Impacto residual: perda silenciosa de fontes/anexos no Cortar; duplicação de
  lógica de mapeamento entre ferramentas.
Fix restante, se houver: (i) acrescentar `-map 0:t?` nas três rotas do Cortar;
  (ii) opcional — centralizar a lista de mapas em `FfmpegMediaPolicies`.
Gravidade: BAIXA (NOVO-1), MÉDIA (governança/duplicação)

--------------------------------------------------------------------------------

28) Status: PARCIALMENTE CORRIGIDO
Finding: nenhum teste cobria os construtores de comando por ferramenta; só
  `FfmpegMediaPoliciesTest` (8 casos) existia.
Evidência no código:
  - AVANÇO GRANDE — os construtores foram extraídos das Activities para o objeto
    puro `FfmpegMediaPolicies` e `FfmpegMediaPoliciesTest.kt` passou de 8 para
    **16 testes** (0 falhas). Hoje há cobertura de:
    `metadataRotationCopyArguments` e `metadataCopyPreflightArguments` (Girar);
    `cutAudioEncoderArguments` e `cutAudioCommandArguments` (Cortar);
    `hybridCopyBodyArguments` (Cortar híbrido, inclusive precisão de µs e
    posição do `-ss`);
    `extractAudioEncoderArguments` e `extractAudioCommandArguments` (Extrair);
    `directConcatCommandArguments` e `joinAudioCommandArguments` (Juntar);
    `insertAudioCommandArguments` (Inserir);
    `cleanAudioCommandArguments` (Limpar);
    `directConcatSignaturesCompatible` (pré-validação do Juntar).
  - NÃO COBERTO — a construção dos grafos de filtro, que permanece privada nas
    Activities: `buildFilterComplex` / `buildFadeInOutFilterComplex`
    (`FfmpegJoinVideosActivity.kt:876-910`, `:823-874`, aridade `v=1:a=1`,
    `xfade`/`acrossfade`); `buildAudioReencodeArguments` (`:685-736`, aridade
    `v=0:a=1`); `buildFullReencodeArguments`
    (`FfmpegInsertAudioActivity.kt:567-625`, `atrim`/`afade`/`acrossfade`).
  - Também não coberto: a lógica de decisão das Activities (fallback do híbrido,
    recusa do concat direto, pré-flight do Girar) e os helpers que dependem de
    MediaExtractor/MediaFormat.
Argumentos FFmpeg efetivos: não aplicável.
Impacto residual: regressões no grafo de filtro ou na lógica de decisão não são
  detectadas por teste.
Fix restante, se houver: extrair os construtores de `filter_complex` para
  funções puras (mesmo padrão) e testá-los.
Gravidade: BAIXA

--------------------------------------------------------------------------------

29) Status: CORRIGIDO
Finding: código morto deixado pelas correções — Inserir (`executeCopyInsert`,
  `executeSmartInsert`, `concatPieces`, `canCopyDirectly`, `smartInsertViable`),
  Juntar (`executeSmartJoinExperiment`, `buildTransitionArgumentsMkv`,
  `detectContainerBitrateKbps`, parâmetro `letterbox`), Extrair
  (`describeAudioFile`, `probeAudioFile`, `estimateBitrate`).
Evidência no código:
  - Varredura por script (regex `private fun X(` × contagem de usos no próprio
    arquivo) nas 6 Activities: **ZERO funções privadas sem chamador**.
  - Varredura dos 23 membros de `FfmpegMediaPolicies`: **ZERO sem chamador**
    fora do próprio arquivo.
  - `rg -n "describeAudioFile|probeAudioFile|estimateBitrate"` em `app/src/` →
    só ocorrências em `GraniteActivity.kt` e `RemoteSttActivity.kt`, que têm
    COPIAS PRIVADAS E UTILIZADAS (nada a ver com o Extrair).
  - `rg -n "SmartJoin|buildTransitionArgumentsMkv|detectContainerBitrateKbps|letterbox"`
    → ZERO.
  - `videoFillFrameFilter(width, height, fps)` (`:912-915`) perdeu o parâmetro
    `letterbox` e é chamado em `:840` e `:881`.
  - `SmartJoinPlanner.kt` e `SmartJoinPlannerTest.kt` deletados.
Argumentos FFmpeg efetivos: não aplicável.
Impacto residual: nenhum.
Fix restante, se houver: nenhum.
Gravidade: NENHUMA

================================================================================
ACHADOS NOVOS (não presentes nos 29)
================================================================================

NOVO-1 — Cortar descarta anexos (`0:t`) em todas as rotas.
  Onde: `FfmpegCutActivity.kt:697` (preciso), `:890` (bordas do híbrido) e
        `FfmpegMediaPolicies.kt:195` (corpo do híbrido).
  Cenário que falha: MKV com legenda ASS + fonte embutida. A legenda é
        preservada, a fonte não — a legenda é renderizada com fonte errada.
  Fix: acrescentar `-map 0:t?` nas três rotas (Girar já faz, `:1133`/`:1332`).
  Gravidade: BAIXA/MÉDIA.

NOVO-2 — Bloqueio por MediaExtractor cria falso negativo.
  Onde: `FfmpegRotateVideoActivity.kt:450-458` + `:499-511`;
        `FfmpegCutActivity.kt:491-498` + `:633-645`.
  Cenário que falha: arquivo que o MediaExtractor não consegue abrir (container
        ou codec fora do suporte da plataforma). `videoTrackCount` devolve 0 e a
        operação aborta com "não possui faixa de vídeo", mesmo sendo um vídeo
        válido que o FFmpeg processaria. Agravante no Cortar: `selectedMime` vem
        de `detectMediaMime` (`:472-487`), que cai em `contentResolver.getType()`
        e pode devolver `video/*` justamente no caso em que o extrator falhou.
  Fix: quando `videoTrackCount` retornar 0 POR EXCEÇÃO (e não por contagem real),
        avisar e permitir seguir, em vez de bloquear.
  Gravidade: BAIXA (raro), mas é uma regressão de alcance introduzida pelos fixes
        2 e 9.

NOVO-3 — Juntar áudio multi-faixa promete uma saída e entrega outra.
  Onde: `FfmpegJoinVideosActivity.kt:501` (`hasSelectedMultitrackAudio`),
        `:538-541` (`audioNeedsNormalization` / `audioWillStandardizeToWav`),
        `:1863` (texto do diálogo de seleção de faixa).
  Cenário: usuário escolhe 1 de N faixas. O diálogo diz "as demais não entrarão
        na saída recodificada" — sugerindo manutenção do formato. Como
        `hasSelectedMultitrackAudio` entra em `audioNeedsNormalization` e
        `checkReencode` está desmarcado, a saída é forçada para **WAV/pcm_s16le**
        (`buildJoinedOutputName` `:1716`, `standardizeToWav = true` `:667`).
        O rótulo da etapa avisa ("...para WAV..."), mas o diálogo e o rótulo se
        contradizem.
  Fix: alinhar o texto do diálogo (mencionar WAV) ou não forçar WAV quando a
        única razão da normalização for a seleção de faixa.
  Gravidade: BAIXA (inconsistência de comunicação).

================================================================================
RESPOSTAS OBJETIVAS
================================================================================
1. Quantos dos 29 findings estão realmente resolvidos?
   18 totalmente corrigidos (1, 2, 3, 4, 6, 7, 8, 9, 10, 13, 15, 16, 17, 21, 22,
   23, 25, 29). Outros 10 estão parcialmente corrigidos (5, 11, 12, 14, 18, 19,
   24, 26, 27, 28) e 1 é melhoria opcional (20). Nenhum item permanece ABERTO,
   nenhum é contraproducente.

2. Quais ainda podem causar operação FFmpeg incorreta?
   Nenhum dos 29, na configuração padrão. Os resíduos afetam qualidade
   (sem CBR/VBR nem bitrate de áudio expostos em Cortar/Extrair/Extrair-OPUS),
   oportunidade de otimização (sem `-c:a copy` com corte) ou defesa em
   profundidade (falta do `?` em Extrair/Inserir/Limpar — sem impacto porque o
   índice é validado antes). O único efeito colateral funcional encontrado é o
   NOVO-1: o Cortar descarta anexos/fontes embutidos.

3. Quais são apenas melhorias ou preferências de produto?
   20 (perfil de voz OPUS); 5c e 18c (expor CBR/VBR e qualidade de saída);
   12 (desejo de `-c:a copy` em rota de reencode, que por definição reencoda);
   11 (concatenar múltiplas faixas em vez de descartar com aviso);
   14 (confirmar por diálogo a conversão para WAV, já comunicada por status);
   19/24/26 (operador `?`); 28 (testes dos grafos de filtro);
   27 (centralizar a política de mapas).

4. Existe algum finding novo introduzido pelas alterações?
   Sim, três: NOVO-1 (Cortar perde anexos `0:t`), NOVO-2 (bloqueio por
   MediaExtractor pode abortar arquivo válido que o FFmpeg abriria) e NOVO-3
   (Juntar promete "saída recodificada" e entrega WAV quando há múltiplas faixas
   de áudio). Nenhum produz arquivo corrompido; são perda de conteúdo e
   inconsistência de comunicação.

5. A conclusão é baseada somente em análise estática ou também em testes reais?
   Em análise estática (leitura integral, `rg`, `git diff 85b889f..52f6253`) MAIS
   execução da suíte unitária:
     ./gradlew.bat :app:testDebugUnitTest --tests "br.gov.sp.pcsp.launcher.Ffmpeg*" --rerun-tasks
     → BUILD SUCCESSFUL; FfmpegMediaPoliciesTest: 16 tests, 0 failures.
   Não houve execução com arquivos de mídia reais (sem binário FFmpeg Android,
   dispositivo ou emulador neste ambiente), portanto nenhuma das afirmações sobre
   comportamento em runtime do FFmpeg (duração, sincronia, rotação efetiva) foi
   validada empiricamente.
================================================================================
