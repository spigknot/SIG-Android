// r34_flow_test.c — R34: fixture da SEAM REAL (flow_seam.h — o MESMO header
// que o PROBE compila e chama!). Os contadores vem do flow_result_t DA SEAM
// (nao ha incrementos simulados fora!). M7/M8 alteram O HEADER (nao copia!).
#include <stdio.h>
#include <string.h>
#include "flow_seam.h"

// === os callbacks INJETADOS (contam as CHAMADAS REAIS da seam!) ===
static long g_alloc_calls = 0, g_free_calls = 0;
static int  g_alloc_returns_null = 0;
static void * inj_alloc(void * cw, void * buft) {
    (void) cw; (void) buft;
    g_alloc_calls++;
    return g_alloc_returns_null ? NULL : (void *) 0x1000;   // o INJETOR!
}
static size_t inj_size(void * cw, void * buft) { (void) cw; (void) buft; return 10240; }
static void inj_free(void * ctx) { (void) ctx; g_free_calls++; }

static int failures = 0;
static void check(const char * nome, int cond) {
    printf("  [%s] %s\n", cond ? "OK " : "RED", nome);
    if (!cond) failures++;
}

int main(void) {
    printf("=== r34_flow_test (SEAM REAL: flow_seam.h — o MESMO do probe!) ===\n");
#ifdef MUTANTE_SEM_IF_BW
    printf("MUTANTE_SEM_IF_BW ativo (no HEADER!)\n");
#endif
#ifdef MUTANTE_BYPASS_OPSTAGE
    printf("MUTANTE_BYPASS_OPSTAGE ativo (no HEADER!)\n");
#endif
    flow_callbacks_t cb;
    cb.alloc_repack = inj_alloc;
    cb.alloc_size = inj_size;
    cb.free_ctx = inj_free;
    flow_result_t r;

    // ---- T1: alloc NULL injetado => ALLOC_FAIL + upload0 + compute0 + cleanup! ----
    g_alloc_calls = g_free_calls = 0;
    g_alloc_returns_null = 1;
    flow_alloc_preflight(&cb, (void *) 0x2, (void *) 0x3, (void *) 0x1, 1, &r);
    printf("  T1: rc=%d alloc_failed=%d upload=%ld compute=%ld free=%ld (alloc_calls=%ld)\n",
           r.rc, r.alloc_failed, r.upload_calls, r.compute_calls, r.free_calls, g_alloc_calls);
    check("T1 alloc NULL => rc=ALLOC_FAIL (-996!)", r.rc == -996);
    check("T1 alloc_failed marcado", r.alloc_failed == 1);
    check("T1 upload_calls == 0 (da SEAM!)", r.upload_calls == 0);
    check("T1 compute_calls == 0 (da SEAM!)", r.compute_calls == 0);
    check("T1 cleanup: free_calls == 2 (da SEAM!)", r.free_calls == 2 && g_free_calls == 2);
    check("T1 o alocador FOI chamado (injetor!)", g_alloc_calls == 1);

    // ---- T2: alloc OK => upload + compute executam (nao-noop!) ----
    g_alloc_calls = g_free_calls = 0;
    g_alloc_returns_null = 0;
    flow_alloc_preflight(&cb, (void *) 0x2, (void *) 0x3, (void *) 0x1, 1, &r);
    check("T2 alloc OK => rc=0 (sem falha!)", r.rc == 0 && r.alloc_failed == 0);
    check("T2 upload_calls == 1 (da SEAM!)", r.upload_calls == 1);
    check("T2 compute_calls == 1 (da SEAM!)", r.compute_calls == 1);
    check("T2 bw devolvido pela seam", r.bw == (void *) 0x1000);
    check("T2 sem frees no caminho valido", r.free_calls == 0);

    // ---- T3: OPSTAGE (a seam do preflight — a MESMA do probe!) ----
    check("T3 opstage 0 RECUSADO", flow_opstage_preflight(0) == 0);
    check("T3 opstage 1 RECUSADO (so QUEUE!)", flow_opstage_preflight(1) == 0);
    check("T3 opstage 2 RECUSADO (so COMPUTE!)", flow_opstage_preflight(2) == 0);
    check("T3 opstage 3 PERMITIDO", flow_opstage_preflight(3) == 1);

    printf("\nTOTAL: %d RED\nEXIT=%d\n", failures, failures ? 1 : 0);
    return failures ? 1 : 0;
}
