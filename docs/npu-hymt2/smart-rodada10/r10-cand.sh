#!/bin/bash
# r10-cand: CANDIDATO B = core SIG + tree ggml-hexagon (host+htp) do UPSTREAM
# Copia isolada; backup do tree SIG; build cohort completo; logs persistentes.
ssh -o BatchMode=yes root@servidor 'bash -s' <<'REMOTE'
set -e
cd /root/sig-smart/llama
echo "=== backup do tree SIG ==="
if [ ! -d /root/ggml-hexagon-SIG-backup ]; then
  cp -r ggml/src/ggml-hexagon /root/ggml-hexagon-SIG-backup
fi
echo "=== deploy do tree upstream (allowlist inicial: dir inteiro htp+host) ==="
rm -rf ggml/src/ggml-hexagon
cp -r /root/llama-cpp-npu/ggml/src/ggml-hexagon ggml/src/ggml-hexagon
echo "=== build candidato (cohort completo) ==="
docker run --rm -u root --volume /root/sig-smart:/workspace --platform linux/amd64 ghcr.io/snapdragon-toolchain/arm64-android:v0.7 bash -c '
set -e
export PATH=$PATH:$ANDROID_NDK_ROOT/shader-tools/linux-x86_64
cd /workspace/llama
rm -rf build-cand
cmake -S . -B build-cand -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_STANDALONE=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_TOOLS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF \
  -DGGML_HEXAGON=ON -DGGML_OPENCL=ON -DGGML_VULKAN=ON \
  -DGGML_OPENMP=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF \
  -DPREBUILT_LIB_DIR=android_aarch64 \
  -DSPIRV-Headers_DIR=/workspace/spirv-cfg/SPIRV-Headers \
  -DVulkan_INCLUDE_DIR=/workspace/vulkan-headers/include \
  -DCMAKE_CXX_FLAGS="-I/workspace/vulkan-headers/include -I/workspace/spirv-headers/include" \
  -DHEXAGON_SDK_ROOT=$HEXAGON_SDK_ROOT -DHEXAGON_TOOLS_ROOT=$HEXAGON_TOOLS_ROOT > /workspace/cfg-cand.log 2>&1 || { echo CFG_FALHOU; tail -n 40 /workspace/cfg-cand.log; exit 1; }
echo CFG_OK
cmake --build build-cand -j 20 --target llama > /workspace/build-cand.log 2>&1 || { echo BUILD_FALHOU; tail -n 60 /workspace/build-cand.log; exit 1; }
echo BUILD_OK
cmake --build build-cand -j 20 --target htp-v81 > /workspace/skel-cand.log 2>&1 || { echo SKEL_FALHOU; tail -n 20 /workspace/skel-cand.log; exit 1; }
echo SKEL_OK
ls -la build-cand/bin/*.so build-cand/ggml/src/ggml-hexagon/*.so 2>/dev/null
'
echo CAND_DONE
REMOTE
