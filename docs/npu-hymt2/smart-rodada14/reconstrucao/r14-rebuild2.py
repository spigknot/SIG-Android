#!/usr/bin/env python3
# R14 "reorganiza o Frankenstein": reconstrucao LIMPA por arquivo
# - 3 headers DSP: SIG == parent => KQT e' o objetivo exato (copia direta!)
# - htp-ops.h: ORIG + 2 opcodes
# - matmul-ops.c/h + ggml-hexagon.cpp: ORIG + patch KQ, medindo aplicabilidade (dry-run)
import subprocess, os, shutil

S = "/root/sig-smart/llama/ggml/src/ggml-hexagon"
K = "/root/kq-target"
W = "/root/kq2/ggml/src/ggml-hexagon"
shutil.rmtree("/root/kq2", ignore_errors=True)
os.makedirs(W + "/htp")
# 1) base: SIG completo
for f in ["ggml-hexagon.cpp","CMakeLists.txt","htp-drv.cpp","htp-drv.h","htp-opnode.h"]:
    if os.path.exists(f"{S}/{f}"):
        shutil.copy(f"{S}/{f}", f"{W}/{f}")
for f in os.listdir(S + "/htp"):
    src = f"{S}/htp/{f}"
    if os.path.isfile(src):
        shutil.copy(src, f"{W}/htp/{f}")
print("base SIG copiada")

# 2) os 3 headers DSP: KQT direto (SIG == parent!)
for f in ["hmx-mm-kernels-tiled.h","hvx-mm-kernels-flat.h","hvx-mm-kernels-tiled.h"]:
    shutil.copy(f"{K}/{f}", f"{W}/htp/{f}")
    print("DSP direto do KQT:", f)

# 3) htp-ops.h: ORIG + 2 opcodes
p = f"{W}/htp/htp-ops.h"
t = open(p).read()
old = "    HTP_TYPE_Q4_1   = 3,\n    HTP_TYPE_Q8_0   = 8,\n"
assert t.count(old) == 1, ("ops", t.count(old))
t = t.replace(old, "    HTP_TYPE_Q4_1   = 3,\n    HTP_TYPE_Q8_0   = 8,\n    HTP_TYPE_Q4_K   = 12,\n    HTP_TYPE_Q6_K   = 14,\n", 1)
open(p, "w").write(t)
print("htp-ops.h: +2 opcodes")

# 4) matmul-ops.c/h + ggml-hexagon.cpp: patch KQ por arquivo, dry-run p/ medir
subprocess.run(["bash","-c","cd /root/llama-cpp-npu && for f in matmul-ops.c matmul-ops.h; do git diff 82324fc508006de234552e701f4509c72c4fdd8d 1ec81880944a63bc4aaf1abfe9a6d35c7569a757 -- ggml/src/ggml-hexagon/htp/$f > /root/kq-$f.patch; done; git diff 82324fc 1ec818809 -- ggml/src/ggml-hexagon/ggml-hexagon.cpp > /root/kq-hexagon.patch"], check=True)
for name, patch in [("htp/matmul-ops.c","/root/kq-matmul-ops.c.patch"),
                    ("htp/matmul-ops.h","/root/kq-matmul-ops.h.patch"),
                    ("ggml-hexagon.cpp","/root/kq-hexagon.patch")]:
    r = subprocess.run(["patch","-p4","--fuzz=0","--dry-run","-i",patch], cwd=W, capture_output=True, text=True)
    ok = r.stdout.count("OK") + r.stdout.count("Done")
    fails = r.stdout.count("FAILED") + r.stdout.count("fail")
    tail = (r.stdout + r.stderr).strip().splitlines()[-3:]
    print(f"dry-run {name}: rc={r.returncode} ok~{ok} fails~{fails}")
    for ln in tail: print("   ", ln[:120])
