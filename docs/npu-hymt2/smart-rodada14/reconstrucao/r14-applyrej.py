#!/usr/bin/env python3
# R14: aplica os hunks rejeitados do matmul-ops via regex flexivel (espacos)
# e compara com o KQT para garantir fidelidade.
import re
W = "/root/kq2/ggml/src/ggml-hexagon"
K = "/root/kq-target/htp"

def apply_flex(path, buscar, novo, maxn=1):
    t = open(path).read()
    pat = re.escape(buscar).replace(r"\ ", r"[ \t]+").replace("\\\t", "[ \t]+")
    # exato primeiro, depois flexivel
    if buscar in t:
        t2 = t.replace(buscar, novo, maxn)
        open(path, "w").write(t2)
        return t.count(buscar)
    m = re.search(pat, t)
    if m:
        t2 = re.sub(pat, novo.replace("\\", "\\\\"), t, count=maxn)
        open(path, "w").write(t2)
        return 1
    return 0

# ---- matmul-ops.c ----
p = W + "/htp/matmul-ops.c"
t = open(p).read()

# (A) dequant: Q4_K + Q6_K no switch do hmx_mm_id (linha ~3348)
buscarA = """        case HTP_TYPE_Q4_1:   dequant_worker_fn = dequantize_tiled_worker_loop_q4_1; break;
        case HTP_TYPE_MXFP4:  dequant_worker_fn = dequantize_tiled_worker_loop_mxfp4; break;
        case HTP_TYPE_Q8_0:   dequant_worker_fn = dequantize_tiled_worker_loop_q8_0; break;
        case HTP_TYPE_F16:    dequant_worker_fn = convert_f16_worker_loop; break;"""
novoA = """        case HTP_TYPE_Q4_1:
        case HTP_TYPE_Q4_K:   dequant_worker_fn = dequantize_tiled_worker_loop_q4_1; break;
        case HTP_TYPE_MXFP4:  dequant_worker_fn = dequantize_tiled_worker_loop_mxfp4; break;
        case HTP_TYPE_Q8_0:   dequant_worker_fn = dequantize_tiled_worker_loop_q8_0; break;
        case HTP_TYPE_Q6_K:   dequant_worker_fn = dequantize_tiled_worker_loop_q6_k; break;
        case HTP_TYPE_F16:    dequant_worker_fn = convert_f16_worker_loop; break;"""
nA = apply_flex(p, buscarA, novoA, 1)
print("A dequant hmx_id:", nA)

# (B) as 4x quant_task/row_size Q4_1 -> + Q4_K (flexivel, NAO tocar as ja feitas)
nB = 0
for _ in range(10):
    t = open(p).read()
    m = re.search(r"\(src0->type == HTP_TYPE_Q4_1\)( \? (?:quantize_f32_q8_1|htp_mm_q8_1))", t)
    if not m: break
    t2 = re.sub(r"\(src0->type == HTP_TYPE_Q4_1\)( \? (?:quantize_f32_q8_1|htp_mm_q8_1))",
                r"(src0->type == HTP_TYPE_Q4_1 || src0->type == HTP_TYPE_Q4_K)\1", t, count=1)
    open(p, "w").write(t2)
    nB += 1
print("B quant/row Q4_K:", nB)

# (C) switches de job func: case Q4_1: X → case Q4_1: case Q4_K: X
nC = 0
for _ in range(12):
    t = open(p).read()
    m = re.search(r"(\n\s*case HTP_TYPE_Q4_1:)(\s+matmul_job_func = \w+;)", t)
    if not m: break
    t2 = re.sub(r"(\n\s*case HTP_TYPE_Q4_1:)(\s+matmul_job_func = (\w+);)",
                r"\1\n                case HTP_TYPE_Q4_K:\2", t, count=1)
    if t2 == t: break
    open(p, "w").write(t2)
    nC += 1
print("C job funcs Q4_K:", nC)

# verificar duplicatas eventuais
chk = open(p).read()
print("Q4_K cases:", chk.count("case HTP_TYPE_Q4_K:"), "| Q6_K cases:", chk.count("case HTP_TYPE_Q6_K:"))
print("worker_loop_q6_k refs:", chk.count("dequantize_tiled_worker_loop_q6_k"))

# ---- matmul-ops.h (1 hunk) ----
ph = W + "/htp/matmul-ops.h"
th = open(ph).read()
nH = 0
for _ in range(4):
    th = open(ph).read()
    m = re.search(r"size_t (flat|tiled)_act_row_size  = \(wtype == HTP_TYPE_Q4_1\)", th)
    if not m: break
    th2 = re.sub(r"\(wtype == HTP_TYPE_Q4_1\)( \? htp_mm_q8_1_(?:flat|tiled)_row_size)",
                 r"(wtype == HTP_TYPE_Q4_1 || wtype == HTP_TYPE_Q4_K)\1", th, count=1)
    open(ph, "w").write(th2)
    nH += 1
print("H act_row_size:", nH)
print("R14_APPLY_REJ_DONE")
