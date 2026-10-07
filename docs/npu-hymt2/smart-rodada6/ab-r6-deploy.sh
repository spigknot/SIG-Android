#!/bin/bash
# AB-R6: hibrido {v12 resto + hexagon v11 upstream} para o A/B do prefill HTP
set -e
J="C:/npu-probe/app/src/main/jniLibs/arm64-v8a"
B="/root/llama-cpp-npu/build-smart"
BK="C:/llama-npu/rodada2/ab-r6-backup-v12"
mkdir -p "$BK"
echo "=== backup do v12 atual (hexagon+skel) ==="
cp "$J/libggml-hexagon.so" "$BK/libggml-hexagon.so.v12"
cp "$J/libggml-htp-v81.so" "$BK/libggml-htp-v81.so.v12"
sha256sum "$J/libggml-hexagon.so" "$J/libggml-htp-v81.so" | cut -c1-16,66-
echo "=== baixar v11 (upstream) ==="
scp -q root@servidor:$B/bin/libggml-hexagon.so "$J/libggml-hexagon.so"
scp -q root@servidor:$B/ggml/src/ggml-hexagon/libggml-htp-v81.so "$J/libggml-htp-v81.so"
sha256sum "$J/libggml-hexagon.so" "$J/libggml-htp-v81.so" | cut -c1-16,66-
echo AB_R6_DEPLOY_OK
