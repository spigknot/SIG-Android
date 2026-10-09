// GERADO — a expressao REAL do fused (L3151!):
const size_t src1_row_size = (wtype == GGML_TYPE_Q4_1 || wtype == GGML_TYPE_Q4_K) ? htp_mm_q8_1_tiled_row_size(ne10) : htp_mm_q8_0_tiled_row_size(ne10);  // R27 FIX
