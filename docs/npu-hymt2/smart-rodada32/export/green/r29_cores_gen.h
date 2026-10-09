// r29_cores_gen.h — GERADO AUTOMATICAMENTE por r29_extrai.py (NAO EDITAR!)
// Fontes: /root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp e /root/llama-cpp-npu/ggml/src/ggml-quants.c
// hashes: fonte-hex=76db17032c6eb367756ab7bb20b1432d57964daa5e17e627c5848f2f8629a7ce fonte-quants=5574a2dccf7c07e75b143733e04a5412d3d8c819e7945f5217a7b83a2b2ff8ab
#pragma once

// ==== get_scale_min_k4 (ggml-quants.c!) ====
static inline void get_scale_min_k4(int j, const uint8_t * GGML_RESTRICT q, uint8_t * GGML_RESTRICT d, uint8_t * GGML_RESTRICT m) {
    if (j < 4) {
        *d = q[j] & 63; *m = q[j + 4] & 63;
    } else {
        *d = (q[j+4] & 0xF) | ((q[j-4] >> 6) << 4);
        *m = (q[j+4] >>  4) | ((q[j-0] >> 6) << 4);
    }
}

// ==== q6_K_get_quant ====
static inline uint8_t q6_K_get_quant(const block_q6_K * b, int e) {
    const int c = e / 128;
    const int w = e % 128;
    const int g = w / 32;
    const int l = w % 32;
    const uint8_t * ql = b->ql + c * 64;
    const uint8_t * qh = b->qh + c * 32;
    uint8_t lo, hi;
    switch (g) {
        case 0:  lo = ql[l]      & 0xF; hi = (qh[l] >> 0) & 3; break;
        case 1:  lo = ql[l + 32] & 0xF; hi = (qh[l] >> 2) & 3; break;
        case 2:  lo = ql[l]      >> 4;  hi = (qh[l] >> 4) & 3; break;
        default: lo = ql[l + 32] >> 4;  hi = (qh[l] >> 6) & 3; break;
    }
    return (uint8_t) (lo | (hi << 4));
}

// ==== is_repack_type ====
static inline bool ggml_hexagon_is_repack_type(enum ggml_type type) {
    return type == GGML_TYPE_Q4_0 || type == GGML_TYPE_Q4_1 ||
           type == GGML_TYPE_Q8_0 || type == GGML_TYPE_IQ4_NL ||
           type == GGML_TYPE_MXFP4 || type == GGML_TYPE_Q6_K ||
           type == GGML_TYPE_Q4_K;
}

// ==== tiled_row_size ====
static inline size_t ggml_hexagon_tiled_row_size(enum ggml_type type, int64_t ne0) {
    if (type == GGML_TYPE_Q6_K) {
        return (size_t) (ne0 / 32) * (HTP_MM_WEIGHT_TILE_SIZE_Q6_K / 32);
    }
    if (type == GGML_TYPE_Q4_K) {
        return (size_t) (ne0 / 32) * (HTP_MM_WEIGHT_TILE_SIZE_Q4_1 / 32);
    }
    return ggml_row_size(type, ne0);
}

// ==== repack_q4_K_tiled ====
static void repack_q4_K_tiled(const ggml_tensor * t, const void * data, size_t offset, size_t size) {
    GGML_ASSERT(offset == 0);

    const block_q4_K * src_matrix = (const block_q4_K *) data;
    int64_t ne0 = t->ne[0];
    int64_t ne1 = t->ne[1];
    int64_t ne2 = t->ne[2];
    int64_t ne3 = t->ne[3];
    int64_t ne0_padded = hex_round_up(ne0, 32);
    int64_t ne1_padded = hex_round_up(ne1, 32);

    GGML_ASSERT(ne0 % QK_K == 0);

    const int n_col_tiles = ne1_padded / 32;
    const int n_k_tiles   = ne0_padded / 32;
    const size_t tile_size   = HTP_MM_WEIGHT_TILE_SIZE_Q4_1;
    const size_t matrix_size = (size_t) n_col_tiles * n_k_tiles * tile_size;

    const int64_t sb_per_row = ne0 / QK_K;

    for (int i3 = 0; i3 < ne3; i3++) {
        for (int i2 = 0; i2 < ne2; i2++) {
            const block_q4_K * src_slice = src_matrix + (i3 * ne2 + i2) * (ne1 * sb_per_row);
            uint8_t * matrix_dst = (uint8_t *) t->data + (i3 * ne2 + i2) * matrix_size;

            memset(matrix_dst, 0, matrix_size);

            for (int64_t r = 0; r < ne1; r++) {
                const int ct  = (int) (r / 32);
                const int row = (int) (r % 32);
                const block_q4_K * src_row = src_slice + r * sb_per_row;

                for (int kt = 0; kt < n_k_tiles; kt++) {
                    const int kt_local = kt % 8;
                    const block_q4_K * b = &src_row[kt / 8];
                    const float d = GGML_FP16_TO_FP32(b->d);
                    const float dmin = GGML_FP16_TO_FP32(b->dmin);

                    uint8_t * tile_dst = matrix_dst + ((size_t) ct * n_k_tiles + kt) * tile_size;

                    uint8_t sc, m;
                    get_scale_min_k4(kt_local, b->scales, &sc, &m);

                    const float D = d * (float) sc;
                    const float M = -dmin * (float) m;

                    const uint8_t * qs_sub = b->qs + (kt_local / 2) * 32;
                    const int shift = (kt_local & 1) ? 4 : 0;

                    for (int cp = 0; cp < 16; cp++) {
                        const uint8_t q0 = (qs_sub[2 * cp + 0] >> shift) & 0x0F;
                        const uint8_t q1 = (qs_sub[2 * cp + 1] >> shift) & 0x0F;
                        tile_dst[cp * 32 + row] = (uint8_t) ((q1 << 4) | q0);
                    }

                    ggml_half * scale_dst = (ggml_half *) (tile_dst + 512);
                    scale_dst[2 * row + 0] = GGML_FP32_TO_FP16(D);
                    scale_dst[2 * row + 1] = GGML_FP32_TO_FP16(M);
                }
            }
        }
    }

    GGML_UNUSED(size);
}

// ==== repack_q6_K_tiled ====
static void repack_q6_K_tiled(const ggml_tensor * t, const void * data, size_t offset, size_t size) {
    GGML_ASSERT(offset == 0);

    const block_q6_K * src_matrix = (const block_q6_K *) data;
    int64_t ne0 = t->ne[0];
    int64_t ne1 = t->ne[1];
    int64_t ne2 = t->ne[2];
    int64_t ne3 = t->ne[3];
    int64_t ne0_padded = hex_round_up(ne0, 32);
    int64_t ne1_padded = hex_round_up(ne1, 32);

    GGML_ASSERT(ne0 % QK_K == 0);

    const int n_col_tiles = ne1_padded / 32;
    const int n_k_tiles   = ne0_padded / 32;
    const size_t tile_size   = HTP_MM_WEIGHT_TILE_SIZE_Q6_K;
    const size_t matrix_size = (size_t) n_col_tiles * n_k_tiles * tile_size;

    const int64_t sb_per_row = ne0 / QK_K;

    for (int i3 = 0; i3 < ne3; i3++) {
        for (int i2 = 0; i2 < ne2; i2++) {
            const block_q6_K * src_slice = src_matrix + (i3 * ne2 + i2) * (ne1 * sb_per_row);
            uint8_t * matrix_dst = (uint8_t *) t->data + (i3 * ne2 + i2) * matrix_size;

            memset(matrix_dst, 0, matrix_size);  // padding rows and the OR-ed nibbles below need zeroed tiles

            for (int64_t r = 0; r < ne1; r++) {
                const int ct  = (int) (r / 32);
                const int row = (int) (r % 32);
                const block_q6_K * src_row = src_slice + r * sb_per_row;

                for (int kt = 0; kt < n_k_tiles; kt++) {
                    const int kt_local = kt % 8;  // k-tile within the super-block
                    const block_q6_K * b = &src_row[kt / 8];
                    const float d = GGML_FP16_TO_FP32(b->d);

                    uint8_t * tile = matrix_dst + ((size_t) ct * n_k_tiles + kt) * tile_size;
                    uint8_t * lo_pl = tile;
                    uint8_t * hi_pl = tile + 512;
                    ggml_half * sc_pl = (ggml_half *) (tile + 768);

                    for (int lk = 0; lk < 32; lk++) {
                        const uint8_t q6 = q6_K_get_quant(b, kt_local * 32 + lk);
                        const int g   = lk >> 2;
                        const int pos = row * 4 + (lk & 3);
                        lo_pl[(g >> 1) * 128 + pos] |= (uint8_t) ((q6 & 0xF) << ((g & 1) * 4));
                        hi_pl[(g >> 2) * 128 + pos] |= (uint8_t) ((q6 >> 4) << ((g & 3) * 2));
                    }
                    for (int sub = 0; sub < 2; sub++) {
                        sc_pl[sub * 32 + row] = GGML_FP32_TO_FP16(d * (float) b->scales[kt_local * 2 + sub]);
                    }
                }
            }
        }
    }

    GGML_UNUSED(size);
}

// ==== get_alloc_size ====
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
