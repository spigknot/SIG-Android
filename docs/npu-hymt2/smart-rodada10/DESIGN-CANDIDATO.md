# DESIGN — CANDIDATO B: tree ggml-hexagon do UPSTREAM sobre o core SIG (R10)

## 1. CONTEXTO E PINS
- Core de referencia: SIG vendor em /root/sig-smart/llama (ggml-vulkan.cpp
  sha e90fcdc9963c57e5 == repo local; patchset Vulkan Adreno 0x5143,
  OpenCL loader proprio, reset de pedidos, F3b/guard/loadercache).
- Upstream de referencia: /root/llama-cpp-npu (build do "controle" v11:
  prefill HTP 72-104ms!).
- Baseline de comparacao: v12 SIG (prefill 795-1445ms na janela do R8).

## 2. HIPOTESE DO CANDIDATO B (v1 — esta rodada)
O subconjunto `ggml/src/ggml-hexagon/` (host `ggml-hexagon.cpp` +
`htp/` DSP) do upstream, compilado sobre o CORE SIG, recupera o prefill
rapido mantendo o restante do SIG intacto (Vulkan/OpenCL/reset/loader).

## 3. ALLOWLIST (v1 — substituicao INICIAL, deliberadamente ampla e isolada)
- ggml/src/ggml-hexagon/ (dir INTEIRO: host + CMake + htp/ {kernels, ops,
  queue, iface/dsp complementares}) do upstream.
- JUSTIFICATIVA v1: os internos divergem substancialmente (main.c 2556
  linhas de diff) — sem patch unico candidato; o bloco inteiro e' o
  conjunto coerente minimo por dependencia (iface.idl/opcodes/queues sao
  acoplados entre si).
- Restricao da ordem: "Port apenas htp/ SEM host protocol equivalente
  pode ser incompativel; escolher conjunto coerente por dependencias" —
  dai a v1 incluir o host backend junto; a declaracao explicita:
  MUDA-SE O HOST HEXAGON (nao o core).

## 4. DENYLIST (intocados)
- core (fora de ggml-hexagon): ggml/src/ggml.c, ggml-base/cpu/vulkan/
  opencl, src/llama* — core SIG preservado;
- ggml-vulkan + shaders + patchset Adreno SIG;
- OpenCL loader proprio (android-opencl-loader.cpp) + F3b/guard/
  loadercache;
- request reset, hooks de teste (OFF), Whisper (nao tocado);
- defaults de produto (Vulkan default; c4bf modelo).

## 5. COMPATIBILIDADE (verificacao)
- simbolos do host upstream usados pelo core/backend API (ggml_backend_*,
  ggml_* ops): resolvidos no build (o linker dirara');
- protocolo host<->skel: iface.idl + opcodes/enums + structs/align/
  packing: dentro do dir substituido (coerente por construcao);
- callbacks buffer/sync/graph API: verificar por compilacao + HTP curto
  no device (placement real/fallback).

## 6. BUILD E MANIFESTO
- Copia ISOLADA: /root/sig-smart/llama (backup do tree SIG em
  /root/ggml-hexagon-SIG-backup antes);
- FULL rebuild cohort: host+skel+bindings+APK (nunca swap de .so entre
  conjuntos); toolchain/flags identicos ao v12b (NDK r29 container,
  platform-28, STANDALONE=OFF, PREBUILT_LIB_DIR=android_aarch64);
- Manifesto: sha256 de cada lib/skel/APK + pins + flags; verificacao no
  app separado do arquivo implantado/maps.

## 7. CRITERIO DE SUCESSO/FALHA
- SUCESSO PARCIAL (v1): COMPILA + HTP curto no device com prefill
  ~<=300ms (vs 795-1445 do baseline) + finite/EOG/reset OK;
- FALHA DOCUMENTADA: incompatibilidade concreta (simbolos/contratos) —
  entregar os erros exatos e NAO insistir com hacks de assinatura;
- Alternativa (experimento separado, nao produto): base upstream +
  transposicao allowlist dos patches GPU/reset/loader do SIG.
- Reversao: restaurar /root/ggml-hexagon-SIG-backup; baseline v12
  reconstruivel; artefatos nunca sobrescritos (builds separados).
