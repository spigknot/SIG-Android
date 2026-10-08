#!/usr/bin/env python3
# R15 §2: guard de merge p/ Q6_K no SIG (analogo ao hunk 17 do KQT)
# + verificar se existem kernels FUSED (qkv/ffn) para Q6_K no matmul-ops.c
import subprocess
K = "/root/kq2/ggml/src/ggml-hexagon"
mo = open(K+"/htp/matmul-ops.c").read()
print("=== kernels fused no matmul-ops.c ===")
for tok in ["qkv_2d_repacked_q6_k", "qkv_2d_repacked_q4_k", "qkv_2d_repacked_q4_1", "ffn_2d_repacked_q6_k", "ffn_2d_repacked_q4_k", "ffn_2d_repacked_q4_1"]:
    print(f"  {tok}: {mo.count(tok)}")
print()
# o guard: no is_mergeable_mul_mat do kq2
t = open(K+"/ggml-hexagon.cpp", "rb").read().replace(b"\r\n", b"\n").decode()
old = """static bool is_mergeable_mul_mat(const ggml_tensor * t) {
    if (!t || t->op != GGML_OP_MUL_MAT)   return false;
    if (t->src[1]->type != GGML_TYPE_F32) return false;
    return ggml_is_quantized(t->src[0]->type) && !mm_is_hmx_eligible(t);
}"""
new = """static bool is_mergeable_mul_mat(const ggml_tensor * t) {
    if (!t || t->op != GGML_OP_MUL_MAT)   return false;
    if (t->src[1]->type != GGML_TYPE_F32) return false;
    if (t->src[0]->type == GGML_TYPE_Q6_K) return false;  // R15: Q6_K nao tem kernel FUSED (analogo ao guard upstream)
    return ggml_is_quantized(t->src[0]->type) && !mm_is_hmx_eligible(t);
}"""
assert t.count(old) == 1, t.count(old)
t = t.replace(old, new, 1)
open(K+"/ggml-hexagon.cpp", "w").write(t)
print("guard is_mergeable Q6_K aplicado")
