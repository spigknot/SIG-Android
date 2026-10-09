// r21_flow_test.cpp — R21: teste NATIVO do FLUXO REAL (gate_preflight!)
// A MESMA funcao usada pelo probe (gate_seam.h) com callbacks de I/O
// contados: post=false => upload_calls==0 E compute_calls==0!
// Mutante: gate_seam_mut.h (preflight bypassado) => este teste fica RED.
#include "gate_seam.h"
#include <cstdio>

static int failures = 0;
static long upload_calls = 0, compute_calls = 0;

static void reset_calls() { upload_calls = 0; compute_calls = 0; }
static void check(const char * nome, bool cond) {
    printf("  [%s] %s\n", cond ? "OK " : "RED", nome);
    if (!cond) failures++;
}

// replica do FLUXO do probe: preflight -> (se GO) upload -> compute -> classifica
static GateStatus fluxo_probe(int sup_pre, int ok_cpu, long ref_nf, int sup_post,
                              int ok_h, int hrc, long unwritten, long out_nf,
                              long novl, long nd, long nexpected) {
    GatePreflight pf = gate_preflight(sup_pre, ok_cpu, ref_nf, sup_post);
    if (pf != PRE_GO) {
        // NAO chama upload/compute — classifica direto!
        switch (pf) {
            case PRE_SKIP:    return GATE_SKIP_PRE;
            case PRE_CPUFAIL: return GATE_CPU_REF_FAIL;
            case PRE_REJECT:  return GATE_REJECTED_BUFFER;
            default: break;
        }
    }
    upload_calls++;    // so' chega aqui se PRE_GO!
    compute_calls++;
    return gate_decide(sup_pre, ok_cpu, ref_nf, sup_post, ok_h, hrc, unwritten, out_nf, novl, nd, nexpected);
}

int main() {
    printf("== r21_flow_test (FLUXO do probe: gate_preflight!) ==\n");
    // T1: post=0 => upload=0 E compute=0 (a exigencia do especialista!)
    reset_calls();
    GateStatus s = fluxo_probe(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 64);
    check("T1 post=0 => REJECTED_BUFFER", s == GATE_REJECTED_BUFFER);
    check("T1 post=0 => upload_calls==0", upload_calls == 0);
    check("T1 post=0 => compute_calls==0", compute_calls == 0);
    // T2: cpu-ref invalida => HTP compute=0!
    reset_calls();
    s = fluxo_probe(1, 1, 2, 1, 0, 0, 0, 0, 0, 0, 64);
    check("T2 ref NaN => CPU_REF_FAIL", s == GATE_CPU_REF_FAIL);
    check("T2 => compute_calls==0", compute_calls == 0);
    // T3: pre=0 => nada
    reset_calls();
    s = fluxo_probe(0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 64);
    check("T3 pre=0 => SKIP + compute==0", s == GATE_SKIP_PRE && compute_calls == 0);
    // T4: sucesso => chamadas previstas (1 upload + 1 compute!)
    reset_calls();
    s = fluxo_probe(1, 1, 0, 1, 1, 0, 0, 0, 0, 64, 64);
    check("T4 GO => PASS", s == GATE_PASS);
    check("T4 => upload_calls==1", upload_calls == 1);
    check("T4 => compute_calls==1", compute_calls == 1);
    // T5: exec fail apos GO => chamadas ocorreram mas EXEC_FAIL
    reset_calls();
    s = fluxo_probe(1, 1, 0, 1, 0, -5, 0, 0, 0, 0, 64);
    check("T5 rc!=0 => EXEC_FAIL", s == GATE_EXEC_FAIL);
    check("T5 => compute_calls==1 (tentou!)", compute_calls == 1);
    printf("\nTOTAL: %d RED de 11 | upload=%ld compute=%ld\n", failures, upload_calls, compute_calls);
    printf("EXIT=%d\n", failures ? 1 : 0);
    return failures ? 1 : 0;
}
