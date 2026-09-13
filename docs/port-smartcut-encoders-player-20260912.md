# Port Windows → Android: SmartCut, modos de corte, seleção de encoder e player

Data: 12/09/2026. Pedido: portar para o SIG Android as mudanças feitas no SIG
Windows no mesmo dia — SmartCut, os três modos de corte, seleção de encoder
(hardware/CPU + Avançado) e os recursos de player (zoom, arrasto e seleção de
área/crop). Fonte da verdade: `src/ffmpeg_tools_panel.py` e
`src/video_encoders.py` do SIG Windows + os testes novos de lá
(`test_video_encoders.py`, `test_ffmpeg_cut_modes.py`,
`test_ffmpeg_area_selection.py`, `test_ffmpeg_preview_stage.py`).

## Resumo do que é portado

| Mudança no Windows | Equivalente no Android |
|---|---|
| SmartCut (miolo copiado + bordas reencodadas, TS + concat) | `FfmpegCutModes` + `FfmpegCutActivity` (já havia um híbrido; agora com as correções do Windows) |
| Modos: SmartCut / Reencode Completo / Sem Reencode (padrão SmartCut) | Seletor novo na tela Cortar + `FfmpegCutModes` (seam puro) |
| Seleção de encoder GPU/CPU + Avançado + "?" e motivo no log | `FfmpegVideoEncoders` (catálogo do aparelho + resolvedor por tarefa) + seletor Hardware/CPU + Avançado na tela |
| Codec preservado + tag `hvc1` | Resolvedor devolve o par do codec do arquivo; `hvc1` no remux/reencode MP4 |
| Falha de hardware repete na CPU só na tarefa (sem rebaixar a preferência) | Mesma regra na execução do Cortar/Girar |
| Player: zoom 1×..5×, arrastar o quadro, seleção de área com alças, "Desfazer seleção", confirmação ao Executar | `FfmpegPreviewSelection` (geometria pura) + view de sobreposição com toque longo/pinça/arrasto |
| Crop força Reencode Completo (Cortar e Girar) | Mesma regra no despacho do Cortar e no Girar (modo metadados desabilitado com seleção) |
| Títulos/subtítulos das abas removidos | Não se aplica: a UI do Android não usa subtítulos de aba nessas telas |

## Decisões de adaptação (diferentes do Windows, de propósito)

1. **Encoders.** O Windows usa NVENC/QSV/AMF/libx264/libx265. O Android não tem
   nenhum desses: o catálogo real do aparelho é `h264_mediacodec` /
   `hevc_mediacodec` (hardware, descobertos por `MediaCodecList`) e `libx264`
   (CPU, só H.264). Verificado no `libavcodec.so` do pacote nativo que o app usa:
   `libx264` presente, **`libx265` ausente** (`0` ocorrências) — logo HEVC não
   tem opção de CPU. Se o aparelho não tiver encoder HEVC, um arquivo HEVC só
   pode sair reencodado em H.264, com confirmação explícita do usuário.
2. **"GPU/CPU" vira "Hardware/CPU".** Mesma lógica de porta de processamento;
   o app decide o encoder concreto.
3. **"Avançado" lista os encoders de hardware POR NOME** (ex.:
   `c2.qti.hevc.encoder`), são os que passaram na sondagem real (encode de 1
   quadro). Forçar um deles acrescenta `-codec_name <nome>` ao comando — a opção
   ("Select codec by name") existe no binário do app.
4. **Regra do trecho curto mantida** (`SHORT_JOB_SECONDS = 3.0`, com motivo no
   log). Pendência honesta: o custo de inicialização do MediaCodec neste
   aparelho ainda não foi medido; o valor do Windows é o ponto de partida.
5. **Player: mouse vira toque.** Roda do mouse → pinça (2 dedos); botão esquerdo
   (arrastar o quadro) → 1 dedo arrasta; botão direito (desenhar a seleção) →
   **toque longo** inicia o desenho; depois a seleção pode ser arrastada e
   redimensionada pelas alças; "Desfazer seleção" (menu do botão direito no
   Windows) → toque longo sobre uma seleção existente abre o diálogo.
6. **Palco com a proporção do vídeo.** O quadro do player passa a ter a
   proporção da mídia (como o palco do Windows), o que faz a matemática de
   zoom/deslocamento/seleção ser exatamente a mesma do Windows.
7. **Velocidades 0,25×/0,5×/1×/2×/4×** já existiam no Android (botões ±) —
   nada a fazer.

## Fases

- **Fase 1 (seams puros + vacinas).** `FfmpegVideoEncoders.kt`,
  `FfmpegCutModes.kt`, `FfmpegPreviewSelection.kt` + testes unitários portando a
  matriz dos testes do Windows.
- **Fase 2 (Cortar).** Seletor de modos + ajuda; seletor Hardware/CPU +
  Avançado (sondagem real) + ajuda + rótulo do motivo; resolução por tarefa;
  SmartCut com as correções (bitstream filters do TS, `-t` fechando o arquivo,
  bordas espelhando o miolo, áudio preciso); modo cópia; preservação de codec +
  `hvc1`; fallback de hardware só na tarefa.
- **Fase 3 (player).** Zoom por pinça, arrasto do quadro, seleção por toque
  longo com alças, "Desfazer seleção", confirmação ao Executar, `crop=` no
  Cortar e no Girar (forçando reencode), seleção reexpressa quando o Girar gira
  o vídeo.
- **Fase 4.** `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`,
  APK.

## Ajustes após o primeiro teste real (12/09, aparelho CPH2747/Android 16)

1. **Arrasto vertical no player e toque longo não funcionavam** (o usuário relatou
   "o arrasto na vertical fica ruim" e "mantive o dedo pressionado, mas não
   aconteceu nada"): **causa única** — o `ScrollView` da tela interceptava o
   gesto que começava dentro do player (cancelava o `ACTION_CANCEL` do toque
   longo e roubava o arrasto vertical). Correção: o overlay chama
   `parent.requestDisallowInterceptTouchEvent(true)` no `ACTION_DOWN` e libera no
   `ACTION_UP/CANCEL` — **toque dentro do player é do player; fora dele a tela
   rola**, como pedido.
2. **Caixa de comando (ffmpeg) movida para o FIM da tela** nas seis telas FFmpeg;
   o que estava abaixo dela (arquivo de saída, botões e estatísticas) subiu para
   a posição que era da caixa.
3. **Rótulo do modo não atualizava**: o botão continuava "SmartCut" depois de
   trocar para "Sem Reencode" (a prévia já mudava). Correção: `updateVideoEncoderButton()`
   escreve `buttonCutMode.text = selectedCutMode`.
4. Feedback do toque longo: ao soltar o dedo, um aviso mostra a seleção em
   pixels ("Seleção: 322 x 162 pixels").

### Evidências de teste (aparelho e emulador)

- **SmartCut real (h264, 640x360, 6 s, GOP 1 s)**: miolo copiado **bit a bit
  idêntico** à fonte (124/124 quadros iguais com 1 quadro de alinhamento), borda
  de 1 s no **libx264 pela regra do trecho curto**, saída decodifica sem erros,
  6,08 s (+2 quadros, igual ao Windows).
- **HEVC**: SmartCut com `hevc_mp4toannexb` no miolo e `hevc_mediacodec` na
  borda; saída continua **HEVC com tag `hvc1`** e decodifica limpo.
- **Sem Reencode**: comando `-c copy -c:t copy`, saída HEVC 59 kb/s (idêntica à
  fonte) — sem reencode.
- **Seleção/crop**: toque longo + arrasto desenha o quadro amarelo; a prévia
  passa a mostrar `-vf crop=W:H:X:Y`; o Executar abre a confirmação com o texto
  do Windows ("Será salvo apenas o que está DENTRO da seleção: 322 x 162 pixels,
  a partir de (100, 87)..."); a saída sai **exatamente 322x162**.
- **Arrasto vertical dentro do player não rola mais a página** (posição dos
  controles idêntica antes/depois do gesto).

## O que fica de fora (com justificativa)

- **Persistência da escolha de encoder**: o Windows também não persistiu (o
  painel FFmpeg não toca as configurações). No Android vale para a sessão.
- **AV1/VP9/WebM/ProRes/H.266**: fora de escopo no Windows também; no Android a
  única diferença é que `vp9_mediacodec` existe no build, mas a decisão é a
  mesma (compatibilidade de vídeo de evidência).
- **Medições de tempo de inicialização do MediaCodec**: só com aparelho em mão.
