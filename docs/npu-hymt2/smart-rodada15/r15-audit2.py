#!/usr/bin/env python3
# R15 §2b: auditoria com normalizacao CRLF + guards
import subprocess, hashlib
K = "/root/kq2/ggml/src/ggml-hexagon"
KT = "/root/kq-target"
S = "/root/sig-smart/llama/ggml/src/ggml-hexagon"

def norm(p):
    return open(p, "rb").read().replace(b"\r\n", b"\n")

print("=== (1N) identidade normalizada ===")
for f in ["htp/hmx-mm-kernels-tiled.h", "htp/hvx-mm-kernels-flat.h", "htp/hvx-mm-kernels-tiled.h"]:
    a = hashlib.sha256(norm(f"{S}/{f}")).hexdigest()[:16]
    pb = subprocess.run(["git", "-C", "/root/llama-cpp-npu", "show", f"82324fc:ggml/src/ggml-hexagon/{f}"], capture_output=True).stdout
    b = hashlib.sha256(pb).hexdigest()[:16]
    print(f"  {f}: SIG-norm={a} parent-norm={b} {'IDENTICO' if a == b else '*** DIFERE ***'}")

print()
print("=== (2N) diff stat normalizado (SIG-orig -> kq2) ===")
for f in ["ggml-hexagon.cpp", "htp/hmx-mm-kernels-tiled.h", "htp/htp-ops.h", "htp/hvx-mm-kernels-flat.h", "htp/hvx-mm-kernels-tiled.h", "htp/matmul-ops.c", "htp/matmul-ops.h"]:
    a = norm(f"{S}/{f}").decode(errors="replace").splitlines()
    b = norm(f"{K}/{f}").decode(errors="replace").splitlines()
    import difflib
    d = list(difflib.unified_diff(a, b, lineterm=""))
    adds = sum(1 for ln in d if ln.startswith("+") and not ln.startswith("+++"))
    dels = sum(1 for ln in d if ln.startswith("-") and not ln.startswith("---"))
    print(f"  {f}: +{adds} -{dels}")

print()
print("=== (3N) is_mergeable_mul_mat (body!) e supported_mul_mat (contexto Q4_K/Q6_K) ===")
t = norm(f"{K}/ggml-hexagon.cpp").decode().splitlines()
i = next(i for i, ln in enumerate(t) if "static bool is_mergeable_mul_mat(const ggml_tensor * t)" in ln)
for j in range(i, i+8): print(f"  {j+1:5}|{t[j][:120]}")
print()
i2 = next(i for i, ln in enumerate(t) if "ggml_hexagon_supported_mul_mat(const" in ln)
# achar os cases Q4_K/Q6_K dentro da funcao
c = 0
for j in range(i2, min(i2+140, len(t))):
    if "Q4_K" in t[j] or "Q6_K" in t[j] or "GGML_TYPE_F16" in t[j]:
        print(f"  {j+1:5}|{t[j][:120]}")
        c += 1
        if c > 10: break
