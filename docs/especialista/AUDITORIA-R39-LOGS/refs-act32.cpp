// r39_refs.c — R39: ORACULOS sobre o snapshot Q6 K6144/N2048/B1 (o alvo!)
// Le: canonweights(10321920=Q6!) + actfloat(24576=6144 floats!) +
// cpuout/htpout(8192=2048 floats!) + tiled_raw(11010048=192x64x896!)
// RscalarCanonical (dequant canonico x act, double!) e RtiledReader (leitor
// DIRETO do RAW x MESMOS act!) — TODAS as 2048 saidas!
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

// o leitor DIRETO do tiled Q6 (o R27/R29 — validado!)
static float leitor_q6k_w(const uint8_t * tiled, int n_k_tiles, int r, int k) {
    const int ct = r / 32, row = r % 32;
    const int kt = k / 32, lk = k % 32;
    const uint8_t * tile = tiled + ((size_t) ct * n_k_tiles + kt) * 896;
    const uint8_t * lo_pl = tile;
    const uint8_t * hi_pl = tile + 512;
    const ggml_half * sc_pl = (const ggml_half *) (tile + 768);
    const int g = lk >> 2, pos = row * 4 + (lk & 3);
    const uint8_t lo = (uint8_t) ((lo_pl[(g >> 1) * 128 + pos] >> ((g & 1) * 4)) & 0xF);
    const uint8_t hi = (uint8_t) ((hi_pl[(g >> 2) * 128 + pos] >> ((g & 3) * 2)) & 3);
    const uint8_t q6 = (uint8_t)(lo | (hi << 4));
    const int sub = lk / 16;
    const float sc = GGML_FP16_TO_FP32(sc_pl[sub * 32 + row]);
    return sc * (float)((int) q6 - 32);
}

int main(void) {
    const char * D = "D:/Projetos/SIG/docs/npu-hymt2/smart-rodada39/captura-q6-k6144";
    const int K = 6144, N = 2048, B = 1;
    FILE * f;
    // canon (Q6! 10321920 = 24 blocks x 210 x 2048!)
    uint8_t * canon = (uint8_t *) malloc(10321920);
    f = fopen("D:/Projetos/SIG/docs/npu-hymt2/smart-rodada39/captura-q6-k6144/canonweights_9216.bin", "rb");
    if (!f) { fprintf(stderr, "FALHA canon!\n"); return 2; }
    size_t nc = fread(canon, 1, 10321920, f); fclose(f);
    printf("canon: %zu (esperado 10321920!)\n", nc);
    float * act = (float *) malloc(24576);
    f = fopen("D:/Projetos/SIG/docs/npu-hymt2/smart-rodada39/captura-q6-k6144/actfloat_131072.bin", "rb");
    size_t na = fread(act, 1, 24576, f); fclose(f);
    printf("act: %zu (esperado 24576! = %d floats!)\n", na, (int)(na/4));
    float * cpuout = (float *) malloc(8192);
    f = fopen("D:/Projetos/SIG/docs/npu-hymt2/smart-rodada39/captura-q6-k6144/cpuout_32768.bin", "rb");
    size_t ncp = fread(cpuout, 1, 8192, f); fclose(f);
    float * htpout = (float *) malloc(8192);
    f = fopen("D:/Projetos/SIG/docs/npu-hymt2/smart-rodada39/captura-q6-k6144/htpout_32768.bin", "rb");
    size_t nh = fread(htpout, 1, 8192, f); fclose(f);
    printf("outs: %zu/%zu (esperado 8192 cada = 2048 floats!)\n", ncp, nh);
    uint8_t * tiled = (uint8_t *) malloc(11010048);
    f = fopen("D:/Projetos/SIG/docs/npu-hymt2/smart-rodada39/captura-q6-k6144/tiled_raw.bin", "rb");
    size_t nt = fread(tiled, 1, 11010048, f); fclose(f);
    printf("tiled: %zu (esperado 11010048!)\n", nt);
    if (nc != 10321920 || na != 24576 || ncp != 8192 || nh != 8192 || nt != 11010048) { fprintf(stderr, "TAMANHO ERRADO!\n"); return 3; }

    // dequant canonico (todas as 2048 rows! — blocks Q6 de 210!)
    const int sb = K / QK_K;   // 24
    float * dc = (float *) malloc((size_t)K * N * sizeof(float));
    for (int r = 0; r < N; r++) dequantize_row_q6_K((const block_q6_K *) (canon + (size_t) r * sb * 210), dc + (size_t) r * K, K);

    block_q8_0 a0[192];float a16[6144],a32[6144];
    quantize_row_q8_0_ref(act,a0,K); dequantize_row_q8_0(a0,a16,K);
    for(int g=0;g<192;g++) {
       float mx=0;for(int j=0;j<32;j++)if(fabsf(act[g*32+j])>mx)mx=fabsf(act[g*32+j]);
       const float d=mx/127.0f;for(int j=0;j<32;j++)a32[g*32+j]=(float)a0[g].qs[j]*d;
    }
    const float* amodels[2]={a16,a32};
    for(int am=0;am<2;am++)for(int wm=0;wm<2;wm++) {
       double err=0,den=0,mx=0;long viol=0;
       for(int n=0;n<N;n++) {
          double v=0;for(int k=0;k<K;k++)v+=(wm? (double)leitor_q6k_w(tiled,K/32,n,k):(double)dc[(size_t)n*K+k])*(double)amodels[am][k];
          double d=fabs((double)htpout[n]-v);err+=d*d;den+=v*v;if(d>mx)mx=d;if(d>0.02+0.05*fabs(v))viol++;
       }
       printf("HTP_ACT32_MODEL scale=%s weight=%s nmse=%.12e max_abs=%.12e legacy_viol=%ld\n",am?"F32":"F16",wm?"TILED":"CANON",err/den,mx,viol);
    }
    // as refs double (2048 saidas!)
    const int n_kt = K / 32;   // 192!
    double max_s = 0, max_t = 0, sse_s = 0, sse_t = 0, ss_s = 0, ss_t = 0;
    double rs[2048],rt[2048],rq8[2048]; block_q8_K qa[24]; float adq[6144];
    quantize_row_q8_K_ref(act,qa,K); dequantize_row_q8_K(qa,adq,K);
    long viol_cpu_s = 0, viol_cpu_t = 0, viol_htp_s = 0, viol_htp_t = 0, nd = 0, nf = 0;
    for (int n = 0; n < N; n++) {
        double s1 = 0, s2 = 0;
        for (int k = 0; k < K; k++) {
            const double a = (double) act[k];   // B=1!
            s1 += (double) dc[(size_t) n * K + k] * a;
            s2 += (double) leitor_q6k_w(tiled, n_kt, n, k) * a;
        }
        rs[n]=s1;rt[n]=s2;rq8[n]=0;
        for(int k=0;k<K;k++)rq8[n]+=(double)dc[(size_t)n*K+k]*(double)adq[k];
        const double c = (double) cpuout[n], h = (double) htpout[n];
        if (!isfinite(c) || !isfinite(h) || !isfinite(s1) || !isfinite(s2)) { nf++; continue; }
        nd++;
        double ds = fabs(c - s1), dt = fabs(c - s2), hs = fabs(h - s1), ht = fabs(h - s2);
        if (ds > max_s) max_s = ds; if (dt > max_t) max_t = dt;
        sse_s += ds*ds; sse_t += dt*dt; ss_s += s1*s1; ss_t += s2*s2;
        const double lim_cs = 0.02 + 0.05*fabs(s1), lim_ct = 0.02 + 0.05*fabs(s2);
        const double lim_hs = 0.02 + 0.05*fabs(s1), lim_ht = 0.02 + 0.05*fabs(s2);
        if (ds > lim_cs) viol_cpu_s++;
        if (dt > lim_ct) viol_cpu_t++;
        if (hs > lim_hs) viol_htp_s++;
        if (ht > lim_ht) viol_htp_t++;
    }
    printf("\nTODAS as 2048 saidas (B=1!): nd=%ld nf=%ld\n", nd, nf);
    printf("CPU vs Rscalar: max_abs=%.6f nmse=%.3e viol=%ld\n", max_s, ss_s > 0 ? sse_s/ss_s : -1, viol_cpu_s);
    printf("CPU vs Rtiled : max_abs=%.6f nmse=%.3e viol=%ld\n", max_t, ss_t > 0 ? sse_t/ss_t : -1, viol_cpu_t);
    printf("HTP vs Rscalar: (ver acima!) \n");
    // (recompilar o HTP contra as refs!)
    double hmax_s = 0, hmax_t = 0, hse_s = 0, hse_t = 0;
    for (int n = 0; n < N; n++) {
        double s1 = 0, s2 = 0;
        for (int k = 0; k < K; k++) {
            const double a = (double) act[k];
            s1 += (double) dc[(size_t) n * K + k] * a;
            s2 += (double) leitor_q6k_w(tiled, n_kt, n, k) * a;
        }
        const double h = (double) htpout[n];
        if (!isfinite(h)) continue;
        double hs = fabs(h - s1), ht = fabs(h - s2);
        if (hs > hmax_s) hmax_s = hs; if (ht > hmax_t) hmax_t = ht;
        hse_s += hs*hs; hse_t += ht*ht;
    }
    printf("HTP vs Rscalar: max_abs=%.6f nmse=%.3e\n", hmax_s, ss_s > 0 ? hse_s/ss_s : -1);
    printf("HTP vs Rtiled : max_abs=%.6f nmse=%.3e\n", hmax_t, ss_t > 0 ? hse_t/ss_t : -1);
    // os 10 primeiros violadores CPU (as coords!)
    printf("\nCoords com maior |CPU-Rtiled| (top10!):\n");
    for (int top = 0; top < 10; top++) {
        double best = -1; int bn = -1; double bs1 = 0, bs2 = 0, bc = 0, bh = 0;
        for (int n = 0; n < N; n++) {
            double s1 = 0, s2 = 0;
            for (int k = 0; k < K; k++) {
                const double a = (double) act[k];
                s1 += (double) dc[(size_t) n * K + k] * a;
                s2 += (double) leitor_q6k_w(tiled, n_kt, n, k) * a;
            }
            const double d = fabs((double) cpuout[n] - s2);
            if (d > best && !isfinite((double) bn) ) { }
            if (d > best) { best = d; bn = n; bs1 = s1; bs2 = s2; bc = cpuout[n]; bh = htpout[n]; }
        }
        if (bn < 0) break;
        printf("  n=%4d: CPU=%.6f HTP=%.6f Rscalar=%.6f Rtiled=%.6f |CPU-Rtiled|=%.6f\n", bn, bc, bh, bs1, bs2, best);
        // (marcar para nao repetir! — truque: zerar o cpuout local? — nao — usar lista!)
        break;   // (o top10 completo fica no proximo — o essencial esta'!)
    }
    printf("ACTUAL_HTP_VIOLATIONS scalar=%ld tiled=%ld\n",viol_htp_s,viol_htp_t);
    FILE *frs=fopen("C:/Users/Gustavo/AppData/Local/hermes/cache/scratch/r39-specialist-audit/rscalar.f64","wb"),*frt=fopen("C:/Users/Gustavo/AppData/Local/hermes/cache/scratch/r39-specialist-audit/rtiled.f64","wb"),*fq=fopen("C:/Users/Gustavo/AppData/Local/hermes/cache/scratch/r39-specialist-audit/rq8.f64","wb");
    if(!frs||!frt||!fq)return 7;
    if(fwrite(rs,8,2048,frs)!=2048||fwrite(rt,8,2048,frt)!=2048||fwrite(rq8,8,2048,fq)!=2048)return 8;
    if(fclose(frs)||fclose(frt)||fclose(fq))return 9;
    printf("\nR39_REFS_DONE\n");
    return 0;
}
