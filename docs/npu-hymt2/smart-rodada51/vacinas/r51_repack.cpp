#include <stdio.h>
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
static inline float fp32_from_bits(uint32_t w) {
    union {
        uint32_t as_bits;
        float as_value;
    } fp32;
    fp32.as_bits = w;
    return fp32.as_value;
}
static inline uint32_t fp32_to_bits(float f) {
    union {
        float as_value;
        uint32_t as_bits;
    } fp32;
    fp32.as_value = f;
    return fp32.as_bits;
}
static inline float ggml_compute_fp16_to_fp32(ggml_fp16_t h) {
    const uint32_t w = (uint32_t) h << 16;
    const uint32_t sign = w & UINT32_C(0x80000000);
    const uint32_t two_w = w + w;

    const uint32_t exp_offset = UINT32_C(0xE0) << 23;
#if (defined(__STDC_VERSION__) && (__STDC_VERSION__ >= 199901L) || defined(__GNUC__) && !defined(__STRICT_ANSI__)) && (!defined(__cplusplus) || __cplusplus >= 201703L)
    const float exp_scale = 0x1.0p-112f;
#else
    const float exp_scale = fp32_from_bits(UINT32_C(0x7800000));
#endif
    const float normalized_value = fp32_from_bits((two_w >> 4) + exp_offset) * exp_scale;

    const uint32_t magic_mask = UINT32_C(126) << 23;
    const float magic_bias = 0.5f;
    const float denormalized_value = fp32_from_bits((two_w >> 17) | magic_mask) - magic_bias;

    const uint32_t denormalized_cutoff = UINT32_C(1) << 27;
    const uint32_t result = sign |
        (two_w < denormalized_cutoff ? fp32_to_bits(denormalized_value) : fp32_to_bits(normalized_value));
    return fp32_from_bits(result);
}
static inline ggml_fp16_t ggml_compute_fp32_to_fp16(float f) {
#if (defined(__STDC_VERSION__) && (__STDC_VERSION__ >= 199901L) || defined(__GNUC__) && !defined(__STRICT_ANSI__)) && (!defined(__cplusplus) || __cplusplus >= 201703L)
    const float scale_to_inf = 0x1.0p+112f;
    const float scale_to_zero = 0x1.0p-110f;
#else
    const float scale_to_inf = fp32_from_bits(UINT32_C(0x77800000));
    const float scale_to_zero = fp32_from_bits(UINT32_C(0x08800000));
#endif
    float base = (fabsf(f) * scale_to_inf) * scale_to_zero;

    const uint32_t w = fp32_to_bits(f);
    const uint32_t shl1_w = w + w;
    const uint32_t sign = w & UINT32_C(0x80000000);
    uint32_t bias = shl1_w & UINT32_C(0xFF000000);
    if (bias < UINT32_C(0x71000000)) {
        bias = UINT32_C(0x71000000);
    }

    base = fp32_from_bits((bias >> 1) + UINT32_C(0x07800000)) + base;
    const uint32_t bits = fp32_to_bits(base);
    const uint32_t exp_bits = (bits >> 13) & UINT32_C(0x00007C00);
    const uint32_t mantissa_bits = bits & UINT32_C(0x00000FFF);
    const uint32_t nonsign = exp_bits + mantissa_bits;
    return (sign >> 16) | (shl1_w > UINT32_C(0xFF000000) ? UINT16_C(0x7E00) : nonsign);
}
typedef struct {
    uint8_t ql[QK_K/2];      // quants, lower 4 bits
    uint8_t qh[QK_K/4];      // quants, upper 2 bits
    int8_t  scales[QK_K/16]; // scales, quantized with 8 bits
    ggml_half d;             // super-block scale
} block_q6_K;
static inline uint32_t hex_round_up(uint32_t n, uint32_t m) {
    return m * ((n + m - 1) / m);
}

static inline uint8_t q6_K_get_quant(const block_q6_K * b, int e) {
    const int c = e / 128;
    const int w = e % 128;
    const int g = w / 32;
    const int l = w % 32;
    const uint8_t * ql = b->ql + c * 64;
    const uint8_t * qh = b->qh + c * 32;
    uint8_t lo, hi;
    switch (g) {
        case 0:  lo = ql[l]      & 0xF; hi = (qh[l] >> 0) & 3; break;
        case 1:  lo = ql[l + 32] & 0xF; hi = (qh[l] >> 2) & 3; break;
        case 2:  lo = ql[l]      >> 4;  hi = (qh[l] >> 4) & 3; break;
        default: lo = ql[l + 32] >> 4;  hi = (qh[l] >> 6) & 3; break;
    }
    return (uint8_t) (lo | (hi << 4));
}

static void repack_q6_K_tiled(const ggml_tensor * t, const void * data, size_t offset, size_t size) {
    GGML_ASSERT(offset == 0);

    const block_q6_K * src_matrix = (const block_q6_K *) data;
    int64_t ne0 = t->ne[0];
    int64_t ne1 = t->ne[1];
    int64_t ne2 = t->ne[2];
    int64_t ne3 = t->ne[3];
    int64_t ne0_padded = hex_round_up(ne0, 32);
    int64_t ne1_padded = hex_round_up(ne1, 32);

    GGML_ASSERT(ne0 % QK_K == 0);

    const int n_col_tiles = ne1_padded / 32;
    const int n_k_tiles   = ne0_padded / 32;
    const size_t tile_size   = HTP_MM_WEIGHT_TILE_SIZE_Q6_K;
    const size_t matrix_size = (size_t) n_col_tiles * n_k_tiles * tile_size;

    const int64_t sb_per_row = ne0 / QK_K;

    for (int i3 = 0; i3 < ne3; i3++) {
        for (int i2 = 0; i2 < ne2; i2++) {
            const block_q6_K * src_slice = src_matrix + (i3 * ne2 + i2) * (ne1 * sb_per_row);
            uint8_t * matrix_dst = (uint8_t *) t->data + (i3 * ne2 + i2) * matrix_size;

            memset(matrix_dst, 0, matrix_size);  // padding rows and the OR-ed nibbles below need zeroed tiles

            for (int64_t r = 0; r < ne1; r++) {
                const int ct  = (int) (r / 32);
                const int row = (int) (r % 32);
                const block_q6_K * src_row = src_slice + r * sb_per_row;

                for (int kt = 0; kt < n_k_tiles; kt++) {
                    const int kt_local = kt % 8;  // k-tile within the super-block
                    const block_q6_K * b = &src_row[kt / 8];
                    const float d = GGML_FP16_TO_FP32(b->d);

                    uint8_t * tile = matrix_dst + ((size_t) ct * n_k_tiles + kt) * tile_size;
                    uint8_t * lo_pl = tile;
                    uint8_t * hi_pl = tile + 512;
                    ggml_half * sc_pl = (ggml_half *) (tile + 768);

                    for (int lk = 0; lk < 32; lk++) {
                        const uint8_t q6 = q6_K_get_quant(b, kt_local * 32 + lk);
                        const int g   = lk >> 2;
                        const int pos = row * 4 + (lk & 3);
                        lo_pl[(g >> 1) * 128 + pos] |= (uint8_t) ((q6 & 0xF) << ((g & 1) * 4));
                        hi_pl[(g >> 2) * 128 + pos] |= (uint8_t) ((q6 >> 4) << ((g & 3) * 2));
                    }
                    for (int sub = 0; sub < 2; sub++) {
                        sc_pl[sub * 32 + row] = GGML_FP32_TO_FP16(d * (float) b->scales[kt_local * 2 + sub]);
                    }
                }
            }
        }
    }

    GGML_UNUSED(size);
}

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
