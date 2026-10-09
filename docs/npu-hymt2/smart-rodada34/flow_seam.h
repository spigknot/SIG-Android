// flow_seam.h — R34: A SEAM COMPARTILHADA do fluxo alloc/upload/compute/cleanup!
// A DECISAO (if NULL/preflight/retorno/ordem cleanup!) vive AQUI — o probe a
// CHAMA (com os callbacks reais!) e a fixture a COMPILA (com callbacks
// injetados e contados!). Os mutantes M7/M8 alteram ESTE header (nao copia!).
// Adaptado da mecanica REAL do probe (bloco L940-951!) — a assinatura e o
// fluxo de retorno espelham o build_e_run.
#pragma once
#include <stddef.h>

// os callbacks (no PROBE: delegam as chamadas ggml REAIS; no TESTE: injetados!)
typedef struct {
    void * (*alloc_repack)(void * cw, void * buft);   // alocador REPACK efetivo!
    size_t (*alloc_size)(void * cw, void * buft);     // required size!
    void (*free_ctx)(void * ctx);                     // cleanup (ggml_free!)
} flow_callbacks_t;

typedef struct {
    int  rc;             // 0 = ok; -996 = ALLOC_FAIL (o contrato do probe!)
    int  alloc_failed;   // ALLOC_FAIL explicito!
    void * bw;           // o buffer REPACK alocado (usado pelo probe!)
    long upload_calls;   // executou o upload?
    long compute_calls;  // executou o compute?
    long free_calls;     // cleanup de contextos!
} flow_result_t;

// A SEAM (a decisao COMPARTILHADA!):
static inline void flow_alloc_preflight(const flow_callbacks_t * cb,
                                        void * cw, void * ca, void * buft_repack,
                                        int is_htp, flow_result_t * res) {
    res->rc = 0; res->alloc_failed = 0; res->bw = NULL;
    res->upload_calls = 0; res->compute_calls = 0; res->free_calls = 0;
    void * bw = NULL;
    if (is_htp && buft_repack) {
        (void) cb->alloc_size(cw, buft_repack);        // req (diagnostico!)
        bw = cb->alloc_repack(cw, buft_repack);
#ifndef MUTANTE_SEM_IF_BW
        if (!bw) {
            // ALLOC_FAIL explicito: NUNCA fallback silencioso ao default!
            res->rc = -996;
            res->alloc_failed = 1;
            cb->free_ctx(cw); cb->free_ctx(ca);        // cleanup!
            res->free_calls = 2;
            return;                                    // NAO prossegue!
        }
#endif
        // (MUTANTE_SEM_IF_BW: SEGUE com bw=NULL!)
    }
    res->bw = bw;
    res->upload_calls = 1;   // upload executado (no probe: o tensor_set REAL!)
    res->compute_calls = 1;  // compute executado (no probe: o graph_compute!)
}

// === a SEAM do OPSTAGE (rota numerica exige QUEUE+COMPUTE!) ===
// as constantes vem do htp-ops.h REAL (1<<0 / 1<<1 — confirmadas no cohort!)
#define FLOW_OPSTAGE_QUEUE   1u
#define FLOW_OPSTAGE_COMPUTE 2u
static inline int flow_opstage_preflight(unsigned opstage) {
#ifdef MUTANTE_BYPASS_OPSTAGE
    (void) opstage;
    return 1;   // MUTANTE: bypass (aceita tudo!) => RED!
#else
    return (opstage & FLOW_OPSTAGE_QUEUE) && (opstage & FLOW_OPSTAGE_COMPUTE);
#endif
}
