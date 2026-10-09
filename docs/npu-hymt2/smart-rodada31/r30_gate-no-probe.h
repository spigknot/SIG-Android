// r30_gate.h — R30: GATE NUMERICO do pin (seam COMPARTILHADA: probe + fixture!)
// Contrato (parecer §4!): verdict_legacy e verdict_pin SEPARADOS; guardas
// explicitas; score invalido NAO pode PASS por ser menor que o limite!
#pragma once
#include <math.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    R30_INVALID  = 0,   // execucao/cobertura/referencia invalida => NUNCA PASS!
    R30_LEGACY_PASS,
    R30_LEGACY_FAIL,
    R30_PIN_PASS,
    R30_PIN_FAIL,
} r30_verdict_t;

typedef struct {
    // entradas (tudo o que o gate precisa saber!):
    int    exec_ok;        // rc == success do compute!
    int    alloc_ok;       // alloc/supports/usage ok!
    int    sync_ok;        // sentinel/sync validados!
    int    ref_ok;         // referencia valida (CPU-ref executada e finita!)
    long   nd;             // elementos comparados!
    long   n_expected;     // elementos esperados (cobertura total!)
    long   nf_ref;         // nao-finitos na referencia!
    long   nf_out;         // nao-finitos na saida!
    long   n_unwritten;    // sentinel intacto!
    double sum_err2;       // soma((a-b)^2) — com precisao dupla!
    double sum_ref2;       // soma(a^2) — a referencia!
    double mx_abs;         // diagnostico!
    long   novl;           // violadores do criterio LEGADO (diagnostico!)
} r30_inputs_t;

typedef struct {
    r30_verdict_t legacy;
    r30_verdict_t pin;
    int    pin_valid;      // o score pin e' valido (denominador>0, somas finitas!)
    double nmse_pin;       // -1 se invalido!
} r30_result_t;

// o gate COMPARTILHADO (o mesmo codigo no probe e na fixture!)
static inline r30_result_t r30_gate(const r30_inputs_t * in) {
    r30_result_t r;
    r.legacy = R30_INVALID;
    r.pin = R30_INVALID;
    r.pin_valid = 0;
    r.nmse_pin = -1.0;

    // ---- guardas de VALIDADE (valem para os DOIS veredictos!) ----
    const int valid_base =
        in->exec_ok && in->alloc_ok && in->sync_ok && in->ref_ok &&
        in->nf_ref == 0 && in->nf_out == 0 && in->n_unwritten == 0 &&
        in->nd == in->n_expected && in->nd > 0 &&
        isfinite(in->sum_err2) && isfinite(in->sum_ref2) &&
        in->sum_err2 >= 0.0 && in->sum_ref2 >= 0.0;
    if (!valid_base) {
        return r;   // INVALID para ambos (NUNCA PASS sem validade!)
    }

    // ---- LEGADO (0.02 + 0.05*|ref| por elemento — CONGELADO!) ----
    r.legacy = (in->novl == 0) ? R30_LEGACY_PASS : R30_LEGACY_FAIL;

    // ---- PIN (NMSE = soma(err²)/soma(ref²) <= 5e-4 — o test_mul_mat do pin!) ----
    if (in->sum_ref2 <= 0.0) {
        // denominador ZERO: contrato explicito (ref zero!) => INVALID neste gate
        // (nunca divisao por zero nem PASS por score sentinela!)
        r.pin = R30_INVALID;
        r.pin_valid = 0;
        r.nmse_pin = -1.0;
        return r;
    }
    const double nmse = in->sum_err2 / in->sum_ref2;
    if (!isfinite(nmse)) {
        r.pin = R30_INVALID;
        r.pin_valid = 0;
        r.nmse_pin = -1.0;
        return r;
    }
    r.pin_valid = 1;
    r.nmse_pin = nmse;
    r.pin = (nmse <= 5e-4) ? R30_PIN_PASS : R30_PIN_FAIL;
    return r;
}

#ifdef __cplusplus
}
#endif
