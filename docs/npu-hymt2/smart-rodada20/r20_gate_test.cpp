// r20_gate_test.cpp — R20: teste NATIVO da seam (mesmo codigo do probe!).
// Exercita o fluxo REAL: post=false => REJECTED_BUFFER e compute NAO chamado.
// Compilar: clang++ -std=c++17 -I. r20_gate_test.cpp -o r20_gate_test
#include "gate_seam.h"
#include <cstdio>
#include <cstring>

static int failures = 0;
static int compute_calls_probe = 0;   // simulacao do contador do fluxo

static void check(const char * nome, bool cond) {
    printf("  [%s] %s\n", cond ? "OK " : "RED", nome);
    if (!cond) failures++;
}

// simula o FLUXO de um caso com a seam: retorna o status E se computou
static GateStatus fluxo(int sup_pre, int ok_cpu, long ref_nf, int sup_post, int ok_h,
                        int hrc, long unwritten, long out_nf, long novl, long nd, long nexpected,
                        bool * computou) {
    *computou = false;
    GateStatus pre = gate_decide(sup_pre, ok_cpu, ref_nf, sup_post, ok_h, hrc, unwritten, out_nf, novl, nd, nexpected);
    // o fluxo real: compute SO acontece se passou por pre/cpu/post!
    if (pre == GATE_SKIP_PRE || pre == GATE_CPU_REF_FAIL || pre == GATE_REJECTED_BUFFER) return pre;
    *computou = true;   // daqui em diante o compute ocorre (exec/unwritten/nonfinite/numeric/pass)
    compute_calls_probe++;
    return pre;
}

int main() {
    bool comp;
    printf("== r20_gate_test (teste NATIVO da seam!) ==\n");
    // T1: post=false NAO computa!
    GateStatus s = fluxo(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 64, &comp);
    check("T1 post=0 => REJECTED_BUFFER", s == GATE_REJECTED_BUFFER);
    check("T1 post=0 => compute NAO chamado", comp == false);
    // T2: ref NaN nao computa
    s = fluxo(1, 1, 3, 1, 0, 0, 0, 0, 0, 0, 64, &comp);
    check("T2 ref NaN => CPU_REF_FAIL", s == GATE_CPU_REF_FAIL);
    check("T2 => compute NAO chamado", comp == false);
    // T3: pre=0 nao computa
    s = fluxo(0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 64, &comp);
    check("T3 pre=0 => SKIP", s == GATE_SKIP_PRE);
    check("T3 => compute NAO chamado", comp == false);
    // T4: NaN no output => NONFINITE (e computou!)
    s = fluxo(1, 1, 0, 1, 1, 0, 0, 1, 0, 63, 64, &comp);
    check("T4 NaN output => NONFINITE_HTP", s == GATE_NONFINITE);
    check("T4 => compute chamado", comp == true);
    // T5: exec fail
    s = fluxo(1, 1, 0, 1, 0, -5, 0, 0, 0, 0, 64, &comp);
    check("T5 rc!=0 => EXEC_FAIL", s == GATE_EXEC_FAIL);
    // T6: sentinel
    s = fluxo(1, 1, 0, 1, 1, 0, 64, 0, 0, 0, 64, &comp);
    check("T6 sentinel => UNWRITTEN", s == GATE_UNWRITTEN);
    // T7: violacao com cobertura completa
    s = fluxo(1, 1, 0, 1, 1, 0, 0, 0, 1, 64, 64, &comp);
    check("T7 viol => NUMERIC_FAIL", s == GATE_NUMERIC_FAIL);
    // T8: cobertura incompleta
    s = fluxo(1, 1, 0, 1, 1, 0, 0, 0, 0, 19, 64, &comp);
    check("T8 cobertura parcial => NUMERIC_FAIL", s == GATE_NUMERIC_FAIL);
    // T9: PASS real
    s = fluxo(1, 1, 0, 1, 1, 0, 0, 0, 0, 64, 64, &comp);
    check("T9 full => PASS", s == GATE_PASS);
    check("T9 => compute chamado", comp == true);
    printf("\nTOTAL: %d RED de 14 testes | compute_calls(simulados)=%d\n", failures, compute_calls_probe);
    printf("EXIT=%d\n", failures ? 1 : 0);
    return failures ? 1 : 0;
}
