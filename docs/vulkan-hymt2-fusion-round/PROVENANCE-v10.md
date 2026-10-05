# PROVENANCE v10 — fontes, recipe e allowlist do pacote nativo

Data: 05/10/2026. Status: smoke runtime PASS (Texto Q4/Q8/CPU + Whisper
OFFLINE); reset in-context A-B-A CONFIRMADO (piloto 05/10 20:13-20:14).

## 1. Baseline e fontes

- Baseline do repo: `e1bd73e` (main).
- Build do PRODUTO v10 foi feito no worktree ISOLADO `D:/svr11`
  (worktree de `e1bd73e`), NAO de um rebuild direto do HEAD WIP.
- O HEAD (commit `022b1d6`) contem TAMbEM o WIP do executor concorrente
  (cache/instrumentacao OpenCL em `ggml-opencl.cpp`, `android-opencl-loader.cpp`,
  `llama-context.cpp`, `sig_transpose_guard.h`) — que NAO entra no produto.
- MATRIZ (source HEAD-WIP vs fonte-produto vs artefato):

| Item | HEAD 022b1d6 (WIP) | Fonte produto (svr11) | ZIP v10 prod | APK 1.507 |
|---|---|---|---|---|
| ggml-vulkan.cpp (workaround 0x5143) | presente | presente (allowlist) | compilado | so Manifest (sem .so) |
| shaders Q8 (dequant/mul_mm) | presente | presente (allowlist) | compilado | - |
| reset Hy-MT2 (hymt2_request_reset.h + JNI) | presente | presente (allowlist) | compilado | - |
| ggml-opencl.cpp cache/instr. | presente (WIP) | AUSENTE (revertido) | AUSENTE | AUSENTE |
| android-opencl-loader.cpp | presente (WIP) | AUSENTE (revertido) | AUSENTE | AUSENTE |
| llama-context.cpp (PROF) | presente (WIP) | AUSENTE (revertido) | AUSENTE | AUSENTE |

NOTA OpenCL: ausencia de `NEEDED libOpenCL.so` na lib NAO prova backend ausente
(o loader usa dlopen/dlsym). A prova usada: o binario do pacote NAO contem os
simbolos/flags de instrumentacao e foi buildado da fonte isolada sem o cache;
`terminal.txt` do whisper mostra `SIG_BUILD_GGML_OPENCL=1` (baseline OpenCL do
ggml presente), com cache/instr. do WIP fora. Nao inferir por NEEDED.

## 2. Allowlist (patch do produto — vulkan/reset)

1. `app/src/main/cpp/llama/ggml/src/ggml-vulkan/ggml-vulkan.cpp`
   — workaround Qualcomm 0x5143: `disable_fused_rms_norm_mul`,
     `disable_add_rms_fusion` (recusa fusao RMS_NORM+MUL).
2. `.../vulkan-shaders/dequant_funcs.glsl` — leitura Q8 s8 aritmetica.
3. `.../vulkan-shaders/mul_mm_funcs.glsl` — idem.
4. `app/src/main/cpp/llama-jni/hymt2_request_reset.h` — helper reset
   (`llama_memory_clear` data=true).
5. `app/src/main/cpp/llama-jni/llama_jni.cpp` — SOMENTE o reset
   (`hymt2_begin_fresh_request` no inicio de generate, sob g_mutex).
6. `app/src/main/cpp/llama-jni/CMakeLists.txt` — comentario GGML_VULKAN_DEBUG OFF.

DENYLIST (fora do produto): `ggml-opencl.cpp`, `android-opencl-loader.cpp`,
`llama-context.cpp`, `sig_transpose_guard.h`, `*.sig_orig`, `layer_diff*.cpp`.

## 3. Recipe do build (svr11)

```powershell
# 1. build nativo (2 ABIs): preenche merged_native_libs (whisper+omp)
.\gradlew.bat :app:assembleDebug -PbuildNativeComponents=true
# 2. STRIP das libs cxx (APOS o assemble — o assemble as repoe com debug):
#    llvm-strip --strip-unneeded (NDK 27.2.12479018) em libsig_llama/libsig_whisper/omp/npu_probe
# 3. gerar ZIPs por ABI:
.\scripts\build-android-native-dependencies.ps1 -Version 10
# 4. verificar contra o manifesto:
.\scripts\verify-native-dependencies.ps1 -Version 10
# 5. APK comum (sem nativos):
.\gradlew.bat :app:assembleDebug
```

PITFALL: editar `.glsl` nao dispara regeneracao no ninja — apagar `*.comp.cpp`.

## 4. Hashes (v10 publicado — IMUTAVEL, nao re-stripar in-place)

| Artefato | Tamanho | SHA-256 |
|---|---|---|
| ZIP v10 arm64 | 48.455.121 | `19373b869acda07c92cd7df6a6d5b29aeb1ebb21640534f175873d462e288398` |
| ZIP v10 x86_64 | 54.971.818 | `be70ce098c913dfc6b36fd0713b7db3bdb697785eb037673ced76689f2bd1349` |
| libsig_llama.so (arm64, stripada) | 30.761.920 | `ec9c8315988c7a40ae5aa1fc61f1cb74cf9aaa24b6f338b5cd453978fb4a79c7` |
| libsig_whisper.so (arm64, NAO stripada) | 62.179.616 | `8e3982b289ca1a32ded995f5bd760fc9c99df83ca1a7ab80e959a2c27cff745b` |
| APK 20261005_001 (sig.apk) | 8.117.678 | `6c76b99c1e5102eb0f39565a8243474b675d77010f26bcf88d50fc2f708cd2d1` |

Estado de strip: `libsig_llama` stripada; `libsig_whisper` NAO (build cxx Debug
do Gradle — +26 MB de debug info; codigo/text ~identico ao v9, delta ~-1 KB).
Corrigir na PROXIMA versao (nao in-place). Ver `native-dependencies/README.md`.

## 5. Estado de testes / CI (pendencias declaradas)

- Fontes de teste versionadas em `native-dependencies/tests/` (commit `3620bb3`):
  CPP/SH/H + fixtures minimas `.glsl.orig` — SEM binarios/logs.
- PENDENTE: fixtures ZIP do `DownloadPlanTablesTest` (48-55 MB/ABI) NAO sao
  versionadas; avaliar fixture minimal determinista que valide o contrato sem
  baixar o ZIP de 50 MB em unit CI (numerical runtime probe != mock fixture).
- PENDENTE: pin completo das fontes do build isolado (hashes de arquivo do
  svr11 por arquivo) — este documento registra baseline+allowlist+recipe+hashes
  de saida; falta o hash por-fonte.
- Whisper: runtime PASS nao exige republish; questao de tamanho/build type
  permanece documentada (nao alterar artefato publicado).

## 6. Evidencias runtime (05/10)

- Smokes Texto: Q4 Vulkan A-B-A classico + piloto in-context A-B-A (20:13-20:14):
  A1 e A2 = "Bom dia, companheiros" (7 tokens cada; sha256 do output
  `1e2a5ddbb1f9b44d912d459feb09fd832127f44fcfa15bf23f7c07b2d624076d`);
  B distinto (20 tokens, sha256 `8176e693...`); entre A1/B/A2 NENHUM evento
  novo de "modelo carregado" (1 unica carga, no A1) — reset in-context OK.
- Whisper OFFLINE: transcricao real de WAV TTS pt-BR (base/CPU) sem crash.
- Detalhes: `SMOKES-V10-2026-10-05.txt`, `AUDITORIA-RELEASE-20261005_001-2026-10-05.txt`.

— fim —
