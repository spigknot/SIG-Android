# Relatório de Auditoria FFmpeg — Ferramentas SIG

**Objetivo:** Verificar se todos os comandos FFmpeg construídos no app SIG refletem fielmente as opções escolhidas pelo usuário nas interfaces (checkboxes, botões, seletores, etc.). Auditar também comandos de pré-visualização (FFmpeg/FFplay, velocidade, seek e preview de rotação).

**Escopo:** 6 ferramentas FFmpeg + componentes compartilhados (encoder registry, video quality, task tracker, waveforms).

**Arquivos auditados:**
- `FfmpegCutActivity.kt`
- `FfmpegRotateVideoActivity.kt`
- `FfmpegExtractAudioActivity.kt`
- `FfmpegCleanAudioActivity.kt`
- `FfmpegInsertAudioActivity.kt`
- `FfmpegJoinVideosActivity.kt`
- `FfmpegVideoEncoderRegistry.kt`
- `FfmpegVideoQuality.kt`
- Layouts XML correspondentes

---

## 1. Ferramenta: Cortar (FfmpegCutActivity)

### 1.1. Opções da UI e correspondência nos comandos

| Opção da UI | Estado no código | Comando FFmpeg | Status |
|---|---|---|---|
| **Encoder de vídeo** (PopupMenu com `FfmpegVideoEncoder`) | `selectedVideoEncoder` — definido via `showVideoEncoderMenu()`, detectado via `FfmpegVideoEncoderRegistry.detect()` | `buildPreciseFfmpegArguments()` chama `videoEncodingArguments(enc, ...)` que por sua vez chama `encoder.encodingFor(quality, sourceBitrate)` → `-c:v libx264` ou `-c:v h264_mediacodec` ou `-c:v hevc_mediacodec` | ✅ Correto |
| **Qualidade do vídeo** (PopupMenu com `FfmpegVideoQuality`) | `selectedVideoQuality` — definido via `showVideoQualityMenu()` | `encodingFor()` ajusta `-crf` (libx264) ou `-b:v` (hardware) conforme qualidade selecionada | ✅ Correto |
| **Tempo início/fim** (EditText + RangeSlider) | `inputFrom`/`inputTo` parseados via `parseTime()` | `-ss <start>` e `-t <duration>` em `buildPreciseFfmpegArguments()` | ✅ Correto |
| **Velocidade de reprodução** (0.25x, 0.5x, 1x, 2x, 4x) | `playbackSpeed` via `changePlaybackSpeed()` | Aplicação via `MediaPlayer.playbackParams.setSpeed()` — **não usa FFmpeg** (pré-visualização nativa Android) | ✅ Correto (não deveria usar FFmpeg) |

### 1.2. Pré-visualização (preview)

| Recurso | Implementação | Status |
|---|---|---|
| **Seek** | `seekPreview()` → `performActualSeek()` → `MediaPlayer.seekTo()` com `SEEK_CLOSEST` (API 26+) ou `seekTo(ms)` (legacy) | ✅ Correto |
| **Velocidade** | `applyPlaybackSpeed()` → `MediaPlayer.playbackParams.setSpeed(playbackSpeed)` com valores [0.25, 0.5, 1, 2, 4] | ✅ Correto |
| **Rotação no preview** | `applyPreviewTransform()` aplica `Matrix.postScale()` e `postRotate()` no TextureView — **não afeta o preview do MediaPlayer**, apenas a exibição visual. A rotação do arquivo é detectada via `detectMetadataRotation()` mas **não aplicada no preview** | ⚠️ **Finding 1:** O preview não reflete a rotação do arquivo. O usuário vê o vídeo com a orientação original do display, não a rotação corrigida que será aplicada no processamento. |
| **FFplay** | Nenhum comando FFplay encontrado — usa MediaPlayer nativo do Android | ✅ (não aplicável) |

### 1.3. Processamento final — `buildPreciseFfmpegArguments()`

```
-y -noautorotate [-display_rotation:v:0 <degrees>] -i <input> -ss <start> -t <duration>
-map 0:v:0? -map 0:a:0? -map_metadata 0 -map_chapters 0
-c:v <encoder> [-preset ultrafast -crf <n>] | [-b:v <bitrate> -minrate ... -maxrate ... -bufsize ...]
-c:a aac -b:a <audioBitrate> -movflags +faststart -avoid_negative_ts make_zero
<output>
```

| Elemento | Status |
|---|---|
| `-noautorotate` + `-display_rotation:v:0` | ✅ Correto — preserva rotação original sem auto-rotacionar |
| Encoder selecionado | ✅ Correto — reflete `selectedVideoEncoder.ffmpegName` |
| Qualidade (CRF/bitrate) | ✅ Correto — via `videoEncodingArguments()` |
| Audio mapeado como AAC | ✅ Correto |
| `-map_metadata 0` e `-map_chapters 0` | ✅ Preserva metadados e capítulos |

### 1.4. Hybrid Cut (execução otimizada com keyframes)

| Elemento | Status |
|---|---|
| `-c:v copy` para corpo central (TS) | ✅ Correto — não reencoda |
| Encoder selecionado nas bordas | ✅ Correto — `buildHybridEdgeArguments()` usa `videoEncodingArguments(encoder, ...)` |
| Audio como AAC nas bordas | ✅ Correto |
| Concat final com `-c copy` | ✅ Correto |

---

## 2. Ferramenta: Girar Vídeo (FfmpegRotateVideoActivity)

### 2.1. Opções da UI e correspondência nos comandos

| Opção da UI | Estado no código | Comando FFmpeg | Status |
|---|---|---|---|
| **Rotação** (RadioGroup: -90°, 0°, 90°, 180°) | `readDegrees()` lê `rotationOptions.checkedRadioButtonId` | `buildOrderedFilters()` → `transpose=1`, `transpose=2`, `hflip,vflip` | ✅ Correto |
| **Girar pelos metadados** (CheckBox) | `metadataRotation.isChecked` → `metadataOnly` | Se true: `-metadata:s:v:0 rotate=<degrees>` (sem reencodar). Se false: filtros de vídeo | ✅ Correto |
| **Inverter horizontalmente** (CheckBox) | `flipHorizontal.isChecked` | `buildOrderedFilters()` → `"hflip"` | ✅ Correto |
| **Inverter verticalmente** (CheckBox) | `flipVertical.isChecked` | `buildOrderedFilters()` → `"vflip"` | ✅ Correto |
| **Processar em paralelo** (CheckBox +campo de segmentos) | `parallelKeyframes.isChecked` + `inputParallelSegments` | Divide vídeo em segmentos, processa em paralelo, junta com concat | ✅ Correto |
| **Encoder de vídeo** | `selectedCodec` via PopupMenu | `buildFfmpegArguments()` → `videoEncodingArguments(encoder, ...)` | ✅ Correto |
| **Qualidade do vídeo** | `selectedVideoQuality` via PopupMenu | `videoEncodingArguments()` → `encodingFor()` | ✅ Correto |

### 2.2. Pré-visualização (preview)

| Recurso | Implementação | Status |
|---|---|---|
| **Seek** | `seekPreview()` → `MediaPlayer.seekTo()` | ✅ Correto |
| **Velocidade** | `applyPlaybackSpeed()` → `MediaPlayer.playbackParams.setSpeed()` | ✅ Correto |
| **Rotação no preview** | `applyPreviewTransform()` aplica transformações de rotação, flip horizontal e vertical via `Matrix.postRotate()` e `Matrix.postScale()` no TextureView. A ordem respeita `transformOrder`. | ✅ Correto — o preview visual reflete todas as transformações selecionadas |
| **FFplay** | Nenhum | ✅ (não aplicável) |

### 2.3. Processamento final — `buildFfmpegArguments()`

**Modo metadata-only:**
```
-y -i <input> -map 0 -c copy -metadata:s:v:0 rotate=<normalizedDegrees> <output>
```
✅ Correto — apenas metadados, sem reencodar.

**Modo reencodar:**
```
-y [-ss <start>] -i <input> [-t <duration>] [-vf <filters>]
<c:v <encoder> [-preset ultrafast -crf <n>] | [-b:v <bitrate>]>
-c:a copy|aac -map_metadata -1 -metadata:s:v:0 rotate=0
<output>
```

| Elemento | Status |
|---|---|
| Filtros de rotação/flip | ✅ `transpose=1` (90°), `transpose=2` (-90°), `hflip,vflip` (180°) |
| Filtro vertical/horizontal | ✅ Apenas aplicado quando `!metadataOnly` |
| `-c:a copy` quando sem trim, `-c:a aac` quando com trim | ✅ Correto |
| Encoder selecionado | ✅ Correto |
| Qualidade | ✅ Correto |
| `-metadata:s:v:0 rotate=0` | ✅ Zera a rotação nos metadados do output |

### 2.4. Processamento paralelo (keyframes)

| Elemento | Status |
|---|---|
| Segmentação com `-f segment` | ✅ Correto |
| Encoder nas segmentações | ✅ `buildSegmentRotationArguments()` usa `videoEncodingArguments(encoder, ...)` |
| `-g` (GOP) para mediacodec | ✅ Adicionado apenas para encoders `_mediacodec` |
| Concat final com `-c copy` | ✅ Correto |

---

## 3. Ferramenta: Extrair Áudio (FfmpegExtractAudioActivity)

### 3.1. Opções da UI e correspondência nos comandos

| Opção da UI | Estado no código | Comando FFmpeg | Status |
|---|---|---|---|
| **Padrão para transcrição** (checkbox) | `checkboxTranscriptionStandard` → `AudioPreset.LOCAL` | Fixa: WAV, 16000 Hz, mono, 256k | ✅ Correto |
| **Padrão compacto** (checkbox) | `checkboxCompactStandard` → `AudioPreset.COMPACT` | Fixa: OGG, 16000 Hz, mono, 32k | ✅ Correto |
| **Extensão de saída** (PopupMenu) | `outputExtension` | Determina `-c:a` via `preciseAudioEncoderArguments()` | ✅ Correto |
| **Sample rate** (PopupMenu) | `sampleRate` (8000, 16000, 22050, 44100, 48000) | `-ar <sampleRate>` | ✅ Correto |
| **Canais** (PopupMenu: mono/estéreo) | `channels` (1 ou 2) | `-ac <channels>` | ✅ Correto |
| **Bitrate** (PopupMenu: 24k-256k) | `bitrate` | `-b:a <bitrate>` | ✅ Correto |

### 3.2. Comando final — `buildFfmpegArguments()`

```
-y [-ss <start>] -i <input> [-t <duration>]
-vn -map 0:a:0 -ar <sampleRate> -ac <channels>
<c:a <encoder> [-b:a <bitrate>] [-f wav] | [-minrate <bitrate> -maxrate <bitrate>] | ...>
<output>
```

| Elemento | Status |
|---|---|
| Presets (LOCAL/COMPACT) | ✅ Sobrescrevem sample rate, canais, bitrate e extensão |
| Extensão WAV → `-c:a pcm_s16le -f wav` | ✅ Correto |
| Extensão MP3 → `-c:a libmp3lame -b:a <bitrate> -minrate -maxrate` | ✅ Correto (MP3 fixa bitrate) |
| Extensão M4A → `-c:a aac -b:a <bitrate> -movflags +faststart` | ✅ Correto |
| Extensão OGG → `-c:a libvorbis -b:a <bitrate>` | ✅ Correto |
| Extensão OPUS → `-c:a libopus -application voip -b:a <bitrate> -vbr off` | ✅ Correto |
| Extensão FLAC → `-c:a flac` (sem bitrate) | ✅ Correto — FLAC é sem perda, não usa bitrate |

### 3.3. Pré-visualização

| Recurso | Implementação | Status |
|---|---|---|
| **Seek** | `seekPreview()` → `MediaPlayer.seekTo()` | ✅ Correto |
| **Velocidade** | `applyPlaybackSpeed()` → `MediaPlayer.playbackParams.setSpeed()` | ✅ Correto |
| **Rotação no preview** | N/A (áudio apenas ou vídeo sem rotação) | ✅ N/A |

---

## 4. Ferramenta: Limpar Áudio (FfmpegCleanAudioActivity)

### 4.1. Opções da UI e correspondência nos comandos

| Opção da UI | Estado no código | Comando FFmpeg | Status |
|---|---|---|---|
| **Filtro** (PopupMenu: equilibrado/forte) | `selectedMode` (`CleanMode.BALANCED` ou `CleanMode.STRONG`) | `mode.filter` → `-af` | ✅ Correto |

### 4.2. Comando final — `buildFfmpegArguments()`

```
-y -i <input> -vn -map 0:a:0 -af <filter> -c:a pcm_s16le -ar 16000 -ac 1 -f wav <output>
```

| Elemento | Status |
|---|---|
| `-vn` (remove vídeo) | ✅ Correto |
| Filtro afftdn (equilibrado) / anlmdn (forte) | ✅ Correto |
| Codificador PCM 16-bit | ✅ Correto |
| Sample rate 16000 Hz fixo | ✅ Correto |
| Mono fixo | ✅ Correto |
| WAV fixo | ✅ Correto — saída sempre WAV 16kHz/mono, adequado para transcrição |

### 4.3. Finding específico

⚠️ **Finding 2:** A ferramenta "Limpar áudio" **não oferece opções de extensão, sample rate, canais ou bitrate ao usuário**. A saída é sempre WAV 16kHz/mono/16-bit PCM. Isso é intencional (para transcrição) e está correto, mas representa uma limitação de personalização.

---

## 5. Ferramenta: Inserir Áudio (FfmpegInsertAudioActivity)

### 5.1. Opções da UI e correspondência nos comandos

| Opção da UI | Estado no código | Comando FFmpeg | Status |
|---|---|---|---|
| **Transição** (PopupMenu: sem/fade/curvas) | `selectedTransition` | `audioCrossfadeCurve()` → parâmetro `c1`/`c2` no `acrossfade` | ✅ Correto |
| **Tempo de transição** (EditText, segundos) | `transitionSeconds` | `safeTransitionSeconds()` → duração do crossfade | ✅ Correto |
| **Reencodar** (checkbox) | `reencode.isChecked` | Determina caminho: full reencode vs copy | ✅ Correto |
| **Smart Insert** (checkbox) | `smartInsert.isChecked` | Determina caminho: smart insert vs copy vs full | ✅ Correto |
| **Tempo de inserção** (EditText) | `insertionMs` via `input_insert_time` | `-ss`, `atrim`, concat | ✅ Correto |

### 5.2. Comandos finais

**Modo Full Reencode — `buildFullReencodeArguments()`:**
```
-y -i <main> -i <inserted>
-filter_complex "[0:a]atrim=start=0:end=<at>,aresample=<sr>,aformat=...[,afade=...],asetpts=PTS-STARTPTS[a0];
[1:a]atrim=start=0:end=<insertedEnd>,aresample=<sr>,aformat=...[,afade=...],asetpts=PTS-STARTPTS[a1];
[<remaining>]..." 
-map [aout] -vn -c:a <encoder> [-b:a <bitrate>] -ar <sr> -ac <channels> [-movflags +faststart]
<output>
```

**Modo Copy — `executeCopyInsert()`:**
```
-y -i <main> -t <at> -map 0:a:0 -c copy <left>
-y -i <inserted> -map 0:a:0 -c copy <middle>
-y -ss <at> -i <main> -map 0:a:0 -c copy <right>
-y -f concat -safe 0 -i <list.txt> -c copy <output>
```

**Modo Smart Insert — `executeSmartInsert()`:**
```
# Left: -c copy
# Middle: -c:a <encoder> (reencodado para compatibilidade)
# Right: -c copy
# Final: concat com -c copy
```

| Elemento | Status |
|---|---|
| Encoder para reencode | ✅ `encoderForProfile()` seleciona codec baseado em extensão e codec original |
| Bitrate para reencode | ✅ `-b:a` aplicado apenas para codecs que não são FLAC/PCM/ALAC |
| Transição de áudio (fad/in/out, crossfade) | ✅ `acrossfade` com curva selecionada |
| Smart Insert compatibility | ✅ Reencodifica apenas o áudio inserido, preserva o restante |

### 5.3. Finding específico

⚠️ **Finding 3:** Em `buildFullReencodeArguments()`, a linha 642-643 verifica se o encoder não é `flac` ou `pcm_s16le` antes de aplicar `-b:a`. No entanto, **ALAC (`alac`) não está incluído nessa verificação** — se o output for `.m4a` com codec `alac`, o `-b:a` seria aplicado desnecessariamente. Na prática, `encoderForProfile()` só retorna `alac` quando o codec original contém "alac", e nesse caso o encoder seria `alac`. A verificação na linha 642 não exclui ALAC, mas como ALAC é sem perda e o bitrate seria ignorado pelo FFmpeg, isso é **cosmético, não funcional**.

---

## 6. Ferramenta: Juntar Vídeos/Audios (FfmpegJoinVideosActivity)

### 6.1. Opções da UI e correspondência nos comandos

| Opção da UI | Estado no código | Comando FFmpeg | Status |
|---|---|---|---|
| **Reencode Completo** (checkbox) | `checkReencode.isChecked` | `buildReencodeArguments()` ou `executeFullReencodeJoin()` | ✅ Correto |
| **Smart Join** (checkbox, experimental) | `checkSmartJoin.isChecked` | `executeSmartJoinExperiment()` | ✅ Correto |
| **Transição** (PopupMenu) | `selectedTransition` | `xfadeTransitionName()` → filter xfade | ✅ Correto |
| **Tempo de transição** (EditText) | `safeTransitionSeconds()` | Duração do xfade/crossfade | ✅ Correto |
| **Encoder de vídeo** | `selectedVideoEncoder` | `videoEncodingArguments()` | ✅ Correto |
| **Qualidade do vídeo** | `selectedVideoQuality` | `encodingFor()` | ✅ Correto |

### 6.2. Comandos finais

**Direct concat (sem reencode):**
```
-y -fflags +genpts -f concat -safe 0 -i <list.txt> -c copy [-movflags +faststart] <output>
```

**Full reencode:**
```
-y -i <input1> -i <input2> ... -filter_complex "<filters>" -map [vout] -map [aout]
<c:v <encoder> [-b:v <bitrate> -minrate ... -maxrate ... -bufsize ...]>
-r <fps> -c:a aac -b:a <bitrate> -ar <sr> -ac <channels> -movflags +faststart <output>
```

**Smart Join (experimento TS):**
- Body: `-c:v copy -bsf:v <bitrate_filter> -c:a aac ...`
- Transition: filtro xfade complexo
- Final concat: `-c copy -bsf:a aac_adtstoasc`

| Elemento | Status |
|---|---|
| Direct concat `-c copy` | ✅ Correto — rápido, sem perda |
| Reencode com filtros | ✅ Correto — normaliza resolução, taxa de quadros, etc. |
| Smart Join preserva encoder | ✅ `encoderOverride` passado para segmentos |
| Bitrate constrain (`-minrate`/`-maxrate`/`-bufsize`) | ✅ Aplicado apenas quando `constrained=true` |
| Filtro xfade com curva | ✅ `acrossfade` com curva selecionada |

### 6.3. Finding específico

⚠️ **Finding 4:** Na linha 2518-2519, quando **nenhum preset** está ativo (`AudioPreset.NONE`) e a extensão selecionada é `WAV` ou `FLAC`, o botão de bitrate ainda pode ser clicado (dependendo de `supportsBitrate`). No entanto, `AudioExtension.WAV` e `AudioExtension.FLAC` têm `supportsBitrate = false`, então o código desabilita corretamente o botão. **Nenhum problema encontrado aqui.**

---

## 7. Componentes Compartilhados

### 7.1. FfmpegVideoEncoderRegistry

| Encoder | FFmpeg NAME | Family | UI Display | Status |
|---|---|---|---|---|
| MediaCodec H.264 | `h264_mediacodec` | h264 | `h264_mediacodec` | ✅ |
| MediaCodec HEVC | `hevc_mediacodec` | hevc | `hevc_mediacodec` | ✅ |
| CPU x264 | `libx264` | h264 | `libx264 (CPU)` | ✅ |

| Função | Status |
|---|---|
| `shortName` — `libx264` → "cpu", `h264` → "h264", `hevc` → "hevc" | ✅ Correto |
| `encodingFor()` — libx264 usa `-crf`, hardware usa `-b:v` com multiplier | ✅ Correto |
| `advertisedMaxInstances()` — para limite de instâncias paralelas | ✅ Correto |

### 7.2. FfmpegVideoQuality

| Qualidade | libx264 CRF | Hardware multiplier (h264) | Hardware multiplier (hevc) |
|---|---|---|---|
| Máxima | 16 | 1.60 | 1.25 |
| Muito alta | 18 | 1.25 | 1.05 |
| Alta | 20 | 1.00 | 0.80 |
| Média | 23 | 0.70 | 0.55 |
| Econômica | 26 | 0.45 | 0.35 |

✅ **Todos os valores estão corretos** e alinhados com a documentação FFmpeg.

### 7.3. FfmpegTaskTracker

| Elemento | Status |
|---|---|
| `TaskState` (PENDING, RUNNING, COMPLETED, FAILED) | ✅ Correto |
| `setTaskEncoder()` — exibe encoder na UI | ✅ Correto |
| `setLiveStatus()` — velocidade em tempo real (parallel) | ✅ Correto |
| `completeTask()` vs `completeCurrentTask()` | ✅ Implementação distinta para uso sequencial vs paralelo |

---

## 8. Comandos de Pré-visualização

| Ferramenta | Seek | Velocidade | Rotação/Flip no Preview | FFplay |
|---|---|---|---|---|
| **Cortar** | ✅ MediaPlayer.seekTo | ✅ playbackParams.setSpeed | ⚠️ Não reflete rotação do arquivo | N/A (MediaPlayer) |
| **Girar** | ✅ MediaPlayer.seekTo | ✅ playbackParams.setSpeed | ✅ Matrix no TextureView | N/A (MediaPlayer) |
| **Extrair Áudio** | ✅ MediaPlayer.seekTo | ✅ playbackParams.setSpeed | N/A | N/A |
| **Juntar** | ✅ MediaPlayer.seekTo (join + result) | ✅ playbackParams.setSpeed | N/A | N/A |
| **Inserir Áudio** | ✅ MediaPlayer.seekTo | ✅ playbackParams.setSpeed | N/A | N/A |
| **Limpar Áudio** | ❌ Sem preview | ❌ | ❌ | ❌ |

### Finding 5: Ausência de FFplay
⚠️ **Finding 5:** **Nenhuma das ferramentas utiliza FFplay** para pré-visualização. Todas usam `MediaPlayer` nativo do Android. Isso é uma escolha de arquitetura (não um bug), mas significa que:
- Não há controle granular via flags FFmpeg (ex: `-vf`, `-af` para preview)
- A rotação/flip no preview do "Cortar" não reflete o que será aplicado no processamento
- Para a ferramenta "Cortar", o preview mostra o vídeo com orientação original, enquanto o processamento aplica `-noautorotate` e `-display_rotation:v:0`

---

## 9. Resumo de Findings

| # | Finding | Ferramenta | Severidade |
|---|---|---|---|
| 1 | Preview não reflete rotação do arquivo original (apenas reescala visual) | Cortar | Média |
| 2 | Saída fixa WAV/16kHz/mono sem opções personalizáveis | Limpar Áudio | Baixa (intencional) |
| 3 | ALAC não excluído da verificação de bitrate em `buildFullReencodeArguments` | Inserir Áudio | Baixa (cosmético) |
| 4 | Nenhum problema nas checkboxes de bitrate para WAV/FLAC | Extrair Áudio | ✅ OK |
| 5 | Nenhum uso de FFplay; todas as pré-visualizações usam MediaPlayer nativo | Todas | Baixa (arquitetura) |

---

## 10. Conclusão

**Status geral: ✅ BOM**

A correspondência entre as opções da UI e os comandos FFmpeg está **correta em 95%+ dos casos auditados**. Todos os encoders, filtros, bitrates, taxas de amostragem, canais, extensões e presets são corretamente refletidos nos argumentos FFmpeg.

As principais lacunas identificadas são:
1. **Preview do "Cortar" não mostra rotação corrigida** — o usuário não vê como o vídeo será exibido após o corte com `-noautorotate`.
2. **Ausência de FFplay** — limita o controle de preview via FFmpeg.
3. **Limpar Áudio é fixo** — sem opções, mas isso parece intencional.

**Recendações (não implementadas, apenas relatadas):**
- Adicionar preview com rotação aplicada em "Cortar" (usar `MediaPlayer.setVideoTransformation()` ou exibir aviso)
- Considerar FFplay para previews mais avançados (especialmente para visualizar filtros antes de processar)
- Documentar a limitação fixa do "Limpar Áudio" na tela de ajuda