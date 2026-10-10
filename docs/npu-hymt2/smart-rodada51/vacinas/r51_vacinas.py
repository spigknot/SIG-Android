#!/usr/bin/env python3
# r51_vacinas.py — R51 FASE 2: vacinas do CONSUMER (gate estrito) e do BLOCO DE CAPTURA (skel)
# Extrai os trechos LITERAIS: predicate val48 do probe.cpp (pos-R51) e o bloco de
# captura pos-barreira do matmul-ops.c do cohort. Negativos PRIMEIRO (2.1).
import re, subprocess, sys
PROBE = "C:/npu-probe/app/src/main/cpp/probe.cpp"
SKEL = "D:/Projetos/SIG/docs/npu-hymt2/smart-rodada51/sources/htp/matmul-ops.c"
OUT = "/root/r51_vac"
t = open(PROBE, encoding="utf-8", errors="replace").read()

# --- struct r48_st literal
start = t.index("struct r48_st {")
end = t.index("} st48;", start) + len("} st48;")
sdef = t[start:end+1].rstrip(";") + ";"
# --- predicate val48 literal
a = t.index("const bool val48 = (")
b = t.index(";", t.index("st48.r50_src1_nrows == 1 && st48.r50_total_nb == 48u)")) + 1
pred = t[a:b]
assert "r50_quant_func_id == 3" in pred, "predicate R51 nao contem o gate estrito!"

consumer = f'''#include <cstdint>
#include <cstdio>
#include <cstring>
enum{{GGML_TYPE_Q6_K=14}};
{sdef}
static bool gate(r48_st st48, int g48_ret = 0) {{
{pred}
    return val48;
}}
static int falhas = 0;
#define REQ(cond, msg) do {{ if (!(cond)) {{ printf("VAC_FAIL: %s\\n", msg); falhas++; }} }} while (0)
static r48_st base() {{
    r48_st s; memset(&s, 0, sizeof(s));
    s.status=2; s.magic=0x52343644u; s.version=3; s.epoch=1; s.type=14; s.K=6144; s.N=2048; s.B=1;
    s.rows_done=1; s.row_size=221184; s.payload_off=64; s.capacity=262144; s.ptr=1;
    s.r50_meta_valid=1; s.r49_op_entered=1; s.r49_n_hmx=0; s.r49_kernel_type=10;
    s.r50_dsp_decoded=1; s.r50_need_quant=1; s.r50_quant_func_id=3; s.r50_n_threads=8;
    s.r50_n_quant_tasks=8; s.r50_src1_nrows=1; s.r50_total_nb=48;
    return s;
}}
int main() {{
    // POSITIVO (profile observado B1/nthr8): qfn=3 nqt=8
    REQ(gate(base()) == true, "P1 baseline qfn3/nqt8 deve PASSAR");
    printf("P1 baseline qfn=3 nqt=8 nthr=8: aceito=1\\n");
    // NEGATIVOS PRIMEIRO (2.2): qfn errado
    for (int q = 0; q <= 9; ++q) {{
        if (q == 3) continue;
        r48_st s = base(); s.r50_quant_func_id = q;
        REQ(gate(s) == false, "N-qfn deve rejeitar");
        printf("N qfn=%d: aceito=0\\n", q);
    }}
    // nqt inconsistente
    {{ r48_st s = base(); s.r50_n_quant_tasks = 999; REQ(!gate(s), "N-nqt999"); printf("N nqt=999: aceito=0\\n"); }}
    {{ r48_st s = base(); s.r50_n_quant_tasks = 7;   REQ(!gate(s), "N-nqt7");   printf("N qfn=3 nqt=7: aceito=0\\n"); }}
    // nthr que muda min(): nthr=16 => nqt deve ser 16; nqt=8 fica INCONSISTENTE
    {{ r48_st s = base(); s.r50_n_threads = 16; s.r50_n_quant_tasks = 8;  REQ(!gate(s), "N-nthr16-nqt8"); printf("N nthr=16 nqt=8 (exige 16): aceito=0\\n"); }}
    {{ r48_st s = base(); s.r50_n_threads = 16; s.r50_n_quant_tasks = 16; REQ(gate(s), "P-nthr16-nqt16"); printf("P nthr=16 nqt=16: aceito=1\\n"); }}
    // nthr fora de faixa
    {{ r48_st s = base(); s.r50_n_threads = 0; REQ(!gate(s), "N-nthr0"); printf("N nthr=0: aceito=0\\n"); }}
    {{ r48_st s = base(); s.r50_n_threads = 17; REQ(!gate(s), "N-nthr17"); printf("N nthr=17: aceito=0\\n"); }}
    // total_nb errado
    {{ r48_st s = base(); s.r50_total_nb = 47; REQ(!gate(s), "N-tnb47"); printf("N total_nb=47: aceito=0\\n"); }}
    // shape/rows/entered ainda rejeitam
    {{ r48_st s = base(); s.rows_done = 2; REQ(!gate(s), "N-rows2"); }}
    {{ r48_st s = base(); s.r49_op_entered = 0; REQ(!gate(s), "N-entered0"); }}
    {{ r48_st s = base(); s.r49_n_hmx = 1; REQ(!gate(s), "N-hmx1"); }}
    printf("VAC_CONSUMER falhas=%d %s\\n", falhas, falhas ? "RED" : "GREEN");
    return falhas ? 1 : 0;
}}
'''
open("C:/llama-npu/rodada2/r51_consumer_v51.cpp", "w", encoding="utf-8", newline="\n").write(consumer)
print("consumer v51 gerado; predicate extraido com", len(pred), "chars")

# ================= SKEL: bloco de captura literal =================
s = open(SKEL, encoding="utf-8", errors="replace").read()
m = re.search(r"    if \(r50_wq_ok && octx->act_dump_ptr && mmctx->quant_task_func && mmctx->n_quant_tasks > 0 &&.*?\n    \}", s, re.S)
assert m, "bloco de captura nao encontrado no skel!"
bloco = m.group(0)
bloco = "\n".join("    " + l.strip() for l in bloco.splitlines())
skel_txt = f'''#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
// ---- bloco de captura LITERAL do cohort (matmul-ops.c, pos-R50) com stubs ----
typedef struct {{ uint8_t * act_dump_ptr; size_t act_dump_cap; }} Octx;
typedef struct {{ void * quant_task_func; unsigned n_quant_tasks; uint8_t * vtcm_src1; size_t vtcm_src1_stride; Octx * octx; }} Mmctx;
static uint32_t g_rows = 0;   /* h[3] simulado (atomic) */
static uint8_t g_buf[262144+4096];
static uint8_t g_src[262144+4096];
static int falhas = 0;
#define REQ(cond, msg) do {{ if (!(cond)) {{ printf("VAC_FAIL: %s\\n", msg); falhas++; }} }} while (0)
static bool rodar(bool r50_wq_ok, Octx * octx, Mmctx * mmctx, unsigned src1_nrows) {{
    g_rows = 0;
{bloco}
    return true;
}}
int main() {{
    Octx octx; Mmctx mm; memset(&octx, 0, sizeof(octx)); memset(&mm, 0, sizeof(mm));
    octx.act_dump_ptr = g_buf; octx.act_dump_cap = 262144; mm.octx = &octx;
    mm.quant_task_func = (void*) 0x1234; mm.n_quant_tasks = 8; mm.vtcm_src1 = g_src; mm.vtcm_src1_stride = 221184;
    for (size_t i = 0; i < 221184; i++) g_src[i] = (uint8_t)(i * 7);
    // P1: captura OK (B1)
    rodar(true, &octx, &mm, 1);
    REQ(g_rows == 1, "P1 rows=1");
    REQ(memcmp(g_buf + 64, g_src, 221184) == 0, "P1 bytes copiados");
    printf("P1 cap OK: rows=%u copy=ok\\n", g_rows);
    // NEGATIVOS (2.4):
    rodar(false, &octx, &mm, 1); REQ(g_rows == 0, "N-runner-false"); printf("N wq_ok=false: rows=0\\n");
    {{ Mmctx m2 = mm; m2.quant_task_func = NULL; rodar(true, &octx, &m2, 1); REQ(g_rows == 0, "N-noquant"); printf("N quant_task_func=NULL: rows=0\\n"); }}
    {{ Mmctx m2 = mm; m2.n_quant_tasks = 0; rodar(true, &octx, &m2, 1); REQ(g_rows == 0, "N-nqt0"); printf("N n_quant_tasks=0: rows=0\\n"); }}
    {{ Mmctx m2 = mm; m2.vtcm_src1 = NULL; rodar(true, &octx, &m2, 1); REQ(g_rows == 0, "N-src-null"); printf("N vtcm_src1=NULL: rows=0\\n"); }}
    {{ Mmctx m2 = mm; m2.vtcm_src1_stride = 0; rodar(true, &octx, &m2, 1); REQ(g_rows == 0, "N-stride0"); printf("N stride=0: rows=0\\n"); }}
    {{ Octx o2 = octx; o2.act_dump_ptr = NULL; rodar(true, &o2, &mm, 1); REQ(g_rows == 0, "N-ptr-null"); printf("N act_dump_ptr=NULL: rows=0\\n"); }}
    {{ Octx o2 = octx; o2.act_dump_cap = 63; rodar(true, &o2, &mm, 1); REQ(g_rows == 0, "N-cap63"); printf("N cap=63: rows=0\\n"); }}
    {{ Mmctx m2 = mm; m2.vtcm_src1_stride = 262144 - 63; rodar(true, &octx, &m2, 1); REQ(g_rows == 0, "N-row>cap-64"); printf("N row=cap-63: rows=0\\n"); }}
    {{ rodar(true, &octx, &mm, 0); REQ(g_rows == 0, "N-nrows0"); printf("N nrows=0: rows=0\\n"); }}
    // OVERSIZE: nrows*row > cap-64 => recusa (ex.: nrows=2 com row=221184 => 442368 > 262080)
    {{ rodar(true, &octx, &mm, 2); REQ(g_rows == 0, "N-oversize"); printf("N nrows=2 overflow: rows=0\\n"); }}
    // POSITIVO 2: nrows=1 stride pequeno ok (row=2048, cap grande)
    {{ Mmctx m2 = mm; m2.vtcm_src1_stride = 2048; rodar(true, &octx, &m2, 1); REQ(g_rows == 1, "P2-row2048"); printf("P2 row=2048 nrows=1: rows=1\\n"); }}
    printf("VAC_SKEL falhas=%d %s\\n", falhas, falhas ? "RED" : "GREEN");
    return falhas ? 1 : 0;
}}
'''
open("C:/llama-npu/rodada2/r51_skel_capture.cpp", "w", encoding="utf-8", newline="\n").write(skel_txt)
print("skel capture v51 gerado; bloco:", len(bloco), "chars")
