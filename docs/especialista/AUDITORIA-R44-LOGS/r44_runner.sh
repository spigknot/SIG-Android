#!/bin/bash
# r44_runner.sh — R44: RUNNER REAL (fullSHA64, raiz autorizada, logs completos por ID!)
# Uso: ./r44_runner.sh <dir> [--reconstruct-r39]
set -u
DIR="${1:?uso: r44_runner.sh <dir> [--reconstruct-r39]}"
shift
SRC=/root/r42_refs.c
BIN=/root/r42_refs_bin
RUN_ID="r44_$(basename "$DIR")_$(date +%s)"
EVID=/root/r44_evid
mkdir -p "$EVID"
cd /root || { echo "RUNNER_FAIL cwd"; exit 90; }
echo "=== R44 RUNNER ==="
echo "RUN_ID: $RUN_ID"
echo "CMD: $0 $DIR $*"
echo "CWD: $(pwd)"
echo "SRC: $SRC ($(stat -c%s $SRC 2>/dev/null || echo AUSENTE) bytes)"
SHA_SRC=$(sha256sum "$SRC" 2>/dev/null | cut -d' ' -f1)
echo "SRC_SHA: ${SHA_SRC:-AUSENTE}"
SHA_SCR=$(sha256sum "$0" | cut -d' ' -f1)
echo "SCRIPT_SHA: $SHA_SCR"
# 1. BUILD
g++ -std=c++17 -fpermissive -O2 -I/root/llama-cpp-npu/ggml/include -I/root/llama-cpp-npu/ggml/src \
    "$SRC" /root/llama-cpp-npu/ggml/src/ggml-quants.c -lm -o "$BIN" > "$EVID/$RUN_ID-build.log" 2>&1
BUILD_EXIT=$?
echo "BUILD_EXIT=$BUILD_EXIT  log: $EVID/$RUN_ID-build.log"
grep -aE "error" "$EVID/$RUN_ID-build.log" | head -n 3
if [ "$BUILD_EXIT" -ne 0 ]; then echo "RUNNER_FAIL: build falhou - run NAO executado"; exit 92; fi
# 2. EXISTENCIA
test -x "$BIN"; EXIST_EXIT=$?
echo "EXIST_EXIT=$EXIST_EXIT"
if [ "$EXIST_EXIT" -ne 0 ]; then echo "RUNNER_FAIL: binario nao produzido"; exit 91; fi
echo "BIN: $BIN ($(stat -c%s "$BIN") bytes)"
SHA_BIN=$(sha256sum "$BIN" | cut -d' ' -f1)
echo "BIN_SHA: $SHA_BIN"
# 3. RUN (stdout/stderr separados, completos e imutaveis por ID!)
timeout 500 "$BIN" "$DIR" "$@" > "$EVID/$RUN_ID-out.txt" 2> "$EVID/$RUN_ID-err.txt"
RUN_EXIT=$?
echo "RUN_EXIT=$RUN_EXIT"
echo "OUT: $EVID/$RUN_ID-out.txt ($(stat -c%s "$EVID/$RUN_ID-out.txt") B) ERR: $EVID/$RUN_ID-err.txt ($(stat -c%s "$EVID/$RUN_ID-err.txt") B)"
echo "--- STDOUT (head; integrais no arquivo acima):"
head -n 12 "$EVID/$RUN_ID-out.txt"
echo "--- STDERR (head; integrais acima):"
head -n 6 "$EVID/$RUN_ID-err.txt"
# 4. RESULTADO consumido
if [ "$RUN_EXIT" -eq 0 ]; then
  echo "RUNNER_STATUS=OBSERVACAO_VALIDA (LEGACY_FAIL e classe NUMERICA separada)"
else
  echo "RUNNER_STATUS=FALHA_OPERACIONAL_OU_INVALID (exit=$RUN_EXIT consumido)"
fi
exit $RUN_EXIT
