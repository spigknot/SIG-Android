# ACHADO — ESTADO DE ENERGIA E DEGRADACAO CPU (05/10-06/10/2026, madrugada)

## O que foi observado
Sessao de benchmarks iniciada ~03:25 com o telefone em uso recente (tela ativa).
Por volta de 03:30-04:20 (device entrando em doze com tela apagada ha tempo):

- CPU (llama-bench -ngl 0): pp64 caiu de ~373 t/s (R1, 02:36) para **12-14 t/s**;
  tg32 caiu para **0,6-1,4 t/s** (vs 47,3 na R1). ~30x de degradacao.
- Frequencias dos cores durante o bench: **384-768 MHz** (idle/doze cap);
  o device tem maximo ~4 GHz.
- Leitura do modelo (1 GB) em page cache: ~2 s (I/O NAO era o gargalo).
- Memoria do sistema: 14,8/15,1 GB usados por apps do usuario + 5,2 GB zram
  (pressao adicional, mas nao a causa primaria).
- **HTP0 na MESMA janela: pp512=2574 t/s, tg=37,9-38,2 t/s** — SEM degradacao.
  (Numeros do C.2 medidos 03:52-03:53 com o device no mesmo estado.)

## Prova de causalidade
`input keyevent KEYCODE_WAKEUP` (acorda a tela) + re-bench imediato:
- pp64: **216,0 t/s** | tg32: **44,6 t/s** (~93% do valor R1 no tg).

## Conclusoes
1. **Medicoes CPU/OCL feitas com o device dormindo NAO sao comparaveis** com a
   R1 (e ficam invalidas para o relatorio). Corrigido: runner2 agora sempre
   acorda o device; benches longos usam waker periodico (keyevent a cada 20 s).
2. **O HTP (Hexagon/DSP) nao segue o cap de frequencia da CPU** — manteve
   throughput pleno com o device em doze. E uma vantagem qualitativa de
   robustez/energia do NPU (nao um numero de joule; sem telemetria de energia).
3. Para o SIG: um backend NPU seria previsivel em cenarios de baixa energia
   do telefone (tela apagada/doze), onde CPU cai fortemente. NAO afirmamos
   economia de bateria (sem medicao de energia).

## Arquivos/evidencias
- trials.jsonl: c2_* (03:25-03:53, contaminados), c2_cpu_recheck*, bench-probe
- Este achado invalida o uso dos numeros CPU/OCL do C.2; re-bench "awake" em
  c2_cpu_ext_awake / c2_ocl_ext_awake (04:30+).
