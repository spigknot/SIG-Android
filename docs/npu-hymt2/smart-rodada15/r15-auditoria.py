#!/usr/bin/env python3
# R15 §2: auditoria do port final (identidade/hashes/guards/diff)
import subprocess, hashlib, os
K = "/root/kq2/ggml/src/ggml-hexagon"
KT = "/root/kq-target"
S = "/root/sig-smart/llama/ggml/src/ggml-hexagon"

def sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()[:16]

print("=== (1) identidade dos 3 DSP (kq2 vs KQT): sha256 ===")
for f in ["htp/hmx-mm-kernels-tiled.h", "htp/hvx-mm-kernels-flat.h", "htp/hvx-mm-kernels-tiled.h"]:
    a, b = sha(f"{K}/{f}"), sha(f"{KT}/{f}")
    print(f"  {f}: kq2={a} kqt={b} {'IDENTICO' if a == b else '*** DIFERE! ***'}")

print()
print("=== (2) identidade vs parent (a base) — os 3 DSP devem = KQT = parent+KQ ===")
import subprocess
for f in ["htp/hmx-mm-kernels-tiled.h", "htp/hvx-mm-kernels-flat.h", "htp/hvx-mm-kernels-tiled.h"]:
    p = subprocess.run(["git", "-C", "/root/llama-cpp-npu", "show", f"82324fc:ggml/src/ggml-hexagon/{f}"], capture_output=True)
    ok = (hashlib.sha256(p.stdout).hexdigest()[:16] == sha(f"{S}/{f}"))
    print(f"  SIG-original {f} == parent? {'SIM' if ok else 'NAO'}")

print()
print("=== (3) guards de merge/fused no kq2 (provar fallback p/ Q6_K!) ===")
t = open(f"{K}/ggml-hexagon.cpp").read().splitlines()
for tok in ["is_mergeable_mul_mat(", "is_supported_mul_mat_nx", "supported_mul_mat", "mul_mat_id"]:
    print(f"--- '{tok}':")
    c = 0
    for i, ln in enumerate(t):
        if tok in ln and ln.strip().startswith(("static ", "if ", "return ", "const ", "bool ")):
            j = i
            while j > 0 and not t[j].startswith("static "):
                j -= 1
            print(f"  {i+1:5}| {ln.strip()[:110]}")
            c += 1
            if c > 6: break

print()
print("=== (4) coerencia typeIDs: ggml vs htp ===")
print(f"  ggml.h: GGML_TYPE_Q4_K=12/Q6_K=14 (esperado); htp-ops: ", end="")
o = open(f"{K}/htp/htp-ops.h").read()
print("HTP_TYPE_Q4_K=12" if "HTP_TYPE_Q4_K   = 12" in o else "?", "| ", "HTP_TYPE_Q6_K=14" if "HTP_TYPE_Q6_K   = 14" in o else "?")
# os static_asserts no ggml-hexagon.cpp?
print("  static_asserts no ggml-hexagon.cpp:", "GGML_TYPE_Q4_K" if "GGML_TYPE_Q4_K" in "".join(t) else "-")

print()
print("=== (5) diff stat final (SIG-orig -> kq2) por arquivo ===")
for f in ["ggml-hexagon.cpp", "htp/hmx-mm-kernels-tiled.h", "htp/htp-ops.h", "htp/hvx-mm-kernels-flat.h", "htp/hvx-mm-kernels-tiled.h", "htp/matmul-ops.c", "htp/matmul-ops.h"]:
    r = subprocess.run(["diff", "-u", f"{S}/{f}", f"{K}/{f}"], capture_output=True, text=True)
    adds = sum(1 for ln in r.stdout.splitlines() if ln.startswith("+") and not ln.startswith("+++"))
    dels = sum(1 for ln in r.stdout.splitlines() if ln.startswith("-") and not ln.startswith("---"))
    print(f"  {f}: +{adds} -{dels}")
