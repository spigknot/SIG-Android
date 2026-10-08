# DESIGN — BACKPORT FOCAL Q4_K/Q6_K (rodada 13)

## 1. ACHADO ARQUEOLOGICO (historia real, nao HEAD flutuante)
- Commit upstream: **1ec818809** "hexagon: Support for K-Quants Q4_K and Q6_K
  (#28994)" — autor Marco Colombo (co-author Qualcomm), 2026-09-16.
- Stat: 7 arquivos, 818 insercoes / 61 delecoes (~880 linhas) — TODOS em
  ggml/src/ggml-hexagon/ (core intocado!).
- Relacionados: 4de092659 (q5_k #29123), a868c3e3c (q2/q3_k #29717),
  07fc586e3 (dynamic quantizer), 0c3626ec0 (buffer/DMA 64-bit),
  8345f3339 (scalability #29974). FORA DO ESCOPO desta fase (so Q4_K/Q6_K).
- O SIG-hexagon NAO e' parent limpo (diff ~11k linhas vs 1ec818809~1):
  git apply rejeita os 7 arquivos => port ADAPTADO, nao cherry-pick.
  Patch extraido: /root/kquant-28994.patch (1444 linhas).

## 2. MAPA DOS HUNKS (pacote focal minimo)
HOST ggml-hexagon.cpp (24 hunks):
- +1 include; flags tipo K-quant (+14);
- **repackers q4_k/q6_k NOVOS (+377) — o maior bloco (host prepara pesos!)**;
- cases em repack_tensor_tiled (+4/+4); buffer_get_tensor[_2d] (+6 x4);
- opbatch (2x1); precompute hmx_mm/hvx_mm/fused_mmnx (6 hunks, pequenos);
- **supported_mul_mat / supported_mul_mat_id: +2/+2 (os cases!)**;
- is_supported_mul_mat_nx_kernel (2), is_mergeable (1), init (+4).
DSP:
- htp-ops.h: **+2 (enum htp_data_type — O OPCODE do protocolo host<->skel)**;
- hvx-mm-kernels-flat.h: **+143 (flat_vec_dot q4_k/q6_k — kernel NOVO)**;
- hvx-mm-kernels-tiled.h: **+74 (accum q4_k) + +63 (tiled_vec_dot k)**;
- hmx-mm-kernels-tiled.h: **+41 (dequantize_tiled_weight_to_fp16_task k)**
  + ajustes de transfer (3 hunks);
- matmul-ops.c/.h: dispatch/params (+84/-61 / +29) — familia compacta.

## 3. CONTRATOS RAIZ (os que mandam no custo)
1. **Repack/layout K-quant no host** (377): os blocos K (super-blocks com
   scales/mins 6-bit!) — formato de repack "tiled" proprio; sem isso os
   kernels nao recebem layout valido.
2. **Kernels DSP (HVX flat/tiled + HMX dequant)**: ~320 linhas de codigo
   VETORIZADO (HVX intrinsics) — portavel em bloco, mas exige revisar
   VTCM/dimensoes/alinhamento (Q6_K 210 bytes/256 blocos etc).
3. **Protocolo host<->skel**: +2 opcodes em htp-ops.h => o SKEL (htp-v81
   build!) recompila; versao de protocolo/skel sobe JUNTO (cohort!).
4. **Dispatch/params** (matmul-ops + precompute no host): selecao de kernel
   por tipo/shape; rejeicao honesta p/ tipos fora do escopo (Q5_K/Q2/Q3).

## 4. ALLOWLIST / DENYLIST
Allowlist: os 7 arquivos do commit (adaptados ao SIG). Denylist: core SIG,
vulkan/shaders/adreno, OpenCL loader/F3b/guard, reset, hooks, Whisper,
defaults/UI. Build: cohort completo (host+skel+bindings+APK) em copia
isolada; backup do tree; nada de swap parcial.

## 5. CUSTO/ESTRATEGIA
- NAO e' "add case": repack 377 + kernels 320 + dispatch ~110 + guards e
  protocolo — trabalho de port ADAPTADO (o SIG diverge do parent em ~11k
  linhas do host!).
- Estrategia recomendada: (1) ports mecanicos primeiro (opcode/protocolo,
  flags, guards supported_*); (2) kernels em bloco (self-contained); (3)
  repackers (dependentes do layout do SIG — adaptar com cuidado); (4)
  numerica por shape ANTES do grafo real.
- Alternativa de escopo: apenas Q4_K (metade do c4bf = matrizes de maior
  volume? — c4bf usa K4 para attn/ffn e K6 em alguns tensores) — a ordem
  pede os DOIS; cortar exigiria justificativa.
- Risco principal: numerica dos super-blocks (scales/mins) — gate CPU-vs-
  HTP por shape com tolerancia definida no DESIGN-NUMERICO.

## 6. ESTADO
Protótipo NAO construido nesta rodada (escopo medido e mapeado; a decisao
de executar o port adaptado — esforco substancial — fica com o usuario/
especialista conforme a ordem: "apresentar escopo concreto"). Evita-se
"10 mil linhas entao impossivel": o pacote REAL e' ~880 linhas em 7
arquivos, com os 3 contratos acima fechados.
