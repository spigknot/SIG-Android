#!/usr/bin/env python3
# r51_repack.py — R51 §3.3: recomputar repack_q6_K_tiled (host cohort R50) sobre o
# canonweights R50 e comparar com o tiled_raw R39 (equivalencia do encoder!)
import re
S = "D:/Projetos/SIG/docs/npu-hymt2/smart-rodada51/sources"
LOCAL = "D:/Projetos/SIG/app/src/main/cpp/llama/ggml/src"
t = open(f"{S}/ggml-hexagon.cpp", encoding="utf-8", errors="replace").read()

def grab(src, pat, tag):
    m = re.search(pat, src, re.S)
    assert m, tag
    return m.group(0)

q6get = grab(t, r"static inline uint8_t q6_K_get_quant\(const block_q6_K \* b, int e\) \{.*?\n\}", "q6get")
repack = grab(t, r"static void repack_q6_K_tiled\(const ggml_tensor \* t, const void \* data, size_t offset, size_t size\) \{.*?\n\}", "repack")
hc = open(f"{S}/htp/hex-common.h", encoding="utf-8", errors="replace").read()
hru = grab(hc, r"static inline uint32_t hex_round_up\(uint32_t n, uint32_t m\) \{.*?\n\}", "hru")
gci = open(f"{LOCAL}/ggml-impl.h", encoding="utf-8", errors="replace").read()
fp16to32 = grab(gci, r"static inline float ggml_compute_fp16_to_fp32\(ggml_fp16_t h\) \{.*?\n\}", "f16to32")
fp32to16 = grab(gci, r"static inline ggml_fp16_t ggml_compute_fp32_to_fp16\(float f\) \{.*?\n\}", "f32to16")
bits_to32 = grab(gci, r"static inline float fp32_from_bits\(uint32_t w\) \{.*?\n\}", "fp32_from_bits")
f32_to_bits = grab(gci, r"static inline uint32_t fp32_to_bits\(float f\) \{.*?\n\}", "fp32_to_bits")
gc = open(f"{LOCAL}/ggml-common.h", encoding="utf-8", errors="replace").read()
block = "\n".join(gc.splitlines()[369:375])
print("q6get", len(q6get), "| repack", len(repack), "| hru", len(hru), "| fp16", len(fp16to32), len(fp32to16), "| block", len(block))

head = r'''#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <math.h>
#define QK_K 256
#define HTP_MM_WEIGHT_TILE_SIZE_Q6_K 896
#define GGML_ASSERT(x) do { if (!(x)) { fprintf(stderr, "ASSERT %s\n", #x); exit(3); } } while (0)
#define GGML_UNUSED(x) (void)(x)
using ggml_half = uint16_t;
using ggml_fp16_t = uint16_t;
struct ggml_tensor { void * data; int64_t ne[4]; };
#define GGML_FP16_TO_FP32(x) ggml_compute_fp16_to_fp32(x)
#define GGML_FP32_TO_FP16(x) ggml_compute_fp32_to_fp16(x)
'''

mid = r'''
int main(int argc, char ** argv) {
    if (argc != 4) { fprintf(stderr, "uso: repack <canonweights> <saida_tiled> <ne0>\n"); return 64; }
    FILE * f = fopen(argv[1], "rb");
    if (!f) { perror("canon"); return 2; }
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
    const int64_t ne0 = atoll(argv[3]);
    const int64_t ne1 = sz / (ne0 / QK_K * 210);
    printf("canonweights sz=%ld => ne=[%lld,%lld]\n", sz, (long long) ne0, (long long) ne1);
    uint8_t * src = (uint8_t *) malloc(sz);
    if (fread(src, 1, sz, f) != (size_t) sz) { fprintf(stderr, "read curto\n"); return 2; }
    fclose(f);
    const int64_t n_col_tiles = ((ne1 + 31) / 32);
    const int64_t n_k_tiles = ((ne0 + 31) / 32);
    size_t out_sz = (size_t) n_col_tiles * n_k_tiles * 896;
    uint8_t * dst = (uint8_t *) malloc(out_sz);
    struct ggml_tensor T; memset(&T, 0, sizeof(T));
    T.data = dst; T.ne[0] = ne0; T.ne[1] = ne1; T.ne[2] = 1; T.ne[3] = 1;
    repack_q6_K_tiled(&T, src, 0, (size_t) sz);
    FILE * g = fopen(argv[2], "wb");
    if (!g) { perror("out"); return 2; }
    if (fwrite(dst, 1, out_sz, g) != out_sz) { fprintf(stderr, "write falhou\n"); return 2; }
    fclose(g);
    printf("tiled escrito: %zu bytes\n", out_sz);
    return 0;
}
'''
out = head + bits_to32 + "\n" + f32_to_bits + "\n" + fp16to32 + "\n" + fp32to16 + "\n" + block + "\n" + hru + "\n\n" + q6get + "\n\n" + repack + "\n" + mid
open("C:/llama-npu/rodada2/r51_repack.cpp", "w", encoding="utf-8", newline="\n").write(out)
print("r51_repack.cpp:", len(out), "bytes")
