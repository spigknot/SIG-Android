static void ggml_backend_hexagon_buffer_set_tensor(ggml_backend_buffer_t buffer,
                                                   ggml_tensor *         tensor,
                                                   const void *          data,
                                                   size_t                offset,
                                                   size_t                size) {
    auto sbuf = (ggml_hexagon_shared_buffer *) buffer->context;
    auto sess = sbuf->sess;

    HEX_VERBOSE("ggml-hex: %s set-tensor %s : data %p offset %zu size %zu\n", sess->c_name(), tensor->name, data, offset, size);

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

        // R23 FIX: cases K-quant FALTAVAM no set_tensor (Q4_K caia no memcpy
        // CANONICO sem repack => DSP lia canonicos como tiled => NaN!)
        case GGML_TYPE_Q4_K:
            fprintf(stderr, "KQSET: q4_K name=%s offset=%zu size=%zu -> repack_q4_K_tiled\n",
                    tensor->name ? tensor->name : "?", offset, size);
            repack_q4_K_tiled(tensor, data, 0, size);
            break;

        case GGML_TYPE_Q6_K:
            fprintf(stderr, "KQSET: q6_K name=%s offset=%zu size=%zu -> repack_q6_K_tiled\n",
                    tensor->name ? tensor->name : "?", offset, size);
            repack_q6_K_tiled(tensor, data, 0, size);
            break;

        default:
            memcpy((char *) tensor->data + offset, data, size);
            break;
    }
}

static void ggml_backend_hexagon_buffer_get_tensor(ggml_backend_buffer_t buffer,
                                                   const ggml_tensor *   tensor,
                                                   void *                data,
                                                   size_t                offset,
                                                   size_t                size) {
    auto sbuf = (ggml_hexagon_shared_buffer *) buffer->context;
