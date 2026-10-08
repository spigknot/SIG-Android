#!/usr/bin/env python3
# R14: dump total para o encaixe dos 8 pendentes
import re
def parse_hunks(path):
    hunks, cur = [], None
    for raw in open(path, encoding="utf-8", errors="replace").read().splitlines():
        if raw.startswith("@@"):
            cur = []; hunks.append(cur); continue
        if cur is None: continue
        if raw.startswith("+++") or raw.startswith("---"): continue
        if raw.startswith("+") or raw.startswith("-") or raw.startswith(" ") or raw=="":
            cur.append(raw)
    return hunks

t = open("/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp").read().splitlines()
hunks = parse_hunks("/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp.rej")

print("=== HUNK 1 completo ===")
for ln in hunks[1]: print(" ", ln[:135])
print("=== HUNK 2 completo ===")
for ln in hunks[2]: print(" ", ln[:135])
print("=== HUNK 3 completo ===")
for ln in hunks[3]: print(" ", ln[:135])
print("=== HUNK 4 completo ===")
for ln in hunks[4]: print(" ", ln[:135])
print()
print("=== SIG 1000-1062 (switches repack) ===")
for i in range(999, 1062): print(f"{i+1:5}|{t[i][:135]}")
print()
for tok in ["flat_src1_row_size", "is_repack_type(src0->type)", "n_weights"]:
    print(f"=== SIG: '{tok}' ===")
    c = 0
    for i, ln in enumerate(t):
        if tok in ln:
            print(f"{i+1:5}|{ln.strip()[:135]}")
            c += 1
            if c > 8: break
    print()
print("=== HUNK 0: primeiras 30 e ultimas 6 linhas '+' ===")
pl = [x[1:] for x in hunks[0] if x.startswith("+")]
print(f"total +: {len(pl)}")
for ln in pl[:28]: print(" +", ln[:135])
print(" ...")
for ln in pl[-6:]: print(" +", ln[:135])
