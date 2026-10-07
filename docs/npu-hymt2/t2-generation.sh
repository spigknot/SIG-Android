#!/bin/bash
# t2-generation.sh — PASSO 2 do harness NPU: geracao REAL com o modelo no HTP0
# Uso: bash t2-generation.sh <serial>
set -euo pipefail
SERIAL="${1:?serial}"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
DEST="/data/local/tmp/sig-hymt2-npu-r1"
MODEL="models/Hy-MT2-1.8B-Q4_0.gguf"
PROMPT="Translate to Portuguese: Good morning, how are you?"

echo "=== [t2] GERACAO no HTP0 (n=24, seed 42, temp 0, simple-io) ==="
timeout 900 "$ADB" -s "$SERIAL" shell "cd $DEST && LD_LIBRARY_PATH=./lib ADSP_LIBRARY_PATH=./lib GGML_HEXAGON_VERBOSE=1 ./bin/llama-cli -m $MODEL -dev HTP0 -ngl 99 -n 24 -s 42 --temp 0 --simple-io -p \"$PROMPT\" 2>&1 | tail -n 45" | tr -d '\r'
echo "T2_DONE"
