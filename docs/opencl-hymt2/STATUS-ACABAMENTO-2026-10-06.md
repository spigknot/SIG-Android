# STATUS — ACABAMENTO OPENCL (F3b/guard + loadercache) — 2026-10-06

Fases 1-4 (local/isolado) concluidas; piloto (Fase 5) aguardando conexao do
aparelho (autorizado pelo usuario; wireless/USB indisponiveis no momento).

## FASE 1 — Inventario e design — CONCLUIDA
- Repo: HEAD d929d08 limpo; v10/release imutaveis; WIP alheio ausente.
- Design: docs/opencl-hymt2/DESIGN-ACABAMENTO-OPENCL-2026-10-06.md
  (allowlist/denylist/matriz/decisoes; denylist = DELTAS de instrumentacao,
  nao fontes baseline). COMMITADO (d929d08).
- Worktree isolado CURTO novo: D:/ocl1 (baseline e1bd73e + vulkan-reset v10
  + candidato OpenCL). D:/svr11 preservado.

## FASE 2 — F3b/guard — CONCLUIDA (local)
- Guard v2 (SEM cache) aplicado; versao inline-com-cache do HEAD NAO usada.
- transpose_guard_test (versionado): GREEN 5/5 PASS + mutacao detectada.
  Runner roda com g++ MSYS2 (PATH + CXX); sem device/NDK.
- Guard no binario candidato: objeto ggml-opencl.cpp.o referencia
  `clGetCommandQueueInfo` (U) — a consulta esta compilada; transpose_2d
  presente. (Sem regressao de lifetime: guard le propriedades da fila por
  consulta; incerteza => bloqueante.)

## FASE 3 — Loadercache — CONCLUIDA (local)
- Produto: cache SEMPRE ON, SEM chaves/contadores (grep=0 no candidato).
- Testes versionados em native-dependencies/tests/loader/: RED 4 / GREEN 2 /
  endurecimento A-F / mutacao — PASS. COMMITADO (d929d08).
- Loader do CANDIDATO compilado no harness host: dlsym=2 (1/simbolo) OK.
- Binario: 40 instancias do template load_opencl_fn (cache por simbolo);
  sem `debug.sig.hymt2` (grep=0).

## FASE 4 — Build real + proveniencia — CONCLUIDA (2 ABIs)
- Comando oficial (script build-android): cmake do SDK 3.22.1 + NDK
  27.2.12479018 + ninja + zig host tool.
- arm64-v8a: BUILD_RC=0 (657/657). ELF64 AArch64; NEEDED libandroid/
  liblog/libvulkan/libm/libdl/libc; 9 JNI symbols; 0 diag; guard+cache.
- STRIP (llvm-strip --strip-unneeded): 95.882.056 -> 30.764.464 B;
  sha256 596aba0e9813f516cb68117e9b8f26fe40458010cf86fb118f7c80fac13200d9
  (v10: 30.761.920 B / ec9c8315 — delta +2.544 B do guard/cache/wrappers).
- Staging: scratch/opencl-cand/libsig_llama.so (para o safe_deploy).
- x86_64: BUILD_RC=0 (657/657). ELF64 X86-64; 9 JNI symbols; STRIP:
  93.062.976 -> 31.874.632 B;
  sha256 b89ba7d10b9e4d96931327e36c57a66c84e140be506535b655d3e02e53a07e93.
  Staging: scratch/opencl-cand/libsig_llama_x86_64.so.
- Whisper/FFmpeg/ONNX/QNN/Silero/OMP: intocados.

## ARTEFATOS CANDIDATOS (staging para deploy)
| ABI | Tamanho | SHA-256 |
|---|---|---|
| arm64-v8a | 30.764.464 | 596aba0e9813f516cb68117e9b8f26fe40458010cf86fb118f7c80fac13200d9 |
| x86_64 | 31.874.632 | b89ba7d10b9e4d96931327e36c57a66c84e140be506535b655d3e02e53a07e93 |
Rollback (estado encontrado aprovado): v10 arm64 ec9c8315... (30.761.920 B).

## FASE 5 — Piloto no aparelho — CONCLUIDA (essencial; ver PILOTO-OPENCL-2026-10-06.md)
- Precheck fresh OK (wireless 41257; APK v1.507; v10 ec9c8315 no device).
- safe_deploy CANDIDATO (596aba0e) OK + maps comprovando a lib carregada.
- Q4 curto: candidato "Bom dia, companheiros" (7t, ~10s) | v10 idem (7t, ~9s).
- Q8 curto: candidato "Bom dia, companheiros." (8t, ~14s) | v10 idem (8t, ~10s).
- OUTPUTS IDENTICOS candidato vs v10; sem crash/ANR; rollback v10 OK.
- Estado final do device = v10 (estado encontrado restaurado).
- LIMITACAO: "Q4 mais longo" nao executado (UI nao aceitou input longo via
  adb nesta sessao); ganho de performance pendente de janela propria.

## APROVACOES (usuario, 06/10)
1. Incorporar F3b+cache no produto local (guard v2 + cache sempre ON) — SIM.
2. Piloto temporario no CPH2747 com backup/rollback — SIM.
3. Commit/push de fontes+testes+recipe — SIM (feito: d929d08).
4. Release/R2/GitHub — SEPARADO; so apos piloto aceito + autorizacao a parte.

## COMMITS DESTA ETAPA
- d929d08: patch candidato + testes do loader + design (+ .gitignore).
Nada publicado; v10/release intocados.
