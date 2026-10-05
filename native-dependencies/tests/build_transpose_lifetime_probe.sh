#!/bin/sh
# Build do transpose_lifetime_probe (host -> aarch64, NDK) para execucao NO
# DEVICE. Execucao no alvo pendente de permissao; este script so' compila.
# Uso: ./build_transpose_lifetime_probe.sh [--tolerante]
#   (--tolerante: nao falha em warnings de link; padrao: estrito)
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then
    HERE="$(cygpath -m "$HERE")"
fi
REPO="$(cd "$HERE/../.." && pwd)"
if command -v cygpath >/dev/null 2>&1; then
    REPO="$(cygpath -m "$REPO")"
fi

NDK="${ANDROID_NDK_HOME:-C:/Users/Gustavo/AppData/Local/Android/Sdk/ndk/27.2.12479018}"
TC="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
CXX="$TC/bin/clang++.exe"
SYSROOT="$TC/sysroot"
B="$REPO/native-dependencies/build/llama/arm64-v8a"
OUT_DIR="$HERE/arm64-v8a"

INC="-I$REPO/app/src/main/cpp/llama/ggml/include -I$REPO/app/src/main/cpp/llama/ggml/src -I$REPO/app/src/main/cpp/opencl-headers/include"
LIBS="$B/llama-build/ggml/src/libggml.a $B/llama-build/ggml/src/libggml-base.a $B/llama-build/ggml/src/libggml-cpu.a $B/llama-build/ggml/src/ggml-opencl/libggml-opencl.a $B/llama-build/ggml/src/ggml-vulkan/libggml-vulkan.a $SYSROOT/usr/lib/aarch64-linux-android/28/libvulkan.so"

echo "== build transpose_lifetime_probe (aarch64; execucao no device PENDENTE) =="
"$CXX" --target=aarch64-none-linux-android24 --sysroot="$SYSROOT" \
    -O2 -fPIC -fvisibility=hidden \
    $INC \
    -o "$OUT_DIR/transpose_lifetime_probe" \
    "$HERE/transpose_lifetime_probe.cpp" \
    $LIBS \
    -llog -ldl -lm -static-libstdc++

echo "OK: $OUT_DIR/transpose_lifetime_probe"
"$TC/bin/llvm-readelf.exe" -d "$OUT_DIR/transpose_lifetime_probe" 2>/dev/null | grep NEEDED | head -n 8 || true
sha256sum "$OUT_DIR/transpose_lifetime_probe" 2>/dev/null || true
