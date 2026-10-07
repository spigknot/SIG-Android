#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R5 §1/§2: recalculo por script (subtotais prefill+gen do lote C + deltas do
espelho valid-vs-bench). NADA escrito a mao; cruzamentos barrados por asserts."""
import re, statistics

LOG = r"C:/llama-npu/rodada2/logs/npuprobe-r4.log"
OUT = r"D:/Projetos/SIG/docs/npu-hymt2/smart-rodada5/RECALCULO-R5.txt"

t = open(LOG, encoding="utf-8", errors="replace").read()
runs = {}
for l in t.splitlines():
    m = re.search(r"(\d\d:\d\d:\d\d\.\d+)\s+I/NpuProbe\(\s*(\d+)\): (.*)", l)
    if not m: continue
    hora, pid, msg = m.groups()
    r = runs.setdefault(pid, {"hora": hora, "rota": None, "prefill": None, "gen": None,
                              "bench": False, "mut": "", "tok1": None, "eog1": None})
    if msg.startswith("SMARTv2 rota=") and r["rota"] is None:
        mm = re.search(r"rota='([^']+)'", msg); r["rota"] = mm.group(1) if mm else "?"
        m2 = re.search(r"mutante=(\S+)", msg)
        r["mut"] = (m2.group(1) if (m2 and m2.group(1) != "(none)") else "")
    if "MODO BENCHMARK" in msg: r["bench"] = True
    if msg.startswith(("A1 tempos:", "puro: ok=")) and r["prefill"] is None:
        mm = re.search(r"prefill=([\d.]+)ms.*?gen=([\d.]+)ms", msg)
        if mm: r["prefill"], r["gen"] = float(mm.group(1)), float(mm.group(2))

out = ["RECALCULO R5 (por script; fonte: npuprobe-r4.log)", ""]
out.append("== 1) Lote C: subtotal prefill+gen por tentativa (3 reps/modo) ==")
C = [(pid, r) for pid, r in runs.items() if r["rota"] and r["prefill"] is not None
     and "17:40" <= r["hora"] <= "18:00"
     and (r["rota"].startswith("puro:") or ("@bench" in r["rota"] and not r["rota"].startswith("puro")))
     and r["mut"] == ""]
from collections import defaultdict
sub = defaultdict(list)
out.append(f"{'rota':16s} {'hora':12s} {'prefill':>9} {'gen':>9} {'subtotal':>10}")
for pid, r in sorted(C, key=lambda x: x[1]["hora"]):
    s = r["prefill"] + r["gen"]
    sub[r["rota"]].append(s)
    out.append(f"{r['rota']:16s} {r['hora']:12s} {r['prefill']:>9.2f} {r['gen']:>9.2f} {s:>10.2f}")
out.append("")
out.append("MEDIANA DOS SUBTOTAIS (comparacao com o recalc do especialista):")
esp = {"puro:cpu": 916.23, "puro:htp": 1527.49, "puro:opencl": 4313.55,
       "puro:vulkan": 1664.02, "opencl@bench": 5571.66, "vulkan@bench": 1522.80}
allok = True
for k in ["puro:cpu", "puro:htp", "puro:opencl", "puro:vulkan", "opencl@bench", "vulkan@bench"]:
    v = sub.get(k, [])
    med = statistics.median(v) if v else -1
    ok = abs(med - esp[k]) < 1.0
    allok = allok and ok
    out.append(f"  {k:16s} n={len(v)} mediana={med:.2f}  ref={esp[k]:.2f}  -> {'CONFERE' if ok else 'DIFERE'}")
out.append(f"  RESULTADO: {'TODOS CONFEREM (6/6)' if allok else 'DIVERGENCIA!'}")
out.append("")
out.append("== 2) Delta do espelho (validacao - benchmark), mesmo modo/corpus ==")
# curto (A1 valid opencl x A2 bench opencl) e medio (B1/B2 valid x B3/B4 bench)
def gen_do(rota, bench, hr_ini, hr_fim):
    v = [r["gen"] for pid, r in runs.items() if r["rota"] == rota and r["bench"] == bench
         and r["prefill"] is not None and hr_ini <= r["hora"] <= hr_fim and r["mut"] == ""]
    assert len(v) == 1, (rota, bench, hr_ini, hr_fim, v)
    return v[0]
curto_oc_valid = gen_do("opencl", False, "17:25", "17:26")     # A1
curto_oc_bench = gen_do("opencl@bench", True, "17:26", "17:27") # A2
med_oc_valid   = gen_do("opencl", False, "17:33", "17:34")     # B1
med_oc_bench   = gen_do("opencl@bench", True, "17:35", "17:37") # B3
med_vk_valid   = gen_do("vulkan", False, "17:34", "17:35")     # B2
med_vk_bench   = gen_do("vulkan@bench", True, "17:37", "17:38") # B4
for nome, v, b in [("curto opencl (8 tok)", curto_oc_valid, curto_oc_bench),
                   ("medio opencl (63 tok)", med_oc_valid, med_oc_bench),
                   ("medio vulkan (63 tok)", med_vk_valid, med_vk_bench)]:
    d = v - b
    out.append(f"  {nome}: valid={v:.2f} bench={b:.2f} delta={d:.2f}ms ({100.0*d/v:.1f}% do valid)")
out.append("")
out.append("NOTA: runs separadas nao isolam custo causal do espelho em condicao")
out.append("termica variavel (declarado); os deltas sao DIFERENCA OBSERVADA.")
import os
os.makedirs(r"D:/Projetos/SIG/docs/npu-hymt2/smart-rodada5", exist_ok=True)
open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
print("\n".join(out[22:40]))
print("salvo:", OUT)
