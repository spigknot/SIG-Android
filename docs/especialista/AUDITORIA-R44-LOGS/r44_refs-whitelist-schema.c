// r42_refs.c — R42: LOADER ESTRITO em DOIS MODOS (sem WARN que lave erro!)
//   MODE NORMAL: meta OBRIGATORIO+parseado (attempt/K/N/B/type!), schema/sizes
//   EXATOS, finitude ESTRITA (nf>0 ou nd<2048 => INVALID!), denom zero/nao-
//   finito => INVALID!; so' depois emite numeros. exit!=0 em tudo invalido.
//   MODE RECONSTRUCAO_R39 (--reconstruct-r39): whitelist fullSHA dos 5
//   originais R39; metadata reconstruida SEPARADA (o meta falso NAO e' lido
//   como schema!); limites declarados; rotulado.
// Uso: ./r42_refs <dir> [--reconstruct-r39]
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

// whitelist dos ORIGINAIS R39 (fullSHA! — a identidade dos 5!)
static const char * SHA_CANON = "0103fc0cc34700ff5b194e35dd89b088fff94c64197c58c3c03ef515e46d3640";
static const char * SHA_ACT   = "8e126e530a6242c70a698dd00936de52890d93eea0960e1eba942b21cf2fd945";
static const char * SHA_CPU   = "f343aaa6a9fe746178bd80cd05770fdeb39bb791dcae8fef489bd8f6e78cb315";
static const char * SHA_HTP   = "70f06e9494542513f1f9de7f837506b479ab06ae2bf078202868e4510c635e94";
static const char * SHA_TILED = "66218735367089fbc9fe33f403c0c81e62214ef167779b88be644fa1104da1ff";

static const char * NOMES_WL[5] = { "canonweights_9216.bin", "actfloat_131072.bin", "cpuout_32768.bin", "htpout_32768.bin", "tiled_raw.bin" };
static uint8_t * load_checked(const char * d, const char * nome, size_t expected, int recon) {
    // R44: nome desconhecido NAO passa sem whitelist!
    int wlok = 0;
    for (int wi = 0; wi < 5; wi++) if (strcmp(nome, NOMES_WL[wi]) == 0) wlok = 1;
    if (!wlok) { fprintf(stderr, "INVALID: nome fora da whitelist: %s!\n", nome); exit(30); }
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s", d, nome);
    FILE * f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "INVALID: fopen falhou: %s\n", path); exit(10); }
    fseek(f, 0, SEEK_END); const long sz = ftell(f); fseek(f, 0, SEEK_SET);
    if (sz != (long) expected) { fprintf(stderr, "INVALID: %s tem %ld (esperado %zu!)\n", nome, sz, expected); exit(11); }
    uint8_t * buf = (uint8_t *) malloc(expected);
    if (!buf) { fprintf(stderr, "INVALID: malloc %zu!\n", expected); exit(12); }
    const size_t rd = fread(buf, 1, expected, f);
    if (rd != expected || fclose(f) != 0) { fprintf(stderr, "INVALID: %s read/close falhou!\n", nome); exit(13); }
    if (recon) {
        // o hash do ARQUIVO ORIGINAL (whitelist!) — invoca sha256sum e compara
        char cmd[1200];
        snprintf(cmd, sizeof(cmd), "sha256sum '%s'", path);   // R43: SEM cut (full 64 hex!)
        FILE * p2 = popen(cmd, "r");
        char got[256] = {0};
        int hok = 0;
        if (p2) { if (fgets(got, sizeof(got), p2)) hok = 1; const int pc = pclose(p2); if (pc != 0) hok = 0; }
        if (!hok) { fprintf(stderr, "INVALID: hash tool falhou (%s!)\n", nome); exit(17); }
        got[64] = 0;   // EXATAMENTE 64 hex (o resto e' ' <file>'!)
        size_t glen = strlen(got);
        if (glen != 64) { fprintf(stderr, "INVALID: digest com %zu chars (exigido 64!)\n", glen); exit(18); }
        for (size_t gi = 0; gi < 64; gi++) {
            const char c = got[gi];
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                fprintf(stderr, "INVALID: digest com char nao-hex pos %zu!\n", gi); exit(19);
            }
        }
        const char * want = NULL;
        if (strstr(nome, "canon")) want = SHA_CANON;
        else if (strstr(nome, "act")) want = SHA_ACT;
        else if (strstr(nome, "cpuout")) want = SHA_CPU;
        else if (strstr(nome, "htpout")) want = SHA_HTP;
        else if (strstr(nome, "tiled")) want = SHA_TILED;
        if (want && strcmp(got, want) != 0) {
            fprintf(stderr, "INVALID(REPLAY): %s SHA fora da whitelist R39: %s (esperado %s!)\n", nome, got, want);
            exit(16);
        }
        fprintf(stderr, "RECONSTRUCAO_R39: %s SHA ok (%s)\n", nome, got);
    }
    return buf;
}

int main(int argc, char ** argv) {
    if (argc < 2) { fprintf(stderr, "uso: %s <dir> [--reconstruct-r39]\n", argv[0]); return 2; }
    const char * D = argv[1];
    const int recon = (argc > 2 && strcmp(argv[2], "--reconstruct-r39") == 0);
    const int K = 6144, N = 2048;
    if (recon) fprintf(stderr, "MODO: RECONSTRUCAO_R39 (whitelist SHA! metadata reconstruida SEPARADA!)\n");
    else       fprintf(stderr, "MODO: NORMAL (meta obrigatoria/parseada! finitude/schema ESTRITOS!)\n");

    // ===== META (modo NORMAL: OBRIGATORIO + parseado; RECON: reconstruida!) =====
    int mK = -1, mN = -1, mB = -1; char mtype[32] = {0}, mattempt[128] = {0};
    if (!recon) {
        char path[1024]; snprintf(path, sizeof(path), "%s/meta.txt", D);
        FILE * fm = fopen(path, "r");
        if (!fm) { fprintf(stderr, "INVALID: meta.txt AUSENTE (modo NORMAL!)\n"); return 20; }
        if (fscanf(fm, "attempt=%127s K=%d N=%d B=%d type=%31s", mattempt, &mK, &mN, &mB, mtype) != 5) {
            fclose(fm); fprintf(stderr, "INVALID: meta.txt nao parseia (schema!)\n"); return 21;
        }
        fclose(fm);
        if (mK != K || mN != N || mB != 1 || strcmp(mtype, "q6_K") != 0) {
            fprintf(stderr, "INVALID: meta DIVERGENTE do esperado (K=%d N=%d B=%d type=%s vs K=6144 N=2048 B=1 q6_K!)\n", mK, mN, mB, mtype);
            return 22;
        }
        printf("META ok: attempt=%s K=%d N=%d B=%d type=%s\n", mattempt, mK, mN, mB, mtype);
    } else {
        printf("RECONSTRUCAO_R39: metadata reconstruida (K=6144 N=2048 B=1 type=q6_K — dos tamanhos/contrato!) — o meta FALSO do R39 NAO e' lido como schema!\n");
    }

    const size_t CANON = (size_t)(K/QK_K) * 210 * N, ACTB = (size_t) K*4, OUTB = (size_t) N*4, TILED = (size_t)(K/32)*(N/32)*896;
    uint8_t * canon = load_checked(D, "canonweights_9216.bin", CANON, recon);
    float * act = (float *) load_checked(D, "actfloat_131072.bin", ACTB, recon);
    float * cpuout = (float *) load_checked(D, "cpuout_32768.bin", OUTB, recon);
    float * htpout = (float *) load_checked(D, "htpout_32768.bin", OUTB, recon);
    uint8_t * tiled = load_checked(D, "tiled_raw.bin", TILED, recon);
    // finitude dos INPUTS (antes de qualquer conta!)
    long act_bad = 0, out_bad = 0;
    for (int i = 0; i < K; i++) if (!isfinite(act[i])) act_bad++;
    for (int i = 0; i < N; i++) { if (!isfinite(cpuout[i])) out_bad++; if (!isfinite(htpout[i])) out_bad++; }
    if (act_bad || out_bad) { fprintf(stderr, "INVALID: finitude dos inputs (act_bad=%ld out_bad=%ld!)\n", act_bad, out_bad); return 23; }

    const int sb = K / QK_K;
    float * dc = (float *) malloc((size_t)K*N*sizeof(float));
    if (!dc) { fprintf(stderr, "INVALID: alloc dc!\n"); return 24; }
    for (int r = 0; r < N; r++) dequantize_row_q6_K((const block_q6_K *)(canon + (size_t)r*sb*210), dc + (size_t)r*K, K);
    long dc_bad = 0; for (int i = 0; i < K*N; i++) if (!isfinite(dc[i])) dc_bad++;
    if (dc_bad) { fprintf(stderr, "INVALID: dequant (dc_bad=%ld!)\n", dc_bad); return 25; }

    double * s1v = (double *) malloc(N*sizeof(double));
    double * s2v = (double *) malloc(N*sizeof(double));
    if (!s1v || !s2v) { fprintf(stderr, "INVALID: alloc refs!\n"); return 26; }
    for (int n = 0; n < N; n++) {
        double s1 = 0, s2 = 0;
        for (int k = 0; k < K; k++) {
            const double a = (double) act[k];
            s1 += (double) dc[(size_t) n*K + k] * a;
            s2 += (double) leitor_q6k_w(tiled, K/32, n, k) * a;
        }
        s1v[n] = s1; s2v[n] = s2;
    }
    // finitude ESTRITA das refs (antes do denominador!)
    long r_bad = 0; for (int n = 0; n < N; n++) { if (!isfinite(s1v[n]) || !isfinite(s2v[n])) r_bad++; }
    if (r_bad) { fprintf(stderr, "INVALID: refs nao-finitas (%ld!)\n", r_bad); return 27; }
    double d1 = 0, d2 = 0;
    for (int n = 0; n < N; n++) { d1 += s1v[n]*s1v[n]; d2 += s2v[n]*s2v[n]; }
    if (!(d1 > 0) || !(d2 > 0) || !isfinite(d1) || !isfinite(d2)) {
        fprintf(stderr, "INVALID: DENOMINADOR zero/nao-finito (d1=%g d2=%g!) — nao emito nmse=-1 como valido!\n", d1, d2);
        return 28;
    }

    long nd = 0, nf = 0, vc1 = 0, vc2 = 0, vh1 = 0, vh2 = 0;
    double m_c1 = 0, m_c2 = 0, m_h1 = 0, m_h2 = 0, e_c1 = 0, e_c2 = 0, e_h1 = 0, e_h2 = 0;
    for (int n = 0; n < N; n++) {
        if (!isfinite(cpuout[n]) || !isfinite(htpout[n])) { nf++; continue; }
        nd++;
        const double ec1 = fabs((double)cpuout[n]-s1v[n]), ec2 = fabs((double)cpuout[n]-s2v[n]);
        const double eh1 = fabs((double)htpout[n]-s1v[n]), eh2 = fabs((double)htpout[n]-s2v[n]);
        if (ec1>m_c1) m_c1=ec1; if (ec2>m_c2) m_c2=ec2; if (eh1>m_h1) m_h1=eh1; if (eh2>m_h2) m_h2=eh2;
        e_c1+=ec1*ec1; e_c2+=ec2*ec2; e_h1+=eh1*eh1; e_h2+=eh2*eh2;
        if (ec1 > 0.02+0.05*fabs(s1v[n])) vc1++;
        if (ec2 > 0.02+0.05*fabs(s2v[n])) vc2++;
        if (eh1 > 0.02+0.05*fabs(s1v[n])) vh1++;
        if (eh2 > 0.02+0.05*fabs(s2v[n])) vh2++;
    }
    // ESTRITO: nf>0 ou nd incompleto => INVALID (sem PASS no subconjunto!)
    if (nf > 0 || nd != N) {
        fprintf(stderr, "INVALID: nd=%ld nf=%ld (exigido nd=%d nf=0!) — SEM PASS parcial!\n", nd, nf, N);
        return 29;
    }
    printf("\nnd=%ld nf=%ld (completo!)\n", nd, nf);
    printf("CPU vs Rscalar: nmse=%.6e max=%.6f VIOL=%ld\n", e_c1/d1, m_c1, vc1);
    printf("CPU vs Rtiled : nmse=%.6e max=%.6f VIOL=%ld\n", e_c2/d2, m_c2, vc2);
    printf("HTP vs Rscalar: nmse=%.6e max=%.6f VIOL=%ld\n", e_h1/d1, m_h1, vh1);
    printf("HTP vs Rtiled : nmse=%.6e max=%.6f VIOL=%ld\n", e_h2/d2, m_h2, vh2);
    printf("PIN(5e-4): CPU/Rs=%s CPU/Rt=%s HTP/Rs=%s HTP/Rt=%s (LEGACY_FAIL preservado separado!)\n",
        e_c1/d1<5e-4?"PASS":"FAIL", e_c2/d2<5e-4?"PASS":"FAIL", e_h1/d1<5e-4?"PASS":"FAIL", e_h2/d2<5e-4?"PASS":"FAIL");
    // EXCESSO ao limite (o objeto DIFERENTE do top absoluto — §1!)
    printf("\nTOP10 EXCESSO/limite (HTP vs Rtiled — o objeto novo!):\n");
    int used[2048] = {0};
    for (int t = 0; t < 10; t++) {
        double best = -1; int bn = -1; double bexc = 0;
        for (int n = 0; n < N; n++) {
            if (used[n]) continue;
            const double lim = 0.02 + 0.05*fabs(s2v[n]);
            const double exc = fabs((double)htpout[n]-s2v[n]) / lim;
            if (exc > best) { best = exc; bn = n; bexc = exc; }
        }
        if (bn < 0) break;
        used[bn] = 1;
        printf("  #%d n=%4d excesso=%.4f (err=%.6f lim=%.6f ref=%.6f!)\n",
               t+1, bn, bexc, fabs((double)htpout[bn]-s2v[bn]), 0.02+0.05*fabs(s2v[bn]), s2v[bn]);
    }
    printf("\nR44_LOADER_DONE modo=%s exit=0\n", recon ? "RECONSTRUCAO_R39" : "NORMAL");
    return 0;
}
