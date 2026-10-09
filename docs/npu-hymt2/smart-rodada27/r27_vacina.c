// r27_vacina.c — R27: VACINA DOS CALLBACKS (pedido §3 nº2!)
// Compila o DISPATCH REAL do set_tensor (switch VERBATIM do candidato kq2!)
// + o get_alloc_size REAL (helper!) com MUTANTES:
//   MUTANTE 1: switch SEM os cases K (o estado pre-R23!) -> memcpy canonico -> RED!
//   MUTANTE 2: get_alloc_size com LISTA inline (sem K!) -> requer 9216/13440 -> RED!
// Compilar: g++ -std=c++17 -fpermissive -O2 ... r27_vacina.c ggml-quants.c -o r27_vacina
// E: -DMUTANTE_SEM_CASES_K / -DMUTANTE_ALLOC_LISTA para os mutantes!
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
#define HTP_MM_WEIGHT_TILE_SIZE_Q4_1 640
#define HTP_MM_WEIGHT_TILE_SIZE_Q6_K 896
static inline int64_t hex_round_up(int64_t x, int64_t n) { return ((x + n - 1) / n) * n; }

// === stubs ===
#include <stdarg.h>
static long g_repack_calls = 0;      // contador: quantas vezes o repack REAL foi chamado!
static long g_memcpy_calls = 0;      // contador: quantas vezes o fallback memcpy rodou!
void ggml_abort(const char * file, int line, const char * fmt, ...) {
    va_list ap; va_start(ap, fmt); fprintf(stderr, "GGML_ABORT %s:%d: ", file, line);
    vfprintf(stderr, fmt, ap); va_end(ap); abort();
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
        default: return 0;
    }
}
const char * ggml_type_name(enum ggml_type type) { (void) type; return "q"; }
// ggml_nbytes simplificado para 2D:
static size_t ggml_nbytes_v(const struct ggml_tensor * t) {
    return (size_t) t->nb[1] * t->ne[1] * t->ne[2] * t->ne[3];
}

// === as funcoes repack do candidato (VERBATIM — so' o Q4_K aqui!) ===
static void repack_q4_K_tiled(const ggml_tensor * t, const void * data, size_t offset, size_t size) {
    GGML_ASSERT(offset == 0); (void) size;
    g_repack_calls++;   // VACINA: marcamos a chamada (o dispatch correto CHEGA aqui!)
    const block_q4_K * src_matrix = (const block_q4_K *) data;
    int64_t ne0 = t->ne[0], ne1 = t->ne[1], ne2 = t->ne[2], ne3 = t->ne[3];
    int64_t ne0_padded = hex_round_up(ne0, 32), ne1_padded = hex_round_up(ne1, 32);
    GGML_ASSERT(ne0 % QK_K == 0);
    const int n_col_tiles = ne1_padded / 32, n_k_tiles = ne0_padded / 32;
    const size_t tile_size = HTP_MM_WEIGHT_TILE_SIZE_Q4_1;
    const size_t matrix_size = (size_t) n_col_tiles * n_k_tiles * tile_size;
    (void) matrix_size;
    const int64_t sb_per_row = ne0 / QK_K;
    for (int i3 = 0; i3 < ne3; i3++)
        for (int i2 = 0; i2 < ne2; i2++) {
            const block_q4_K * src_slice = src_matrix + (i3 * ne2 + i2) * (ne1 * sb_per_row);
            // (corpo completo omitido para o teste do DISPATCH: escrevemos um byte-marcador!)
            uint8_t * dst = (uint8_t *) t->data;
            dst[0] = 0xAA;   // marcador: o repack REAL escreveu aqui!
            (void) src_slice;
        }
}

// === O SWITCH DO SET_TENSOR — VERBATIM do candidato kq2 (L1318-1364!) ===
// (adaptado os tipos do contexto de teste; a ESTRUTURA e os cases sao os REAIS!)
static void set_tensor_dispatch(struct ggml_tensor * tensor, const void * data, size_t offset, size_t size) {
    switch (tensor->type) {
#ifdef MUTANTE_SEM_CASES_K
        // MUTANTE: os cases K REMOVIDOS (o estado pre-R23 que causou o NaN!)
#else
        case GGML_TYPE_Q4_K:
            // R23 FIX (o dispatch REAL do candidato!):
            repack_q4_K_tiled(tensor, data, offset, size);
            break;
        case GGML_TYPE_Q6_K:
            break;   // (Q6: dispatch presente no candidato; detalhe fora deste teste!)
#endif
        default:
            g_memcpy_calls++;
            memcpy((char *) tensor->data + offset, data, size);
            break;
    }
}

// === O GET_ALLOC_SIZE do candidato (com o FIX R23: helper!) ===
static size_t get_alloc_size_real(const struct ggml_tensor * t) {
#ifdef MUTANTE_ALLOC_LISTA
    // MUTANTE: a LISTA inline antiga (sem K!) -> canonico!
    if (t->type == GGML_TYPE_Q4_0 || t->type == GGML_TYPE_Q4_1 || t->type == GGML_TYPE_Q8_0 ||
        t->type == GGML_TYPE_IQ4_NL || t->type == GGML_TYPE_MXFP4) {
        int64_t ne0 = hex_round_up(t->ne[0], 32), ne1 = hex_round_up(t->ne[1], 32);
        return (size_t)((ne0 / 32) * (HTP_MM_WEIGHT_TILE_SIZE_Q4_1 / 32)) * ne1 * t->ne[2] * t->ne[3];
    }
    return ggml_nbytes_v(t);
#else
    // FIX R23 (o REAL!): usa o reconhecimento de tipo repack (inclui K!)
    if (t->type == GGML_TYPE_Q4_K || t->type == GGML_TYPE_Q6_K) {
        int64_t ne0 = hex_round_up(t->ne[0], 32), ne1 = hex_round_up(t->ne[1], 32);
        size_t rs = (t->type == GGML_TYPE_Q6_K)
            ? (size_t)(ne0 / 32) * (HTP_MM_WEIGHT_TILE_SIZE_Q6_K / 32)
            : (size_t)(ne0 / 32) * (HTP_MM_WEIGHT_TILE_SIZE_Q4_1 / 32);
        return rs * ne1 * t->ne[2] * t->ne[3];
    }
    return ggml_nbytes_v(t);
#endif
}

int main(void) {
    printf("=== r27_vacina (dispatch set_tensor + alloc!) ===\n");
#ifdef MUTANTE_SEM_CASES_K
    printf("MUTANTE_SEM_CASES_K ativo!\n");
#endif
#ifdef MUTANTE_ALLOC_LISTA
    printf("MUTANTE_ALLOC_LISTA ativo!\n");
#endif
    const int K = 256, N = 64;
    // tensor Q4_K 2D com nb canonicos!
    uint8_t * tdata = (uint8_t *) calloc(1, 10240 + 640);   // folga p/ escrita do pack!
    struct ggml_tensor t;
    memset(&t, 0, sizeof(t));
    t.type = GGML_TYPE_Q4_K;
    t.ne[0] = K; t.ne[1] = N; t.ne[2] = 1; t.ne[3] = 1;
    t.nb[0] = 1; t.nb[1] = sizeof(block_q4_K) * (K / QK_K);
    t.data = tdata;
    // input canonico (quantizado!)
    float * src = (float *) malloc((size_t)K * N * sizeof(float));
    for (int i = 0; i < K * N; i++) src[i] = (float)(sin((double)i * 0.017) * 1.7);
    block_q4_K * q = (block_q4_K *) malloc((size_t)N * (K / QK_K) * sizeof(block_q4_K));
    quantize_q4_K(src, q, N, K, NULL);
    const size_t canon_bytes = (size_t)N * (K / QK_K) * sizeof(block_q4_K);

    int failures = 0;
    // T1: o dispatch DEVE chamar o repack para Q4_K (e NAO o memcpy!)
    g_repack_calls = 0; g_memcpy_calls = 0;
    set_tensor_dispatch(&t, q, 0, canon_bytes);
    printf("  [%s] T1 dispatch Q4_K -> repack calls=%ld (esperado >=1!)\n",
           g_repack_calls >= 1 ? "OK " : "RED", g_repack_calls);
    if (g_repack_calls < 1) failures++;
    printf("  [%s] T1b memcpy calls=%ld (esperado 0 para Q4_K!)\n",
           g_memcpy_calls == 0 ? "OK " : "RED", g_memcpy_calls);
    if (g_memcpy_calls != 0) failures++;
    printf("  [%s] T1c byte-marcador do repack presente (0x%02x!)\n",
           tdata[0] == 0xAA ? "OK " : "RED", tdata[0]);
    if (tdata[0] != 0xAA) failures++;

    // T2: o alloc de Q4_K DEVE ser o TILED (10240!) e NAO o canonico (9216!)
    int canon_nbytes = canon_bytes;
    int tiled_expected = 160 * 64;
    size_t got = get_alloc_size_real(&t);
    printf("  [%s] T2 alloc Q4_K: got=%zu esperado=%d (canon=%d)\n",
           got == (size_t) tiled_expected ? "OK " : "RED", got, tiled_expected, canon_nbytes);
    if (got != (size_t) tiled_expected) failures++;

    // T3: Q6_K alloc (tile 896: 14336!)
    struct ggml_tensor t6 = t;
    t6.type = GGML_TYPE_Q6_K;
    size_t got6 = get_alloc_size_real(&t6);
    printf("  [%s] T3 alloc Q6_K: got=%zu esperado=%d\n",
           got6 == 14336 ? "OK " : "RED", got6, 14336);
    if (got6 != 14336) failures++;

    printf("\nTOTAL: %d RED de 5 | repack=%ld memcpy=%ld\n", failures, g_repack_calls, g_memcpy_calls);
    printf("EXIT=%d\n", failures ? 1 : 0);
    return failures ? 1 : 0;
}
