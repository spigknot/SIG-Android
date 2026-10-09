// gate_seam_mut.h — MUTANTE de regressao (R21): self-contained, preflight
// BYPASSADO (retorna PRE_GO sempre) — o teste de fluxo DEVE ficar RED!
#pragma once
typedef enum { GATE_SKIP_PRE=0, GATE_CPU_REF_FAIL, GATE_REJECTED_BUFFER, GATE_EXEC_FAIL,
    GATE_UNWRITTEN, GATE_NONFINITE, GATE_NUMERIC_FAIL, GATE_PASS } GateStatus;
typedef enum { PRE_GO = 0, PRE_SKIP, PRE_CPUFAIL, PRE_REJECT } GatePreflight;
static inline GatePreflight gate_preflight(int sp, int oc, long rn, int spo) {
    (void) sp; (void) oc; (void) rn; (void) spo;
    return PRE_GO;   // BYPASS!
}
static inline GateStatus gate_decide(int sup_pre, int ok_cpu, long ref_nf, int sup_post,
                                     int ok_h, int hrc, long unwritten, long out_nf,
                                     long novl, long nd, long nexpected) {
    if (!sup_pre) return GATE_SKIP_PRE;
    if (!ok_cpu || ref_nf > 0) return GATE_CPU_REF_FAIL;
    if (!ok_h || hrc != 0) return GATE_EXEC_FAIL;
    if (!sup_post) return GATE_REJECTED_BUFFER;
    if (unwritten > 0) return GATE_UNWRITTEN;
    if (out_nf > 0) return GATE_NONFINITE;
    if (novl > 0 || nd != nexpected) return GATE_NUMERIC_FAIL;
    return GATE_PASS;
}
