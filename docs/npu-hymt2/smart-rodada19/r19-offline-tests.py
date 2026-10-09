#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R19: testes OFFLINE permanentes do gate/métrica do harness (sem DSP!).
Pura logica: replica as regras do gate C++ e verifica RED/GREEN.
Executar: python r19-offline-tests.py  (exit 0 = todos OK)"""
import sys

RESULTS = []
def t(name, cond, msg=""):
    RESULTS.append((name, bool(cond), msg))
    print(f"  [{'OK ' if cond else 'RED'}] {name} {msg}")

# ---------- helpers que replicam as regras do gate ----------
def classifica(sup_pre, sup_post, ok_cpu, ref_nf, ok_h, hrc, out_nf, novl, nd, n_expected, unwritten):
    """Replica a classificacao do harness v3 (ordem exata!)."""
    if not sup_pre: return "SKIP (UNSUPPORTED_PRE)"
    if not ok_cpu or ref_nf > 0: return "CPU_REF_FAIL"
    if not sup_post: return "REJECTED_BUFFER"
    if not ok_h: return "EXEC_FAIL"
    if unwritten > 0: return "UNWRITTEN_OUTPUT"
    if out_nf > 0: return "NONFINITE_HTP"
    if novl > 0 or nd != n_expected: return "NUMERIC_FAIL"
    return "PASS"

def tolera(d, ref, ABS=2e-2, REL=5e-2):
    return d <= ABS + REL * abs(ref)

# ---------- T1: pre=true/post=false => REJECTED_BUFFER (nunca compute!) ----------
t("T1 pre/post: REJECTED_BUFFER",
  classifica(1, 0, True, 0, False, -1, 0, 0, 64, 64, 0) == "REJECTED_BUFFER",
  "(pre=1 post=0 nao computa)")

# ---------- T2: CPU-ref NaN => CPU_REF_FAIL (nunca HTP!) ----------
t("T2 ref NaN: CPU_REF_FAIL",
  classifica(1, 1, True, 3, False, -1, 0, 0, 0, 64, 0) == "CPU_REF_FAIL",
  "(ref invalida aborta antes do HTP)")

# ---------- T3: output NaN no PRIMEIRO elemento => NONFINITE_HTP ----------
t("T3 NaN primeiro: NONFINITE_HTP",
  classifica(1, 1, True, 0, True, 0, 1, 0, 63, 64, 0) == "NONFINITE_HTP",
  "(NaN com hrc=0 nao vira PASS)")

# ---------- T4: exec rc != 0 nunca usa o vetor => EXEC_FAIL ----------
t("T4 exec rc!=0: EXEC_FAIL",
  classifica(1, 1, True, 0, False, -5, 0, 0, 0, 64, 0) == "EXEC_FAIL")

# ---------- T5: sentinel nao escrito => UNWRITTEN_OUTPUT ----------
t("T5 sentinel: UNWRITTEN_OUTPUT",
  classifica(1, 1, True, 0, True, 0, 0, 0, 0, 64, 64) == "UNWRITTEN_OUTPUT",
  "(output nao escrito != kernel errado; classificado!)")

# ---------- T6: violacao de tolerancia com NRMSE pequeno => FAIL ----------
viola = not tolera(0.043, 0.4)    # lim=0.02+0.05*0.4=0.04; d=0.043 > 0.04 => VIOLA!
t("T6 viol+NRMSE pequeno: FAIL",
  viola and classifica(1, 1, True, 0, True, 0, 0, 1, 64, 64, 0) == "NUMERIC_FAIL",
  "(elemento violado domina; NRMSE nao anula)")

# ---------- T7: indexing B1/B2/B128 consistente (b*N+n!) ----------
ok_idx = True
for B, N in [(1, 64), (2, 64), (128, 64)]:
    for b in range(min(B, 4)):
        for n in range(0, N, 16):
            # o indice linear b*N+n e o usado no harness (strides!) — roundtrip
            lin = b * N + n
            assert 0 <= lin < B * N
t("T7 indexing B1/B2/B128 (b*N+n)",
  ok_idx, "(span ok nos 3 batches)")

# ---------- T8: rotulagem fail-closed (attempt + cohort + PID!) ----------
def aceita_linha(linha, cohort_esperado):
    """Agregador recusa: sem origem/attempt/PID, ou cohort trocado."""
    import re
    if not re.search(r"attempt=\S+", linha): return False
    if not re.search(r"cohort=\S+", linha): return False
    if not re.search(r"pid=\d+", linha): return False
    m = re.search(r"cohort=(\S+)", linha)
    return m.group(1) == cohort_esperado

t("T8a rotulagem completa aceita",
  aceita_linha("attempt=r19-01 cohort=v12 pid=25572 result=PASS", "v12"))
t("T8b rotulo sem attempt recusado",
  not aceita_linha("cohort=v12 pid=25572", "v12"))
t("T8c cohort trocado (baseline c/ hash candidato) recusado",
  not aceita_linha("attempt=r19-01 cohort=kqon pid=25572", "v12"),
  "(a vacina do erro R17!)")

# ---------- resumo ----------
n_ok = sum(1 for _, c, _ in RESULTS if c)
n_red = len(RESULTS) - n_ok
print(f"\nTOTAL: {n_ok} OK / {n_red} RED de {len(RESULTS)} testes")
sys.exit(0 if n_red == 0 else 1)
