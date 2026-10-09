        size += 4 * 1024;  // guard page
        ggml_hexagon_shared_buffer * sbuf = new ggml_hexagon_shared_buffer(sess, size);
        return ggml_backend_buffer_init(buffer_type, ggml_backend_hexagon_buffer_interface, sbuf, size);
    } catch (const std::exception & exc) {
        GGML_LOG_ERROR("ggml-hex: %s failed to allocate buffer context (repack): %s\n", sess->c_name(), exc.what());
        return nullptr;
    }
}

static size_t ggml_backend_hexagon_buffer_type_get_alignment(ggml_backend_buffer_type_t buft) {
    return 128;  // HVX alignment
    GGML_UNUSED(buft);
}

static size_t ggml_backend_hexagon_buffer_type_get_alloc_size(ggml_backend_buffer_type_t buft, const struct ggml_tensor * t) {
    // R23 FIX: era lista inline SEM Q4_K/Q6_K (req=9216 canonico em vez de
    // 10240 tiled!) — usa o helper como o pin upstream.
    if (ggml_hexagon_is_repack_type(t->type)) {
        int64_t ne0 = hex_round_up(t->ne[0], 32);
        int64_t ne1 = hex_round_up(t->ne[1], 32);
        int64_t ne2 = t->ne[2];
        int64_t ne3 = t->ne[3];
        return ggml_hexagon_tiled_row_size(t->type, ne0) * ne1 * ne2 * ne3;
    }
    return ggml_nbytes(t);

    GGML_UNUSED(buft);
}

static size_t ggml_backend_hexagon_buffer_type_get_max_size(ggml_backend_buffer_type_t buft) {
    auto * context = static_cast<ggml_backend_hexagon_buffer_type_context *>(buft->context);
