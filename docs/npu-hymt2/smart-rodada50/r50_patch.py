#!/usr/bin/env python3
# r50_patch.py — R50: OBSERVADOR CORRIGIDO + CAPTURA POS-BARREIRA + TRACE DSP
# ============================================================================
# A) HOST (ggml-hexagon.cpp):
#    A1 struct v3: + r50_* (observabilidade em PENDING + trace DSP!)
#    A2 version=3; A3 reset dos campos novos
#    A4 cap-check ANTES de qualquer load do header (o parecer R49/D!)
#    A5 METADATA VISIVEL antes dos returns de pending (op_seen/rows!) +
#       firstcause + CAPTURED exige o entered-magic do op_matmul
# B) SKEL (htp/matmul-ops.c):
#    B1 REMOVE a copia por-task do macro QUANTIZE_IMPL (R46-R49: o worker
#       BLOCK nunca a executava — o parecer R49/E!)
#    B2 helper r50_quant_func_id (o quantizador EFETIVO, nao o numero do enum!)
#    B3 TRACE no ponto da decisao: h[8..14] (src1_nrows/n_threads/need_quant/
#       n_quant_tasks/quant_func_id/decoded-magic/total_nb — o parecer R49/B!)
#    B4 CAPTURA UNICA POS-BARREIRA: apos worker_pool_run_func OK + quant real
#       executada (quant_task_func!=NULL), copia mmctx->vtcm_src1 com stride
#       real + bounds de ORIGEM e destino (cobre BLOCK E ROW!)
# Nada de assert: if/raise (o parecer R49/F.2: -O NAO pode mascarar ancora!)
import sys, os

HEX = sys.argv[1] if len(sys.argv) > 1 else None
if not HEX or not os.path.isdir(HEX):
    print(f"R50_ANCHOR_FAIL: HEX invalido: {HEX!r} (uso: r50_patch.py <hexagon-dir>)"); sys.exit(1)
HOST = f"{HEX}/ggml-hexagon.cpp"
SKEL = f"{HEX}/htp/matmul-ops.c"
for p in (HOST, SKEL):
    if not os.path.isfile(p):
        print(f"R50_ANCHOR_FAIL: fonte ausente: {p}"); sys.exit(1)

def need(cond, msg):
    if not cond:
        print(f"R50_ANCHOR_FAIL: {msg}"); sys.exit(1)

def rep1(t, old, new, tag):
    c = t.count(old)
    need(c == 1, f"{tag}: ancora com count={c} (exige 1!)")
    return t.replace(old, new, 1)

# ============================================================
# A) HOST
# ============================================================
t = open(HOST, encoding="utf-8", errors="replace").read()

# A1: struct v3
old = """    int32_t  r49_op_entered;  // 1 = o op_matmul LEU os kparams desta op!
    int32_t  r49_reserved;
};"""
new = """    int32_t  r49_op_entered;  // 1 = o op_matmul LEU os kparams desta op!
    int32_t  r49_reserved;
    // R50 (version 3!): observabilidade em PENDING + o TRACE do quantizador DSP
    int32_t  r50_meta_valid;    // 1 = header com magic/epoch OK (metadata VISIVEL mesmo em pending!)
    int32_t  r50_firstcause;    // 0=none; 1=op_seen0; 2=rows0; 3=B!=1; 4=rows!=B; 5=row0; 6=cap/row; 7=sem entered
    uint32_t r50_src1_nrows;    // h[8]  (DSP!)
    uint32_t r50_n_threads;     // h[9]  (DSP!)
    uint32_t r50_need_quant;    // h[10] (DSP!)
    uint32_t r50_n_quant_tasks; // h[11] (DSP!)
    uint32_t r50_quant_func_id; // h[12] (DSP! 0=null 1=q8_0_tiled 2=q8_1_tiled 3=q8_0_tiled_block 4=q8_1_tiled_block 5=q8_0_flat 6=q8_1_flat 7=f32 8=f16 9=f16f16)
    uint32_t r50_dsp_decoded;   // 1 = h[13]==0xA5A50002 (o DSP DECODIFICOU a decisao!)
    uint32_t r50_total_nb;      // h[14] (DSP! total de tiles da ACT — o coverage!)
    uint32_t r50_reserved;
};"""
t = rep1(t, old, new, "A1-struct")

# A2: version 3
t = rep1(t,
    "    out->magic = R46_MAGIC; out->version = 2;   // R49: + metadata do ramo!",
    "    out->magic = R46_MAGIC; out->version = 3;   // R50: + observabilidade em PENDING + trace DSP!",
    "A2-version")

# A3: reset dos campos novos
t = rep1(t,
    "    out->r49_n_hmx = -1; out->r49_kernel_type = -1; out->r49_op_entered = 0; out->r49_reserved = 0;",
    """    out->r49_n_hmx = -1; out->r49_kernel_type = -1; out->r49_op_entered = 0; out->r49_reserved = 0;
    out->r50_meta_valid = 0; out->r50_firstcause = 0;
    out->r50_src1_nrows = 0; out->r50_n_threads = 0; out->r50_need_quant = 0;
    out->r50_n_quant_tasks = 0; out->r50_quant_func_id = 0; out->r50_dsp_decoded = 0;
    out->r50_total_nb = 0; out->r50_reserved = 0;""",
    "A3-reset")

# A4+A5: o corpo pos-meta: cap-check + metadata ANTES dos gates
old = """    const uint32_t * hdr = (const uint32_t *) g_r46_act_dump_sbuf->base;
    if (hdr[0] != R46_MAGIC || hdr[1] != g_r46_meta.epoch) { out->status = -1; return 0; }   // INVALIDO (o header!)
    out->rows_done = hdr[3];
    // R47: VALIDACAO FORMAL antes de CAPTURED (nada de >=B/row0/cap64 passando!)
    const int op_seen = hdr[2] == 1;
    if (!op_seen) { out->status = 1; return 0; }                       // a op nao chegou!
    if (hdr[3] == 0) { out->status = 1; return 0; }                    // copiou 0 rows (nao capturou!)
    if (g_r46_meta.B != 1) { out->status = -1; return 0; }             // ALVO e' B1!
    if ((int) hdr[3] != g_r46_meta.B) { out->status = -1; return 0; }  // rows EXATO (excesso = INVALID!)
    if (g_r46_meta.row_size == 0) { out->status = -1; return 0; }      // row 0 = INVALID!
    if (g_r46_meta.row_size > out->capacity || out->payload_off + g_r46_meta.row_size > out->capacity) {
        out->status = -1; return 0;                                    // 64+row <= cap (fim!)
    }
    out->status = 2;   // CAPTURED (tudo validado!)
    // R49: a metadata do ramo (lida do header!) — exposta SEMPRE (observabilidade!)
    out->r49_n_hmx = (int32_t) hdr[4];
    out->r49_kernel_type = (int32_t) hdr[5];
    out->r49_op_entered = (hdr[7] == 0xA5A50001u) ? 1 : 0;
    return 0;"""
new = """    // R50: CAPACIDADE conferida ANTES de qualquer load do header (parecer R49/D!)
    if (g_r46_act_dump_sbuf->base == nullptr || g_r46_act_dump_sbuf->size < 64) { out->status = -1; return 0; }
    const uint32_t * hdr = (const uint32_t *) g_r46_act_dump_sbuf->base;
    if (hdr[0] != R46_MAGIC || hdr[1] != g_r46_meta.epoch) { out->status = -1; return 0; }   // INVALIDO (o header!)
    out->rows_done = hdr[3];
    // R50: A METADATA SAI AQUI (header valido!) — a observacao acontece MESMO em PENDING
    // (o parecer R49/D: "-1/-1/0 eram os DEFAULTS do GETTER, nao evidencia do SKEL!")
    out->r49_n_hmx = (int32_t) hdr[4];
    out->r49_kernel_type = (int32_t) hdr[5];
    out->r49_op_entered = (hdr[7] == 0xA5A50001u) ? 1 : 0;
    out->r50_meta_valid = 1;
    out->r50_src1_nrows = hdr[8];
    out->r50_n_threads = hdr[9];
    out->r50_need_quant = hdr[10];
    out->r50_n_quant_tasks = hdr[11];
    out->r50_quant_func_id = hdr[12];
    out->r50_dsp_decoded = (hdr[13] == 0xA5A50002u) ? 1 : 0;
    out->r50_total_nb = hdr[14];
    // R47/R50: VALIDACAO FORMAL antes de CAPTURED (nada de >=B/row0/cap64 passando!)
    const int op_seen = hdr[2] == 1;
    if (!op_seen) { out->status = 1; out->r50_firstcause = 1; return 0; }                       // op_seen0 (pending!)
    if (hdr[3] == 0) { out->status = 1; out->r50_firstcause = 2; return 0; }                    // rows0 (pending!)
    if (g_r46_meta.B != 1) { out->status = -1; out->r50_firstcause = 3; return 0; }             // ALVO e' B1!
    if ((int) hdr[3] != g_r46_meta.B) { out->status = -1; out->r50_firstcause = 4; return 0; }  // rows EXATO!
    if (g_r46_meta.row_size == 0) { out->status = -1; out->r50_firstcause = 5; return 0; }      // row 0!
    if (g_r46_meta.row_size > out->capacity || out->payload_off + g_r46_meta.row_size > out->capacity) {
        out->status = -1; out->r50_firstcause = 6; return 0;                                    // 64+row <= cap!
    }
    if (hdr[7] != 0xA5A50001u) { out->status = -1; out->r50_firstcause = 7; return 0; }         // R50: CAPTURED exige o op_matmul!
    out->status = 2;   // CAPTURED (tudo validado!)
    return 0;"""
t = rep1(t, old, new, "A4A5-getter")

open(HOST, "w", encoding="utf-8", newline="\n").write(t)
print("A) HOST R50 ok (struct v3 + getter com metadata em PENDING + firstcause!)")

# ============================================================
# B) SKEL
# ============================================================
import re
t2 = open(SKEL, encoding="utf-8", errors="replace").read()

# B1: remover a copia por-task do macro (regex, non-greedy ate o trace_stop do macro)
pat_b1 = re.compile(
    r"    kernel_fn\(src_data, dst_data, tmp_data, ne0, ir_last - ir_first, src_row_size, dst_row_size\);.*?"
    r"htp_trace_event_stop\(tr, HTP_TRACE_EVT_HVX_A_QUANT, ir_first\);", re.S)
ms = pat_b1.findall(t2)
need(len(ms) == 1, f"B1-macro: matches={len(ms)} (exige 1!)")
new_b1 = (
"    kernel_fn(src_data, dst_data, tmp_data, ne0, ir_last - ir_first, src_row_size, dst_row_size);          \\\n"
"    /* R50: a copia por-task (R46-R49) foi REMOVIDA — o worker BLOCK nunca a chamava!  */                \\\n"
"    /* A captura UNICA agora e' POS-BARREIRA em hvx_mm_matmul (cobre BLOCK E ROW!)     */                \\\n"
"                                                                                                           \\\n"
"    htp_trace_event_stop(tr, HTP_TRACE_EVT_HVX_A_QUANT, ir_first);")
t2 = pat_b1.sub(new_b1, t2, count=1)

# B2: helper r50_quant_func_id (antes de hvx_mm_matmul)
old = "static int hvx_mm_matmul(struct htp_ops_context * octx) {"
new = """// R50: o ID do quantizador EFETIVO (parecer R49/E: nao escolher pelo numero do enum!)
static uint32_t r50_quant_func_id(worker_callback_t f) {
    if (f == quantize_f32_q8_0_tiled)       return 1;
    if (f == quantize_f32_q8_1_tiled)       return 2;
    if (f == quantize_f32_q8_0_tiled_block) return 3;
    if (f == quantize_f32_q8_1_tiled_block) return 4;
    if (f == quantize_f32_q8_0_flat)        return 5;
    if (f == quantize_f32_q8_1_flat)        return 6;
    if (f == quantize_f32_f32_flat)         return 7;
    if (f == quantize_f32_f16_flat)         return 8;
    if (f == quantize_f16_f16_flat)         return 9;
    return 0;
}

static int hvx_mm_matmul(struct htp_ops_context * octx) {"""
t2 = rep1(t2, old, new, "B2-helper")

# B3: trace no ponto da decisao (antes do if (need_quant))
old = """    mmctx->vtcm_src0_stride = src0_row_size_padded;
    mmctx->vtcm_src1_stride = src1_row_size;

    if (need_quant) {"""
new = """    mmctx->vtcm_src0_stride = src0_row_size_padded;
    mmctx->vtcm_src1_stride = src1_row_size;

    // R50: TRACE MINIMO DO QUANTIZADOR (o parecer R49/B/E!) — o DSP DECODIFICOU:
    // campos reais no header (h[8..14]) ANTES de qualquer copia de payload.
    if (octx->act_dump_ptr && octx->act_dump_cap >= 64) {
        uint32_t * h = (uint32_t *) octx->act_dump_ptr;
        h[8]  = src1_nrows;
        h[9]  = octx->n_threads;
        h[10] = need_quant ? 1u : 0u;
        h[11] = n_quant_tasks;
        h[12] = r50_quant_func_id(quant_task_func);
        h[13] = 0xA5A50002u;   // R50: o DSP decodificou a decisao!
        h[14] = src1_nrows * ((ne10 + QK_Q8_0_TILED - 1) / QK_Q8_0_TILED);   // total_nb (coverage!)
    }

    if (need_quant) {"""
t2 = rep1(t2, old, new, "B3-trace")

# B4: captura unica pos-barreira
old = """    worker_pool_run_func(octx->ctx->worker_pool, matmul_job_func, mmctx, octx->n_threads);

    return HTP_STATUS_OK;
}"""
new = """    const bool r50_wq_ok = worker_pool_run_func(octx->ctx->worker_pool, matmul_job_func, mmctx, octx->n_threads);

    // R50: A CAPTURA UNICA POS-BARREIRA (o parecer R49/E.5!) — cobre BLOCK E ROW:
    // so' com a quantizacao REAL executada (quant_task_func!=NULL), runner OK e
    // barreiras completas (o work_queue_run espera a task barrier!) — e a VTCM
    // ainda nao foi reusada/liberada. Bounds da ORIGEM e do destino ANTES do memcpy.
    if (r50_wq_ok && octx->act_dump_ptr && mmctx->quant_task_func && mmctx->n_quant_tasks > 0 &&
        mmctx->vtcm_src1 && mmctx->vtcm_src1_stride > 0) {
        const size_t r50_row   = mmctx->vtcm_src1_stride;
        const size_t r50_cap   = octx->act_dump_cap;
        const size_t r50_nrows = (size_t) src1_nrows;
        if (r50_cap >= 64 && r50_row <= r50_cap - 64 && r50_nrows > 0 &&
            r50_nrows <= (r50_cap - 64) / r50_row) {
            memcpy(octx->act_dump_ptr + 64, mmctx->vtcm_src1, r50_row * r50_nrows);
            atomic_fetch_add((atomic_uint *) &((uint32_t *) octx->act_dump_ptr)[3], (uint32_t) r50_nrows);
        }
    }

    return HTP_STATUS_OK;
}"""
t2 = rep1(t2, old, new, "B4-captura")

open(SKEL, "w", encoding="utf-8", newline="\n").write(t2)
print("B) SKEL R50 ok (macro limpo + helper + trace h[8..14] + captura pos-barreira!)")
print("=== PATCH R50 COMPLETO!")
