#!/usr/bin/env python3
# r45_patch5.py — R45: P4b (op_matmul) + P5 (worker memcpy!) — o matmul-ops.c!
import sys
HTP = (sys.argv[1] if len(sys.argv) > 1 else "/root/kq2/ggml/src/ggml-hexagon") + "/htp"
p = f"{HTP}/matmul-ops.c"
t = open(p, encoding="utf-8", errors="replace").read()

# P4b: o cast do bi -> ptr no op_matmul!
old4 = """int op_matmul(struct htp_ops_context * octx) {
    const struct htp_mm_kernel_params * kparams = (const struct htp_mm_kernel_params *) octx->kernel_params;

    if (kparams->n_hmx) {"""
if t.count(old4) == 1:
    t = t.replace(old4, """int op_matmul(struct htp_ops_context * octx) {
    const struct htp_mm_kernel_params * kparams = (const struct htp_mm_kernel_params *) octx->kernel_params;

    // R45 (test-only!): P4 — o dump da ACT: bi -> endereco DSP (o fd ja' mapeado!)
    if (kparams->act_dump_bi >= 0 && kparams->act_dump_bi < HTP_MAX_MMAPS && octx->ctx->mmap[kparams->act_dump_bi].size) {
        octx->act_dump_ptr = (uint8_t *) (uintptr_t) octx->ctx->mmap[kparams->act_dump_bi].base;
    } else {
        octx->act_dump_ptr = NULL;
    }

    if (kparams->n_hmx) {""", 1)
    print("P4b ok")
else:
    print(f"P4b: ja aplicado ou nao encontrado (count={t.count(old4)})")

# P5: o memcpy (dentro do macro — com \\!)
old5 = "    kernel_fn(src_data, dst_data, tmp_data, ne0, ir_last - ir_first, src_row_size, dst_row_size);          \\"
if t.count(old5) == 1:
    new5 = """    kernel_fn(src_data, dst_data, tmp_data, ne0, ir_last - ir_first, src_row_size, dst_row_size);          \\
    if (mmctx->octx->act_dump_ptr) {                                                                       \\
        uint8_t * d45 = mmctx->octx->act_dump_ptr + ((size_t) dst_row_size * ir_first);                    \\
        memcpy(d45, dst_data, (size_t) dst_row_size * (ir_last - ir_first));                               \\
    }                                                                                                      \\"""
    t = t.replace(old5, new5, 1)
    print("P5 ok")
else:
    print(f"P5: ja aplicado ou nao encontrado (count={t.count(old5)})")

open(p, "w", encoding="utf-8", newline="\n").write(t)
print(f"gravado: {p}")
