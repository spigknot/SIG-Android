# BUILD-RECIPE — F3b (transposes non-blocking) — patch minimo de produto

Objetivo: reproduzir/inspecionar o **patch mínimo de produto** do F3b sem a
instrumentação de diagnóstico, com entradas, flags e saídas fixadas por hash.

## 1) Conteúdo do patch (focado; sem instrumentação)

`docs/opencl-hymt2/F3B-PATCH.diff` (sha256 `33e56f1cbd7226c082ea55f8def34372a2ebf64e48b3337614f192ece0043f8d`):

1. **novo arquivo** `app/src/main/cpp/llama/ggml/src/ggml-opencl/sig_transpose_guard.h`
   — guard testável (`sig_transpose_can_skip_wait`): habilita non-blocking
   SOMENTE com fila IN-ORDER confirmada por `clGetCommandQueueInfo`; erro da
   consulta ⇒ modo bloqueante (fail-safe). **Sem cache** (consulta por chamada,
   ~µs cada; elimina a classe "cache do primeiro queue").
2. **`ggml-opencl.cpp`**: `#include "sig_transpose_guard.h"` + a linha, no
   `transpose_2d`:
   `if (blocking && sig_transpose_can_skip_wait(backend_ctx->queue)) blocking = false;`
3. **`android-opencl-loader.cpp`**: shim aditivo `clGetCommandQueueInfo`
   (ponteiro nulo ⇒ `OPENCL_MISSING_ERROR`; nunca chama ponteiro nulo).

Verificação do patch: aplica limpo sobre os arquivos de `HEAD` do repo
(`patch -p1 --dry-run` OK; testado em 03/10).

## 2) Entradas (sha256 completo no MANIFESTO-OPENCL.json)

| Arquivo | sha256 (32 primeiros) | bytes |
|---|---|---|
| `ggml-opencl_prodmin.cpp` (fonte mínima) | `387980bf1b20cbfba009534571d78564` | ver manifesto |
| `llama_jni_prodmin.cpp` (fonte mínima) | `eee6fd4ceb656f18d55e9b8bb3cf6df7` | ver manifesto |
| `sig_transpose_guard.h` | `4dd93679fba83ce85f939c7415fec8a3` | 1.450 |
| `android-opencl-loader.cpp` | `65be756da45c59cc9e797837563c7ac5` | ver manifesto |
| Kernels OpenCL (168 arquivos, hash combinado¹) | `4f6aac1269c62c35336276a89487a012` | — |

¹ hash combinado = sha256 da concatenação ordenada (`path` + conteúdo) de todos
os arquivos sob `ggml-opencl/kernels/`. Kernels NÃO alterados nesta frente.

## 3) Receita de build (idêntica ao fluxo do SIG)

- Toolchain: **NDK 27.2.12479018**, alvo `aarch64-none-linux-android24`,
  `-std=gnu++17`, `-O3 -DNDEBUG`, `-fPIC`.
- Defines: `GGML_OPENCL_EMBED_KERNELS`, `GGML_OPENCL_SOA_Q`,
  `GGML_OPENCL_USE_ADRENO_KERNELS`, `GGML_OPENCL_TARGET_VERSION=300`,
  `GGML_SCHED_MAX_COPIES=4`.
- Comando (árvore ninja do repo):
  `ninja -C native-dependencies/build/llama/arm64-v8a sig_llama`
- Fontes mínimas colocadas nas canônicas (`ggml-opencl.cpp`, `llama_jni.cpp`)
  + o loader atual; **NÃO** mexer no restante do WIP.
- Saída esperada: `native-dependencies/build/llama/arm64-v8a/libsig_llama.so`
  = sha256 **`e14e6a80fb6942a213f96465ca6adc2cc508fdf6e6d2a8178b81312b8cfda2de`**
  (95.626.512 B) — build "prodmin2" (guard sem cache), 0 strings de
  instrumento/`debug.sig.hymt2`.

Observações de lineage:
- `15a548fd...` (95.637.296 B) = prodmin v1 (guard com cache de 1 consulta);
  superado pelo v2 `e14e6a80...`.
- A reprodução BYTE-exata exige o mesmo estado do WIP de base (o patch F3b é
  focado; o WIP contém instrumentação e demais alterações do fork que NÃO
  entram no produto).
- O build "rápido" do Gradle (`assembleDebug`) **não compila** este `.so`;
  o gate Gradle não cobre o nativo.

## 4) Testes

- Guard (host, mock CL, RED-GREEN): `native-dependencies/harness/run_transpose_guard_test.sh`
  (compila `transpose_guard_test.cpp` com `g++`, sem device/NDK).
- Estresse de lifetime e2e (device): `stress_lifetime.py` (ver relatório F4).
- SMOKE do prodmin2: ver `logs-rodada-opencl/prodmin2_smoke.log`.

## 5) Regras de promoção

- Nada aqui é empacotamento/release: o `.so` é **referência de conteúdo**.
- Instalação/ativação em produção exige consentimento específico e o fluxo
  oficial de pacote nativo (`docs/`/scripts do repositório).
