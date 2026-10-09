// GERADO (r30_fused_extrai.py!) — VERBATIM de /root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp L3942..3947
static bool is_mergeable_mul_mat(const ggml_tensor * t) {
    if (!t || t->op != GGML_OP_MUL_MAT)   return false;
    if (t->src[1]->type != GGML_TYPE_F32) return false;
    if (t->src[0]->type == GGML_TYPE_Q6_K) return false;  // R15: Q6_K nao tem kernel FUSED (analogo ao guard upstream)
    return ggml_is_quantized(t->src[0]->type) && !mm_is_hmx_eligible(t);
}
