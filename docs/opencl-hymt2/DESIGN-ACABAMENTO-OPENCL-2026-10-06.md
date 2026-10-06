# DESIGN — ACABAMENTO OPENCL (F3b/guard + loadercache) — 2026-10-06

Frente: consolidar F3b (transposes non-blocking com guard) + loadercache
(cache de simbolos OpenCL) em PRODUTO MINIMO. Nao reiniciar pesquisa.
Escopo duro: preservar Vulkan/reset v10, Whisper e demais componentes; NAO
abrir batching/backpressure, fusao ampla, novos kernels ou tuning de precisao.

## 0. Contexto (fonte)

- Achado central (F15/F17): `android-opencl-loader.cpp` fazia
  `dlerror()+dlsym()` POR CHAMADA. Contador real: pedido curto cache OFF =
  2.190.996 chamadas/dlsym; cache ON = 3 dlsym. Ganho stock medido (F20,
  sem instrumentacao): Q4c +16..+30%%, Q8c +20..+26%%, Q4l +53%% (um run
  anomalo declarado). Saida canonica IDENTICA ON/OFF na amostra.
- F3b: transposes non-blocking SEGURO via guard que consulta
  CL_QUEUE_PROPERTIES e so habilita com fila IN-ORDER confirmada; erro da
  consulta => bloqueante (fail-safe). Versao v2 = SEM cache de guard
  (elimina a classe "cache do primeiro queue").
- Material: `docs/opencl-hymt2/` (F3B-PATCH.diff, LOADER-CACHE-PATCH.diff,
  BUILD-RECIPE-F3B.md, F15-F20, logs). Testes: `native-dependencies/tests/`
  (guard) + `native-dependencies/harness/fixtures/loader_cache/` (loader:
  fake_dlfcn + cache/hardening tests + runners) — ESTES AINDA NAO VERSIONADOS.

## 1. Estado dos artefatos (inventario)

- Repo: HEAD e521a0a, limpo (0 alteracoes). Release 20261005_001 + ZIPs v10
  IMUTAVEIS. Lib llama v10: ec9c8315 (30.761.920 B). Whisper v10: 8e3982b2
  (62.179.616 B, NAO stripada — questao conhecida, NAO corrigir nesta tarefa).
- HEAD contem o WIP OpenCL: loadercache COM contadores + chaves
  (`debug.sig.hymt2.loadercache` default LIGADA, `debug.sig.hymt2.clcount`)
  e schedprof no ggml-opencl.cpp; guard F3b INLINE COM cache (linha ~150,
  versao v1 superada).
- Fontes isoladas v10: baseline e1bd73e + `native-dependencies/patches/
  vulkan-reset-v10.patch` (sha 9ea01331...). NAO usar o HEAD como fonte
  integral do produto nativo.

## 2. ALLOWLIST (produto minimo candidato)

A. F3b/guard (sem regressao de lifetime):
  1. `sig_transpose_guard.h` — versao v2 (SEM cache): consulta por chamada,
     fail-safe em erro. SUBSTITUI a versao inline-com-cache do HEAD.
  2. `ggml-opencl.cpp` — include do header + UMA linha no `transpose_2d`:
     `if (blocking && sig_transpose_can_skip_wait(backend_ctx->queue)) blocking = false;`
     (remove a definicao inline com cache e QUALQUER instrumentacao).
  3. `android-opencl-loader.cpp` — wrappers `clGetCommandQueueInfo` +
     `clGetEventInfo` (necessarios ao guard; padrao dos demais wrappers,
     null-safe com OPENCL_MISSING_ERROR).

B. Loadercache (seguro, default produto):
  4. `android-opencl-loader.cpp` `load_opencl_fn` — cache de simbolo
     atomico (acquire/release), falhas NUNCA cacheadas, null seguro p/
     ausente; SEMPRE ATIVO no produto (SEM chave runtime, SEM contadores).
     Handle dlopen = lifetime do processo (sem dlclose) => "1 dlsym por
     simbolo por processo" valido; sem generation/invalidation.
  5. Wrapper `clGetMemObjectInfo` (fusion-round 27/09, ja em uso).

C. Testes/recipe/docs (a versionar):
  6. native-dependencies/tests/: transpose_guard_test.cpp (ja versionado) +
     loader_cache_test.cpp + loader_hardening_test.cpp + fake_dlfcn/ +
     run_loader_cache_test.sh + run_transpose_guard_test.sh (ja).
  7. Recipe de build + manifesto SHA (entradas/saidas) do candidato.

## 3. DENYLIST (deltas experimentais — NAO entram no produto)

- schedprof: `sig_sched_*`, `g_sig_sched_enabled`, `g_sig_perop_us/n`,
  `g_sig_graph_us`, `g_sig_enq_us`, `g_sig_nodes`, `g_sig_enq_n`.
- hostprof / decprof / topops / `sig_opencl_diag` / paths de CSV trace.
- Contadores do loader: `g_sig_shim_calls`, `g_sig_dlsym_calls`,
  `sig_opencl_shim_calls/dlsym_calls/counters_reset`.
- Chaves de debug no PRODUTO: `debug.sig.hymt2.loadercache`,
  `debug.sig.hymt2.clcount` (A/B OFF fica em ARTEFATO DE DIAGNOSTICO
  separado, se necessario — nunca requisito do beneficio).
- NAO remover arquivos baseline inteiros (ggml-opencl.cpp baseline, kernels,
  libdl.h etc.). A denylist e' sobre DELTAS, nao sobre fontes do backend.

## 4. MATRIZ baseline-produto / HEAD / candidato

| Item | v10 (fontes produto) | HEAD e521a0a (WIP) | Candidato OpenCL |
|---|---|---|---|
| Vulkan/reset (patch v10) | presente | presente | PRESERVADO |
| guard F3b | ausente | inline COM cache (v1) | v2 sem cache + chamada |
| loader cache | ausente | COM contadores+chaves | SEM contadores/chaves (sempre ON) |
| wrappers queue/event/memobj | nao | sim | sim (3 wrappers) |
| schedprof/instrumentacao | ausente | presente | AUSENTE |
| default UI | Q4_K_M + Vulkan | idem | IDEM (nao muda backend default) |

## 5. Etapas de seguranca / testes (Fases 2-4)

- Guard: reposicionar v2; rodar transpose_guard_test (RED/GREEN) +
  mutacao; lifetime probe compile (runtime so com consentimento).
- Loader: produto sem chaves => adaptar o harness para testar o mecanismo
  puro (a fixture loader_PATCHED.cpp serve; validar publicacao/falhas/
  concorrencia + repeated/multisymbol). Contagens atuais contadas na hora.
- Build: diretorio isolado CURTO novo (D:/ocl1) com cache CMake proprio;
  baseline+patch+allowlist; ninja 2 ABIs; verificar ELF (sem refs diag,
  JNI resolve, NEEDED); shaders Vulkan regenerados preservam Q8.
- Whisper/FFmpeg/ONNX/QNN/Silero/OMP: preservar contrato/origem (nao tocar;
  strip do whisper NAO entra aqui).

## 6. Decisoes que precisam do USUARIO (formulario agrupado)

1. INCORPORAR F3b/cache no produto LOCAL (fontes candidatas + cache
   default ON no artefato)? (o A/B OFF vira artefato de diagnostico separado)
2. Ativacao TEMPORARIA de lib/pacote/APK no CPH2747 para o piloto enxuto
   (com backup/rollback ao estado encontrado)?
3. Commit/push das fontes/testes/recipe no repo?
4. Release/R2/GitHub (nova NativeVersion + APK) — so apos piloto aceito?

Nada publicado/commitado sem essas aprovacoes. Pacotes v10/release atuais
permanecem intocados.
