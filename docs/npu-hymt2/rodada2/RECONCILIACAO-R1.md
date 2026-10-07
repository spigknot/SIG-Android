# RECONCILIACAO-R1.md — contagens e escopo REAL (auditoria R2)

## Contagem de execucoes R1 (auditada)
O relatorio R1 citou "5 execucoes de harness". Contagem real (dos artefatos):

| # | Execucao | Resultado |
|---|----------|-----------|
| 1 | llama-cli --list-devices (T1) | OK (HTP0+GPUOpenCL listados) |
| 2 | test-backend-ops support SEM ADSP_LIBRARY_PATH | FALHA 0x80000406 (crash) |
| 3 | test-backend-ops support COM ADSP (matriz) | OK (21454 linhas) |
| 4 | test-backend-ops test MUL_MAT q4_0 | OK 101/101 |
| 5 | llama-cli geracao (t2) | OK 25,2 t/s |
| 6 | llama-cli geracao (log simples) | OK 24,2 t/s |
| 7 | llama-cli geracao -v (log 9,8 MB) | OK 18-26 t/s |
| 8 | llama-bench -dev CPU -ngl 0 (sintaxe invalida) | FALHA operacional (help) |
| 9 | llama-bench HTP0 | OK pp1015,7/tg26,9 |
| 10 | llama-bench CPU (sem -dev) | OK pp373,3/tg47,3 |
| 11 | llama-bench GPUOpenCL | OK pp329,0/tg20,1 |

=> 11 execucoes (9 validas + 2 falhas; 1 falha TECNICA real: env ADSP ausente).
Correcao do texto R1: "5 execucoes" subestimou; "3x gen + 3x bench" = na verdade
3 geracoes + 4 benches (1 invalido) + 2 test-backend-ops + 1 list = 11.

## "Zero fallback" — CORRECAO (auditoria R2)
R1 afirmou "zero fallback oculto". ERRO PARCIAL: o grafo tem **splits = 2**
(1129 nos): 3 nos rodam na **CPU** —
  - node#0 GET_ROWS (embd), node#1 GET_ROWS (node_1), node#2 DUP
Causa: `GET_ROWS` sobre `token_embd.weight` em **Q6_K** (1 tensor q6_K do GGUF)
nao e suportado no HTP (matriz R1: GET_ROWS suportado so p/ q4_0/f32/f16).
O restante (~1126 nos, incluindo FLASH_ATTN e o MUL_MAT final do output) roda
no HTP0. Nada de "fallback oculto" adicional foi encontrado nos 3 dumps.

## Escopo dos 101 PASS
`MUL_MAT type_a=q4_0` (101 casos, f32/f16 bias): comprova kernels MUL_MAT q4_0.
NAO cobre: RMS_NORM/ROPE/SOFT_MAX/SET_ROWS/GET_ROWS/ADD/MUL no runtime do modelo
(apenas a matriz `support`). Compensacao na R2: Bloco B roda numericos adicionais.

## Numeros R1 referem-se a
Q4_0 local (sha256 415e7300...) no build upstream 5e03bdd — NAO ao Q4_K_M do
app (Vulkan). Nao comparavel como "ganho de backend" do produto.
