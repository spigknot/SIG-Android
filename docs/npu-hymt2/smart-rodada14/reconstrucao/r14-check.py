#!/usr/bin/env python3
# R14: verificar estado do matmul-ops.c pos-applyrej
import os
f = "/root/kq2/ggml/src/ggml-hexagon/htp/matmul-ops.c"
t = open(f).read()
print("tamanho:", len(t))
print("case HTP_TYPE_Q4_K:", t.count("case HTP_TYPE_Q4_K:"))
print("case HTP_TYPE_Q6_K:", t.count("case HTP_TYPE_Q6_K:"))
print("worker_loop_q6_k:", t.count("dequantize_tiled_worker_loop_q6_k"))
print("src0->type Q4_K em condicoes:", t.count("|| src0->type == HTP_TYPE_Q4_K"))
print("wtype Q4_K em condicoes:", t.count("|| wtype == HTP_TYPE_Q4_K"))
r = open("/root/kq-target/htp/matmul-ops.c").read()
print("--- KQT referencia ---")
print("case Q4_K:", r.count("case HTP_TYPE_Q4_K:"), "| case Q6_K:", r.count("case HTP_TYPE_Q6_K:"), "| worker q6k:", r.count("dequantize_tiled_worker_loop_q6_k"), "| src0 cond:", r.count("|| src0->type == HTP_TYPE_Q4_K"), "| wtype cond:", r.count("|| wtype == HTP_TYPE_Q4_K"))
# e o rej-out
if os.path.exists("/root/rej-out.txt"):
    print("--- /root/rej-out.txt ---")
    print(open("/root/rej-out.txt").read()[:400])
# matmul-ops.h
h = open("/root/kq2/ggml/src/ggml-hexagon/htp/matmul-ops.h").read()
rh = open("/root/kq-target/htp/matmul-ops.h").read()
print("--- h ---")
print("Q4_K no h (atual):", h.count("wtype == HTP_TYPE_Q4_K"), "| KQT:", rh.count("wtype == HTP_TYPE_Q4_K"))
