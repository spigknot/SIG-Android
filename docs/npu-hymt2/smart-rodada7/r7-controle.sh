#!/bin/bash
# r7-controle: cohort UPSTREAM completo (v11) no lugar do v12 — CONTROLE da
# causa (mesmo probe/device/janela). Restauracao fica no script r7-restaura.
set -e
J="C:/npu-probe/app/src/main/jniLibs/arm64-v8a"
B="/root/llama-cpp-npu/build-smart"
BK="C:/llama-npu/rodada2/r7-backup-v12-full"
mkdir -p "$BK"
echo "=== backup v12 (8 libs + skel) ==="
for f in libllama.so libggml.so libggml-base.so libggml-cpu.so libggml-hexagon.so libggml-htp-v81.so libggml-opencl.so libggml-vulkan.so; do
  cp "$J/$f" "$BK/$f"
done
sha256sum "$J"/lib*.so | cut -c1-16,66- | head -n 9
echo "=== baixar cohort v11 (upstream) ==="
scp -q root@servidor:$B/bin/libllama.so root@servidor:$B/bin/libggml.so root@servidor:$B/bin/libggml-base.so root@servidor:$B/bin/libggml-cpu.so root@servidor:$B/bin/libggml-hexagon.so root@servidor:$B/bin/libggml-opencl.so root@servidor:$B/bin/libggml-vulkan.so "$J/"
scp -q root@servidor:$B/ggml/src/ggml-hexagon/libggml-htp-v81.so "$J/"
sha256sum "$J"/lib*.so | cut -c1-16,66- | head -n 9
echo CONTROLE_DEPLOY_OK
