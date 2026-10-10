#!/bin/bash
# r42_runner.sh — R42: RUNNER REAL da cadeia completa (SEM mascarar exits!)
# Uso: ./r42_runner.sh <dir> [extra-opts...]
# Registra: comando/cwd/binario produzido/fullSHA/exits (build/existencia/run!)
# stdout e stderr SEPARADOS. Nenhum "printf exit0" inferido!
set -u
DIR="${1:?uso: r42_runner.sh <dir> [--reconstruct-r39]}"
shift
SRC=/root/r42_refs.c
BIN=/root/r42_refs_bin
cd /root || { echo "RUNNER_FAIL cwd!"; exit 90; }
echo "=== R42 RUNNER ==="
echo "CMD: $0 $DIR $*"
echo "CWD: $(pwd)"
echo "SRC: $SRC ($(stat -c%s $SRC 2>/dev/null || echo AUSENTE!) bytes!)"
echo "SRC_SHA: $(sha256sum $SRC 2>/dev/null | cut -c1-40 || echo 'AUSENTE!')"
# 1. BUILD
g++ -std=c++17 -fpermissive -O2 -I/root/llama-cpp-npu/ggml/include -I/root/llama-cpp-npu/ggml/src \
    "$SRC" /root/llama-cpp-npu/ggml/src/ggml-quants.c -lm -o "$BIN" > /tmp/r42_build.log 2>&1
BUILD_EXIT=$?
echo "BUILD_EXIT=$BUILD_EXIT"
grep -aE "error" /tmp/r42_build.log | head -n 3
# 2. EXISTENCIA
test -x "$BIN"; EXIST_EXIT=$?
echo "EXIST_EXIT=$EXIST_EXIT"
if [ "$EXIST_EXIT" -ne 0 ]; then echo "RUNNER_FAIL: binario nao produzido!"; exit 91; fi
echo "BIN: $BIN ($(stat -c%s $BIN) bytes!)"
echo "BIN_SHA: $(sha256sum $BIN | cut -c1-40)"
# 3. RUN (stdout/stderr SEPARADOS!)
timeout 500 "$BIN" "$DIR" "$@" > /tmp/r42_run_out.txt 2> /tmp/r42_run_err.txt
RUN_EXIT=$?
echo "RUN_EXIT=$RUN_EXIT"
echo "STDOUT bytes=$(stat -c%s /tmp/r42_run_out.txt) STDERR bytes=$(stat -c%s /tmp/r42_run_err.txt)"
echo "--- STDOUT (head):"; head -n 12 /tmp/r42_run_out.txt
echo "--- STDERR (head):"; head -n 6 /tmp/r42_run_err.txt
# 4. o RESULTADO consumido (o exit REAL!)
if [ "$RUN_EXIT" -eq 0 ]; then echo "RUNNER_STATUS=OBSERVACAO_VALIDA (LEGACY_FAIL e' classe NUMERICA separada!)"
else echo "RUNNER_STATUS=FALHA_OPERACIONAL_OU_INVALID (exit=$RUN_EXIT — consumido!)"; fi
exit $RUN_EXIT
