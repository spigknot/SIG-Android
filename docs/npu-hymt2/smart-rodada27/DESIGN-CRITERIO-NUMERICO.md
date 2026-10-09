# DESIGN-CRITERIO-NUMERICO.md — contrato de avaliação numérica dos kernels (matmul)

Contexto: campanha Smart dual-backend / port K-quant (cópia isolada `kq2`/`sigcand2`).
Aplicação: **somente** matmul do backend HTP no probe/cohort autorizado. NÃO altera o
NUMGATE do handoff/logits nem libera qualidade de transcrição/modelo.

## A. Critério LEGADO (CONGELADO — falhas históricas preservadas)

- Regra: `|htp - ref| <= ABS_TOL + REL_TOL * |ref|` com `ABS_TOL = 2e-2`, `REL_TOL = 5e-2`.
- Todas as falhas registradas sob este critério **permanecem como falhas** nos
  relatórios históricos (B128: q4_0=4, q8_0=6, q4_K=10, q6_K=9 violadores; F32=0).
- Não há reclassificação retroativa. O critério só muda de CONTEXTO de uso
  (diagnóstico), nunca reescreve resultado.

## B. Critério PIN (do upstream pinado `1ec818809…` — teste oficial do backend)

- Fonte: `tests/test-backend-ops.cpp` (pin exato) — `nmse`: `soma((a-b)^2)/soma(a^2)`,
  usando a referência como `a`; `test_mul_mat::max_nmse_err() = 5e-4`.
- Implementado NO PROBE como `nmse_pin` (o valor REAL do device, sem arredondar):
  - Q4_K B1: `nmse_pin=2.536685e-08` (PASS; margem ~20000x);
  - Q4_K B2: `nmse_pin=1.187797e-07` (PASS);
  - Q4_K B128: `nmse_pin=1.337495e-07` (PASS; margem ~3700x);
  - (nrmse^2 == nmse_pin confere: 0.00037^2 = 1.369e-7 ≈ 1.337e-7 — a diferença é
    só o arredondamento do log do nrmse; o valor exato é o `nmse_pin`.)
- Implementação OBRIGATÓRIA de guardas: `finite` em todos os elementos, cobertura
  completa (`nd == N*B`), denominador (`soma(a^2)`) > 0 e referência explícita
  (CPU backend no mesmo processo/config!). Cobertura parcial ou denominador zero ⇒
  resultado INVÁLIDO (não PASS).

## C. Diagnósticos (preservados, não apagados)

- `max_abs`, violadores (MMVIOL com b/n/htp/ref/abs/lim/exc), `first_viol`,
  `first_nonfinite` e os CSV por tentativa continuam sendo coletados e reportados.
- São ferramentas para LOCALIZAR diferenças; não são o gate de aceitação.

## D. Aceite global (matmul) — exige MAIS que o NMSE

Um `PASS` de NMSE **não basta** para aceitar um kernel/caminho:
1. **Contratos estruturais**: dispatch real do upload (`set_tensor` → repack!),
   `get_alloc_size` (tiled ≥ canônico!), descriptor (`h.nb[1]`/`h.size`!), bounds,
   offsets, `usage`, cobertura por tipo (Q4_K E Q6_K!) e mutantes que DEVEM falhar
   (dispatch→memcpy RED; alloc→canônico RED; offset/parcial RED; ALLOC_FAIL ⇒
   sem upload/compute!).
2. **Fixtures**: dados conhecidos (one-hot/sinais/layout/scales) + perturbações
   localizadas — o gate deve detectar mutantes de layout/escala/NaN.
3. **Estabilidade**: resultado reproduzível (multi-run!) e limite causal aberto
   documentado (ex.: correlação de build/ternário — OPEN; não é fix aceito!).

## E. Limites assumidos (honestidade)

- NMSE é uma métrica GLOBAL — pode diluir um erro localizado; por isso as fixtures
  e os diagnósticos (C) são obrigatórios em conjunto.
- O critério acima vale para **matmul**; os contratos de KV/handoff/logits têm gates
  próprios (NUMGATE) e NÃO são alterados por este documento.
- Dados de referência (Rcpu/Rscalar/Rtiled) e a decomposição fina de erro
  (quantização de ativação vs acumulação vs cast final) permanecem como análise
  pendente registrada na R27.
