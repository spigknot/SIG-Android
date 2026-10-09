// r28_fixture.c — R28: VACINA REAL (opcao C do parecer!)
// Usa o codigo EXTRAIDO AUTOMATICAMENTE do candidato (r28_cores_gen.h +
// r28_switch_gen.inc — gerados por r28_extrai.py com hashes!) EXERCITANDO O
// PATH REAL: switch do set_tensor -> repack REAL -> payload TILED FISICO
// -> LEITOR independente vs DEQUANT canonico em TODAS as entradas!
// MUTANTES (via sed no gerado, fora deste arquivo!): cases K removidos -> RED!
// Compilar: g++ -std=c++17 -fpermissive -O2 -I... r28_fixture.c ggml-quants.c -lm -o r28_fixture
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdint.h>
#include <setjmp.h>
#include <assert.h>
#include "ggml.h"
#include "ggml-impl.h"
#include "ggml-quants.h"

#define QK_K 256
#define HTP_MM_WEIGHT_TILE_SIZE_Q4_1 640
#define HTP_MM_WEIGHT_TILE_SIZE_Q6_K 896
static inline int64_t hex_round_up(int64_t x, int64_t n) { return ((x + n - 1) / n) * n; }

// === stubs de contorno (externos: log/contexto apenas!) ===
#include <stdarg.h>
static jmp_buf g_jmp;
static volatile int g_aborted = 0;
void ggml_abort(const char * file, int line, const char * fmt, ...) {
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "GGML_ABORT %s:%d: ", file, line); vfprintf(stderr, fmt, ap); va_end(ap);
    g_aborted = 1;
    longjmp(g_jmp, 1);   // o harness captura (contrato: rejeicao do assert!)
}
size_t ggml_row_size(enum ggml_type type, int64_t ne) {
    switch (type) {
        case GGML_TYPE_Q4_K: return (size_t)(ne / QK_K) * sizeof(block_q4_K);
        case GGML_TYPE_Q6_K: return (size_t)(ne / QK_K) * sizeof(block_q6_K);
        case GGML_TYPE_Q4_0: return (size_t)(ne / 32) * sizeof(block_q4_0);
        case GGML_TYPE_Q4_1: return (size_t)(ne / 32) * sizeof(block_q4_1);
        case GGML_TYPE_Q8_0: return (size_t)(ne / 32) * sizeof(block_q8_0);
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
// ggml_nbytes (o assert do switch usa!):
#define ggml_nbytes(t) ((size_t) (t)->nb[1] * (t)->ne[1] * (t)->ne[2] * (t)->ne[3])

// === adaptadores de contexto (declarados no harness — o codigo REAL os usa!) ===
typedef struct ggml_backend_buffer_type * ggml_backend_buffer_type_t;   // stub de tipo (o callback ignora o buft!)
// get_scale_min_k4 VERBATIM do ggml-quants.c (usada pelo repack_q4_K_tiled REAL!)
static inline void get_scale_min_k4(int j, const uint8_t * q, uint8_t * d, uint8_t * m) {
    if (j < 4) {
        *d = q[j] & 63; *m = q[j + 4] & 63;
    } else {
        *d = (uint8_t) ((q[j+4] & 0xF) | ((q[j-4] >> 6) << 4));
        *m = (uint8_t) ((q[j+4] >>  4) | ((q[j-4] >> 6) << 4));
    }
}

// stubs dos repacks NAO-K (nao exercitados neste teste; externos!)
static void repack_q4_0_tiled(const ggml_tensor * t, const void * d, size_t s) { (void)t;(void)d;(void)s; }
static void repack_q4_1_tiled(const ggml_tensor * t, const void * d, size_t s) { (void)t;(void)d;(void)s; }
static void repack_q8_0_tiled(const ggml_tensor * t, const void * d, size_t s) { (void)t;(void)d;(void)s; }
static void repack_mxfp4_tiled(const ggml_tensor * t, const void * d, size_t s) { (void)t;(void)d;(void)s; }

// === O CODIGO REAL EXTRAIDO (verbatim do candidato!): q6_K_get_quant,
// is_repack_type, tiled_row_size, repack_q4_K_tiled, repack_q6_K_tiled,
// get_alloc_size! ===
#include "r28_cores_gen.h"

// === o CALLBACK REAL (o switch extraido, dentro da funcao!) ===
static void set_tensor_real(struct ggml_tensor * tensor, const void * data, size_t offset, size_t size) {
#include "r28_switch_gen.inc"
}

// === LEITORES INDEPENDENTES (validados nos oraculos R22/R27!) ===
static float leitor_q4k_w(const uint8_t * tiled, int n_k_tiles, int64_t r, int64_t k) {
    const int ct = (int)(r / 32), row = (int)(r % 32);
    const int kt = (int)(k / 32), k_loc = (int)(k % 32);
    const uint8_t * tile = tiled + ((size_t) ct * n_k_tiles + kt) * 640;
    const int cp = k_loc / 2;
    const uint8_t qv = tile[cp * 32 + row];
    const uint8_t q = (k_loc & 1) ? (qv >> 4) : (qv & 0x0F);
    const ggml_half * sc = (const ggml_half *) (tile + 512);
    const float D = GGML_FP16_TO_FP32(sc[2 * row + 0]);
    const float M = GGML_FP16_TO_FP32(sc[2 * row + 1]);
    return D * (float) q + M;
}
static float leitor_q6k_w(const uint8_t * tiled, int n_k_tiles, int64_t r, int64_t k) {
    const int ct = (int)(r / 32), row = (int)(r % 32);
    const int kt = (int)(k / 32), lk = (int)(k % 32);
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
    printf("=== r28_fixture (VACINA REAL: codigo extraido verbatim!) ===\n");
    const int K = 256, N = 64;
    float * src = (float *) malloc((size_t)K * N * sizeof(float));
    for (int i = 0; i < K * N; i++) src[i] = (float)(sin((double)i * 0.017) * 1.7);
    int failures = 0;
    const int n_k_tiles = K / 32;

    // ---------------- T1: Q4_K: upload REAL + leitor em TODAS as entradas! ----------
    {
        block_q4_K * q = (block_q4_K *) malloc((size_t)N * (K / QK_K) * sizeof(block_q4_K));
        quantize_q4_K(src, q, N, K, NULL);
        float * dc = (float *) malloc((size_t)K * N * sizeof(float));
        for (int r = 0; r < N; r++) dequantize_row_q4_K(&q[r * (K / QK_K)], dc + (size_t)r * K, K);
        uint8_t * tiled = (uint8_t *) calloc(1, 10240 + 4096);
        struct ggml_tensor t; memset(&t, 0, sizeof(t));
        t.type = GGML_TYPE_Q4_K; t.ne[0] = K; t.ne[1] = N; t.ne[2] = 1; t.ne[3] = 1;
        t.nb[0] = 1; t.nb[1] = (size_t)(K / QK_K) * sizeof(block_q4_K);   // 144 canonico!
        t.data = tiled; snprintf(t.name, sizeof(t.name), "testq4k");
        const size_t canon = (size_t)N * (K / QK_K) * sizeof(block_q4_K);
        g_aborted = 0;
        if (setjmp(g_jmp) == 0) {
            set_tensor_real(&t, q, 0, canon);   // O SWITCH REAL! (repack_q4_K_tiled!)
        }
        long n_bad = 0; double max_abs = 0; long n_nan = 0;
        for (int r = 0; r < N; r++) for (int k = 0; k < K; k++) {
            float w = leitor_q4k_w(tiled, n_k_tiles, r, k);
            float ref = dc[(size_t)r * K + k];
            if (!isfinite(w) || !isfinite(ref)) { n_nan++; continue; }
            double d = fabs((double)w - (double)ref);
            if (d > max_abs) max_abs = d;
            if (d > 0.05) n_bad++;
        }
        int ok = (g_aborted == 0 && n_nan == 0 && n_bad == 0);
        printf("  [%s] T1 Q4_K upload REAL: leitor vs dequant nas %d entradas: n_bad=%ld n_nan=%ld max_abs=%.6f%s\n",
               ok ? "OK " : "RED", K * N, n_bad, n_nan, max_abs, g_aborted ? " (ABORTADO!)" : "");
        if (!ok) failures++;
    }

    // ---------------- T2: Q6_K: upload REAL + leitor em TODAS as entradas! ----------
    {
        block_q6_K * q = (block_q6_K *) malloc((size_t)N * (K / QK_K) * sizeof(block_q6_K));
        quantize_q6_K(src, q, N, K, NULL);
        float * dc = (float *) malloc((size_t)K * N * sizeof(float));
        for (int r = 0; r < N; r++) dequantize_row_q6_K(&q[r * (K / QK_K)], dc + (size_t)r * K, K);
        uint8_t * tiled = (uint8_t *) calloc(1, 14336 + 4096);
        struct ggml_tensor t; memset(&t, 0, sizeof(t));
        t.type = GGML_TYPE_Q6_K; t.ne[0] = K; t.ne[1] = N; t.ne[2] = 1; t.ne[3] = 1;
        t.nb[0] = 1; t.nb[1] = (size_t)(K / QK_K) * sizeof(block_q6_K);   // 210 canonico!
        t.data = tiled; snprintf(t.name, sizeof(t.name), "testq6k");
        const size_t canon = (size_t)N * (K / QK_K) * sizeof(block_q6_K);
        g_aborted = 0;
        if (setjmp(g_jmp) == 0) {
            set_tensor_real(&t, q, 0, canon);   // O SWITCH REAL! (repack_q6_K_tiled!)
        }
        long n_bad = 0; double max_abs = 0; long n_nan = 0;
        for (int r = 0; r < N; r++) for (int k = 0; k < K; k++) {
            float w = leitor_q6k_w(tiled, n_k_tiles, r, k);
            float ref = dc[(size_t)r * K + k];
            if (!isfinite(w) || !isfinite(ref)) { n_nan++; continue; }
            double d = fabs((double)w - (double)ref);
            if (d > max_abs) max_abs = d;
            if (d > 0.05) n_bad++;
        }
        int ok = (g_aborted == 0 && n_nan == 0 && n_bad == 0);
        printf("  [%s] T2 Q6_K upload REAL: leitor vs dequant nas %d entradas: n_bad=%ld n_nan=%ld max_abs=%.6f%s\n",
               ok ? "OK " : "RED", K * N, n_bad, n_nan, max_abs, g_aborted ? " (ABORTADO!)" : "");
        if (!ok) failures++;
    }

    // ---------------- T3: alloc REAL (tensores com nb corretos!) ----------
    {
        struct ggml_tensor t; memset(&t, 0, sizeof(t));
        t.type = GGML_TYPE_Q4_K; t.ne[0] = K; t.ne[1] = N; t.ne[2] = 1; t.ne[3] = 1;
        t.nb[0] = 1; t.nb[1] = (size_t)(K / QK_K) * sizeof(block_q4_K);
        size_t a4 = ggml_backend_hexagon_buffer_type_get_alloc_size(NULL, &t);
        struct ggml_tensor t6; memset(&t6, 0, sizeof(t6));
        t6.type = GGML_TYPE_Q6_K; t6.ne[0] = K; t6.ne[1] = N; t6.ne[2] = 1; t6.ne[3] = 1;
        t6.nb[0] = 1; t6.nb[1] = (size_t)(K / QK_K) * sizeof(block_q6_K);   // 210! (correto!)
        size_t a6 = ggml_backend_hexagon_buffer_type_get_alloc_size(NULL, &t6);
        int ok = (a4 == 160 * 64) && (a6 == (size_t)(224 * 64));   // 10240 e 14336!
        printf("  [%s] T3 alloc REAL: Q4_K=%zu (tiled 10240/canon 9216!) Q6_K=%zu (tiled 14336/canon 13440!)\n",
               ok ? "OK " : "RED", a4, a6);
        if (!ok) failures++;
    }

    // ---------------- T4: negativos (offset!=0 e size parcial -> REJEITADO!) ----------
    {
        struct ggml_tensor t; memset(&t, 0, sizeof(t));
        t.type = GGML_TYPE_Q4_K; t.ne[0] = K; t.ne[1] = N; t.ne[2] = 1; t.ne[3] = 1;
        t.nb[0] = 1; t.nb[1] = (size_t)(K / QK_K) * sizeof(block_q4_K);
        uint8_t * buf = (uint8_t *) calloc(1, 10240 + 4096);
        t.data = buf;
        float * src2 = (float *) malloc((size_t)K * N * sizeof(float));
        memcpy(src2, src, (size_t)K * N * sizeof(float));
        block_q4_K * q = (block_q4_K *) malloc((size_t)N * (K / QK_K) * sizeof(block_q4_K));
        quantize_q4_K(src2, q, N, K, NULL);
        const size_t canon = (size_t)N * (K / QK_K) * sizeof(block_q4_K);
        // offset != 0: o pack real DEVE rejeitar (GGML_ASSERT(offset==0) do pack!)
        g_aborted = 0;
        if (setjmp(g_jmp) == 0) { set_tensor_real(&t, q, 32, canon); }
        int ok_off = (g_aborted == 1);   // rejeitado!
        printf("  [%s] T4a offset!=0 REJEITADO pelo pack real (abort=%d esperado 1!)\n", ok_off ? "OK " : "RED", g_aborted);
        if (!ok_off) failures++;
        // size parcial: tambem rejeitado (assert do pack!)
        g_aborted = 0;
        if (setjmp(g_jmp) == 0) { set_tensor_real(&t, q, 0, canon / 2); }
        int ok_sz = (g_aborted == 1);
        printf("  [%s] T4b size parcial REJEITADO (abort=%d esperado 1!)\n", ok_sz ? "OK " : "RED", g_aborted);
        if (!ok_sz) failures++;
    }

    printf("\nTOTAL: %d RED\nEXIT=%d\n", failures, failures ? 1 : 0);
    return failures ? 1 : 0;
}
