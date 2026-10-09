// r30_gate_test.c — R30: fixture COMPILADA do gate PIN (os casos do parecer §4!)
// Usa a MESMA seam (r30_gate.h!) que o probe incluira' (include/uso comprovado!).
// Casos: ambosPASS; legacyFAIL/PINPASS; NMSEalto; NaN; exec; parcial; sentinel;
//        denom0; score invalido/somas nao-finitas; overflow; ref invalida!
#include <stdio.h>
#include <math.h>
#include "r30_gate.h"

static int failures = 0;
static void check(const char * nome, int cond) {
    printf("  [%s] %s\n", cond ? "OK " : "RED", nome);
    if (!cond) failures++;
}

int main(void) {
    printf("=== r30_gate_test (gate PIN compartilhado!) ===\n");
    r30_inputs_t in;
    r30_result_t r;
    // base valida (usada pelos casos!):
    #define BASE() do { \
        in.exec_ok=1; in.alloc_ok=1; in.sync_ok=1; in.ref_ok=1; \
        in.nd=8192; in.n_expected=8192; in.nf_ref=0; in.nf_out=0; \
        in.n_unwritten=0; in.sum_err2=1e-7; in.sum_ref2=1.0; \
        in.mx_abs=0.1; in.novl=0; } while (0)

    // C1: ambos PASS!
    BASE(); r = r30_gate(&in);
    check("C1 ambos PASS", r.legacy == R30_LEGACY_PASS && r.pin == R30_PIN_PASS && r.pin_valid == 1);

    // C2: legacyFAIL / PINPASS (o caso B128 REAL! novl>0 mas NMSE baixo!)
    BASE(); in.novl = 10; in.sum_err2 = 1.337e-7; in.sum_ref2 = 1.0;
    r = r30_gate(&in);
    check("C2 legacyFAIL/PINPASS (B128!)", r.legacy == R30_LEGACY_FAIL && r.pin == R30_PIN_PASS && r.nmse_pin > 0);

    // C3: NMSE alto (acima do limite!)
    BASE(); in.sum_err2 = 6e-4; in.sum_ref2 = 1.0;   // nmse = 6e-4 > 5e-4!
    r = r30_gate(&in);
    check("C3 NMSE alto => PIN_FAIL", r.pin == R30_PIN_FAIL && r.legacy == R30_LEGACY_PASS);

    // C4: NaN na referencia ou saida => INVALID!
    BASE(); in.nf_ref = 1; r = r30_gate(&in);
    check("C4a ref NaN => INVALID", r.pin == R30_INVALID && r.legacy == R30_INVALID);
    BASE(); in.nf_out = 3; r = r30_gate(&in);
    check("C4b out NaN => INVALID", r.pin == R30_INVALID);

    // C5: exec falha => INVALID!
    BASE(); in.exec_ok = 0; r = r30_gate(&in);
    check("C5 exec fail => INVALID", r.pin == R30_INVALID && r.legacy == R30_INVALID);

    // C6: cobertura parcial => INVALID!
    BASE(); in.nd = 4096; r = r30_gate(&in);
    check("C6 parcial => INVALID", r.pin == R30_INVALID);

    // C7: sentinel (unwritten!) => INVALID!
    BASE(); in.n_unwritten = 64; r = r30_gate(&in);
    check("C7 sentinel => INVALID", r.pin == R30_INVALID);

    // C8: denominador ZERO => INVALID (nunca divisao0/PASS por sentinela!)
    BASE(); in.sum_ref2 = 0.0; r = r30_gate(&in);
    check("C8 denom0 => INVALID (nao PASS!)", r.pin == R30_INVALID && r.pin_valid == 0 && r.nmse_pin == -1.0);

    // C9: somas nao-finitas (score invalido!) => INVALID (o -1 NAO pode PASS!)
    BASE(); in.sum_err2 = INFINITY; r = r30_gate(&in);
    check("C9 soma inf => INVALID", r.pin == R30_INVALID && r.pin_valid == 0);

    // C10: overflow das somas => INVALID!
    BASE(); in.sum_err2 = 1e308 * 10.0; r = r30_gate(&in);   // inf!
    check("C10 overflow => INVALID", r.pin == R30_INVALID);

    // C11: referencia invalida (ref_ok=0!) => INVALID!
    BASE(); in.ref_ok = 0; r = r30_gate(&in);
    check("C11 ref invalida => INVALID", r.pin == R30_INVALID);

    // C12: nd==0 => INVALID!
    BASE(); in.nd = 0; r = r30_gate(&in);
    check("C12 nd=0 => INVALID", r.pin == R30_INVALID);

    printf("\nTOTAL: %d RED\nEXIT=%d\n", failures, failures ? 1 : 0);
    return failures ? 1 : 0;
}
