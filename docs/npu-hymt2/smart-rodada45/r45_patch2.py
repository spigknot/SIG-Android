#!/usr/bin/env python3
# r45_patch2.py — R45: P1-P3 (o dump da ACT Q6 — test-only!)
# P1: shared_buffer global (256KB) armado por env GGML_HEXAGON_ACT_DUMP=1
# P2: add_buffer(dump) -> bi (o global!)
# P3: bi no kernel_params do matmul (campo NOVO no struct!)
import re
import sys
HEX = sys.argv[1] if len(sys.argv) > 1 else "/root/kq2/ggml/src/ggml-hexagon"

# ===== ggml-hexagon.cpp: P1+P2+P3 =====
p = f"{HEX}/ggml-hexagon.cpp"
t = open(p, encoding="utf-8", errors="replace").read()

# 0) o global file-scope (antes do precompute — L243!)
old0 = "static void ggml_hexagon_precompute_matmul_params(\n    const struct ggml_hexagon_session * sess,"
assert t.count(old0) >= 1, "global anchor!"
t = t.replace(old0, """// R45 (test-only!): o bi global do dump da ACT (-1 = OFF!)
static int g_r45_act_dump_bi = -1;

static void ggml_hexagon_precompute_matmul_params(
    const struct ggml_hexagon_session * sess,""", 1)

# 1) P3: o precompute (a DEFINICAO!) seta o kparams->act_dump_bi!
m = re.search(r"static void ggml_hexagon_precompute_matmul_params\([^;]*?\)\s*\{", t)
assert m, "precompute def!"
b0 = m.end() - 1   # o '{'
t = t[:b0+1] + "\n    kparams->act_dump_bi = g_r45_act_dump_bi;   // R45: P3 (test-only: -1 = OFF!)" + t[b0+1:]

# 2) P1+P2: no add_tensor (o batch!) — armar antes dos tensores!
old2 = """    // add tensor and return its index
    int add_tensor(const ggml_tensor * t) {
        auto sbuf = static_cast<ggml_hexagon_shared_buffer *>(t->buffer->context);"""
assert t.count(old2) == 1, "add_tensor anchor!"
t = t.replace(old2, """    // R45 (test-only!): arma o dump UMA vez (P1: criacao; P2: add_buffer!)
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

    // add tensor and return its index
    int add_tensor(const ggml_tensor * t) {
        auto sbuf = static_cast<ggml_hexagon_shared_buffer *>(t->buffer->context);
        r45_act_dump_arm(sbuf->sess);   // R45: P2 (antes dos tensores — bi estavel!)""", 1)
open(p, "w", encoding="utf-8", newline="\n").write(t)
print("P1+P2+P3 (host) ok")

# ===== htp/matmul-ops.h: o campo (P3 wire!) =====
p2 = f"{HEX}/htp/matmul-ops.h"
t2 = open(p2, encoding="utf-8", errors="replace").read()
old3 = """    int32_t  vtcm_dst_size;      // dst scratchpad size in VTCM
"""
assert t2.count(old3) == 1, "struct anchor!"
t2 = t2.replace(old3, """    int32_t  vtcm_dst_size;      // dst scratchpad size in VTCM
    int32_t  act_dump_bi;        // R45 (test-only): buffer index do dump da ACT (-1 = OFF!)
""", 1)
open(p2, "w", encoding="utf-8", newline="\n").write(t2)
print("P3 (wire: matmul-ops.h) ok")
print("=== patch P1-P3 COMPLETO (P4/P5 = skel — proximo!)")
