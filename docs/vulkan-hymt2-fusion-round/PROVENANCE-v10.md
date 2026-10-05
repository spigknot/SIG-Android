# PROVENANCE v10 — fontes, recipe e allowlist do pacote nativo

Data: 05/10/2026 (rev. 2 — correcoes do parecer: recipe real, denylist precisa,
testes de checkout limpo, pin de fontes). Status: smoke runtime PASS (Texto
Q4/Q8/CPU + Whisper OFFLINE); reset in-context A-B-A CONFIRMADO (piloto 05/10
20:13-20:14).

## 1. Baseline, fontes e MATRIZ (precisao: baseline presente x delta ausente)

- Baseline do repo: `e1bd73e` (main).
- Build do PRODUTO v10 foi feito no worktree ISOLADO `D:/svr11`
  (worktree de `e1bd73e`), NAO de um rebuild direto do HEAD WIP.
- O HEAD (commit `022b1d6`) contem TAMbEM o WIP do executor concorrente
  (cache + instrumentacao OpenCL) — cujo DELTA nao entra no produto.

MATRIZ (o que significa "fora do produto" — precisao do parecer):
| Item | HEAD 022b1d6 (WIP) | Fonte produto (svr11) | ZIP v10 prod | APK 1.507 |
|---|---|---|---|---|
| ggml-opencl.cpp — BASELINE (backend OpenCL do ggml) | presente | presente | compilado (baseline) | so Manifest (sem .so) |
| ggml-opencl.cpp — DELTA WIP (cache C3/C4 + instrumentacao) | presente (WIP) | AUSENTE (revertido) | AUSENTE (delta) | AUSENTE |
| android-opencl-loader.cpp — DELTA WIP | presente (WIP) | AUSENTE (revertido) | AUSENTE (delta) | AUSENTE |
| llama-context.cpp — DELTA WIP (PROF) | presente (WIP) | AUSENTE (revertido) | AUSENTE (delta) | AUSENTE |
| ggml-vulkan.cpp (workaround 0x5143) | presente | presente (allowlist) | compilado | - |
| shaders Q8 (dequant/mul_mm) | presente | presente (allowlist) | compilado | - |
| reset Hy-MT2 (hymt2_request_reset.h + JNI) | presente | presente (allowlist) | compilado | - |

CORRECAO SEMANTICA: "denylist" refere os DELTAS experimentais/cache/instrumentacao
— NAO a remocao dos fontes baseline do backend OpenCL (que existem no produto).
O produto compila o backend OpenCL BASELINE do ggml; o que fica fora e' o delta
do WIP (cache de programa + hooks de diagnostico) e o `*.sig_orig`.

NOTA OpenCL/metodo: ausencia de `NEEDED libOpenCL.so` NAO prova backend ausente
(o loader usa dlopen/dlsym). A evidencia usada e' de FONTE + FLAGS: o build do
produto veio do worktree isolado sem o delta (ver `patches/` e o manifest de
fontes); `terminal.txt` do whisper mostra `SIG_BUILD_GGML_OPENCL=1` (baseline
presente). Nao inferir por NEEDED.

## 2. Allowlist (patch do produto — vulkan/reset)

Versionada em `native-dependencies/patches/vulkan-reset-v10.patch` (aplicavel ao
baseline; sha256 e hashes por arquivo no `.manifest.txt` do diretorio):

1. `ggml-vulkan.cpp` — workaround Qualcomm 0x5143 (`disable_fused_rms_norm_mul`,
   `disable_add_rms_fusion`).
2. `vulkan-shaders/dequant_funcs.glsl` — leitura Q8 s8 aritmetica.
3. `vulkan-shaders/mul_mm_funcs.glsl` — idem.
4. `llama-jni/hymt2_request_reset.h` (NOVO) — helper reset (`llama_memory_clear` data=true).
5. `llama-jni/llama_jni.cpp` — SOMENTE o reset (`hymt2_begin_fresh_request` no
   inicio de generate, sob g_mutex). ZERO referencias a instrumentacao (grep = 0).
6. `llama-jni/CMakeLists.txt` — correcao de comentario (GGML_VULKAN_DEBUG OFF).

Validacao do patch: `git apply --check` OK no baseline (worktree de teste) e
hashes pos-aplicacao identicos ao manifest (LF canonico).

## 3. Recipe — FLUXO REAL v10 (e a correcao para o fluxo FUTURO)

FLUXO REAL do v10 (historico; foi assim que o artefato publicado saiu):

1. `gradlew :app:assembleDebug -PbuildNativeComponents=true`
   -> compila: cxx (libsig_llama Debug ~95,6 MB) + merged_native_libs
      (libsig_whisper Debug ~62,2 MB, libomp, libsig_npu_probe).
2. `llvm-strip --strip-unneeded` (NDK 27.2.12479018) aplicado nas libs
   (llama 95,6->30,7 MB; whisper 62,2->36,2 MB; omp; npu_probe).
3. `assembleDebug` rodado DE NOVO (para repor `merged_native_libs`, que havia
   sido limpo) -> o merge REGENEROU/RE-POS as libs do merged a partir do cxx
   Debug (whisper 62,2 MB de novo). O cxx do llama NAO foi retocado (o build
   do llama e' via CMake externo, nao regerado pelo assemble).
4. `scripts/build-android-native-dependencies.ps1 -Version 10` copia:
   - libsig_llama.so <- **cxx** (stripped, 30,7 MB)
   - libsig_whisper.so/libomp/etc <- **merged_native_libs** (whisper RE-POSTA
     Debug, 62,2 MB UNSTRIPPED)
   -> ZIP v10 final: llama strippada; whisper UNSTRIPPED (+26 MB de debug info).

CAUSA RAIZ: o script de empacotamento copia de DOIS lugares diferentes (cxx e
merged); o strip tinha sido feito nos dois, mas o segundo assemble re-populou
o merged (copiando do cxx da whisper, que continuava Debug) DEPOIS do strip.
O resultado: ZIP com whisper unstripped.

CORRECAO PARA O FLUXO FUTURO (nao aplicada no v10 — artefato imutavel):
  a) aplicando o strip na COPIA do pacote ao zipar (dentro do script) — ponto
     unico, apos todas as re-populacoes; ou
  b) rodando o strip APOS O ULTIMO assemble, tocando TANTO o cxx quanto o
     merged (os dois lugares de onde o script copia);
  e SEMPRE: apos zipar, CONFERIR as ELF sections das entries do ZIP final
  (sem `.debug_*`/`.symtab` para libs de release) antes de publicar.
  Usar `--strip-unneeded` (preserva simbolos dinamicos/JNI; nao usar strip
  agressivo que mate exports). NAO re-stripar o v10 publicado (imutavel).

## 4. Hashes (v10 publicado — IMUTAVEL)

| Artefato | Tamanho | SHA-256 |
|---|---|---|
| ZIP v10 arm64 | 48.455.121 | `19373b869acda07c92cd7df6a6d5b29aeb1ebb21640534f175873d462e288398` |
| ZIP v10 x86_64 | 54.971.818 | `be70ce098c913dfc6b36fd0713b7db3bdb697785eb037673ced76689f2bd1349` |
| libsig_llama.so (arm64, stripada) | 30.761.920 | `ec9c8315988c7a40ae5aa1fc61f1cb74cf9aaa24b6f338b5cd453978fb4a79c7` |
| libsig_whisper.so (arm64, NAO stripada) | 62.179.616 | `8e3982b289ca1a32ded995f5bd760fc9c99df83ca1a7ab80e959a2c27cff745b` |
| APK 20261005_001 (sig.apk) | 8.117.678 | `6c76b99c1e5102eb0f39565a8243474b675d77010f26bcf88d50fc2f708cd2d1` |

Estado de strip: `libsig_llama` stripada; `libsig_whisper` NAO (fluxo real §3;
codigo/text ~identico ao v9, delta ~-1 KB = debug info). Corrigir na PROXIMA
versao nativa (nova NativeVersion), nao in-place. Runtime PASS nao bloqueia a
release existente.

## 5. Testes — estrutura atual (checkout limpo) + pin de fontes

TESTES DOS PACOTES NATIVOS (separados em 3, ref. parecer 05/10 §1):
- `DownloadPlanTablesTest` (5 testes) — plano, contagens, STORED/DEFLATE.
  NAO depende de fixture nenhuma. Roda em checkout limpo.
- `NativeDepsContractFixtureTest` (6 testes) — CONTRATO com a fixture MINIMAL
  deterministica `app/src/test/resources/native-deps/contract-min.zip` (~1 KB,
  VERSIONADA; gerador `scripts/gen-native-deps-fixture.py`): positivo +
  negativos (entry faltante, entry extra, tamanho divergente, sha corrompido).
- `NativeDepsOfficialFixturesTest` (4 testes) — VALORES REAIS contra os ZIPs
  v10 (50 MB/ABI, FORA do Git): tabela x ZIP arquivo-a-arquivo, soma, sha256
  declarado; PULADO (Assume) quando os ZIPs nao estao em disco.

PROVA DE CHECKOUT LIMPO (05/10): com os ZIPs reais movidos para fora do
resources (simulando clone limpo): DownloadPlanTablesTest 5/5 PASS,
NativeDepsContractFixtureTest 6/6 PASS, NativeDepsOfficialFixturesTest
4/4 SKIPPED (AssumptionViolatedException "ZIPs reais v10 ausentes").
Suite completa no ambiente com ZIPs: 541 testes, 0 falhas, 0 erros, 0 skipped.

NAO enfraquecido: os valores de producao seguem conferidos pelo official
(arquivo-a-arquivo + sha declarado) quando os ZIPs reais existem; o contract
exercita a mesma logica com a fixture minimal. Fixture minimal NAO substitui
os valores prod.

PIN DE FONTES: `native-dependencies/patches/` — patch exato
(`vulkan-reset-v10.patch`, aplicavel ao baseline) + manifest SHA-256 por
arquivo + contexto (toolchain/ABIs/fluxo) + README. Permite reconstruir o
produto se `D:/svr11` for perdido. LIMITE: source hash nao prova
reprodutibilidade byte-a-byte — a prova de bytes e' o SHA dos artefatos
publicados + smoke runtime; fresh build nao undertaken.

## 6. Evidencias runtime (05/10)

- Piloto A-B-A in-context (20:13-20:14): A1==A2 = "Bom dia, companheiros"
  (7 tokens; sha256 do output `1e2a5ddbb1f9b44d912d459feb09fd832127f44fcfa15bf23f7c07b2d624076d`);
  B distinto 20 tokens; entre A1/B/A2 NENHUM "modelo carregado" novo (1 unica
  carga, no A1) — reset in-context OK.
- Whisper OFFLINE: transcricao real de WAV TTS pt-BR (base/CPU) sem crash;
  precheck: sha do .so 8e3982b2 (device), maps/PID com root mostra libs v10
  mapeadas; modelo base 60ed5bc3; wav e6bd87bc.
- Smokes Q4/Q8 Vulkan + CPU: 541/0/0/0 unit; sem ANR/crash na janela.
- Detalhes: `SMOKES-V10-2026-10-05.txt`, `AUDITORIA-RELEASE-...txt`.

— fim —
