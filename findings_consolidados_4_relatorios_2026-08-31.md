================================================================================
LISTA ÚNICA DE FINDINGS — FUSÃO DE 4 RELATÓRIOS (SEM DUPLICIDADE)
Ferramentas FFmpeg — SIG Android
================================================================================
Data:          2026-08-31
HEAD auditado: 52f6253 ("fix: validar compatibilidade e remover legado ffmpeg")
Base:          85b889f

Fontes fundidas:
  F1 = relatório do usuário (7 itens: 5, 12, 18, 19, 24, 26, 27)
  F2 = relatório do usuário (8 itens: 1, 3, 5, 11, 13, 18, 27, 29)
  F3 = relatório do usuário (4 itens: 5, 7, 8, 16)
  F4 = auditoria independente (10 parciais: 5,11,12,14,18,19,24,26,27,28
       + item 20 + NOVO-1, NOVO-2, NOVO-3)

Regra de fusão:
  - Uma entrada por finding, identificada pelo número original.
  - Quando dois relatórios descrevem o mesmo defeito, as evidências são
    SOMADAS e as divergências de classificação ficam registradas.
  - Achados novos que descrevem o mesmo fato de um item numerado foram
    DOBRADOS DENTRO dele (NOVO-1 → item 27; NOVO-3 → item 14).
  - Achado novo sem correspondência (NOVO-2) entra como NOVO-A.

Total: 19 entradas — 18 findings numerados + 1 achado novo.

REGISTRO DE TESTE:
  ./gradlew.bat :app:testDebugUnitTest --tests "br.gov.sp.pcsp.launcher.Ffmpeg*"
      --rerun-tasks --console=plain
  BUILD SUCCESSFUL in 39s — 26 actionable tasks: 26 executed
  FfmpegMediaPoliciesTest: tests="16" skipped="0" failures="0" errors="0"

================================================================================
RESUMO — 19 ENTRADAS
================================================================================

#   Item   Ferramenta   Status                 Gravidade  Fontes
--  -----  -----------  ---------------------  ---------  ------------
1    1     Girar        PARCIALMENTE CORRIGIDO MÉDIA      F2
2    3     Girar        PARCIALMENTE CORRIGIDO BAIXA      F2
3    5     Cortar       PARCIALMENTE CORRIGIDO MÉDIA      F1 F2 F3 F4
4    7     Cortar       ABERTO                 MÉDIA      F3
5    8     Cortar       ABERTO                 MÉDIA      F3
6   11     Juntar       PARCIALMENTE CORRIGIDO MÉDIA      F2 F4
7   12     Juntar       PARCIALMENTE CORRIGIDO MÉDIA      F1 F4
8   13     Juntar       PARCIALMENTE CORRIGIDO MÉDIA      F2
9   14     Juntar       PARCIALMENTE CORRIGIDO BAIXA      F4 (+NOVO-3)
10  16     Juntar       MELHORIA OPCIONAL      BAIXA      F3
11  18     Extrair      PARCIALMENTE CORRIGIDO ALTA       F1 F2 F4
12  19     Extrair      ABERTO (mitigado)      BAIXA      F1 F4
13  20     Extrair      MELHORIA OPCIONAL      BAIXA      F4
14  24     Inserir      PARCIALMENTE CORRIGIDO BAIXA      F1 F4
15  26     Limpar       ABERTO (mitigado)      BAIXA      F1 F4
16  27     Transversal  PARCIALMENTE CORRIGIDO MÉDIA      F1 F2 F4 (+NOVO-1)
17  28     Transversal  PARCIALMENTE CORRIGIDO BAIXA      F4
18  29     Transversal  ABERTO                 BAIXA      F2
19  NOVO-A Transversal  ABERTO                 BAIXA      F4

Por status:
  PARCIALMENTE CORRIGIDO .. 11  (1, 3, 5, 11, 12, 13, 14, 18, 24, 27, 28)
  ABERTO ................... 5  (7, 8, 19, 26, 29, NOVO-A)
  MELHORIA OPCIONAL ........ 2  (16, 20)

Por gravidade:
  ALTA (1) .... 18   — Extrair omite `-t` e pode exportar o arquivo inteiro
  MÉDIA (8) ... 1, 5, 7, 8, 11, 12, 13, 27
  BAIXA (10) .. 3, 14, 16, 19, 20, 24, 26, 28, 29, NOVO-A

================================================================================
CORREÇÕES DE CLASSIFICAÇÃO DECORRENTES DA FUSÃO
================================================================================

A fusão obrigou a REVISAR quatro itens que a auditoria independente (F4) havia
classificado como CORRIGIDOS. Verificação no código atual, com resultado:

  #7  CORRIGIDO (F4) -> ABERTO
      F3 alegava "fallback para reencode completo sem aviso prévio".
      VERIFICADO: executeHybridVideoCut chama executeFullPrecisionFallback em
      dois pontos (FfmpegCutActivity.kt:725 e :738) sem nenhum diálogo. Só há
      uma linha de tracker ("Caminho rápido indisponível: $reason"), que é
      posterior à decisão e não pede consentimento. F3 está correto.

  #8  CORRIGIDO (F4) -> ABERTO
      F3 alegava "fallback fixo 15M quando KEY_BIT_RATE ausente".
      VERIFICADO: FALLBACK_VIDEO_BITRATE = "15M" (FfmpegCutActivity.kt:1806),
      usado em :700 e :893 quando detectStreamBitrates devolve video == null.
      detectStreamBitrates (:1035-1056) depende de MediaFormat.KEY_BIT_RATE,
      ausente em muitos containers. F3 está correto.

  #13 CORRIGIDO (F4) -> PARCIALMENTE CORRIGIDO
      F2 alegava que a validação do concat direto continua derivada da extensão.
      VERIFICADO: containerFamily(file.name) em FfmpegJoinVideosActivity.kt:1744
      e FfmpegMediaPolicies.containerFamily (:299-311) leem só a extensão.
      F2 está correto.

  #29 CORRIGIDO (F4) -> ABERTO
      F2 alegava código morto residual.
      VERIFICADO: videoBitstreamFilter é atribuído em :1950, :1989, :2010,
      declarado em :2276, e videoBitstreamFilterFor definido em :2181 —
      e NUNCA LIDO. F2 está correto.

Divergência NÃO aceita:

  #5  F3 classificava como ABERTO ("sem -c:a copy").
      VERIFICADO: existe `-c:a copy` em FfmpegCutActivity.kt:668-675,
      condicionado a !hasRealTrim, e detectPcmEncoder em :1010-1029 preserva a
      profundidade PCM. A afirmação literal de F3 é falsa no HEAD 52f6253.
      Mantido como PARCIALMENTE CORRIGIDO (falta cópia com corte e UI de
      qualidade), não como ABERTO.

Divergências de gravidade, com a adotada e o motivo:

  #1  F2=ALTA  -> adotada MÉDIA. A falha do muxer aparece como erro visível do
      FFmpeg; não há corrupção silenciosa.
  #3  F2=MÉDIA -> adotada BAIXA. É estado de UI; o pedido ignorado não gera
      saída errada (canUseParallel exclui metadataOnly em :521).
  #11 F2=ALTA  -> adotada MÉDIA. A perda das faixas é anunciada em diálogo
      antes da execução; não é silenciosa.
  #18 F1/F2=MÉDIA, F4=BAIXA -> adotada ALTA. Verificado que `hasEndTrim`
      (FfmpegExtractAudioActivity.kt:860) e `canCopyAudioWithoutConversion`
      (:945) usam a mesma tolerância de 250 ms, e que `duration` fica null
      quando ela casa — o comando sai SEM `-t` e exporta o arquivo INTEIRO em
      vez do trecho pedido. É saída com conteúdo errado, não perda de
      qualidade.

================================================================================
1) ITEM 1 — GIRAR VÍDEO — validação de container no modo por metadados
================================================================================
Status: PARCIALMENTE CORRIGIDO
Fontes: F2
Finding: o modo por metadados pode falhar ao copiar streams incompatíveis para
  o container escolhido. O preflight é uma checagem parcial, não uma validação
  do muxer real.
Evidência no código:
  - Aviso de extensão desconhecida: FfmpegRotateVideoActivity.kt:462-464
    (Toast "Container não reconhecido: a cópia será salva em MKV.").
  - Preflight: FfmpegRotateVideoActivity.kt:574-607, que executa
    FfmpegMediaPolicies.metadataCopyPreflightArguments
    (FfmpegMediaPolicies.kt:48-60) — acrescenta -hide_banner -loglevel error
    e -t 0.001.
  - LIMITAÇÃO: FfmpegMediaPolicies.safeContainerExtension (:294-297) considera
    SOMENTE a extensão:
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it in setOf("mp4","m4v","mov","mkv","webm","avi") } ?: "mkv"
Argumentos FFmpeg efetivos:
  -y -display_rotation:v:0 <rotação> -i <entrada> -map 0 -c copy <saída>
  preflight: mesmo comando + -hide_banner -loglevel error -t 0.001
Impacto residual: arquivo com extensão incorreta, ou com stream que o muxer de
  destino não aceite, pode passar pelo preflight de 1 ms e falhar na cópia
  completa. A falha é visível, não silenciosa.
Fix restante: consultar o container real do arquivo e validar codecs, legendas,
  attachments e demais streams contra o container de saída.
Divergência: F2=ALTA; adotada MÉDIA (erro visível, sem corrupção silenciosa).
Gravidade: MÉDIA

================================================================================
2) ITEM 3 — GIRAR VÍDEO — checkbox de paralelismo reabilitado após o job
================================================================================
Status: PARCIALMENTE CORRIGIDO
Fontes: F2
Finding: o paralelismo é desativado quando o modo por metadados está ativo, mas
  volta a ficar habilitado incondicionalmente ao término do processamento.
Evidência no código:
  - canUseParallel exclui metadataOnly: FfmpegRotateVideoActivity.kt:521
  - updateMetadataModeState desabilita: :1462
        parallelKeyframes.isEnabled = !metadataOnly
    e aplica alpha 0.42f (:1465).
  - MAS setProcessing(false) reabilita sem consultar metadataOnly: :1563
        parallelKeyframes.isEnabled = !processing
        inputParallelSegments.isEnabled = !processing
    (com processing == false, ambos ficam true.)
Argumentos FFmpeg efetivos: com metadataOnly verdadeiro a rota paralela nunca
  é executada; o comando final usa -map 0 -c copy.
Impacto residual: após uma operação, o checkbox reaparece habilitado (alpha
  0.42 desatualizado) com o modo por metadados ativo. O usuário pode marcá-lo e
  o pedido é ignorado sem aviso específico. Re-sincroniza se o usuário alternar
  o checkbox de metadados.
Fix restante: reaplicar updateMetadataModeState() ao fim do processamento, ou
  avisar quando requestedParallel && metadataOnly.
Divergência: F2=MÉDIA; adotada BAIXA (não altera a saída).
Gravidade: BAIXA

================================================================================
3) ITEM 5 — CORTAR ÁUDIO/VÍDEO — reencode no corte real; sem ajustes de áudio
================================================================================
Status: PARCIALMENTE CORRIGIDO
Fontes: F1, F2, F3, F4
Finding: o corte de áudio com intervalo real sempre reencoda; MP3 sai em CBR;
  não há controle de qualidade/CBR-VBR na UI. Profundidade PCM e cópia sem
  corte estão corrigidas.
Evidência no código:
  - CÓPIA SÓ SEM CORTE REAL: FfmpegCutActivity.kt:668-675
        val hasRealTrim = startMs > 0L ||
            (inputDurationMs > 0L && endMs < inputDurationMs - 10L)
        val encoderArguments = if (!hasRealTrim) listOf("-c:a", "copy") else ...
  - Encoder sempre escolhido com corte:
    FfmpegMediaPolicies.cutAudioEncoderArguments (:65-74); MP3 em CBR
    ("-c:a","libmp3lame","-b:a",bitrate) — FfmpegMediaPolicies.kt:72.
  - Profundidade PCM CORRETA: detectPcmEncoder (:1010-1029) lê
    MediaFormat.KEY_PCM_ENCODING → pcm_u8 / pcm_f32le / pcm_s24le / pcm_s32le /
    pcm_s16le.
  - Sem UI de áudio: activity_ffmpeg_cut.xml só tem button_video_encoder e
    button_video_quality; showEditingControls (:1339-1344) mostra encoder/
    qualidade apenas quando selectedMime.startsWith("video/").
  - FALLBACK_AUDIO_BITRATE = "192k" (:1807) quando KEY_BIT_RATE está ausente
    (comum em WAV/FLAC/PCM).
  - (F2) Um fim até 10 ms antes da duração é tratado como "sem corte" e usa
    cópia por pacote, sem garantir limite exato; o tracker ainda pode dizer
    "Recodificando áudio" mesmo com -c:a copy.
Argumentos FFmpeg efetivos (com corte):
  -y -ss <s> -i <in> -t <s> -map 0:a? -map_metadata 0 -map_chapters 0 -vn
     -c:a pcm_s24le|aac|libmp3lame -b:a <k> -avoid_negative_ts make_zero <out>
Argumentos FFmpeg efetivos (sem corte real):
  -y -ss 0.000 -i <in> -t <duração> -map 0:a? -map_metadata 0 -map_chapters 0
     -vn -c:a copy -avoid_negative_ts make_zero <out>
Impacto residual: reencode desnecessário em WAV/FLAC, MP3 sempre CBR, qualidade
  fixa quando o MediaFormat não informa bitrate. Nenhum comando incorreto.
Fix restante: (a) -c:a copy quando codec+container+intervalo forem compatíveis
  (containers de taxa constante: WAV/FLAC), alinhando ao limite de amostra;
  (b) expor CBR/VBR e qualidade na UI; (c) diferenciar "fim igual à duração" de
  "fim próximo da duração" (limiar de 10 ms em :670).
Divergência: F4=BAIXA; F1/F2=MÉDIA → adotada MÉDIA (consenso de três fontes).
Gravidade: MÉDIA

================================================================================
4) ITEM 7 — CORTAR VÍDEO — fallback para reencode completo sem aviso prévio
================================================================================
Status: ABERTO
Fontes: F3
Finding: quando o caminho híbrido não é viável (codec fora de h264/hevc,
  encoder divergente, ou ausência de keyframes internos no intervalo), o Cortar
  cai para reencode completo sem consultar o usuário — operação muito mais
  lenta e com perda de qualidade.
Evidência no código (VERIFICADO NA FUSÃO):
  - FfmpegCutActivity.kt:720-725 —
        if (sourceCodec !in setOf("h264","hevc") || actualEncoder.codecFamily != sourceCodec) {
            ... tracker.appendTasks(listOf("Caminho rápido indisponível: $reason"))
            return executeFullPrecisionFallback(...)
        }
  - FfmpegCutActivity.kt:736-738 —
        if (startKeyframe == null || endKeyframe == null || startKeyframe >= endKeyframe) {
            ... tracker.appendTasks(listOf("Caminho rápido indisponível: não há keyframes internos suficientes"))
            return executeFullPrecisionFallback(...)
        }
  - executeFullPrecisionFallback definido em :835-875.
  - Nenhum AlertDialog precede as duas chamadas: só uma linha de tracker,
    emitida DEPOIS da decisão.
Argumentos FFmpeg efetivos (fallback):
  -y -noautorotate [-display_rotation:v:0 G] -ss <ini> -i <in> -t <dur>
     -map 0:v:0? -map 0:a? -map 0:s? -map 0:d? -map_metadata 0 -map_chapters 0
     -c:v <enc> <args de qualidade> -avoid_negative_ts make_zero <out>
Impacto residual: o usuário pede corte rápido e recebe reencode completo, sem
  consentimento e sem estimativa. Em vídeo longo é minutos a mais de
  processamento e perda geracional de qualidade.
Fix restante: diálogo antes do fallback informando o motivo e o custo
  estimado, com opção de cancelar; ou ao menos registrar a decisão de forma
  persistente no resultado final.
Gravidade: MÉDIA

================================================================================
5) ITEM 8 — CORTAR VÍDEO — bitrate de vídeo fixo em 15M quando não detectado
================================================================================
Status: ABERTO
Fontes: F3
Finding: quando MediaFormat não informa KEY_BIT_RATE (frequente em MKV, WebM e
  em arquivos remuxados), o Cortar usa o bitrate fixo de 15M em vez de estimar
  pela resolução/taxa de quadros ou de perguntar.
Evidência no código (VERIFICADO NA FUSÃO):
  - FfmpegCutActivity.kt:1806 — private const val FALLBACK_VIDEO_BITRATE = "15M"
  - Uso em :700 (modo preciso):
        args.addAll(videoEncodingArguments(enc, streamBitrates.video ?: FALLBACK_VIDEO_BITRATE, quality))
  - Uso em :893 (fallback de precisão):
        args += videoEncodingArguments(encoder, bitrates.video ?: FALLBACK_VIDEO_BITRATE, quality)
  - Origem do null: detectStreamBitrates (:1035-1056) faz
        runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()
            ?.takeIf { it > 0 }?.let { "${(it / 1000).coerceAtLeast(1)}k" }
    e devolve StreamBitrates() vazio no catch.
Argumentos FFmpeg efetivos:
  -c:v <enc> -b:v 15M -maxrate ... -bufsize ... (conforme FfmpegVideoQuality)
Impacto residual: um clipe de 480p sem KEY_BIT_RATE é reencodado a 15 Mbps —
  arquivo muito maior que a origem, ou falha do encoder em dispositivo com
  limite de nível. Oposto (vídeo 4K de 60 Mbps) fica sub-bitratado.
Fix restante: estimar o bitrate por resolução×fps×fator de codec quando a
  detecção falhar, ou expor o campo de qualidade para o usuário corrigir.
Gravidade: MÉDIA

================================================================================
6) ITEM 11 — JUNTAR VÍDEO/ÁUDIO — sempre uma faixa de áudio nas rotas filtradas
================================================================================
Status: PARCIALMENTE CORRIGIDO
Fontes: F2, F4
Finding: as rotas recodificadas produzem uma única faixa de áudio e descartam
  faixas secundárias, legendas, dados e anexos. A perda é anunciada, mas não há
  caminho que preserve as faixas.
Evidência no código:
  - Aridade 1 nos quatro pontos: FfmpegJoinVideosActivity.kt:713 e :724
    ("concat=n=N:v=0:a=1[aout]"), :872 e :907 ("concat=n=N:v=1:a=1[vout][aout]").
  - Anúncio: requestAudioTrack :1860-1871, título "Escolha 1 das ${labels.size}
    faixas de ${clip.name}; as demais não entrarão na saída recodificada".
  - (F2) Junções de vídeo com múltiplas faixas são bloqueadas no caminho direto
    em :569.
  - Índice escolhido é honrado: audioInputLabel (:1836-1840) usa
    FfmpegMediaPolicies.audioStreamSpecifier(inputIndex, track).
Argumentos FFmpeg efetivos (caminho filtrado):
  -filter_complex "...concat=n=2:v=0:a=1[aout]" -map [aout] -vn -c:a <enc>
Argumentos FFmpeg efetivos (caminho direto):
  -y -fflags +genpts -f concat -safe 0 -i join_list_<ts>.txt -map 0
     -map_metadata 0 -map_chapters 0 -c copy -avoid_negative_ts make_zero <out>
  (join_list_*.txt apagado em :983-984)
Impacto residual: em qualquer reencode ou normalização forçada, só a faixa
  escolhida é exportada; legendas são descartadas com diálogo (:503-511), mas
  dados e anexos sem aviso.
Fix restante: preservar todas as faixas compatíveis (um filter_complex por
  faixa quando a contagem coincidir entre os clipes), e informar QUANTAS serão
  descartadas; descartar dados/anexos com o mesmo aviso dado às legendas.
Divergência: F2=ALTA, F4=BAIXA → adotada MÉDIA (perda anunciada, mas real).
Gravidade: MÉDIA

================================================================================
7) ITEM 12 — JUNTAR ÁUDIO — reencode sem -c:a copy e sem aviso de codec
================================================================================
Status: PARCIALMENTE CORRIGIDO
Fontes: F1, F4
Finding: a rota de reencode nunca emite -c:a copy e não informa o codec de
  destino antes de executar. Áudios idênticos são recodificados com perda.
Evidência no código:
  - FfmpegMediaPolicies.joinAudioCommandArguments (:134-149) sempre emite
    -c:a <encoder>; buildAudioReencodeArguments
    (FfmpegJoinVideosActivity.kt:685-736) e audioEncodingArguments (:810-821)
    sempre escolhem encoder.
  - Melhorias já presentes: audioEncoderForOutput (:738-756) deixou de ser
    sempre aac; audioEncodingArguments (:810-821) preserva opus/vorbis/flac/
    mp3/ac3; detectAggregateOutputProfile (:2023-2053) tira o MÁXIMO de taxa,
    canais e bitrate (não mais 16 kHz mono).
  - Aviso existe para a conversão forçada a WAV (:655-660), mas NÃO para o
    reencode voluntário de áudios idênticos.
Argumentos FFmpeg efetivos:
  -filter_complex "...concat..." -map [aout] -vn -c:a <enc> -ar <r> -ac <c> -b:a <k>
Impacto residual: reencode com perda em AAC/MP3/Opus/Vorbis quando os clipes já
  são idênticos e o usuário queria apenas transição ou corte de tempo.
Fix restante: quando codec, taxa, canais e sample format forem idênticos entre
  todos os clipes, emitir -c:a copy; informar codec e perfil de destino antes
  de executar o reencode manual.
Divergência: F1=MÉDIA, F4=BAIXA → adotada MÉDIA.
Gravidade: MÉDIA

================================================================================
8) ITEM 13 — JUNTAR VÍDEO — validação do concat direto derivada da extensão
================================================================================
Status: PARCIALMENTE CORRIGIDO
Fontes: F2
Finding: a validação do concat direto compara 23 campos internos, mas o
  container continua sendo deduzido da extensão do arquivo, não do muxer real.
Evidência no código (VERIFICADO NA FUSÃO):
  - FfmpegJoinVideosActivity.kt:1744 —
        val containerFamily = FfmpegMediaPolicies.containerFamily(file.name)
  - FfmpegMediaPolicies.containerFamily (:299-311) —
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp4","m4v","mov" -> "mov" ; ... else -> "unknown" }
  - Comparação exige igualdade total da lista de assinaturas:
    FfmpegMediaPolicies.directConcatSignaturesCompatible (:313-321), que
    rejeita containerFamily == "unknown", ffmpegDescriptor vazio ou mime vazio.
  - A assinatura inclui codec, perfil, nível, resolução, taxa, canais, sample
    format, channel layout, time base, CSD e outros (:1757-1758).
  - A descrição de streams depende de regex sobre a saída do FFmpeg
    (FfmpegJoinVideosActivity.kt:1793).
Argumentos FFmpeg efetivos:
  -y -fflags +genpts -f concat -safe 0 -i <lista> -map 0 -map_metadata 0
     -map_chapters 0 -c copy -avoid_negative_ts make_zero <out>
Impacto residual: dois arquivos com a MESMA extensão incorreta recebem o mesmo
  containerFamily e podem ser considerados compatíveis. A proteção de fato vem
  do ffmpegDescriptor (que reflete o conteúdo real), então o furo é estreito —
  mas existe e não depende do muxer.
Fix restante: consultar o formato real via FFmpeg/ffprobe e rejeitar qualquer
  concat cuja compatibilidade a nível de muxer não possa ser comprovada.
Divergência: F2=ALTA; adotada MÉDIA (a checagem de 23 campos cobre a maior
  parte do risco; o furo é o container, não o conteúdo).
Gravidade: MÉDIA

================================================================================
9) ITEM 14 — JUNTAR ÁUDIO — conversão silenciosa para WAV (+ NOVO-3 dobrado)
================================================================================
Status: PARCIALMENTE CORRIGIDO
Fontes: F4 (+ NOVO-3 da F4, dobrado aqui por descrever o mesmo fato)
Finding: áudios incompatíveis viram WAV sem confirmação. A padronização deixou
  de ser 16 kHz mono, mas nenhum diálogo permite aceitar ou recusar a conversão
  — só cancelar a operação inteira. Quando o gatilho é áudio multi-faixa, o
  diálogo de seleção ainda promete "saída recodificada", contradizendo o WAV.
Evidência no código:
  - Decisão: FfmpegJoinVideosActivity.kt:501
        val hasSelectedMultitrackAudio = clips.any { audioTrackCount(it.uri) > 1 }
    e :538-541
        val audioNeedsNormalization = audioOnly &&
            (directConcatIncompatibility != null || firstAudioExtension == "mp3"
             || hasSelectedMultitrackAudio)
        val audioWillStandardizeToWav = audioNeedsNormalization && !reencodeChecked
  - Nome forçado ANTES de executar: :542 → buildJoinedOutputName(
    forceAudioStandardization = audioWillStandardizeToWav), que devolve
    extensão "wav" em :1716.
  - Linha de status honesta mas tardia: executeAudioJoin :652-660
    ("Convertendo áudios incompatíveis para WAV no perfil agregado"),
    renderizada por renameProcessingStep (:1574-1586) já durante a execução.
  - NOVO-3: o diálogo de :1863 diz "as demais não entrarão na saída
    recodificada" — "recodificada" não implica WAV, mas é exatamente o que
    acontece quando hasSelectedMultitrackAudio força a normalização.
Argumentos FFmpeg efetivos:
  -y -i A -i B -filter_complex
     "[0:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a0];
      [1:a:0]...[a1]; [a0][a1]concat=n=2:v=0:a=1[aout]"
     -map [aout] -vn -c:a pcm_s16le -ar 48000 -ac 2
     -avoid_negative_ts make_zero <nome>.wav
Impacto residual: o usuário é informado pela etapa, mas não pode recusar a
  conversão sem cancelar tudo. A contradição do diálogo de multi-faixa é o
  ponto mais confuso.
Fix restante: AlertDialog de confirmação antes da conversão (padrão do modo
  forte do Limpar Áudio, FfmpegCleanAudioActivity.kt:182-194) e alinhamento do
  texto de :1863 quando a saída for WAV.
Gravidade: BAIXA

================================================================================
10) ITEM 16 — JUNTAR VÍDEO — join parcial
================================================================================
Status: MELHORIA OPCIONAL
Fontes: F3
Finding: o Smart Join legado foi removido e o bug associado foi eliminado. O
  que resta é uma decisão de produto: oferecer ou não junção parcial de clipes.
Evidência no código (VERIFICADO NA FUSÃO):
  - rg -n "smartJoin|SmartJoin|smart_join" app/src/ → ZERO ocorrências.
  - SmartJoinPlanner.kt e SmartJoinPlannerTest.kt deletados em 52f6253.
  - activity_ffmpeg_join_videos.xml: o LinearLayout smart_join_row (com
    check_smart_join e help_smart_join) foi removido.
Impacto residual: nenhum. Não há resíduo funcional do Smart Join.
Fix restante: nenhum obrigatório. Se o produto quiser junção parcial, é
  funcionalidade nova, não correção.
Gravidade: BAIXA

================================================================================
11) ITEM 18 — EXTRAIR ÁUDIO — o comando pode SAIR SEM -t e exportar o arquivo
              inteiro; cópia negada com corte; MP3 sempre CBR
================================================================================
Status: PARCIALMENTE CORRIGIDO
Fontes: F1, F2, F4
Finding: TRÊS defeitos no mesmo item. (a) O mais grave: a tolerância de 250 ms
  no fim do intervalo faz o comando sair SEM -t, exportando o arquivo INTEIRO
  em vez do trecho pedido. (b) A cópia sem perdas é negada com qualquer corte
  real. (c) MP3 sai sempre em CBR, sem escolha na UI.
Evidência no código (VERIFICADO NA FUSÃO):
  (a) TOLERÂNCIA DE 250 ms — dois pontos com a MESMA condição:
      FfmpegExtractAudioActivity.kt:860 (buildFfmpegArguments):
          val hasEndTrim = endMs != null && inputDuration > 0L &&
                           endMs < inputDuration - 250L
          ...
          duration = if (hasEndTrim) formatSeconds(endMs - startMs) else null
      FfmpegExtractAudioActivity.kt:945 (canCopyAudioWithoutConversion):
          if (startMs > 0L || (endMs != null && duration > 0L &&
              endMs < duration - 250L)) return false
      Consequência: duração 10.000 ms, usuário escolhe fim em 9.900 ms →
      9900 < 9750 é FALSO → hasEndTrim = false → duration = null → o comando
      NÃO recebe -t. Com início em 0, sai o arquivo inteiro. Com início > 0,
      sai do início escolhido até o fim original. E como a cópia é liberada
      (canCopyAudioWithoutConversion devolve true), sai com -c:a copy.
  (b) Cópia só na extração integral, e ainda exigindo
      sourceRate == settings.sampleRate && sourceChannels == settings.channels
      (:964). Mimes WAV ampliados (:961: audio/raw, audio/x-raw, audio/wav,
      audio/x-wav) e profundidade PCM corrigida (detectPcmEncoder :972-991).
  (c) MP3 em CBR: FfmpegMediaPolicies.kt:93
          "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", bitrate)
      (F1) O teste extractionUsesVbrCapableMp3AndAudioOpusProfile
      (FfmpegMediaPoliciesTest:88-108) tem NOME ASPIRACIONAL: os argumentos
      reais são CBR. O teste afirma apenas a ausência de -minrate/-maxrate.
Argumentos FFmpeg efetivos (cópia integral):
  -y -i <in> -vn -map 0:a:N -map_metadata 0 -c:a copy
     -avoid_negative_ts make_zero <out>
Argumentos FFmpeg efetivos (COM O DEFEITO — fim a 100 ms do final):
  -y -i <in> -vn -map 0:a:N -map_metadata 0 -c:a copy
     -avoid_negative_ts make_zero <out>
     ^^^ SEM -t: exporta o arquivo inteiro, não os 9,9 s pedidos
Argumentos FFmpeg efetivos (reencode com corte):
  -y -ss <s> -i <in> -t <s> -vn -map 0:a:N -map_metadata 0 -ar <r> -ac <c>
     -c:a libmp3lame -b:a <k> -avoid_negative_ts make_zero <out>
Impacto residual: o defeito (a) produz SAÍDA COM CONTEÚDO ERRADO — o usuário
  pede um trecho e recebe o arquivo completo, sem nenhum aviso e sem erro de
  execução. É o único item da lista fundida com esse tipo de falha.
Fix restante: (i) emitir -t SEMPRE que o usuário escolher um fim real, e
  remover a tolerância de 250 ms (ou reduzi-la a um limiar de arredondamento
  que NÃO desligue o -t); (ii) permitir cópia com corte em WAV/FLAC;
  (iii) expor CBR/VBR; (iv) renomear o teste que mente.
Divergência: F1/F2=MÉDIA, F4=BAIXA → adotada ALTA (saída com conteúdo errado).
Gravidade: ALTA

================================================================================
12) ITEM 19 — EXTRAIR ÁUDIO — mapa de faixa sem operador "?"
================================================================================
Status: ABERTO (mitigado)
Fontes: F1, F4
Finding: o mapa da faixa de áudio é emitido sem o operador de tolerância "?".
  A mitigação é real (índice sempre validado antes), mas o comando literal
  segue sem defesa em profundidade.
Evidência no código:
  - FfmpegExtractAudioActivity.kt:873 —
        audioMap = FfmpegMediaPolicies.audioStreamSpecifier(0, audioTrack)
  - FfmpegMediaPolicies.audioStreamSpecifier (:62-63) —
        "$inputIndex:a:${audioTrackIndex.coerceAtLeast(0)}"   // sem "?"
  - Mitigação: :739-742 rejeita arquivo sem faixa de áudio; :743-748 dispara
    requestAudioTrack quando audioTrackCount(it.uri) > 1; :751 guarda a escolha;
    :785 lê jobAudioTracks[...] ?: 0.
  - Teste congela o formato: FfmpegMediaPoliciesTest:60-64
    (assertEquals("0:a:0", ...), assertEquals("2:a:3", ...)).
Argumentos FFmpeg efetivos:
  -y [-ss X] -i <in> [-t Y] -vn -map 0:a:<n> -map_metadata 0
     [-c:a copy | -ar SR -ac CH <enc>] -avoid_negative_ts make_zero <out>
Impacto residual: nenhum na rota atual. Só falharia se divergir a ordem de
  faixas entre MediaExtractor e FFmpeg, ou se a validação prévia mudar.
Fix restante: trocar por "$inputIndex:a:${idx}?" — uma linha, mas vale para
  Extrair, Inserir (item 24) e Limpar (item 26) ao mesmo tempo; requer
  atualizar os testes FfmpegMediaPoliciesTest:62-63 e :145.
Divergência: F1="ABERTO (mitigado)", F4="PARCIALMENTE CORRIGIDO". Adotada
  ABERTO (mitigado): a letra do fix não foi implementada em nada.
Gravidade: BAIXA

================================================================================
13) ITEM 20 — EXTRAIR ÁUDIO — perfil de voz OPUS
================================================================================
Status: MELHORIA OPCIONAL
Fontes: F4
Finding: o OPUS usa -application audio e -vbr on (correto e alinhado com
  Cortar e Inserir), mas não existe a opção de perfil de voz
  (-application voip com CBR) para priorizar inteligibilidade de fala.
Evidência no código:
  - FfmpegMediaPolicies.kt:97 (Extrair) —
        "opus" -> listOf("-c:a","libopus","-application","audio","-b:a",bitrate,"-vbr","on")
  - FfmpegMediaPolicies.kt:71 (Cortar) — mesma lista, ordem diferente.
  - A string "voip" NÃO existe em nenhum arquivo Kotlin do módulo app.
  - FfmpegExtractAudioActivity.kt:1722 — OPUS("opus","audio/opus",true): o enum
    não tem campo de perfil.
  - activity_ffmpeg_extract_audio.xml: só button_output_extension,
    button_bitrate, button_sample_rate, button_channels.
Argumentos FFmpeg efetivos:
  ... -c:a libopus -application audio -b:a 96k -vbr on ...
  com perfil de voz: ... -c:a libopus -application voip -b:a 24k -vbr off ...
Impacto residual: nenhum funcional. O padrão aplicado está correto.
Fix restante: opcional — acrescentar "Voz (CBR)" ao lado de "Áudio (VBR)".
Gravidade: BAIXA

================================================================================
14) ITEM 24 — INSERIR ÁUDIO — mapas da rota ativa sem "?"
================================================================================
Status: PARCIALMENTE CORRIGIDO
Fontes: F1, F4
Finding: as rotas legadas (que ignoravam a faixa escolhida) foram removidas —
  esse ponto está resolvido. Resta a ausência do operador "?" nos três mapas.
Evidência no código:
  - Rotas legadas extintas: rg -n "executeCopyInsert|executeSmartInsert|
    concatPieces|audioInputsAreCopyCompatible" app/src/ → ZERO.
  - Rota única: buildFullReencodeArguments (:567-625), chamada uma vez em
    startInsert (:520), honrando a seleção:
        :586 [${audioStreamSpecifier(0, jobConfig.mainAudioTrack)}]   (esquerda)
        :593 [${audioStreamSpecifier(1, jobConfig.insertedAudioTrack)}] (inserido)
        :597 [${audioStreamSpecifier(0, jobConfig.mainAudioTrack)}]   (direita)
  - startInsert (:478-487) exige ≥1 faixa em cada arquivo e dispara
    requestAudioTrack (:641-653) quando há mais de uma.
  - Falta o "?" — mesma função compartilhada do item 19.
Argumentos FFmpeg efetivos:
  -y -i PRINCIPAL -i INSERIDO -filter_complex
     "[0:a:<n>]atrim=start=0:end=10.000,aresample=48000,aformat=...,asetpts=PTS-STARTPTS[a0];
      [1:a:<m>]atrim=start=0:end=4.000,...[a1];
      [0:a:<n>]atrim=start=10.000:end=60.000,...[a2];
      [a0][a1][a2]concat=n=3:v=0:a=1[aout]"
     -map [aout] -vn -c:a aac -b:a 192k -ar 48000 -ac 2 -movflags +faststart
     -avoid_negative_ts make_zero <out>.m4a
Impacto residual: nenhum hoje. Defesa em profundidade, igual aos itens 19 e 26.
Fix restante: adicionar "?" aos três mapas (uma linha, função compartilhada).
Gravidade: BAIXA

================================================================================
15) ITEM 26 — LIMPAR ÁUDIO — mapa de faixa sem "?"
================================================================================
Status: ABERTO (mitigado)
Fontes: F1, F4
Finding: o mapa segue -map 0:a:0 sem "?". A parte de preservar taxa, canais e
  profundidade da origem ESTÁ CORRETA.
Evidência no código:
  - FfmpegCleanAudioActivity.kt:270 —
        audioMap = FfmpegMediaPolicies.audioStreamSpecifier(0, 0)  // "0:a:0"
  - Mitigação: cleanSelectedAudio :177-181 exige exatamente uma faixa
    ("O arquivo precisa ter exatamente uma faixa de áudio."); audioSourceProfile
    (:302-319) só devolve perfil via audioFormats.singleOrNull().
  - Preservação confirmada: :311-317 escolhe pcm_u8 / pcm_f32le / pcm_s24le /
    pcm_s32le / pcm_s16le por KEY_PCM_ENCODING; cleanAudioCommandArguments
    (FfmpegMediaPolicies.kt:172-185) emite -ar e -ac da origem e -f wav.
  - Teste congela o formato: FfmpegMediaPoliciesTest:141-153 espera "0:a:0".
Argumentos FFmpeg efetivos:
  -y -i <in> -vn -map 0:a:0 -af afftdn=nf=-25 -c:a pcm_s32le -ar 96000 -ac 6
     -avoid_negative_ts make_zero -f wav <out>.wav
Impacto residual: nenhum na rota atual.
Fix restante: "0:a:0?" — uma linha, mesma função compartilhada; requer
  atualizar FfmpegMediaPoliciesTest:145.
Divergência: F1="ABERTO (mitigado)", F4="PARCIALMENTE CORRIGIDO". Adotada
  ABERTO (mitigado), pelo mesmo critério do item 19.
Gravidade: BAIXA

================================================================================
16) ITEM 27 — TRANSVERSAL — política de streams não centralizada; o Cortar
              descarta anexos (NOVO-1 dobrado aqui)
================================================================================
Status: PARCIALMENTE CORRIGIDO
Fontes: F1, F2, F4 (+ NOVO-1 da F4, dobrado aqui por ser o mesmo fato)
Finding: não existe política única de legendas e streams auxiliares em
  FfmpegMediaPolicies: cada Activity monta sua própria lista de mapas. O Cortar
  não mapeia anexos (0:t) nem emite -c:t copy, enquanto o Girar mapeia.
Evidência no código:
  - Girar preserva tudo: FfmpegRotateVideoActivity.kt:1129-1133 e :1332 —
        "-map","0:v:0", "-map","0:a?", "-map","0:s?", "-map","0:d?", "-map","0:t?"
  - Cortar NÃO preserva anexos (três rotas): FfmpegCutActivity.kt:697,
    FfmpegCutActivity.kt:890 e FfmpegMediaPolicies.kt:195
    (hybridCopyBodyArguments) —
        "-map","0:v:0?", "-map","0:a?", "-map","0:s?", "-map","0:d?"
    (F1 observa que falta também -c:t copy.)
  - Juntar: preserva no concat direto (-map 0, FfmpegMediaPolicies.kt:127-132);
    descarta no reencode com diálogo apenas para legendas
    (FfmpegJoinVideosActivity.kt:503-511, condicionado a
    !audioOnly && reencodeChecked). Dados e anexos caem sem aviso.
  - A rejeição indiscriminada de vídeos com legenda saiu de
    validateSupportedStreamTopology (:1811-1834).
  - Pré-existente em 85b889f — não é regressão (F1).
Argumentos FFmpeg efetivos (Cortar):
  -y -noautorotate [-display_rotation:v:0 G] -ss <ini> -i <in> -t <dur>
     -map 0:v:0? -map 0:a? -map 0:s? -map 0:d? -map_metadata 0 -map_chapters 0
     -c copy -avoid_negative_ts make_zero <out>.mkv
  Falta: -map 0:t? -c:t copy
Impacto residual: MKV com legenda ASS e fonte embutida cortado no modo preciso
  ou híbrido perde a fonte SEM AVISO — a legenda sobrevive e passa a ser
  renderizada com fonte substituta. É o único residual com perda de dados real
  fora do item 18.
Fix restante: adicionar -map 0:t? e -c:t copy às três rotas do Cortar;
  opcionalmente centralizar a política em FfmpegMediaPolicies (por exemplo
  preserveStreamMaps(includeAttachments: Boolean)) e avisar também sobre dados
  e anexos no Juntar recodificado.
Divergência: F2=ALTA, F1/F4=MÉDIA → adotada MÉDIA.
Gravidade: MÉDIA

================================================================================
17) ITEM 28 — TRANSVERSAL — grafos de filter_complex sem cobertura de teste
================================================================================
Status: PARCIALMENTE CORRIGIDO
Fontes: F4
Finding: os construtores de comando foram extraídos e cobertos (8 → 16 testes),
  mas os grafos de filter_complex continuam privados nas Activities e sem
  nenhum teste.
Evidência no código:
  - Coberto hoje (FfmpegMediaPoliciesTest, 16 testes): metadataRotation*,
    cutAudio*, hybridBody*, extractAudio*, joinAudio*, directConcat*,
    insertCommand*, cleanCommand* + helpers compartilhados.
  - NÃO coberto: FfmpegJoinVideosActivity.kt:876-910 (buildFilterComplex —
    concat v=1:a=1, xfade, acrossfade), :823-874
    (buildFadeInOutFilterComplex — fade, afade), :685-736
    (buildAudioReencodeArguments — concat v=0:a=1, acrossfade),
    FfmpegInsertAudioActivity.kt:567-625 (buildFullReencodeArguments — atrim,
    afade, acrossfade).
  - Também fora de cobertura: decisões de rota (fallback do corte híbrido,
    recusa do concat direto, pré-flight do Girar) e os helpers dependentes de
    MediaExtractor (detectPcmEncoder, detectStreamBitrates,
    streamCopySignatures, detectAggregateOutputProfile, videoTrackCount).
Argumentos FFmpeg efetivos: não aplicável (item de teste).
Impacto residual: regressão em filter_complex (ordem de asetpts/afade,
  aridade do concat) ou nas decisões de rota passa despercebida — e 52f6253
  mexeu exatamente nessas rotas.
Fix restante: extrair os construtores de filter_complex para funções puras
  (entrada: clipes + perfil; saída: String) e cobri-los com testes.
Gravidade: BAIXA

================================================================================
18) ITEM 29 — TRANSVERSAL — estado morto residual: videoBitstreamFilter
================================================================================
Status: ABERTO
Fontes: F2
Finding: parte do código morto foi removida, mas videoBitstreamFilter continua
  sendo preenchido e nunca é lido.
Evidência no código (VERIFICADO NA FUSÃO):
  - Atribuído em FfmpegJoinVideosActivity.kt:1950
    (videoBitstreamFilterFor(DEFAULT_VIDEO_CODEC)), :1989
    (videoBitstreamFilterFor(videoCodec)) e :2010
    (videoBitstreamFilterFor(DEFAULT_VIDEO_CODEC)).
  - Declarado em :2276 (val videoBitstreamFilter: String?).
  - videoBitstreamFilterFor definido em :2181.
  - grep por "videoBitstreamFilter" no arquivo devolve SOMENTE essas cinco
    linhas — nenhuma leitura do campo.
Impacto residual: nenhuma rota FFmpeg ativa é afetada. O risco é de governança:
  o estado morto sugere uma rota que não existe e pode reativar lógica removida
  de forma incompleta numa alteração futura.
Fix restante: remover videoBitstreamFilter e videoBitstreamFilterFor, ou
  voltar a consumi-los numa rota comprovadamente necessária.
Gravidade: BAIXA

================================================================================
19) NOVO-A — TRANSVERSAL — MediaExtractor falho aborta como "sem vídeo"
================================================================================
Status: ABERTO
Fontes: F4 (NOVO-2)
Finding: Girar e Cortar bloqueiam o arquivo com MediaExtractor antes de
  qualquer FFmpeg. Se o extrator da plataforma não abrir o container, a
  operação aborta com "O arquivo não possui uma faixa de vídeo" — mensagem
  falsa, porque quem falhou foi o extrator, não o arquivo.
Evidência no código:
  - FfmpegRotateVideoActivity.kt:450-458 — if (videoTracks != 1) {
    "O arquivo não possui uma faixa de vídeo." ; return }
  - FfmpegRotateVideoActivity.kt:499-511 — videoTrackCount abre com
    MediaExtractor.setDataSource(this, uri, null) e, no
    catch (_: Throwable), devolve 0: falha de abertura é indistinguível de
    "sem faixa de vídeo".
  - FfmpegCutActivity.kt:491-498 — mesma checagem ("O arquivo não possui
    vídeo.").
  - Contraste: detectMediaMime (FfmpegCutActivity.kt:472-487) degrada com
    elegância para contentResolver.getType(uri) no catch. O problema está
    somente na contagem de faixas.
  - A checagem é anterior a qualquer FFmpegKit.execute — não há segunda chance.
Argumentos FFmpeg efetivos: nenhum — a execução aborta antes.
Impacto residual: falso negativo silencioso que se apresenta como erro do
  arquivo. Containers que o extrator de um fabricante não abra (variações de
  MPEG-TS, MOV com codec não registrado, metadados corrompidos) ficam
  inutilizáveis mesmo sendo válidos para o FFmpeg.
Fix restante: fazer videoTrackCount distinguir "falha ao abrir" de "zero
  faixas" (ex.: selo TrackProbe.Failed vs TrackProbe.Count(n)) e, na falha,
  avisar "Não foi possível inspecionar o arquivo" em vez de acusar ausência de
  vídeo — opcionalmente oferecendo prosseguir sem a checagem.
Gravidade: BAIXA

================================================================================
CONSOLIDAÇÃO — ORDEM DE TRABALHO
================================================================================

P1 — saída com conteúdo errado (fazer primeiro):
  18  Extrair omite -t por causa da tolerância de 250 ms e exporta o arquivo
      inteiro. Fix: emitir -t sempre que houver fim real escolhido
      (FfmpegExtractAudioActivity.kt:860 e :945).
  27  Cortar descarta fontes/anexos. Fix: -map 0:t? -c:t copy em
      FfmpegCutActivity.kt:697, :890 e FfmpegMediaPolicies.kt:195.

P2 — decisão tomada sem o usuário:
  7   Fallback para reencode completo sem aviso (FfmpegCutActivity.kt:725, :738).
  8   Bitrate fixo 15M quando KEY_BIT_RATE ausente (:1806, usado em :700, :893).
  14  Conversão para WAV sem confirmação, com diálogo contraditório
      (FfmpegJoinVideosActivity.kt:538-542, :1863).

P3 — qualidade e opção de produto:
  5   Cópia com corte em PCM/FLAC + UI de qualidade no Cortar.
  12  -c:a copy no Juntar quando os clipes forem idênticos.
  11  Preservar N faixas quando a contagem coincidir.
  1   Validar o container real no Girar, não a extensão.
  13  Container por ffprobe no concat direto.
  16 / 20 — junção parcial e perfil de voz OPUS: só se o produto quiser.

P4 — limpeza e endurecimento:
  19 / 24 / 26  operador "?" em audioStreamSpecifier (1 linha + 2 testes).
  3             re-sincronizar updateMetadataModeState após o processamento.
  29            remover videoBitstreamFilter.
  NOVO-A        separar falha de abertura de ausência de faixa.
  28            testes dos grafos de filter_complex.

Sobre a base de prova: análise estática (leitura integral das seis Activities,
de FfmpegMediaPolicies.kt, dos layouts e dos testes), rg,
git diff 85b889f..52f6253, e a suíte unitária registrada no topo (16 testes,
0 falhas). NÃO houve execução com arquivos de mídia reais nem com o binário
FFmpeg. Os itens marcados como "saída com conteúdo errado" (18) e "perda de
dados" (27) são conclusões sobre a FORMA DOS COMANDOS gerados, deduzidas do
código — confirmar em runtime com um MKV com anexos e um WAV de 10 s cortado
a 9,9 s antes de fechar qualquer uma das duas correções.
================================================================================
