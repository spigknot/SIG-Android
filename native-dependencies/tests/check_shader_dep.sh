#!/bin/bash
# Teste AUTOMATIZADO de build stale (item C): falha se alterar um .glsl NAO
# regenerar o blob correspondente. Fica no repo como vacina permanente.
#   uso: bash check_shader_dep.sh        rc=0 = build responde ao .glsl
# Nao mexe em fonte: apenas 'toca' o .glsl e compara mtime+hash do blob.
set -u
export PATH="/c/msys64/mingw64/bin:$PATH"
ROOT=D:/Projetos/SIG
B="$ROOT/native-dependencies/build/llama/arm64-v8a"
GEN="$B/llama-build/ggml/src/ggml-vulkan"
SPV="$GEN/vulkan-shaders.spv"
SRC="$ROOT/app/src/main/cpp/llama/ggml/src/ggml-vulkan/vulkan-shaders"

check() {  # $1 = .comp ; $2 = .glsl ; $3 = variante .spv
  local comp="$1" glsl="$2" var="$3"
  local blob="$GEN/$comp.cpp"
  local m0 h0
  m0=$(stat -c%Y "$blob"); h0=$(sha256sum "$SPV/$var" | cut -c1-16)
  touch "$SRC/$glsl"
  if ! ninja -C "$B" ggml-vulkan > /dev/null 2>&1; then
    echo "  FALHA: build quebrou ao regenerar $comp"; return 1
  fi
  local m1 h1
  m1=$(stat -c%Y "$blob"); h1=$(sha256sum "$SPV/$var" | cut -c1-16)
  if [ "$m0" = "$m1" ]; then
    echo "  FALHA: $glsl alterado mas $comp.cpp NAO regenerou (build stale)"; return 1
  fi
  if [ "$h1" = "$h0" ] && [ "$m0" != "$m1" ]; then
    echo "  ok   $glsl -> $comp.cpp regenerou; .spv $var inalterado (h1=$h1) [conteudo identico: normal]"
    return 0
  fi
  echo "  ok   $glsl -> $comp.cpp regenerou; $var mudou $h0 -> $h1"
  return 0
}

echo "== VACINA AUTOMATICA: build de shader responde a alteracao do .glsl? =="
RC=0
check "mul_mat_vec.comp" "dequant_funcs.glsl" "mul_mat_vec_q8_0_f32_f32.spv" || RC=1
check "mul_mm.comp"      "mul_mm_funcs.glsl"   "matmul_q8_0_f32_f16acc.spv" || RC=1
echo "RESULTADO: $([ $RC -eq 0 ] && echo 'PASS (build nao fica stale)' || echo 'FAIL (build stale)')"
exit $RC