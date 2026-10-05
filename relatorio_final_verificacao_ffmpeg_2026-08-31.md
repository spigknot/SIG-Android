# Verificação final dos findings — Ferramentas FFmpeg (SIG Android)

- **Data:** 2026-08-31
- **Commit verificado:** `85b889f` ("fix: alinhar ferramentas FFmpeg às opções da interface") — estado atual do working tree (nenhum `Ffmpeg*.kt` modificado após o commit; `git status` confirmado)
- **Método:** leitura caso a caso do código atual + diffs dos commits `85b889f` e `543a61b` + `FfmpegMediaPoliciesTest` (8 testes presentes e consistentes com os mecanismos descritos)
- **Escopo:** 61 findings únicos da auditoria de 2026-08-30 (`findings_por_ferramenta_ffmpeg_android_2026-08-30.txt`)

## RESUMO

| Status | Qtde | Observação |
|---|---|---|
| CORRIGIDO | **47** | Mecanismo verificado no código atual |
| PENDENTE — parcial | 7 | Melhorou, mas o cerne do finding persiste |
| PENDENTE — mitigado | 5 | Rota perigosa inalcançável; código legado permanece morto no arquivo |
| PENDENTE — não corrigido | 1 | Cortar #7 (áudio sempre reencoda) |
| PENDENTE — por desenho | 1 | Girar (só 1ª faixa de vídeo é mapeada — decisão consciente, documentada) |

> Nota: o relatório `verificacao_correcoes_ffmpeg_2026-08-31.txt` apontava "1 pendência" porque só contava itens integralmente não corrigidos. O `relatorio_pendencias_ffmpeg_android_2026-08-31.txt` lista 14 — **todos os 14 foram confirmados reais nesta verificação** (cada um com linha verificada abaixo).

---

## Ferramenta de girar vídeo

Arquivo: [FfmpegRotateVideoActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegRotateVideoActivity.kt)

1. Finding: modo metadados girava no sentido OPOSTO ao da prévia (graus da UI em horário somados à rotação anti-horária da display matrix; tag legada `rotate: N` lida com sinal errado).
   Fix: `metadataDegrees = normalize(current − requested)`; negar o valor do ramo `rotate: N` no parser.
   Linhas: `FfmpegMediaPolicies.kt` 45–47 (`metadataRotationAfterClockwiseRequest`); parser negando em ~1445 (`?.let { normalizeMetadataDegrees(-it) }`).
   Gravidade: ALTA.
   **Status: CORRIGIDO** — subtração implementada e testada (`metadataRotationConvertsClockwiseUiToCounterClockwiseFfmpeg`).

2. Finding: "Girar pelos metadados" vinha LIGADO por padrão e não persistia entre aberturas.
   Fix: `android:checked="false"` no layout + persistir em SharedPreferences.
   Linhas: layout `activity_ffmpeg_rotate_video.xml` ~421 (`checked=false`); `FfmpegRotateVideoActivity.kt` 287–296 (`ROTATE_PREFS` / `PREF_METADATA_ROTATION` com leitura e gravação no listener).
   Gravidade: ALTA.
   **Status: CORRIGIDO** — desmarcado por padrão e persistido.

3. Finding: rotação por metadados que resultasse em 0 grau era no-op silencioso (`-display_rotation` omitido; `-map 0 -c copy` copiava a matrix da entrada intacta).
   Fix: emitir sempre `-display_rotation:v:0 <valor>`, inclusive 0.
   Linhas: `FfmpegMediaPolicies.kt` 8–21 (`metadataRotationCopyArguments` sempre inclui o valor; teste confirma `0` emitido no caso (90,90)).
   Gravidade: ALTA.
   **Status: CORRIGIDO.**

4. Finding: reencode apagava TODA a metadata global (`-map_metadata -1`), inclusive GPS/creation_time.
   Fix: trocar por `-map_metadata 0`.
   Linhas: 935 (concat paralelo), 1065 (trechos paralelos), 1256 (sequencial).
   Gravidade: ALTA.
   **Status: CORRIGIDO** — os três pontos usam `0`.

5. Finding: paralelo reencodava áudio para AAC 48 kHz estéreo; sequencial copiava.
   Fix: remover `-ar 48000 -ac 2` e copiar o áudio.
   Linhas: 1061 (`-c:a copy` nos trechos) + 1062–1064 (`-c:s/-c:d/-c:t copy`); sem `-ar`/`-ac` em lugar nenhum do caminho paralelo.
   Gravidade: ALTA.
   **Status: CORRIGIDO.**

6. Finding: modo somente-metadados gravava `-c copy` em `.mp4` FIXO sem validar codec/streams.
   Fix: validar codecs/legendas/anexos contra o container, ou manter o container real da origem.
   Linhas: `buildOutputName` ~1821–1826 (`safeContainerExtension` mantém extensão segura da origem; cai para `mkv` só quando não é segura).
   Gravidade: ALTA.
   **Status: NÃO CORRIGIDO (parcial)** — a extensão agora herda do original (mitiga o caso mais comum), mas não há validação de codecs/streams incompatíveis com o container escolhido (ex.: VP9/WMV em MP4, legendas ASS em MP4).

7. Finding: paralelismo desligado em silêncio em 2 dos 4 cenários (sem filtros; vídeo curto).
   Fix: Toast/aviso em todos os casos.
   Linhas: 497–503 (Toast "não há transformação física para dividir") e ~795 (`setLiveStatus("Paralelo ajustado: N trecho(s)...")`).
   Gravidade: MÉDIO.
   **Status: CORRIGIDO** (o caso `hasTrim` já tinha Toast; os dois restantes agora avisam).

8. Finding: segmentação paralela forçava MP4 intermediário e não degradava para serial.
   Fix: formato intermediário tolerante + fallback sequencial.
   Linhas: 732/746 (`part_%05d.mkv`, `-segment_format matroska`), 812 (`rotated_*.mkv`), 537–558 (fallback sequencial automático com task "Fallback sequencial").
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

9. Finding: `-avoid_negative_ts` não uniforme entre branches com `-ss` + cópia.
   Fix: padronizar `make_zero` em todos os branches.
   Linhas: 747 (split), 1066 (trechos), 936 (concat final), ~1257 (sequencial).
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

10. Finding: `-metadata:s:v:0 rotate=0` era no-op e a ferramenta NÃO passava `-noautorotate` (frames chegavam girados ao filtro).
    Fix: `-noautorotate` antes de `-i` + `-display_rotation:v:0 0` explícito.
    Linhas: 738–739 e 758–759 (split), 1045–1046 (trechos), 1246 (sequencial); a tag inerte `rotate=0` foi removida.
    Gravidade: MÉDIO.
    **Status: CORRIGIDO** — ressalva documentada no item "por desenho" abaixo (mapeamento de vídeo).

11. Finding: modo metadados descartava espelhamentos sem restaurar ao desligar.
    Fix: salvar estado anterior e restaurar.
    Linhas: 1366–1389 (`savedFlipHorizontal/Vertical` + restore).
    Gravidade: MÉDIO.
    **Status: CORRIGIDO.**

12. Finding: modo metadados zerava o intervalo de corte sem aviso/sem restaurar.
    Fix: guardar e restaurar o intervalo.
    Linhas: 1371–1372 (salva) e 1380–1388 (restaura via `timeline.setStart/setEnd`).
    Gravidade: MÉDIO.
    **Status: CORRIGIDO.**

13. Finding: `normalizeMetadataDegrees` colapsava valores não múltiplos de 90 para 0.
    Fix: arredondar ao múltiplo de 90 mais próximo.
    Linhas: `FfmpegMediaPolicies.kt` 26–43 (`normalizeRightAngle` com arredondamento).
    Gravidade: BAIXO.
    **Status: CORRIGIDO** (teste `arbitraryRotationRoundsToNearestRightAngle`).

14. Finding: encoder/qualidade lidos dentro da thread de trabalho, sem barreira de memória.
    Fix: capturar em locais antes da `Thread{}`.
    Linhas: ~486–489 (`val encoder = selectedCodec ?: ...; val quality = selectedVideoQuality` antes da Thread; propagados por parâmetro para paralelo e sequencial).
    Gravidade: BAIXO.
    **Status: CORRIGIDO.**

15. (Pós-fix, relatório de pendências) Finding: o mapeamento preserva áudios/legendas/dados/anexos, mas usa `0:v:0` e preserva somente o primeiro stream de vídeo.
    Linhas: 1251 e 1048–1052.
    Gravidade: ALTA (revisão pós-fix).
    **Status: NÃO CORRIGIDO (por desenho)** — arquivos com 2+ faixas de vídeo são patológicos; a decisão está consciente e documentada no código. Se quiser cobertura total, mapear `0:v?` com `-c:v` por faixa ou bloquear com aviso explícito.

---

## Ferramenta de cortar áudio/vídeo

Arquivo: [FfmpegCutActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegCutActivity.kt)

1. Finding: corpo do corte híbrido começava no keyframe errado (µs truncado em ms + `-ss` antes de `-i` com copy → até um GOP duplicado e trecho final perdido).
   Fix: keyframes em µs / `-ss` depois de `-i` no corpo / margem.
   Linhas: 739–741 (corpo: `-i` antes, `-ss` depois — `buildHybridBodyArguments` ~854+); bordas com `seekAccurately = true` (733 e 749); keyframes reais do MediaExtractor (683, 871–884).
   Gravidade: ALTO.
   **Status: CORRIGIDO** — o corpo copia a partir do keyframe real e as bordas são reencodadas com seek preciso; não há mais seek por keyframe com truncamento.

2. Finding: rótulo "Copiando trecho central sem reencodar" era falso para o áudio (sempre AAC).
   Fix: corrigir o rótulo (e copiar áudio quando possível).
   Linhas: ~697 (`"Copiando vídeo do trecho central sem reencodar"`); o encoder "aac" não é mais atribuído a essa task.
   Gravidade: ALTO.
   **Status: CORRIGIDO** (rótulo coerente; a cópia do áudio em si segue pendente no item 7).

3. Finding: legendas e faixas extras de áudio descartadas em silêncio (`-map 0:v:0? -map 0:a:0?`).
   Fix: mapear `0:s?` / todos os áudios com `-c:s copy` quando possível.
   Linhas: 650–651, 844–845, 865–866 (agora `-map 0:v:0? -map 0:a? -map 0:s? -map 0:d?` + `-map_metadata 0 -map_chapters 0` + `-c copy`).
   Gravidade: ALTO.
   **Status: NÃO CORRIGIDO (parcial)** — áudios, legendas, dados e capítulos agora são preservados, mas o vídeo continua limitado a `0:v:0?` (só a primeira faixa de vídeo).

4. Finding: classificação vídeo/áudio por MIME declarado ou extensão, nunca por conteúdo; extensão desconhecida virava `video/mp4`.
   Fix: sondar com MediaExtractor e decidir pela presença de faixa de vídeo.
   Linhas: 472–487 (`detectMediaMime` via MediaExtractor); fallback `application/octet-stream` (~404).
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

5. Finding: HEVC (ou encoder incompatível) desligava o caminho rápido sem aviso.
   Fix: informar o modo escolhido antes de executar.
   Linhas: 508–527 (diálogo "O encoder escolhido exige recodificação completa" com opção "Usar <encoder compatível>").
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

6. Finding: caminho de precisão usava `-ss` DEPOIS de `-i` (corte no fim de arquivo longo decodificava tudo).
   Fix: `-ss` antes de `-i` com reencode (accurate seek).
   Linhas: 639–642 (`"-ss", formatSeconds(startMs), "-i", inputFile`).
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

7. Finding: corte de áudio sempre reencodava — WAV sempre pcm_s16le, MP3 travado em CBR, sem caminho `-c:a copy`.
   Fix: oferecer stream copy quando codec/container/intervalo forem compatíveis (padrão já existe no Extrair: `canCopyAudioWithoutConversion`).
   Linhas: 657 (chamada incondicional no caminho de áudio), 961–968 (`preciseAudioEncoderArguments` — sempre devolve um encoder; não existe ramificação com `-c:a copy`).
   Gravidade: MÉDIO.
   **Status: NÃO CORRIGIDO** — verificado por leitura: nenhum branch de cópia existe no corte de áudio. É a única pendência integral do conjunto. (O relatório anterior de verificação já a apontava; o Extrair tem o padrão pronto para reaproveitar.)

8. Finding: `isH264Video` usava `contains("Video:")` na primeira linha que aparecesse (frágil com capítulos/títulos).
   Fix: detectar codec por MediaExtractor.
   Linhas: ~891+ (`detectVideoCodecFamily(File/Uri)` via MediaExtractor: `video/avc`→h264, `video/hevc`→hevc) — também habilitou o diálogo do item 5 e o caminho híbrido para HEVC.
   Gravidade: BAIXO.
   **Status: CORRIGIDO.**

---

## Ferramenta de juntar vídeo/áudio

Arquivo: [FfmpegJoinVideosActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegJoinVideosActivity.kt)

1. Finding: clipes com proporções diferentes eram CORTADOS (crop) em vez de letterbox, sem aviso — 68% do quadro de um clipe vertical podia sumir.
   Fix: letterbox por padrão (decrease + pad).
   Linhas: 1571–1574 (`videoFillFrameFilter` agora só tem o ramo letterbox), 1500+ (`videoNormalizeFilter` decrease+pad).
   Gravidade: CRÍTICO.
   **Status: CORRIGIDO.**

2. Finding: SmartJoin duplicava e REMOVIA conteúdo em toda emenda interna (`-ss` antes de `-i` com copy → alinhamento por keyframe errado).
   Fix: alinhar bodyStart ao keyframe / desabilitar até corrigir.
   Linhas: 539 (`val smartJoinChecked = false` incondicional), 408–409 e 1819–1820 (checkbox forçado desligado e desabilitado), help ~524 ("temporariamente indisponível").
   Gravidade: CRÍTICO.
   **Status: NÃO CORRIGIDO (mitigado)** — a rota insegura está inalcançável (checkbox morto + flag fixa), mas `executeSmartJoinExperiment` (1206+) e `buildTransitionArgumentsMkv` (1343+) permanecem como código morto no arquivo, com call sites inalcançáveis (710/770 — só rodam se `smartJoinChecked`, que é sempre `false`). Não reativar sem corrigir o alinhamento por keyframe e testar duração/PTS.

3. Finding: nenhum caminho preservava múltiplas faixas de áudio; grafo de filtro com aridade 1.
   Fix: recusar SmartJoin quando o 1º clipe tem 2+ faixas; no reencode, concat por faixa.
   Linhas: 2660+ (`requestAudioTrack` — seletor por clipe), `audioInputLabel`/`audioStreamSpecifier` (`FfmpegMediaPolicies.kt` 23–24), `validateSupportedStreamTopology` 2599+.
   Gravidade: ALTO.
   **Status: NÃO CORRIGIDO (parcial)** — hoje o usuário ESCOLHE uma faixa por clipe (não é mais descarte silencioso da 2ª), mas o reencode ainda processa exatamente 1 faixa de áudio por clipe; concat com N faixas não existe.

4. Finding: todo reencode forçava `-c:a aac` com taxa/canais do primeiro clipe.
   Fix: `-c:a copy` quando todos compartilham codec/taxa/canais e o container aceita.
   Linhas: 1137+ (`audioEncoderForOutput` — preserva opus/vorbis/flac/mp3/ac3 por extensão e perfil), `audioEncodingArguments` (1543+).
   Gravidade: ALTO.
   **Status: NÃO CORRIGIDO (parcial)** — não converte mais tudo para AAC (o codec do perfil de saída é respeitado), mas a escolha vem do perfil agregado do 1º clipe sem validação cruzada entre clipes nem aviso de conversão.

5. Finding: perfil de saída era sempre o do primeiro clipe (480p primeiro derrubava todos).
   Fix: escolher pelo maior perfil entre os clipes.
   Linhas: 2831–2861 (`detectAggregateOutputProfile` — `maxOf` de resolução/fps/bitrate/canais), usado em 1513, 1473 e equivalentes.
   Gravidade: ALTO.
   **Status: CORRIGIDO.**

6. Finding: caminho "sem reencodar" não validava áudio do 1º clipe nem legendas/anexos; MP4 com ASS/fontes abortava.
   Fix: aplicar validação de áudio no caminho de vídeo; contar legendas/anexos antes do concat.
   Linhas: 722–728 (recusa com mensagem "Ative 'Recodificar'" quando perfis divergem), `validateSupportedStreamTopology` 2599+ (recusa vídeo com legenda no modo com transições), 1199 (`-map 0` preserva tudo no concat direto).
   Gravidade: ALTO.
   **Status: NÃO CORRIGIDO (parcial)** — a validação por perfis principais existe e bloqueia, mas não compara extradata, codec tag nem compatibilidade efetiva do container (`SmartJoinPlanner.kt` 88–119).

7. Finding: fade/afade aplicados ANTES de `setpts/asetpts` — janela calculada sobre o PTS original (transição não disparava com PTS offset).
   Fix: normalizar PTS primeiro, aplicar fade depois.
   Linhas: 1479–1480 e 1574/1590 (`settb=AVTB,setpts=PTS-STARTPTS` antes dos fades; `asetpts=PTS-STARTPTS` antes dos afades).
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

8. Finding: áudios incompatíveis viravam WAV 16 kHz mono em silêncio.
   Fix: diálogo de confirmação ou perfil real.
   Linhas: 565–569 (normalização pelo perfil agregado real), 1045–1060 (sem mais 16 kHz mono forçado).
   Gravidade: MÉDIO.
   **Status: NÃO CORRIGIDO (parcial)** — o comportamento melhorou (usa o perfil real mais alto entre os clipes), mas o rótulo da linha 1047 ainda diz "Normalizando pelo primeiro áudio" (impreciso) e não há confirmação do perfil de destino.

9. Finding: detecção de canais só reconhecia mono/estereo; 5.1 virava 2.0 (downmix silencioso).
   Fix: regex de layouts / MediaExtractor.
   Linhas: `FfmpegMediaPolicies.kt` 58–74 (`parseAudioChannelCount`) e 76–86 (`channelLayout`); delegação em ~3069; testes (`channelParserSupportsMultichannelLayouts`).
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

10. Finding: bitrate de vídeo alvo era, na prática, o do CONTAINER (linha Duration), inflando o arquivo.
    Fix: usar só o bitrate da linha da stream de vídeo.
    Linhas: 2890–2891 (`bestVideoBitrate` parseia somente a `videoLine`).
    Gravidade: MÉDIO.
    **Status: CORRIGIDO.**

11. Finding: `parseVideoProfile` capturava campos diferentes conforme o arquivo (dois H.264 idênticos podiam ser considerados incompatíveis).
    Fix: whitelist de perfis conhecidos.
    Linhas: `FfmpegMediaPolicies.kt` 88–96 (`parseKnownVideoProfile`); delegação ~3027; teste `videoProfileParserDoesNotTreatCodecTagAsProfile`.
    Gravidade: MÉDIO.
    **Status: CORRIGIDO.**

12. Finding: `join_list_*.txt` nunca era apagado.
    Fix: apagar após o concat.
    Linhas: 1726–1728 (delete do arquivo cujo nome casa com `join_list_` em toda sessão).
    Gravidade: BAIXO.
    **Status: CORRIGIDO.**

13. Finding: concat direto de MP3 com `-c copy` (cabeçalho Xing/ID3 incorreto, duração errada).
    Fix: forçar normalização/reencode para MP3.
    Linhas: 566 (`firstAudioExtension == "mp3"` força `audioNeedsNormalization`).
    Gravidade: BAIXO.
    **Status: CORRIGIDO.**

14. Finding: nomes internos do FFmpeg exibidos ao usuário no menu de transição.
    Fix: rótulos em português com valor interno separado.
    Linhas: ~3183–3210 (`TRANSITIONS` em PT + `VIDEO_TRANSITION_VALUES` nome→valor), `AUDIO_TRANSITIONS` em PT ("Curva linear"→tri etc.).
    Gravidade: BAIXO.
    **Status: CORRIGIDO.**

15. Finding: encoder/qualidade lidos na thread de trabalho.
    Fix: capturar antes da Thread.
    Linhas: ~545 (`processingVideoQuality = selectedVideoQuality` em `startJoin`), 3000 (`encodingFor(processingVideoQuality, ...)`).
    Gravidade: BAIXO.
    **Status: CORRIGIDO.**

16. Finding: `configureFadeProcessingPlan` era código morto (sem chamador).
    Fix: remover ou ligar ao fluxo.
    Linhas: função removida no commit `85b889f` (confirmado no diff; não existe mais no arquivo).
    Gravidade: BAIXO.
    **Status: CORRIGIDO.**

---

## Ferramenta de extrair áudio

Arquivo: [FfmpegExtractAudioActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegExtractAudioActivity.kt)

1. Finding: padrão de fábrica era WAV 16 kHz mono (idêntico ao preset de transcrição, mas com checkbox desmarcado).
   Fix: subir o padrão ou marcar o checkbox coerente.
   Linhas: 150–152 (`sampleRate = 48000; channels = 2`); presets marcam/desmarcam em sincronia (1100–1129).
   Gravidade: ALTO.
   **Status: CORRIGIDO.**

2. Finding: sem caminho de cópia; WAV sempre 16-bit; MP3 travado em CBR.
   Fix: oferecer `-c:a copy` quando a extensão casar com o codec; expor CBR/VBR.
   Linhas: 958+ (`canCopyAudioWithoutConversion`) e 873–875 (`-c:a copy` no comando) — cópia sem perdas implementada, inclusive em lote; **mas** 883 (`AudioExtension.MP3` ainda emite `-minrate`/`-maxrate` = CBR forçado) e 1247–1251 (sem escolha CBR/VBR).
   Gravidade: ALTO.
   **Status: NÃO CORRIGIDO (parcial)** — a cópia atende o cerne do finding para os casos compatíveis; CBR forçado do MP3 e ausência de escolha CBR/VBR persistem.

3. Finding: OPUS forçado como VOIP + CBR (`-application voip -vbr off`).
   Fix: `-application audio` e VBR ligado.
   Linhas: 895–900 (`"-application", "audio", ..., "-vbr", "on"`).
   Gravidade: ALTO.
   **Status: CORRIGIDO.**

4. Finding: `-map 0:a:0` sem `?` e sempre a primeira faixa.
   Fix: validar faixas antes; permitir escolha.
   Linhas: 751+ (`requestAudioTrack` com rótulos de faixa/idioma/canais), 763–770 (bloqueio de arquivo sem áudio e de faixa não escolhida), 872 (`-map 0:a:$audioTrack`).
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

5. Finding: desmarcar um preset mantinha os valores dele.
   Fix: guardar e restaurar o estado anterior.
   Linhas: 1100–1129 (`savedCustomSettings` salvo ao ligar preset e restaurado ao desligar).
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

6. Finding: política de Opus divergente entre as ferramentas.
   Fix: unificar em `-application audio` + VBR.
   Linhas: Extrair 895–900; Cortar 967 (`libopus -vbr on -application audio`); Inserir (mesma política).
   Gravidade: BAIXO.
   **Status: CORRIGIDO.**

7. Finding: seleção de arquivo único sempre injetava `-ss 0 -t <duração>` (áudio truncado se o campo estivesse desatualizado).
   Fix: só emitir `-ss`/`-t` quando houver corte real.
   Linhas: 863–871 (`hasStartTrim`/`hasEndTrim` com tolerância de 250 ms contra a duração real).
   Gravidade: BAIXO.
   **Status: CORRIGIDO.**

8. Finding: dois processos FFmpeg extras por arquivo só para o terminal (`describeAudioFile` entrada+saída).
   Fix: extrair do próprio log / remover as sondagens.
   Linhas: 1653–1704 (`describeAudioFile`/`probeAudioFile`/`estimateBitrate` continuam definidas mas SEM call sites — verificado por busca); comando real exibido via `FfmpegCommandPresenter` (1566–1567).
   Gravidade: BAIXO.
   **Status: CORRIGIDO** (as sondagens saíram do fluxo; as funções órfãs são código morto a limpar — higiene, não finding).

9. Finding: terminal mostrava o bitrate do CONTAINER como se fosse o do áudio.
   Fix: procurar na `audioLine`.
   Linhas: ~1692–1696 (`.find(audioLine)` em vez de `.find(logs)`).
   Gravidade: BAIXO.
   **Status: CORRIGIDO.**

10. Finding: menu de bitrate oferecia 24k, rejeitado/clampado em silêncio pelo libmp3lame.
    Fix: remover/filtrar da lista.
    Linhas: 1248 (lista sem "24k": 32k–256k).
    Gravidade: BAIXO.
    **Status: CORRIGIDO.**

---

## Ferramenta de inserir áudio

Arquivo: [FfmpegInsertAudioActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegInsertAudioActivity.kt)

1. Finding: `-ss` antes de `-i` com `-c copy` no trecho direito; pedaços eram containers completos unidos por demuxer concat (deriva de até ~85 ms).
   Fix: estratégia .ts + aac_adtstoasc, ou `-ss` depois de `-i` com reencode das bordas.
   Linhas: ~536–540 (`val fullReencode = true` incondicional — toda execução passa pelo reencode preciso), 547 (única chamada de execução); `executeCopyInsert` 707+ permanece SEM call sites.
   Gravidade: ALTO.
   **Status: NÃO CORRIGIDO (mitigado)** — a rota insegura está inalcançável; o código legado (com o problema original) permanece morto no arquivo e deve ser removido ou reescrito antes de qualquer reativação.

2. Finding: Smart Insert reencodava só o trecho inserido e unia com `-c copy` (parâmetros de stream podiam divergir).
   Fix: recodificar/validar todas as partes, ou remover a rota.
   Linhas: 732+ (`executeSmartInsert` sem call sites; dispatcher único no reencode completo).
   Gravidade: ALTO.
   **Status: NÃO CORRIGIDO (mitigado)** — mesma situação do item 1.

3. Finding: validação de cópia ignorava sample_fmt, perfil AAC e extradata.
   Fix: comparar campos extras ou remover o validador com a rota.
   Linhas: 531 (chamada ainda existe) e 1068+ (`audioInputsAreCopyCompatible` segue comparando só codec/taxa/canais) — porém o resultado não decide mais rota (`fullReencode = true` sempre).
   Gravidade: MÉDIO.
   **Status: NÃO CORRIGIDO (mitigado)** — o risco real desapareceu porque a rota de cópia é inalcançável; o validador fraco permanece (código morto se a rota for removida).

4. Finding: o campo de tempo exibia a posição do playhead, não o ponto de inserção (o executado era outro).
   Fix: campo dedicado ao ponto de inserção; só reescrever quando mudar.
   Linhas: 260 e 420 (`inputTime` agora mostra `insertionMs`); as sobrescritas pelo playhead (`compositePositionMs`) foram removidas em `seekComposite`/`finishPlayback`/`seekTo`.
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

5. Finding: `detectAudioProfile` só reconhecia mono/estereo (5.1 virava 2).
   Fix: MediaExtractor / regex de layout.
   Linhas: ~815+ (`KEY_CHANNEL_COUNT` real com fallback).
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

6. Finding: `-map 0:a:0` sem `?` em todas as extrações de pedaço (aborto no meio do processo).
   Fix: validar presença de áudio antes / usar faixa selecionada em todas as rotas.
   Linhas: rota ativa usa `[0:a:${jobConfig.mainAudioTrack}]` e `[1:a:${jobConfig.insertedAudioTrack}]` (613, 620, 624) com validação prévia de faixas (~491–500); as rotas legadas (707–759) ainda fixam `0:a:0` mas estão sem call sites.
   Gravidade: MÉDIO.
   **Status: NÃO CORRIGIDO (mitigado)** — rota ativa correta; rotas mortas permanecem com o padrão antigo.

7. Finding: "Recodificar" era ignorado quando Smart Insert + transição "none".
   Fix: priorizar fullReencode quando marcado.
   Linhas: ~536–540 (`fullReencode = true` incondicional).
   Gravidade: BAIXO.
   **Status: CORRIGIDO.**

8. Finding: nomes internos do FFmpeg no menu de transição (26 itens técnicos em inglês).
   Fix: rótulos em português com valor interno separado.
   Linhas: 1135–1155 (`AUDIO_TRANSITIONS` "Sem transição"/"Fade de entrada/saída"/"Curva linear"→tri etc.).
   Gravidade: BAIXO.
   **Status: CORRIGIDO.**

---

## Ferramenta de limpar áudio

Arquivo: [FfmpegCleanAudioActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegCleanAudioActivity.kt)

1. Finding: saída sempre WAV 16 kHz mono, sem opção e sem aviso (destrutivo).
   Fix: preservar/expor o formato de saída.
   Linhas: 267–279 (`-c:a sourceProfile.pcmEncoder -ar <origem> -ac <origem>`), 306–323 (`pcm_u8/pcm_f32le/pcm_s24le/pcm_s32le/pcm_s16le` conforme a profundidade da origem).
   Gravidade: ALTO.
   **Status: CORRIGIDO** — taxa, canais e profundidade da origem são preservados.

2. Finding: `anlmdn` (modo forte) extremamente lento, sem estimativa.
   Fix: avisar/dar estimativa antes de executar.
   Linhas: 182–193 (diálogo "Limpeza forte" com "Estimativa conservadora: X a Y" = duração × 2 a × 6).
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

3. Finding: `-map 0:a:0` sem `?` (vídeo sem áudio abortava o FFmpeg).
   Fix: validar faixa antes.
   Linhas: 177–181 ("O arquivo precisa ter exatamente uma faixa de áudio." quando `trackCount != 1`).
   Gravidade: MÉDIO.
   **Status: CORRIGIDO.**

4. Finding (baixo): só o Limpar mostrava o comando FFmpeg ao usuário.
   Fix: padronizar nas 6 ferramentas.
   Linhas: `FfmpegCommandPresenter.show` presente nas 6 Activities (verificado por busca: Rotate 633/667/862, Cut 1235, Join 1691, Extract 1566, Insert 772/793, Clean 378) + log `Log.i` com `FfmpegMediaPolicies.formatCommand`.
   Gravidade: BAIXO.
   **Status: CORRIGIDO.**

---

## Achados transversais

1. [ALTO] Nenhuma ferramenta preservava legendas → **CORRIGIDO nos pontos aplicáveis**: Girar mapeia `0:s?` + `-c:s copy` (1050/1062, 1251); Cortar mapeia `0:s?` nos 3 caminhos (650/844/865); Join rejeita vídeo+legenda com aviso explícito no modo com transições e usa `-map 0` no concat direto; Extrair/Inserir/Limpar são só-áudio (N/A). Ressalvas residuais listadas nos itens Girar-15 e Cortar-3.
2. [MÉDIO] `-map_metadata` inconsistente → **CORRIGIDO**: Girar usa `0` nos 3 pontos (935/1065/1256); Cortar `0` em todos (651/656/762/845/866); Join concat direto `0` (1199); Extrair `0` (872); Inserir/Limpar sem mapeamento (padrão do FFmpeg copia — aceitável para arquivos só-áudio).
3. [BAIXO] Nenhum teste cobria a montagem de comandos → **PARCIAL**: `FfmpegMediaPoliciesTest` (8 casos) e `SmartJoinPlannerTest` cobrem as seams puras novas (rotação, faixa de áudio, canais, perfil de vídeo); `buildFfmpegArguments` das Activities continua sem teste direto — as seams existem para expandir.

---

## Pendências consolidadas (14)

| # | Ferramenta | Finding | Gravidade | Situação |
|---|---|---|---|---|
| 1 | Girar | #6 — modo metadados não valida codecs/streams contra o container | ALTA | Parcial (extensão herda da origem; falta validação) |
| 2 | Girar | pós-fix — `0:v:0` preserva só a 1ª faixa de vídeo | ALTA | Por desenho (documentada) |
| 3 | Cortar | #3 — `0:v:0?` preserva só a 1ª faixa de vídeo | ALTA | Parcial (áudio/legendas/dados/capítulos preservados) |
| 4 | Cortar | #7 — corte de áudio sempre reencoda (sem `-c:a copy`, WAV 16-bit, MP3 CBR) | MÉDIA | **Não corrigido** (única pendência integral) |
| 5 | Juntar | #2 — Smart Join legado permanece no código (rota insegura) | CRÍTICA | Mitigado (inalcançável; remover/reescrever antes de reativar) |
| 6 | Juntar | #3 — reencode processa 1 faixa de áudio; vídeo+legendas é rejeitado | ALTA | Parcial (seletor de faixa existe; concat N-faixas não) |
| 7 | Juntar | #4 — codec de áudio do perfil agregado sem validação cruzada/aviso | ALTA | Parcial (preserva codec por perfil; sem checagem entre clipes) |
| 8 | Juntar | #6 — pré-validação sem extradata/codec tag/container efetivo | ALTA | Parcial (perfis principais validados e bloqueados) |
| 9 | Juntar | #8 — rótulo "Normalizando pelo primeiro áudio" (usa perfil agregado) sem confirmação | ALTA | Parcial (comportamento correto; rótulo impreciso, linha 1047) |
| 10 | Extrair | #2 — MP3 ainda CBR forçado (`-minrate/-maxrate`), sem CBR/VBR | MÉDIA | Parcial (cópia sem perdas implementada) |
| 11 | Inserir | #1 — `executeCopyInsert` legado (`-ss` antes de `-i` + concat de containers) | ALTA | Mitigado (sem call sites; remover/reescrever) |
| 12 | Inserir | #2 — `executeSmartInsert` legado (bordas copiadas sem validar) | ALTA | Mitigado (sem call sites) |
| 13 | Inserir | #3 — validador fraco `audioInputsAreCopyCompatible` | MÉDIA | Mitigado (inócuo enquanto a rota de cópia não existe) |
| 14 | Inserir | #6 — rotas legadas fixam `0:a:0` | MÉDIA | Mitigado (rota ativa usa faixa selecionada) |

## Higiene (não é finding; limpeza futura)

- Código morto acumulado dos fixes: `executeCopyInsert`/`executeSmartInsert` (Inserir), `executeSmartJoinExperiment` + `buildTransitionArgumentsMkv` (Join), `describeAudioFile`/`probeAudioFile`/`estimateBitrate` (Extrair), `detectContainerBitrateKbps` (Join).
- Rótulo do Join (linha 1047): trocar "Normalizando pelo primeiro áudio" por "Normalizando pelo melhor perfil" — 1 linha, resolve a pendência 9 pela metade.
- Cortar #7 tem o padrão pronto no Extrair (`canCopyAudioWithoutConversion`) para reaproveitar.
