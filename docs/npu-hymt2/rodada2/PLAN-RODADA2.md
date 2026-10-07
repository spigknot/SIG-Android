# PLAN-RODADA2.md — Campanha NPU/Hexagon (Hy-MT2 1.8B) — 06/10/2026

> Reabertura da investigacao isolada (decisao R1 "A/ENCERRAR" revertida pelo pedido
> do usuario). NAO integrar ao SIG; NAO tocar APK/libs/pacotes/defaults/commit/push/R2.
> Uso autorizado: sandbox device, C:/llama-npu, servidor (container/clone existentes).

## Regras de operacao

- Serial explicito em TODO comando adb: `3B15BD00FVE00000` (USB; wireless 41257 e o
  MESMO aparelho — nao usar em paralelo). PJA110 NUNCA tocar.
- Child-owned timeout/exit-capture em cada poll ADB; watchdog geral; sem matar
  processos de terceiros; sem root/SELinux/governor/thermal hacks.
- Fail-closed: preflight checa skels/env/paths/model-hash/device e ABORTA antes de
  qualquer execucao; erro 0x80000406 vira diagnostico, nao crash.
- C:/llama-npu e docs/npu-hymt2 sao PROTEGIDOS; D: tem so 31G -> logs/binarios em C:.
- Telefone pode cair (interferencia) -> nao insistir; retomar quando voltar.
- Cada bateria: JSON linha-por-tentativa (append) + stats Python (count/dedupe/
  mediana/media/dispersao) + checkpoint de bloco.

## Blocos e criterios (ordem)

### A. Precheck + validacao R1  [EM CURSO]
- [x] Skills carregadas (llama-cpp, sig-android-dev, context-efficiency, windows-hermes-tooling)
- [x] Refs upstream no PIN lidas (README/build.py/run.py --help real)
- [x] Fresh ADB serial (USB) + device/sandbox/app-state
- [x] SHA-256 baseline dos arquivos do sandbox + modelo (device+PC)
- [x] Reconciliar contagem R1 ("5 execucoes" vs support+test+3gen+3bench+OCL)
- [x] Suporte matrix filtrada (tipos/ops/shapes HyMT2)
- [x] Preflight fail-closed executavel + 1 probe RT pass
- Saida: baseline JSON (hashes/env/proof-scopes) + probe OK

### B. Corretude, reset e modos reais
- [ ] NumOps HTP: RMS_NORM/ROPE/SOFT_MAX/SET_ROWS/GET_ROWS/ADD/MUL/MUL_MAT
      shapes HyMT2 (prefill N64/256 e decode N1), tipos GGUF reais; ref CPU
- [ ] Reset A-B-A no MESMO contexto (sem reload): llama_memory_clear API atual;
      KV pos before/after; A(new context) vs B(reset) comparados
- [ ] Q6_K embd + tensores unsupported -> fallback declarado (contadores)
- Saida: reset PASS/FAIL verificado + escopo de fallback real por grafo

### C. Workloads representativos
- [ ] Corpus EN->PT do app (409/952/2335/4924 chars) — copia p/ docs/npu-hymt2/rodada2/
- [ ] pp64/256/512/1024/2048/4096 + tg128/256 (densidade long-context degradada)
- [ ] CPU/HTP/OCL mesmo modelo upstream; TTFT/total/kT/s; n=3 onde variancia importa
- [ ] Break-even pp/tg dos numeros atuais (script local; hipotese de scan)
- Saida: matriz valid counts pp/tg/TTFT/total/quality + candidatos p/ hibrido

### D. Quantizacao e produto
- [ ] Inspecao Q4_K_M/Q8 (tipos por tensor; sem download novo) PC/device
- [ ] Q4_K_M curto no HTP: supported/unsupported + assignment/log
- [ ] Recomendacao de formatos; NAO quantizar automaticamente
- Saida: compatibilidade produto/variante/fallback

### E. Tuning bounded
- [ ] CPU threads 1/2/4/6/8 (baseline honesto)
- [ ] HTP threads/batch suportados (2-3 configs; sem brute)
- [ ] Op-offload parcial (-ot) se parser suportar; assignment provado
- Saida: shortlist <=3 configs + profiling leve

### F. Hibrido prefill NPU + decode CPU
- [ ] F1 espacial (op-offload): assignment/transfers
- [ ] F2 temporal: export/import state HTP->CPU (llama_state_*); logits/top-k check
- [ ] F3: so se F2 impossivel e escopo pequeno; senao doc de bloqueio exato
- Saida: F factivel? prova de corretude? ganho liquido? memoria?

### G. Estabilidade/energia/termica (melhores configs)
- [ ] Serie 20-40min por config; temp/battery/cpufreq read-only
- [ ] Sem desligar USB; proxy temporal qualitativo (sem claim joule)
- Saida: janela de estabilidade + observacoes

### H. Relatorio final
- TXT em D:/Projetos/SIG/docs/especialista/PROMPT-RELATORIO-NPU-HYMT2-RODADA2.txt
- Tabela CCE/PARCIAL/PENDENTE/BLOQUEADO + JSON/CSV + dedupe/stats
- Decisao: VIAVEL_COM_GANHO | VIAVEL_SEM_GANHO | HIBRIDO_PROMISSOR_COM_GANHO |
  HIBRIDO_SEM_GANHO | API_INCOMPATIVEL | QUANT_NAO_SUPORTADA | BLOQUEIO_AMBIENTE

## Checkpoints (append)

| # | Bloco | Hora | Estado | Nota |
|---|-------|------|--------|------|
| 0 | setup | 02:53 | OK | skills + ambiente verificado (device USB, servidor, sandbox) |
| 1 | A | 03:08 | OK | refs lidas (README/build.py/run.py help reais; env vars mapeadas); SHA baseline (111/111 identicos; modelo 415e7300); probe RT OK; splits=2 auditado (3 nos CPU: GET_ROWS x2 + DUP do embd Q6_K); RECONCILIACAO-R1.md |
| 2 | B | 03:12 | OK | Corretude: +590 testes numericos PASS (RMS_NORM/SOFT_MAX 135, ROPE 287, rows/add/mul 168) + GET_ROWS q6_K 8/8 unsupported (causa do fallback embd). Reset A-B-A no HTP0 PASSOU: pos_max 16->-1; streams pos-reset identicos; idempotente (llama_memory_clear, accept interno confirmado). Harness reset_harness.cpp compilado no container. |
| 3 | C | 03:20 | PARCIAL | Corpus criado (409/952/2335/4924 chars exatos). Bench estendido pp64-4096/tg128-256 rodando (CPU lento: pp4096 custa ~min/rep no CPU). Break-even calculado: Np/Ng=9.50 (HTP vence so com >9.5:1 entrada/saida); CPU vence cenarios de traducao tipicos. |
| 3b | D | 03:35 | OK | Q4_K_M produto: sha256 c4bf1015... CONFIRMADO no device (leitura direta, sem copia); mix F32:129/Q6_K:33/Q4_K:192 (vs Q4_0 teste: Q4_0:224/Q6_K:1). Q8_0 no PC: F32:129/Q8_0:225. token_embd=Q6_K em todos -> GET_ROWS sempre na CPU. Q5_K_M local e lixo (15 bytes). |

| 4 | C | 04:00 | OK* | C.2 bench estendido COMPLETO (3 backends x pp64-4096 x tg128/256, r=3). HTP0: pp512=2574, pp4096=2012, tg=37,9-38,2. OCL: pp512=628, tg128=9,2/tg256=20,4. CPU: CONTAMINADO por thrashing (ver nota). Break-even Np/Ng=9,50. |
| 5 | C-diag | 04:05 | OK | DIAGNOSTICO DE MEMORIA: device com 14,8/15,1 GB usados (apps do usuario) + 5,2 GB zram; thrashing medido (43 MB swap-out em 8s de bench; modelo relido do disco por token). Provas: pp32 variou 9,15/206,63/9,17 t/s em runs consecutivos. Medicoes CPU/OCL deste periodo NAO comparaveis com R1; HTP mais imune (pesos pinned no DSP). |

**NOTA C (interpretacao obrigatoria no relatorio):**
- Qualquer numero de CPU (e OCL, que divide RAM com o sistema) medido entre 03:25-04:10
  carrega variancia de pressao de memoria; reportar SEMPRE com a condicao.
- HTP pp/tg sao confiaveis (memoria do DSP pinned; 1035 MiB fora do caminho do swap).
- Proxima acao: adicionar MemFree/SwapFree/pswpout ao telemetry do runner2 e agendar
  re-mediacao CPU em janela calma; F2 (hybrid_bridge ja no device) e E (tuning) podem
  rodar com a mesma cautela.
| 6 | F2 | 04:22 | OK | **HIBRIDO TEMPORAL PROVADO**: HTP prefill (np-1 tokens) -> export state (918KB p/ 15 pos = 61KB/token; export 0,09ms/import 0,17ms) -> import em CPU -> ponte (1 forward ultimo token, pos nova) -> streams IDENTICOS a referencia CPU (4 tokens: [185,46,111664,1654]). Controle CPU->CPU tambem OK. Regra descoberta: posicoes DEVEM ser consecutivas (Y=X+1) -> ultimo token fica p/ o destino. Harness: hybrid_bridge.cpp (v4). |

**F2 — implicacao quantitativa (modelo linear, numeros saudaveis R1/C.2-HTP):**
- HTP-all: pp 1015-2574 / tg 26-38 | CPU-all: pp 373 / tg 47
- Hibrido (prefill HTP + decode CPU): t = Np/1015 + Ng/47 + bridge(~0,1s)
- vs CPU-all t = Np/373 + Ng/47 -> ganho = Np*(1/373-1/1015) ~ 1,7 ms/token de entrada
- Ex.: Np=1000 -> ~1,7 s de ganho num total de ~21,7 s (~8%). Modesto para
  traducao (Np~Ng); util para prefill-dominante (ex.: resumo 4000->200).
- Custo de state: ~61 KB/posicao (memoria p/ transferir), tempo ~desprezivel (ms).
| 7 | C | 05:20 | OK | **C.2 FINAL (acordado/stayon)**: CPU pp64=372,8 (==R1) pp4096=154,8 tg128=48,0; HTP pp512=2575 pp4096=2012 tg=37,9-38,2; OCL pp256=596 pp4096=347,7 tg=21,7-20,3. Break-even atualizado: Np/Ng=3,12 (HTP vence com >3,1:1). Medicoes do C.2 original (03:25-04:10) ficam marcadas INVALIDAS (doze). |
| 7b | E | 05:25 | em curso | e1-tuning lancado (CPU threads; HTP opbatch/nhvx/mmselect/oppoll) |
| 8 | E | 05:28 | OK | Tuning completo: CPU 8t melhor (tg 52,1 vs 6t 51,2 vs 1t 27,6); HTP: MM_SELECT=1(HVX) pp -92% (HMX essencial); NHVX=2 -45% tg; OPBATCH/OPPOLL nenhum ganha do default. DEFAULT OTIMO; tg do HTP e limite estrutural. |
| 9 | G | 05:52 | OK | G1: 12/12 runs (6 HTP + 6 CPU) com hash IDENTICO (16a61449...) — saida BIT-EXATA entre backends; sem crash/NaN; temps 27,9-31,1 C. |
| 10 | D.2 | 05:53 | OK | Q4_K_M do PRODUTO rodou no HTP0 (leitura direta): pp64=1165,7/tg32=26,2 vs CPU pp64=271,8/tg32=37,7. Tipos Q4_K/Q6_K aceitos. |
| 11 | H | 05:56 | em curso | Relatorio final em docs/especialista/ (10,3 KB; dec viavel com ganho condicionado). Cleanup feito (stayon off; processos limpos). |
