================================================================================
CORREÇÕES PENDENTES — FERRAMENTAS FFmpeg (SIG Android)
================================================================================
Repositório: D:\Projetos\SIG
Commit auditado: 52f6253
Data: 2026-08-31

Arquivos envolvidos:
  app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegRotateVideoActivity.kt
  app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegCutActivity.kt
  app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegJoinVideosActivity.kt
  app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegExtractAudioActivity.kt
  app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegInsertAudioActivity.kt
  app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegCleanAudioActivity.kt
  app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegMediaPolicies.kt
  app/src/test/java/br/gov/sp/pcsp/launcher/FfmpegMediaPoliciesTest.kt

Verificação: leitura do código, busca por símbolo e execução da suíte unitária
  ./gradlew.bat :app:testDebugUnitTest --tests "br.gov.sp.pcsp.launcher.Ffmpeg*"
  BUILD SUCCESSFUL — FfmpegMediaPoliciesTest: 16 testes, 0 falhas
Não houve execução com arquivos de mídia reais nem com o binário FFmpeg.

Total: 17 correções pendentes.
  ALTA (1) ..... 12
  MÉDIA (8) .... 1, 4, 5, 6, 7, 8, 9, 3
  BAIXA (8) .... 2, 10, 11, 13, 14, 15, 16, 17

================================================================================
GIRAR VÍDEO — FfmpegRotateVideoActivity.kt
================================================================================

1) O preflight do modo por metadados valida apenas a extensão do arquivo,
   não o container real.
   Onde: FfmpegMediaPolicies.kt:294-297
             fun safeContainerExtension(name: String): String =
                 name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                     .takeIf { it in setOf("mp4","m4v","mov","mkv","webm","avi") }
                     ?: "mkv"
         FfmpegRotateVideoActivity.kt:574-607 (onde o preflight é chamado)
         FfmpegMediaPolicies.kt:48-60 (metadataCopyPreflightArguments)
   Detalhe: o preflight é uma cópia de 1 ms (-t 0.001) com -loglevel error.
   Um arquivo cuja extensão não corresponde ao conteúdo, ou que contenha um
   stream que o muxer de destino não aceite, passa pela prévia e só falha na
   cópia completa.
   Comando gerado:
     -y -display_rotation:v:0 <g> -i <in> -map 0 -c copy <out>
     preflight: mesmo comando + -hide_banner -loglevel error -t 0.001
   Impacto: falha tardia da operação, com erro visível do FFmpeg. Não há
   corrupção silenciosa.
   Correção: consultar o container real do arquivo e validar codecs, legendas e
   anexos contra o container de saída antes de executar.
   Gravidade: MÉDIA

2) O checkbox de paralelismo volta a ficar habilitado após a operação, mesmo
   com o modo por metadados ativo, e o pedido é ignorado em silêncio.
   Onde: FfmpegRotateVideoActivity.kt:1563-1564
             parallelKeyframes.isEnabled = !processing
             inputParallelSegments.isEnabled = !processing
         Contraste — :1462
             parallelKeyframes.isEnabled = !metadataOnly
         O pedido é descartado em :521 (canUseParallel exclui metadataOnly).
   Detalhe: updateMetadataModeState desabilita o controle e aplica alpha 0.42f,
   mas setProcessing(false) reabilita sem consultar metadataOnly. O estado só
   se re-sincroniza se o usuário alternar o checkbox de metadados.
   Impacto: o usuário marca a opção, a execução ignora e nada avisa.
   Correção: reaplicar updateMetadataModeState() ao fim do processamento, ou
   avisar quando requestedParallel && metadataOnly.
   Gravidade: BAIXA

================================================================================
CORTAR ÁUDIO/VÍDEO — FfmpegCutActivity.kt
================================================================================

3) O corte de áudio reencoda em qualquer intervalo real; não há cópia com
   corte nem controles de qualidade de áudio na interface.
   Onde: FfmpegCutActivity.kt:668-675
             val hasRealTrim = startMs > 0L ||
                 (inputDurationMs > 0L && endMs < inputDurationMs - 10L)
             val encoderArguments = if (!hasRealTrim) listOf("-c:a", "copy")
                 else preciseAudioEncoderArguments(...)
         FfmpegMediaPolicies.kt:65-74 (cutAudioEncoderArguments)
         FfmpegMediaPolicies.kt:72 (MP3 fixo em CBR)
         FfmpegCutActivity.kt:1807 (FALLBACK_AUDIO_BITRATE = "192k")
         FfmpegCutActivity.kt:1339-1344 (controles só para vídeo)
         activity_ffmpeg_cut.xml (não há controle de bitrate nem CBR/VBR)
   Detalhe: -c:a copy só existe quando não há corte de fato. Com corte, o
   encoder é sempre escolhido — inclusive para WAV e FLAC, que são de taxa
   constante e poderiam ser copiados com alinhamento de amostra. MP3 sai sempre
   em CBR (-b:a). Quando MediaFormat não informa KEY_BIT_RATE (comum em WAV,
   FLAC e PCM), o bitrate fica fixo em 192k, sem possibilidade de ajuste.
   O limiar de 10 ms em :670 trata "fim próximo da duração" como "sem corte",
   usando cópia por pacote sem garantir o limite exato; o tracker ainda pode
   exibir "Recodificando áudio" mesmo com -c:a copy.
   Comando gerado (com corte):
     -y -ss <s> -i <in> -t <s> -map 0:a? -map_metadata 0 -map_chapters 0 -vn
        -c:a pcm_s24le|aac|libmp3lame -b:a <k> -avoid_negative_ts make_zero <out>
   Impacto: reprocessamento e perda desnecessários; MP3 sempre CBR. Nenhum
   comando incorreto.
   Correção: (i) liberar -c:a copy com corte quando codec, container e
   intervalo forem compatíveis (WAV/FLAC), alinhando ao limite de amostra;
   (ii) expor bitrate e CBR/VBR na interface; (iii) diferenciar "fim igual à
   duração" de "fim próximo da duração".
   Gravidade: MÉDIA

4) A queda para reencode completo acontece sem aviso nem consentimento.
   Onde: FfmpegCutActivity.kt:722-725
             if (sourceCodec !in setOf("h264","hevc") ||
                 actualEncoder.codecFamily != sourceCodec) {
                 tracker.appendTasks(listOf("Caminho rápido indisponível: $reason"))
                 return executeFullPrecisionFallback(...)
             }
         FfmpegCutActivity.kt:737-738
             if (startKeyframe == null || endKeyframe == null ||
                 startKeyframe >= endKeyframe) {
                 tracker.appendTasks(listOf("Caminho rápido indisponível: não há keyframes internos suficientes"))
                 return executeFullPrecisionFallback(...)
             }
         executeFullPrecisionFallback: FfmpegCutActivity.kt:835-875
   Detalhe: nenhum diálogo antecede as duas chamadas. Apenas uma linha de
   tracker é emitida, e depois da decisão tomada.
   Impacto: o usuário pede corte rápido e recebe reencode completo — minutos a
   mais em vídeo longo e perda geracional de qualidade, sem poder de escolha.
   Correção: exibir diálogo antes do fallback, informando motivo e custo
   estimado, com opção de cancelar; ou registrar a decisão no resultado final.
   Gravidade: MÉDIA

5) Bitrate de vídeo fixo em 15M quando a detecção falha.
   Onde: FfmpegCutActivity.kt:1806
             private const val FALLBACK_VIDEO_BITRATE = "15M"
         Uso: FfmpegCutActivity.kt:700 (modo preciso) e :893 (fallback)
             videoEncodingArguments(enc, streamBitrates.video ?: FALLBACK_VIDEO_BITRATE, quality)
         Origem do valor nulo: FfmpegCutActivity.kt:1044-1045 (detectStreamBitrates)
             runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()
                 ?.takeIf { it > 0 }?.let { "${(it / 1000).coerceAtLeast(1)}k" }
   Detalhe: KEY_BIT_RATE está ausente em boa parte dos MKV, WebM e arquivos
   remuxados. Nesses casos o valor não é estimado nem perguntado.
   Impacto: clipe de 480p é reencodado a 15 Mbps, gerando arquivo muito maior
   que a origem ou falha de encoder em dispositivo com limite de nível; o
   inverso (4K acima de 15 Mbps) fica sub-bitratado.
   Correção: estimar o bitrate por resolução, taxa de quadros e codec quando a
   detecção falhar, ou expor o campo de qualidade para ajuste manual.
   Gravidade: MÉDIA

6) O corte de vídeo descarta anexos (fontes de legenda) silenciosamente.
   Onde: FfmpegCutActivity.kt:697 (modo preciso)
         FfmpegCutActivity.kt:890 (bordas do corte híbrido)
         FfmpegMediaPolicies.kt:195 (hybridCopyBodyArguments)
   As três listas são idênticas:
             "-map", "0:v:0?", "-map", "0:a?", "-map", "0:s?", "-map", "0:d?"
   Contraste: o Girar preserva, em FfmpegRotateVideoActivity.kt:1129-1133 e
   :1332, com "-map", "0:t?" acrescentado.
   Detalhe: falta também -c:t copy. A legenda é preservada (0:s?), mas a fonte
   embutida é perdida.
   Comando gerado:
     -y -noautorotate [-display_rotation:v:0 <g>] -ss <ini> -i <in> -t <dur>
        -map 0:v:0? -map 0:a? -map 0:s? -map 0:d? -map_metadata 0
        -map_chapters 0 -c copy -avoid_negative_ts make_zero <out>.mkv
   Impacto: MKV com legenda ASS e fonte embutida passa a ser exibido com fonte
   substituta, sem nenhum aviso. É perda real de conteúdo.
   Correção: acrescentar "-map", "0:t?" e "-c:t copy" nas três rotas.
   Gravidade: MÉDIA

================================================================================
JUNTAR VÍDEO/ÁUDIO — FfmpegJoinVideosActivity.kt
================================================================================

7) As rotas recodificadas exportam uma única faixa de áudio e descartam dados
   e anexos sem aviso.
   Onde: FfmpegJoinVideosActivity.kt:713 (junta de áudio, com transição)
         FfmpegJoinVideosActivity.kt:724 (junta de áudio, sem transição)
             "concat=n=${clips.size}:v=0:a=1[aout]"
         FfmpegJoinVideosActivity.kt:872 (vídeo, com transição)
         FfmpegJoinVideosActivity.kt:907 (vídeo, sem transição)
             "concat=n=${clips.size}:v=1:a=1[vout][aout]"
         FfmpegJoinVideosActivity.kt:569 (múltiplas faixas bloqueiam o
             caminho direto para vídeo)
         Aviso apenas para legendas: FfmpegJoinVideosActivity.kt:503-511
   Detalhe: o diálogo de seleção (requestAudioTrack, :1860-1871) informa que as
   demais faixas "não entrarão na saída recodificada", mas não há rota que as
   preserve. Legendas recebem diálogo de confirmação; dados e anexos, não.
   Impacto: clipes com 3 faixas de áudio resultam em 1 faixa. A perda de faixas
   é anunciada; a de dados e anexos não.
   Correção: quando a contagem de faixas coincidir entre todos os clipes, gerar
   um filter_complex por faixa e um -map por faixa de saída; estender o aviso
   das legendas a dados e anexos.
   Gravidade: MÉDIA

8) A rota de reencode nunca emite -c:a copy e não informa o codec de destino
   antes de executar.
   Onde: FfmpegMediaPolicies.kt:134-149 (joinAudioCommandArguments — sempre
             emite -c:a <encoder>)
         FfmpegJoinVideosActivity.kt:685-736 (buildAudioReencodeArguments)
         FfmpegJoinVideosActivity.kt:810-821 (audioEncodingArguments)
   Detalhe: áudios idênticos entre si são recodificados. O aviso existe apenas
   para a conversão forçada a WAV (:654-660), não para o reencode voluntário.
   Comando gerado:
     -filter_complex "...concat..." -map [aout] -vn -c:a <enc> -ar <r> -ac <c> -b:a <k>
   Impacto: perda em AAC/MP3/Opus/Vorbis e tempo desperdiçado quando os clipes
   já são compatíveis e o usuário só queria transição ou corte de tempo.
   Correção: emitir -c:a copy quando codec, taxa, canais e sample format forem
   idênticos entre todos os clipes; informar codec e perfil de destino antes da
   execução do reencode manual.
   Gravidade: MÉDIA

9) A validação do concat direto deduz o container da extensão do arquivo.
   Onde: FfmpegJoinVideosActivity.kt:1744
             val containerFamily = FfmpegMediaPolicies.containerFamily(file.name)
         FfmpegMediaPolicies.kt:299-311
             when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                 "mp4","m4v","mov" -> "mov" ; ... else -> "unknown" }
         Descrição de streams por regex: FfmpegJoinVideosActivity.kt:1793
   Detalhe: a assinatura compara 23 campos (codec, perfil, nível, resolução,
   taxa, canais, sample format, channel layout, time base, CSD), mas um deles —
   o container — vem do nome do arquivo, não do muxer real.
   Comando gerado:
     -y -fflags +genpts -f concat -safe 0 -i <lista> -map 0 -map_metadata 0
        -map_chapters 0 -c copy -avoid_negative_ts make_zero <out>
   Impacto: dois arquivos com a mesma extensão incorreta recebem o mesmo
   containerFamily e podem ser considerados compatíveis. A proteção real vem do
   ffmpegDescriptor, que reflete o conteúdo — o furo é estreito, mas existe.
   Correção: consultar o formato real via FFmpeg/ffprobe e rejeitar qualquer
   concat cuja compatibilidade a nível de muxer não possa ser comprovada.
   Gravidade: MÉDIA

10) A conversão de áudios incompatíveis para WAV ocorre sem confirmação, e o
    diálogo de seleção de faixa contradiz o resultado.
    Onde: FfmpegJoinVideosActivity.kt:501
              val hasSelectedMultitrackAudio = clips.any { audioTrackCount(it.uri) > 1 }
          FfmpegJoinVideosActivity.kt:538-541
              val audioNeedsNormalization = audioOnly &&
                  (directConcatIncompatibility != null || firstAudioExtension == "mp3"
                   || hasSelectedMultitrackAudio)
              val audioWillStandardizeToWav = audioNeedsNormalization && !reencodeChecked
          FfmpegJoinVideosActivity.kt:542 (nome do arquivo forçado antes de
              executar) e :1716 (extensão "wav")
          FfmpegJoinVideosActivity.kt:1863 (texto do diálogo)
    Detalhe: o usuário é informado pela linha de etapa (:654-660, "Convertendo
    áudios incompatíveis para WAV no perfil agregado"), mas essa linha aparece
    durante a execução. A única alternativa é cancelar a operação inteira.
    Quando o gatilho é áudio multi-faixa, o diálogo diz "as demais não entrarão
    na saída recodificada" e o resultado é WAV — formato, taxa e profundidade
    podem mudar sem que isso tenha sido dito.
    Comando gerado:
      -y -i A -i B -filter_complex
         "[0:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a0];
          [1:a:0]...[a1]; [a0][a1]concat=n=2:v=0:a=1[aout]"
         -map [aout] -vn -c:a pcm_s16le -ar 48000 -ac 2
         -avoid_negative_ts make_zero <nome>.wav
    Impacto: usuário não pode recusar a conversão mantendo a junção; a
    contradição entre diálogo e resultado confunde.
    Correção: diálogo de confirmação antes da conversão (mesmo padrão do modo
    forte do Limpar Áudio, FfmpegCleanAudioActivity.kt:182-194) e alinhamento
    do texto de :1863 quando a saída for WAV.
    Gravidade: BAIXA

11) Estado morto: videoBitstreamFilter é preenchido e nunca lido.
    Onde: FfmpegJoinVideosActivity.kt:1950, :1989, :2010 (atribuições)
          FfmpegJoinVideosActivity.kt:2276 (declaração)
          FfmpegJoinVideosActivity.kt:2181 (videoBitstreamFilterFor)
    Detalhe: a busca pelo identificador no arquivo devolve apenas essas cinco
    linhas. Não há nenhuma leitura do campo.
    Impacto: nenhuma rota ativa é afetada. O risco é de manutenção: o campo
    sugere uma rota inexistente e pode reativar lógica removida de forma
    incompleta.
    Correção: remover videoBitstreamFilter e videoBitstreamFilterFor, ou voltar
    a consumi-los em uma rota comprovadamente necessária.
    Gravidade: BAIXA

================================================================================
EXTRAIR ÁUDIO — FfmpegExtractAudioActivity.kt
================================================================================

12) O comando é gerado SEM -t quando o fim escolhido cai dentro da tolerância
    de 250 ms, exportando o arquivo inteiro em vez do trecho pedido.
    Onde: FfmpegExtractAudioActivity.kt:860
              val hasEndTrim = endMs != null && inputDuration > 0L &&
                               endMs < inputDuration - 250L
          FfmpegExtractAudioActivity.kt:869 (duration = null quando falso)
              duration = if (hasEndTrim) formatSeconds(endMs - startMs) else null
          FfmpegExtractAudioActivity.kt:945 — a mesma tolerância decide a cópia:
              if (startMs > 0L || (endMs != null && duration > 0L &&
                  endMs < duration - 250L)) return false
    Detalhe: arquivo de 10.000 ms com fim escolhido em 9.900 ms →
    `9900 < 9750` é falso → hasEndTrim = false → duration = null → o comando não
    recebe -t. Com início em 0, sai o arquivo completo. Com início maior que 0,
    sai do início escolhido até o fim original. E como a cópia é liberada pela
    mesma condição em :945, sai com -c:a copy.
    Comando gerado (com o defeito):
      -y -i <in> -vn -map 0:a:N -map_metadata 0 -c:a copy
         -avoid_negative_ts make_zero <out>
      ^^^ sem -t
    Impacto: saída com conteúdo errado, sem erro de execução e sem aviso. É a
    correção mais urgente da lista.
    Correção: emitir -t sempre que o usuário escolher um fim real; remover a
    tolerância de 250 ms ou reduzi-la a um limiar de arredondamento que não
    desligue o -t.
    Gravidade: ALTA

    Pendências secundárias da mesma ferramenta:
    - Cópia sem perdas negada com qualquer corte real e condicionada a
      taxa e canais idênticos: FfmpegExtractAudioActivity.kt:945 e :964.
      Correção: liberar a cópia com corte para WAV e FLAC (taxa constante),
      alinhando ao limite de amostra.
    - MP3 sempre em CBR: FfmpegMediaPolicies.kt:93
          "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", bitrate)
      Correção: expor CBR/VBR (ou -q:a) na interface.
    - O teste extractionUsesVbrCapableMp3AndAudioOpusProfile
      (FfmpegMediaPoliciesTest.kt:88-108) tem nome que não corresponde ao que
      verifica: afirma apenas a ausência de -minrate e -maxrate, enquanto os
      argumentos reais são CBR. Correção: renomear.

13) O mapa da faixa de áudio é emitido sem o operador de tolerância "?".
    Onde: FfmpegExtractAudioActivity.kt:873
              audioMap = FfmpegMediaPolicies.audioStreamSpecifier(0, audioTrack)
          FfmpegMediaPolicies.kt:62-63
              fun audioStreamSpecifier(inputIndex: Int, audioTrackIndex: Int): String =
                  "$inputIndex:a:${audioTrackIndex.coerceAtLeast(0)}"
          Teste que congela o formato: FfmpegMediaPoliciesTest.kt:60-64
    Detalhe: o índice é validado antes da execução (arquivo sem áudio é
    rejeitado em :739-742; múltiplas faixas disparam seleção em :743-748), de
    modo que hoje o mapa sempre aponta para uma faixa existente.
    Comando gerado:
      -y [-ss X] -i <in> [-t Y] -vn -map 0:a:<n> -map_metadata 0
         [-c:a copy | -ar SR -ac CH <enc>] -avoid_negative_ts make_zero <out>
    Impacto: nenhum hoje. O "?" protegeria contra divergência futura entre a
    ordem de faixas do MediaExtractor e a do FFmpeg, ou caso a validação prévia
    seja alterada.
    Correção: trocar por "$inputIndex:a:${idx}?". Uma linha, mas vale ao mesmo
    tempo para Extrair, Inserir (item 14) e Limpar (item 15); exige atualizar
    FfmpegMediaPoliciesTest.kt:62-63 e :145.
    Gravidade: BAIXA

================================================================================
INSERIR ÁUDIO — FfmpegInsertAudioActivity.kt
================================================================================

14) Os três mapas de faixa do filtro são emitidos sem o operador "?".
    Onde: FfmpegInsertAudioActivity.kt:586 (trecho esquerdo)
              [${audioStreamSpecifier(0, jobConfig.mainAudioTrack)}]
          FfmpegInsertAudioActivity.kt:593 (áudio inserido)
              [${audioStreamSpecifier(1, jobConfig.insertedAudioTrack)}]
          FfmpegInsertAudioActivity.kt:597 (trecho direito)
              [${audioStreamSpecifier(0, jobConfig.mainAudioTrack)}]
          Função compartilhada: FfmpegMediaPolicies.kt:62-63
          Rota única: buildFullReencodeArguments (:567-625), chamada em :520
    Detalhe: a faixa escolhida pelo usuário é honrada nos três pontos; falta
    apenas a tolerância.
    Comando gerado:
      -y -i PRINCIPAL -i INSERIDO -filter_complex
         "[0:a:<n>]atrim=start=0:end=10.000,aresample=48000,aformat=...,asetpts=PTS-STARTPTS[a0];
          [1:a:<m>]atrim=start=0:end=4.000,...[a1];
          [0:a:<n>]atrim=start=10.000:end=60.000,...[a2];
          [a0][a1][a2]concat=n=3:v=0:a=1[aout]"
         -map [aout] -vn -c:a aac -b:a 192k -ar 48000 -ac 2 -movflags +faststart
         -avoid_negative_ts make_zero <out>.m4a
    Impacto: nenhum hoje. Defesa em profundidade, igual aos itens 13 e 15.
    Correção: adicionar "?" aos três mapas (uma linha, função compartilhada).
    Gravidade: BAIXA

================================================================================
LIMPAR ÁUDIO — FfmpegCleanAudioActivity.kt
================================================================================

15) O mapa da faixa de áudio é emitido sem o operador "?".
    Onde: FfmpegCleanAudioActivity.kt:270
              audioMap = FfmpegMediaPolicies.audioStreamSpecifier(0, 0)
          Função compartilhada: FfmpegMediaPolicies.kt:62-63
          Teste que congela o formato: FfmpegMediaPoliciesTest.kt:141-153
    Detalhe: a Activity exige exatamente uma faixa de áudio antes de executar
    (:177-181), portanto o índice 0 é sempre válido.
    Comando gerado:
      -y -i <in> -vn -map 0:a:0 -af afftdn=nf=-25 -c:a pcm_s32le -ar 96000
         -ac 6 -avoid_negative_ts make_zero -f wav <out>.wav
    Impacto: nenhum na rota atual.
    Correção: usar "0:a:0?" (uma linha, função compartilhada); exige atualizar
    FfmpegMediaPoliciesTest.kt:145.
    Gravidade: BAIXA

================================================================================
TRANSVERSAL
================================================================================

16) Os grafos de filter_complex não têm nenhuma cobertura de teste.
    Onde: FfmpegJoinVideosActivity.kt:876-910 (buildFilterComplex —
              concat v=1:a=1, xfade, acrossfade)
          FfmpegJoinVideosActivity.kt:823-874 (buildFadeInOutFilterComplex —
              fade, afade)
          FfmpegJoinVideosActivity.kt:685-736 (buildAudioReencodeArguments —
              concat v=0:a=1, acrossfade)
          FfmpegInsertAudioActivity.kt:567-625 (buildFullReencodeArguments —
              atrim, afade, acrossfade)
    Detalhe: os construtores de comando foram extraídos para FfmpegMediaPolicies
    e cobertos por 16 testes, mas os grafos permanecem privados nas Activities.
    Também fora de cobertura: as decisões de rota (fallback do corte híbrido,
    recusa do concat direto, preflight do Girar) e os helpers dependentes de
    MediaExtractor (detectPcmEncoder, detectStreamBitrates,
    streamCopySignatures, detectAggregateOutputProfile, videoTrackCount).
    Impacto: uma regressão na ordem de asetpts e afade, ou na aridade do
    concat, não é detectada por nenhum teste — e o commit 52f6253 alterou
    exatamente essas rotas.
    Correção: extrair os construtores de filter_complex para funções puras
    (entrada: clipes + perfil; saída: String) e cobri-los com testes de unidade.
    Gravidade: BAIXA

17) Falha do MediaExtractor aborta a operação com a mensagem "não possui uma
    faixa de vídeo", atribuindo ao arquivo um erro do extrator.
    Onde: FfmpegRotateVideoActivity.kt:499-511 (videoTrackCount)
              } catch (_: Throwable) {
                  0
              }
          Uso: FfmpegRotateVideoActivity.kt:450-458
                  if (videoTracks != 1) {
                      status.text = "O arquivo não possui uma faixa de vídeo."
                      return
                  }
          FfmpegCutActivity.kt:491-498 (mesma checagem: "O arquivo não possui
              vídeo.")
    Detalhe: falha de abertura e ausência de faixa são indistinguíveis — ambas
    devolvem 0. A checagem é anterior a qualquer chamada de FFmpegKit, portanto
    não há segunda oportunidade. Contraste: detectMediaMime
    (FfmpegCutActivity.kt:472-487) degrada com elegância para
    contentResolver.getType(uri) no mesmo catch.
    Impacto: containers que o extrator de determinado fabricante não abra
    (variações de MPEG-TS, MOV com codec não registrado, metadados corrompidos)
    ficam inutilizáveis, embora o FFmpeg os abra. O erro exibido induz a
    diagnóstico errado em campo.
    Correção: fazer videoTrackCount distinguir "falha ao abrir" de "zero
    faixas" (por exemplo, um selo TrackProbe.Failed contra TrackProbe.Count(n))
    e, na falha, exibir "Não foi possível inspecionar o arquivo" — opcionalmente
    oferecendo prosseguir sem a checagem.
    Gravidade: BAIXA

================================================================================
ORDEM DE EXECUÇÃO
================================================================================

P1 — saída errada e perda de dados:
  12  Extrair: emitir -t sempre que houver fim real escolhido
      (FfmpegExtractAudioActivity.kt:860, :869, :945).
  6   Cortar: acrescentar -map 0:t? e -c:t copy
      (FfmpegCutActivity.kt:697, :890; FfmpegMediaPolicies.kt:195).

P2 — decisão tomada sem o usuário:
  4   Diálogo antes do fallback de reencode
      (FfmpegCutActivity.kt:722-725, :737-738).
  5   Estimar bitrate em vez de fixar 15M
      (FfmpegCutActivity.kt:1806, :700, :893).
  10  Confirmar a conversão para WAV e alinhar o texto do diálogo
      (FfmpegJoinVideosActivity.kt:538-542, :1863).

P3 — qualidade, fidelidade e validação:
  3   Cópia com corte em WAV/FLAC e controles de áudio na interface.
  8   -c:a copy no Juntar quando os clipes forem idênticos.
  7   Preservar N faixas quando a contagem coincidir entre os clipes.
  1   Validar o container real no Girar.
  9   Container por ffprobe no concat direto.

P4 — limpeza e endurecimento:
  13 / 14 / 15  operador "?" em audioStreamSpecifier — 1 linha + 2 testes.
  2   Reaplicar updateMetadataModeState após o processamento.
  11  Remover videoBitstreamFilter.
  17  Separar falha de abertura de ausência de faixa.
  16  Testes dos grafos de filter_complex.

Os itens de P1 foram deduzidos da forma dos comandos gerados, sem execução do
FFmpeg. Validar em runtime com um WAV de 10 s cortado a 9,9 s e com um MKV
contendo fonte embutida antes de encerrar as duas correções.
================================================================================
