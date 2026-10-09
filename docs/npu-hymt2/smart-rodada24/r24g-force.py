#!/usr/bin/env python3
# R24g: logs INCONDICIONAIS no set_tensor K (GGML_LOG_DEBUG)
BSN = chr(92) + "n"
for path in ["/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp",
             "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    old = """        case GGML_TYPE_Q4_K:
            // R24: log via HEX_VERBOSE (vai pro logcat!) com os numeros REAIS!
            HEX_VERBOSE(""" 
    # achar o bloco atual do Q4_K!
    i4 = t.find("        case GGML_TYPE_Q4_K:")
    i6 = t.find("        case GGML_TYPE_Q6_K:")
    assert i4 > 0 and i6 > i4
    bloco = t[i4:i6]
    assert "KQSET" in bloco, "sem KQSET no bloco?"
    # substituir o bloco Q4_K inteiro por versao incondicional!
    novo4 = """        case GGML_TYPE_Q4_K:
            GGML_LOG_DEBUG("KQSET-FORCE q4_K: offset=%zu size=%zu nbytes=%zu ne0=%lld ne1=%lld nb1=%zu""" + BSN + """",
                           offset, size, (size_t) ggml_nbytes(tensor),
                           (long long) tensor->ne[0], (long long) tensor->ne[1], (size_t) tensor->nb[1]);
            if (offset != 0 || size != ggml_nbytes(tensor)) {
                GGML_LOG_DEBUG("KQSET-RECUSA q4_K: offset=%zu size=%zu nbytes=%zu (partial rejeitado!)""" + BSN + """",
                               offset, size, (size_t) ggml_nbytes(tensor));
                break;
            }
            GGML_LOG_DEBUG("KQSET-REPACK q4_K: chamando repack_q4_K_tiled!""" + BSN + """");
            repack_q4_K_tiled(tensor, data, offset, size);
            break;

"""
    t = t[:i4] + novo4 + t[i6:]
    # idem Q6_K! (achar fim: o proximo "default:" apos i6!)
    i6b = t.find("        case GGML_TYPE_Q6_K:")
    idef = t.find("        default:", i6b)
    assert i6b > 0 and idef > i6b
    novo6 = """        case GGML_TYPE_Q6_K:
            GGML_LOG_DEBUG("KQSET-FORCE q6_K: offset=%zu size=%zu nbytes=%zu ne0=%lld ne1=%lld nb1=%zu""" + BSN + """",
                           offset, size, (size_t) ggml_nbytes(tensor),
                           (long long) tensor->ne[0], (long long) tensor->ne[1], (size_t) tensor->nb[1]);
            if (offset != 0 || size != ggml_nbytes(tensor)) {
                GGML_LOG_DEBUG("KQSET-RECUSA q6_K: offset=%zu size=%zu nbytes=%zu (partial rejeitado!)""" + BSN + """",
                               offset, size, (size_t) ggml_nbytes(tensor));
                break;
            }
            GGML_LOG_DEBUG("KQSET-REPACK q6_K: chamando repack_q6_K_tiled!""" + BSN + """");
            repack_q6_K_tiled(tensor, data, offset, size);
            break;

"""
    t = t[:i6b] + novo6 + t[idef:]
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "R24g ok")
