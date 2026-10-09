// r27_oracle_q6k.c — R27: ORACULO Q6_K OFFLINE (pedido §3 nº1!)
// Pack GOLDEN (repack_q6_K_tiled VERBATIM do candidato!) + LEITOR
// INDEPENDENTE do tiled (decodifica pelo CONTRATO: lo-nibbles/hi-2bit/sc_pl!)
// e compara com o DEQUANT CANONICO do ggml (dequantize_row_q6_K!).
// Constantes REAIS: tile896 (HTP_MM_WEIGHT_TILE_SIZE_Q6_K!), K256/N64.
// Compilar: g++ -std=c++17 -fpermissive -O2 -I... r27_oracle_q6k.c ggml-quants.c -lm -o r27_oracle_q6k
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdint.h>
#include <assert.h>
#include "ggml.h"
#include "ggml-impl.h"
#include "ggml-quants.h"

#define QK_K 256
#define HTP_MM_WEIGHT_TILE_SIZE_Q6_K 896

static inline int64_t hex_round_up(int64_t x, int64_t n) { return ((x + n - 1) / n) * n; }

// === stubs minimalistas ===
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

// === q6_K_get_quant VERBATIM do candidato (ggml-hexagon.cpp L570-586!) ===
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

// === PACK GOLDEN: repack_q6_K_tiled VERBATIM (L588-646!) ===
static void repack_q6_K_tiled(const ggml_tensor * t, const void * data, size_t offset, size_t size) {
    GGML_ASSERT(offset == 0);
    (void) size;
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
            memset(matrix_dst, 0, matrix_size);
            for (int64_t r = 0; r < ne1; r++) {
                const int ct  = (int) (r / 32);
                const int row = (int) (r % 32);
                const block_q6_K * src_row = src_slice + r * sb_per_row;
                for (int kt = 0; kt < n_k_tiles; kt++) {
                    const int kt_local = kt % 8;
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
}

// === LEITOR INDEPENDENTE (decodifica o TILED pelo contrato do formato!):
// para (r, k): tile (ct=r/32, kt=k/32); lo/hi (g=(k%32)>>2, pos=row*4+((k%32)&3));
// q6 = lo | (hi<<4); sc = fp16(sc_pl[sub*32+row]) com sub=(k%32)/16; w = sc*(q6-32)!
static float leitor_q6k_w(const uint8_t * tiled, int n_k_tiles, int64_t r, int64_t k) {
    const int ct  = (int) (r / 32);
    const int row = (int) (r % 32);
    const int kt  = (int) (k / 32);
    const int lk  = (int) (k % 32);
    const uint8_t * tile = tiled + ((size_t) ct * n_k_tiles + kt) * HTP_MM_WEIGHT_TILE_SIZE_Q6_K;
    const uint8_t * lo_pl = tile;
    const uint8_t * hi_pl = tile + 512;
    const ggml_half * sc_pl = (const ggml_half *) (tile + 768);
    const int g   = lk >> 2;
    const int pos = row * 4 + (lk & 3);
    const uint8_t lo = (uint8_t) ((lo_pl[(g >> 1) * 128 + pos] >> ((g & 1) * 4)) & 0xF);
    const uint8_t hi = (uint8_t) ((hi_pl[(g >> 2) * 128 + pos] >> ((g & 3) * 2)) & 3);
    const uint8_t q6 = (uint8_t) (lo | (hi << 4));   // 0..63!
    const int sub = lk / 16;
    const float sc = GGML_FP16_TO_FP32(sc_pl[sub * 32 + row]);   // ja' = d*scale do sub-block!
    return sc * (float) ((int) q6 - 32);   // formula canonica Q6: (q-32)!
}

int main(void) {
    const int K = 256, N = 64;
    float * src = (float *) malloc((size_t)K * N * sizeof(float));
    for (int i = 0; i < K * N; i++) src[i] = (float)(sin((double)i * 0.017) * 1.7);
    block_q6_K * q = (block_q6_K *) malloc((size_t)N * (K / QK_K) * sizeof(block_q6_K));
    quantize_row_q6_K(src, q, K * N);

    // o tiled (tamanho REAL: 224/row * 64 = 14336!)
    const int n_k_tiles = K / 32;
    const int n_col_tiles = N / 32;
    const size_t tiled_sz = (size_t) n_col_tiles * n_k_tiles * HTP_MM_WEIGHT_TILE_SIZE_Q6_K;
    uint8_t * tiled = (uint8_t *) calloc(1, tiled_sz);
    struct ggml_tensor t0;
    memset(&t0, 0, sizeof(t0));
    t0.type = GGML_TYPE_Q6_K;
    t0.ne[0] = K; t0.ne[1] = N; t0.ne[2] = 1; t0.ne[3] = 1;
    t0.data = tiled;
    repack_q6_K_tiled(&t0, q, 0, (size_t)N * (K / QK_K) * sizeof(block_q6_K));
    printf("tiled_sz=%zu (esperado 14336!)\n", tiled_sz);

    // dequant CANONICO (a referencia!)
    float * dc = (float *) malloc((size_t)K * N * sizeof(float));
    for (int r = 0; r < N; r++) dequantize_row_q6_K(&q[r * (K / QK_K)], dc + (size_t)r * K, K);

    // LEITOR vs CANONICO — TODAS as 16384 entradas!
    double max_abs = 0; long n_nan = 0, nd = 0; int first_bad = -1; long n_bad1e = 0;
    for (int r = 0; r < N; r++) {
        for (int k = 0; k < K; k++) {
            float w = leitor_q6k_w(tiled, n_k_tiles, r, k);
            float ref = dc[(size_t)r * K + k];
            if (!isfinite(w) || !isfinite(ref)) { n_nan++; continue; }
            double dd = fabs((double)w - (double)ref);
            if (dd > max_abs) max_abs = dd;
            if (dd > 1e-6) n_bad1e++;
            if (dd > 0.05 && first_bad < 0) first_bad = r * K + k;
            nd++;
        }
    }
    printf("LEITOR_Q6 vs CANONICO: nd=%ld/%d n_nan=%ld max_abs=%.9f n_bad1e6=%ld first_bad=%d %s\n",
           nd, K * N, n_nan, max_abs, n_bad1e, first_bad, (n_nan == 0 && max_abs <= 0.05 && nd == K * N) ? "PASS" : "FAIL");
    // scalar matmul TODAS as N rows (vs dequant canonico!)
    float * act = (float *) malloc((size_t)K * sizeof(float));
    for (int k = 0; k < K; k++) act[k] = (float)(cos((double)k * 0.013) * 0.8);
    double maxd = 0; int first_viol = -1; long nn = 0;
    for (int n = 0; n < N; n++) {
        double acc1 = 0, acc2 = 0;
        for (int k = 0; k < K; k++) {
            acc1 += (double) leitor_q6k_w(tiled, n_k_tiles, n, k) * act[k];
            acc2 += (double) dc[(size_t)n * K + k] * act[k];
        }
        if (!isfinite(acc1) || !isfinite(acc2)) { nn++; continue; }
        double d = fabs(acc1 - acc2);
        if (d > maxd) maxd = d;
        if (d > 0.1 && first_viol < 0) first_viol = n;
    }
    printf("MATMUL Q6 todas as %d rows: max_diff=%.9f first_viol=%d n_nan=%ld %s\n",
           N, maxd, first_viol, nn, (nn == 0 && maxd <= 0.1 && first_viol < 0) ? "PASS" : "FAIL");
    printf("R27_Q6_ORACLE_DONE\n");
    return (n_nan == 0 && max_abs <= 0.05 && nd == K * N && nn == 0 && maxd <= 0.1) ? 0 : 1;
}
