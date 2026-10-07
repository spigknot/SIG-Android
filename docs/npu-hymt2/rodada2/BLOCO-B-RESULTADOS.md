# BLOCO B — RESULTADOS (percentual consolidado)

## B.1 Numericos no HTP0 (test-backend-ops)
| Run | Casos OK | Unsupported | FAIL |
|-----|---------:|------------:|-----:|
| RMS_NORM + SOFT_MAX | 135/135 | 130 | 0 |
| ROPE | 287/287 | 183 | 0 |
| SET_ROWS/GET_ROWS/ADD/MUL (f32/f16/q4_0) | 168/168 | 49 | 0 |
| GET_ROWS q6_K (filtro) | 0 | 8 | 0 |
| (R1) MUL_MAT q4_0 | 101/101 | - | 0 |
TOTAL R2: 590 casos PASS, 0 FAIL. q6_K GET_ROWS: 100% unsupported (explica
os 3 nos CPU no grafo do modelo Q4_0 - embedding).

## B.2/B.3 Reset A-B-A (reset_harness.cpp, mesmo processo, sem reload)
- P1 em 2 contextos (A,B): top8 ids iguais, maxdiff 0.000000, streams iguais.
- Reset em B (llama_memory_clear(mem,true)): pos_max 16 -> -1 (KV limpo).
- P2 pos-reset (B) vs referencia (A resetado): top8 iguais, streams iguais.
- A-B-A: P2 -> reset -> P2: streams identicos (idempotente).
- aceito: llama_sampler_sample faz accept interno (confirmado no source).
=> reset VALIDADO no HTP0 para KV/pos/token stream.

## B.4 API usada
llama_memory_clear(llama_get_memory(ctx), data=true) [llama.h:752].
