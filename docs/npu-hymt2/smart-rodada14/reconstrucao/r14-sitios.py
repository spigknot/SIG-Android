#!/usr/bin/env python3
# R14: aplica os 5 sitios faltantes no matmul-ops.c + 2 no .h
p = "/root/kq2/ggml/src/ggml-hexagon/htp/matmul-ops.c"
t = open(p).read()
n = 0

# 1) src1_row_size tiled (hvx_mm_matmul_id_nx) - linha ~3815 KQT
busy = "size_t src1_row_size = (src0->type == HTP_TYPE_Q4_1) ? htp_mm_q8_1_tiled_row_size(act->ne[0]) : htp_mm_q8_0_tiled_row_size(act->ne[0]);"
novo = "size_t src1_row_size = (src0->type == HTP_TYPE_Q4_1 || src0->type == HTP_TYPE_Q4_K) ? htp_mm_q8_1_tiled_row_size(act->ne[0]) : htp_mm_q8_0_tiled_row_size(act->ne[0]);"
if busy in t:
    t = t.replace(busy, novo, 1); n += 1; print("1 ok")
else:
    print("1 NAO achou; tentando flex")
    import re
    m = re.search(r"size_t src1_row_size = \(src0->type == HTP_TYPE_Q4_1\)", t)
    print("   flex:", bool(m))

# 2/3) src1_row_size flat/tiled (op_matmul_nx)
b2 = "src1_row_size = (src0->type == HTP_TYPE_Q4_1) ? htp_mm_q8_1_flat_row_size(act->ne[0]) : htp_mm_q8_0_flat_row_size(act->ne[0]);"
n2 = "src1_row_size = (src0->type == HTP_TYPE_Q4_1 || src0->type == HTP_TYPE_Q4_K) ? htp_mm_q8_1_flat_row_size(act->ne[0]) : htp_mm_q8_0_flat_row_size(act->ne[0]);"
if b2 in t:
    t = t.replace(b2, n2, 1); n += 1; print("2 ok")
else: print("2 NAO achou")
b3 = "src1_row_size = (src0->type == HTP_TYPE_Q4_1) ? htp_mm_q8_1_tiled_row_size(act->ne[0]) : htp_mm_q8_0_tiled_row_size(act->ne[0]);"
n3 = "src1_row_size = (src0->type == HTP_TYPE_Q4_1 || src0->type == HTP_TYPE_Q4_K) ? htp_mm_q8_1_tiled_row_size(act->ne[0]) : htp_mm_q8_0_tiled_row_size(act->ne[0]);"
if b3 in t:
    t = t.replace(b3, n3, 1); n += 1; print("3 ok")
else: print("3 NAO achou")

# 4/5) cases Q4_K nos switches do op_matmul_nx (estilo do KQT: mesma linha)
for var, func in [("_flat;", "hvx_mm_nx_2d_repacked_q4_1_flat"), (";", "hvx_mm_nx_2d_repacked_q4_1")]:
    linha = f"case HTP_TYPE_Q4_1:   matmul_job_func = {func}{var}"
    if linha in t:
        # KQT coloca "case HTP_TYPE_Q4_K:   matmul_job_func = <mesma>;" ANTES (ou depois?)
        # do KQT: 'case HTP_TYPE_Q4_1:' quebra em 2 linhas e Q4_K vem na linha do func
        # iremos no estilo: substituir a linha por Q4_1 + Q4_K(func)
        alvo = f"case HTP_TYPE_Q4_1:\n                case HTP_TYPE_Q4_K:   matmul_job_func = {func}{var}"
        t = t.replace(linha, alvo, 1); n += 1; print(f"4/5 ok {func}")
    else:
        print(f"4/5 NAO achou {func}")

open(p, "w").write(t)
print("total aplicado:", n)

# ---- .h: faltam 2 ----
p2 = "/root/kq2/ggml/src/ggml-hexagon/htp/matmul-ops.h"
h = open(p2).read()
import re
for pat, rep in [
    (r"\(wtype == HTP_TYPE_Q4_1\)( \? htp_mm_q8_1_flat_row_size)", r"(wtype == HTP_TYPE_Q4_1 || wtype == HTP_TYPE_Q4_K)\1"),
    (r"\(wtype == HTP_TYPE_Q4_1\)( \? htp_mm_q8_1_tiled_row_size)", r"(wtype == HTP_TYPE_Q4_1 || wtype == HTP_TYPE_Q4_K)\1"),
]:
    h2 = re.sub(pat, rep, h, count=1)
    if h2 != h:
        print("h ok:", pat[:50])
        h = h2
    else:
        print("h NAO achou:", pat[:50])
open(p2, "w").write(h)
import subprocess
print(subprocess.run(["grep","-c","Q4_K","/root/kq2/ggml/src/ggml-hexagon/htp/matmul-ops.h"],capture_output=True,text=True).stdout)
print("R14_FIX_SITIOS_DONE")
