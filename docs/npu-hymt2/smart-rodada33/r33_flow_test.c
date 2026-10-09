// r33_flow_test.c — R33: TESTES DE FLUXO (ALLOC_FAIL + OPSTAGE — o minimo obrigatorio!)
// Modela o FLUXO REAL do probe (o trecho do build_e_run!) com SEAMS injetaveis:
//   alloc: o NULL injetado => ALLOC_FAIL + upload0 + compute0 + cleanup;
//   opstage: rota numerica exige QUEUE+COMPUTE (0/1/2 recusam; 3 executa!)
// Mutantes: M7 (sem o if(!bw)! -> continua apos NULL!) e M8 (bypass do opstage!)
// devem RED. Compilar: g++ -std=c++17 -O2 r33_flow_test.c -o r33_flow
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

// === as constantes/flags REAIS (do htp-ops.h do cohort!) ===
#define HTP_OPSTAGE_QUEUE   1
#define HTP_OPSTAGE_COMPUTE 2

// === SEAMS injetaveis (os "callbacks" do fluxo!) ===
static long g_upload_calls = 0, g_compute_calls = 0, g_free_calls = 0;
static long g_alloc_calls = 0;
static int  g_alloc_returns_null = 0;   // a INJECAO!

// os allocadores (REAIS no probe; aqui: com a injecao!)
static size_t fake_alloc_size(void * cw, void * buft) { (void) cw; (void) buft; return 10240; }
static void * fake_alloc(void * cw, void * buft) {
    (void) cw; (void) buft;
    g_alloc_calls++;
    return g_alloc_returns_null ? NULL : (void *) 0x1000;   // o buffer ou NULL!
}
// os ggml_free contados (o cleanup!)
static void fake_free(void * ctx) { (void) ctx; g_free_calls++; }

// === O TRECHO DO FLUXO (modela o build_e_run do probe — L940-955!) ===
typedef struct { int rc; int sup_post; } fluxo_res_t;
static int fluxo_alloc_e_upload(int is_htp, void * buft_repack, void * cw, void * ca, fluxo_res_t * res) {
    res->rc = 0; res->sup_post = 1;
    void * bw = NULL;
    size_t req_alloc = 0;
    if (is_htp && buft_repack) {
        req_alloc = fake_alloc_size(cw, buft_repack);
        bw = fake_alloc(cw, buft_repack);
#ifndef MUTANTE_SEM_IF_BW
        if (!bw) {
            // ALLOC_FAIL explicito (NUNCA fallback silencioso ao default!)
            printf("    [fluxo] ALLOC_FAIL (REPACK selecionado falhou! req=%zu)\n", req_alloc);
            res->rc = -996;
            fake_free(cw); fake_free(ca);   // cleanup!
            return 0;   // NAO continua!
        }
#endif
        // (MUTANTE! sem o if: SEGUE com bw=NULL!)
    }
    // com o buffer (valido ou nao, se mutante!): upload + compute!
    g_upload_calls++;
    g_compute_calls++;
    return 1;
}

// === A SEAM DO OPSTAGE (o preflight da rota numerica!) ===
static int opstage_preflight(int opstage) {
    // a rota numerica EXIGE QUEUE+COMPUTE (o contrato do parecer §3!)
    return (opstage & HTP_OPSTAGE_QUEUE) && (opstage & HTP_OPSTAGE_COMPUTE);
}

static int failures = 0;
static void check(const char * nome, int cond) {
    printf("  [%s] %s\n", cond ? "OK " : "RED", nome);
    if (!cond) failures++;
}

int main(void) {
    printf("=== r33_flow_test (ALLOC_FAIL + OPSTAGE!) ===\n");
#ifdef MUTANTE_SEM_IF_BW
    printf("MUTANTE_SEM_IF_BW ativo!\n");
#endif
    fluxo_res_t res;

    // ---- T1: alloc NULL injetado => ALLOC_FAIL + upload0 + compute0 + cleanup! ----
    g_upload_calls = g_compute_calls = g_free_calls = g_alloc_calls = 0;
    g_alloc_returns_null = 1;
    int ok1 = fluxo_alloc_e_upload(1, (void *) 0x1, (void *) 0x2, (void *) 0x3, &res);
    printf("  T1: ret=%d rc=%d upload=%ld compute=%ld frees=%ld\n", ok1, res.rc, g_upload_calls, g_compute_calls, g_free_calls);
    check("T1 alloc NULL => NAO continua (ret=0!)", ok1 == 0);
    check("T1 rc = ALLOC_FAIL (-996!)", res.rc == -996);
    check("T1 upload_calls == 0", g_upload_calls == 0);
    check("T1 compute_calls == 0", g_compute_calls == 0);
    check("T1 cleanup (frees == 2!)", g_free_calls == 2);

    // ---- T2: alloc OK => upload + compute executam (nao-noop!) ----
    g_upload_calls = g_compute_calls = g_free_calls = 0;
    g_alloc_returns_null = 0;
    int ok2 = fluxo_alloc_e_upload(1, (void *) 0x1, (void *) 0x2, (void *) 0x3, &res);
    check("T2 alloc OK => continua (ret=1!)", ok2 == 1);
    check("T2 upload_calls == 1", g_upload_calls == 1);
    check("T2 compute_calls == 1", g_compute_calls == 1);
    check("T2 sem frees no caminho valido", g_free_calls == 0);

    // ---- T3: OPSTAGE (a rota numerica!) ----
    check("T3 opstage 0 RECUSADO",   opstage_preflight(0) == 0);
    check("T3 opstage 1 RECUSADO (só QUEUE!)", opstage_preflight(1) == 0);
    check("T3 opstage 2 RECUSADO (só COMPUTE!)", opstage_preflight(2) == 0);
    check("T3 opstage 3 PERMITIDO", opstage_preflight(3) == 1);

    printf("\nTOTAL: %d RED\nEXIT=%d\n", failures, failures ? 1 : 0);
    return failures ? 1 : 0;
}
