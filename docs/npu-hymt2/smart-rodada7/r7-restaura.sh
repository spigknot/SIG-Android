#!/bin/bash
# r7-restaura: volta o cohort v12 SIG (backup completo) + rebuild + install
set -e
J="C:/npu-probe/app/src/main/jniLibs/arm64-v8a"
BK="C:/llama-npu/rodada2/r7-backup-v12-full"
for f in libllama.so libggml.so libggml-base.so libggml-cpu.so libggml-hexagon.so libggml-htp-v81.so libggml-opencl.so libggml-vulkan.so; do
  cp "$BK/$f" "$J/$f"
done
sha256sum "$J"/lib*.so | cut -c1-16,66-
cd C:/npu-probe && ./gradlew assembleDebug --console=plain > C:/llama-npu/rodada2/logs/probe-rest-v12.log 2>&1
echo "BUILD_RC=$?"
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s 100.108.27.64:5555 install -r -d app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -n 1
echo RESTAURA_OK
