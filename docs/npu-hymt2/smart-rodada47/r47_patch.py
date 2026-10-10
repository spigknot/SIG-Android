#!/usr/bin/env python3
# r47_patch.py — R47: O CONTRATO MÍNIMO B1 (os fixes do parecer final!)
# A) HOST: default OFF nos 5 irmãos (MUL_MAT_ID/QKV/FFN/ADD!); seleção estrita
#    B1 2D (ne2/ne3==1!); owner recusa 2ª sessão; getter ESTRITO (rows==B
#    exato! row>0! 64+row<=cap! senão INVALID!); handshake (out_size!).
# B) SKEL: checked multiply (divisão ANTES dos produtos — sem wrap!)
import sys
HEX = sys.argv[1] if len(sys.argv) > 1 else "/root/sigcand2/ggml/src/ggml-hexagon"
HTP = f"{HEX}/htp"

# ============================================================
# A) HOST
# ============================================================
p = f"{HEX}/ggml-hexagon.cpp"
t = open(p, encoding="utf-8", errors="replace").read()

# A.1 o add_op: irmãos default OFF + seleção estrita + owner + ne2/ne3!
old = """        // R46 (test-only!): o dump da ACT — selecao POR OPERACAO + bi DESTE batch!
        // (DEPOIS do memcpy — o memset do precompute nao o alcanca mais!)
        if (o.opcode == HTP_OP_MUL_MAT) {
            struct htp_mm_kernel_params * k46 = (struct htp_mm_kernel_params *) o.kernel_params;
            k46->act_dump_bi = -1;   // OFF por default (sentinela!)
            const ggml_tensor * s0 = node.node->src[0];
            const ggml_tensor * s1 = node.node->src[1];
            if (s0 && s1 && g_r46_act_dump_armed() &&
                s0->type == GGML_TYPE_Q6_K && s0->ne[0] == 6144 && s0->ne[1] == 2048 &&
                s1->type == GGML_TYPE_F32 && s1->ne[1] == 1) {
                if (!g_r46_act_dump_sbuf) {
                    g_r46_act_dump_sbuf = new ggml_hexagon_shared_buffer(sess, 262144);   // OWNER (processo!)
                }
                if (r46_dump_bi < 0) {
                    r46_dump_bi = add_buffer(g_r46_act_dump_sbuf);   // bi DESTE batch (0 e' valido!)
                }
                if (r46_dump_bi >= 0 && !g_r46_meta.used) {   // UMA captura (desarma!)
                    g_r46_meta.used = 1;
                    g_r46_meta.epoch++;
                    g_r46_meta.K = (int) s0->ne[0]; g_r46_meta.N = (int) s0->ne[1]; g_r46_meta.B = (int) s1->ne[1];
                    g_r46_meta.type = (int) s0->type;
                    g_r46_meta.row_size = (size_t) (uint32_t) k46->src1_row_size;   // da op (o precompute!)
                    // O HEADER (16 B!): magic/epoch/op_seen=0/rows_done=0 — o host prepara
                    // ANTES do enqueue (a shared mem: o DSP vai setar o op_seen/rows_done!)
                    uint32_t * hdr = (uint32_t *) g_r46_act_dump_sbuf->base;
                    hdr[0] = R46_MAGIC; hdr[1] = g_r46_meta.epoch; hdr[2] = 0; hdr[3] = 0;
                    k46->act_dump_bi = r46_dump_bi;
                    GGML_LOG_DEBUG("ggml-hex: R46 ACT_DUMP: epoch=%u bi=%d K=%d N=%d B=%d row=%zu\\n",
                                   g_r46_meta.epoch, r46_dump_bi, g_r46_meta.K, g_r46_meta.N, g_r46_meta.B, g_r46_meta.row_size);
                } else {
                    k46->act_dump_bi = -1;   // ja capturou (as demais nao sobrescrevem!)
                }
            }
        }
"""
assert t.count(old) == 1, "A1 anchor"
t = t.replace(old, """        // R47 (test-only!): o dump da ACT — default OFF em TODOS os irmãos do
        // mm_kernel_params (MUL_MAT/ID/QKV/FFN/ADD!) DEPOIS do memcpy!
        const bool r47_mm_family = (o.opcode == HTP_OP_MUL_MAT || o.opcode == HTP_OP_MUL_MAT_ID ||
                                    o.opcode == HTP_OP_MUL_MAT_QKV || o.opcode == HTP_OP_MUL_MAT_FFN ||
                                    o.opcode == HTP_OP_MUL_MAT_ADD);
        if (r47_mm_family) {
            struct htp_mm_kernel_params * k46 = (struct htp_mm_kernel_params *) o.kernel_params;
            k46->act_dump_bi = -1;   // OFF por default (TODOS os irmãos!)
            if (o.opcode == HTP_OP_MUL_MAT) {   // a selecao SO' no MUL_MAT!
                const ggml_tensor * s0 = node.node->src[0];
                const ggml_tensor * s1 = node.node->src[1];
                // R47: B1 ESTrito 2D (ne2/ne3==1 em AMBOS!) + owner de UMA sessao!
                const bool r47_owner_ok = (!g_r46_act_dump_sbuf || g_r46_act_dump_sbuf->sess == sess);
                if (s0 && s1 && g_r46_act_dump_armed() && r47_owner_ok &&
                    s0->type == GGML_TYPE_Q6_K && s0->ne[0] == 6144 && s0->ne[1] == 2048 &&
                    s0->ne[2] == 1 && s0->ne[3] == 1 &&
                    s1->type == GGML_TYPE_F32 && s1->ne[1] == 1 && s1->ne[2] == 1 && s1->ne[3] == 1) {
                    if (!g_r46_act_dump_sbuf) {
                        g_r46_act_dump_sbuf = new ggml_hexagon_shared_buffer(sess, 262144);   // OWNER (processo!)
                    }
                    if (r46_dump_bi < 0) {
                        r46_dump_bi = add_buffer(g_r46_act_dump_sbuf);   // bi DESTE batch (0 e' valido!)
                    }
                    if (r46_dump_bi >= 0 && !g_r46_meta.used) {   // UMA captura (desarma!)
                        g_r46_meta.used = 1;
                        g_r46_meta.epoch++;
                        g_r46_meta.K = (int) s0->ne[0]; g_r46_meta.N = (int) s0->ne[1]; g_r46_meta.B = (int) s1->ne[1];
                        g_r46_meta.type = (int) s0->type;
                        g_r46_meta.row_size = (size_t) (uint32_t) k46->src1_row_size;   // da op (o precompute!)
                        uint32_t * hdr = (uint32_t *) g_r46_act_dump_sbuf->base;
                        hdr[0] = R46_MAGIC; hdr[1] = g_r46_meta.epoch; hdr[2] = 0; hdr[3] = 0;
                        k46->act_dump_bi = r46_dump_bi;
                        GGML_LOG_DEBUG("ggml-hex: R47 ACT_DUMP: epoch=%u bi=%d K=%d N=%d B=%d row=%zu\\n",
                                       g_r46_meta.epoch, r46_dump_bi, g_r46_meta.K, g_r46_meta.N, g_r46_meta.B, g_r46_meta.row_size);
                    }
                }
            }
        }
""", 1)

# A.2 o getter ESTRITO + handshake (out_size!)
old = """static int ggml_hexagon_get_act_dump(void * out_v) {
    struct ggml_hexagon_act_dump_status * out = (struct ggml_hexagon_act_dump_status *) out_v;
    if (!out) return -1;"""
assert t.count(old) == 1, "A2 anchor"
t = t.replace(old, """static int ggml_hexagon_get_act_dump(void * out_v, size_t out_size) {
    // R47: HANDSHAKE — recusa struct copiado menor (ABI às cegas morre aqui!)
    if (!out_v || out_size < sizeof(struct ggml_hexagon_act_dump_status)) return -2;
    struct ggml_hexagon_act_dump_status * out = (struct ggml_hexagon_act_dump_status *) out_v;""", 1)

old = """    out->rows_done = hdr[3];
    const int op_seen = hdr[2] == 1;
    out->status = (op_seen && (int) hdr[3] >= g_r46_meta.B && g_r46_meta.B > 0) ? 2 : 1;
    return 0;
}"""
assert t.count(old) == 1, "A3 anchor"
t = t.replace(old, """    const uint32_t * hdr = (const uint32_t *) g_r46_act_dump_sbuf->base;
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
    return 0;
}""", 1)
open(p, "w", encoding="utf-8", newline="\n").write(t)
print("A) HOST R47 ok (irmãos+estrito+owner+handshake!)")

# ============================================================
# B) SKEL: checked multiply (divisão ANTES dos produtos!)
# ============================================================
p2 = f"{HTP}/matmul-ops.c"
t2 = open(p2, encoding="utf-8", errors="replace").read()
old = """    if (mmctx->octx->act_dump_ptr) {                                                                       \\
        const size_t d46_off = 64 + (size_t) dst_row_size * ir_first;    /* OFF 64 (o header!) */          \\
        const size_t d46_len = (size_t) dst_row_size * (ir_last - ir_first);                               \\
        if (d46_off <= mmctx->octx->act_dump_cap && d46_len <= mmctx->octx->act_dump_cap - d46_off) {      \\
            memcpy(mmctx->octx->act_dump_ptr + d46_off, dst_data, d46_len);                                \\
            atomic_fetch_add((atomic_uint *) &((uint32_t *) mmctx->octx->act_dump_ptr)[3], 1u);            \\
        }                                                                                                  \\
    }                                                                                                      \\"""
assert t2.count(old) == 1, "B1 anchor"
t2 = t2.replace(old, """    if (mmctx->octx->act_dump_ptr) {                                                                       \\
        /* R47: CHECKED — divisao ANTES dos produtos (sem wrap!) e todos os                  */          \\
        /* limites validados: row>0, cap>=64, row<=cap-64, last<=max_rows!                   */          \\
        const size_t d47_row = dst_row_size;                                                               \\
        const size_t d47_cap = mmctx->octx->act_dump_cap;                                                  \\
        if (d47_row > 0 && d47_cap >= 64 && d47_row <= d47_cap - 64 &&                                     \\
            ir_first >= 0 && ir_last > ir_first &&                                                         \\
            (size_t) ir_last <= (d47_cap - 64) / d47_row) {                                                \\
            const size_t d47_off = 64 + d47_row * (size_t) ir_first;   /* provado <= cap! */               \\
            const size_t d47_len = d47_row * (size_t) (ir_last - ir_first);                                \\
            memcpy(mmctx->octx->act_dump_ptr + d47_off, dst_data, d47_len);                                \\
            atomic_fetch_add((atomic_uint *) &((uint32_t *) mmctx->octx->act_dump_ptr)[3], 1u);            \\
        }                                                                                                  \\
    }                                                                                                      \\""", 1)
open(p2, "w", encoding="utf-8", newline="\n").write(t2)
print("B) SKEL R47 ok (checked multiply!)")
print("=== PATCH R47 COMPLETO!")
