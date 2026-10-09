// r36_refs.c — R36: REFERENCIAS OFFLINE sobre os bytes CAPTURADOS do device!
// Le: canonweights_9216.bin + actfloat_131072.bin + cpuout/htpout_32768.bin +
// tiled_raw.bin (10240!) e calcula: RscalarCanonical (dequant canonico x act,
// double!) e RtiledReader (leitor DIRETO do tiled x MESMOS act, double!) para
// todas as 8192 entradas + tabela dos violadores conhecidos!
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdint.h>
#include "ggml.h"
#include "ggml-impl.h"
#include "ggml-quants.h"
#define QK_K 256
static inline int64_t hex_round_up(int64_t x, int64_t n) { return ((x + n - 1) / n) * n; }

// stubs
#include <stdarg.h>
void ggml_abort(const char * file, int line, const char * fmt, ...) {
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "GGML_ABORT %s:%d: ", file, line); vfprintf(stderr, fmt, ap); va_end(ap);
    abort();
}
size_t ggml_row_size(enum ggml_type t, int64_t ne) { switch (t) { case GGML_TYPE_Q4_K: return (size_t)(ne/QK_K)*sizeof(block_q4_K); case GGML_TYPE_Q6_K: return (size_t)(ne/QK_K)*sizeof(block_q6_K); default: return 0; } }
size_t ggml_type_size(enum ggml_type t) { switch (t) { case GGML_TYPE_Q4_K: return sizeof(block_q4_K); case GGML_TYPE_Q6_K: return sizeof(block_q6_K); case GGML_TYPE_F32: return 4; default: return 0; } }
const char * ggml_type_name(enum ggml_type t) { (void)t; return "q"; }

// o leitor DIRETO do tiled (validado no R22/R29! — dependencia corrigida!)
static float leitor_q4k_w(const uint8_t * tiled, int n_k_tiles, int r, int k) {
    const int ct = r / 32, row = r % 32;
    const int kt = k / 32, k_loc = k % 32;
    const uint8_t * tile = tiled + ((size_t) ct * n_k_tiles + kt) * 640;
    const int cp = k_loc / 2;
    const uint8_t qv = tile[cp * 32 + row];
    const uint8_t q = (k_loc & 1) ? (qv >> 4) : (qv & 0x0F);
    const ggml_half * sc = (const ggml_half *) (tile + 512);
    const float D = GGML_FP16_TO_FP32(sc[2 * row + 0]);
    const float M = GGML_FP16_TO_FP32(sc[2 * row + 1]);
    return D * (float) q + M;
}

static void * load(const char * path, size_t expect, size_t * got) {
    FILE * f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "FALHA ao abrir %s\n", path); exit(2); }
    void * buf = malloc(expect + 16);
    size_t n = fread(buf, 1, expect, f);
    fclose(f);
    *got = n;
    return buf;
}

int main(void) {
    const char * D = "/root/r36_cap";
    const int K = 256, N = 64, B = 128;
    size_t sz;
    void * canon = load("/root/r36_cap/canonweights_9216.bin", 9216, &sz);
    printf("canon: %zu bytes (esperado 9216!)\n", sz);
    float * act = (float *) load("/root/r36_cap/actfloat_131072.bin", 131072, &sz);
    printf("act: %zu bytes (esperado 131072!)\n", sz);
    float * cpuout = (float *) load("/root/r36_cap/cpuout_32768.bin", 32768, &sz);
    float * htpout = (float *) load("/root/r36_cap/htpout_32768.bin", 32768, &sz);
    uint8_t * tiled = (uint8_t *) load("/root/r36_cap/tiled_raw.bin", 10240, &sz);
    printf("tiled: %zu bytes (esperado 10240!)\n", sz);

    // dequant canonico de TODAS as rows (a referencia!)
    float * dc = (float *) malloc((size_t)K * N * sizeof(float));
    for (int r = 0; r < N; r++) dequantize_row_q4_K((const block_q4_K *) ((const uint8_t *) canon + (size_t) r * 144), dc + (size_t) r * K, K);

    // as refs double para TODAS as 8192 entradas!
    double * rscalar = (double *) malloc((size_t)N * B * sizeof(double));
    double * rtiled = (double *) malloc((size_t)N * B * sizeof(double));
    for (int b = 0; b < B; b++)
        for (int n = 0; n < N; n++) {
            double s1 = 0, s2 = 0;
            for (int k = 0; k < K; k++) {
                const double a = (double) act[(size_t) b * K + k];
                s1 += (double) dc[(size_t) n * K + k] * a;
                s2 += (double) leitor_q4k_w(tiled, 8, n, k) * a;
            }
            rscalar[(size_t) b * N + n] = s1;
            rtiled[(size_t) b * N + n] = s2;
        }

    // comparacoes (TODAS as 8192!): CPUout vs Rscalar, CPUout vs Rtiled, HTPout vs Rscalar, HTPout vs Rtiled!
    struct { const char * nome; float * out; double * ref; } cmps[4] = {
        { "CPUout vs Rscalar", cpuout, rscalar },
        { "CPUout vs Rtiled ", cpuout, rtiled },
        { "HTPout vs Rscalar", htpout, rscalar },
        { "HTPout vs Rtiled ", htpout, rtiled },
    };
    for (int c = 0; c < 4; c++) {
        double maxabs = 0, sum2 = 0, sum2ref = 0; long nd = 0, nf = 0;
        for (int i = 0; i < N * B; i++) {
            double o = (double) cmps[c].out[i], r = cmps[c].ref[i];
            if (!isfinite(o) || !isfinite(r)) { nf++; continue; }
            double d = fabs(o - r);
            if (d > maxabs) maxabs = d;
            sum2 += d * d; sum2ref += r * r; nd++;
        }
        printf("%s: nd=%ld nf=%ld max_abs=%.9f nmse=%.3e\n", cmps[c].nome, nd, nf, maxabs, sum2ref > 0 ? sum2 / sum2ref : -1.0);
    }
    // a tabela dos violadores conhecidos (10 coords!)
    const int viol[10][2] = {{10,22},{22,21},{31,33},{39,34},{42,45},{50,54},{56,47},{66,2},{110,1},{123,8}};
    printf("\n10 coords (b,n): CPUout | HTPout | Rscalar | Rtiled:\n");
    for (int i = 0; i < 10; i++) {
        int b = viol[i][0], n = viol[i][1]; size_t idx = (size_t) b * N + n;
        printf("  (%3d,%2d): %.6f | %.6f | %.6f | %.6f   dHTP-Rtiled=%.6f dHTP-Rscalar=%.6f\n",
               b, n, cpuout[idx], htpout[idx], rscalar[idx], rtiled[idx],
               htpout[idx] - rtiled[idx], htpout[idx] - rscalar[idx]);
    }
    printf("\nR36_REFS_DONE\n");
    return 0;
}
