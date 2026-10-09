// r28_mapas.c — R28: descobre o MAPEAMENTO CORRETO k_loc <-> (cp, nibble) do
// tile Q4_K usando o DEQUANT CANONICO como arbitro (a verdade externa!).
// Testa TODAS as convencoes plausiveis e reporta qual zera o erro!
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdint.h>
#include "ggml.h"
#include "ggml-impl.h"
#include "ggml-quants.h"
#define QK_K 256

// leitura do byte (o pack escreve tile[cp*32+row] = (q1<<4)|q0!)
static inline uint8_t nib(const uint8_t * tile_qs, int cp, int row, int hi) {
    const uint8_t b = tile_qs[cp * 32 + row];
    return hi ? (b >> 4) : (b & 0x0F);
}

int main(void) {
    const int K = 256, N = 64;
    float * src = (float *) malloc((size_t)K * N * sizeof(float));
    for (int i = 0; i < K * N; i++) src[i] = (float)(sin((double)i * 0.017) * 1.7);
    block_q4_K * q = (block_q4_K *) malloc((size_t)N * (K / QK_K) * sizeof(block_q4_K));
    quantize_q4_K(src, q, N, K, NULL);
    float * dc = (float *) malloc((size_t)K * N * sizeof(float));
    for (int r = 0; r < N; r++) dequantize_row_q4_K(&q[r * (K / QK_K)], dc + (size_t)r * K, K);

    // pack (o REAL — o mesmo do gen!)
    const int n_k_tiles = 8, n_col_tiles = 2;
    uint8_t * tiled = (uint8_t *) calloc(1, 10240);
    for (int r = 0; r < N; r++) {
        const int ct = r / 32, row = r % 32;
        for (int kt = 0; kt < n_k_tiles; kt++) {
            const int kt_local = kt % 8;
            const block_q4_K * b = &q[r * (K / QK_K) + kt / 8];
            const float d = GGML_FP16_TO_FP32(b->d);
            const float dmin = GGML_FP16_TO_FP32(b->dmin);
            uint8_t * tile = tiled + ((size_t) ct * n_k_tiles + kt) * 640;
            uint8_t sc, m;
            {   // get_scale_min_k4 (verbatim!)
                const uint8_t * qq = b->scales;
                if (kt_local < 4) { sc = qq[kt_local] & 63; m = qq[kt_local + 4] & 63; }
                else { sc = (qq[kt_local+4] & 0xF) | ((qq[kt_local-4] >> 6) << 4);
                       m = (qq[kt_local+4] >> 4) | ((qq[kt_local-4] >> 6) << 4); }
            }
            const float D = d * (float) sc;
            const float M = -dmin * (float) m;
            const uint8_t * qs_sub = b->qs + (kt_local / 2) * 32;
            const int shift = (kt_local & 1) ? 4 : 0;
            for (int cp = 0; cp < 16; cp++) {
                const uint8_t q0 = (qs_sub[2*cp+0] >> shift) & 0x0F;
                const uint8_t q1 = (qs_sub[2*cp+1] >> shift) & 0x0F;
                tile[cp*32+row] = (uint8_t)((q1 << 4) | q0);
            }
            ggml_half * s = (ggml_half *)(tile + 512);
            s[2*row+0] = GGML_FP32_TO_FP16(D);
            s[2*row+1] = GGML_FP32_TO_FP16(M);
        }
    }
    // TESTAR OS MAPEAMENTOS k_loc -> (cp, hi):
    // M0: (meu R22!) k par: cp=k/2,lo; k imp: cp=k/2,hi
    // M1: (invertido) k par: cp=k/2,hi; k imp: cp=k/2,lo
    // M2: duas metades: k<16: cp=k,lo; k>=16: cp=k-16,hi
    // M3: duas metades inv: k<16: cp=k,hi; k>=16: cp=k-16,lo
    // M4: k<16: cp=k/2 com par/imp? (mesmo M0 nas primeiras 16!) — pular!
    // M5: cp = k/2 com k<16->lo e k>=16->lo?? (nao — )
    for (int modo = 0; modo <= 3; modo++) {
        long n_bad = 0; double max_abs = 0;
        for (int r = 0; r < N; r++) for (int k = 0; k < K; k++) {
            const int ct = r / 32, row = r % 32;
            const int kt = k / 32, k_loc = k % 32;
            const uint8_t * tile = tiled + ((size_t) ct * n_k_tiles + kt) * 640;
            int cp = 0, hi = 0;
            switch (modo) {
                case 0: cp = k_loc / 2; hi = (k_loc & 1); break;
                case 1: cp = k_loc / 2; hi = !(k_loc & 1); break;
                case 2: cp = k_loc % 16; hi = (k_loc >= 16); break;
                case 3: cp = k_loc % 16; hi = !(k_loc >= 16); break;
            }
            const uint8_t qq = nib(tile, cp, row, hi);
            const ggml_half * s = (const ggml_half *)(tile + 512);
            const float D = GGML_FP16_TO_FP32(s[2*row+0]);
            const float M = GGML_FP16_TO_FP32(s[2*row+1]);
            const float w = D * (float) qq + M;
            const float ref = dc[(size_t)r * K + k];
            const double dd = fabs((double) w - (double) ref);
            if (dd > 0.05) n_bad++;
            if (dd > max_abs) max_abs = dd;
        }
        printf("MODO %d: n_bad=%ld max_abs=%.6f %s\n", modo, n_bad, max_abs, n_bad == 0 ? "PASS!!!" : "");
    }
    return 0;
}
