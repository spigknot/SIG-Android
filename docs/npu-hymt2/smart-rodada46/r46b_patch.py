#!/usr/bin/env python3
# r46b_patch.py — R46 §5: A API DE LEITURA (o consumidor!) + o header no buffer!
# HOST: struct do status (layout acordado!) + g_r46_meta (epoch/metadados!) +
#   o header preparado no add_op (magic/epoch!) + a API no registry!
# SKEL: op_seen (no op_matmul!) + rows_done (atomic nos workers!) + payload OFF64!
import sys
HEX = sys.argv[1] if len(sys.argv) > 1 else "/root/sigcand2/ggml/src/ggml-hexagon"
HTP = f"{HEX}/htp"

# ============================================================
# A) HOST: o struct + os metadados + o header + a API!
# ============================================================
p = f"{HEX}/ggml-hexagon.cpp"
t = open(p, encoding="utf-8", errors="replace").read()

# A.1 o struct do status + os metadados (antes do getter do opstage — L88!)
anc = "static int ggml_hexagon_get_opstage_effective(void)"
assert t.count(anc) == 1, "A1 anchor"
t = t.replace(anc, """// R46 §5 (test-only!): O STATUS DA CAPTURA DA ACT — layout ACORDADO com o
// consumidor (o probe copia o struct + confere o magic/version!)!
struct ggml_hexagon_act_dump_status {
    uint32_t magic;        // 0x52343644
    uint32_t version;      // 1
    int32_t  status;       // 0=NOT_ARMED; 1=ARMED_NO_CAPTURE; 2=CAPTURED; -1=INVALIDO
    int32_t  type;         // ggml_type do peso (o Q6_K!)
    int32_t  K, N, B;
    uint32_t epoch;        // a captura (1,2,...)
    uint32_t rows_done;    // do header (LIDO AGORA!)
    uint32_t payload_off;  // 64 (payload!)
    uint32_t reserved;
    uint64_t ptr;          // o buffer (para o probe ler!)
    uint64_t capacity;     // total do sbuf
    uint64_t row_size;     // da op capturada
};

// Os metadados da captura (host!): a UMA captura (sem sobrescrever!)
static struct {
    int      used;         // 1 = ja capturou (desarma as demais!)
    uint32_t epoch;
    int      K, N, B, type;
    size_t   row_size;
} g_r46_meta = {0};

#define R46_MAGIC 0x52343644u   // "R46D"

""" + anc, 1)

# A.2 no add_op: preparar o header + guardar os metadados (a UMA captura!)
old = """                if (r46_dump_bi < 0) {
                    r46_dump_bi = add_buffer(g_r46_act_dump_sbuf);   // bi DESTE batch (0 e' valido!)
                }
                if (r46_dump_bi >= 0) {
                    k46->act_dump_bi = r46_dump_bi;
                    GGML_LOG_DEBUG("ggml-hex: R46 ACT_DUMP: batch bi=%d fd=%d\\n", r46_dump_bi, g_r46_act_dump_sbuf->fd);
                }"""
assert t.count(old) == 1, "A2 anchor"
t = t.replace(old, """                if (r46_dump_bi < 0) {
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
                }"""
, 1)

# A.3 A API (o getter!) — depois do getter do opstage!
old = "static int ggml_hexagon_get_opstage_effective(void) {"
i = t.find(old)
assert i > 0, "A3 anchor"
# achar o fim da funcao (o proximo "\n}\n"!)
j = t.find("\n}\n", i)
assert j > 0, "A3 end"
t = t[:j+3] + """
// R46 §5 (test-only!): A API DE LEITURA da captura da ACT (o consumidor!)
// Retorna 0 e preenche o struct (status!); o magic/version conferem o layout.
static int ggml_hexagon_get_act_dump(void * out_v) {
    struct ggml_hexagon_act_dump_status * out = (struct ggml_hexagon_act_dump_status *) out_v;
    if (!out) return -1;
    out->magic = R46_MAGIC; out->version = 1;
    out->type = 0; out->K = 0; out->N = 0; out->B = 0;
    out->epoch = g_r46_meta.epoch; out->rows_done = 0; out->payload_off = 64;
    out->reserved = 0; out->ptr = 0; out->capacity = 0; out->row_size = 0;
    if (!g_r46_act_dump_armed()) { out->status = 0; return 0; }          // NOT_ARMED (a flag!)
    if (g_r46_meta.epoch == 0 || !g_r46_act_dump_sbuf) { out->status = 1; return 0; }   // ARMED, sem captura
    out->ptr = (uint64_t) (uintptr_t) g_r46_act_dump_sbuf->base;
    out->capacity = g_r46_act_dump_sbuf->size;
    out->type = g_r46_meta.type; out->K = g_r46_meta.K; out->N = g_r46_meta.N; out->B = g_r46_meta.B;
    out->row_size = g_r46_meta.row_size;
    const uint32_t * hdr = (const uint32_t *) g_r46_act_dump_sbuf->base;
    if (hdr[0] != R46_MAGIC || hdr[1] != g_r46_meta.epoch) { out->status = -1; return 0; }   // INVALIDO (o header!)
    out->rows_done = hdr[3];
    // CAPTURED: a op chegou (op_seen!), as rows completaram e o compute acabou (quem
    // chama: SO' depois da resposta do batch — o flush global do DSP ja' ocorreu!)!
    const int op_seen = hdr[2] == 1;
    out->status = (op_seen && (int) hdr[3] >= g_r46_meta.B && g_r46_meta.B > 0) ? 2 : 1;
    return 0;
}
""" + t[j+3:]

# A.4 O registro no get_proc_address!
old = """    if (strcmp(name, "ggml_hexagon_get_opstage_effective") == 0) {"""
assert t.count(old) == 1, "A4 anchor"
t = t.replace(old, """    if (strcmp(name, "ggml_hexagon_get_act_dump") == 0) {
        return (void *) ggml_hexagon_get_act_dump;   // R46 §5 (test-only!)
    }
    if (strcmp(name, "ggml_hexagon_get_opstage_effective") == 0) {""", 1)

open(p, "w", encoding="utf-8", newline="\n").write(t)
print("A) HOST: struct+meta+header+API+registry ok")

# ============================================================
# B) SKEL: op_seen + rows_done (atomic!) + payload OFF 64!
# ============================================================
p2 = f"{HTP}/matmul-ops.c"
t2 = open(p2, encoding="utf-8", errors="replace").read()

# B.1 no op_matmul: marcar o op_seen (a op CHEGOU — header[2] = 1!)
old = """    if (kparams->act_dump_bi >= 0 && kparams->act_dump_bi < (int) octx->ctx->r46_nbufs && octx->ctx->r46_bufs && !kparams->n_hmx) {
        octx->act_dump_ptr = (uint8_t *) (uintptr_t) octx->ctx->r46_bufs[kparams->act_dump_bi].base;
        octx->act_dump_cap = (size_t) octx->ctx->r46_bufs[kparams->act_dump_bi].size;
    }"""
assert t2.count(old) == 1, "B1 anchor"
t2 = t2.replace(old, """    if (kparams->act_dump_bi >= 0 && kparams->act_dump_bi < (int) octx->ctx->r46_nbufs && octx->ctx->r46_bufs && !kparams->n_hmx) {
        octx->act_dump_ptr = (uint8_t *) (uintptr_t) octx->ctx->r46_bufs[kparams->act_dump_bi].base;
        octx->act_dump_cap = (size_t) octx->ctx->r46_bufs[kparams->act_dump_bi].size;
        if (octx->act_dump_ptr && octx->act_dump_cap >= 64) {
            uint32_t * h = (uint32_t *) octx->act_dump_ptr;
            h[2] = 1;   // R46: op_seen (a op SELECIONADA chegou ao op_matmul!)
        } else {
            octx->act_dump_ptr = NULL;   // cap < 64: recusa!
            octx->act_dump_cap = 0;
        }
    }""", 1)

# B.2 no worker: o atomic (rows_done!) + o payload no OFF 64!
old = """    if (mmctx->octx->act_dump_ptr) {                                                                       \\
        const size_t d46_off = (size_t) dst_row_size * ir_first;                                           \\
        const size_t d46_len = (size_t) dst_row_size * (ir_last - ir_first);                               \\
        if (d46_off <= mmctx->octx->act_dump_cap && d46_len <= mmctx->octx->act_dump_cap - d46_off) {      \\
            memcpy(mmctx->octx->act_dump_ptr + d46_off, dst_data, d46_len);                                \\
        }                                                                                                  \\
    }                                                                                                      \\"""
assert t2.count(old) == 1, "B2 anchor"
t2 = t2.replace(old, """    if (mmctx->octx->act_dump_ptr) {                                                                       \\
        const size_t d46_off = 64 + (size_t) dst_row_size * ir_first;    /* OFF 64 (o header!) */          \\
        const size_t d46_len = (size_t) dst_row_size * (ir_last - ir_first);                               \\
        if (d46_off <= mmctx->octx->act_dump_cap && d46_len <= mmctx->octx->act_dump_cap - d46_off) {      \\
            memcpy(mmctx->octx->act_dump_ptr + d46_off, dst_data, d46_len);                                \\
            atomic_fetch_add((atomic_uint *) &((uint32_t *) mmctx->octx->act_dump_ptr)[3], 1u);            \\
        }                                                                                                  \\
    }                                                                                                      \\""", 1)
open(p2, "w", encoding="utf-8", newline="\n").write(t2)
print("B) SKEL: op_seen+rows_done+off64 ok")
print("=== PATCH §5 COMPLETO (a API + o header + o consumidor no host!)")
