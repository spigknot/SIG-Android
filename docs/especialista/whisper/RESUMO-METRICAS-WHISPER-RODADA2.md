# Resumo de metricas — Whisper rodada 2 / lote final F (2026-10-09)

Contexto completo em `RELATORIO-WHISPER-RODADA2.txt` (12 secoes). Fonte numerica:
`D:/SIG-whisper-r1/build/whisper-rodada2/20261009-0149/fase-f/analysis.json`
(gerado por `analyze_batch_f.py` a partir dos JSONL de celula) + logs citados no
relatorio. Medicao = 2a inferencia do processo (call2), warmup 1, jni/threads=4
salvo indicacao; mediana em ms, salvo indicacao; C01=14,0 s / C02=96,7 s /
C03=393,9 s. Formatos: `inference_wall_ms` do harness. Device PJA110; lib R2-C4
(sha 8ba09192…); ggml-base; faixas de temperatura da bateria registradas em
`fase-f/device-state.jsonl` (35,5–38,2 °C na sessao).

## 1. Desempenho (configs validas)

| Config | C01 mediana ms | WER | nota |
|---|---|---|---|
| CPU FA-off (jni) | 5057.8 [4920.4–5146.8] | 0.0435 | n=5; encoder med 4312.8 |
| CPU FA-on (jni) | 4147.3 [4028.0–4172.7] | 0.0217 | n=5; enc med 3414.4; speedup 1.22x |
| CPU FA-on (30 repeticoes) | 4917.0 [4901.0–5656.1] | 0.0217 | 30/30 sha unico |
| OpenCL FA-off (jni) | 24588.4 | 0.0682 | EXCLUIDO (4,8–5,9x CPU; 1 warmup 24,4%) |

C02 (modo core, FA-on) — uma variavel por vez:
| threads (beam1) | 2: 48441.5 | 4: 24310.3 | 6: 29848.1 | 8: 23243.2 |
|---|---|---|---|---|
| ms | 48442 | 24310 | 29848 | 23243 |
| WER | 0.1792 | 0.2122 | 0.1440 | 0.1741 |

| beam (thr4) | beam1: 24310.3 ms | beam3: 43786.2 ms (WER 0.1510) | beam5: 52185.5 ms (WER 0.1548) |
|---|---|---|---|
| complemento thr8 | beam1: 23243.2 ms | beam3: 28664.3 ms | beam5: 39818.1 ms |

C03 (jni, FA-on, 1x): 101.8 s (RTF 0.258; enc 58.6 s; dec 20.1 s; WER 0.2107, CER 0.1056).

## 2. Estabilidade (F2)

| Bloco | Denominador | Falhas | Nota |
|---|---|---|---|
| 30 transcricoes C01 (CPU FA-on) | 30/30 ok | 0 | sha unico 428ca5654e80 (30/30); mediana 4917.0 ms |
| 20 ciclos load/release (jni) | 20/20 ok | 0 | loads 171.1–211.9 ms; teardown 31.6–41.4 ms |
| 10 ciclos cancel/resume | 10/10 + 10/10 | 0 | lat: during-infer 4–5 s; pre-infer 0.3–0.5 ms; load 155.9–156.0 ms |
| 5 ciclos fechar/reabrir (lab s2) | 5/5 PASS | 0 | stale_release rc_freed=false em todos |
| C03 longo 1x | 1/1 ok | 0 | 101.8 s (sem >1800 s) |

Nota honesta: zero falhas e triagem, nao prova de ausencia de intermitencia;
amostras pequenas (sem promessa de p95); deriva termica ~19% nos tempos
absolutos ao longo da sessao (pares A/B alternados neutralizam na comparacao).

## 3. Achado central de repetibilidade (F-01)

Mesmo audio/processo, chamadas seguidas => textos diferentes (deterministico
por indice; JNI==core==threads=1==threads=4):
call1 7f666ee4 (266 B; WER FA-off 0.0435) -> call2 66408b73 (271 B; 0.0435) ->
call3 7901a742 (269 B; 0.0000) -> call4 7613324d (268 B; 0.0217). FA-on: 6,52%
-> 2,17% -> 12,50% -> 0%. Evidencia: fase-f/f1/f1ax-live.jsonl,
f1ax2-live.jsonl. Mecanismo nao isolado (hipoteses descartadas no relatorio).

## 4. Controles da rodada (A3, jni CPU FA-off; confronto)

| Lib | C01 WER | C02 WER | Nota |
|---|---|---|---|
| B0/B1/C2 | 0,0435 (x3) | 0,2782 + 1 byte 0xB3 invalido (x3) | transcripts byte-identicos entre libs; sha C01 66408b73 = call2 do R2-C4 |

## 5. Gates (2026-10-09; logs em fase-f/f3/)

- `:app:testDebugUnitTest` EXIT=0 — **630 testes, 0 falhas, 4 skips** (skips =
  NativeDepsOfficialFixturesTest, exigem ZIPs oficiais). SmartInsertNativeTest
  PASSA com ffmpeg 8.0.1 gyan (referencia do repo) primeiro no PATH; daemon
  reiniciado. Nenhum teste alterado.
- `:app:lintDebug` EXIT=0 (1116 findings informativos); `:app:assembleDebug` EXIT=0.
- `check-module-map.ps1 -Quiet` EXIT=0; `git diff --check` EXIT=0 (so warnings
  LF->CRLF); `git diff --cached --check` EXIT=0.
- Pester runner **54/54** EXIT=0; validate-agent-harness EXIT=0.
- wav_io host 35/35 + device 35/35 EXIT=0; fa host 5/5 + device 5/5 EXIT=0;
  resolvedor host 18/18 EXIT=0; mutf8 sanitize 128/0 (fase C).
- Snapshot final: final-diff.patch sha256 81d2c7474d3b31a188dc2c022c4da9e7fe8c6f10d1bf390830a51a14bf3f95dd; untracked=55; manifesto
  119/119 entradas com sha256.

## 6. Números-chave

- **FA CPU acelera 1,22x** (mediana 4 147 ms vs 5 058 ms) e melhora o WER de
  C01 (2,17% vs 4,35%). Melhor perfil valido = CPU FA-on (jni/4 threads/beam1).
- Vulkan: EXCLUIDO (W13 — texto corrompido mesmo FA-off); FA GPU nao entrega
  aceleracao (recusa limpa + fallback). OpenCL: 4,8–5,9x mais lento + WER pior.
- R2-C4: lib aceitavel para nova rodada de experimentos, NAO para integracao
  ainda (pendencias: F-01, FA GPU, silencio/semantica, enc[0], prova GPU).
