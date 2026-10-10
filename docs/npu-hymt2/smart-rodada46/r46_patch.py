#!/usr/bin/env python3
# r46_patch.py — R46: O PATCH V2 (corrigido contra as 7 contraprovas!)
# FIXES: bi=0 valido; bi POR BATCH (nao global!); selecao NA OP (Q6_K K6144/
# N2048/B1 F32!); atribuicao APOS o impl (o memset do impl apagava!); DSP
# resolve pelo DESCRITOR (bufs[bi].base, nao mmap!); bounds checados (cap!);
# limpeza central no dispatcher (execute_op!); flag init-only declarada!
import sys
HEX = sys.argv[1] if len(sys.argv) > 1 else "/root/sigcand2/ggml/src/ggml-hexagon"
HTP = f"{HEX}/htp"

# ============================================================
# A) HOST (ggml-hexagon.cpp): remover o R45 global+arm; FIX no add_op!
# ============================================================
p = f"{HEX}/ggml-hexagon.cpp"
t = open(p, encoding="utf-8", errors="replace").read()

# A.1 remover a linha P3 do R45 (o precompute wrapper — ela era APAGADA pelo memset!)
old = "    kparams->act_dump_bi = g_r45_act_dump_bi;   // R45: P3 (test-only: -1 = OFF!)\n    ggml_hexagon_precompute_matmul_params_impl(sess, src0, src1, dst, 0, kparams);"
assert t.count(old) == 1, "A1 anchor"
t = t.replace(old, "    ggml_hexagon_precompute_matmul_params_impl(sess, src0, src1, dst, 0, kparams);", 1)

# A.2 trocar o global do bi pelo OWNER do sbuf + a flag init-only!
old = """// R45 (test-only!): o bi global do dump da ACT (-1 = OFF!)
static int g_r45_act_dump_bi = -1;
"""
assert t.count(old) == 1, "A2 anchor"
t = t.replace(old, """// R46 (test-only!): o OWNER do sbuf do dump (uma vez por processo; o bi e' POR BATCH!)
static ggml_hexagon_shared_buffer * g_r46_act_dump_sbuf = nullptr;
static int g_r46_armed = -1;
static bool g_r46_act_dump_armed(void) {
    if (g_r46_armed < 0) {   // INIT-ONLY (declarado!): a primeira leitura decide!
        const char * e = getenv("GGML_HEXAGON_ACT_DUMP");
        g_r46_armed = (e && e[0] == '1' && e[1] == 0) ? 1 : 0;   // so' "1" (o resto OFF!)
    }
    return g_r46_armed == 1;
}
""", 1)

# A.3 remover o r45_act_dump_arm (a funcao R45 do batch!)
old = """    // R45 (test-only!): arma o dump UMA vez (P1: criacao; P2: add_buffer!)
    void r45_act_dump_arm(ggml_hexagon_session * sess) {
        if (g_r45_act_dump_bi >= 0) return;
        const char * e = getenv("GGML_HEXAGON_ACT_DUMP");
        if (!(e && e[0] == '1' && e[1] == 0)) return;   // parse explicito (so' "1"!)
        ggml_hexagon_shared_buffer * d = new ggml_hexagon_shared_buffer(sess, 262144);   // 256KB >= 221184!
        const int bi = add_buffer(d);
        if (bi > 0) {
            g_r45_act_dump_bi = bi;
            GGML_LOG_DEBUG("ggml-hex: R45 ACT_DUMP: bi=%d fd=%d size=%zu\\n", bi, d->fd, d->size);
        }
    }

"""
assert t.count(old) == 1, "A3 anchor"
t = t.replace(old, "", 1)

# A.4 remover a chamada no add_tensor (o R45!)
old = """        auto sbuf = static_cast<ggml_hexagon_shared_buffer *>(t->buffer->context);
        r45_act_dump_arm(sbuf->sess);   // R45: P2 (antes dos tensores — bi estavel!)
"""
assert t.count(old) == 1, "A4 anchor"
t = t.replace(old, """        auto sbuf = static_cast<ggml_hexagon_shared_buffer *>(t->buffer->context);
""", 1)

# A.5 o membro do BI POR BATCH (junto do reset!)
old = """    void reset() {
        n_bufs = 0;
        n_tens = 0;
        n_ops  = 0;
        b_vmem = 0;

        b_map.clear();
        t_map.clear();
        d_map.clear();
    }"""
assert t.count(old) == 1, "A5 anchor"
t = t.replace(old, """    int r46_dump_bi = -1;   // R46: o bi do dump NESTE batch (-1 = nenhum!)

    void reset() {
        n_bufs = 0;
        n_tens = 0;
        n_ops  = 0;
        b_vmem = 0;

        b_map.clear();
        t_map.clear();
        d_map.clear();

        r46_dump_bi = -1;   // R46: re-derivado a cada batch (sem stale!)
    }""", 1)

# A.6 O FIX CENTRAL: no add_op (apos o memcpy!) — a selecao POR OPERACAO + o wire!
old = """        htp_op_desc &o = h_ops[n];
        memcpy(o.params,        node.node->op_params, sizeof(node.node->op_params));
        memcpy(o.kernel_params, node.kernel_params,   sizeof(o.kernel_params));
        o.opcode = node.opcode;
        o.flags  = 0;
"""
assert t.count(old) == 1, "A6 anchor"
t = t.replace(old, """        htp_op_desc &o = h_ops[n];
        memcpy(o.params,        node.node->op_params, sizeof(node.node->op_params));
        memcpy(o.kernel_params, node.kernel_params,   sizeof(o.kernel_params));
        o.opcode = node.opcode;
        o.flags  = 0;

        // R46 (test-only!): o dump da ACT — selecao POR OPERACAO + bi DESTE batch!
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
                if (r46_dump_bi >= 0) {
                    k46->act_dump_bi = r46_dump_bi;
                    GGML_LOG_DEBUG("ggml-hex: R46 ACT_DUMP: batch bi=%d fd=%d\\n", r46_dump_bi, g_r46_act_dump_sbuf->fd);
                }
            }
        }
""", 1)
open(p, "w", encoding="utf-8", newline="\n").write(t)
print("A) HOST v2 ok (7 fixes: global->owner; por-batch; selecao; apos-memcpy; =0; init-only!)")

# ============================================================
# B) SKEL: htp-ctx.h (campos!), matmul-ops.c (descritor+bounds!), main.c (limpa!)
# ============================================================
p2 = f"{HTP}/htp-ctx.h"
t2 = open(p2, encoding="utf-8", errors="replace").read()
old = "    uint8_t * act_dump_ptr;   // R45 (test-only!): o dump da ACT Q6 (NULL = OFF!)"
assert t2.count(old) == 1, "B1 anchor"
t2 = t2.replace(old, """    uint8_t * act_dump_ptr;   // R46 (test-only!): o dump da ACT Q6 (NULL = OFF!)
    size_t    act_dump_cap;   // R46: a CAPACIDADE do buffer (bounds!)""", 1)
# + os bufs do request (a resolucao OFICIAL!)
old = "    uint8_t *              vtcm_base;"
assert t2.count(old) == 1, "B2 anchor"
t2 = t2.replace(old, """    const struct htp_buf_desc * r46_bufs;   // R46: os descs do request (resolvidos!)
    uint32_t                    r46_nbufs;

    uint8_t *              vtcm_base;""", 1)
open(p2, "w", encoding="utf-8", newline="\n").write(t2)
print("B1/B2) skel ctx ok")

p3 = f"{HTP}/matmul-ops.c"
t3 = open(p3, encoding="utf-8", errors="replace").read()
# B3: o op_matmul — resolver pelo DESCRITOR + bounds + !n_hmx!
old = """    // R45 (test-only!): P4 — o dump da ACT: bi -> endereco DSP (o fd ja' mapeado!)
    if (kparams->act_dump_bi >= 0 && kparams->act_dump_bi < HTP_MAX_MMAPS && octx->ctx->mmap[kparams->act_dump_bi].size) {
        octx->act_dump_ptr = (uint8_t *) (uintptr_t) octx->ctx->mmap[kparams->act_dump_bi].base;
    } else {
        octx->act_dump_ptr = NULL;
    }
"""
assert t3.count(old) == 1, "B3 anchor"
t3 = t3.replace(old, """    // R46 (test-only!): o dump da ACT — resolvido pelo DESCRITOR do request
    // (bufs[bi].base do prep_op_bufs — NAO ctx->mmap[bi]! numeracoes DIFERENTES!)
    // + bounds + somente o caminho HVX (o HMX desvia e nao usa!)
    octx->act_dump_ptr = NULL;
    octx->act_dump_cap = 0;
    if (kparams->act_dump_bi >= 0 && kparams->act_dump_bi < (int) octx->ctx->r46_nbufs && octx->ctx->r46_bufs && !kparams->n_hmx) {
        octx->act_dump_ptr = (uint8_t *) (uintptr_t) octx->ctx->r46_bufs[kparams->act_dump_bi].base;
        octx->act_dump_cap = (size_t) octx->ctx->r46_bufs[kparams->act_dump_bi].size;
    }
""", 1)
# B4: o worker — bounds checados (fim <= cap!)
old = """    if (mmctx->octx->act_dump_ptr) {                                                                       \\
        uint8_t * d45 = mmctx->octx->act_dump_ptr + ((size_t) dst_row_size * ir_first);                    \\
        memcpy(d45, dst_data, (size_t) dst_row_size * (ir_last - ir_first));                               \\
    }                                                                                                      \\"""
assert t3.count(old) == 1, "B4 anchor"
t3 = t3.replace(old, """    if (mmctx->octx->act_dump_ptr) {                                                                       \\
        const size_t d46_off = (size_t) dst_row_size * ir_first;                                           \\
        const size_t d46_len = (size_t) dst_row_size * (ir_last - ir_first);                               \\
        if (d46_off <= mmctx->octx->act_dump_cap && d46_len <= mmctx->octx->act_dump_cap - d46_off) {      \\
            memcpy(mmctx->octx->act_dump_ptr + d46_off, dst_data, d46_len);                                \\
        }                                                                                                  \\
    }                                                                                                      \\""", 1)
open(p3, "w", encoding="utf-8", newline="\n").write(t3)
print("B3/B4) skel matmul ok")

# B5: main.c — os bufs no ctx (apos o prep!) + limpeza central no execute_op!
p4 = f"{HTP}/main.c"
t4 = open(p4, encoding="utf-8", errors="replace").read()
old = "    prep_op_bufs(ctx, bufs, n_bufs);"
assert t4.count(old) == 1, "B5 anchor"
t4 = t4.replace(old, """    prep_op_bufs(ctx, bufs, n_bufs);
    ctx->r46_bufs  = bufs;     // R46 (test-only!): os descs RESOLVIDOS do request!
    ctx->r46_nbufs = n_bufs;""", 1)
old = """static int execute_op(struct htp_ops_context * octx) {
    switch (octx->op) {"""
assert t4.count(old) == 1, "B6 anchor"
t4 = t4.replace(old, """static int execute_op(struct htp_ops_context * octx) {
    octx->act_dump_ptr = NULL;   // R46: limpeza CENTRAL entre ops (sem stale!)
    octx->act_dump_cap = 0;
    switch (octx->op) {""", 1)
open(p4, "w", encoding="utf-8", newline="\n").write(t4)
print("B5/B6) skel main ok")
print("=== PATCH V2 COMPLETO (todos os 7 fixes + extras!)")
