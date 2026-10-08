#!/usr/bin/env python3
# R14: v4 final — KQ sempre (os S| sao contexto envolvente; o build e' o arbitro)
import os
base = "/root/kq-sig-work"
files = ["ggml-hexagon.cpp","htp/hmx-mm-kernels-tiled.h","htp/matmul-ops.c","htp/matmul-ops.h"]
for f in files:
    p = os.path.join(base, f)
    lines = open(p, encoding="utf-8", errors="replace").read().splitlines()
    out = []
    i = 0
    n = 0
    while i < len(lines):
        if lines[i].startswith("<<<<<<<"):
            j1 = i + 1
            while j1 < len(lines) and not lines[j1].startswith("======="):
                j1 += 1
            j2 = j1 + 1
            while j2 < len(lines) and not lines[j2].startswith(">>>>>>>"):
                j2 += 1
            out.extend(lines[j1+1:j2])
            n += 1
            i = j2 + 1
            continue
        out.append(lines[i]); i += 1
    open(p, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
    c = open(p, encoding="utf-8", errors="replace").read()
    print(f"{f}: resolvidos={n} markers_restantes={c.count('<<<<<<<')}")
print("R14_RESOLVE4_DONE")
