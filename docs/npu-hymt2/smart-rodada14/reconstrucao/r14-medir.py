#!/usr/bin/env python3
# R14: medir distancias por arquivo para escolher a base de reconstrucao
import subprocess, difflib, os
files = ["ggml-hexagon.cpp","htp/hmx-mm-kernels-tiled.h","htp/htp-ops.h","htp/hvx-mm-kernels-flat.h","htp/hvx-mm-kernels-tiled.h","htp/matmul-ops.c","htp/matmul-ops.h"]
S = "/root/sig-smart/llama/ggml/src/ggml-hexagon"
for f in files:
    # parent do arquivo via git
    r = subprocess.run(["git","-C","/root/llama-cpp-npu","show",f"82324fc508006de234552e701f4509c72c4fdd8d:ggml/src/ggml-hexagon/{f}"], capture_output=True, text=True)
    parent = r.stdout.splitlines()
    sig = open(f"{S}/{f}", encoding="utf-8", errors="replace").read().splitlines()
    kq  = open(f"/root/kq-target/{f}", encoding="utf-8", errors="replace").read().splitlines()
    d_sp = sum(1 for _ in difflib.unified_diff(parent, sig, lineterm=""))  # SIG vs parent
    d_pk = sum(1 for _ in difflib.unified_diff(parent, kq, lineterm=""))   # KQ(sem parent) - o patch
    d_sk = sum(1 for _ in difflib.unified_diff(sig, kq, lineterm=""))      # SIG vs KQT
    print(f"{f}: linhas SIG={len(sig)} KQT={len(kq)} | diff(SIG,parent)={d_sp} diff(parent,KQT)={d_pk} diff(SIG,KQT)={d_sk}")
