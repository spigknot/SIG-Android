# Relatório — Durações de saída das ferramentas FFmpeg (SIG Android)

Data: 2026-09-03 · Build: 58680ba (debug) · Dispositivo: emulador Pixel 9 (Android 15, x86_64)
Método: execução real de cada fluxo no app + medição dos arquivos de saída do cache com ffprobe (host).

---

## 1. Fórmulas esperadas (extraídas do código)

| Ferramenta | Cenário | Fórmula da duração da saída |
|---|---|---|
| **Juntar** | Concat direto (sem reencode) | Σ durações (exata) |
| **Juntar** | Reencode com transição xfade (Dissolver etc.), N clipes | Σ durações − transição × (N−1) |
| **Juntar** | Reencode "Fade in/out" | Σ durações (fades são aplicados nas bordas, sem sobreposição de conteúdo — `FfmpegMediaPolicies.videoJoinFilterComplex` + `SmartJoinPlanner.expectedDurationSeconds`) |
| **Juntar** | SmartJoin com transição xfade | Σ durações − transição × (N−1) (mesma regra; o app valida com `expectedDurationSeconds` e aborta se divergir) |
| **Cortar** | De A até B | B − A (conteúdo), quando o fluxo recodifica |
| **Girar** | Qualquer ângulo | Duração da origem (rotação não altera tempo) |
| **Extrair áudio** | Seleção cheia | Duração da origem; bytes (PCM) = taxa × canais × 2 bytes × duração + header |
| **Limpar áudio** | Equilibrado / Forte | Duração da origem (filtros não alteram tempo); bytes (PCM) idem |
| **Inserir áudio** | B em A | A + B (`compositeDurationMs = main + inserted`; concat) — com crossfade: A + B − fades aplicados |

**Exemplo do usuário**: 2 vídeos de 30 s + transição de 3 s → **57 s** (60 − 3) para xfade/Dissolver e SmartJoin; **60 s** para "Fade in/out" e para concat sem transição.

## 2. Resultados medidos vs. esperados

Mídia de teste: 0_durA/0_durB (30,000 s, h264 640×360 30 fps + AAC 44,1 kHz), sigt_clipA_30s (30 s), d30a.mp4 (30 s, 320×240), d30.wav (30 s PCM mono 44,1 kHz), d10.mp3 (10 s).

| # | Cenário (executado no app) | Esperado | Medido | Δ | Status |
|---|---|---|---|---|---|
| 1 | Juntar — concat direto, 2×30 s | 60,000 s | 60,023 s | +0,023 s | ✅ |
| 2 | Juntar — reencode **Dissolver 3 s**, 2×30 s | 57,000 s | 57,056 s | +0,056 s | ✅ |
| 3 | Juntar — reencode **Fade in/out 3 s**, 2×30 s | 60,000 s | 60,024 s | +0,024 s | ✅ |
| 4 | Juntar — **SmartJoin Dissolver 3 s**, 2×30 s | 57,000 s | 57,260 s | +0,260 s | ✅ (tolerância pequena) |
| 5 | Cortar 5 s → 15 s (reencode libx264) — **vídeo** | 10,000 s | 10,000 s (start 5,023 s) | 0 | ✅ (conteúdo) |
| 5b | Cortar 5 s → 15 s — **áudio / container** | 10,000 s | **15,023 s** (áudio 0→15,02) | +5,023 s | ❌ **FINDING 1** |
| 6 | Girar 90° (libx264), 30 s | 30,000 s | 30,033 s | +0,033 s | ✅ |
| 7 | Extrair áudio (d30a → WAV 48 kHz estéreo), 30 s | 30,000 s · 5 760 046 B | 30,000 s · **5 760 078 B** | 0 · +32 B | ✅ (PCM exato) |
| 8 | Limpar áudio forte (d30.wav → WAV 44,1 kHz mono), 30 s | 30,000 s · 2 646 046 B | 30,000 s · **2 646 078 B** | 0 · +32 B | ✅ (PCM exato) |
| 9 | Inserir áudio (d30.wav 30 s + d10.mp3 10 s, sem transição) | 40,000 s | 40,000 s · 3 528 078 B | 0 | ✅ (PCM exato) |

Arquivos de saída medidos (cópia local): `C:/Users/Gustavo/AppData/Local/Temp/dur/out/` (join_*, rotate_*, extract_audio_*, clean_*, insert_*).

## 3. Findings

### FINDING 1 (❌ severidade média) — Cortar com início em t>0: o áudio não é cortado e o container reporta a duração até o fim do trecho
- Cenário: cortar 5→15 s com reencode (libx264) em vídeo com áudio. Comando real do app (logcat):
  `ffmpeg -y -noautorotate -ss 5.000 -i input.mp4 -t 10.000 ... -c copy -c:t copy -c:v libx264 ... output.mkv` → remux para mp4.
- Resultado: **vídeo** = 10,000 s (start_time 5,023 s) — conteúdo correto; **áudio** = 15,023 s com start 0 — o trecho antes dos 5 s NÃO foi removido do áudio; container reporta 15,023 s.
- Causa raiz (reproduzida no host com o comando exato): a combinação `-ss` **antes** de `-i` + `-c copy` (áudio copiado) + `-c:v <encoder>` (vídeo recodificado) não reajusta a timeline — o ffmpeg mantém os pts originais do vídeo (5→15) e o áudio entra do início; `-avoid_negative_ts make_zero` não resolve pts positivos.
- Reprodução host: `ffmpeg -ss 5 -i d30a.mp4 -t 10 -c copy -c:v libx264 ...` → mkv com vídeo 15,023 s. Sem o `-c copy` (áudio recodificado), o mesmo comando produz 10,003 s corretos.
- Impacto: players que respeitam edit list mostram ~10 s; players/metadados que usam a duração bruta mostram 15 s; o áudio traz conteúdo do início (erro audível de conteúdo). Ferramenta de CORTE deve entregar exatamente o trecho.
- Correção sugerida: no fluxo de reencode com `-ss`, recodificar também o áudio (remover o `-c copy` do áudio) ou usar `-ss` como output option (`-i input -ss ...` com reencode zera os pts); validar o resultado com ffprobe em teste automatizado (vacina de duração).

### FINDING 2 (⚠️ severidade alta, validação interna do SmartJoin) — SmartJoin aborta com clipes válidos quando o concat "estoura" a duração esperada
- Ao executar SmartJoin + Dissolver 3 s com o par sigt_clipA_30s (30 s) + sigt_clipA_30s (mesmo arquivo 2×), o app abortou:
  `SmartJoin failed without full-reencode fallback: Duração inesperada: 65.431s; esperado 57.000s.`
- O MESMO par, com reencode regular (Dissolver 3 s), produziu 57,056 s sem problema (cenário 2). O par 0_durA + 0_durB (arquivos distintos) funcionou no SmartJoin (57,260 s, cenário 4).
- Leitura: em alguns pares de arquivos o concat de corpos/emendas do SmartJoin entrega duração ≠ prevista (65,43 − 57,00 = +8,43 s sugere sobreposição/duplicação de segmento) e o app NÃO tem fallback — a operação falha com mensagem técnica. O usuário fica sem o arquivo.
- Recomendação: investigar o caso de clipes com o mesmo GOP/duração (ou conteúdo idêntico) no montador de segmentos; considerar fallback para reencode completo quando a validação de duração falhar, com aviso claro.

### FINDING 3 (⚠️ severidade baixa/média) — `CalledFromWrongThreadException` no Cortar (linha 596)
- Ao exigir "recodificação completa" (corte começando fora de keyframe), o logcat registrou:
  `ViewRootImpl$CalledFromWrongThreadException: Only the original thread that created a view hierarchy can touch its views. Expected: main Calling: Thread-4 — FfmpegCutActivity.cutSelectedMedia$lambda$49 (FfmpegCutActivity.kt:596)`.
- O diálogo aparece (o fluxo sobrevive nesta versão do Android), mas é uma violação de threading que pode quebrar em versões futuras ou em aparelhos lentos (UI tocada de `Thread`).
- Correção sugerida: envolver o `showFullReencodeConfirmation` em `runOnUiThread` (ou mover a decisão do diálogo para antes da thread de corte).

### FINDING 4 (ℹ️ ambiente) — h264_mediacodec travou no emulador durante o Cortar
- O encoder `h264_mediacodec` configurou (c2.android.avc.encoder) e não produziu frames (deadlock) no emulador; com `libx264 (CPU)` o mesmo corte concluiu. O MediaCodec funcionou em outras execuções no mesmo dia (SmartJoin). Nota de ambiente: ao testar no emulador, preferir libx264; em aparelho real o MediaCodec é o caminho normal.

### Nota comportamental (esperado, não é bug)
- "Fade in/out" no Juntar NÃO subtrai a transição da duração total (60 s em 2×30 s): os fades são aplicados no fim do clipe anterior e início do seguinte **sem sobreposição de conteúdo** — diferente do Dissolver (xfade com sobreposição, 57 s). O texto da UI "Transição: Fade in/out 3 s" pode sugerir sobreposição, mas o resultado é o documentado no código.
- SmartJoin adiciona ~0,26 s sobre o valor teórico (57,260 vs 57,000): caudas de TS/concat com `+genpts`. Dentro de tolerância de players, mas registrado.

## 4. Conclusão

As fórmulas de duração do Juntar (com e sem transição, reencode e SmartJoin), Girar, Extrair, Limpar e Inserir **batem com o esperado** dentro de ±0,3 s (e exatamente no PCM). O caso do usuário — 2 vídeos de 30 s com transição de 3 s → **57 s** para Dissolver/SmartJoin, **60 s** para Fade in/out ou sem transição — foi confirmado em execução real.

Dois problemas reais precisam de correção: **(1)** o Cortar com início em t>0 entrega áudio não cortado e duração de container errada (FINDING 1); **(2)** o SmartJoin aborta sem fallback quando a validação interna de duração detecta divergência (FINDING 2), que também expõe um caso em que o concat do SmartJoin produz duração errada. Mais o problema de threading do diálogo do Cortar (FINDING 3).
