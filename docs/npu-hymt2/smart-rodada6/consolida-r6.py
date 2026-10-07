#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R6: consolida os JSONL nativos das sessoes (pf*/g*) num JSONL final +
agregado 3-warm por modo (mediana/min/max). Denominadores exclusivos por ID
(sess.i); nada de parser de labels para o dado principal."""
import json, glob, os, statistics
from collections import defaultdict

SRC = r"C:/llama-npu/rodada2/logs"
OUTDIR = r"D:/Projetos/SIG/docs/npu-hymt2/smart-rodada6"
os.makedirs(OUTDIR, exist_ok=True)

reqs = []
for f in sorted(glob.glob(os.path.join(SRC, "pf*.jsonl")) + glob.glob(os.path.join(SRC, "g[0-9]*.jsonl"))):
    nome = os.path.basename(f)
    for ln, linha in enumerate(open(f, encoding="utf-8", errors="replace")):
        linha = linha.strip()
        if not linha:
            continue
        try:
            j = json.loads(linha)
        except Exception as e:
            print(f"AVISO: linha invalida em {nome}:{ln}: {e}")
            continue
        if j.get("t") != "req":
            continue
        j["fonte"] = nome
        reqs.append(j)

# JSONL final
final = os.path.join(OUTDIR, "SESSOES-R6.jsonl")
with open(final, "w", encoding="utf-8", newline="\n") as f:
    for j in sorted(reqs, key=lambda x: (x.get("sess", ""), x.get("i", 0))):
        f.write(json.dumps(j, ensure_ascii=False) + "\n")
print(f"JSONL final: {final} ({len(reqs)} requests)")

# agregado: warm = load_dst_ms==0 && load_src_ms==0 (e ok); por (fonte, rota)
agg = defaultdict(list)
for j in reqs:
    warm = (j.get("load_dst_ms", 1) == 0) and (j.get("load_src_ms", 1) == 0)
    if not warm or not j.get("ok"):
        continue
    agg[(j["fonte"], j.get("rota", "?"))].append(j.get("wall_ms", 0))

rep = ["AGREGADO R6 (warm real: loads=0; wall_ms)", ""]
rep.append(f"{'fonte':22s} {'rota':18s} {'n':>2} {'mediana':>9} {'min':>9} {'max':>9}")
for k in sorted(agg.keys()):
    v = agg[k]
    if len(v) >= 2:
        rep.append(f"{k[0]:22s} {k[1]:18s} {len(v):>2} {statistics.median(v):>9.1f} {min(v):>9.1f} {max(v):>9.1f}")
for k in sorted(agg.keys()):
    v = agg[k]
    if len(v) < 2:
        rep.append(f"{k[0]:22s} {k[1]:18s} {len(v):>2} (amostras insuficientes: {['%.1f' % x for x in v]})")
open(os.path.join(OUTDIR, "AGREGADO-R6.txt"), "w", encoding="utf-8", newline="\n").write("\n".join(rep) + "\n")
print("\n".join(rep))
