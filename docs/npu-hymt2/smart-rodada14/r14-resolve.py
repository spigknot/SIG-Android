#!/usr/bin/env python3
# R14: resolve conflitos vazios (SIG-side empty => adicao KQ pura) e reporta os REAIS
import os, sys
base = "/root/kq-sig-work"
files = ["ggml-hexagon.cpp","htp/hmx-mm-kernels-tiled.h","htp/htp-ops.h","htp/hvx-mm-kernels-flat.h","htp/hvx-mm-kernels-tiled.h","htp/matmul-ops.c","htp/matmul-ops.h"]
tot = resolv = reais = 0
reais_det = []
for f in files:
    p = os.path.join(base, f)
    lines = open(p, encoding="utf-8", errors="replace").read().splitlines()
    out = []
    i = 0
    nc_reais = 0
    while i < len(lines):
        if lines[i].startswith("<<<<<<<"):
            # procura os separadores
            j1 = i + 1
            while j1 < len(lines) and not (lines[j1].startswith("=======") or lines[j1].startswith("|||||||")):
                j1 += 1
            # 3-way: <<<<<<< | base ||||||| ... ======= sig(?) kq(?) >>>>>>>
            # formato observado: <<<<<<<  ======= <KQ> >>>>>>>  (SIG vazio)
            # ou: <<<<<<< <sig> ======= <kq> >>>>>>>
            if j1 < len(lines) and lines[j1].startswith("======="):
                # sem |||||||: sig-side vazio?
                sig_side = lines[i+1:j1]
                j2 = j1 + 1
                while j2 < len(lines) and not lines[j2].startswith(">>>>>>>"):
                    j2 += 1
                kq_side = lines[j1+1:j2]
                tot += 1
                if len(sig_side) == 0:
                    # adicao KQ pura: fica o kq_side
                    out.extend(kq_side)
                    resolv += 1
                elif len(kq_side) == 0:
                    out.extend(sig_side)
                    resolv += 1
                else:
                    reais += 1; nc_reais += 1
                    out.extend(lines[i:j2+1])  # mantem para resolver a mao
                    reais_det.append(f"{f}: L{i+1} (sig={len(sig_side)} kq={len(kq_side)})")
                i = j2 + 1
                continue
            elif j1 < len(lines) and lines[j1].startswith("|||||||"):
                # formato 3-way completo: <<<<<<< sig ||||||| base ======= kq >>>>>>> (ou variacoes)
                jb = j1
                j2 = jb + 1
                while j2 < len(lines) and not lines[j2].startswith("======="):
                    j2 += 1
                j3 = j2 + 1
                while j3 < len(lines) and not lines[j3].startswith(">>>>>>>"):
                    j3 += 1
                sig_side = lines[i+1:jb]; base_side = lines[jb+1:j2]; kq_side = lines[j2+1:j3]
                tot += 1
                if len(base_side) == 0 and len(sig_side) == 0:
                    out.extend(kq_side); resolv += 1
                elif len(base_side) == 0 and len(kq_side) == 0:
                    out.extend(sig_side); resolv += 1
                else:
                    reais += 1; nc_reais += 1
                    out.extend(lines[i:j3+1])
                    reais_det.append(f"{f}: L{i+1} 3way (sig={len(sig_side)} base={len(base_side)} kq={len(kq_side)})")
                i = j3 + 1
                continue
        out.append(lines[i])
        i += 1
    open(p, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
    print(f"{f}: reais restantes={nc_reais}")
print(f"TOTAL={tot} resolvidos_auto={resolv} reais={reais}")
for d in reais_det[:60]:
    print("  REAL:", d)
