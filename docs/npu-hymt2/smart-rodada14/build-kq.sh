#!/bin/bash
# build-kq: compila o candidato KQ (sigcand2) — primeiro o HOST+SKEL
docker run --rm -u root --volume /root/sigcand2:/workspace --platform linux/amd64 ghcr.io/snapdragon-toolchain/arm64-android:v0.7 bash -c '
set -e
export PATH=$PATH:$ANDROID_NDK_ROOT/shader-tools/linux-x86_64
cd /workspace
rm -rf build-kq
cmake -S . -B build-kq -G Ninja \
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
  -DHEXAGON_SDK_ROOT=$HEXAGON_SDK_ROOT -DHEXAGON_TOOLS_ROOT=$HEXAGON_TOOLS_ROOT > /workspace/cfg-kq.log 2>&1 || { echo CFG_FALHOU; tail -n 30 /workspace/cfg-kq.log; exit 1; }
echo CFG_OK
cmake --build build-kq -j 20 --target htp-v81 -- -k 0 > /workspace/skel-kq.log 2>&1 || { echo SKEL_FALHOU; grep -E "error:" /workspace/skel-kq.log | sort -u | head -n 40; exit 1; }
echo SKEL_OK
cmake --build build-kq -j 20 --target llama > /workspace/host-kq.log 2>&1 || { echo HOST_FALHOU; grep -E "error" /workspace/host-kq.log | head -n 30; exit 1; }
echo HOST_OK
ls -la build-kq/bin/*.so build-kq/ggml/src/ggml-hexagon/*.so 2>/dev/null | head -n 12
echo KQ_BUILD_DONE
'
