// r30_fused_test.c — R30: FIXTURE da FUSAO (parecer §4!)
// Liga-se ao codigo REAL: is_mergeable_mul_mat (VERBATIM do candidato!) +
// a expressao REAL do src1_row_size do precompute_fused_ffn_params!
// Q4_1 = controle; Q4_K = alvo; Q6_K = rejeicao (guard!); F16 = controle!
// MUTANTE (via sed): retira o Q4_K do row_size => RED!
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

// === stubs minimos (valores DISTINTOS para o teste DETECTAR o formato!) ===
typedef enum { GGML_TYPE_F16=0, GGML_TYPE_F32=1, GGML_TYPE_Q4_0=2, GGML_TYPE_Q4_1=3,
               GGML_TYPE_Q8_0=4, GGML_TYPE_Q4_K=12, GGML_TYPE_Q6_K=14 } ggml_type_t;
enum { GGML_OP_NONE=0, GGML_OP_MUL_MAT=1 };
typedef struct ggml_tensor {
    int op;
    int type;
    struct ggml_tensor * src[2];
} ggml_tensor;
static int ggml_is_quantized(int t) { return t >= GGML_TYPE_Q4_0; }         // stub!
static int mm_is_hmx_eligible(const ggml_tensor * t) { (void) t; return 0; } // stub!

// os row_size (DISTINTOS: 640 = q8_1; 512 = q8_0 — o teste distingue!)
static size_t htp_mm_q8_1_tiled_row_size(int ne10) { return 640; (void) ne10; }
static size_t htp_mm_q8_0_tiled_row_size(int ne10) { return 512; (void) ne10; }

// === O PREDICADO REAL (extraido VERBATIM!) ===
#include "r30_merge_gen.inc"

// === a EXPRESSAO REAL do src1_row_size (extraida da L3151!) dentro de funcao ===
static size_t test_src1_row_size(int wtype, int ne10) {
#include "r30_rowsize_gen.inc"
    return src1_row_size;
}

int main(void) {
    printf("=== r30_fused_test (fixture da fusao: funcao REAL!) ===\n");
    int failures = 0;
    // ---- T1: o PREDICADO real (controle/alvo/rejeicao!) ----
    ggml_tensor wt = {0}, act = {0}, node = {0};
    act.type = GGML_TYPE_F32;
    node.op = GGML_OP_MUL_MAT;
    node.src[0] = &wt; node.src[1] = &act;
    wt.type = GGML_TYPE_Q4_1;
    int m41 = is_mergeable_mul_mat(&node);
    wt.type = GGML_TYPE_Q4_K;
    int m4k = is_mergeable_mul_mat(&node);
    wt.type = GGML_TYPE_Q6_K;
    int m6k = is_mergeable_mul_mat(&node);
    wt.type = GGML_TYPE_F16;
    int mf16 = is_mergeable_mul_mat(&node);
    printf("  [%s] T1 predicado: Q4_1=%d Q4_K=%d Q6_K=%d F16=%d (esperado 1/1/0/0!)\n",
           (m41 && m4k && !m6k && !mf16) ? "OK " : "RED", m41, m4k, m6k, mf16);
    if (!(m41 && m4k && !m6k && !mf16)) failures++;
    // ---- T2: a EXPRESSAO real do row_size (o alvo Q4_K usa q8_1 = 640!) ----
    size_t r41 = test_src1_row_size(GGML_TYPE_Q4_1, 256);
    size_t r4k = test_src1_row_size(GGML_TYPE_Q4_K, 256);
    size_t rf16 = test_src1_row_size(GGML_TYPE_F16, 256);
    size_t rq80 = test_src1_row_size(GGML_TYPE_Q8_0, 256);
    int ok2 = (r41 == 640 && r4k == 640 && rf16 == 512 && rq80 == 512);
    printf("  [%s] T2 row_size: Q4_1=%zu Q4_K=%zu F16=%zu Q8_0=%zu (esperado 640/640/512/512!)\n",
           ok2 ? "OK " : "RED", r41, r4k, rf16, rq80);
    if (!ok2) failures++;
    // ---- T3: src1 NAO compartilhado (nao-degenerado!) ----
    ggml_tensor act2 = {0}; act2.type = GGML_TYPE_F32;
    node.src[1] = &act2;
    wt.type = GGML_TYPE_Q4_K;
    int m_ok = is_mergeable_mul_mat(&node);   // (o predicado base nao checa o eixo!)
    printf("  [OK ] T3 base: Q4_K com src1 F32 = %d (predicado ativo!)\n", m_ok);
    printf("\nTOTAL: %d RED\nEXIT=%d\n", failures, failures ? 1 : 0);
    return failures ? 1 : 0;
}
