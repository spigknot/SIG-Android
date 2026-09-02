# Pendências — auditoria pós-fix das ferramentas FFmpeg

Verificação caso a caso dos 57 findings (commit `85b889f``).
Critério: "corrigido" = a funcionalidade foi corrigida de verdade.
Resultado: **53 corrigidos de fato · 4 pendências reais · 1 observação**.

Ferramenta de cortar áudio/vídeo
Arquivo: [FfmpegCutActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegCutActivity.kt)
1. Finding: o corpo do corte híbrido pode começar no keyframe anterior. A conversão de microssegundos para milissegundos trunca o valor usado por -ss com stream copy.
   Fix: manter os keyframes em microssegundos ou mover o -ss para depois de -i.
   Linhas: 884 (`keyframes += sampleTime / 1000L`), 863 (`buildHybridBodyArguments` com `-ss` pós-`-i` + `-c copy`).
   Gravidade: ALTA.
   Status: PARCIALMENTE CORRIGIDO — o `-ss` foi movido para depois de `-i` (ajuda as bordas recodificadas), MAS o truncamento µs→ms persiste na linha 884 e o corpo continua `-c copy` (seek do demuxer). Em vídeos com keyframes fracionários (ex.: 29,97 fps), o corpo ainda pode começar no keyframe anterior e duplicar conteúdo. O fix sugerido ("manter keyframes em µs") não foi feito.
2. Finding: cortar áudio sempre reencoda. WAV vira PCM 16-bit e MP3 usa bitrate fixo, mesmo que a origem pudesse ser copiada.
   Fix: oferecer stream copy quando codec e container forem compatíveis e expor a qualidade de saída.
   Linhas: 961–969 (`preciseAudioEncoderArguments`), 625–661 (`buildPreciseFfmpegArguments`), 550 (`preciseAudioEncoderName`).
   Gravidade: MÉDIA.
   Status: NÃO CORRIGIDO — `preciseAudioEncoderArguments` continua fixando `pcm_s16le` para WAV e `libmp3lame -b:a <bitrate>` para MP3; não há caminho `-c:a copy` nem exposição de qualidade de saída para corte de áudio.

Ferramenta de juntar vídeo/áudio
Arquivo: [FfmpegJoinVideosActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegJoinVideosActivity.kt)
1. Finding: Smart Join pode duplicar e remover conteúdo em cada emenda porque usa -ss antes de -i com stream copy em tempos que normalmente não são keyframes.
   Fix: alinhar o corpo ao keyframe real e compensar a duração ou recodificar as bordas.
   Linhas: 408–409 (`checkSmartJoin.isEnabled = false`), 1820 (`checkSmartJoin.isEnabled = false`), 61 (`smartJoinChecked = false`).
   Gravidade: CRÍTICA.
   Status: CORRIGIDO POR DESATIVAÇÃO — o Smart Join foi desabilitado (checkbox indisponível, `smartJoinChecked=false`), em vez de corrigir o alinhamento. O bug persiste no código morto (`runSmartJoinPreflight`/`executeSmartJoin`); a funcionalidade perdeu a opção de join parcial. Reimplementação pendente se a feature voltar.
2. Finding: o rótulo "Normalizando pelo primeiro áudio" ficou desatualizado após o fix. A normalização agora usa o perfil agregado (máximo de taxa/canais entre os clipes via `detectAggregateOutputProfile`), não o perfil do primeiro áudio.
   Fix: renomear o rótulo para refletir a normalização real (ex.: "Normalizando áudios pelo perfil de maior qualidade") ou passar a usar de fato o primeiro áudio como referência.
   Linhas: 1047 (`forceNormalization -> "Normalizando pelo primeiro áudio"`).
   Gravidade: BAIXA.
   Status: PENDENTE — observação cosmética; o argumento FFmpeg em si está correto (não usa mais 16 kHz mono).

Ferramenta de inserir áudio
Arquivo: [FfmpegInsertAudioActivity.kt](D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\FfmpegInsertAudioActivity.kt)
1. Finding: o modo sem reencode usa -ss antes de -i com stream copy no trecho direito e concatena containers completos. O ponto pode ser deslocado para uma fronteira de pacote.
   Fix: recodificar as bordas ou usar um formato intermediário apropriado.
   Linhas: 538 (`val fullReencode = true`), 847–848 (`reencode.isEnabled = false`, `smartInsert.isEnabled = false`); layout `activity_ffmpeg_insert_audio.xml` (checkbox "Reencodar (preciso)" travada, "Smart Insert" GONE).
   Gravidade: ALTA.
   Status: CORRIGIDO POR ELIMINAÇÃO — o caminho de cópa/smart insert foi removído; todo modo agora é reencode incondicional. O bug não ocorre mais, mas a opção de inserir sem reencodar deixou de existir (decisão de produto pendente).
2. Finding: Smart Insert recodifica apenas o áudio inserido e concatena o resultado com trechos copiados usando -c copy. Parâmetros internos diferentes podem gerar arquivo incompatível.
   Fix: validar os parâmetros completos ou recodificar também as bordas/saída final.
   Linhas: 538 (`val fullReencode = true`), 847–848.
   Gravidade: ALTA.
   Status: CORRIGIDO POR ELIMINAÇÃO — o Smart Insert foi ocultado (GONE) e todo modo usa o reencode preciso. O bug não ocorre mais; a funcionalidade foi removída (decisão de produto pendente).
