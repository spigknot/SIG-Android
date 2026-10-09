#!/usr/bin/env python3
# R23 FIX: set_tensor (cases Q4_K/Q6_K!) + get_alloc_size (is_repack_type!)
K = "/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
t = open(K, "rb").read().replace(b"\r\n", b"\n").decode()

# ---- FIX 1: set_tensor += cases Q4_K/Q6_K (o bug do NaN!) ----
old_set = """        case GGML_TYPE_MXFP4:
            GGML_ASSERT(offset == 0);
            GGML_ASSERT(offset + size <= ggml_nbytes(tensor));
            repack_mxfp4_tiled(tensor, data, size);
            break;

        default:
            memcpy((char *) tensor->data + offset, data, size);
            break;
    }
}"""
new_set = """        case GGML_TYPE_MXFP4:
            GGML_ASSERT(offset == 0);
            GGML_ASSERT(offset + size <= ggml_nbytes(tensor));
            repack_mxfp4_tiled(tensor, data, size);
            break;

        // R23 FIX: cases K-quant FALTAVAM no set_tensor (Q4_K caia no memcpy
        // CANONICO sem repack => DSP lia canonicos como tiled => NaN!)
        case GGML_TYPE_Q4_K:
            fprintf(stderr, "KQSET: q4_K name=%s offset=%zu size=%zu -> repack_q4_K_tiled\\n",
                    tensor->name ? tensor->name : "?", offset, size);
            repack_q4_K_tiled(tensor, data, 0, size);
            break;

        case GGML_TYPE_Q6_K:
            fprintf(stderr, "KQSET: q6_K name=%s offset=%zu size=%zu -> repack_q6_K_tiled\\n",
                    tensor->name ? tensor->name : "?", offset, size);
            repack_q6_K_tiled(tensor, data, 0, size);
            break;

        default:
            memcpy((char *) tensor->data + offset, data, size);
            break;
    }
}"""
assert t.count(old_set) == 1, "set_tensor pattern: %d" % t.count(old_set)
t = t.replace(old_set, new_set, 1)

# ---- FIX 2: get_alloc_size usa is_repack_type (nao lista inline!) ----
old_alloc = """    if (t->type == GGML_TYPE_Q4_0 || t->type == GGML_TYPE_Q4_1 || t->type == GGML_TYPE_Q8_0 || t->type == GGML_TYPE_IQ4_NL || t->type == GGML_TYPE_MXFP4) {
        int64_t ne0 = hex_round_up(t->ne[0], 32);"""
new_alloc = """    // R23 FIX: era lista inline SEM Q4_K/Q6_K (req=9216 canonico em vez de
    // 10240 tiled!) — usa o helper como o pin upstream.
    if (ggml_hexagon_is_repack_type(t->type)) {
        int64_t ne0 = hex_round_up(t->ne[0], 32);"""
assert t.count(old_alloc) == 1, "alloc pattern: %d" % t.count(old_alloc)
t = t.replace(old_alloc, new_alloc, 1)

open(K, "w", encoding="utf-8", newline="\n").write(t)
print("FIX 1 (set_tensor Q4_K/Q6_K!) + FIX 2 (get_alloc_size helper!) aplicados!")
print("Q4_K no set?", t.count("repack_q4_K_tiled(tensor, data, 0, size)") >= 2)
print("Q6_K no set?", t.count("repack_q6_K_tiled(tensor, data, 0, size)") >= 2)
