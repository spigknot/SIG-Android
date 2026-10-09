// r22_leitor_direto.c — R22 §4B: LEITOR ESCALAR INDEPENDENTE do tiled Q4_K
// Le DIRETO o payload fisico (NAO usa repack_tiled_q4_K do candidato!)
// Base: layout do golden (tile 640B): qs em [cp*32+row]=(q1<<4)|q0 (cp 0..15),
// fp16 [D,M] em tile+512+2*row*2 (D=d*sc, M=-dmin*m).
// VALIDACAO: leitor deve reproduzir o dequant canonico do MESMO bloco
// (golden-first!), depois julgar o payload do candidato.
// Compilar (servidor): g++ -std=c++17 -fpermissive -O2 -I... r22_leitor_direto.c ../ggml-quants.c -o r22_leitor
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdint.h>
#include <assert.h>
#include "ggml.h"
#include "ggml-impl.h"
#include "ggml-quants.h"

#define GGML_UNUSED(x) (void)(x)
static inline int64_t hex_round_up(int64_t x, int64_t n) { return ((x + n - 1) / n) * n; }
#define HTP_MM_WEIGHT_TILE_SIZE_Q4_1 640
#define QK_K 256

// ---- leitor INDEPENDENTE: reconstroi UM peso w[k] do row r do tiled ----
// layout: tile_index = (ct * n_k_tiles + kt); tile = tiled + idx*640
// q em tile[cp*32 + row] com cp = (k%32)/2; shift = (k%2)*4
// D/M em (ggml_half*)(tile+512)[2*row], [2*row+1] (fp16)
static float leitor_w(const uint8_t * tiled, int n_k_tiles, int r, int k) {
    const int ct = r / 32;
    const int row = r % 32;
    const int kt = k / 32;
    const int k_loc = k % 32;
    const uint8_t * tile = tiled + ((size_t) ct * n_k_tiles + kt) * 640;
    const int cp = k_loc / 2;
    const uint8_t qv = tile[cp * 32 + row];
    const uint8_t q = (k_loc & 1) ? (qv >> 4) : (qv & 0x0F);
    const ggml_half * sc = (const ggml_half *) (tile + 512);
    const float D = GGML_FP16_TO_FP32(sc[2 * row + 0]);   // d*sc
    const float M = GGML_FP16_TO_FP32(sc[2 * row + 1]);   // -dmin*m
    // equivalencia com o dequant canonico Q4_K: w = D*q + M (M ja' negativo!)
    return D * (float) q + M;
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
    // input determinista identico ao R19/R20/R22
    float * src = (float *) malloc((size_t)K * N * sizeof(float));
    for (int i = 0; i < K * N; i++) src[i] = (float)(sin((double)i * 0.017) * 1.7);
    block_q4_K * q = (block_q4_K *) malloc((size_t)N * (K / QK_K) * sizeof(block_q4_K));
    quantize_q4_K(src, q, N, K, NULL);

    // ===== VALIDACAO DO LEITOR (golden-first!): leitor vs canonico no
    // MESMO bloco de origem (usando o tiled feito pelo CODIGO DO PIN!) =====
    const size_t row_sz = (size_t)(K / 32) * (HTP_MM_WEIGHT_TILE_SIZE_Q4_1 / 32);
    const int n_k_tiles = hex_round_up(K, 32) / 32;     // 8
    const size_t tiled_sz = row_sz * (hex_round_up(N, 32) / 32) * 32;
    uint8_t * tiled = (uint8_t *) calloc(1, tiled_sz);
    // o pack GOLDEN inline (layout do pin, extraido verbatim):
    for (int r = 0; r < N; r++) {
        const int ct = r / 32;
        const int row = r % 32;
        for (int kt = 0; kt < n_k_tiles; kt++) {
            const int kt_local = kt % 8;
            const block_q4_K * b = &q[r * (K / QK_K) + kt / 8];
            const float d = GGML_FP16_TO_FP32(b->d);
            const float dmin = GGML_FP16_TO_FP32(b->dmin);
            uint8_t * tile_dst = tiled + ((size_t) ct * n_k_tiles + kt) * 640;
            uint8_t sc, m;
            // get_scale_min_k4 (verbatim do upstream!)
            {
                const uint8_t * qq = b->scales;
                if (kt_local < 4) { sc = qq[kt_local] & 63; m = qq[kt_local + 4] & 63; }
                else { sc = (qq[kt_local+4] & 0xF) | ((qq[kt_local-4] >> 6) << 4); m = (qq[kt_local+4] >> 4) | ((qq[kt_local-0] >> 6) << 4); }
            }
            const float D = d * (float) sc;
            const float M = -dmin * (float) m;
            const uint8_t * qs_sub = b->qs + (kt_local / 2) * 32;
            const int shift = (kt_local & 1) ? 4 : 0;
            for (int cp = 0; cp < 16; cp++) {
                const uint8_t q0 = (qs_sub[2 * cp + 0] >> shift) & 0x0F;
                const uint8_t q1 = (qs_sub[2 * cp + 1] >> shift) & 0x0F;
                tile_dst[cp * 32 + row] = (uint8_t)((q1 << 4) | q0);
            }
            ggml_half * scale_dst = (ggml_half *) (tile_dst + 512);
            scale_dst[2 * row + 0] = GGML_FP32_TO_FP16(D);
            scale_dst[2 * row + 1] = GGML_FP32_TO_FP16(M);
        }
    }
    // agora o LEITOR (independente!) reconstroi e compara com o dequant CANONICO
    float * dc = (float *) malloc((size_t)K * N * sizeof(float));
    for (int r = 0; r < N; r++) dequantize_row_q4_K(&q[r * (K / QK_K)], dc + (size_t)r * K, K);
    double max_abs = 0; long n_nan = 0, nd = 0; int first_bad = -1;
    for (int r = 0; r < N; r++) {
        for (int k = 0; k < K; k++) {
            float w = leitor_w(tiled, n_k_tiles, r, k);
            float ref = dc[(size_t)r * K + k];
            if (!isfinite(w) || !isfinite(ref)) { n_nan++; continue; }
            double dd = fabs((double)w - (double)ref);
            if (dd > max_abs) max_abs = dd;
            if (dd > 0.05 && first_bad < 0) first_bad = r * K + k;
            nd++;
        }
    }
    printf("LEITOR vs CANONICO: nd=%ld/%d n_nan=%ld max_abs=%.6f first_bad=%d %s\n",
           nd, K * N, n_nan, max_abs, first_bad, (n_nan == 0 && max_abs <= 0.05 && nd == K * N) ? "PASS" : "FAIL");
    // matmul B1 (TODAS as N linhas! cobre o n=1 do R19!)
    float * act = (float *) malloc((size_t)K * sizeof(float));
    for (int k = 0; k < K; k++) act[k] = (float)(cos((double)k * 0.013) * 0.8);
    double maxd = 0; int first_viol = -1; long nn = 0;
    for (int n = 0; n < N; n++) {
        double acc1 = 0, acc2 = 0;
        for (int k = 0; k < K; k++) {
            float w = leitor_w(tiled, n_k_tiles, n, k);
            acc1 += (double)w * act[k];
            acc2 += (double)dc[(size_t)n * K + k] * act[k];
        }
        if (!isfinite(acc1) || !isfinite(acc2)) { nn++; continue; }
        double d = fabs(acc1 - acc2);
        if (d > maxd) maxd = d;
        if (d > 0.1 && first_viol < 0) first_viol = n;
    }
    printf("MATMUL B1 todas as %d rows: max_diff=%.6f first_viol=%d n_nan=%ld %s\n",
           N, maxd, first_viol, nn, (nn == 0 && maxd <= 0.1 && first_viol < 0) ? "PASS" : "FAIL");
    printf("R22_LEITOR_DONE\n");
    return (n_nan == 0 && max_abs <= 0.05 && nd == K * N && nn == 0 && maxd <= 0.1) ? 0 : 1;
}
