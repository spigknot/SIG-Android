// r35_flow_test.c — R35: FLUXO do caller (opstage + I/O REAL!) na SEAM!
// A decisao vem da flow_seam.h (a MESMA do probe!); a I/O e' medida pelos
// CALLBACKS do caller (io_upload/io_compute — eventos REAIS, nao marcadores!).
// M8 (no header!) deve permitir os invalidos => io_calls>0 => RED PELA I/O!
#include <stdio.h>
#include <string.h>
#include "flow_seam.h"

static long g_alloc_calls = 0, g_free_calls = 0;
static long io_upload = 0, io_compute = 0;   // EVENTOS (medidos no caller!)
static int  g_alloc_returns_null = 0;
static void * inj_alloc(void * cw, void * buft) { (void) cw; (void) buft; g_alloc_calls++; return g_alloc_returns_null ? NULL : (void *) 0x1000; }
static size_t inj_size(void * cw, void * buft) { (void) cw; (void) buft; return 10240; }
static void inj_free(void * ctx) { (void) ctx; g_free_calls++; }

// === O CALLER MODELADO (a sequencia do build_e_run: alloc -> OPSTAGE -> I/O!) ===
static int caller_flow(int is_htp, unsigned opstage, flow_callbacks_t * cb, int * rc) {
    *rc = 0;
    flow_result_t fres;
    flow_alloc_preflight(cb, (void *) 0x2, (void *) 0x3, (void *) 0x1, is_htp, &fres);
    if (fres.rc == -996) { *rc = -996; return 0; }          // ALLOC_FAIL: nao segue!
    // R35: o PREFLIGHT do opstage (ZERO I/O quando recusa!)
    if (is_htp && !flow_opstage_preflight(opstage)) { *rc = -998; return 0; }
    // A I/O REAL do caller (os eventos!):
    io_upload++;    // o tensor_set!
    io_compute++;   // o graph_compute!
    return 1;
}

static int failures = 0;
static void check(const char * nome, int cond) { printf("  [%s] %s\n", cond ? "OK " : "RED", nome); if (!cond) failures++; }

int main(void) {
    printf("=== r35_flow_test (opstage no CALLER + I/O real!) ===\n");
    flow_callbacks_t cb; cb.alloc_repack = inj_alloc; cb.alloc_size = inj_size; cb.free_ctx = inj_free;
    int rc;

    // T4: OPSTAGE invalido (0/1/2) => rejeita + ZERO I/O!
    unsigned inval[3] = {0u, 1u, 2u};
    for (int i = 0; i < 3; i++) {
        io_upload = io_compute = 0; g_alloc_returns_null = 0;
        int cont = caller_flow(1, inval[i], &cb, &rc);
        char nome[64]; snprintf(nome, sizeof(nome), "T4 opstage %u: rejeitado (rc=-998!) + ZERO I/O (io=%ld/%ld!)", inval[i], io_upload, io_compute);
        check(nome, cont == 0 && rc == -998 && io_upload == 0 && io_compute == 0);
    }
    // T5: opstage 3 => continua E EXECUTA a I/O!
    io_upload = io_compute = 0; g_alloc_returns_null = 0;
    int cont = caller_flow(1, 3u, &cb, &rc);
    check("T5 opstage 3: continua + I/O executada (1/1!)", cont == 1 && rc == 0 && io_upload == 1 && io_compute == 1);

    // T6: alloc NULL => ALLOC_FAIL + ZERO I/O (nem chega ao opstage!)
    io_upload = io_compute = 0; g_alloc_returns_null = 1;
    cont = caller_flow(1, 3u, &cb, &rc);
    check("T6 alloc NULL: rc=-996 + ZERO I/O", cont == 0 && rc == -996 && io_upload == 0 && io_compute == 0);

    // T7: CPU (is_htp=0): sem bloqueio global do opstage!
    io_upload = io_compute = 0; g_alloc_returns_null = 0;
    cont = caller_flow(0, 0u, &cb, &rc);
    check("T7 CPU (fora da rota numerica!): nao bloqueia (I/O 1/1!)", cont == 1 && io_upload == 1 && io_compute == 1);

    printf("\nTOTAL: %d RED\nEXIT=%d\n", failures, failures ? 1 : 0);
    return failures ? 1 : 0;
}
