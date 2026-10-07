#!/bin/bash
# build-npu-harness.sh — RODA NO SERVIDOR (root@servidor)
# Build do llama.cpp ggml-hexagon para o CPH2747 (arm64-v8a), dentro do
# container oficial snapdragon. Requer: clone em /root/llama-cpp-npu (5e03bdd)
# e a imagem ghcr.io/snapdragon-toolchain/arm64-android:v0.7.
set -euo pipefail

cd /root/llama-cpp-npu

echo "== 1) copiar preset oficial =="
cp docs/backend/snapdragon/CMakeUserPresets.json .

echo "== 2) configure (preset arm64-android-snapdragon-release) =="
docker run --rm --platform linux/amd64 -u root \
  --volume /root/llama-cpp-npu:/workspace \
  ghcr.io/snapdragon-toolchain/arm64-android:v0.7 \
  bash -c '
    set -e
    cd /workspace
    echo "-- env do container --"
    echo "NDK:     $ANDROID_NDK_ROOT"
    echo "HSDK:    $HEXAGON_SDK_ROOT"
    echo "TOOLS:   $HEXAGON_TOOLS_ROOT"
    echo "OPENCL:  $OPENCL_SDK_ROOT"
    cmake --preset arm64-android-snapdragon-release -B build-snapdragon
    echo "== configure OK =="
  '

echo "== 3) build (jobs=20) =="
docker run --rm --platform linux/amd64 -u root \
  --volume /root/llama-cpp-npu:/workspace \
  ghcr.io/snapdragon-toolchain/arm64-android:v0.7 \
  bash -c '
    set -e
    cd /workspace
    cmake --build build-snapdragon -j 20
    echo "== build OK =="
  '

echo "== 4) install (pkg-android) =="
docker run --rm --platform linux/amd64 -u root \
  --volume /root/llama-cpp-npu:/workspace \
  ghcr.io/snapdragon-toolchain/arm64-android:v0.7 \
  bash -c '
    set -e
    cd /workspace
    cmake --install build-snapdragon --prefix pkg-android/llama.cpp
    echo "== install OK =="
    echo "-- conteudo do pkg --"
    ls -la pkg-android/llama.cpp/lib/ | head -n 20
    ls -la pkg-android/llama.cpp/bin/ | head -n 20
  '

echo "== 5) resumo =="
du -sh pkg-android
find pkg-android -name "*.so" | head -n 20
echo "BUILD_ALL_OK"
