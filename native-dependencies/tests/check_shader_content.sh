#!/bin/bash
# Teste de CONTEUDO do blob Vulkan (item F): verifica que o build atual
# corresponde ao conteudo ESPERADO do patch (nao apenas "mudou"), e que o
# build responde a alteracao dos includes (.glsl). Restaura tudo por sha.
#
# Como: compila os .comp afetados (1) com as fontes PATCHED atuais e
# (2) com as fontes ORIGINAIS guardadas em vkdbg/*.orig; exige:
#    blob_do_build == patched  E  blob_do_build != original
# para os dois caminhos (vec: dequant_funcs.glsl; mm: mul_mm_funcs.glsl).
# Nao altera produto: as trocas temporarias sao restauradas e conferidas
# por sha256; qualquer falha restaura antes de sair.
set -u
export PATH="/c/msys64/mingw64/bin:$PATH"
ROOT=D:/Projetos/SIG
SRC="$ROOT/app/src/main/cpp/llama/ggml/src/ggml-vulkan/vulkan-shaders"
B="$ROOT/native-dependencies/build/llama/arm64-v8a"
GENEXE="$B/Release/vulkan-shaders-gen.exe"
GLSLC="$LOCALAPPDATA/Android/Sdk/ndk/27.2.12479018/shader-tools/windows-x86_64/glslc.exe"
GENCPP="$B/llama-build/ggml/src/ggml-vulkan"
BUILDSPV="$GENCPP/vulkan-shaders.spv"
# fixtures autocontidas no repo (fallback para o scratch do executor antigo)
BK="$ROOT/native-dependencies/harness/fixtures"
[ -f "$BK/mul_mm_funcs.glsl.orig" ] || BK="C:/Users/Gustavo/AppData/Local/hermes/cache/scratch/vkdbg"
T="C:/Users/Gustavo/AppData/Local/hermes/cache/scratch/spv_content_check"
rm -rf "$T"; mkdir -p "$T/p1" "$T/o1" "$T/p2" "$T/o2"
sha() { sha256sum "$1" 2>/dev/null | cut -c1-24; }
RC=0

[ -x "$GENEXE" ] || { echo "FALHA: generator nao existe: $GENEXE"; exit 2; }
[ -x "$GLSLC" ]  || { echo "FALHA: glslc nao existe"; exit 2; }

S_DQ0=$(sha "$SRC/dequant_funcs.glsl"); S_MM0=$(sha "$SRC/mul_mm_funcs.glsl")
V_DQ=$(sha "$BUILDSPV/mul_mat_vec_q8_0_f32_f32.spv")
V_MM=$(sha "$BUILDSPV/matmul_q8_0_f32_f16acc.spv")
C1_0=$(stat -c%Y "$GENCPP/mul_mat_vec.comp.cpp"); C2_0=$(stat -c%Y "$GENCPP/mul_mm.comp.cpp")
echo "== TESTE DE CONTEUDO DO BLOB (esperado aplicado) =="
echo "-- fontes patched: dequant=$S_DQ0 mul_mm=$S_MM0"
echo "-- blob do build : vec=$V_DQ mm=$V_MM"

# 1) dependencia: tocar os .glsl exige regeracao dos .cpp
touch "$SRC/dequant_funcs.glsl" "$SRC/mul_mm_funcs.glsl"
if ! ninja -C "$B" ggml-vulkan >/dev/null 2>&1; then echo "  FAIL: ninja quebrou"; exit 1; fi
C1_1=$(stat -c%Y "$GENCPP/mul_mat_vec.comp.cpp"); C2_1=$(stat -c%Y "$GENCPP/mul_mm.comp.cpp")
if [ "$C1_0" = "$C1_1" ]; then echo "  FAIL: mul_mat_vec.comp.cpp NAO regenerou (dep. nao rastreada)"; RC=1; fi
if [ "$C2_0" = "$C2_1" ]; then echo "  FAIL: mul_mm.comp.cpp NAO regenerou (dep. nao rastreada)"; RC=1; fi
echo "-- dependencia: cpp regenerados [ok se sem FAIL acima]"

# 2) determinismo: mesma fonte -> mesmo blob
if [ "$(sha "$BUILDSPV/mul_mat_vec_q8_0_f32_f32.spv")" != "$V_DQ" ]; then echo "  FAIL: blob vec mudou sem fonte mudar"; RC=1; fi
if [ "$(sha "$BUILDSPV/matmul_q8_0_f32_f16acc.spv")" != "$V_MM" ]; then echo "  FAIL: blob mm mudou sem fonte mudar"; RC=1; fi
echo "-- determinismo: blobs identicos apos recompilacao [ok]"

# 3) compila PATCHED (fontes atuais)
(cd "$GENCPP" && "$GENEXE" --glslc "$GLSLC" --source "$SRC/mul_mat_vec.comp" --output-dir "$T/p1" \
   --target-hpp "$T/p1/h.hpp" --target-cpp "$T/p1/o.cpp" >"$T/p1/gen.log" 2>&1)
(cd "$GENCPP" && "$GENEXE" --glslc "$GLSLC" --source "$SRC/mul_mm.comp" --output-dir "$T/p2" \
   --target-hpp "$T/p2/h.hpp" --target-cpp "$T/p2/o.cpp" >"$T/p2/gen.log" 2>&1)
P_VEC=$(sha "$T/p1/mul_mat_vec_q8_0_f32_f32.spv"); P_MM=$(sha "$T/p2/matmul_q8_0_f32_f16acc.spv")
echo "-- compilado patched : vec=$P_VEC mm=$P_MM"

# 4) compila ORIGINAL (troca temporaria + restauracao conferida)
cp "$SRC/dequant_funcs.glsl" "$T/dequant.patched"; cp "$SRC/mul_mm_funcs.glsl" "$T/mm.patched"
restaura() {
  cp "$T/dequant.patched" "$SRC/dequant_funcs.glsl"; cp "$T/mm.patched" "$SRC/mul_mm_funcs.glsl"
  [ "$(sha "$SRC/dequant_funcs.glsl")" = "$S_DQ0" ] || { echo "  ERRO FATAL: dequant nao voltou ao sha"; exit 9; }
  [ "$(sha "$SRC/mul_mm_funcs.glsl")" = "$S_MM0" ] || { echo "  ERRO FATAL: mul_mm nao voltou ao sha"; exit 9; }
}
trap restaura EXIT

cp "$BK/dequant_funcs.glsl.orig" "$SRC/dequant_funcs.glsl"
(cd "$GENCPP" && "$GENEXE" --glslc "$GLSLC" --source "$SRC/mul_mat_vec.comp" --output-dir "$T/o1" \
   --target-hpp "$T/o1/h.hpp" --target-cpp "$T/o1/o.cpp" >"$T/o1/gen.log" 2>&1)
cp "$T/dequant.patched" "$SRC/dequant_funcs.glsl"
cp "$BK/mul_mm_funcs.glsl.orig" "$SRC/mul_mm_funcs.glsl"
(cd "$GENCPP" && "$GENEXE" --glslc "$GLSLC" --source "$SRC/mul_mm.comp" --output-dir "$T/o2" \
   --target-hpp "$T/o2/h.hpp" --target-cpp "$T/o2/o.cpp" >"$T/o2/gen.log" 2>&1)
restaura; trap - EXIT
O_VEC=$(sha "$T/o1/mul_mat_vec_q8_0_f32_f32.spv"); O_MM=$(sha "$T/o2/matmul_q8_0_f32_f16acc.spv")
echo "-- compilado original: vec=$O_VEC mm=$O_MM"

# 5) veredito
[ "$V_DQ" = "$P_VEC" ] && echo "-- vec: build == patched [ok]" || { echo "  FAIL: build vec != patched"; RC=1; }
[ "$V_DQ" != "$O_VEC" ] && echo "-- vec: patched != original [ok]" || { echo "  FAIL: vec patched==original (patch nao muda o blob!)"; RC=1; }
[ "$V_MM" = "$P_MM" ] && echo "-- mm : build == patched [ok]" || { echo "  FAIL: build mm != patched"; RC=1; }
[ "$V_MM" != "$O_MM" ] && echo "-- mm : patched != original [ok]" || { echo "  FAIL: mm patched==original"; RC=1; }

[ "$RC" -eq 0 ] && echo "RESULTADO: PASS (blob esperado aplicado nos dois caminhos)" || echo "RESULTADO: FAIL"
exit $RC
