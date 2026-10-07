#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""c2-analyze.py — parse dos resultados do bench C.2 (3 backends) -> matriz."""
import re, glob, json

def parse_bench(path):
    """extrai linhas | modelo | size | params | backend | ngl | dev | test | t/s |"""
    rows = []
    for l in open(path, encoding="utf-8", errors="replace"):
        l = re.sub(r"\x1b\[[0-9;]*m", "", l).strip()
        if not l.startswith("|"):
            continue
        cells = [c.strip() for c in l.strip("|").split("|")]
        if len(cells) < 8 or cells[0] in ("model",) or set(cells[0]) <= {"-"}:
            continue
        try:
            test = cells[-2]; ts = cells[-1]
            rows.append({"test": test, "tps": ts})
        except Exception:
            pass
    return rows

print("=== C.2 resultados por run ===")
for f in sorted(glob.glob("C:/llama-npu/rodada2/logs/c2_*.out")):
    nome = f.split("\\")[-1].split("/")[-1]
    rows = parse_bench(f)
    print(f"\n--- {nome} ({len(rows)} linhas) ---")
    for r in rows:
        print(f"  {r['test']:10s} {r['tps']}")
