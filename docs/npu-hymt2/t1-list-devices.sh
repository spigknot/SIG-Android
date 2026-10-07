#!/bin/bash
# t1-list-devices.sh — PASSO 1 do harness NPU: enumerar devices do llama.cpp
# (HTP/OpenCL/CPU) e capturar o ambiente (propriedades + sessao).
# Uso: bash t1-list-devices.sh <serial>
set -euo pipefail
SERIAL="${1:?serial}"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
DEST="/data/local/tmp/sig-hymt2-npu-r1"

echo "=== [t1] ambiente do device ==="
"$ADB" -s "$SERIAL" shell "getprop ro.soc.model; getprop ro.board.platform; ls /dev/fastrpc-* 2>/dev/null | head -n 5"

echo ""
echo "=== [t1] llama-cli --help (procura a flag de devices) ==="
"$ADB" -s "$SERIAL" shell "cd $DEST && LD_LIBRARY_PATH=$DEST/lib ./bin/llama-cli --help 2>&1 | grep -iE 'device|hexagon|npu|backend' | head -n 15"

echo ""
echo "=== [t1] lista de devices (--list-devices) ==="
"$ADB" -s "$SERIAL" shell "cd $DEST && LD_LIBRARY_PATH=$DEST/lib ./bin/llama-cli --list-devices 2>&1 | head -n 40" || \
"$ADB" -s "$SERIAL" shell "cd $DEST && LD_LIBRARY_PATH=$DEST/lib ./bin/llama 2>&1 | head -n 30"

echo ""
echo "=== [t1] GGML_HEXAGON_VERBOSE=1 sessao (dry: --version/list) ==="
"$ADB" -s "$SERIAL" shell "cd $DEST && GGML_HEXAGON_VERBOSE=1 LD_LIBRARY_PATH=$DEST/lib ./bin/llama-cli --list-devices 2>&1 | tail -n 30"
echo "T1_DONE"
