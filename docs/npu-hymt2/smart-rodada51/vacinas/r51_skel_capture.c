#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdatomic.h>
// ---- bloco de captura LITERAL do cohort (matmul-ops.c, pos-R50) com stubs ----
typedef struct { uint8_t * act_dump_ptr; size_t act_dump_cap; } Octx;
typedef struct { void * quant_task_func; unsigned n_quant_tasks; uint8_t * vtcm_src1; size_t vtcm_src1_stride; Octx * octx; } Mmctx;
static uint8_t g_buf[262144+4096];
#define ROWS (((uint32_t *) g_buf)[3])   /* h[3] do buffer (o contador REAL!) */
static uint8_t g_src[262144+4096];
static int falhas = 0;
#define REQ(cond, msg) do { if (!(cond)) { printf("VAC_FAIL: %s\n", msg); falhas++; } } while (0)
static bool rodar(bool r50_wq_ok, Octx * octx, Mmctx * mmctx, unsigned src1_nrows) {
    ROWS = 0;
    if (r50_wq_ok && octx->act_dump_ptr && mmctx->quant_task_func && mmctx->n_quant_tasks > 0 &&
    mmctx->vtcm_src1 && mmctx->vtcm_src1_stride > 0) {
    const size_t r50_row   = mmctx->vtcm_src1_stride;
    const size_t r50_cap   = octx->act_dump_cap;
    const size_t r50_nrows = (size_t) src1_nrows;
    if (r50_cap >= 64 && r50_row <= r50_cap - 64 && r50_nrows > 0 &&
    r50_nrows <= (r50_cap - 64) / r50_row) {
    memcpy(octx->act_dump_ptr + 64, mmctx->vtcm_src1, r50_row * r50_nrows);
    atomic_fetch_add((atomic_uint *) &((uint32_t *) octx->act_dump_ptr)[3], (uint32_t) r50_nrows);
    }
    }
    return true;
}
int main() {
    Octx octx; Mmctx mm; memset(&octx, 0, sizeof(octx)); memset(&mm, 0, sizeof(mm));
    octx.act_dump_ptr = g_buf; octx.act_dump_cap = 262144; mm.octx = &octx;
    mm.quant_task_func = (void*) 0x1234; mm.n_quant_tasks = 8; mm.vtcm_src1 = g_src; mm.vtcm_src1_stride = 221184;
    for (size_t i = 0; i < 221184; i++) g_src[i] = (uint8_t)(i * 7);
    // P1: captura OK (B1)
    rodar(true, &octx, &mm, 1);
    REQ(ROWS == 1, "P1 rows=1");
    REQ(memcmp(g_buf + 64, g_src, 221184) == 0, "P1 bytes copiados");
    printf("P1 cap OK: rows=%u copy=ok\n", ROWS);
    // NEGATIVOS (2.4):
    rodar(false, &octx, &mm, 1); REQ(ROWS == 0, "N-runner-false"); printf("N wq_ok=false: rows=0\n");
    { Mmctx m2 = mm; m2.quant_task_func = NULL; rodar(true, &octx, &m2, 1); REQ(ROWS == 0, "N-noquant"); printf("N quant_task_func=NULL: rows=0\n"); }
    { Mmctx m2 = mm; m2.n_quant_tasks = 0; rodar(true, &octx, &m2, 1); REQ(ROWS == 0, "N-nqt0"); printf("N n_quant_tasks=0: rows=0\n"); }
    { Mmctx m2 = mm; m2.vtcm_src1 = NULL; rodar(true, &octx, &m2, 1); REQ(ROWS == 0, "N-src-null"); printf("N vtcm_src1=NULL: rows=0\n"); }
    { Mmctx m2 = mm; m2.vtcm_src1_stride = 0; rodar(true, &octx, &m2, 1); REQ(ROWS == 0, "N-stride0"); printf("N stride=0: rows=0\n"); }
    { Octx o2 = octx; o2.act_dump_ptr = NULL; rodar(true, &o2, &mm, 1); REQ(ROWS == 0, "N-ptr-null"); printf("N act_dump_ptr=NULL: rows=0\n"); }
    { Octx o2 = octx; o2.act_dump_cap = 63; rodar(true, &o2, &mm, 1); REQ(ROWS == 0, "N-cap63"); printf("N cap=63: rows=0\n"); }
    { Mmctx m2 = mm; m2.vtcm_src1_stride = 262144 - 63; rodar(true, &octx, &m2, 1); REQ(ROWS == 0, "N-row>cap-64"); printf("N row=cap-63: rows=0\n"); }
    { rodar(true, &octx, &mm, 0); REQ(ROWS == 0, "N-nrows0"); printf("N nrows=0: rows=0\n"); }
    // OVERSIZE: nrows*row > cap-64 => recusa (ex.: nrows=2 com row=221184 => 442368 > 262080)
    { rodar(true, &octx, &mm, 2); REQ(ROWS == 0, "N-oversize"); printf("N nrows=2 overflow: rows=0\n"); }
    // POSITIVO 2: nrows=1 stride pequeno ok (row=2048, cap grande)
    { Mmctx m2 = mm; m2.vtcm_src1_stride = 2048; rodar(true, &octx, &m2, 1); REQ(ROWS == 1, "P2-row2048"); printf("P2 row=2048 nrows=1: rows=1\n"); }
    printf("VAC_SKEL falhas=%d %s\n", falhas, falhas ? "RED" : "GREEN");
    return falhas ? 1 : 0;
}
