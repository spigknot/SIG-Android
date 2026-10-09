#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdint.h>
#include <assert.h>
#include <cstddef>
#include "ggml.h"
#include "ggml-impl.h"
#include "ggml-quants.h"

#define GGML_UNUSED(x) (void)(x)
#undef GGML_ASSERT
#define GGML_ASSERT(x) assert(x)
static inline int64_t hex_round_up(int64_t x, int64_t n) { return ((x + n - 1) / n) * n; }
#define HTP_MM_WEIGHT_TILE_SIZE_Q4_1 640

static inline void get_scale_min_k4(int j, const uint8_t * GGML_RESTRICT q, uint8_t * GGML_RESTRICT d, uint8_t * GGML_RESTRICT m) {
    if (j < 4) {
        *d = q[j] & 63; *m = q[j + 4] & 63;
    } else {
        *d = (q[j+4] & 0xF) | ((q[j-4] >> 6) << 4);
        *m = (q[j+4] >>  4) | ((q[j-0] >> 6) << 4);
    }
}

//========================- 2-bit (de)-quantization

static void repack_q4_K_tiled(const struct ggml_tensor * t, const void * data, size_t offset, size_t size) {
    GGML_ASSERT(offset == 0);

    const block_q4_K * src_matrix = (const block_q4_K *) data;
    int64_t ne0 = t->ne[0];
    int64_t ne1 = t->ne[1];
    int64_t ne2 = t->ne[2];
    int64_t ne3 = t->ne[3];
    int64_t ne0_padded = hex_round_up(ne0, 32);
    int64_t ne1_padded = hex_round_up(ne1, 32);

    GGML_ASSERT(ne0 % QK_K == 0);

    const int n_col_tiles = ne1_padded / 32;
    const int n_k_tiles   = ne0_padded / 32;
    const size_t tile_size   = HTP_MM_WEIGHT_TILE_SIZE_Q4_1;
    const size_t matrix_size = (size_t) n_col_tiles * n_k_tiles * tile_size;

    const int64_t sb_per_row = ne0 / QK_K;

    for (int i3 = 0; i3 < ne3; i3++) {
        for (int i2 = 0; i2 < ne2; i2++) {
            const block_q4_K * src_slice = src_matrix + (i3 * ne2 + i2) * (ne1 * sb_per_row);
            uint8_t * matrix_dst = (uint8_t *) t->data + (i3 * ne2 + i2) * matrix_size;

            memset(matrix_dst, 0, matrix_size);

            for (int64_t r = 0; r < ne1; r++) {
                const int ct  = (int) (r / 32);
                const int row = (int) (r % 32);
                const block_q4_K * src_row = src_slice + r * sb_per_row;

                for (int kt = 0; kt < n_k_tiles; kt++) {
                    const int kt_local = kt % 8;
                    const block_q4_K * b = &src_row[kt / 8];
                    const float d = GGML_FP16_TO_FP32(b->d);
                    const float dmin = GGML_FP16_TO_FP32(b->dmin);

                    uint8_t * tile_dst = matrix_dst + ((size_t) ct * n_k_tiles + kt) * tile_size;

                    uint8_t sc, m;
                    get_scale_min_k4(kt_local, b->scales, &sc, &m);

                    const float D = d * (float) sc;
                    const float M = -dmin * (float) m;

                    const uint8_t * qs_sub = b->qs + (kt_local / 2) * 32;
                    const int shift = (kt_local & 1) ? 4 : 0;

                    for (int cp = 0; cp < 16; cp++) {
                        const uint8_t q0 = (qs_sub[2 * cp + 0] >> shift) & 0x0F;
                        const uint8_t q1 = (qs_sub[2 * cp + 1] >> shift) & 0x0F;
                        tile_dst[cp * 32 + row] = (uint8_t) ((q1 << 4) | q0);
                    }

                    ggml_half * scale_dst = (ggml_half *) (tile_dst + 512);
                    scale_dst[2 * row + 0] = GGML_FP32_TO_FP16(D);
                    scale_dst[2 * row + 1] = GGML_FP32_TO_FP16(M);
                }
            }
        }
    }

    GGML_UNUSED(size);
}

static void repack_tiled_q4_K(void * data, const struct ggml_tensor * t, size_t offset, size_t size) {
    GGML_ASSERT(offset == 0);

    block_q4_K * dst_matrix = (block_q4_K *) data;
    int64_t ne0 = t->ne[0];
    int64_t ne1 = t->ne[1];
    int64_t ne2 = t->ne[2];
    int64_t ne3 = t->ne[3];
    int64_t ne0_padded = hex_round_up(ne0, 32);
    int64_t ne1_padded = hex_round_up(ne1, 32);

    GGML_ASSERT(ne0 % QK_K == 0);

    const int n_col_tiles = ne1_padded / 32;
    const int n_k_tiles   = ne0_padded / 32;
    const size_t tile_size   = HTP_MM_WEIGHT_TILE_SIZE_Q4_1;
    const size_t matrix_size = (size_t) n_col_tiles * n_k_tiles * tile_size;

    const int64_t sb_per_row = ne0 / QK_K;

    for (int i3 = 0; i3 < ne3; i3++) {
        for (int i2 = 0; i2 < ne2; i2++) {
            block_q4_K * dst_slice = dst_matrix + (i3 * ne2 + i2) * (ne1 * sb_per_row);
            const uint8_t * matrix_src = (const uint8_t *) t->data + (i3 * ne2 + i2) * matrix_size;

            for (int64_t r = 0; r < ne1; r++) {
                const int ct  = (int) (r / 32);
                const int row = (int) (r % 32);
                block_q4_K * dst_row = dst_slice + r * sb_per_row;

                for (int64_t sb = 0; sb < sb_per_row; sb++) {
                    block_q4_K * b = &dst_row[sb];
                    memset(b, 0, sizeof(block_q4_K));

                    float sub_scales[8];
                    float sub_mins[8];

                    for (int kt_local = 0; kt_local < 8; kt_local++) {
                        const int kt = sb * 8 + kt_local;
                        const uint8_t * tile_src = matrix_src + ((size_t) ct * n_k_tiles + kt) * tile_size;
                        const ggml_half * scale_src = (const ggml_half *) (tile_src + 512);

                        uint8_t * qs_sub = b->qs + (kt_local / 2) * 32;
                        const int shift = (kt_local & 1) ? 4 : 0;

                        for (int cp = 0; cp < 16; cp++) {
                            const uint8_t val = tile_src[cp * 32 + row];
                            const uint8_t q0 = val & 0x0F;
                            const uint8_t q1 = val >> 4;
                            qs_sub[2 * cp + 0] |= (uint8_t) (q0 << shift);
                            qs_sub[2 * cp + 1] |= (uint8_t) (q1 << shift);
                        }

                        const float D = GGML_FP16_TO_FP32(scale_src[2 * row + 0]);
                        const float M = GGML_FP16_TO_FP32(scale_src[2 * row + 1]);
                        sub_scales[kt_local] = (D > 0.0f) ? D : 0.0f;
                        sub_mins[kt_local]   = (-M > 0.0f) ? -M : 0.0f;
                    }

                    float max_scale = 0.0f;
                    float max_min   = 0.0f;
                    for (int j = 0; j < 8; j++) {
                        if (sub_scales[j] > max_scale) max_scale = sub_scales[j];
                        if (sub_mins[j]   > max_min)   max_min   = sub_mins[j];
                    }

                    float inv_scale = 0.0f;
                    if (max_scale > 0.0f) {
                        b->d = GGML_FP32_TO_FP16(max_scale / 63.0f);
                        const float d_actual = GGML_FP16_TO_FP32(b->d);
                        inv_scale = (d_actual > 0.0f) ? (1.0f / d_actual) : 0.0f;
                    } else {
                        b->d = GGML_FP32_TO_FP16(0.0f);
                    }

                    float inv_min = 0.0f;
                    if (max_min > 0.0f) {
                        b->dmin = GGML_FP32_TO_FP16(max_min / 63.0f);
                        const float dmin_actual = GGML_FP16_TO_FP32(b->dmin);
                        inv_min = (dmin_actual > 0.0f) ? (1.0f / dmin_actual) : 0.0f;
                    } else {
                        b->dmin = GGML_FP32_TO_FP16(0.0f);
                    }

                    for (int j = 0; j < 8; j++) {
                        uint8_t ls = (uint8_t) roundf(inv_scale * sub_scales[j]);
                        uint8_t lm = (uint8_t) roundf(inv_min * sub_mins[j]);
                        ls = (std::min)((uint8_t) 63, ls);
                        lm = (std::min)((uint8_t) 63, lm);
                        if (j < 4) {
                            b->scales[j]     = ls;
                            b->scales[j + 4] = lm;
                        } else {
                            b->scales[j + 4] = (ls & 0xF) | ((lm & 0xF) << 4);
                            b->scales[j - 4] |= ((ls >> 4) << 6);
                            b->scales[j - 0] |= ((lm >> 4) << 6);
                        }
                    }
                }
            }
        }
    }

    GGML_UNUSED(size);
}




// === stubs minimalistas (o oraculo nao linka o core!) ===
#include <stdarg.h>
void ggml_abort(const char * file, int line, const char * fmt, ...) {
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "GGML_ABORT %s:%d: ", file, line); vfprintf(stderr, fmt, ap); va_end(ap);
    abort();
}
size_t ggml_row_size(enum ggml_type type, int64_t ne) {
    switch (type) {
        case GGML_TYPE_Q4_K: return (size_t)(ne / QK_K) * sizeof(block_q4_K);
        case GGML_TYPE_Q6_K: return (size_t)(ne / QK_K) * sizeof(block_q6_K);
        default: return 0;
    }
}


size_t ggml_type_size(enum ggml_type type) {
    switch (type) {
        case GGML_TYPE_Q4_K: return sizeof(block_q4_K);
        case GGML_TYPE_Q6_K: return sizeof(block_q6_K);
        case GGML_TYPE_F32:  return 4;
        default: return 0;
    }
}
const char * ggml_type_name(enum ggml_type type) { (void) type; return "q"; }

int main(void) {
    const int K = 256, N = 64;
    // 1) fonte determinista + quantize canonico (ggml!)
    float * src = (float *) malloc(K * N * sizeof(float));
    for (int i = 0; i < K * N; i++) src[i] = (float)(sin((double)i * 0.017) * 1.7);
    block_q4_K * q = (block_q4_K *) malloc(N * sizeof(block_q4_K) * (K / QK_K));
    quantize_q4_K(src, q, N, K, NULL);
    // 2) repack (VERBATIM do candidato!) para payload tiled
    size_t row_sz = (size_t)(K / 32) * (HTP_MM_WEIGHT_TILE_SIZE_Q4_1 / 32);
    size_t tiled_sz = row_sz * ((N + 31) / 32) * 32;   // matrix_size (n_col_tiles*n_k_tiles*tile)
    uint8_t * tiled = (uint8_t *) calloc(1, tiled_sz + 4096);
    struct ggml_tensor t0;
    memset(&t0, 0, sizeof(t0));
    t0.ne[0] = K; t0.ne[1] = N; t0.ne[2] = 1; t0.ne[3] = 1;
    t0.type = GGML_TYPE_Q4_K;
    t0.data = tiled;
    repack_q4_K_tiled(&t0, q, 0, 0);
    printf("repack OK: row_sz=%zu tiled_sz=%zu\n", row_sz, tiled_sz);
    // 3) roundtrip: unpack de volta
    block_q4_K * q2 = (block_q4_K *) calloc(N * (K / QK_K), sizeof(block_q4_K));
    repack_tiled_q4_K(q2, &t0, 0, 0);
    // 4) comparar dequantizado (canonico!) dos dois
    float * d1 = (float *) malloc(K * N * sizeof(float));
    float * d2 = (float *) malloc(K * N * sizeof(float));
    for (int r = 0; r < N; r++) {
        dequantize_row_q4_K(q + r * (K / QK_K), d1 + r * K, K);
        dequantize_row_q4_K(q2 + r * (K / QK_K), d2 + r * K, K);
    }
    double max_abs = 0; int first_bad = -1;
    for (int i = 0; i < K * N; i++) {
        double d = fabs((double) d1[i] - (double) d2[i]);
        if (d > max_abs) max_abs = d;
        if (d > 0.05 && first_bad < 0) first_bad = i;
    }
    printf("roundtrip: max_abs=%.6f first_bad=%d %s\n", max_abs, first_bad, max_abs <= 0.05 ? "PASS" : "FAIL");
    // 5) scalar matmul oracle vs repack->unpack->dequant (a mesma conta!)
    // (K=N=64 mini: comparar y[v] = sum_k d1[k]*act[k] com d2)
    float * act = (float *) malloc(K * sizeof(float));
    for (int k = 0; k < K; k++) act[k] = (float)(cos((double)k * 0.013) * 0.8);
    double m1 = 0, m2 = 0;
    for (int k = 0; k < K; k++) { m1 += (double) d1[k] * act[k]; m2 += (double) d2[k] * act[k]; }
    printf("scalar matmul row0: canonico=%.6f roundtrip=%.6f diff=%.6f\n", m1, m2, fabs(m1 - m2));
    printf("ORACLE_DONE\n");
    return max_abs <= 0.05 ? 0 : 1;
}
