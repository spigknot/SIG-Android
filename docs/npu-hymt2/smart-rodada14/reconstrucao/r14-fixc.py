#!/usr/bin/env python3
# R14: fix final do matmul-ops.c (4 src1_row_size + 4 case Q4_K qkv/ffn)
import re
p = "/root/kq2/ggml/src/ggml-hexagon/htp/matmul-ops.c"
t = open(p).read()
n = 0

# (a) src1_row_size com src1->ne[0] (4 ocorrencias SEM Q4_K ainda)
for _ in range(6):
    t = open(p).read()
    m = re.search(r"src1_row_size = \(src0->type == HTP_TYPE_Q4_1\)( \? htp_mm_q8_1_(?:flat|tiled)_row_size\(src1->ne\[0\]\))", t)
    if not m: break
    t2 = re.sub(r"src1_row_size = \(src0->type == HTP_TYPE_Q4_1\)( \? htp_mm_q8_1_(?:flat|tiled)_row_size\(src1->ne\[0\]\))",
                r"src1_row_size = (src0->type == HTP_TYPE_Q4_1 || src0->type == HTP_TYPE_Q4_K)\1", t, count=1)
    open(p, "w").write(t2)
    n += 1
print("src1_row_size fixes:", n)

# (b) cases qkv/ffn: inserir case Q4_K antes dos Q4_1 que NAO tenham Q4_K vizinho
t = open(p).read()
lines = t.splitlines()
out = []
ins = 0
for i, ln in enumerate(lines):
    if "case HTP_TYPE_Q4_1:" in ln and "repacked_q4_1" in ln:
        # verificar vizinhanca (linha anterior tem Q4_K?)
        prev = "\n".join(out[-2:]) if out else ""
        if "HTP_TYPE_Q4_K" not in prev:
            indent = ln[:len(ln) - len(ln.lstrip())]
            out.append(indent + "case HTP_TYPE_Q4_K:")
            ins += 1
    out.append(ln)
t2 = "\n".join(out) + "\n"
open(p, "w").write(t2)
print("case Q4_K inseridos (qkv/ffn):", ins)
c = open(p).read()
print("total case Q4_K:", c.count("case HTP_TYPE_Q4_K:"), "| case Q6_K:", c.count("case HTP_TYPE_Q6_K:"))
print("R14_FIXC_DONE")
