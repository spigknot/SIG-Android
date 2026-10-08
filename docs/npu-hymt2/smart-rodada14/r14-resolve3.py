#!/usr/bin/env python3
# R14: resolucao v3 — KQ quando: (a) superconjunto exato; (b) sig<=4 linhas nao-vazias
# e >=70% delas aparecem no kq; resto fica MANUAL.
import os
base = "/root/kq-sig-work"
files = ["ggml-hexagon.cpp","htp/hmx-mm-kernels-tiled.h","htp/matmul-ops.c","htp/matmul-ops.h"]

def norm(l): return l.strip()

for f in files:
    p = os.path.join(base, f)
    lines = open(p, encoding="utf-8", errors="replace").read().splitlines()
    out = []
    i = 0
    auto = manual = 0
    while i < len(lines):
        if lines[i].startswith("<<<<<<<"):
            j1 = i + 1
            while j1 < len(lines) and not lines[j1].startswith("======="):
                j1 += 1
            j2 = j1 + 1
            while j2 < len(lines) and not lines[j2].startswith(">>>>>>>"):
                j2 += 1
            sig = [norm(x) for x in lines[i+1:j1] if norm(x)]
            kq  = [norm(x) for x in lines[j1+1:j2] if norm(x)]
            kqset = set(kq)
            ok = False
            if all(s in kqset for s in sig):
                ok = True
            elif len(sig) <= 4 and sig:
                hit = sum(1 for s in sig if s in kqset)
                ok = (hit / len(sig)) >= 0.7
            if ok:
                out.extend(lines[j1+1:j2])
                auto += 1
            else:
                out.extend(lines[i:j2+1])
                manual += 1
            i = j2 + 1
            continue
        out.append(lines[i]); i += 1
    open(p, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
    left = open(p, encoding="utf-8", errors="replace").read().count("<<<<<<<")
    print(f"{f}: auto={auto} manual={manual} restantes={left}")
print("R14_RESOLVE3_DONE")
