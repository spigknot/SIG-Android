#!/bin/sh
# RED/GREEN do cache do loader (host; mock de dlfcn; sem device/NDK).
# Versionado em native-dependencies/tests/loader/ (paths ajustados:
# REPO = HERE/../..). Requer g++ (MSYS2 mingw64) no PATH/CXX.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then HERE="$(cygpath -m "$HERE")"; fi
REPO="$(cd "$HERE/../../.." && pwd)"
if command -v cygpath >/dev/null 2>&1; then REPO="$(cygpath -m "$REPO")"; fi
INC_CL="$REPO/app/src/main/cpp/opencl-headers/include"
OUT_BASE="${TMPDIR:-/tmp}"
if command -v cygpath >/dev/null 2>&1; then OUT_BASE="$(cygpath -m "$OUT_BASE")"; fi
CXX_BIN="${CXX:-g++}"

echo "== RED: loader ORIG (dlsym a cada chamada: 2+2=4) =="
"$CXX_BIN" -std=c++17 -O1 -I"$HERE/fake_dlfcn" -I"$INC_CL" \
    -DLOADER_FILE='"'"$HERE/loader_ORIG.cpp"'"' -DEXPECT_DLSYM=4 \
    -o "$OUT_BASE/loader_test_orig" "$HERE/loader_cache_test.cpp"
"$OUT_BASE/loader_test_orig"

echo "== GREEN: loader PATCHED (cache: 1 por simbolo: 1+1=2) =="
"$CXX_BIN" -std=c++17 -O1 -I"$HERE/fake_dlfcn" -I"$INC_CL" \
    -DLOADER_FILE='"'"$HERE/loader_PATCHED.cpp"'"' -DEXPECT_DLSYM=2 \
    -DSIG_LOADER_HOST_TEST -o "$OUT_BASE/loader_test_patched" "$HERE/loader_cache_test.cpp"
"$OUT_BASE/loader_test_patched"

echo "== ENDURECIMENTO (F17): falha transitoria/ausente/concorrencia/chave OFF =="
"$CXX_BIN" -std=c++17 -O1 -pthread -I"$HERE/fake_dlfcn" -I"$INC_CL" -DSIG_LOADER_HOST_TEST \
    -o "$OUT_BASE/loader_hardening" "$HERE/loader_hardening_test.cpp"
"$OUT_BASE/loader_hardening"

echo "== MUTACAO (patched com cache DESLIGADO deve falhar o GREEN esperado) =="
# gera variante mutante: cache ignorado (sempre dlsym) -> deve contagens 4
sed 's/if (cache_on) {/if (false) {/' "$HERE/loader_PATCHED.cpp" > "$OUT_BASE/loader_MUTANT.cpp"
"$CXX_BIN" -std=c++17 -O1 -I"$HERE/fake_dlfcn" -I"$INC_CL" \
    -DSIG_LOADER_HOST_TEST -DLOADER_FILE='"'"$OUT_BASE/loader_MUTANT.cpp"'"' -DEXPECT_DLSYM=2 \
    -o "$OUT_BASE/loader_test_mut" "$HERE/loader_cache_test.cpp"
if "$OUT_BASE/loader_test_mut" >/dev/null 2>&1; then
    echo "  ERRO: mutante passou (teste insensivel)"; exit 1
else
    echo "  OK: mutante falhou como esperado (teste sensivel)"
fi
echo "== resultado: PASS (RED 4 / GREEN 2 / mutacao detectada) =="
