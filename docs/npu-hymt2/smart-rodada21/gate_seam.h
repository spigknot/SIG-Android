// gate_seam.h — R20: A SEAM do gate de classificacao do harness.
// Compilada INCLUIDA tanto pelo probe (fluxo real) quanto pelo teste
// nativo (r20_gate_test) — NAO e' replica: e' o MESMO codigo.
// Regras (ordem exata do fluxo):
//   1) sem suporte PRE => SKIP (nunca compute)
//   2) CPU-ref falha/NaN => CPU_REF_FAIL (nunca HTP)
//   3) sup_post=0 => REJECTED_BUFFER (nunca upload/compute!)
//   4) exec falhou => EXEC_FAIL (vetor nao usado como resultado)
//   5) sentinel nao escrito => UNWRITTEN_OUTPUT
//   6) NaN/Inf no output => NONFINITE_HTP
//   7) violacao de tolerancia ou cobertura incompleta => NUMERIC_FAIL
//   8) tudo ok => PASS
#pragma once

typedef enum {
    GATE_SKIP_PRE = 0,
    GATE_CPU_REF_FAIL,
    GATE_REJECTED_BUFFER,
    GATE_EXEC_FAIL,
    GATE_UNWRITTEN,
    GATE_NONFINITE,
    GATE_NUMERIC_FAIL,
    GATE_PASS,
} GateStatus;

static inline GateStatus gate_decide(int sup_pre, int ok_cpu, long ref_nf, int sup_post,
                                     int ok_h, int hrc, long unwritten, long out_nf,
                                     long novl, long nd, long nexpected) {
    if (!sup_pre)               return GATE_SKIP_PRE;
    if (!ok_cpu || ref_nf > 0)  return GATE_CPU_REF_FAIL;
    if (!sup_post)              return GATE_REJECTED_BUFFER;
    if (!ok_h || hrc != 0)      return GATE_EXEC_FAIL;
    if (unwritten > 0)          return GATE_UNWRITTEN;
    if (out_nf > 0)             return GATE_NONFINITE;
    if (novl > 0 || nd != nexpected) return GATE_NUMERIC_FAIL;
    return GATE_PASS;
}

static inline const char * gate_name(GateStatus g) {
    switch (g) {
        case GATE_SKIP_PRE:       return "SKIP (UNSUPPORTED_PRE)";
        case GATE_CPU_REF_FAIL:   return "CPU_REF_FAIL";
        case GATE_REJECTED_BUFFER:return "REJECTED_BUFFER";
        case GATE_EXEC_FAIL:      return "EXEC_FAIL";
        case GATE_UNWRITTEN:      return "UNWRITTEN_OUTPUT";
        case GATE_NONFINITE:      return "NONFINITE_HTP";
        case GATE_NUMERIC_FAIL:   return "NUMERIC_FAIL";
        case GATE_PASS:           return "PASS";
    }
    return "?";
}

// === R21: SEAM DE FLUXO (preflight!) — chamada pelo probe ANTES de
// upload/compute e pelo teste nativo com callbacks de I/O contados.
// Separa DECISAO (preflight) de CLASSIFICACAO (pos-execucao)!
typedef enum {
    PRE_GO = 0,      // pode prosseguir (upload+compute)
    PRE_SKIP,        // unsupported pre => nao computa
    PRE_CPUFAIL,     // cpu-ref invalida => nao computa HTP
    PRE_REJECT,      // sup_post=0 => REJECTED_BUFFER (nao computa!)
} GatePreflight;

static inline GatePreflight gate_preflight(int sup_pre, int ok_cpu, long ref_nf, int sup_post) {
    if (!sup_pre)               return PRE_SKIP;
    if (!ok_cpu || ref_nf > 0)  return PRE_CPUFAIL;
    if (!sup_post)              return PRE_REJECT;
    return PRE_GO;
}
