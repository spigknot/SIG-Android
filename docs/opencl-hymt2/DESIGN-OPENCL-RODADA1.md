# Frente OpenCL — Hy-MT2 (app Texto) — DESENHO DA RODADA 1

Status: iniciada 02/10/2026, autorizada pelo usuário ("deixar OpenCL o melhor e
mais rápido possível SEM sacrificar correção"). Vulkan e CPU permanecem
CONTROLES. NPU/Whisper fora.

## Objetivo mensurável
1. Correção: traduções corretas como rascunho revisável nos 4 tamanhos
   (409/952/2335/4924 chars, EN→PT).
2. Estabilidade: sem crash/-38/NaN/limite patológico em smokes progressivos.
3. Backend efetivo OpenCL (não fallback CPU silencioso), pedidos isolados.
4. Só depois: minimizar carga/prefill/decode/latência de uso repetido.

## Estado e referências (pré-condições da rodada)
- Aparelho CPH2747/OP611FL1 (Android 16, Adreno 840, plataforma canoe),
  serial USB 3B15BD00FVE00000.
- APK instalado = build 27d9b20b… (vc69/1.506, loader v9) — conferido ao vivo.
- Lib efetiva pasta 9 = 9698670c… (reset+signedness) — conferida ao vivo.
- Modelos: Q4_K_M c4bf1015… (1.133.080.544 B); Q8_0 5c3fe0b1… (1.908.528.192 B)
  — SHA completo conferido no device.
- Corpus: 409/952/2335/4924 chars com sha e8b35290/2115fd95/6991a631/b20256db
  — conferidos na fonte REAL (corpus.json).
- Histórico: 6 cargas + 6 inferências OpenCL de 56 chars sem -38; a falha
  histórica CL_INVALID_MEM_OBJECT NÃO está provada resolvida; primeiro
  load OpenCL do dia teve outlier 20.9 s (causa aberta).

## Métricas (separadas, sem confundir)
- load_s = timer do app "Modelo carregado em Ls" (fronteira JNI: probe device
  + carga GGUF + upload + contexto). TOTAL interno = "Tradução pronta em".
  GERACAO = "Desempenho: N tokens em T s (tok/s)". WALL = tap→observação.
  Prefill/TTFT: só se medidos diretamente (não derivar por subtração).
- process_cold ≠ disco frio (page cache persiste). Cache de programa/kernel
  OpenCL do driver: estado conhecido/desconhecido registrado.
- Memória: buffers do app (model/context/compute + VRAM) e, como amostra,
  PSS/RSS — ciente de que o driver OpenCL NÃO contabiliza os pesos no PSS do
  processo (verificado 02/10); não anunciar economia por isso.

## Fases
- F1 (esta rodada): smokes progressivos OpenCL Q4 e Q8 (curto fresco → medio →
  longo → curto A→B→A warm SEM reload). Fail-fast: qualquer crash/-38/NaN/
  saída patológica → guardar prova e PARAR a combinação antes dos longos.
  Se OK: baseline OpenCL 2 modelos × 4 tamanhos × 3 reps (blocos por modelo).
  Controles CPU/Vulkan: curto e mais_longo por modelo (3 reps) — mesma APK/
  lib/config/corpus. Contagens por Python (planejado/executado/válido/
  inválido/falha-de-correção separados).
- F2: localizar gargalo (init/probe/JIT/upload/prefill/decode/readback) por
  rastreio; profiling só isolado e com controle; build diagnóstica local se
  precisar separar etapas (ativação exige consentimento).
- F3: uma otimização por vez, A/B com ordem alternada, 3 reps, mediana/range;
  sem corte silencioso de precisão; sem tocar sampler/template/pesos/reset.
- F4: aceitação com matriz matched e recomendação explícita por workload;
  backend default INALTERADO.

## Critérios de parada / bloqueio
- Falha de correção ou estabilidade OpenCL → PARAR a combinação e documentar
  (medir é opcional; preservar correção é obrigatório).
- Qualquer deploy novo (.so/APK) só com autorização específica
  (hash/escopo/backup/reversão); enquanto isso, trabalho offline.
- Térmica/instabilidade do aparelho → checkpoint e pausa clara.
- Nada de root/drop-cache/reboot/disable Vulkan/remoção de dados.

## Entregas
- logs-rodada-opencl/ (runs com journal/record/delta; manifest SHA verificado)
- Relatório A-H (docs/opencl-hymt2/ ou subpasta), conclusões
  MEDIDO/HIPOTESE/PARCIAL/BLOQUEADO, tabela final por backend × tamanho.
