#!/bin/sh
# Build+run do teste nativo do guard F3b (host; mock CL; sem device/NDK).
# Uso: ./run_transpose_guard_test.sh   (exit != 0 = falha)
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then   # MSYS: g++ nativo precisa de D:/...
    HERE="$(cygpath -m "$HERE")"
fi
INC="$HERE/../../app/src/main/cpp/llama/ggml/src/ggml-opencl"
OUT_BASE="${TMPDIR:-/tmp}"
if command -v cygpath >/dev/null 2>&1; then
    OUT_BASE="$(cygpath -m "$OUT_BASE")"
fi
OUT="$OUT_BASE/sig_transpose_guard_test"
CXX_BIN="${CXX:-g++}"

echo "== 1) GREEN: teste contra o header REAL do produto =="
"$CXX_BIN" -std=c++17 -Wall -Wextra -Werror -I"$INC" -o "$OUT" "$HERE/transpose_guard_test.cpp"
"$OUT"

echo "== 2) MUTACAO: copia do header com guard QUEBRADO deve FALHAR =="
MUT="$OUT_BASE/sig_guard_mut"
rm -rf "$MUT"
mkdir -p "$MUT"
sed 's/return ((ooo == 0) ? 1 : 0) == 1;/return true;  \/\/ MUTANTE/' \
    "$INC/sig_transpose_guard.h" > "$MUT/sig_transpose_guard.h"
if grep -q 'MUTANTE' "$MUT/sig_transpose_guard.h"; then
    "$CXX_BIN" -std=c++17 -Wall -Wextra -I"$MUT" -I"$INC" -o "$OUT.mut" "$HERE/transpose_guard_test.cpp"
    if "$OUT.mut" > "$OUT.mut.log" 2>&1; then
        echo "  ERRO: o mutante passou (teste NAO detecta regressao!)"; exit 1
    else
        echo "  OK: mutante falhou como esperado (teste sensivel a regressao)"
        grep -E "FAIL|resultado" "$OUT.mut.log" | head -n 4 || true
    fi
else
    echo "  ERRO: mutacao nao aplicada (padrao do header mudou?)"; exit 1
fi
echo "== resultado final: PASS (GREEN ok + mutante detectado) =="
