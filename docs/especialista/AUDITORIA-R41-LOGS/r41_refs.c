// r41_refs.c — R41: ORACULO COMPLETO Q6 K6144/N2048/B1 (o runner do §4!)
// - valida schema/identidade/tamanhos EXATOS (inclusive excesso!) + meta;
// - computa os vetores UMA VEZ (as 2048 refs Rscalar E Rtiled!);
// - contagens COMPLETAS de violacoes (LEGACY 0.02/0.05!) para CPU e HTP;
// - NMSE PIN (5e-4!) separado; top10 com 10 indice DISTINTOS (ordenacao
//   DECLARADA: |err| absoluto do HTP vs Rtiled!); CSV completo (2048!);
// - INVALID = exit!=0 (nunca return0 com printf!).
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdint.h>
#include <stdarg.h>
#include "ggml.h"
#include "ggml-impl.h"
#include "ggml-quants.h"
#define QK_K 256
void ggml_abort(const char * file, int line, const char * fmt, ...) {
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "GGML_ABORT %s:%d: ", file, line); vfprintf(stderr, fmt, ap); va_end(ap);
    abort();
}
size_t ggml_row_size(enum ggml_type t, int64_t ne) { switch (t) { case GGML_TYPE_Q4_K: return (size_t)(ne/QK_K)*sizeof(block_q4_K); case GGML_TYPE_Q6_K: return (size_t)(ne/QK_K)*sizeof(block_q6_K); default: return 0; } }
size_t ggml_type_size(enum ggml_type t) { switch (t) { case GGML_TYPE_Q4_K: return sizeof(block_q4_K); case GGML_TYPE_Q6_K: return sizeof(block_q6_K); case GGML_TYPE_F32: return 4; default: return 0; } }
const char * ggml_type_name(enum ggml_type t) { (void)t; return "q"; }

// leitor DIRETO do tiled Q6 (o R27/R29 validado!)
static float leitor_q6k_w(const uint8_t * tiled, int n_k_tiles, int r, int k) {
    const int ct = r / 32, row = r % 32;
    const int kt = k / 32, lk = k % 32;
    const uint8_t * tile = tiled + ((size_t) ct * n_k_tiles + kt) * 896;
    const uint8_t * lo_pl = tile, * hi_pl = tile + 512;
    const ggml_half * sc_pl = (const ggml_half *) (tile + 768);
    const int g = lk >> 2, pos = row * 4 + (lk & 3);
    const uint8_t lo = (uint8_t) ((lo_pl[(g >> 1) * 128 + pos] >> ((g & 1) * 4)) & 0xF);
    const uint8_t hi = (uint8_t) ((hi_pl[(g >> 2) * 128 + pos] >> ((g & 3) * 2)) & 3);
    const uint8_t q6 = (uint8_t)(lo | (hi << 4));
    const int sub = lk / 16;
    const float sc = GGML_FP16_TO_FP32(sc_pl[sub * 32 + row]);
    return sc * (float)((int) q6 - 32);
}

static uint8_t * load_checked(const char * path, size_t expected, const char * nome) {
    FILE * f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "INVALID: fopen falhou: %s\n", path); exit(10); }
    fseek(f, 0, SEEK_END); const long sz = ftell(f); fseek(f, 0, SEEK_SET);
    if (sz != (long) expected) { fprintf(stderr, "INVALID: %s tem %ld bytes (esperado %zu!)\n", nome, sz, expected); exit(11); }
    uint8_t * buf = (uint8_t *) malloc(expected);
    if (!buf) { fprintf(stderr, "INVALID: malloc falhou (%zu!)\n", expected); exit(12); }
    const size_t rd = fread(buf, 1, expected, f);
    if (rd != expected) { fprintf(stderr, "INVALID: %s leu %zu/%zu!\n", nome, rd, expected); exit(13); }
    fclose(f);
    // boost de confianca: byte excedente? (ja' checado pelo size!)
    return buf;
}

int main(int argc, char ** argv) {
    const char * D = (argc > 1) ? argv[1] : "/root/r39_cap";
    const int K = 6144, N = 2048;
    char path[512];
    // identidade/schema: tamanhos EXATOS derivados dos contratos reais
    const size_t CANON = (size_t)(K/QK_K) * 210 * N;          // 10321920
    const size_t ACTB = (size_t) K * 4;                        // 24576 (B=1)
    const size_t OUTB = (size_t) N * 4;                        // 8192
    const size_t TILED = (size_t)(K/32) * (N/32) * 896;        // 11010048
    printf("R41 RUNNER: schema K=%d N=%d B=1 | canon=%zu act=%zu out=%zu tiled=%zu\n", K, N, CANON, ACTB, OUTB, TILED);
    // META: presente? (divergente conhecido do R39 -> WARN, nao silencio!)
    snprintf(path, sizeof(path), "%s/meta.txt", D);
    FILE * fm = fopen(path, "r");
    if (fm) {
        char line[256];
        printf("META (WARN: o R39 tem rotulo historico hardcoded!):\n");
        while (fgets(line, sizeof(line), fm)) printf("  %s", line);
        fclose(fm);
    } else printf("META ausente (WARN!)\n");
    snprintf(path, sizeof(path), "%s/canonweights_9216.bin", D);
    uint8_t * canon = load_checked(path, CANON, "canon");
    snprintf(path, sizeof(path), "%s/actfloat_131072.bin", D);
    float * act = (float *) load_checked(path, ACTB, "act");
    snprintf(path, sizeof(path), "%s/cpuout_32768.bin", D);
    float * cpuout = (float *) load_checked(path, OUTB, "cpuout");
    snprintf(path, sizeof(path), "%s/htpout_32768.bin", D);
    float * htpout = (float *) load_checked(path, OUTB, "htpout");
    snprintf(path, sizeof(path), "%s/tiled_raw.bin", D);
    uint8_t * tiled = load_checked(path, TILED, "tiled");
    printf("5 binarios: tamanhos EXATOS ok (identidade por conteudo!) — stress: %s\n", argc > 1 ? "dir argumento" : "default");

    // dequant canonico (uma vez!)
    const int sb = K / QK_K;
    float * dc = (float *) malloc((size_t)K * N * sizeof(float));
    for (int r = 0; r < N; r++) dequantize_row_q6_K((const block_q6_K *) (canon + (size_t) r * sb * 210), dc + (size_t) r * K, K);

    // as 2048 refs (vetores UMA vez!)
    double * s1v = (double *) malloc(N * sizeof(double));
    double * s2v = (double *) malloc(N * sizeof(double));
    for (int n = 0; n < N; n++) {
        double s1 = 0, s2 = 0;
        for (int k = 0; k < K; k++) {
            const double a = (double) act[k];
            s1 += (double) dc[(size_t) n * K + k] * a;
            s2 += (double) leitor_q6k_w(tiled, K/32, n, k) * a;
        }
        s1v[n] = s1; s2v[n] = s2;
    }
    // metricas completas
    long nd = 0, nf = 0;
    long vc1 = 0, vc2 = 0, vh1 = 0, vh2 = 0;
    double m_c1 = 0, m_c2 = 0, m_h1 = 0, m_h2 = 0;
    double e_c1 = 0, e_c2 = 0, e_h1 = 0, e_h2 = 0, d1 = 0, d2 = 0;
    for (int n = 0; n < N; n++) {
        if (!isfinite(s1v[n]) || !isfinite(s2v[n]) || !isfinite(cpuout[n]) || !isfinite(htpout[n])) { nf++; continue; }
        nd++;
        const double ec1 = fabs((double) cpuout[n] - s1v[n]), ec2 = fabs((double) cpuout[n] - s2v[n]);
        const double eh1 = fabs((double) htpout[n] - s1v[n]), eh2 = fabs((double) htpout[n] - s2v[n]);
        if (ec1 > m_c1) m_c1 = ec1; if (ec2 > m_c2) m_c2 = ec2;
        if (eh1 > m_h1) m_h1 = eh1; if (eh2 > m_h2) m_h2 = eh2;
        e_c1 += ec1*ec1; e_c2 += ec2*ec2; e_h1 += eh1*eh1; e_h2 += eh2*eh2;
        d1 += s1v[n]*s1v[n]; d2 += s2v[n]*s2v[n];
        if (ec1 > 0.02 + 0.05*fabs(s1v[n])) vc1++;
        if (ec2 > 0.02 + 0.05*fabs(s2v[n])) vc2++;
        if (eh1 > 0.02 + 0.05*fabs(s1v[n])) vh1++;
        if (eh2 > 0.02 + 0.05*fabs(s2v[n])) vh2++;
    }
    printf("\nnd=%ld nf=%ld (de %d!)\n", nd, nf, N);
    printf("=====================================================\n");
    printf("CPU vs Rscalar: nmse=%.3e max_abs=%.6f VIOL=%ld\n", d1>0?e_c1/d1:-1, m_c1, vc1);
    printf("CPU vs Rtiled : nmse=%.3e max_abs=%.6f VIOL=%ld\n", d2>0?e_c2/d2:-1, m_c2, vc2);
    printf("HTP vs Rscalar: nmse=%.3e max_abs=%.6f VIOL=%ld\n", d1>0?e_h1/d1:-1, m_h1, vh1);
    printf("HTP vs Rtiled : nmse=%.3e max_abs=%.6f VIOL=%ld\n", d2>0?e_h2/d2:-1, m_h2, vh2);
    printf("PIN (5e-4!): CPU/Rscalar=%s CPU/Rtiled=%s HTP/Rscalar=%s HTP/Rtiled=%s\n",
           (d1>0&&e_c1/d1<5e-4)?"PASS":"FAIL", (d2>0&&e_c2/d2<5e-4)?"PASS":"FAIL",
           (d1>0&&e_h1/d1<5e-4)?"PASS":"FAIL", (d2>0&&e_h2/d2<5e-4)?"PASS":"FAIL");
    // CSV COMPLETO (2048!)
    snprintf(path, sizeof(path), "%s/r41-referencias-completas.csv", D);
    FILE * fc = fopen(path, "w");
    if (!fc) { fprintf(stderr, "INVALID: csv fopen!\n"); return 14; }
    fprintf(fc, "n,cpu,htp,rscalar,rtiled,err_cpu_s,err_cpu_t,err_htp_s,err_htp_t\n");
    for (int n = 0; n < N; n++)
        fprintf(fc, "%d,%.6f,%.6f,%.6f,%.6f,%.6e,%.6e,%.6e,%.6e\n",
                n, (double) cpuout[n], (double) htpout[n], s1v[n], s2v[n],
                fabs((double) cpuout[n]-s1v[n]), fabs((double) cpuout[n]-s2v[n]),
                fabs((double) htpout[n]-s1v[n]), fabs((double) htpout[n]-s2v[n]));
    if (fclose(fc) != 0) { fprintf(stderr, "INVALID: csv fclose!\n"); return 15; }
    printf("CSV completo: %s (2048 linhas!)\n", path);
    // TOP10: 10 indices DISTINTOS (ordenacao DECLARADA: |err| HTP vs Rtiled!)
    printf("\nTOP10 |HTP-Rtiled| (absoluto — ordenacao DECLARADA!):\n");
    int used[2048] = {0};
    for (int t = 0; t < 10; t++) {
        double best = -1; int bn = -1;
        for (int n = 0; n < N; n++) {
            if (used[n]) continue;
            const double e = fabs((double) htpout[n] - s2v[n]);
            if (e > best) { best = e; bn = n; }
        }
        if (bn < 0) break;
        used[bn] = 1;
        printf("  #%d n=%4d: HTP=%+.6f Rscalar=%+.6f Rtiled=%+.6f |err_t|=%.6f\n",
               t + 1, bn, (double) htpout[bn], s1v[bn], s2v[bn], best);
    }
    printf("\nR41_LOADER_DONE exit=0 (observacao numerica VALIDA — LEGACY_FAIL preservado!)\n");
    return 0;
}
