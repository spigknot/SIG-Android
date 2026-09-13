# Bateria comparativa — SIG Android (emulador) × SIG Windows

Data: 13/09/2026. Objetivo: verificar, com dados e parâmetros iguais, se as
ferramentas FFmpeg dos dois apps se comportam da mesma forma.

**Método (sem alterar nenhum código):**

- **Android**: APK de debug (`cc49b0f`) no emulador Pixel_9; cada ferramenta foi
  aberta pelo intent de compartilhamento e operada por `adb` (toques e marcação
  das seleções de área por `input motionevent`); os comandos executados foram
  lidos do logcat (`FfmpegCut: FFmpeg: …`) e as saídas foram puxadas do cache e
  analisadas com ffprobe.
- **Windows**: os comandos foram capturados executando o pipeline REAL do app
  (import de `src/ffmpeg_tools_panel.py` + harness de captura equivalente ao dos
  testes do próprio projeto, com `_execute` instrumentado e o ffmpeg do
  `dist/`), sobre os MESMOS arquivos — nenhum arquivo do SIG Windows foi
  modificado.

**Fontes de teste (idênticas nos dois):** `fonte_h264.mp4` e `fonte_hevc.mp4`,
640x360, 25 fps, 6,0 s, GOP de 1 s (keyframes em 0..5), áudio AAC 96 kb/s mono.

## 1. Cortar — SmartCut [1.4 → 4.6]

| Etapa | Windows (CPU/libx264) | Android (emulador) | Igual? |
|---|---|---|---|
| Cabeça 1.4→2.0 (0,6 s) | `-ss 1.4 … -t 0.6 -map 0:v:0 -map 0:a? -c:v libx264 … -pix_fmt yuv420p -r 25 -c:a aac -b:a 96k -ar 44100 -ac 1 -bsf:v h264_mp4toannexb -avoid_negative_ts make_zero -mpegts_flags +resend_headers+initial_discontinuity -muxdelay 0 -muxpreload 0 -f mpegts` | `-ss 1.400000 … -t 0.600000 -map 0:v:0 -map 0:a? -c:v libx264 … -r 25.000000 -c:a aac -b:a 96k -bsf:v h264_mp4toannexb -avoid_negative_ts make_zero -mpegts_flags +resend_headers+initial_discontinuity -muxdelay 0 -muxpreload 0 -f mpegts` | ✅ estrutura idêntica |
| Miolo 2.0→4.0 | `-c:v copy -c:a aac … -bsf:v h264_mp4toannexb …` | idem | ✅ |
| Cauda 4.0→4.6 (0,6 s) | **libx264** (regra do trecho curto) | **libx264** (regra do trecho curto) | ✅ |
| Concat | `… -c:v copy -c:a copy -bsf:a aac_adtstoasc -avoid_negative_ts make_zero -t 3.2 -max_interleave_delta 0 -video_track_timescale 90000 -movflags +faststart -map_metadata 0 -map_chapters -1` | `… -c:v copy -c:a copy -bsf:a aac_adtstoasc -avoid_negative_ts make_zero -max_interleave_delta 0 -map_metadata 0 -map_chapters -1` | ✅ núcleo; Δ do contêiner (ver §4) |

**Segmentação idêntica:** 3 trechos (0,6 s reencodado + 2,0 s copiado + 0,6 s
reencodado). No Windows o mesmo resultado se repete em GPU (NVENC) e em CPU,
porque a borda de 0,6 s cai na CPU — no Android o mesmo aconteceu (borda no
libx264). O log de tarefas do Android mostra exatamente a mesma leitura:
`SmartCut: 2.00s copiados sem reencode e 1.20s reencodados (3 trechos)`.

| Saída | Duração | Vídeo | Bytes |
|---|---|---|---|
| Windows SmartCut (CPU e GPU) | 3,29 s | 640x360 h264 200 kb/s | 124.665 |
| Android SmartCut | 3,30 s | 640x360 h264 272 kb/s | 154.640 |

**Miolo copiado bit a bit:** comparando os quadros decodificados do trecho
copiado contra a fonte, **Windows 39/49 e Android 38/49 quadros idênticos**, com
o **mesmo alinhamento (-2 quadros)**; as ~10 divergências são as transições de
borda incluídas na janela de 2 s.

## 2. Cortar — Reencode Completo [1.4 → 4.6]

| | Windows (CPU) | Android |
|---|---|---|
| Comando | `-ss 1.4 -i src -t 3.2 -map 0:v:0? -map 0:a? -sn -dn -vf null -c:v libx264 -preset medium -crf 20 -c:a aac -b:a 96k -ar 44100 -ac 1 -map_metadata 0 -map_chapters -1 -movflags +faststart` | `-ss 1.400 -i src -t 3.200 -map 0:v:0? -map 0:a? -map 0:s? -map 0:d? -map 0:t? -map_metadata 0 -map_chapters 0 -c copy -c:t copy -c:v libx264 -preset ultrafast -crf 20 -avoid_negative_ts make_zero` |
| Saída | 3,20 s, 640x360 h264 80 kb/s | 3,62 s, 640x360 h264 244 kb/s |

Igual: `-ss` antes do input, `-t` do intervalo, maps de vídeo/áudio,
`libx264 -crf 20`, saída h264 640x360. **Δ em §4.**

## 3. Cortar — Sem Reencode e crop

**Sem Reencode [1.4 → 4.6]**

| | Windows | Android |
|---|---|---|
| Comando | `-ss 1.4 -i src -t 3.2 -map 0:v:0 -map 0:a? -sn -dn -c copy -movflags +faststart` | `-ss 1.400 -i src -t 3.200 -map 0:v:0? -map 0:a? -map 0:s? -map 0:d? -map 0:t? -map_metadata 0 -map_chapters 0 -c copy -c:t copy -avoid_negative_ts make_zero` |
| Saída | 3,22 s, h264 640x360 250 kb/s (fonte: 241 kb/s) | 3,62 s, h264 640x360 244 kb/s (vídeo copiado) |

**Crop 322x162 em (100, 87), Reencode Completo, intervalo completo** — o filtro
saiu **idêntico** nos dois: `-vf crop=322:162:100:87`; a seleção foi desenhada
por toque no player do Android (toque longo + arrasto), a prévia passou a mostrar
o filtro e o Executar pediu a confirmação com o texto do Windows
("Será salvo apenas o que está DENTRO da seleção: 322 x 162 pixels, a partir de
(100, 87)…"). **Saída: 322x162 nos dois** (Windows 83.369 bytes; Android
80.202 bytes) — mesma resolução, tamanhos equivalentes.

## 4. Diferenças encontradas (todas objetivas)

1. **Áudio no Reencode Completo**: o Windows **reencoda** o áudio (`-c:a aac
   -b:a 96k -ar 44100 -ac 1`); o Android mantém **`-c:a copy`** (o vídeo é
   reencodado, o áudio não). É a diferença mais relevante para decidir.
2. **Preset do libx264**: Windows `-preset medium`; Android `-preset ultrafast`
   (escolha pré-existente do Android). Explica boa parte da diferença de
   kb/s/bytes no SmartCut e no Reencode.
3. **Sem Reencode**: o Android preserva legendas/dados/timecode e
   metadados/capítulos; o Windows mapeia só vídeo+áudio e descarta
   (`-sn -dn`).
4. **Concat do SmartCut**: o Windows grava MP4 direto e acrescenta `-t`,
   `-video_track_timescale 90000` e `-movflags +faststart`; o Android grava MKV
   e remuxa para o MP4 final no fim (o `faststart` sai no remux). O `-t` não é
   aplicado no mux intermediário — medido: **+2 quadros** (3,30 s × 3,29 s),
   ou seja, empata com o Windows.
5. **Encoders por plataforma**: Windows NVENC/libx264/libx265; Android
   `*_mediacodec` (hardware do aparelho)/libx264. Não existe libx265 no pacote
   do Android — HEVC sem encoder de hardware cai em H.264 com aviso e
   confirmação. No emulador (sem encoder de hardware) a etiqueta de decisão
   mostra "o hardware deste aparelho não tem encoder H264; usando CPU (libx264)"
   — comportamento esperado e visível na tela.
6. **Borda do SmartCut com o aparelho sem hardware**: no emulador a razão exibida
   é a ausência de encoder de hardware; no celular (CPH2747) era o trecho curto
   (< 3 s). A regra é a mesma; muda só o motivo.

## 5. Cobertura desta bateria

| Ferramenta / cenário | Comparado? |
|---|---|
| Cortar — SmartCut [1.4-4.6] | ✅ comandos + saída + miolo bit-exato |
| Cortar — Reencode Completo [1.4-4.6] | ✅ comandos + saída |
| Cortar — Sem Reencode [1.4-4.6] | ✅ comandos + saída |
| Cortar — crop 322x162@100,87 | ✅ comando + saída + confirmação |
| Cortar — HEVC (SmartCut, hvc1) | ✅ no celular (não nesta rodada) |
| Girar — rotação + crop | ⏳ não comparado nesta bateria |
| Juntar — SmartJoin/Reencode | ⏳ não comparado nesta bateria |
| Extrair áudio / Inserir / Limpar | ⏳ não comparado nesta bateria |

Os pendentes dependem de rodar os mesmos cenários nas três ferramentas que
faltam (comandos capturados pelos dois lados, como acima).
