#!/bin/bash
# build-smart12b: SIG vendor exige STANDALONE=OFF (sem tests/examples/tools no vendor)
docker run --rm -u root --volume /root/sig-smart:/workspace --platform linux/amd64 ghcr.io/snapdragon-toolchain/arm64-android:v0.7 bash -c '
set -e
export PATH=$PATH:$ANDROID_NDK_ROOT/shader-tools/linux-x86_64
cd /workspace/llama
sha256sum ggml/src/ggml-vulkan/ggml-vulkan.cpp | cut -c1-16
rm -rf build-smart12
cmake -S . -B build-smart12 -G Ninja \
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
  -DHEXAGON_SDK_ROOT=$HEXAGON_SDK_ROOT -DHEXAGON_TOOLS_ROOT=$HEXAGON_TOOLS_ROOT > /workspace/cfg12.log 2>&1 || { echo CFG_FALHOU; tail -n 30 /workspace/cfg12.log; exit 1; }
echo CFG_OK
cmake --build build-smart12 -j 20 --target llama > /workspace/build12.log 2>&1 || { echo BUILD_FALHOU; tail -n 30 /workspace/build12.log; exit 1; }
echo BUILD_OK
ls -la build-smart12/bin/*.so 2>/dev/null
echo "=== skel ==="
cmake --build build-smart12 -j 20 --target htp-v81 > /workspace/skel12.log 2>&1 && ls -la build-smart12/ggml/src/ggml-hexagon/*.so 2>/dev/null || tail -n 10 /workspace/skel12.log
echo V12_DONE
'
