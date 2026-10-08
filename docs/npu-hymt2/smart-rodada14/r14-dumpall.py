#!/usr/bin/env python3
# R14: dump COMPLETO dos 29 conflitos restantes (sem truncar linhas criticas)
import os
base = "/root/kq-sig-work"
files = ["ggml-hexagon.cpp","htp/hmx-mm-kernels-tiled.h","htp/matmul-ops.c","htp/matmul-ops.h"]
for f in files:
    p = os.path.join(base, f)
    lines = open(p, encoding="utf-8", errors="replace").read().splitlines()
    i = 0
    while i < len(lines):
        if lines[i].startswith("<<<<<<<"):
            j1 = i + 1
            while j1 < len(lines) and not lines[j1].startswith("======="):
                j1 += 1
            j2 = j1 + 1
            while j2 < len(lines) and not lines[j2].startswith(">>>>>>>"):
                j2 += 1
            sig = lines[i+1:j1]
            kq = lines[j1+1:j2]
            print(f"@@@ {f}:L{i+1} sig={len(sig)} kq={len(kq)}")
            for ln in sig: print("S|" + ln[:150])
            for ln in kq: print("K|" + ln[:150])
            i = j2 + 1
            continue
        i += 1
    print()
