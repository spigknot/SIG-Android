#!/bin/bash
# t3-bench.sh — PASSO 3 do harness NPU: medicao CURTA CPU vs HTP0 (mesmo modelo)
# Uso: bash t3-bench.sh <serial> [repeticoes]
# Mede pp=64/tg=32, 3 repeticoes por device; rotula explicitamente o backend.
set -euo pipefail
SERIAL="${1:?serial}"
REPS="${2:-3}"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
DEST="/data/local/tmp/sig-hymt2-npu-r1"
MODEL="models/Hy-MT2-1.8B-Q4_0.gguf"

echo "=== [t3] llama-bench CPU (baseline) ==="
timeout 900 "$ADB" -s "$SERIAL" shell "cd $DEST && LD_LIBRARY_PATH=./lib ADSP_LIBRARY_PATH=./lib ./bin/llama-bench -m $MODEL -dev CPU -ngl 0 -p 64 -n 32 -r $REPS 2>&1 | tail -n 12" | tr -d '\r'

echo ""
echo "=== [t3] llama-bench HTP0 (NPU) ==="
timeout 900 "$ADB" -s "$SERIAL" shell "cd $DEST && LD_LIBRARY_PATH=./lib ADSP_LIBRARY_PATH=./lib GGML_HEXAGON_VERBOSE=1 ./bin/llama-bench -m $MODEL -dev HTP0 -ngl 99 -p 64 -n 32 -r $REPS 2>&1 | tail -n 20" | tr -d '\r'
echo "T3_DONE"
