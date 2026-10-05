================================================================================
RELATÓRIO DOS FINDINGS PARCIALMENTE CORRIGIDOS — FERRAMENTAS FFmpeg (SIG)
================================================================================
Data:          2026-08-31
HEAD auditado: 52f6253 ("fix: validar compatibilidade e remover legado ffmpeg")
Base:          85b889f
Origem:        findings_consolidados_ffmpeg_2026-08-31.txt (29 findings)
Recorte:       apenas os classificados como PARCIALMENTE CORRIGIDO
Total:         10 itens — 5, 11, 12, 14, 18, 19, 24, 26, 27, 28

Método: leitura integral do código atual, `rg`, `git diff 85b889f..52f6253`,
        execução da suíte unitária. Nenhum arquivo alterado.

Teste executado (registro):
  ./gradlew.bat :app:testDebugUnitTest --tests "br.gov.sp.pcsp.launcher.Ffmpeg*"
      --rerun-tasks --console=plain
  Resultado: BUILD SUCCESSFUL in 39s — 26 actionable tasks: 26 executed
  TEST-br.gov.sp.pcsp.launcher.FfmpegMediaPoliciesTest.xml
      tests="16" skipped="0" failures="0" errors="0"

Campos de cada item: Status / Finding / Evidência no código /
Argumentos FFmpeg efetivos / Impacto residual / Fix restante / Gravidade

================================================================================
RESUMO DOS 10 ITENS
================================================================================
Item  Ferramenta  O que foi corrigido              O que falta
----- ----------- -------------------------------- ------------------------------
 5    Cortar      -c:a copy sem corte; PCM depth   CBR/VBR e qualidade na UI
11    Juntar      perda divulgada; contagem faixas aridade a=1; múltiplas faixas
12    Juntar      codec/perfil agregado preservado sem -c:a copy
14    Juntar      WAV anunciado no nome e no passo sem diálogo de confirmação
18    Extrair     PCM depth; mimes WAV; CBR limpo  cópia com corte; CBR/VBR
19    Extrair     índice dinâmico + validação      operador "?"
24    Inserir     rotas legadas removidas          operador "?"
26    Limpar      taxa/canais/depth preservados    operador "?"
27    Transversal política coerente por ferramenta sem política única; 0:t no Cortar
28    Transversal 16 testes dos builders           grafos filter_complex

Por gravidade residual:
  MÉDIA (1): 27 — descarte de anexos (NOVO-1) e duplicação da política de mapas
  BAIXA (9): 5, 11, 12, 14, 18, 19, 24, 26, 28

Nenhum dos 10 produz comando FFmpeg incorreto na configuração padrão.

================================================================================
FERRAMENTA DE CORTAR ÁUDIO/VÍDEO — FfmpegCutActivity.kt
================================================================================

5) Status: PARCIALMENTE CORRIGIDO
Finding: o corte de áudio sempre reencodava. Não existia `-c:a copy` em nenhum
  ponto do arquivo; `preciseAudioEncoderArguments` fixava `pcm_s16le` para WAV
  (derrubando 24/32-bit), `libmp3lame` com `-b:a` fixo para MP3 (CBR, sem VBR) e
  `aac` para o restante, mesmo quando a origem poderia ser copiada sem perda.
  Também não havia exposição de qualidade de saída para o corte de áudio.

Evidência no código:
  - (b) RESOLVIDO — profundidade PCM por origem.
    `detectPcmEncoder(inputFile)` (linhas 1010-1029) abre o arquivo com
    `MediaExtractor`, isola a primeira faixa `audio/` e lê
    `MediaFormat.KEY_PCM_ENCODING`:
        ENCODING_PCM_8BIT          -> "pcm_u8"
        ENCODING_PCM_FLOAT         -> "pcm_f32le"
        ENCODING_PCM_24BIT_PACKED  -> "pcm_s24le"
        ENCODING_PCM_32BIT         -> "pcm_s32le"
        else                       -> "pcm_s16le"
    O valor é passado por `preciseAudioEncoderArguments` (linhas 1002-1008) a
    `FfmpegMediaPolicies.cutAudioEncoderArguments` (FfmpegMediaPolicies.kt:65-74),
    cujo branch `wav` (linha 67) emite `listOf("-c:a", pcmEncoder)`.
    Teste: `FfmpegMediaPoliciesTest.cutAudioArgumentsPreservePcmDepthAndDeclareCommonContainers`
    (linhas 66-86) — afirma `wav` -> `[-c:a, pcm_s24le]`.
  - (a) PARCIAL — existe `-c:a copy`, mas somente sem corte real.
    `buildPreciseFfmpegArguments` linhas 668-675:
        val hasRealTrim = startMs > 0L ||
            (inputDurationMs > 0L && endMs < inputDurationMs - 10L)
        val encoderArguments = if (!hasRealTrim) listOf("-c:a", "copy")
            else preciseAudioEncoderArguments(selectedName, streamBitrates.audio
                 ?: FALLBACK_AUDIO_BITRATE, inputFile)
    Com corte real não há cópia alinhada a pacote, nem em WAV nem em FLAC.
  - (c) NÃO FEITO — a UI não expõe qualidade de áudio.
    `activity_ffmpeg_cut.xml` contém apenas `button_video_encoder`,
    `button_video_quality`, `label_video_encoder`, `label_video_quality` e os
    campos de tempo. Não há nenhum controle de bitrate, CBR/VBR ou profundidade.
    `showEditingControls` (linhas 1339-1344) mostra encoder/qualidade somente
    quando `selectedMime.startsWith("video/")`.

Argumentos FFmpeg efetivos (WAV 24-bit, sem corte real — cópia):
  ffmpeg -y -ss 0.000 -i IN -t 12.500 -map 0:a? -map_metadata 0 -map_chapters 0
         -vn -c:a copy -avoid_negative_ts make_zero OUT.wav

Argumentos FFmpeg efetivos (WAV 32-bit, com corte — reencode):
  ffmpeg -y -ss 3.000 -i IN -t 5.000 -map 0:a? -map_metadata 0 -map_chapters 0
         -vn -c:a pcm_s32le -avoid_negative_ts make_zero OUT.wav

Argumentos FFmpeg efetivos (MP3, com corte):
  ffmpeg -y -ss 1.500 -i IN -t 8.000 -map 0:a? -map_metadata 0 -map_chapters 0
         -vn -c:a libmp3lame -b:a 192k -avoid_negative_ts make_zero OUT.mp3

Cenário que ainda falha:
  Usuário recorta 10 s de um WAV 24-bit/96 kHz no meio do arquivo. O corte
  reencoda para `pcm_s24le` — sem perda de profundidade hoje — mas continua
  sem `-c:a copy`, e o usuário não tem como escolher a qualidade de saída, que
  fica fixa em `FALLBACK_AUDIO_BITRATE = "192k"` (linha 1807) quando o
  MediaFormat não informa `KEY_BIT_RATE` (comum em WAV, FLAC e PCM).

Impacto residual:
  Nenhum comando incorreto. A cópia, quando ocorre, é segura porque
  `buildOutputName` (linhas 1241-1246) preserva a extensão da origem para áudio
  — o container de saída é sempre igual ao de entrada. Perde-se apenas a
  oportunidade de cópia com corte e a escolha de qualidade.

Fix restante, se houver:
  (i) liberar `-c:a copy` com corte quando a origem for PCM ou FLAC (taxa
      constante), alinhando o corte ao limite de amostra;
  (ii) expor na UI do Cortar um seletor de qualidade de áudio (bitrate e
      CBR/VBR), hoje existente só para vídeo.

Gravidade: BAIXA (qualidade e exposição; nenhum comando incorreto)

================================================================================
FERRAMENTA DE JUNTAR VÍDEO/ÁUDIO — FfmpegJoinVideosActivity.kt
================================================================================

11) Status: PARCIALMENTE CORRIGIDO
Finding: a aridade de áudio continua 1 em todos os caminhos. O seletor de faixa
  por clipe (`requestAudioTrack`) pergunta qual faixa usar, mas o resultado
  exporta uma única faixa: `concat=n=N:v=0:a=1` na junta de áudio e
  `concat=n=N:v=1:a=1` no reencode de vídeo. Faixas secundárias e legendas são
  descartadas sem nenhum aviso de quantas foram perdidas.
  `SmartJoinPlanner.profilesCompatible` comparava o codec de áudio, mas não a
  quantidade de faixas entre clipes.

Evidência no código:
  - MELHORADO — a perda passou a ser divulgada.
    `requestAudioTrack` (linhas 1860-1871) usa o título
    "Escolha 1 das ${labels.size} faixas de ${clip.name}; as demais não entrarão
    na saída recodificada". Só é exibido quando há mais de uma faixa
    (`startJoin`, linhas 494-499).
  - MELHORADO — a contagem de faixas entrou na comparação de perfis.
    `SmartJoinPlanner.kt` e `SmartJoinPlannerTest.kt` foram DELETADOS no commit
    52f6253. A comparação agora é
    `FfmpegMediaPolicies.directConcatSignaturesCompatible`
    (FfmpegMediaPolicies.kt:313-321), que exige igualdade da LISTA INTEIRA de
    assinaturas — listas iguais implicam o mesmo número de faixas.
  - NÃO FEITO — a aridade segue 1:
      linha 713 (junta de áudio, fade in/out):
        "concat=n=${clips.size}:v=0:a=1[aout]"
      linha 724 (junta de áudio, sem transição ou acrossfade):
        "concat=n=${clips.size}:v=0:a=1[aout]"
      linha 872 (vídeo, fade in/out):
        "concat=n=${clips.size}:v=1:a=1[vout][aout]"
      linha 907 (vídeo, sem transição ou xfade):
        "concat=n=${clips.size}:v=1:a=1[vout][aout]"
  - O índice escolhido é honrado: `audioInputLabel` (linhas 1836-1840) devolve
    `FfmpegMediaPolicies.audioStreamSpecifier(inputIndex, track)`, com `track`
    vindo de `selectedAudioTracks` (padrão 0).

Argumentos FFmpeg efetivos (junta de 2 áudios com reencode):
  ffmpeg -y -i A -i B -filter_complex
    "[0:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a0];
     [1:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a1];
     [a0][a1]concat=n=2:v=0:a=1[aout]"
    -map "[aout]" -vn -c:a aac -ar 48000 -ac 2 -b:a 192k
    -avoid_negative_ts make_zero OUT

Argumentos FFmpeg efetivos (reencode de 2 vídeos sem transição):
  ffmpeg -y -i A -i B -filter_complex
    "[0:v]scale=W:H:force_original_aspect_ratio=decrease,pad=W:H:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p[v0];
     [0:a:0]aresample=48000,aformat=...[a0];
     [1:v]scale=...,pad=...,setsar=1,fps=30,format=yuv420p[v1];
     [1:a:0]aresample=48000,aformat=...[a1];
     [v0][a0][v1][a1]concat=n=2:v=1:a=1[vout][aout]"
    -map "[vout]" -map "[aout]" -c:v h264_mediacodec ... -r 30 -c:a aac -b:a 128k
    -ar 48000 -ac 2 -avoid_negative_ts make_zero OUT.mkv

Cenário que ainda falha:
  Juntar dois MKVs com 3 faixas de áudio cada (pt-BR, en, comentário). O diálogo
  avisa e pede para escolher uma faixa por clipe; a saída terá exatamente 1
  faixa de áudio e nenhuma legenda, mesmo no modo sem reencode quando as
  assinaturas divergirem. Não há caminho que preserve as 3 faixas.

Impacto residual:
  Descarte por decisão explicitamente comunicada, não por acidente. Legendas são
  preservadas no concat direto (`-map 0`) e removidas com confirmação no
  reencode. Ver também NOVO-3: quando há múltiplas faixas, a saída vira WAV, o
  que contraria o texto do diálogo ("saída recodificada").

Fix restante, se houver:
  Opcional — quando a contagem de faixas coincidir entre todos os clipes,
  gerar um `filter_complex` por faixa (a0/a1 para a faixa 0, b0/b1 para a faixa
  1, ...) e concatenar cada par, emitindo `-map` por faixa de saída.
  Corrigir o texto do diálogo enquanto isso não for feito (NOVO-3).

Gravidade: BAIXA (comportamento divulgado)

--------------------------------------------------------------------------------

12) Status: PARCIALMENTE CORRIGIDO
Finding: não existe caminho `-c:a copy` em nenhuma rota de reencode. O encoder
  deixou de ser sempre `aac` (audioEncoderForOutput escolhe pcm_s16le/flac/
  libmp3lame/libopus/libvorbis/ac3 conforme codec e extensão, e
  audioEncodingArguments preserva opus/vorbis/flac/mp3/ac3), mas áudios
  idênticos entre clipes continuam sendo reencodados sempre que o reencode roda,
  com perda em AAC/MP3/Opus/Vorbis e tempo desperdiçado. A escolha do codec vem
  do perfil agregado, sem validação cruzada entre clipes e sem aviso de conversão
  antes de executar.

Evidência no código:
  - MELHORADO — a escolha do codec deixou de ser sempre `aac`:
    `audioEncoderForOutput` (linhas 738-756) decide pela extensão da saída
    (`wav`->pcm_s16le, `flac`->flac, `mp3`->libmp3lame, `opus`->libopus,
    `ogg`->libopus/libvorbis conforme codec, `m4a`/`mp4`/`aac`->aac) e, sem
    extensão conhecida, pelo codec (`flac`/`mp3`/`opus`/`vorbis`/`ac3`).
  - MELHORADO — `audioEncodingArguments` (linhas 810-821) preserva
    opus/vorbis/flac/mp3/ac3 e só força `-b:a` fora do FLAC.
  - MELHORADO — o perfil vem do agregado, não de 16 kHz mono:
    `detectAggregateOutputProfile` (linhas 2023-2053) tira o MÁXIMO de
    `audioSampleRate`, `audioChannels` e `audioBitrate` entre os clipes;
    `applySelectedAudioProfile` (linhas 2055-2080) ainda troca taxa/canais/
    bitrate/codec pela faixa escolhida em cada clipe.
  - NÃO FEITO — não há `-c:a copy`:
    `FfmpegMediaPolicies.joinAudioCommandArguments` (FfmpegMediaPolicies.kt:
    134-149) sempre emite `-c:a <encoder>`; `buildReencodeArguments`
    (linhas 782-794) e `buildFadeInOutReencodeArguments` (linhas 796-808)
    sempre chamam `audioEncodingArguments(profile)`.
  - Aviso de conversão existe como rótulo de etapa e encoder exibido durante a
    execução (`executeAudioJoin` linhas 672-677, `executeFfmpegWithProgress`
    linhas 940-995), mas NÃO como diálogo anterior à execução.

Argumentos FFmpeg efetivos (2 MP3 idênticos, sem reencode, compatíveis):
  ffmpeg -y -fflags +genpts -f concat -safe 0 -i join_list_<ts>.txt -map 0
         -map_metadata 0 -map_chapters 0 -c copy -avoid_negative_ts make_zero OUT.mp3
  (o `join_list_*.txt` é apagado após a execução, linhas 983-984)

Argumentos FFmpeg efetivos (com reencode, saída .mp3, perfil 44,1 kHz estéreo):
  ffmpeg -y -i A -i B -filter_complex "...concat=n=2:v=0:a=1[aout]"
         -map "[aout]" -vn -c:a libmp3lame -ar 44100 -ac 2 -b:a 128k
         -avoid_negative_ts make_zero OUT.mp3

Cenário que ainda falha:
  Dois MP3 idênticos (mesmo codec, taxa, canais, extradata). Como
  `audioNeedsNormalization` inclui `firstAudioExtension == "mp3"` (linha 539), a
  normalização é forçada e o áudio é reencodado para `pcm_s16le`/WAV — mesmo
  quando as assinaturas seriam idênticas e o concat direto funcionaria.
  Com reencode marcado, qualquer junta reencoda: não há atalho de cópia.

Impacto residual:
  Áudios idênticos só escapam do reencode pela rota de concat direto, que agora
  é rigorosamente validada. Quando o reencode roda, ele roda porque o usuário
  pediu, porque os clipes divergem, porque é MP3 ou porque há múltiplas faixas
  — sempre com justificativa visível na etapa.

Fix restante, se houver:
  Opcional — quando a única razão da normalização for `firstAudioExtension ==
  "mp3"` (linha 539) e `directConcatCompatibilityError` retornar null, permitir
  o concat direto em vez de converter para WAV.

Gravidade: BAIXA

--------------------------------------------------------------------------------

14) Status: PARCIALMENTE CORRIGIDO
Finding: áudios incompatíveis viram WAV de forma silenciosa. A padronização
  deixou de ser 16 kHz mono (usa taxa e canais do perfil agregado), mas nenhum
  diálogo, aviso ou linha de status informa que a saída virou WAV; o usuário só
  percebe pela extensão no nome do arquivo.

Evidência no código:
  - Decisão: `audioNeedsNormalization` (linhas 538-539) e
    `audioWillStandardizeToWav` (linhas 540-541):
        val audioNeedsNormalization = audioOnly &&
            (directConcatIncompatibility != null || firstAudioExtension == "mp3"
             || hasSelectedMultitrackAudio)
        val audioWillStandardizeToWav = audioNeedsNormalization && !reencodeChecked
  - Nome do arquivo forçado ANTES de executar: `buildJoinedOutputName(
    forceAudioStandardization = audioWillStandardizeToWav)` (linha 542), que em
    `FfmpegJoinVideosActivity.kt:1716` devolve extensão `"wav"`.
  - Linha de status explícita: `executeAudioJoin` (linhas 652-660):
        val label = when {
            requestedReencode -> "Aplicando transição de áudio"
            forceNormalization -> "Convertendo áudios incompatíveis para WAV no
                                  perfil agregado"
            else -> "Juntando áudios sem reencodar"
        }
        if (forceNormalization && !requestedReencode)
            renameProcessingStep("Juntando áudios sem reencodar", label)
    `renameProcessingStep` (linhas 1574-1586) renderiza antes da chamada ao
    FFmpeg. O encoder exibido é `pcm_s16le` (linha 674).
  - NÃO FEITO — não há AlertDialog de confirmação antes da conversão. O fix
    original aceitava "pedir confirmação antes da conversão (ou ao menos
    registrar uma linha de status explícita)"; só a alternativa fraca foi
    implementada.

Argumentos FFmpeg efetivos (2 áudos incompatíveis, padronização para WAV):
  ffmpeg -y -i A -i B -filter_complex
    "[0:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a0];
     [1:a:0]aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a1];
     [a0][a1]concat=n=2:v=0:a=1[aout]"
    -map "[aout]" -vn -c:a pcm_s16le -ar 48000 -ac 2
    -avoid_negative_ts make_zero <nome>.wav

Cenário que ainda falha:
  Usuário seleciona dois FLACs de 96 kHz e um de 44,1 kHz, com "Recodificar"
  desmarcado. O app converte tudo para WAV 96 kHz PCM 16-bit sem nenhuma
  pergunta. A etapa avisa, mas o usuário só pode aceitar ou cancelar a operação
  inteira — não há como desistir da conversão mantendo a junção.
  Adicionalmente (NOVO-3): se um dos clipes tiver mais de uma faixa de áudio, a
  saída também vira WAV, mas o diálogo de seleção diz "as demais não entrarão na
  saída recodificada", sugerindo manutenção do formato original.

Impacto residual:
  O usuário é informado, porém não pode recusar a conversão sem cancelar
  tudo. A contradição entre o diálogo de faixa múltipla e o resultado em WAV
  (NOVO-3) é o ponto mais confuso.

Fix restante, se houver:
  AlertDialog de confirmação antes da conversão, no mesmo padrão do modo forte
  do Limpar Áudio (`FfmpegCleanAudioActivity.kt:182-194`), e alinhamento do
  texto de `requestAudioTrack` (linha 1863) quando a saída for WAV.

Gravidade: BAIXA

================================================================================
FERRAMENTA DE EXTRAIR ÁUDIO — FfmpegExtractAudioActivity.kt
================================================================================

18) Status: PARCIALMENTE CORRIGIDO
Finding: o caminho de cópia existe, mas é restrito. `canCopyAudioWithoutConversion`
  retorna false sempre que há qualquer corte e quando taxa ou canais divergem dos
  ajustes, então na maioria dos usos reais o reencode continua ocorrendo: WAV
  caía em `pcm_s16le` (derrubava 24/32-bit) e MP3 era emitido com `-b:a`,
  `-minrate` e `-maxrate` iguais (CBR forçado), sem escolha CBR/VBR na UI. A
  detecção de WAV dependia do mime "audio/raw", então extratores que reportassem
  `audio/x-wav` ou `audio/wav` nunca entravam na cópia.

Evidência no código:
  - (b) RESOLVIDO — profundidade PCM por faixa.
    `detectPcmEncoder(inputFile, audioTrack)` (linhas 972-991) lê
    `KEY_PCM_ENCODING` da faixa escolhida e devolve `pcm_u8` / `pcm_f32le` /
    `pcm_s24le` / `pcm_s32le` / `pcm_s16le`. `buildFfmpegArguments` (linha 865)
    repassa a `FfmpegMediaPolicies.extractAudioEncoderArguments`
    (FfmpegMediaPolicies.kt:90-100), cujo branch `wav` (linha 92) emite
    `listOf("-c:a", pcmEncoder, "-f", "wav")`.
  - (mime) RESOLVIDO — `canCopyAudioWithoutConversion` linha 961:
        "audio/raw", "audio/x-raw", "audio/wav", "audio/x-wav" ->
            settings.extension == AudioExtension.WAV
  - (CBR) PARCIAL — `extractAudioEncoderArguments` linha 93:
        "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", bitrate)
    `-minrate` e `-maxrate` saíram — há teste que congela isso
    (`assertFalse("-minrate" in mp3)`, FfmpegMediaPoliciesTest:92). Mas não foi
    exposta escolha CBR/VBR: `activity_ffmpeg_extract_audio.xml` oferece apenas
    `button_output_extension`, `button_bitrate`, `button_sample_rate` e
    `button_channels`.
  - (cópia com corte) NÃO FEITO — linha 945:
        if (startMs > 0L ||
            (endMs != null && duration > 0L && endMs < duration - 250L)) return false
    A cópia só ocorre na extração do arquivo inteiro, com tolerância de 250 ms
    no fim. Ainda exige `sourceRate == settings.sampleRate &&
    sourceChannels == settings.channels` (linha 964).

Argumentos FFmpeg efetivos (extração integral, WAV 24-bit, taxa/canais iguais):
  ffmpeg -y -i IN -vn -map 0:a:0 -map_metadata 0 -c:a copy
         -avoid_negative_ts make_zero OUT.wav

Argumentos FFmpeg efetivos (com corte, MP3, faixa 1):
  ffmpeg -y -ss 2.000 -i IN -t 3.000 -vn -map 0:a:1 -map_metadata 0
         -ar 48000 -ac 2 -c:a libmp3lame -b:a 160k
         -avoid_negative_ts make_zero OUT.mp3

Argumentos FFmpeg efetivos (OPUS, com corte):
  ffmpeg -y -ss 1.000 -i IN -t 4.000 -vn -map 0:a:0 -map_metadata 0
         -ar 48000 -ac 2 -c:a libopus -application audio -b:a 96k -vbr on
         -avoid_negative_ts make_zero OUT.opus

Cenário que ainda falha:
  Extrair 30 s do meio de um WAV 24-bit/96 kHz. Como há corte,
  `canCopyAudioWithoutConversion` retorna false e o arquivo é reencodado — para
  `pcm_s24le`, sem perda de profundidade, mas com processamento desnecessário.
  Para MP3, o usuário não consegue pedir VBR (`-q:a`): a saída é sempre CBR em
  `-b:a`.

Impacto residual:
  Nenhum comando incorreto. Perde-se a oportunidade de cópia com corte em
  containers de taxa constante e a escolha de modo de taxa.

Fix restante, se houver:
  (i) liberar a cópia com corte quando a origem for PCM ou FLAC, usando corte
      alinhado ao limite de amostra;
  (ii) expor CBR/VBR (ou `-q:a`) na UI de extração.

Gravidade: BAIXA

--------------------------------------------------------------------------------

19) Status: PARCIALMENTE CORRIGIDO
Finding: o mapa da faixa de áudio continua sem o "?". A faixa deixou de ser
  sempre a 0 (há contagem prévia que rejeita arquivo sem áudio e seletor quando
  há mais de uma), então o índice é sempre válido na prática. Falta o operador
  de tolerância como defesa em profundidade.

Evidência no código:
  - RESOLVIDO (substância) — `buildFfmpegArguments` linha 873:
        audioMap = FfmpegMediaPolicies.audioStreamSpecifier(0, audioTrack)
    com `audioTrack` vindo do seletor: `extractSelectedAudio` linhas 743-748
    disparam `requestAudioTrack` quando `audioTrackCount(it.uri) > 1`;
    linha 751 guarda a escolha; linha 785 lê
    `jobAudioTracks[video.uri.toString()] ?: 0`.
  - Bloqueio prévio: linhas 739-742 rejeitam qualquer arquivo com zero faixas de
    áudio ("${media.name} não possui faixa de áudio.").
  - NÃO FEITO (letra do fix) — `FfmpegMediaPolicies.audioStreamSpecifier`
    (FfmpegMediaPolicies.kt:62-63):
        fun audioStreamSpecifier(inputIndex: Int, audioTrackIndex: Int): String =
            "$inputIndex:a:${audioTrackIndex.coerceAtLeast(0)}"
    Sem o `?`. O teste congela o formato:
    `FfmpegMediaPoliciesTest.selectedAudioTrackProducesExplicitFfmpegSpecifier`
    (linhas 60-64) — `assertEquals("0:a:0", ...)`, `assertEquals("2:a:3", ...)`.

Argumentos FFmpeg efetivos:
  ffmpeg -y [-ss X] -i IN [-t Y] -vn -map 0:a:<faixa escolhida> -map_metadata 0
         [-c:a copy | -ar SR -ac CH <encoder args>]
         -avoid_negative_ts make_zero OUT

Cenário que ainda falharia:
  Nenhum no código atual. O índice é validado antes, e `coerceAtLeast(0)`
  impede índice negativo. O `?` só protegeria contra divergência futura entre a
  ordem de faixas do MediaExtractor e a ordem de streams do FFmpeg (por exemplo,
  um container em que o extrator não exponha uma faixa de dados que o FFmpeg
  conte — o índice `a:N` apontaria para outra faixa).

Impacto residual:
  Nenhum funcional. É endurecimento puro.

Fix restante, se houver:
  Opcional, uma linha — trocar `audioStreamSpecifier` por
  `"$inputIndex:a:${idx}?"` e atualizar
  `FfmpegMediaPoliciesTest.selectedAudioTrackProducesExplicitFfmpegSpecifier`.
  Atenção: a mudança vale para Extrair, Inserir (item 24) e Limpar (item 26) ao
  mesmo tempo, e os testes `:62-63`, `:145` (clean) congelam o formato atual.

Gravidade: BAIXA (endurecimento)

================================================================================
FERRAMENTA DE INSERIR ÁUDIO — FfmpegInsertAudioActivity.kt
================================================================================

24) Status: PARCIALMENTE CORRIGIDO
Finding: a seleção de faixa não era consistente entre rotas. A rota ativa usa o
  índice escolhido (`[0:a:${track}]` / `[1:a:${track}]`), mas sem o operador "?"
  de tolerância; as rotas legadas fixavam `0:a:0` em todos os pontos, ignorando
  completamente a faixa selecionada pelo usuário.

Evidência no código:
  - RESOLVIDO (inconsistência) — as rotas legadas não existem mais.
    `rg -n "executeCopyInsert|executeSmartInsert|concatPieces"` em `app/src/`
    retorna zero. Resta uma única rota, `buildFullReencodeArguments`
    (linhas 567-625), chamada uma única vez em `startInsert()` (linha 520), que
    honra a seleção nos três pontos do filtro:
        linha 586: [${audioStreamSpecifier(0, jobConfig.mainAudioTrack)}]      (trecho esquerdo)
        linha 593: [${audioStreamSpecifier(1, jobConfig.insertedAudioTrack)}]  (áudio inserido)
        linha 597: [${audioStreamSpecifier(0, jobConfig.mainAudioTrack)}]      (trecho direito)
  - Seleção garantida em `startInsert()` linhas 478-487: exige pelo menos uma
    faixa de áudio em cada arquivo ("Os dois arquivos precisam possuir pelo
    menos uma faixa de áudio.") e dispara `requestAudioTrack` (linhas 641-653)
    quando `audioTrackCount(it.uri) > 1`.
  - NÃO FEITO (letra do fix) — falta o `?`, pela mesma função compartilhada do
    item 19 (`FfmpegMediaPolicies.audioStreamSpecifier`, :62-63).

Argumentos FFmpeg efetivos (inserção no meio, sem transição, faixa 0 em ambos):
  ffmpeg -y -i PRINCIPAL -i INSERIDO -filter_complex
    "[0:a:0]atrim=start=0:end=10.000,aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a0];
     [1:a:0]atrim=start=0:end=4.000,aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a1];
     [0:a:0]atrim=start=10.000:end=60.000,aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,asetpts=PTS-STARTPTS[a2];
     [a0][a1][a2]concat=n=3:v=0:a=1[aout]"
    -map "[aout]" -vn -c:a aac -b:a 192k -ar 48000 -ac 2 -movflags +faststart
    -avoid_negative_ts make_zero OUT.m4a

Cenário que ainda falharia:
  Nenhum hoje. Com as rotas legadas removidas, a inconsistência desapareceu. O
  `?` protegeria apenas contra divergência de ordem de faixas entre
  MediaExtractor e FFmpeg.

Impacto residual:
  Nenhum funcional — idem ao item 19.

Fix restante, se houver:
  Opcional — mesmo ajuste de uma linha do item 19 (função compartilhada).

Gravidade: BAIXA (endurecimento)

================================================================================
FERRAMENTA DE LIMPAR ÁUDIO — FfmpegCleanAudioActivity.kt
================================================================================

26) Status: PARCIALMENTE CORRIGIDO
Finding: o mapa da faixa de áudio continua sem o "?". A mitigação funciona
  (`inspectAudioSource` exige exatamente uma faixa de áudio antes de executar,
  então o índice 0 é sempre válido), mas o comando literal segue `-map 0:a:0`,
  sem defesa em profundidade no próprio FFmpeg.
  Nota: a reclamação de que a saída era sempre WAV PCM 16-bit/16 kHz/mono está
  corrigida — o comando preserva taxa, canais e profundidade da origem.

Evidência no código:
  - `buildFfmpegArguments` linha 270:
        audioMap = FfmpegMediaPolicies.audioStreamSpecifier(0, 0)   // -> "0:a:0"
    Sem `?`. O teste congela o formato:
    `FfmpegMediaPoliciesTest.cleanCommandPreservesRequestedPcmProfile`
    (linhas 141-153) — espera `"-map", "0:a:0"`.
  - Mitigação intacta: `cleanSelectedAudio` linhas 177-181:
        val audioTracks = inspectAudioSource(uri)?.trackCount ?: 0
        if (audioTracks != 1) {
            status.text = "O arquivo precisa ter exatamente uma faixa de áudio."
            return
        }
    e `audioSourceProfile` (linhas 302-319) só devolve perfil via
    `audioFormats.singleOrNull()`.
  - Preservação de formato confirmada: linhas 311-317 escolhem `pcm_u8` /
    `pcm_f32le` / `pcm_s24le` / `pcm_s32le` / `pcm_s16le` por
    `KEY_PCM_ENCODING`; `cleanAudioCommandArguments`
    (FfmpegMediaPolicies.kt:172-185) emite `-ar <sampleRate>` e `-ac <channels>`
    da origem e `-f wav`.

Argumentos FFmpeg efetivos (origem 96 kHz, 6 canais, 32-bit, modo equilibrado):
  ffmpeg -y -i IN -vn -map 0:a:0 -af afftdn=nf=-25 -c:a pcm_s32le -ar 96000
         -ac 6 -avoid_negative_ts make_zero -f wav OUT.wav

Argumentos FFmpeg efetivos (origem 48 kHz estéreo 16-bit, modo forte):
  ffmpeg -y -i IN -vn -map 0:a:0 -af anlmdn=s=0.00003:p=0.002:r=0.002
         -c:a pcm_s16le -ar 48000 -ac 2 -avoid_negative_ts make_zero -f wav OUT.wav

Cenário que ainda falharia:
  Nenhum. A validação `audioTracks != 1` garante que o índice 0 existe. O `?` é
  redundante com a checagem atual.

Impacto residual:
  Nenhum funcional. Endurecimento puro, igual aos itens 19 e 24.

Fix restante, se houver:
  Opcional — `"0:a:0?"`, mesma função compartilhada; requer atualizar
  `FfmpegMediaPoliciesTest` linha 145.

Gravidade: BAIXA (endurecimento)

================================================================================
PENDÊNCIAS TRANSVERSAIS
================================================================================

27) Status: PARCIALMENTE CORRIGIDO
Finding: não existe política única de legendas. Girar e Cortar preservam
  áudios, legendas, dados e anexos (`-map 0:s? 0:d? 0:t?` com cópia). O Juntar
  não preservava — e havia piorado ao rejeitar qualquer vídeo com legenda em
  toda junção (item 15, corrigido). Extrair, Inserir e Limpar são somente áudio
  e não se aplicam.

Evidência no código:
  - Girar preserva tudo, inclusive anexos:
      `FfmpegRotateVideoActivity.kt:1129-1133` (trechos paralelos):
        "-map", "0:v:0", "-map", "0:a?", "-map", "0:s?", "-map", "0:d?", "-map", "0:t?"
      `FfmpegRotateVideoActivity.kt:1332` (sequencial): mesma lista.
  - Cortar preserva, MAS SEM ANEXOS:
      `FfmpegCutActivity.kt:697` (modo preciso):
        "-map", "0:v:0?", "-map", "0:a?", "-map", "0:s?", "-map", "0:d?"
      `FfmpegCutActivity.kt:890` (bordas do híbrido): mesma lista.
      `FfmpegMediaPolicies.kt:195` (corpo do híbrido): mesma lista.
      Não há `-map 0:t?` em nenhuma das três rotas.
  - Juntar:
      preserva no concat direto — `FfmpegMediaPolicies.kt:127-132`
      (`directConcatCommandArguments` emite `-map 0 -map_metadata 0
      -map_chapters 0 -c copy`), com saída sempre `.mkv` (`buildJoinedOutputName`
      linha 1724).
      descarta com confirmação no reencode — linhas 503-511, diálogo
      "As legendas não podem participar das transições" / "Remover e continuar",
      condicionado a `!audioOnly && reencodeChecked`.
      A rejeição indiscriminada saiu de `validateSupportedStreamTopology`
      (linhas 1811-1834), que hoje só exige `videoCount == 1` para vídeo e
      `audioCount >= 1` para áudio.
  - NÃO FEITO — não há política una em `FfmpegMediaPolicies`: cada Activity
    monta a sua própria lista de `-map`, com resultados divergentes.

Argumentos FFmpeg efetivos (Cortar, modo preciso):
  ffmpeg -y -noautorotate [-display_rotation:v:0 G] -ss INI -i IN -t DUR
         -map 0:v:0? -map 0:a? -map 0:s? -map 0:d? -map_metadata 0
         -map_chapters 0 -c copy -c:v <enc> -b:v X -avoid_negative_ts make_zero OUT.mkv

Argumentos FFmpeg efetivos (Juntar, concat direto com legendas):
  ffmpeg -y -fflags +genpts -f concat -safe 0 -i join_list_<ts>.txt
         -map 0 -map_metadata 0 -map_chapters 0 -c copy
         -avoid_negative_ts make_zero OUT.mkv

NOVO-1 (achado desta auditoria) — Cortar descarta anexos:
  Um MKV com legenda ASS e fonte embutida passa pelo Cortar: a legenda é
  preservada (`0:s?`), mas a fonte (`0:t`, attachment) é descartada. A legenda
  passa a ser renderizada com fonte substituta. O Girar não tem esse problema
  porque mapeia `0:t?`.

Impacto residual:
  Perda silenciosa de fontes e anexos no Cortar; duplicação da lógica de
  mapeamento entre ferramentas, sem um ponto único de decisão.

Fix restante, se houver:
  (i) acrescentar "-map", "0:t?" nas três rotas do Cortar (`:697`, `:890`,
      `FfmpegMediaPolicies.kt:195`) — alinhamento com o Girar;
  (ii) opcional — centralizar a lista de mapas em `FfmpegMediaPolicies`
      (por exemplo `preserveStreamMaps(includeAttachments: Boolean)`) e usar nas
      duas ferramentas de vídeo.

Gravidade: BAIXA (NOVO-1) a MÉDIA (governança/duplicação)

--------------------------------------------------------------------------------

28) Status: PARCIALMENTE CORRIGIDO
Finding: nenhum teste cobria os construtores de comando por ferramenta.
  `FfmpegMediaPoliciesTest` existia, rodava e passava (8 casos), mas cobria
  apenas a política compartilhada — rotação por metadados, canais multicanal,
  perfil de vídeo e formatação. Nenhum teste cobria `preciseAudioEncoderArguments`
  e `buildHybridBodyArguments` no Cortar, `buildFfmpegArguments` no Extrair,
  `buildAudioReencodeArguments` no Juntar, `buildFfmpegArguments` no Limpar ou a
  rota de reencode do Inserir — exatamente onde estavam os itens abertos.

Evidência no código:
  - AVANÇO — os construtores foram extraídos das Activities para o objeto puro
    `FfmpegMediaPolicies` e `FfmpegMediaPoliciesTest.kt` passou de 8 para
    **16 testes**, todos executando sem binário FFmpeg. Cobertura atual:
      Girar     metadataModeAlwaysUsesCopyCommand... (:39-58) —
                `usesMetadataCopyCommand`, `metadataRotationCopyArguments`,
                `metadataCopyPreflightArguments`
      Cortar    cutAudioArgumentsPreservePcmDepth... (:66-86) —
                `cutAudioEncoderArguments`, `cutAudioCommandArguments`
      Cortar    hybridBodyKeepsMicrosecondPrecisionAndSeeksBeforeInput (:177-184)
                — precisão de µs e posição do `-ss`
      Extrair   extractionUsesVbrCapableMp3AndAudioOpusProfile (:88-108) —
                `extractAudioEncoderArguments`, `extractAudioCommandArguments`
      Juntar    joinCommandsCoverDirectCopyAndFilteredAudio (:110-127) —
                `directConcatCommandArguments`, `joinAudioCommandArguments`
      Juntar    directConcatRequiresExactContainerAndFfmpegStreamContract
                (:155-175)
      Inserir   insertCommandMapsFilteredOutputAndKeepsContainerOptions
                (:129-139)
      Limpar    cleanCommandPreservesRequestedPcmProfile (:141-153)
      + compartilhados: `audioStreamSpecifier`, `normalizedAudioFilter`,
        `metadataRotationAfterClockwiseRequest`, `normalizeRightAngle`,
        `physicalRotationFilters`, `parseAudioChannelCount`,
        `parseKnownVideoProfile`, `formatCommand`.
  - NÃO COBERTO — a construção dos grafos de filtro continua privada nas
    Activities:
      `FfmpegJoinVideosActivity.kt:876-910`  buildFilterComplex
                                             (concat `v=1:a=1`, xfade, acrossfade)
      `FfmpegJoinVideosActivity.kt:823-874`  buildFadeInOutFilterComplex
                                             (fade, afade, `v=1:a=1`)
      `FfmpegJoinVideosActivity.kt:685-736`  buildAudioReencodeArguments
                                             (concat `v=0:a=1`, acrossfade)
      `FfmpegInsertAudioActivity.kt:567-625` buildFullReencodeArguments
                                             (atrim, afade, acrossfade)
  - Também não coberto: a lógica de decisão das Activities (fallback do corte
    híbrido, recusa do concat direto, pré-flight do Girar) e todos os helpers
    que dependem de `MediaExtractor`/`MediaFormat`
    (`detectPcmEncoder`, `detectStreamBitrates`, `streamCopySignatures`,
    `detectAggregateOutputProfile`, `videoTrackCount`).

Argumentos FFmpeg efetivos: não aplicável (item de teste).

Cenário que ainda falha:
  Uma regressão no grafo de filtro — por exemplo, trocar a ordem de
  `asetpts=PTS-STARTPTS` e `afade=`, ou alterar a aridade do `concat` — não é
  detectada por nenhum teste. O mesmo vale para a lógica que decide entre
  caminho rápido e fallback no Cortar.

Impacto residual:
  Regressões em `filter_complex` e nas decisões de rota passam despercebidas. O
  commit 52f6253 mexeu exatamente nessas rotas, então é onde o risco de
  regressão silenciosa é maior.

Fix restante, se houver:
  Extrair os construtores de `filter_complex` para funções puras no mesmo
  padrão (entrada: lista de clipes + perfil; saída: String) e cobri-los com
  testes de unidade; opcionalmente, extrair as decisões
  (`audioNeedsNormalization`, `canUseParallel`, `executeHybridVideoCut` fallback)
  para um objeto testável.

Gravidade: BAIXA

================================================================================
CONSOLIDAÇÃO — O QUE AINDA PODE SER TRIADO
================================================================================
Prioridade 1 (efeito funcional visível ao usuário):
  - 27 / NOVO-1 — Cortar perde fontes e anexos (`0:t`). Fix de uma linha em
    três pontos.

Prioridade 2 (contradição de comunicação):
  - 14 + NOVO-3 — Juntar com áudio multi-faixa entrega WAV enquanto o diálogo
    promete "saída recodificada".

Prioridade 3 (qualidade e exposição de opções — decisão de produto):
  - 5  — cópia com corte em PCM/FLAC + seletor de qualidade de áudio no Cortar
  - 18 — cópia com corte em WAV/FLAC + CBR/VBR no Extrair
  - 12 — permitir concat direto quando a única razão da normalização for MP3
  - 11 — concatenar N faixas quando a contagem coincidir entre os clipes
  - 20 — perfil de voz OPUS (fora deste recorte, é melhoria opcional)

Prioridade 4 (endurecimento, sem impacto hoje):
  - 19 / 24 / 26 — operador "?" em `audioStreamSpecifier` (1 linha + 2 testes)
  - 28 — testes dos grafos de `filter_complex`

Sobre execução: análise estática (leitura integral, `rg`,
`git diff 85b889f..52f6253`) mais a suíte unitária registrada no topo.
Não houve teste com arquivos de mídia reais, logo nenhuma afirmação sobre
duração, sincronia ou rotação efetiva em runtime foi validada empiricamente.
================================================================================
