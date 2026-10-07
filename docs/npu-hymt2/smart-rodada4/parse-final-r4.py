#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Parser DEFINITIVO do matched R4 (do log global, sem janelas).
CSV por tentativa + agregado por modo (mediana/dispersao; denominadores exclusivos).
Colunas: pid, hora, rota, modo_probe, prefill_ms, gen_ms, resultado, texto, nota."""
import re, csv, statistics, os
from collections import defaultdict

LOG = r"C:/llama-npu/rodada2/logs/npuprobe-r4.log"
OUTDIR = r"D:/Projetos/SIG/docs/npu-hymt2/smart-rodada4"

t = open(LOG, encoding="utf-8", errors="replace").read()
runs, ordem = {}, []
for l in t.splitlines():
    m = re.search(r"(\d\d:\d\d:\d\d\.\d+)\s+I/NpuProbe\(\s*(\d+)\): (.*)", l)
    if not m: continue
    hora, pid, msg = m.groups()
    msg = msg.strip()
    r = runs.get(pid)
    if r is None:
        r = {"pid": pid, "hora": hora, "rota": None, "bench": False, "resultado": None,
             "prefill": "", "gen": "", "texto": "", "mut": "", "tempos_visto": False}
        runs[pid] = r; ordem.append(pid)
    if msg.startswith("SMARTv2 rota="):
        if r["rota"] is None:
            mm = re.search(r"rota='([^']+)'", msg); r["rota"] = mm.group(1) if mm else "?"
            m2 = re.search(r"mutante=(\S+)", msg)
            r["mut"] = (m2.group(1) if (m2 and m2.group(1) != "(none)") else "")
    elif "MODO BENCHMARK" in msg: r["bench"] = True
    elif msg.startswith(("A1 tempos:", "puro: ok=")) and not r["tempos_visto"]:
        mm = re.search(r"prefill=([\d.]+)ms.*?(?:gen=([\d.]+)ms)", msg)
        if mm:
            r["prefill"], r["gen"] = mm.group(1), mm.group(2)
            r["tempos_visto"] = True
    elif msg.startswith("A1 texto:") and not r["texto"]:
        r["texto"] = msg[9:].strip()[:70]
    elif msg.startswith("puro texto:") and not r["texto"]:
        r["texto"] = msg[11:].strip()[:70]
    elif msg.startswith("RESULTADO:"):
        if r["resultado"] is None or ("APP-UID" in (r["resultado"] or "") and "APP-UID" not in msg):
            r["resultado"] = msg.replace("RESULTADO: ", "")

rows = []
for pid in ordem:
    r = runs[pid]
    if r["rota"] is None or not r["resultado"] or "APP-UID" in (r["resultado"] or ""):
        continue
    rows.append({"pid": pid, "hora": r["hora"], "rota": r["rota"],
                 "modo_probe": ("bench" if r["bench"] else ("puro" if r["rota"].startswith("puro") else "valid")),
                 "mut": r["mut"], "prefill_ms": r["prefill"], "gen_ms": r["gen"],
                 "texto": r["texto"], "resultado": (r["resultado"] or "")[:80]})

os.makedirs(OUTDIR, exist_ok=True)
with open(os.path.join(OUTDIR, "MATCHED-R4.csv"), "w", encoding="utf-8", newline="") as f:
    w = csv.DictWriter(f, fieldnames=["pid", "hora", "rota", "modo_probe", "mut",
                                      "prefill_ms", "gen_ms", "texto", "resultado"])
    w.writeheader(); w.writerows(rows)
print(f"CSV: {len(rows)} linhas")

# agregado: matched do lote C (rotas puras + smart bench, sem mutantes)
C_rotas = {"puro:cpu": "puro CPU", "puro:htp": "puro HTP", "puro:opencl": "puro OpenCL",
           "puro:vulkan": "puro Vulkan", "opencl@bench": "Smart OC", "vulkan@bench": "Smart VK"}
agg = defaultdict(list)
for r in rows:
    chave = None
    if r["rota"] in ("puro:cpu", "puro:htp", "puro:opencl", "puro:vulkan") and r["mut"] == "" and r["hora"] >= "17:40":
        chave = C_rotas[r["rota"]]
    elif r["rota"].replace("@bench", "") in ("opencl", "vulkan") and r["modo_probe"] == "bench" and r["mut"] == "" and r["hora"] >= "17:40":
        chave = "Smart OC" if r["rota"].startswith("opencl") else "Smart VK"
    if chave and r["gen_ms"]:
        try: agg[chave].append(float(r["gen_ms"]))
        except ValueError: pass

rep = ["AGREGADO MATCHED R4 (gen_ms, corpus curto, sessao QUENTE; lote C)", ""]
rep.append(f"{'modo':12s} {'n':>2} {'mediana':>9} {'min':>9} {'max':>9} {'amplitude':>9}")
for k in ["puro CPU", "puro HTP", "puro OpenCL", "puro Vulkan", "Smart OC", "Smart VK"]:
    v = agg.get(k, [])
    if v:
        rep.append(f"{k:12s} {len(v):>2} {statistics.median(v):>9.1f} {min(v):>9.1f} {max(v):>9.1f} {max(v)-min(v):>9.1f}")
    else:
        rep.append(f"{k:12s}  0  (sem amostras no lote C)")
open(os.path.join(OUTDIR, "AGREGADO-MATCHED.txt"), "w", encoding="utf-8", newline="\n").write("\n".join(rep) + "\n")
print("\n".join(rep))
