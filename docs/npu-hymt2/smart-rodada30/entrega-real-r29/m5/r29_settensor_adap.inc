// r29_settensor_gen.inc — CORPO COMPLETO do set_tensor (L0..L0 do fonte!)
static void set_tensor_real(ggml_backend_buffer_t buffer,
                                                   ggml_tensor *         tensor,
                                                   const void *          data,
                                                   size_t                offset,
                                                   size_t                size) {
    // ADAPTADO (declarado!): sbuf/sess = contexto de sessao nao exercitado!
    (void) buffer;

    // HEX_VERBOSE removido (log externo! declarado!)

    switch (tensor->type) {
        case GGML_TYPE_Q4_0:
            GGML_ASSERT(offset == 0);
            GGML_ASSERT(offset + size <= ggml_nbytes(tensor));
            repack_q4_0_tiled(tensor, data, size);
            break;

        case GGML_TYPE_Q4_1:
            GGML_ASSERT(offset == 0);
            GGML_ASSERT(offset + size <= ggml_nbytes(tensor));
            repack_q4_1_tiled(tensor, data, size);
            break;

        case GGML_TYPE_Q8_0:
            GGML_ASSERT(offset == 0);
            GGML_ASSERT(offset + size <= ggml_nbytes(tensor));
            repack_q8_0_tiled(tensor, data, size);
            break;

        case GGML_TYPE_IQ4_NL:
            GGML_ASSERT(offset == 0);
            GGML_ASSERT(offset + size <= ggml_nbytes(tensor));
            // IQ4_NL has identical block layout to Q4_0 (ggml_half d + uint8_t qs[16])
            repack_q4_0_tiled(tensor, data, size);
            break;

        case GGML_TYPE_MXFP4:
            GGML_ASSERT(offset == 0);
            GGML_ASSERT(offset + size <= ggml_nbytes(tensor));
            repack_mxfp4_tiled(tensor, data, size);
            break;

        // R23/R24 FIX: cases K-quant FALTAVAM no set_tensor (Q4_K caia no
        // memcpy CANONICO sem repack => DSP lia canonicos como tiled => NaN!).
        // R24: guard EXPLICITO de offset/size (o pack exige tensor inteiro!);
        // recusa upload parcial ANTES de ler/escrever (contrato do pack!).
        case GGML_TYPE_Q4_K:
            // R29: guarda full-only do contrato (offset=0 E size completo!);
            // recusa ANTES de ler/escrever; NUNCA normalizar offset silenciosamente!
            fprintf(stderr, "KQSET: q4_K name=%s size=%zu -> repack_q4_K_tiled\n", tensor->name ? tensor->name : "?", size);
            repack_q4_K_tiled(tensor, data, offset, size);
            break;

        case GGML_TYPE_Q6_K:
            // R29: guarda full-only (idem Q4_K!)
            fprintf(stderr, "KQSET: q6_K name=%s size=%zu -> repack_q6_K_tiled\n", tensor->name ? tensor->name : "?", size);
            repack_q6_K_tiled(tensor, data, offset, size);
            break;

        default:
            memcpy((char *) tensor->data + offset, data, size);
            break;
    }
}