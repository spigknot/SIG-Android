#!/usr/bin/env python3
# r49_patch.py — R49: A METADATA RUNTIME DO RAMO (sem cópia de ACT!)
# HOST: log dos campos do blob na op selecionada (n_hmx/kernel_type/pipeline!).
# SKEL: no op_matmul, ANTES do if(n_hmx): escreve a metadata no header do dump
# (h[4]=n_hmx, h[5]=kernel_type, h[6]=bi_wire, h[7]=magic) — SEMPRE que armado;
# a CÓPIA do worker fica condicionada a h[4]==0 (o ramo HVX!) — a observabilidade
# do ramo deixa de ser escondida pelo !n_hmx (o parecer R48/H!).
# GETTER: expõe n_hmx/kernel_type/op_entered (version 2!) no status.
import sys
HEX = sys.argv[1] if len(sys.argv) > 1 else "/root/sigcand2/ggml/src/ggml-hexagon"
HTP = f"{HEX}/htp"

# ============================================================
# A) HOST: log da metadata (o blob = a decisão real do precompute!)
# ============================================================
p = f"{HEX}/ggml-hexagon.cpp"
t = open(p, encoding="utf-8", errors="replace").read()
old = """                    if (r46_dump_bi >= 0 && !g_r46_meta.used) {   // UMA captura (desarma!)"""
assert t.count(old) == 1, "A1 anchor"
t = t.replace(old, """                    if (r46_dump_bi >= 0) {   // R49: a METADATA da op selecionada (o blob!)
                        GGML_LOG_DEBUG("ggml-hex: R49 OP meta: n_hmx=%d kernel_type=%d pipeline=%d m_chunk=%d n_chunk=%d s0=[%lld,%lld,%lld,%lld,t=%d] s1=[%lld,%lld,%lld,%lld,t=%d]",
                            k46->n_hmx, k46->kernel_type, k46->pipeline, k46->m_chunk, k46->n_chunk,
                            (long long) s0->ne[0], (long long) s0->ne[1], (long long) s0->ne[2], (long long) s0->ne[3], (int) s0->type,
                            (long long) s1->ne[0], (long long) s1->ne[1], (long long) s1->ne[2], (long long) s1->ne[3], (int) s1->type);
                    }
                    if (r46_dump_bi >= 0 && !g_r46_meta.used) {   // UMA captura (desarma!)""", 1)

# A.2 o getter: expor n_hmx/kernel_type/op_entered (version 2 — o handshake cobre!)
old = """    out->status = 2;   // CAPTURED (tudo validado!)
    return 0;
}"""
assert t.count(old) == 1, "A2 anchor"
t = t.replace(old, """    out->status = 2;   // CAPTURED (tudo validado!)
    // R49: a metadata do ramo (lida do header!) — exposta SEMPRE (observabilidade!)
    out->r49_n_hmx = (int32_t) hdr[4];
    out->r49_kernel_type = (int32_t) hdr[5];
    out->r49_op_entered = (hdr[7] == 0xA5A50001u) ? 1 : 0;
    return 0;
}""", 1)
# A.3 o struct: campos novos + version 2!
old = """    uint32_t payload_off;  // 64 (payload!)
    uint32_t reserved;
    uint64_t ptr;          // o buffer (para o probe ler!)
    uint64_t capacity;     // total do sbuf
    uint64_t row_size;     // da op capturada
};"""
assert t.count(old) == 1, "A3 anchor"
t = t.replace(old, """    uint32_t payload_off;  // 64 (payload!)
    uint32_t reserved;
    uint64_t ptr;          // o buffer (para o probe ler!)
    uint64_t capacity;     // total do sbuf
    uint64_t row_size;     // da op capturada
    // R49 (version 2!): a METADATA do ramo real (do header!)
    int32_t  r49_n_hmx;       // o n_hmx DECODIFICADO pelo DSP no op_matmul!
    int32_t  r49_kernel_type; // o kernel_type!
    int32_t  r49_op_entered;  // 1 = o op_matmul LEU os kparams desta op!
    int32_t  r49_reserved;
};""", 1)
# A.4 version=2!
old = "    out->magic = R46_MAGIC; out->version = 1;"
assert t.count(old) == 1, "A4 anchor"
t = t.replace(old, "    out->magic = R46_MAGIC; out->version = 2;   // R49: + metadata do ramo!", 1)
# A.5 limpar os campos no início!
old = """    out->reserved = 0; out->ptr = 0; out->capacity = 0; out->row_size = 0;"""
assert t.count(old) == 1, "A5 anchor"
t = t.replace(old, """    out->reserved = 0; out->ptr = 0; out->capacity = 0; out->row_size = 0;
    out->r49_n_hmx = -1; out->r49_kernel_type = -1; out->r49_op_entered = 0; out->r49_reserved = 0;""", 1)
open(p, "w", encoding="utf-8", newline="\n").write(t)
print("A) HOST R49 ok (metadata log + getter v2!)")

# ============================================================
# B) SKEL: metadata no header ANTES do if(n_hmx); worker gated por h[4]==0!
# ============================================================
p2 = f"{HTP}/matmul-ops.c"
t2 = open(p2, encoding="utf-8", errors="replace").read()
old = """    octx->act_dump_ptr = NULL;
    octx->act_dump_cap = 0;
    if (kparams->act_dump_bi >= 0 && kparams->act_dump_bi < (int) octx->ctx->r46_nbufs && octx->ctx->r46_bufs && !kparams->n_hmx) {
        octx->act_dump_ptr = (uint8_t *) (uintptr_t) octx->ctx->r46_bufs[kparams->act_dump_bi].base;
        octx->act_dump_cap = (size_t) octx->ctx->r46_bufs[kparams->act_dump_bi].size;
        if (octx->act_dump_ptr && octx->act_dump_cap >= 64) {
            uint32_t * h = (uint32_t *) octx->act_dump_ptr;
            h[2] = 1;   // R46: op_seen (a op SELECIONADA chegou ao op_matmul!)
        } else {
            octx->act_dump_ptr = NULL;   // cap < 64: recusa!
            octx->act_dump_cap = 0;
        }
    }"""
assert t2.count(old) == 1, "B1 anchor"
t2 = t2.replace(old, """    // R49: METADATA DO RAMO (o parecer R48/H!): SEMPRE que armado, registrar os
    // campos DECODIFICADOS no header ANTES do if(n_hmx) — a observabilidade do
    // ramo real deixa de depender do ramo da CÓPIA (que segue HVX-only!).
    octx->act_dump_ptr = NULL;
    octx->act_dump_cap = 0;
    if (kparams->act_dump_bi >= 0 && kparams->act_dump_bi < (int) octx->ctx->r46_nbufs && octx->ctx->r46_bufs) {
        octx->act_dump_ptr = (uint8_t *) (uintptr_t) octx->ctx->r46_bufs[kparams->act_dump_bi].base;
        octx->act_dump_cap = (size_t) octx->ctx->r46_bufs[kparams->act_dump_bi].size;
        if (octx->act_dump_ptr && octx->act_dump_cap >= 64) {
            uint32_t * h = (uint32_t *) octx->act_dump_ptr;
            h[2] = 1;                        // op_seen
            h[4] = (uint32_t) kparams->n_hmx;        // R49: o ramo REAL!
            h[5] = (uint32_t) kparams->kernel_type;  // R49!
            h[6] = (uint32_t) kparams->act_dump_bi;  // (o bi do wire!)
            h[7] = 0xA5A50001u;                      // R49: op_matmul entrou!
        } else {
            octx->act_dump_ptr = NULL;
            octx->act_dump_cap = 0;
        }
    }""", 1)
# B.2 o worker: a COPIA soh no ramo HVX (h[4]==0!) — a protecao permanece!
old = """    if (mmctx->octx->act_dump_ptr) {                                                                       \\
        /* R47: CHECKED — divisao ANTES dos produtos (sem wrap!) e todos os                  */          \\"""
assert t2.count(old) == 1, "B2 anchor"
t2 = t2.replace(old, """    if (mmctx->octx->act_dump_ptr && ((const uint32_t *) mmctx->octx->act_dump_ptr)[4] == 0) {            \\
        /* R49: a COPIA e' HVX-only (h[4]==0 — o n_hmx REAL do op_matmul!)!                  */          \\
        /* R47: CHECKED — divisao ANTES dos produtos (sem wrap!) e todos os                  */          \\""", 1)
open(p2, "w", encoding="utf-8", newline="\n").write(t2)
print("B) SKEL R49 ok (metadata+worker gate!)")
print("=== PATCH R49 COMPLETO!")
