# CONTRATO DO HANDOFF SMART (prefill NPU/HTP -> geracao GPU) — Fase 4

Status: INVESTIGACAO CONCLUIDA (auditoria de codigo no fork SIG); prototipo a
implementar na F5. Base: app/src/main/cpp/llama (fork) + build
build-probe do servidor (GGML_HEXAGON=ON). Regra das ordens: NAO presumir —
cada afirmacao abaixo vem de leitura de codigo com arquivo/linha.

## 1. MECANISMO ESCOLHIDO (e o descartado)

### Estrategia A (ESCOLHIDA): dois contextos + export/import de estado
- APIS: `llama_state_get_size` / `llama_state_get_data` (export) e
  `llama_state_set_data` (import) — llama.h:802+; implementacao em
  llama-context.cpp:4053-4068 delegando para `ctx->state_write_data` /
  `state_read_data` (L2971-2999).
- TRANSPORTE: a serializacao usa `llama_io_write_host` (export) e
  `llama_io_read_host` (import) que copiam os tensores do KV via
  `ggml_backend_tensor_get/set` (llama-context.cpp:2603/2655) — ou seja, e'
  um FORMATO HOST agnostico de backend. **O handoff HTP -> Vulkan (e -> OpenCL)
  MOSTROU-SE transportavel no ESCOPO TESTADO (rodadas 1-4)**: Hy-MT2-1.8B
  c4bf, KV f16, n_ctx 2048, FA desligado nos dois lados, build do FONTE SIG
  (v12; workarounds Adreno 0x5143 — ver rodadas 3/4), device CPH2747
  (Android 15/API35), app-uid de prova. NAO e' afirmacao universal:
  (a) o binario do backend PRECISA ser o do fork SIG (o upstream degenerava —
  causa demonstrada na rodada 3); (b) o prototipo linka em API28 enquanto a
  lib de produto segue minSDK24; (c) o loader OpenCL do produto e o
  empacotamento nativo sao SEPARADOS deste probe e nao foram alterados.
  Revalidar (matriz puro/src:dst) antes de portar para outra combinacao de
  backend/modelo/quantizacao.
- O que o estado contem (L3197-3215): (1) `arch` do modelo (string);
  (2) `memory->state_write(io)` = o KV (metadados de cells/posicoes/seq_ids
  + os tensores K/V da memoria).
- O QUE O ESTADO NAO CONTEM: logits. A ponte de token e' obrigatoria (secao 3).

### Estrategia B (DESCARTADA com evidencia): contexto unico, roteamento por fase
- Nao existe API de "trocar o backend de computacao por fase" em um contexto;
  o backend de cada tensor e' fixado na alocacao/buffer type na carga do
  modelo (o decode usa os tensores onde eles ESTAO). Roteamento por fase
  exigiria patch profundo no grafo/scheduler do ggml — fora do escopo
  "minimo e reversivel" das ordens. Registrado como nao-viabilidade atual,
  nao como impossibilidade eterna.

## 2. SEQUENCIA OPERACIONAL (por pedido)

```
[1] ctx_htp (model carregado com devices={HTP0,NULL}; n_ctx=N; KV dtype D)
      -> prefill do prompt (llama_decode, logits do ultimo token habilitados)
[2] L = llama_get_logits(ctx_htp); sampler -> TOKEN1 (contrato do 1o token)
[3] SZ = llama_state_get_size(ctx_htp); buffer host; llama_state_get_data
      (KV do prompt: posicoes 0..n-1; ~100-200 MB típicos p/ n_ctx 4096 f16)
[4] ctx_gpu (model carregado com devices={Vulkan0,NULL} [ou OpenCL]; MESMO
      modelo/quant/n_ctx/dtype) -> llama_state_set_data(buffer)
[5] assert: llama_memory_seq_pos_max(ctx_gpu, 0) == n-1  (continuidade)
[6] decode(TOKEN1) no ctx_gpu (posicao n) -> geracao NA GPU (sampler de
      produto com seed/config registrados)
[7] proximo pedido: llama_memory_clear (ambos) — reset A-B-A
```

## 3. CONTRATO DO PRIMEIRO TOKEN (ponte minima)

- O token inicial da fase GPU e' o resultado do sampler do ULTIMO logits do
  prefill de origem (SAMPLER CHAMADO UMA VEZ SO — proibido dupla chamada
  accept/por sampler ja ter amostrado; usar greedy no diagnostico).
- O token entra no destino como QUALQUER token da sequencia (decode pos n) —
  nao ha' "reprocessamento" do prompt: zero replay por construcao.
- Validacao de corretude (F5): run Smart vs run PURA no destino (mesmo
  prompt/sampler seed) -> sequencias de tokens comparadas (tolerancia
  float documentada; greedy deve coincidir).
- Reprocessamento pequeno EXCEPCIONAL (se alguma validacao futura exigir)
  deve ser explicitado, contado em tokens e testado — nunca silencioso.

## 4. INVARIANTES OBRIGATORIAS (validacao NATIVA e' minima!)

ACHADO DA AUDITORIA: o read nativo valida apenas `arch` (llama-context.cpp:
3200-3208; o proprio upstream marca "TODO: add more model-specific info
which should prevent loading the session file if not identical"). Portanto o
harness DEVE impor e verificar por FORA:
- mesma build do llama.cpp (mesmo binario/fork; skew entre lados nao e' detectado);
- mesmo modelo e quantizacao (c4bf fixado por SHA nesta campanha);
- mesmo n_ctx, n_batch/ubatch relevantes, type_k/type_v do KV;
- mesma config de flash-attention entre origem e destino (mudar FA pode
  alterar o layout/uso do KV; documentar e MANTER IGUAL na v1);
- posicoes/seq_ids: 1 sequencia (seq 0), posicoes contiguas 0..n-1.
Qualquer violacao = estado corrompido silencioso (nao ha' checksum nativo).

## 5. CUSTOS ESPERADOS (a medir na F6)

- MEMORIA: 2 modelos residentes (pesos no DSP do HTP ~1 GB + pesos no
  Vulkan/GPU ~1,2 GB) + 2 KVs (~100-200 MB cada p/ n_ctx 4096) — risco R3
  do design (LMK!); medir pico antes de qualquer promocao.
- TRANSFERENCIA: export HTP->host + import host->GPU do KV (dezenas a
  centenas de MB; tempo a medir — entra no TTFT!).
- LOAD: 2 cargas de modelo (~2,8 s cada no P4 — o cold do Smart soma).
- Ganho esperado: prefill no HTP (MEDIDO 184,5 tok/s in-app no P4) vs
  GPU (25-60 tok/s) — o trade e' o custo de transferencia+2o load.

## 6. PLANO DO PROTOTIPO (F5, alvo separado)

- Modo novo no probe (app-id separado; wrapper fail-closed): runSmart(prompt,
  max_tokens) com os passos da secao 2; logs por fase (prefill_htp, export_ms,
  import_ms, ttft, decode_gpu_ms, memoria quando observavel).
- Rotas: runSmart("vulkan") e runSmart("opencl") SEPARADOS (uma nao habilita
  a outra; falha de uma rota nao contamina a outra).
- Testes: A-B-A no mesmo processo; falha de sessao/contexto; destino
  indisponivel -> erro explicito (nunca fallback silencioso para CPU/NPU
  inteira mantendo rotulo Smart); cancelamento; limite de contexto.
- Evidencia exigida: contadores de posicao por fase + ausencia de replay +
  comparacao com runs puras (secao 3).

## 7. ORDEM DE IMPLEMENTACAO

1. Smart (Vulkan): destino = default atual do produto; validar primeiro.
2. Smart (OpenCL): reuso da mesma maquinaria; validar depois.
3. NPU-inteira: aceitacao propria (ja em uso no P4; falta a fase de produto).

## 8. REFERENCIAS (arquivo:linha do fork, lido em 07/10)

- llama.h:802-827 (state get/set/seq), 570-793 (llama_memory_*).
- llama-context.cpp:4053-4068 (entrypoints), 2971-2999 (state_*),
  2595-2690 (llama_io_write/read_host — tensor_get/set em 2603/2655),
  3197-3218 (state_write_data: arch + memory->state_write).
- Design geral: docs/npu-hymt2/DESIGN-SMART-DUAL-BACKEND.md (fases/riscos).
