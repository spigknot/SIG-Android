import hashlib
K = "/root/kq2/ggml/src/ggml-hexagon"
t = open(K + "/ggml-hexagon.cpp").read()
# os cases Q4_K/Q6_K estao ativos (sem gate)? => variante KQ_ON!
import re
na = t.count("case GGML_TYPE_Q4_K:")
nq = t.count("case GGML_TYPE_Q6_K:")
print(f"cases Q4_K={na} Q6_K={nq} (ativos sem gate => KQ_ON)")
print("gate presente?", "GGML_HEXAGON_KQ_ENABLED" in t)
# hashes de congelamento (7 fontes)
print("\n=== HASHES DE CONGELAMENTO (KQ_ON candidato) ===")
for f in ["ggml-hexagon.cpp","htp/hmx-mm-kernels-tiled.h","htp/htp-ops.h","htp/hvx-mm-kernels-flat.h","htp/hvx-mm-kernels-tiled.h","htp/matmul-ops.c","htp/matmul-ops.h"]:
    h = hashlib.sha256(open(f"{K}/{f}", "rb").read().replace(b"\r\n", b"\n")).hexdigest()
    print(f"{h}  {f}")
