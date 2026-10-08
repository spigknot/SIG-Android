#!/usr/bin/env python3
# R14: dump completo dos conflitos manuais (matmul-ops.c e ggml-hexagon.cpp)
import os
base = "/root/kq-sig-work"
for f, maxn in [("htp/matmul-ops.c", 6), ("ggml-hexagon.cpp", 8), ("htp/matmul-ops.h", 4), ("htp/hmx-mm-kernels-tiled.h", 1)]:
    p = os.path.join(base, f)
    lines = open(p, encoding="utf-8", errors="replace").read().splitlines()
    shown = 0
    i = 0
    while i < len(lines) and shown < maxn:
        if lines[i].startswith("<<<<<<<"):
            j1 = i + 1
            while j1 < len(lines) and not lines[j1].startswith("======="):
                j1 += 1
            j2 = j1 + 1
            while j2 < len(lines) and not lines[j2].startswith(">>>>>>>"):
                j2 += 1
            sig = lines[i+1:j1]
            kq = lines[j1+1:j2]
            print(f"### {f}:L{i+1} sig={len(sig)} kq={len(kq)}")
            for ln in sig[:5]: print("   S|", ln[:115])
            for ln in kq[:10]: print("   K|", ln[:115])
            i = j2 + 1
            shown += 1
            continue
        i += 1
    print()
