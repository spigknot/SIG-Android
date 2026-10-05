================================================================================
FFmpeg (SIG Android) — APENAS O QUE AINDA PRECISA SER CORRIGIDO
================================================================================
Data:          2026-08-31
HEAD:          52f6253 ("fix: validar compatibilidade e remover legado ffmpeg")
Base:          85b889f
Origem:        findings_consolidados_4_relatorios_2026-08-31.md (19 entradas)

O que foi REMOVIDO deste recorte:
  - Toda evidência de parte já corrigida dentro de cada item (profundidade PCM,
    mimes WAV, rotas legadas do Inserir, preservação de taxa/canais no Limpar,
    codec por perfil agregado no Juntar, rejeição indiscriminada de legendas).
  - As entradas que NÃO exigem correção:
      #16 (Juntar — join parcial): decisão de produto, bug do Smart Join
           eliminado, zero resíduo no código.
      #20 (Extrair — perfil de voz OPUS): melhoria opcional, o padrão aplicado
           (-application audio -vbr on) está correto.
  - O histórico de reclassificação entre relatórios (era processo, não trabalho).

Total: 17 entradas — 6 ABERTAS e 11 PARCIALMENTE CORRIGIDAS.

Campos: Finding / Evidência no código / Argumentos FFmpeg efetivos /
        Impacto residual / Fix restante / Gravidade

================================================================================
LISTA
================================================================================

#   Item   Ferramenta    Status                  Grav.  O que falta
--  -----  ------------  ----------------------  -----  ------------------------
1    18    Extrair       PARCIALMENTE CORRIGIDO  ALTA   -t omitido: exporta o
                                                        arquivo inteiro
2    27    Transversal   PARCIALMENTE CORRIGIDO  MÉDIA  Cortar descarta anexos
3     7    Cortar        ABERTO                  MÉDIA  fallback sem aviso
4     8    Cortar        ABERTO                  MÉDIA  bitrate fixo 15M
5     5    Cortar        PARCIALMENTE CORRIGIDO  MÉDIA  cópia com corte + UI
6    11    Juntar        PARCIALMENTE CORRIGIDO  MÉDIA  só 1 faixa de áudio
7    12    Juntar        PARCIALMENTE CORRIGIDO  MÉDIA  sem -c:a copy
8    13    Juntar        PARCIALMENTE CORRIGIDO  MÉDIA  container por extensão
9     1    Girar         PARCIALMENTE CORRIGIDO  MÉDIA  valida só a extensão
10   14    Juntar        PARCIALMENTE CORRIGIDO  BAIXA  WAV sem confirmação
11    3    Girar         PARCIALMENTE CORRIGIDO  BAIXA  checkbox reabilitado
12   19    Extrair       ABERTO (mitigado)       BAIXA  falta "?" no mapa
13   24    Inserir       PARCIALMENTE CORRIGIDO  BAIXA  falta "?" nos 3 mapas
14   26    Limpar        ABERTO (mitigado)       BAIXA  falta "?" no mapa
15   28    Transversal   PARCIALMENTE CORRIGIDO  BAIXA  filter_complex sem teste
16   29    Transversal   ABERTO                  BAIXA  estado morto
17  NOVO-A Transversal   ABERTO                  BAIXA  extrator falho aborta

Contagem por gravidade:
  ALTA (1) .... 18
  MÉDIA (8) ... 27, 7, 8, 5, 11, 12, 13, 1
  BAIXA (8) ... 14, 3, 19, 24, 26, 28, 29, NOVO-A

================================================================================
1) ITEM 18 — EXTRAIR ÁUDIO — o comando sai SEM -t e exporta o arquivo inteiro
================================================================================
Status: PARCIALMENTE CORRIGIDO
Finding: a tolerância de 250 ms no fim do intervalo faz o comando ser gerado
  SEM `-t`. O usuário pede um trecho e recebe o arquivo completo. Secundários:
  a cópia sem perdas é negada com qualquer corte real, e o MP3 sai sempre CBR.
Evidência no código:
  - FfmpegExtractAudioActivity.kt:860 (buildFfmpegArguments):
        val hasEndTrim = endMs != null && inputDuration > 0L &&
                         endMs < inputDuration - 250L
        ...
        duration = if (hasEndTrim) formatSeconds(endMs - startMs) else null
  - FfmpegExtractAudioActivity.kt:945 (canCopyAudioWithoutConversion): a MESMA
    tolerância decide se a cópia é liberada.
  - Consequência: duração 10.000 ms, usuário escolhe fim em 9.900 ms →
    `9900 < 9750` é FALSO → hasEndTrim = false → duration = null → o comando
    NÃO recebe -t. Com início em 0, sai o arquivo inteiro. Com início > 0, sai
    do início escolhido até o fim original. E como a cópia foi liberada, sai
    com -c:a copy.
  - Cópia negada com corte: :945 devolve false; ainda exige
    sourceRate == settings.sampleRate && sourceChannels == settings.channels
    (:964).
  - MP3 em CBR: FfmpegMediaPolicies.kt:93 —
        "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", bitrate)
  - O teste extractionUsesVbrCapableMp3AndAudioOpusProfile
    (FfmpegMediaPoliciesTest:88-108) tem NOME ENGANOSO: afirma apenas a
    ausência de -minrate/-maxrate, os argumentos reais são CBR.
Argumentos FFmpeg efetivos (COM O DEFEITO — fim a 100 ms do final):
  -y -i <in> -vn -map 0:a:N -map_metadata 0 -c:a copy
     -avoid_negative_ts make_zero <out>
     ^^^ SEM -t: exporta o arquivo inteiro em vez dos 9,9 s pedidos
Argumentos FFmpeg efetivos (reencode com corte):
  -y -ss <s> -i <in> -t <s> -vn -map 0:a:N -map_metadata 0 -ar <r> -ac <c>
     -c:a libmp3lame -b:a <k> -avoid_negative_ts make_zero <out>
Impacto residual: SAÍDA COM CONTEÚDO ERRADO, sem erro de execução e sem aviso.
  É o único item da lista com esse tipo de falha.
Fix restante:
  (i) emitir -t SEMPRE que o usuário escolher um fim real; remover a tolerância
      de 250 ms ou reduzi-la a um limiar que NÃO desligue o -t;
  (ii) permitir cópia com corte em WAV/FLAC (CFR), alinhando ao limite de
       amostra;
  (iii) expor CBR/VBR (ou -q:a) na UI;
  (iv) renomear o teste que descreve CBR como "VbrCapable".
Gravidade: ALTA

================================================================================
2) ITEM 27 — TRANSVERSAL — o Cortar descarta anexos; política não centralizada
================================================================================
Status: PARCIALMENTE CORRIGIDO
Finding: não existe política única de streams em FfmpegMediaPolicies — cada
  Activity monta sua própria lista de mapas. O Cortar não mapeia anexos (0:t)
  nem emite -c:t copy, enquanto o Girar mapeia. No Juntar recodificado, dados e
  anexos caem sem o aviso que as legendas recebem.
Evidência no código:
  - Girar preserva: FfmpegRotateVideoActivity.kt:1129-1133 e :1332 —
        "-map","0:v:0", "-map","0:a?", "-map","0:s?", "-map","0:d?", "-map","0:t?"
  - Cortar NÃO preserva (três rotas): FfmpegCutActivity.kt:697,
    FfmpegCutActivity.kt:890 e FfmpegMediaPolicies.kt:195
    (hybridCopyBodyArguments) —
        "-map","0:v:0?", "-map","0:a?", "-map","0:s?", "-map","0:d?"
  - Juntar: concat direto preserva (-map 0, FfmpegMediaPolicies.kt:127-132);
    reencode descarta, mas o diálogo (FfmpegJoinVideosActivity.kt:503-511)
    cobre SÓ legendas, condicionado a !audioOnly && reencodeChecked.
Argumentos FFmpeg efetivos (Cortar, modo preciso):
  -y -noautorotate [-display_rotation:v:0 G] -ss <ini> -i <in> -t <dur>
     -map 0:v:0? -map 0:a? -map 0:s? -map 0:d? -map_metadata 0 -map_chapters 0
     -c copy -avoid_negative_ts make_zero <out>.mkv
  Falta: -map 0:t? -c:t copy
Impacto residual: MKV com legenda ASS e fonte embutida cortado no modo preciso
  ou híbrido PERDE A FONTE SEM AVISO — a legenda sobrevive e passa a ser
  renderizada com fonte substituta. É o único item com perda de dados real fora
  do #18.
Fix restante:
  (i) adicionar -map 0:t? e -c:t copy nas três rotas do Cortar;
  (ii) centralizar a política em FfmpegMediaPolicies (ex.:
       preserveStreamMaps(includeAttachments: Boolean)) e avisar também sobre
       dados e anexos no Juntar recodificado.
Gravidade: MÉDIA

================================================================================
3) ITEM 7 — CORTAR VÍDEO — fallback para reencode completo SEM AVISO
================================================================================
Status: ABERTO
Finding: quando o caminho híbrido não é viável, o Cortar cai para reencode
  completo sem consultar o usuário — operação muito mais lenta e com perda
  geracional de qualidade.
Evidência no código:
  - FfmpegCutActivity.kt:720-725 —
        if (sourceCodec !in setOf("h264","hevc") ||
            actualEncoder.codecFamily != sourceCodec) {
            tracker.appendTasks(listOf("Caminho rápido indisponível: $reason"))
            return executeFullPrecisionFallback(...)
        }
  - FfmpegCutActivity.kt:736-738 —
        if (startKeyframe == null || endKeyframe == null ||
            startKeyframe >= endKeyframe) {
            tracker.appendTasks(listOf("Caminho rápido indisponível: não há keyframes internos suficientes"))
            return executeFullPrecisionFallback(...)
        }
  - executeFullPrecisionFallback em :835-875.
  - Nenhum AlertDialog precede as duas chamadas: só uma linha de tracker,
    emitida DEPOIS da decisão.
Argumentos FFmpeg efetivos (fallback):
  -y -noautorotate [-display_rotation:v:0 G] -ss <ini> -i <in> -t <dur>
     -map 0:v:0? -map 0:a? -map 0:s? -map 0:d? -map_metadata 0 -map_chapters 0
     -c:v <enc> <args de qualidade> -avoid_negative_ts make_zero <out>
Impacto residual: o usuário pede corte rápido e recebe reencode completo, sem
  consentimento e sem estimativa. Em vídeo longo são minutos a mais e perda de
  qualidade.
Fix restante: diálogo antes do fallback informando motivo e custo estimado, com
  opção de cancelar; ou ao menos registrar a decisão no resultado final.
Gravidade: MÉDIA

================================================================================
4) ITEM 8 — CORTAR VÍDEO — bitrate fixo 15M quando não detectado
================================================================================
Status: ABERTO
Finding: quando MediaFormat não informa KEY_BIT_RATE (frequente em MKV, WebM e
  arquivos remuxados), o Cortar usa 15M fixos em vez de estimar ou perguntar.
Evidência no código:
  - FfmpegCutActivity.kt:1806 — private const val FALLBACK_VIDEO_BITRATE = "15M"
  - Uso em :700 (modo preciso) e :893 (fallback de precisão):
        videoEncodingArguments(enc, streamBitrates.video ?: FALLBACK_VIDEO_BITRATE, quality)
  - Origem do null: detectStreamBitrates (:1035-1056) faz
        runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()
            ?.takeIf { it > 0 }?.let { "${(it / 1000).coerceAtLeast(1)}k" }
    e devolve StreamBitrates() vazio no catch.
Argumentos FFmpeg efetivos:
  -c:v <enc> -b:v 15M -maxrate ... -bufsize ...
Impacto residual: clipe de 480p sem KEY_BIT_RATE é reencodado a 15 Mbps —
  arquivo muito maior que a origem, ou falha de encoder em dispositivo com
  limite de nível. Oposto (4K a 60 Mbps) fica sub-bitratado.
Fix restante: estimar bitrate por resolução × fps × fator de codec quando a
  detecção falhar, ou expor o campo de qualidade para correção manual.
Gravidade: MÉDIA

================================================================================
5) ITEM 5 — CORTAR ÁUDIO — reencode em todo corte real; sem ajustes na UI
================================================================================
Status: PARCIALMENTE CORRIGIDO
Finding: o corte de áudio com intervalo real sempre reencoda; MP3 sai em CBR;
  não há controle de qualidade/CBR-VBR na UI.
Evidência no código:
  - A cópia só existe SEM corte real: FfmpegCutActivity.kt:668-675 —
        val hasRealTrim = startMs > 0L ||
            (inputDurationMs > 0L && endMs < inputDurationMs - 10L)
        val encoderArguments = if (!hasRealTrim) listOf("-c:a","copy") else ...
  - Encoder sempre escolhido com corte:
    FfmpegMediaPolicies.cutAudioEncoderArguments (:65-74); MP3 em CBR em
    FfmpegMediaPolicies.kt:72.
  - FALLBACK_AUDIO_BITRATE = "192k" (:1807) quando KEY_BIT_RATE está ausente
    (comum em WAV/FLAC/PCM).
  - Sem UI de áudio: activity_ffmpeg_cut.xml só tem button_video_encoder e
    button_video_quality; showEditingControls (:1339-1344) mostra encoder/
    qualidade apenas quando selectedMime.startsWith("video/").
  - Um fim até 10 ms antes da duração é tratado como "sem corte" (:670) e usa
    cópia por pacote, sem garantir limite exato; o tracker ainda pode dizer
    "Recodificando áudio" mesmo com -c:a copy.
Argumentos FFmpeg efetivos (com corte):
  -y -ss <s> -i <in> -t <s> -map 0:a? -map_metadata 0 -map_chapters 0 -vn
     -c:a pcm_s24le|aac|libmp3lame -b:a <k> -avoid_negative_ts make_zero <out>
Impacto residual: reencode desnecessário em WAV/FLAC, MP3 sempre CBR, qualidade
  fixa quando o MediaFormat não informa bitrate. Nenhum comando incorreto.
Fix restante:
  (i) -c:a copy quando codec + container + intervalo forem compatíveis
      (containers de taxa constante: WAV/FLAC), alinhando ao limite de amostra;
  (ii) expor CBR/VBR e qualidade de saída na UI;
  (iii) diferenciar "fim igual à duração" de "fim próximo da duração"
      (limiar de 10 ms em :670).
Gravidade: MÉDIA

================================================================================
6) ITEM 11 — JUNTAR — rotas filtradas exportam uma única faixa de áudio
================================================================================
Status: PARCIALMENTE CORRIGIDO
Finding: as rotas recodificadas produzem uma só faixa de áudio e descartam
  faixas secundárias, dados e anexos. A perda de faixas é anunciada, mas não há
  caminho que as preserve, e dados/anexos caem sem aviso.
Evidência no código:
  - Aridade 1 nos quatro pontos: FfmpegJoinVideosActivity.kt:713 e :724
    ("concat=n=N:v=0:a=1[aout]"), :872 e :907
    ("concat=n=N:v=1:a=1[vout][aout]").
  - Anúncio: requestAudioTrack :1860-1871 — "Escolha 1 das ${labels.size}
    faixas de ${clip.name}; as demais não entrarão na saída recodificada".
  - Junções de vídeo com múltiplas faixas são bloqueadas no caminho direto em
    :569.
Argumentos FFmpeg efetivos (caminho filtrado):
  -filter_complex "...concat=n=2:v=0:a=1[aout]" -map [aout] -vn -c:a <enc>
Impacto residual: em qualquer reencode ou normalização forçada, só a faixa
  escolhida é exportada. Legendas têm diálogo (:503-511); dados e anexos, não.
Fix restante: preservar todas as faixas compatíveis (um filter_complex por
  faixa quando a contagem coincidir entre os clipes) e informar QUANTAS serão
  descartadas; estender o aviso das legendas a dados e anexos.
Gravidade: MÉDIA

================================================================================
7) ITEM 12 — JUNTAR ÁUDIO — sem -c:a copy e sem aviso de codec de destino
================================================================================
Status: PARCIALMENTE CORRIGIDO
Finding: a rota de reencode nunca emite -c:a copy e não informa o codec de
  destino antes de executar. Áudios idênticos são recodificados com perda.
Evidência no código:
  - FfmpegMediaPolicies.joinAudioCommandArguments (:134-149) sempre emite
    -c:a <encoder>; buildAudioReencodeArguments
    (FfmpegJoinVideosActivity.kt:685-736) e audioEncodingArguments (:810-821)
    sempre escolhem encoder.
  - O aviso existe para a conversão forçada a WAV (:655-660), mas NÃO para o
    reencode voluntário de áudios idênticos.
Argumentos FFmpeg efetivos:
  -filter_complex "...concat..." -map [aout] -vn -c:a <enc> -ar <r> -ac <c> -b:a <k>
Impacto residual: reencode com perda em AAC/MP3/Opus/Vorbis quando os clipes já
  são idênticos e o usuário queria apenas transição ou corte de tempo.
Fix restante: quando codec, taxa, canais e sample format forem idênticos entre
  todos os clipes, emitir -c:a copy; informar codec e perfil de destino antes
  de executar o reencode manual.
Gravidade: MÉDIA

================================================================================
8) ITEM 13 — JUNTAR VÍDEO — container deduzido da extensão, não do muxer
================================================================================
Status: PARCIALMENTE CORRIGIDO
Finding: a validação do concat direto compara 23 campos internos, mas o
  container continua sendo deduzido da extensão do arquivo.
Evidência no código:
  - FfmpegJoinVideosActivity.kt:1744 —
        val containerFamily = FfmpegMediaPolicies.containerFamily(file.name)
  - FfmpegMediaPolicies.containerFamily (:299-311) —
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp4","m4v","mov" -> "mov" ; ... else -> "unknown" }
  - A descrição de streams depende de regex sobre a saída do FFmpeg
    (FfmpegJoinVideosActivity.kt:1793).
Argumentos FFmpeg efetivos:
  -y -fflags +genpts -f concat -safe 0 -i <lista> -map 0 -map_metadata 0
     -map_chapters 0 -c copy -avoid_negative_ts make_zero <out>
Impacto residual: dois arquivos com a MESMA extensão incorreta recebem o mesmo
  containerFamily e podem ser considerados compatíveis. A proteção de fato vem
  do ffmpegDescriptor (que reflete o conteúdo real), então o furo é estreito —
  mas não depende do muxer.
Fix restante: consultar o formato real via FFmpeg/ffprobe e rejeitar qualquer
  concat cuja compatibilidade a nível de muxer não possa ser comprovada.
Gravidade: MÉDIA

================================================================================
9) ITEM 1 — GIRAR VÍDEO — preflight não valida o container real
================================================================================
Status: PARCIALMENTE CORRIGIDO
Finding: o modo por metadados pode falhar ao copiar streams incompatíveis para
  o container escolhido. O preflight é uma checagem parcial, não uma validação
  do muxer real.
Evidência no código:
  - FfmpegMediaPolicies.safeContainerExtension (:294-297) considera SOMENTE a
    extensão:
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it in setOf("mp4","m4v","mov","mkv","webm","avi") } ?: "mkv"
  - O preflight (FfmpegRotateVideoActivity.kt:574-607, via
    FfmpegMediaPolicies.metadataCopyPreflightArguments :48-60) é uma cópia de
    1 ms (-t 0.001), não uma validação completa do muxer.
Argumentos FFmpeg efetivos:
  -y -display_rotation:v:0 <rotação> -i <entrada> -map 0 -c copy <saída>
  preflight: mesmo comando + -hide_banner -loglevel error -t 0.001
Impacto residual: arquivo com extensão incorreta, ou com stream que o muxer de
  destino não aceite, pode passar pelo preflight de 1 ms e falhar na cópia
  completa. A falha é visível, não silenciosa.
Fix restante: consultar o container real do arquivo e validar codecs, legendas,
  attachments e demais streams contra o container de saída.
Gravidade: MÉDIA

================================================================================
10) ITEM 14 — JUNTAR ÁUDIO — conversão para WAV sem confirmação
================================================================================
Status: PARCIALMENTE CORRIGIDO
Finding: áudios incompatíveis viram WAV sem confirmação. Nenhum diálogo permite
  aceitar ou recusar a conversão — só cancelar a operação inteira. Quando o
  gatilho é áudio multi-faixa, o diálogo de seleção ainda promete "saída
  recodificada", contradizendo o WAV.
Evidência no código:
  - FfmpegJoinVideosActivity.kt:501 —
        val hasSelectedMultitrackAudio = clips.any { audioTrackCount(it.uri) > 1 }
  - :538-541 —
        val audioNeedsNormalization = audioOnly &&
            (directConcatIncompatibility != null || firstAudioExtension == "mp3"
             || hasSelectedMultitrackAudio)
        val audioWillStandardizeToWav = audioNeedsNormalization && !reencodeChecked
  - Nome forçado ANTES de executar: :542 → buildJoinedOutputName(
    forceAudioStandardization = audioWillStandardizeToWav), extensão "wav" em
    :1716.
  - A linha de status (:652-660, "Convertendo áudios incompatíveis para WAV no
    perfil agregado") aparece JÁ DURANTE a execução.
  - O diálogo de :1863 diz "as demais não entrarão na saída recodificada" —
    "recodificada" não implica WAV, mas é o que acontece quando
    hasSelectedMultitrackAudio força a normalização.
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
11) ITEM 3 — GIRAR VÍDEO — checkbox de paralelismo reabilitado após o job
================================================================================
Status: PARCIALMENTE CORRIGIDO
Finding: o paralelismo é desativado quando o modo por metadados está ativo, mas
  volta a ficar habilitado incondicionalmente ao término do processamento.
Evidência no código:
  - updateMetadataModeState desabilita: FfmpegRotateVideoActivity.kt:1462 —
        parallelKeyframes.isEnabled = !metadataOnly   (alpha 0.42f em :1465)
  - MAS setProcessing(false) reabilita sem consultar metadataOnly: :1563 —
        parallelKeyframes.isEnabled = !processing
        inputParallelSegments.isEnabled = !processing
  - O pedido é então ignorado: canUseParallel exclui metadataOnly em :521.
Argumentos FFmpeg efetivos: com metadataOnly verdadeiro a rota paralela nunca
  é executada; o comando final usa -map 0 -c copy.
Impacto residual: após uma operação, o checkbox reaparece habilitado (alpha
  0.42 desatualizado) com o modo por metadados ativo. O usuário pode marcá-lo e
  o pedido é ignorado sem aviso específico. Re-sincroniza se o usuário alternar
  o checkbox de metadados.
Fix restante: reaplicar updateMetadataModeState() ao fim do processamento, ou
  avisar quando requestedParallel && metadataOnly.
Gravidade: BAIXA

================================================================================
12) ITEM 19 — EXTRAIR ÁUDIO — mapa de faixa sem operador "?"
================================================================================
Status: ABERTO (mitigado)
Finding: o mapa da faixa de áudio é emitido sem o operador de tolerância "?".
Evidência no código:
  - FfmpegExtractAudioActivity.kt:873 —
        audioMap = FfmpegMediaPolicies.audioStreamSpecifier(0, audioTrack)
  - FfmpegMediaPolicies.audioStreamSpecifier (:62-63) —
        "$inputIndex:a:${audioTrackIndex.coerceAtLeast(0)}"   // sem "?"
  - Teste congela o formato: FfmpegMediaPoliciesTest:60-64
    (assertEquals("0:a:0", ...), assertEquals("2:a:3", ...)).
Argumentos FFmpeg efetivos:
  -y [-ss X] -i <in> [-t Y] -vn -map 0:a:<n> -map_metadata 0
     [-c:a copy | -ar SR -ac CH <enc>] -avoid_negative_ts make_zero <out>
Impacto residual: nenhum hoje — o índice é validado antes da execução. O "?"
  só protegeria contra divergência entre a ordem de faixas do MediaExtractor e
  a do FFmpeg, ou se a validação prévia mudar.
Fix restante: trocar por "$inputIndex:a:${idx}?" — UMA LINHA, mas vale ao mesmo
  tempo para Extrair, Inserir (#24) e Limpar (#26); exige atualizar
  FfmpegMediaPoliciesTest:62-63 e :145.
Gravidade: BAIXA

================================================================================
13) ITEM 24 — INSERIR ÁUDIO — três mapas sem operador "?"
================================================================================
Status: PARCIALMENTE CORRIGIDO
Finding: a rota ativa honra a faixa escolhida nos três pontos do filtro, mas
  nenhum mapa usa o operador de tolerância "?".
Evidência no código:
  - buildFullReencodeArguments (:567-625), chamada uma vez em startInsert
    (:520), nos três pontos:
        :586 [${audioStreamSpecifier(0, jobConfig.mainAudioTrack)}]   (esquerda)
        :593 [${audioStreamSpecifier(1, jobConfig.insertedAudioTrack)}] (inserido)
        :597 [${audioStreamSpecifier(0, jobConfig.mainAudioTrack)}]   (direita)
  - Falta o "?" — mesma função compartilhada do #19.
Argumentos FFmpeg efetivos:
  -y -i PRINCIPAL -i INSERIDO -filter_complex
     "[0:a:<n>]atrim=start=0:end=10.000,aresample=48000,aformat=...,asetpts=PTS-STARTPTS[a0];
      [1:a:<m>]atrim=start=0:end=4.000,...[a1];
      [0:a:<n>]atrim=start=10.000:end=60.000,...[a2];
      [a0][a1][a2]concat=n=3:v=0:a=1[aout]"
     -map [aout] -vn -c:a aac -b:a 192k -ar 48000 -ac 2 -movflags +faststart
     -avoid_negative_ts make_zero <out>.m4a
Impacto residual: nenhum hoje. Defesa em profundidade, igual a #19 e #26.
Fix restante: adicionar "?" aos três mapas (uma linha, função compartilhada).
Gravidade: BAIXA

================================================================================
14) ITEM 26 — LIMPAR ÁUDIO — mapa de faixa sem operador "?"
================================================================================
Status: ABERTO (mitigado)
Finding: o mapa segue -map 0:a:0 sem "?".
Evidência no código:
  - FfmpegCleanAudioActivity.kt:270 —
        audioMap = FfmpegMediaPolicies.audioStreamSpecifier(0, 0)  // "0:a:0"
  - Teste congela o formato: FfmpegMediaPoliciesTest:141-153 espera "0:a:0".
Argumentos FFmpeg efetivos:
  -y -i <in> -vn -map 0:a:0 -af afftdn=nf=-25 -c:a pcm_s32le -ar 96000 -ac 6
     -avoid_negative_ts make_zero -f wav <out>.wav
Impacto residual: nenhum na rota atual — a Activity exige exatamente uma faixa
  antes de executar (:177-181).
Fix restante: "0:a:0?" — uma linha, mesma função compartilhada; requer
  atualizar FfmpegMediaPoliciesTest:145.
Gravidade: BAIXA

================================================================================
15) ITEM 28 — TRANSVERSAL — grafos de filter_complex sem teste
================================================================================
Status: PARCIALMENTE CORRIGIDO
Finding: os construtores de comando foram extraídos e cobertos (8 → 16 testes),
  mas os grafos de filter_complex continuam privados nas Activities e sem
  nenhum teste.
Evidência no código — NÃO coberto:
  - FfmpegJoinVideosActivity.kt:876-910  buildFilterComplex
                                         (concat v=1:a=1, xfade, acrossfade)
  - FfmpegJoinVideosActivity.kt:823-874  buildFadeInOutFilterComplex
                                         (fade, afade)
  - FfmpegJoinVideosActivity.kt:685-736  buildAudioReencodeArguments
                                         (concat v=0:a=1, acrossfade)
  - FfmpegInsertAudioActivity.kt:567-625 buildFullReencodeArguments
                                         (atrim, afade, acrossfade)
  - Fora de cobertura também: decisões de rota (fallback do corte híbrido,
    recusa do concat direto, pré-flight do Girar) e helpers dependentes de
    MediaExtractor (detectPcmEncoder, detectStreamBitrates,
    streamCopySignatures, detectAggregateOutputProfile, videoTrackCount).
Argumentos FFmpeg efetivos: não aplicável (item de teste).
Impacto residual: regressão em filter_complex (ordem de asetpts/afade, aridade
  do concat) ou nas decisões de rota passa despercebida — e 52f6253 mexeu
  exatamente nessas rotas.
Fix restante: extrair os construtores de filter_complex para funções puras
  (entrada: clipes + perfil; saída: String) e cobri-los com testes de unidade.
Gravidade: BAIXA

================================================================================
16) ITEM 29 — TRANSVERSAL — estado morto: videoBitstreamFilter
================================================================================
Status: ABERTO
Finding: videoBitstreamFilter continua sendo preenchido e nunca é lido.
Evidência no código:
  - Atribuído em FfmpegJoinVideosActivity.kt:1950
    (videoBitstreamFilterFor(DEFAULT_VIDEO_CODEC)), :1989
    (videoBitstreamFilterFor(videoCodec)) e :2010.
  - Declarado em :2276; videoBitstreamFilterFor definido em :2181.
  - grep por "videoBitstreamFilter" no arquivo devolve SOMENTE essas cinco
    linhas — NENHUMA leitura do campo.
Argumentos FFmpeg efetivos: não aplicável.
Impacto residual: nenhuma rota ativa é afetada. Risco de governança: o estado
  morto sugere uma rota inexistente e pode reativar lógica removida de forma
  incompleta numa alteração futura.
Fix restante: remover videoBitstreamFilter e videoBitstreamFilterFor, ou voltar
  a consumi-los numa rota comprovadamente necessária.
Gravidade: BAIXA

================================================================================
17) NOVO-A — TRANSVERSAL — extrator falho aborta como "sem vídeo"
================================================================================
Status: ABERTO
Finding: Girar e Cortar bloqueiam o arquivo com MediaExtractor antes de qualquer
  FFmpeg. Se o extrator da plataforma não abrir o container, a operação aborta
  com "O arquivo não possui uma faixa de vídeo" — mensagem falsa, porque quem
  falhou foi o extrator, não o arquivo.
Evidência no código:
  - FfmpegRotateVideoActivity.kt:450-458 — if (videoTracks != 1) {
    "O arquivo não possui uma faixa de vídeo." ; return }
  - FfmpegRotateVideoActivity.kt:499-511 — videoTrackCount abre com
    MediaExtractor.setDataSource(this, uri, null) e, no catch (_: Throwable),
    devolve 0: falha de abertura é indistinguível de "sem faixa de vídeo".
  - FfmpegCutActivity.kt:491-498 — mesma checagem ("O arquivo não possui vídeo.").
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
ORDEM DE EXECUÇÃO RECOMENDADA
================================================================================

P1 — saída errada / perda de dados (corrigir primeiro):
  18   Extrair: emitir -t sempre que houver fim real escolhido
       (FfmpegExtractAudioActivity.kt:860 e :945).
  27   Cortar: -map 0:t? -c:t copy em FfmpegCutActivity.kt:697, :890 e
       FfmpegMediaPolicies.kt:195.

P2 — decisão tomada sem o usuário:
  7    Diálogo antes do fallback (FfmpegCutActivity.kt:725, :738).
  8    Estimar bitrate em vez de 15M (:1806, usado em :700 e :893).
  14   Confirmar a conversão para WAV e alinhar o diálogo
       (FfmpegJoinVideosActivity.kt:538-542, :1863).

P3 — qualidade, fidelidade e validação:
  5    Cópia com corte em PCM/FLAC + UI de qualidade no Cortar.
  12   -c:a copy no Juntar quando os clipes forem idênticos.
  11   Preservar N faixas quando a contagem coincidir entre os clipes.
  1    Validar o container real no Girar, não a extensão.
  13   Container por ffprobe no concat direto.

P4 — limpeza e endurecimento:
  19 / 24 / 26  operador "?" em audioStreamSpecifier — 1 linha + 2 testes.
  3             reaplicar updateMetadataModeState após o processamento.
  29            remover videoBitstreamFilter.
  NOVO-A        separar falha de abertura de ausência de faixa.
  28            testes dos grafos de filter_complex.

Observação final: análise estática (leitura integral das seis Activities, de
FfmpegMediaPolicies.kt, dos layouts e dos testes), rg,
git diff 85b889f..52f6253, e execução da suíte unitária
(:app:testDebugUnitTest → FfmpegMediaPoliciesTest 16 testes, 0 falhas).
NÃO houve execução com mídia real nem com o binário FFmpeg. Os dois itens de
P1 são conclusões sobre a FORMA DOS COMANDOS gerados, deduzidas do código:
validar em runtime com um WAV de 10 s cortado a 9,9 s e um MKV com fonte
embutida antes de fechar as correções.
================================================================================
