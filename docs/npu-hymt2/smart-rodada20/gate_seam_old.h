// gate_seam_old.h — R20 RED: comportamento ANTIGO (R19) — o post=0 NAO
// impedia o compute! (a prova de que o teste nativo DETECTA o bug!)
#pragma once
typedef enum {
    GATE_SKIP_PRE = 0, GATE_CPU_REF_FAIL, GATE_REJECTED_BUFFER, GATE_EXEC_FAIL,
    GATE_UNWRITTEN, GATE_NONFINITE, GATE_NUMERIC_FAIL, GATE_PASS,
} GateStatus;
static inline GateStatus gate_decide(int sup_pre, int ok_cpu, long ref_nf, int sup_post,
                                     int ok_h, int hrc, long unwritten, long out_nf,
                                     long novl, long nd, long nexpected) {
    // COMPORTAMENTO ANTIGO: avalia exec primeiro; post so' la' adiante!
    if (!sup_pre)               return GATE_SKIP_PRE;
    if (!ok_cpu || ref_nf > 0)  return GATE_CPU_REF_FAIL;
    if (!ok_h || hrc != 0)      return GATE_EXEC_FAIL;    // <- exec ANTES do post!
    if (!sup_post)              return GATE_REJECTED_BUFFER;
    if (unwritten > 0)          return GATE_UNWRITTEN;
    if (out_nf > 0)             return GATE_NONFINITE;
    if (novl > 0 || nd != nexpected) return GATE_NUMERIC_FAIL;
    return GATE_PASS;
}
static inline const char * gate_name(GateStatus g) { (void) g; return "?"; }
