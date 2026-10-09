import subprocess
K = "/root/kq2/ggml/src/ggml-hexagon"
# (a) enum do htp-ops (valores! consistencia host<->skel)
t = open(K + "/htp/htp-ops.h").read()
import re
m = re.search(r"enum htp_data_type \{(.*?)\};", t, re.S)
print("=== enum htp_data_type (skel/interface!) ===")
print(m.group(1)[:700])
# (b) os workers q6_k/q4_k no matmul-ops.c: declaracao vs uso
mo = open(K + "/htp/matmul-ops.c").read()
print("\n=== workers dequant (refs no .c) ===")
for w in ["dequantize_tiled_worker_loop_q6_k", "dequantize_tiled_worker_loop_q4_1", "dequantize_tiled_worker_loop_q4_k"]:
    c = mo.count(w)
    print(f"  {w}: {c}")
# (c) onde o worker q6_k e' definido?
r = subprocess.run(["grep", "-rn", "dequantize_tiled_worker_loop_q6_k", K], capture_output=True, text=True)
print(r.stdout[:900])
