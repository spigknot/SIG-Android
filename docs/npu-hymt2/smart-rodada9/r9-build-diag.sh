#!/bin/bash
# build-diag: cohort SIG + logs DIAGNOSTICOS test-only em ggml-hexagon.cpp
# (clone do servidor; fonte restaurado ao fim). NAO vai a produto.
ssh -o BatchMode=yes root@servidor 'bash -s' <<'REMOTE'
set -e
cd /root/sig-smart/llama
F=ggml/src/ggml-hexagon/ggml-hexagon.cpp
cp -f $F /root/ggml-hexagon.cpp.orig
python3 - <<'EOF'
f = "ggml/src/ggml-hexagon/ggml-hexagon.cpp"
s = open(f).read()
# 1) log dos opt efetivos apos a leitura (apos a linha do opt_vmem)
anchor = "opt_vmem      = str_vmem     ? strtoul(str_vmem, NULL, 0) * MiB       : opt_vmem;"
assert anchor in s
diag1 = anchor + '\n    GGML_LOG_ERROR("HEXINIT_DIAG: nhvx=%zu nhmx=%d mm_select=%d opbatch=%zu opqueue=%zu vmem=%zu\\n", opt_nhvx, opt_nhmx, opt_mm_select, opt_opbatch, opt_opqueue, opt_vmem);'
s = s.replace(anchor, diag1, 1)
# 2) log antes do htp_iface_start
anchor2 = "err = htp_iface_start(this->handle, dev_id, this->queue_id, opt_nhvx, opt_nhmx, this->max_vmem);"
assert anchor2 in s
diag2 = 'GGML_LOG_ERROR("IFACE_START_DIAG: arg_nthreads=%zu n_hvx=%u n_hmx=%u dev=%u queue=%u vmem=%zu\\n", opt_nhvx, this->n_hvx, this->n_hmx, dev_id, this->queue_id, this->max_vmem);\n    ' + anchor2
s = s.replace(anchor2, diag2, 1)
open(f, "w").write(s)
print("DIAG_APPLIED")
EOF
docker run --rm -u root --volume /root/sig-smart:/workspace --platform linux/amd64 ghcr.io/snapdragon-toolchain/arm64-android:v0.7 bash -c '
set -e
export PATH=$PATH:$ANDROID_NDK_ROOT/shader-tools/linux-x86_64
cd /workspace/llama
rm -rf build-diag
cmake -S . -B build-diag -G Ninja \
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
  -DHEXAGON_SDK_ROOT=$HEXAGON_SDK_ROOT -DHEXAGON_TOOLS_ROOT=$HEXAGON_TOOLS_ROOT > /workspace/cfg-diag.log 2>&1 || { echo CFG_FALHOU; tail -n 20 /workspace/cfg-diag.log; exit 1; }
echo CFG_OK
cmake --build build-diag -j 20 --target llama > /workspace/build-diag.log 2>&1 || { echo BUILD_FALHOU; tail -n 25 /workspace/build-diag.log; exit 1; }
echo BUILD_OK
cmake --build build-diag -j 20 --target htp-v81 > /workspace/skel-diag.log 2>&1 || { echo SKEL_FALHOU; tail -n 10 /workspace/skel-diag.log; exit 1; }
echo SKEL_OK
ls -la build-diag/bin/*.so build-diag/ggml/src/ggml-hexagon/*.so 2>/dev/null
'
cp -f /root/ggml-hexagon.cpp.orig ggml/src/ggml-hexagon/ggml-hexagon.cpp
echo "FONTE_RESTAURADO"
echo DIAG_DONE
REMOTE
